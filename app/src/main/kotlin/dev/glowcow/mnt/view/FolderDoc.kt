package dev.glowcow.mnt.view

import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.view.html.Look
import java.io.ByteArrayInputStream

/**
 * A page made of a file that refers to the files beside it, as Markdown and HTML do: the page is `/f/<its name>`,
 * so a picture next to it is `/f/<that name>` and is read from the store. [body] makes the page; null serves the file as it is.
 */
class FolderDoc(
    private val files: FileStore,
    private val path: String,
    private val page: ((Look) -> String)?,
    private val raw: ByteArray? = null,
) : WebDoc() {
    private val folder = Paths.parent(path)
    private val self = "/f/" + encode(Paths.name(path))

    override fun path(part: Int) = self

    override val own: Boolean get() = page != null

    override fun serve(path: String, look: Look): Served? {
        if (path == self || decode(path) == decode(self)) {
            page?.let { return Served("text/html", it(look).byteInputStream()) }
            return Served("text/html", ByteArrayInputStream(raw ?: ByteArray(0)))
        }
        val target = file(path) ?: return null
        return runCatching { Served(mime(target), files.read(target)) }.getOrNull()
    }

    override fun file(path: String): String? {
        if (!path.startsWith("/f/")) return null
        val relative = decode(path.removePrefix("/f/"))
        // `..` steps out of the folder, as a link from a page to its parent's files does.
        val steps = folder.trimEnd('/').split('/').filter { it.isNotEmpty() }.toMutableList()
        relative.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (!folder.startsWith("//") || steps.size > 1) steps.removeLastOrNull()
                else -> steps += it
            }
        }
        // A server's path keeps its `//<id>` in front; `..` never steps above it.
        val remote = folder.startsWith("//")
        if (remote && steps.isEmpty()) return null
        return (if (remote) "//" else "/") + steps.joinToString("/")
    }

    companion object {
        fun encode(name: String): String = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

        /** Undoes `%XX` and nothing else: a `+` in a path is a plus. */
        fun decode(path: String): String {
            if ('%' !in path) return path
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < path.length) {
                val c = path[i]
                if (c == '%' && i + 2 < path.length) {
                    val v = path.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) {
                        bytes.write(v)
                        i += 3
                        continue
                    }
                }
                bytes.write(c.toString().toByteArray())
                i++
            }
            return bytes.toString("UTF-8")
        }
    }
}
