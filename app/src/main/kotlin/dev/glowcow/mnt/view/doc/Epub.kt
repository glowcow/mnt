package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.FolderDoc
import dev.glowcow.mnt.view.Served
import dev.glowcow.mnt.view.WebDoc
import dev.glowcow.mnt.view.html.Look
import dev.glowcow.mnt.view.html.Page
import java.io.File

/**
 * An EPUB book: its chapters as the book lays them out, each at `/e/<its path in the book>`, in the app's colours.
 * [contents] is the table of contents; the parts to step through are the chapters of the spine.
 */
class Epub(file: File) : WebDoc() {
    private val pkg = Package(file)
    private val spine: List<String>
    val title: String?

    /** The table of contents: a title, the path of what it leads to, and how deep it stands. */
    val contents: List<Triple<String, String, Int>>

    init {
        try {
            val opf = pkg.xml("META-INF/container.xml")?.find("rootfile")?.attr("full-path") ?: pkg.names.firstOrNull { it.endsWith(".opf") } ?: error("No package document")
            val root = pkg.xml(opf) ?: error("No package document")
            val manifest = root.find("manifest")?.children("item").orEmpty().associateBy { it.attr("id").orEmpty() }
            spine = root.find("spine")?.children("itemref").orEmpty()
                .filter { it.attr("linear") != "no" }
                .mapNotNull { manifest[it.attr("idref")]?.attr("href")?.let { href -> resolve(opf, FolderDoc.decode(href)) } }
            title = root.find("title")?.text()?.trim()?.ifEmpty { null }
            val nav = manifest.values.firstOrNull { it.attr("properties").orEmpty().split(' ').contains("nav") }?.attr("href")?.let { resolve(opf, FolderDoc.decode(it)) }
            val ncx = root.find("spine")?.attr("toc")?.let { manifest[it]?.attr("href") }?.let { resolve(opf, FolderDoc.decode(it)) }
            contents = nav?.let { navigation(it) }?.takeIf { it.isNotEmpty() } ?: ncx?.let { ncx(it) }.orEmpty()
        } catch (e: Exception) {
            pkg.close()
            throw e
        }
    }

    // EPUB 3: the list of links in the <nav> of the table of contents.
    private fun navigation(path: String): List<Triple<String, String, Int>> {
        val doc = pkg.xml(path) ?: return emptyList()
        val toc = doc.all("nav").firstOrNull { (it.attr("type") ?: "").contains("toc") } ?: doc.find("nav") ?: return emptyList()
        val out = ArrayList<Triple<String, String, Int>>()
        fun list(ol: El, depth: Int) {
            for (li in ol.children("li")) {
                val a = li.child("a") ?: li.child("span")
                val href = a?.attr("href")
                if (a != null) out += Triple(a.text().trim().replace(Regex("\\s+"), " "), href?.let { resolve(path, FolderDoc.decode(it)) }.orEmpty(), depth)
                li.child("ol")?.let { list(it, depth + 1) }
            }
        }
        toc.find("ol")?.let { list(it, 0) }
        return out.filter { it.second.isNotEmpty() }
    }

    // EPUB 2: the points of the NCX navigation map.
    private fun ncx(path: String): List<Triple<String, String, Int>> {
        val map = pkg.xml(path)?.find("navMap") ?: return emptyList()
        val out = ArrayList<Triple<String, String, Int>>()
        fun points(parent: El, depth: Int) {
            for (point in parent.children("navPoint")) {
                val label = point.find("text")?.text()?.trim().orEmpty()
                val src = point.child("content")?.attr("src")
                if (src != null) out += Triple(label, resolve(path, FolderDoc.decode(src)), depth)
                points(point, depth + 1)
            }
        }
        points(map, 0)
        return out
    }

    override val parts: List<String>
        get() = spine.mapIndexed { i, path -> contents.firstOrNull { it.second.substringBefore('#') == path }?.first ?: "${i + 1}" }

    override fun path(part: Int): String = "/e/" + spine.getOrElse(part) { spine.firstOrNull().orEmpty() }.split('/').joinToString("/") { FolderDoc.encode(it) }

    override fun part(path: String): Int = spine.indexOf(FolderDoc.decode(path.removePrefix("/e/")))

    /** The path a link of the table of contents leads to, fragment and all. */
    fun link(target: String): String = "/e/" + target.substringBefore('#').split('/').joinToString("/") { FolderDoc.encode(it) } + target.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }

    override fun stays(path: String): Boolean = path.startsWith("/e/")

    override fun serve(path: String, look: Look): Served? {
        if (!path.startsWith("/e/")) return null
        val name = FolderDoc.decode(path.removePrefix("/e/"))
        val bytes = pkg.bytes(name) ?: return null
        val mime = mime(name)
        if (mime != "text/html") return Served(mime, bytes.inputStream())
        // A chapter of XHTML is read as XML, or a `<a/>` would swallow what follows it.
        val xml = !name.endsWith(".html", true) && !name.endsWith(".htm", true)
        return Served(if (xml) "application/xhtml+xml" else "text/html", restyle(String(bytes, Charsets.UTF_8), look).byteInputStream())
    }

    // The book keeps its layout; its colours, its margins and the size of its pictures are the app's.
    private fun restyle(page: String, look: Look): String {
        val style = "<style>" + Page.css(look) +
            "html,body{background:var(--bg)!important;color:var(--fg)!important}" +
            "body *{color:inherit!important;background-color:transparent!important;border-color:var(--line)!important}" +
            "body a{color:var(--accent)!important}img,svg,video{max-width:100%!important;height:auto!important}" +
            "body{margin:0!important;padding:12px 16px 32px!important;max-width:none!important}</style>" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
        val head = Regex("</head>", RegexOption.IGNORE_CASE).find(page)
        return if (head != null) page.substring(0, head.range.first) + style + page.substring(head.range.first) else style + page
    }

    override fun close() = pkg.close()
}
