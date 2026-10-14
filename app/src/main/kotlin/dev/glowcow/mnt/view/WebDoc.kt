package dev.glowcow.mnt.view

import dev.glowcow.mnt.view.doc.Converted
import dev.glowcow.mnt.view.html.Look
import dev.glowcow.mnt.view.html.Page
import java.io.Closeable
import java.io.InputStream

/** A page or a file a page refers to, as the viewer's web view is given it. */
class Served(val mime: String, val stream: InputStream)

/**
 * What the viewer's web view shows: pages served from [Page.ORIGIN] and what they refer to.
 * [parts] are the sheets or chapters to choose among, empty when there is one page.
 */
abstract class WebDoc : Closeable {
    open val parts: List<String> get() = emptyList()

    /** The path of the page of [part]. */
    abstract fun path(part: Int): String

    /** The part a page at [path] belongs to; -1 when none does. */
    open fun part(path: String): Int = if (parts.isEmpty()) 0 else -1

    /** The page or the file at [path]; null when there is none. */
    abstract fun serve(path: String, look: Look): Served?

    /** The file of the store a link at [path] leads to, to be opened in a viewer of its own; null when it leads nowhere there. */
    open fun file(path: String): String? = null

    /** Whether a link to [path] stays in this page's web view, as the chapters of a book do. */
    open fun stays(path: String): Boolean = false

    /** Pages laid out with a script of the app's own, as the text over the pictures of a DjVu. */
    open val scripted: Boolean get() = false

    override fun close() = Unit

    companion object {
        fun mime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "html", "htm", "xhtml", "xml" -> "text/html"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}

/** A document turned into pages of HTML, each of them at `/doc/<n>`, its pictures at `/a/<name>`. */
open class ConvertedDoc(private val converted: Converted) : WebDoc() {
    override val parts: List<String> get() = if (converted.pages.size > 1) converted.pages.map { it.name } else emptyList()

    override fun path(part: Int) = "/doc/$part"

    override fun part(path: String): Int = path.removePrefix("/doc/").toIntOrNull() ?: -1

    override fun serve(path: String, look: Look): Served? {
        if (path.startsWith("/doc/")) {
            val page = converted.pages.getOrNull(part(path)) ?: converted.pages.firstOrNull() ?: Converted.Part("", "")
            return Served("text/html", Page.html(page.body, look).byteInputStream())
        }
        if (path.startsWith("/a/")) {
            val name = FolderDoc.decode(path.removePrefix("/a/"))
            return converted.asset(name)?.let { Served(mime(name), it.inputStream()) }
        }
        return null
    }

    override fun close() = converted.close()
}
