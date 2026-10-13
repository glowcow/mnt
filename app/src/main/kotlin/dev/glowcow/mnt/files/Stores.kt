package dev.glowcow.mnt.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Paths of what does not lie in the phone's file system: `//<id>` is the root of a place, `//<id>/a/b` a file in it. */
object Remote {
    fun root(id: String): String = "//$id"

    /** The place [path] lies in; null for a path of the phone's own file system. */
    fun id(path: String): String? = if (path.startsWith("//")) path.substring(2).substringBefore('/').takeIf { it.isNotEmpty() } else null

    /** [path] as the place itself knows it, from its own root `/`. */
    fun inner(path: String): String = "/" + path.substring(2).substringAfter('/', "")

    fun outer(id: String, inner: String): String = if (inner == "/") root(id) else root(id) + inner
}

/**
 * Every place files are kept in as one store: the phone's file system under its own paths and each
 * place of [remote] under `//<id>`. Nothing is renamed from one place into another — that is a copy.
 */
class Stores(private val local: FileStore, private val remote: (id: String) -> FileStore?) : FileStore {
    private class At(val store: FileStore, val id: String?, val path: String) {
        fun out(node: Node): Node = if (id == null) node else node.copy(path = Remote.outer(id, node.path))
    }

    private fun find(path: String): At? {
        val id = Remote.id(path) ?: return At(local, null, path)
        return remote(id)?.let { At(it, id, Remote.inner(path)) }
    }

    // Finding a place may open it, which is not for the main thread.
    private suspend fun at(path: String): At? = if (Remote.id(path) == null) At(local, null, path) else withContext(Dispatchers.IO) { find(path) }

    private fun open(path: String): At = find(path) ?: throw IOException("No such place")

    override suspend fun list(path: String): List<Node>? = at(path)?.run { store.list(this.path)?.map(::out) }

    override suspend fun stat(path: String): Node? = at(path)?.run { store.stat(this.path)?.let(::out) }

    override suspend fun facts(path: String): Facts? = at(path)?.run { store.facts(this.path) }

    override suspend fun mkdir(path: String): Boolean = at(path)?.run { store.mkdir(this.path) } ?: false

    override suspend fun rename(from: String, to: String): Boolean {
        val source = at(from) ?: return false
        val target = at(to) ?: return false
        return source.store === target.store && source.store.rename(source.path, target.path)
    }

    override suspend fun remove(path: String): Boolean = at(path)?.run { store.remove(this.path) } ?: false

    override suspend fun setModified(path: String, time: Long) {
        at(path)?.run { store.setModified(this.path, time) }
    }

    override fun renames(path: String): Boolean = find(path)?.run { store.renames(this.path) } ?: true

    override fun read(path: String, offset: Long): InputStream = open(path).run { store.read(this.path, offset) }

    override fun write(path: String, size: Long): OutputStream = open(path).run { store.write(this.path, size) }
}
