package dev.glowcow.mnt.archive

import dev.glowcow.mnt.files.Facts
import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.Remote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The files of the archive at [archive], read from [files], as a place that is only read. What it holds is read
 * once and kept until the archive changes — in memory, and in [cache] when reading it took a while; a file is read
 * by going through the archive up to it. [open] starts a [Walk] through an archive of a size, read as the
 * [Formats] say, telling [open]'s last argument how far it has read. More than [limit] entries is refused; a
 * stream that would take [slowAfter] seconds or more is asked about before it is read through; a list that took
 * [saveAfter] ms is kept.
 */
class ArchiveStore(
    private val archive: String,
    private val files: FileStore,
    private val cache: File? = null,
    private val limit: Int = entriesLimit(),
    private val slowAfter: Long = SLOW_SECONDS,
    private val saveAfter: Long = SAVE_MS,
    private val open: (archive: String, size: Long, formats: Formats, heard: ((Float) -> Unit)?) -> Walk,
) : FileStore {
    private val lock = Mutex()

    @Volatile
    private var index: ArchiveIndex? = null

    @Volatile
    private var known: Node? = null

    // Said yes to reading a large stream through.
    @Volatile
    private var agreed = false

    private val _reading = MutableStateFlow<Float?>(null)

    /** How far the archive has been read through to learn what it holds, 0–1; null while it is not being read. */
    val reading: StateFlow<Float?> = _reading

    private val _reaching = MutableStateFlow<Float?>(null)

    /** How far the archive has been gone through on the way to a file asked for, 0–1; null while none is. */
    val reaching: StateFlow<Float?> = _reaching

    /** Why the archive could not be read the last time. */
    @Volatile
    var error: ArchiveException? = null
        private set

    // A file compressed whole that turns out not to be readable as an archive is read as the one file it holds.
    @Volatile
    private var formats = if (Archives.raw(Paths.name(archive))) Formats.ANY else Formats.PACKED

    /**
     * About how many seconds learning what the archive holds will take, when that means reading a stream through
     * for long enough to ask first; null when it is quick, already known or already agreed to.
     */
    suspend fun slow(): Long? {
        if (agreed || !Archives.streamed(Paths.name(archive))) return null
        val node = files.stat(archive) ?: return null
        if (index != null && known?.size == node.size && known?.modified == node.modified) return null
        // A server gives a stream more slowly than a phone unpacks it.
        val rate = Archives.rate(node.name).let { if (Remote.id(archive) != null) minOf(it, FAR_RATE) else it }
        val seconds = node.size / rate + 1
        if (seconds < slowAfter) return null
        if (withContext(Dispatchers.IO) { saved(node) } != null) return null
        return seconds
    }

    /** Reading the archive through is agreed to, however long it takes. */
    fun agree() {
        agreed = true
    }

    /** What the archive holds; null when it is gone or cannot be read. */
    suspend fun index(): ArchiveIndex? = lock.withLock {
        val node = files.stat(archive)?.takeIf { !it.directory } ?: return null
        val kept = index
        if (kept != null && known?.size == node.size && known?.modified == node.modified) return kept
        index = null
        val read = try {
            withContext(Dispatchers.IO) { saved(node) ?: walk(node) }
        } catch (e: ArchiveException) {
            error = e
            null
        } catch (e: IOException) {
            error = ArchiveException(Reason.BROKEN, e.message)
            null
        } finally {
            _reading.value = null
        }
        if (read != null) error = null
        known = node
        index = read
        read
    }

    private suspend fun walk(node: Node): ArchiveIndex {
        val started = System.nanoTime()
        val read = try {
            through(node)
        } catch (e: ArchiveException) {
            // An archive inside a compressed file that a stream cannot give (a 7z gzipped): the file it is, then.
            if (formats != Formats.ANY || e.reason != Reason.BROKEN && e.reason != Reason.UNKNOWN) throw e
            formats = Formats.COMPRESSED
            through(node)
        }
        if ((System.nanoTime() - started) / 1_000_000 >= saveAfter) save(node, read)
        return read
    }

    // How far it has got goes by the bytes of the archive read, not by its entries: one entry may be all of it.
    private suspend fun through(node: Node): ArchiveIndex {
        var shown = 0f
        _reading.value = 0f
        return open(archive, node.size, formats) { done ->
            if (done - shown >= STEP) {
                shown = done
                _reading.value = done
            }
        }.use { walk -> ArchiveIndex.read(walk, Archives.stem(node.name), node.modified, limit) }
    }

    // What the archive held when it was this size and this old, as written down after a long read.
    private fun saved(node: Node): ArchiveIndex? {
        val file = cached() ?: return null
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != SAVED || input.readUTF() != archive || input.readLong() != node.size || input.readLong() != node.modified) return@use null
                val read = Formats.entries.getOrNull(input.readByte().toInt()) ?: return@use null
                ArchiveIndex.read(input)?.also { formats = read }
            }
        }.getOrNull()
    }

    private fun save(node: Node, index: ArchiveIndex) {
        val file = cached() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            DataOutputStream(part.outputStream().buffered()).use { out ->
                out.writeUTF(SAVED)
                out.writeUTF(archive)
                out.writeLong(node.size)
                out.writeLong(node.modified)
                out.writeByte(formats.ordinal)
                index.write(out)
            }
            part.renameTo(file)
            // The archives read last are kept, the rest let go.
            file.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEPT)?.forEach { it.delete() }
        }
    }

    private fun cached(): File? = cache?.let { dir ->
        val hash = MessageDigest.getInstance("SHA-256").digest(archive.toByteArray()).take(HASH).joinToString("") { "%02x".format(it) }
        File(dir, hash)
    }

    override suspend fun list(path: String): List<Node>? = index()?.children(path)?.map(Item::node)

    override suspend fun stat(path: String): Node? = index()?.item(path)?.node()

    override suspend fun facts(path: String): Facts? = null

    override suspend fun mkdir(path: String): Boolean = false

    override suspend fun rename(from: String, to: String): Boolean = false

    override suspend fun remove(path: String): Boolean = false

    override suspend fun setModified(path: String, time: Long) = Unit

    override fun renames(path: String): Boolean = false

    override fun writes(path: String): Boolean = false

    override fun write(path: String, size: Long): OutputStream = throw IOException("An archive is only read")

    // Blocking, like any read: what the archive holds is learnt first, if it is not yet known. The way to the
    // file is told in [reaching] until its first bytes come.
    override fun read(path: String, offset: Long): InputStream {
        val item = file(runBlocking { index() }, path)
        var arrived = false
        var shown = -1f
        val walk = open(archive, known?.size ?: 0, formats) { done ->
            if (!arrived && done - shown >= STEP) {
                shown = done
                _reaching.value = done
            }
        }
        val came = {
            arrived = true
            _reaching.value = null
        }
        try {
            reach(walk, item.ordinal)
            return Data(walk, owned = true, came).also { if (offset > 0) it.skipNBytes(offset) }
        } catch (e: Throwable) {
            came()
            walk.close()
            throw e
        }
    }

    // One pass through the archive, however many of its files are asked for.
    override suspend fun readEach(paths: List<String>, each: suspend (path: String, input: InputStream) -> Unit) {
        val index = index() ?: return
        val wanted = paths.mapNotNull { path -> index.item(path)?.takeIf { !it.directory && !it.locked }?.let { path to it.ordinal } }
        if (wanted.isEmpty()) return
        val byEntry = wanted.groupBy({ it.second }, { it.first })
        val walk = withContext(Dispatchers.IO) { open(archive, known?.size ?: 0, formats, null) }
        // Hard links to one entry: the first takes its data, the others read it again.
        val again = ArrayList<String>()
        walk.use {
            var ordinal = -1
            var left = byEntry.size
            while (left > 0) {
                currentCoroutineContext().ensureActive()
                runInterruptible(Dispatchers.IO) { walk.next() } ?: break
                ordinal++
                val those = byEntry[ordinal] ?: continue
                left--
                each(those.first(), Data(walk, owned = false) {})
                again += those.drop(1)
            }
        }
        if (again.isNotEmpty()) super.readEach(again, each)
    }

    private fun file(index: ArchiveIndex?, path: String): Item {
        index ?: throw error ?: FileNotFoundException(path)
        val item = index.item(path)?.takeIf { !it.directory } ?: throw FileNotFoundException(path)
        if (item.locked) throw ArchiveException(Reason.LOCKED)
        return item
    }

    private fun reach(walk: Walk, ordinal: Int) {
        repeat(ordinal + 1) { walk.next() ?: throw ArchiveException(Reason.BROKEN, "The archive ends early") }
    }

    /** The data of the entry [walk] stands at; [came] hears once that it began, or was let go; closing it lets go of the walk when it is [owned]. */
    private class Data(private val walk: Walk, private val owned: Boolean, private val came: () -> Unit) : InputStream() {
        private val one = ByteArray(1)
        private var begun = false

        override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            return walk.read(b, off, len).also { begin() }
        }

        override fun skip(n: Long): Long {
            val buffer = ByteArray(minOf(n, SKIP_BUFFER.toLong()).toInt().coerceAtLeast(1))
            var skipped = 0L
            while (skipped < n) {
                val got = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
                if (got < 0) break
                skipped += got
            }
            return skipped
        }

        override fun close() {
            begin()
            if (owned) walk.close()
        }

        private fun begin() {
            if (begun) return
            begun = true
            came()
        }
    }

    companion object {
        private const val STEP = 0.01f
        private const val SKIP_BUFFER = 256 * 1024

        // A list read this long is written down; this many of them are kept.
        private const val SAVE_MS = 500L
        private const val KEPT = 32
        private const val HASH = 16
        private const val SAVED = "mnt archive list 2"

        // A stream that takes this many seconds to read through is asked about first.
        const val SLOW_SECONDS = 15L

        // What a server gives, bytes a second, at best.
        private const val FAR_RATE = 40L shl 20

        // What one entry of a list takes in memory, at most, with the rows a folder of it makes.
        private const val ENTRY_BYTES = 200L

        /** How many entries an archive may have: what a quarter of the app's memory holds. */
        fun entriesLimit(): Int = (Runtime.getRuntime().maxMemory() / 4 / ENTRY_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
