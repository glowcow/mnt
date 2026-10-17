package dev.glowcow.mnt.archive

import dev.glowcow.mnt.files.FileStore
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Why an archive, or a file in it, cannot be read; the first four are the native reader's. */
enum class Reason { BROKEN, LOCKED, MEMORY, UNKNOWN, TOO_MANY }

class ArchiveException(val reason: Reason, detail: String? = null) : IOException(detail ?: reason.name) {
    // Made by the native reader, with libarchive's words as it wrote them.
    @Suppress("unused")
    private constructor(reason: Int, detail: ByteArray?) : this(Reason.entries.getOrElse(reason) { Reason.BROKEN }, detail?.decodeToString())
}

/** One entry of an archive as it is stored: its name in the archive's own bytes, and what it is. */
class Header(
    val name: ByteArray,
    val kind: EntryKind,
    /** Bytes; -1 when the archive does not tell before the data is read. */
    val size: Long,
    /** Milliseconds since the epoch; 0 when not known. */
    val modified: Long,
    /** Its data is enciphered with a password. */
    val locked: Boolean,
    /** Written by a zip tool, which may part names with backslashes. */
    val zip: Boolean,
    /** Only compressed, not packed: the one entry is the whole of the data, with no name of its own. */
    val raw: Boolean,
    /** The entry a hard link stands for. */
    val link: ByteArray? = null,
)

enum class EntryKind { FILE, FOLDER, SYMLINK, HARDLINK, OTHER }

/** An archive read from its start, entry after entry; the data of the entry last stepped to is read with [read]. */
interface Walk : Closeable {
    /** Steps to the next entry, past whatever was not read of this one; null at the end. */
    fun next(): Header?

    /** Bytes of the current entry's data into [buffer]; -1 at its end. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** How much of the archive has been gone through, 0–1. */
    val done: Float
}

/** What an archive is read from: blocks one after another, and jumps, as the native reader asks for them. */
abstract class ArchiveSource : Closeable {
    abstract val size: Long

    /** Where the next block starts. */
    @Volatile
    var position = 0L
        protected set

    /** Hears how much of the source has been read, 0–1, as blocks are taken. */
    var heard: ((Float) -> Unit)? = null

    /** The block the native reader asks for. A thread interrupted — a page left — stops the reading there. */
    fun read(block: ByteArray): Int {
        if (Thread.interrupted()) throw InterruptedException()
        val n = fill(block)
        heard?.invoke(if (size > 0) position.toFloat() / size else 1f)
        return n
    }

    /** Fills [block] from [position] on: how many bytes, 0 at the end. */
    protected abstract fun fill(block: ByteArray): Int

    /** Goes to [offset] from the start, from where it stands or from the end (as `lseek`); where it now stands. */
    fun seek(offset: Long, whence: Int): Long {
        position = when (whence) {
            SEEK_CUR -> position + offset
            SEEK_END -> size + offset
            else -> offset
        }.coerceIn(0, size)
        return position
    }

    private companion object {
        const val SEEK_CUR = 1
        const val SEEK_END = 2
    }
}

/** An archive on the phone's own storage, read where it is asked. */
class FileSource(file: File) : ArchiveSource() {
    private val access = RandomAccessFile(file, "r")
    override val size: Long = access.length()

    override fun fill(block: ByteArray): Int {
        val n = access.channel.read(ByteBuffer.wrap(block), position)
        if (n <= 0) return 0
        position += n
        return n
    }

    override fun close() = access.close()
}

/** An archive in another place: one stream, opened again where a read jumps back or far ahead. */
class StoreSource(private val files: FileStore, private val path: String, override val size: Long) : ArchiveSource() {
    private var stream: InputStream? = null
    private var at = 0L

    override fun fill(block: ByteArray): Int {
        if (position >= size) return 0
        val ahead = position - at
        if (stream == null || ahead < 0 || ahead > SKIP_AHEAD) {
            stream?.close()
            stream = files.read(path, position)
            at = position
        }
        val input = stream!!
        while (at < position) {
            val skipped = input.read(block, 0, minOf(block.size.toLong(), position - at).toInt())
            if (skipped < 0) return 0
            at += skipped
        }
        var n = 0
        while (n < block.size) {
            val got = input.read(block, n, block.size - n)
            if (got < 0) break
            n += got
        }
        at += n
        position += n
        return n
    }

    override fun close() {
        stream?.close()
    }

    private companion object {
        // Reading on is cheaper than asking the place for the file again.
        const val SKIP_AHEAD = 1L shl 20
    }
}

/** libarchive reading [source]; [raw] lets a file that is only compressed read as one entry. */
class ArchiveReader(private val source: ArchiveSource, raw: Boolean) : Walk {
    private var handle: Long
    private val facts = LongArray(FACTS)

    init {
        handle = try {
            open(source, raw)
        } catch (e: Throwable) {
            source.close()
            throw e
        }
        if (handle == 0L) {
            source.close()
            throw ArchiveException(Reason.MEMORY)
        }
    }

    override val done: Float get() = if (source.size > 0) source.position.toFloat() / source.size else 1f

    override fun next(): Header? {
        val name = next(handle, facts) ?: return null
        val kind = EntryKind.entries.getOrElse(facts[0].toInt()) { EntryKind.OTHER }
        val family = facts[4].toInt() and FAMILY
        return Header(
            name = name,
            kind = kind,
            size = facts[1],
            modified = facts[2],
            locked = facts[3] != 0L,
            zip = family == FORMAT_ZIP,
            raw = family == FORMAT_RAW,
            link = if (kind == EntryKind.HARDLINK) link(handle) else null,
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read(handle, buffer, offset, length)

    override fun close() {
        if (handle != 0L) close(handle)
        handle = 0
        source.close()
    }

    companion object {
        init {
            System.loadLibrary("mntarchive")
        }

        /** No decoder may take more than [bytes] for its dictionary: one that would is refused instead of running the phone out of memory. */
        fun limitMemory(bytes: Long) = limit(bytes)

        private const val FACTS = 5

        // libarchive's format codes: the family is the top bits.
        private const val FAMILY = 0xff0000
        private const val FORMAT_ZIP = 0x50000
        private const val FORMAT_RAW = 0x90000

        @JvmStatic private external fun limit(bytes: Long)

        @JvmStatic private external fun open(source: ArchiveSource, raw: Boolean): Long

        @JvmStatic private external fun next(handle: Long, facts: LongArray): ByteArray?

        @JvmStatic private external fun link(handle: Long): ByteArray?

        @JvmStatic private external fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int

        @JvmStatic private external fun close(handle: Long)
    }
}
