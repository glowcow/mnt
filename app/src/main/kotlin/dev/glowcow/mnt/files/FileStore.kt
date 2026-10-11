package dev.glowcow.mnt.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

    /** Blocking: read off the main thread. */
    fun read(path: String): InputStream

    /** Blocking: write off the main thread. */
    fun write(path: String): OutputStream
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
            accessed = known.lastAccessTime().toMillis(),
            mode = posix?.permissions()?.fold(0) { bits, permission -> bits or (OWNER_READ shr permission.ordinal) },
            owner = posix?.owner()?.name,
            group = posix?.group()?.name,
        )
    }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) { File(path).mkdir() }

    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.ATOMIC_MOVE) }.isSuccess
    }

    override suspend fun remove(path: String): Boolean = withContext(Dispatchers.IO) { File(path).delete() }

    override suspend fun setModified(path: String, time: Long) {
        withContext(Dispatchers.IO) { File(path).setLastModified(time) }
    }

    override fun read(path: String): InputStream = File(path).inputStream()

    override fun write(path: String): OutputStream = File(path).outputStream()

    // One question to the file system for all of it: on a phone each one costs a trip through FUSE.
    private fun File.node(): Node? {
        val known = runCatching { Files.readAttributes(toPath(), BasicFileAttributes::class.java) }.getOrNull() ?: return null
        return Node(path, name, known.isDirectory, if (known.isDirectory) 0 else known.size(), known.lastModifiedTime().toMillis())
    }

    private companion object {
        const val OWNER_READ = 0x100
    }
}
