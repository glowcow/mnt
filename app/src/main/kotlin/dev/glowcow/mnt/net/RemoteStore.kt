package dev.glowcow.mnt.net

import dev.glowcow.mnt.files.Facts
import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * A place reached through a library that blocks and throws: each question goes off the main thread,
 * and one that fails answers "no" and leaves its reason in [error]. Paths start at the place's root, `/`.
 */
abstract class RemoteStore : FileStore {
    /** Why the last thing asked could not be done; null when it could. */
    @Volatile
    var error: String? = null
        private set

    protected abstract fun listing(path: String): List<Node>

    /** The file or folder at [path]; null when there is none. */
    protected abstract fun node(path: String): Node?

    protected abstract fun makeFolder(path: String)

    protected abstract fun move(from: String, to: String)

    protected abstract fun delete(path: String)

    protected open fun touch(path: String, time: Long) = Unit

    protected abstract fun input(path: String, offset: Long): InputStream

    protected abstract fun output(path: String, size: Long): OutputStream

    /** Lets go of the connection; the next question opens it again. */
    open fun close() = Unit

    private suspend fun <T> ask(work: () -> T): T? = withContext(Dispatchers.IO) {
        try {
            work().also { error = null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            null
        }
    }

    override suspend fun list(path: String): List<Node>? = ask { listing(path) }

    override suspend fun stat(path: String): Node? = if (path == ROOT) Node(ROOT, "", true, 0, 0) else ask { node(path) }

    override suspend fun facts(path: String): Facts? = stat(path)?.let { Facts() }

    override suspend fun mkdir(path: String): Boolean = ask { makeFolder(path) } != null

    override suspend fun rename(from: String, to: String): Boolean = ask { move(from, to) } != null

    override suspend fun remove(path: String): Boolean = ask { delete(path) } != null

    override suspend fun setModified(path: String, time: Long) {
        ask { touch(path, time) }
    }

    override fun read(path: String, offset: Long): InputStream = input(path, offset)

    override fun write(path: String, size: Long): OutputStream = output(path, size)

    protected companion object {
        const val ROOT = "/"

        fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

        fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { ROOT }

        fun child(folder: String, name: String): String = if (folder.endsWith('/')) folder + name else "$folder/$name"
    }
}
