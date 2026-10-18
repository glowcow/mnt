package dev.glowcow.mnt.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes

/** A place that holds files, addressed by absolute paths. */
interface FileStore {
    /** What the folder at [path] holds; null when it cannot be read. */
    suspend fun list(path: String): List<Node>?

    /** The file or folder at [path]; null when there is none. */
    suspend fun stat(path: String): Node?

    /** What more is known of the file or folder at [path]; null when there is none. */
    suspend fun facts(path: String): Facts?

    suspend fun mkdir(path: String): Boolean

    /** Moves [from] to [to] without copying, over a file that is there; false when that cannot be done. */
    suspend fun rename(from: String, to: String): Boolean

    /** Removes a file or an empty folder. */
    suspend fun remove(path: String): Boolean

    suspend fun setModified(path: String, time: Long)

    /** False where a file cannot be renamed once written: it is then written straight under its own name. */
    fun renames(path: String): Boolean = true

    /** False where nothing can be written, renamed or removed: inside an archive. */
    fun writes(path: String): Boolean = true

    /** Blocking: a small picture of the file the place itself keeps, as JPEG; null when it keeps none. */
    fun thumbnail(path: String): ByteArray? = null

    /** Bytes free where [path] lies; null when that is not known. */
    fun free(path: String): Long? = null

    /**
     * Hands each file of [paths] to [each] with its stream, in the order the store reads them fastest: an archive
     * packed as one stream gives them all in one pass. What cannot be opened is left out.
     */
    suspend fun readEach(paths: List<String>, each: suspend (path: String, input: InputStream) -> Unit) {
        for (path in paths) {
            val input = try {
                withContext(Dispatchers.IO) { read(path) }
            } catch (_: IOException) {
                continue
            }
            input.use { each(path, it) }
        }
    }

    /** Blocking: read off the main thread. The stream starts [offset] bytes into the file. */
    fun read(path: String, offset: Long = 0): InputStream

    /** Blocking: write off the main thread. [size] is how many bytes will come, or -1 when that is not known. */
    fun write(path: String, size: Long = -1): OutputStream
}

/**
 * The phone's own file system: the shared storage and the drives mounted into it.
 * [born] tells when a file came to be where the file system itself keeps no such time.
 */
class LocalStore(private val born: (String) -> Long? = { null }) : FileStore {
    override suspend fun list(path: String): List<Node>? = withContext(Dispatchers.IO) {
        File(path).listFiles()?.mapNotNull { it.node() }
    }

    override suspend fun stat(path: String): Node? = withContext(Dispatchers.IO) { File(path).node() }

    override suspend fun facts(path: String): Facts? = withContext(Dispatchers.IO) {
        val file = File(path).toPath()
        val posix = runCatching { Files.readAttributes(file, PosixFileAttributes::class.java) }.getOrNull()
        val known = posix ?: runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }.getOrNull() ?: return@withContext null
        val modified = known.lastModifiedTime().toMillis()
        Facts(
            // A file system with no time of birth answers with the last change instead.
            created = born(path) ?: known.creationTime().toMillis().takeIf { it != modified },
            mode = posix?.permissions()?.fold(0) { bits, permission -> bits or (OWNER_READ shr permission.ordinal) },
            owner = posix?.owner()?.name,
            group = posix?.group()?.name,
        )
    }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) { File(path).mkdir() }

    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        if (!from.equals(to, ignoreCase = true) || from == to) return@withContext move(from, to)
        // Where case does not tell names apart a change of case alone is taken for no change: it goes by way of a third name.
        val spare = generateSequence(0) { it + 1 }.map { "$to.$it.rename" }.first { !File(it).exists() }
        if (!move(from, spare)) return@withContext false
        move(spare, to).also { if (!it) move(spare, from) }
    }

    private fun move(from: String, to: String): Boolean =
        runCatching { Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.ATOMIC_MOVE) }.isSuccess

    override suspend fun remove(path: String): Boolean = withContext(Dispatchers.IO) { File(path).delete() }

    override suspend fun setModified(path: String, time: Long) {
        withContext(Dispatchers.IO) { File(path).setLastModified(time) }
    }

    override fun read(path: String, offset: Long): InputStream = File(path).inputStream().also { if (offset > 0) it.channel.position(offset) }

    override fun write(path: String, size: Long): OutputStream = File(path).outputStream()

    // The room files can take now: what the system could clear from caches is not counted on.
    @Suppress("UsableSpace")
    override fun free(path: String): Long? = generateSequence(File(path)) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace

    // One question to the file system for all of it: on a phone each one costs a trip through FUSE.
    private fun File.node(): Node? {
        val known = runCatching { Files.readAttributes(toPath(), BasicFileAttributes::class.java) }.getOrNull() ?: return null
        return Node(path, name, known.isDirectory, if (known.isDirectory) 0 else known.size(), known.lastModifiedTime().toMillis())
    }

    private companion object {
        const val OWNER_READ = 0x100
    }
}
