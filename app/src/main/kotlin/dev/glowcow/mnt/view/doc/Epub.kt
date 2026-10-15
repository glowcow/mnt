package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.FolderDoc
import dev.glowcow.mnt.view.Served
import dev.glowcow.mnt.view.WebDoc
import dev.glowcow.mnt.view.code.each
import dev.glowcow.mnt.view.html.Look
import dev.glowcow.mnt.view.html.Page
import java.io.File

/**
 * An EPUB book: its chapters one after another on sheets the size of the screen, in the app's colours; what they
 * show at `/e/<its path in the book>`. [contents] is the table of contents, [parts] the chapters of the spine.
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

    // The whole book is one page, `/book`; a chapter starts at `#c<n>`, what it names at `#c<n>-<id>`.
    override fun path(part: Int): String = if (part <= 0) BOOK else "$BOOK#c$part"

    override fun part(path: String): Int = 0

    /** The path a link of the table of contents leads to, fragment and all. */
    fun link(target: String): String = BOOK + anchor(target.substringBefore('#'), target.substringAfter('#', ""))

    override fun stays(path: String): Boolean = path == BOOK || path.startsWith("/e/")

    override fun serve(path: String, look: Look): Served? {
        if (path == BOOK) return Served("text/html", book(look).byteInputStream())
        if (!path.startsWith("/e/")) return null
        val name = FolderDoc.decode(path.removePrefix("/e/"))
        return pkg.bytes(name)?.let { Served(mime(name), it.inputStream()) }
    }

    // `#c<n>` or `#c<n>-<id>` for a place in chapter [path]; the first chapter's when [path] is none.
    private fun anchor(path: String, id: String): String {
        val n = spine.indexOf(path).takeIf { it >= 0 } ?: 0
        return if (id.isEmpty()) "#c$n" else "#c$n-$id"
    }

    // The chapters one after another, each from a sheet of its own, under the book's own styles; its colours, its
    // margins and the size of its pictures are the app's.
    private fun book(look: Look): String {
        val links = LinkedHashSet<String>()
        val styles = StringBuilder()
        val body = StringBuilder()
        spine.forEachIndexed { i, path ->
            val text = pkg.bytes(path)?.toString(Charsets.UTF_8) ?: return@forEachIndexed
            val head = text.indexOf("</head>", ignoreCase = true).let { if (it < 0) "" else text.substring(0, it) }
            LINK.each(head) { m ->
                val tag = m.group()
                if (!tag.contains("stylesheet", ignoreCase = true)) return@each
                HREF.find(tag)?.let { h -> links += "/e/" + encode(resolve(path, FolderDoc.decode(h.groupValues[2].ifEmpty { h.groupValues[3] }))) }
            }
            STYLE.each(head) { m -> styles.append(m.group(1)).append('\n') }
            val open = BODY.find(text)
            val from = open?.range?.last?.plus(1) ?: head.length
            val to = text.lastIndexOf("</body>", ignoreCase = true).takeIf { it >= from } ?: text.length
            body.append("<div class=\"pbb\" id=\"c").append(i).append("\"></div>")
            body.append(rewrite(text.substring(from, to), path, i))
        }
        val own = "body *{color:inherit!important;background-color:transparent!important;border-color:var(--line)!important}" +
            "body a{color:var(--accent)!important}img,svg,video{max-width:100%!important;height:auto!important}" +
            "html,body{background:var(--bg)!important;color:var(--fg)!important}" +
            "body.paged{margin:0!important;padding:calc(var(--top) + 8px) 0 calc(var(--bottom) + 8px)!important;max-width:none!important}"
        val head = links.joinToString("") { "<link rel=\"stylesheet\" href=\"" + Page.escape(it) + "\">" } + "<style>" + own + "</style>"
        return Page.html(body.toString(), look, extra = styles.toString(), head = head, book = true)
    }

    // A chapter's markup, read as HTML: an element closed in itself is closed after it, ids and links between chapters
    // become places in the one page, and what it shows is fetched from the book.
    private fun rewrite(html: String, path: String, chapter: Int): String {
        val closed = replace(SELF_CLOSED, html) { m ->
            val name = m.group(1)
            val rest = m.group(2).orEmpty()
            if (name.lowercase() in VOID) "<$name$rest>" else "<$name$rest></$name>"
        }
        return replace(ATTR, closed) { m ->
            val value = m.group(5) ?: m.group(6) ?: ""
            val out = when (m.group(2).lowercase()) {
                "id" -> "c$chapter-$value"
                "href" -> when {
                    value.startsWith("#") -> "#c$chapter-" + value.substring(1)
                    SCHEME.containsMatchIn(value) -> value
                    else -> {
                        val target = resolve(path, FolderDoc.decode(value.substringBefore('#')))
                        if (target in spine) anchor(target, value.substringAfter('#', "")) else "/e/" + encode(target)
                    }
                }
                else -> if (SCHEME.containsMatchIn(value)) value else "/e/" + encode(resolve(path, FolderDoc.decode(value)))
            }
            m.group(1) + m.group(2) + m.group(3) + "\"" + out.replace("\"", "&quot;") + "\""
        }
    }

    private fun encode(path: String) = path.split('/').joinToString("/") { FolderDoc.encode(it) }

    private inline fun replace(regex: Regex, text: String, transform: (java.util.regex.Matcher) -> String): String {
        val out = StringBuilder(text.length + 256)
        var last = 0
        regex.each(text) { m ->
            out.append(text, last, m.start()).append(transform(m))
            last = m.end()
        }
        return out.append(text, last, text.length).toString()
    }

    private companion object {
        const val BOOK = "/book"
        val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        val HREF = Regex("href\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
        val STYLE = Regex("<style[^>]*>([\\s\\S]*?)</style>", RegexOption.IGNORE_CASE)
        val BODY = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
        val SELF_CLOSED = Regex("<([A-Za-z][\\w:.-]*)(\\s[^<>]*?)?/>")
        val ATTR = Regex("(\\s)(src|href|xlink:href|id|poster)(\\s*=\\s*)(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
        val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    }

    override fun close() = pkg.close()
}
