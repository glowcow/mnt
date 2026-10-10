package dev.glowcow.mnt.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** A place that holds files, addressed by absolute paths. */
interface FileStore {
    /** What the folder at [path] holds; null when it cannot be read. */
    suspend fun list(path: String): List<Node>?

    /** The file or folder at [path]; null when there is none. */
    suspend fun stat(path: String): Node?

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

/** The phone's own file system: the shared storage and the drives mounted into it. */
class LocalStore : FileStore {
    override suspend fun list(path: String): List<Node>? = withContext(Dispatchers.IO) {
        File(path).listFiles()?.mapNotNull { it.node() }
    }

    override suspend fun stat(path: String): Node? = withContext(Dispatchers.IO) { File(path).node() }

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
}
