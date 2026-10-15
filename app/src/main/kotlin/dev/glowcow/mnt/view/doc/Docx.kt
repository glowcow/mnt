package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.html.Paper
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** A Word document of Office Open XML: paragraphs, headings, lists, tables, links, pictures and footnotes. */
object Docx {
    fun convert(file: File): Converted {
        val pkg = Package(file)
        try {
            val main = pkg.xml("[Content_Types].xml")?.children("Override")
                ?.firstOrNull { it.attr("ContentType").orEmpty().contains("document.main") }
                ?.attr("PartName")?.trimStart('/') ?: "word/document.xml"
            val body = pkg.xml(main)?.find("body") ?: return Converted(listOf(Converted.Part("", "")), closing = pkg::close)
            val writer = Writer(Context(pkg, main))
            writer.blocks(body)
            writer.footnotes()
            return Converted(listOf(Converted.Part("", writer.out.toString(), paper(body))), { name -> pkg.bytes(name) }, pkg::close)
        } catch (e: Exception) {
            pkg.close()
            throw e
        }
    }

    // The last section's sheets: their size and margins, in twentieths of a point.
    private fun paper(body: El): Paper {
        val section = body.child("sectPr") ?: body.all("sectPr").lastOrNull() ?: return Paper.A4
        val size = section.child("pgSz")
        val margin = section.child("pgMar")
        fun points(el: El?, name: String, default: Float) = el?.attr(name)?.toFloatOrNull()?.let { abs(it) / 20f } ?: default
        val a = points(size, "w", Paper.A4.width)
        val b = points(size, "h", Paper.A4.height)
        val (width, height) = if (size?.attr("orient") == "landscape") maxOf(a, b) to minOf(a, b) else a to b
        return Paper(width, height, points(margin, "top", 72f), points(margin, "right", 72f), points(margin, "bottom", 72f), points(margin, "left", 72f))
    }

    /** How a run is set: each null where nothing has said. */
    private data class Font(
        val size: Float? = null,
        val face: String? = null,
        val color: String? = null,
        val mark: String? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val under: Boolean? = null,
        val strike: Boolean? = null,
        val script: String? = null,
    )

    /** How a paragraph is set, in points. */
    private data class Para(
        val before: Float? = null,
        val after: Float? = null,
        val line: String? = null,
        val left: Float? = null,
        val right: Float? = null,
        val first: Float? = null,
        val align: String? = null,
        val breakBefore: Boolean = false,
    )

    private class Style(val el: El, val name: String, val level: Int?, val basedOn: String?, val numId: String?, val ilvl: Int?)

    /** A list's level: how it counts, its marker, and the indents of its paragraphs. */
    private class Level(val format: String, val text: String, val pPr: El?)

    /** What the parts of a document share: styles, theme fonts, numbering, and the notes and list counts met so far. */
    private class Context(val pkg: Package, val part: String) {
        val rels = Rels(pkg, part)
        private val stylesXml = pkg.xml(resolve(part, "styles.xml"))
        val styles: Map<String, Style> = stylesXml?.children("style")?.associate {
            val id = it.attr("styleId").orEmpty()
            val level = it.child("pPr")?.child("outlineLvl")?.attr("val")?.toIntOrNull()
            val num = it.child("pPr")?.child("numPr")
            id to Style(it, it.child("name")?.attr("val").orEmpty(), level, it.child("basedOn")?.attr("val"), num?.child("numId")?.attr("val"), num?.child("ilvl")?.attr("val")?.toIntOrNull())
        }.orEmpty()
        private val normal = stylesXml?.children("style")?.firstOrNull { it.attr("type") == "paragraph" && it.attr("default") in setOf("1", "true") }?.attr("styleId")

        // The theme's fonts, for runs that name a font of the theme: `minorHAnsi` and the like.
        private val themeFonts: Map<String, String> = run {
            val theme = rels.ofType("theme").firstOrNull()?.let { pkg.xml(it.target) }?.find("fontScheme") ?: return@run emptyMap()
            listOfNotNull(
                theme.child("minorFont")?.child("latin")?.attr("typeface")?.let { "minor" to it },
                theme.child("majorFont")?.child("latin")?.attr("typeface")?.let { "major" to it },
            ).toMap()
        }
        private val defaults = stylesXml?.child("docDefaults")
        val baseFont: Font = font(Font(size = 10f), defaults?.child("rPrDefault")?.child("rPr"))
        private val basePara: Para = para(Para(), defaults?.child("pPrDefault")?.child("pPr"))

        // numId → level → its count, marker and indents
        val numbering: Map<String, Map<Int, Level>> = run {
            val xml = pkg.xml(resolve(part, "numbering.xml")) ?: return@run emptyMap()
            val abstract = xml.children("abstractNum").associate { a ->
                a.attr("abstractNumId").orEmpty() to a.children("lvl").associate { l ->
                    (l.attr("ilvl")?.toIntOrNull() ?: 0) to Level(l.child("numFmt")?.attr("val").orEmpty(), l.child("lvlText")?.attr("val").orEmpty(), l.child("pPr"))
                }
            }
            xml.children("num").associate { it.attr("numId").orEmpty() to abstract[it.child("abstractNumId")?.attr("val")].orEmpty() }
        }
        val counters = HashMap<String, IntArray>()
        val notes = ArrayList<String>()
        val noteNumbers = HashMap<String, Int>()
        private val paragraphs = HashMap<String, Pair<Para, Font>>()

        // A style and those it is based on, the furthest first.
        fun chain(id: String?): List<Style> {
            val out = ArrayList<Style>()
            var at = id
            while (at != null && out.size < 10) {
                val style = styles[at] ?: break
                out += style
                at = style.basedOn
            }
            return out.asReversed()
        }

        /** A paragraph style's own setting and that of its runs, over the document's defaults. */
        fun paragraph(id: String?): Pair<Para, Font> = paragraphs.getOrPut(id ?: "") {
            var p = basePara
            var f = baseFont
            for (style in chain(id ?: normal)) {
                p = para(p, style.el.child("pPr"))
                f = font(f, style.el.child("rPr"))
            }
            p to f
        }

        fun font(base: Font, rPr: El?): Font {
            rPr ?: return base
            fun on(name: String): Boolean? = rPr.child(name)?.let { it.attr("val") !in setOf("0", "false", "none") }
            val fonts = rPr.child("rFonts")
            val face = fonts?.attr("ascii") ?: fonts?.attr("hAnsi")
                ?: (fonts?.attr("asciiTheme") ?: fonts?.attr("hAnsiTheme"))?.let { themeFonts[if (it.startsWith("major")) "major" else "minor"] }
            val mark = rPr.child("highlight")?.attr("val")?.let { HIGHLIGHTS[it] }
                ?: rPr.child("shd")?.attr("fill")?.takeIf { it.length == 6 && it != "auto" }?.let { "#$it" }
            return base.copy(
                size = rPr.child("sz")?.attr("val")?.toFloatOrNull()?.let { it / 2f } ?: base.size,
                face = face ?: base.face,
                color = rPr.child("color")?.attr("val")?.takeIf { it.length == 6 && it != "auto" }?.let { "#$it" } ?: base.color,
                mark = mark ?: base.mark,
                bold = on("b") ?: base.bold,
                italic = on("i") ?: base.italic,
                under = on("u") ?: base.under,
                strike = (on("strike") ?: on("dstrike")) ?: base.strike,
                script = rPr.child("vertAlign")?.attr("val") ?: base.script,
            )
        }

        fun para(base: Para, pPr: El?): Para {
            pPr ?: return base
            val spacing = pPr.child("spacing")
            val ind = pPr.child("ind")
            fun twips(el: El?, vararg names: String) = names.firstNotNullOfOrNull { el?.attr(it)?.toFloatOrNull() }?.let { it / 20f }
            val line = spacing?.attr("line")?.toFloatOrNull()?.let { value ->
                when (spacing.attr("lineRule")) {
                    "exact" -> points(value / 20f)
                    "atLeast" -> "max(1.15em," + points(value / 20f) + ")"
                    else -> String.format(Locale.US, "%.3f", LINE * value / 240f)
                }
            }
            val hanging = twips(ind, "hanging")
            return base.copy(
                before = if (spacing?.attr("beforeAutospacing") in setOf("1", "true")) AUTO_SPACE else twips(spacing, "before") ?: base.before,
                after = if (spacing?.attr("afterAutospacing") in setOf("1", "true")) AUTO_SPACE else twips(spacing, "after") ?: base.after,
                line = line ?: base.line,
                left = twips(ind, "left", "start") ?: base.left,
                right = twips(ind, "right", "end") ?: base.right,
                first = hanging?.let { -it } ?: twips(ind, "firstLine") ?: base.first,
                align = when (pPr.child("jc")?.attr("val")) {
                    "center" -> "center"
                    "right", "end" -> "right"
                    "both", "distribute" -> "justify"
                    "left", "start" -> "left"
                    else -> base.align
                },
                breakBefore = pPr.child("pageBreakBefore")?.let { it.attr("val") !in setOf("0", "false") } ?: base.breakBefore,
            )
        }
    }

    private class Writer(private val ctx: Context, private val table: El? = null) {
        val out = StringBuilder()
        private val pkg = ctx.pkg
        private val part = ctx.part
        private val rels = ctx.rels

        // Within a field, only its result is shown; a HYPERLINK field makes a link of it.
        private var field = 0
        private var showing = true
        private var fieldLink: String? = null

        fun blocks(parent: El) {
            for (el in parent.elements) {
                when (el.local) {
                    "p" -> paragraph(el)
                    "tbl" -> table(el)
                    "sdt" -> el.child("sdtContent")?.let { blocks(it) }
                    "customXml", "smartTag" -> blocks(el)
                }
            }
        }

        private fun heading(pPr: El?): Int? {
            pPr?.child("outlineLvl")?.attr("val")?.toIntOrNull()?.let { return (it + 1).coerceIn(1, 6) }
            var id = pPr?.child("pStyle")?.attr("val")
            repeat(5) {
                val style = ctx.styles[id ?: return null] ?: return null
                val name = style.name.lowercase()
                when {
                    name == "title" -> return 1
                    name == "subtitle" -> return 2
                    name.startsWith("heading ") -> return (name.removePrefix("heading ").trim().toIntOrNull() ?: 1).coerceIn(1, 6)
                    style.level != null -> return (style.level + 1).coerceIn(1, 6)
                }
                id = style.basedOn
            }
            return null
        }

        // A list style numbers its paragraphs itself: the numbering comes from the style or one it is based on.
        private fun styleNumbering(pPr: El?): Pair<String, Int>? {
            var id = pPr?.child("pStyle")?.attr("val")
            repeat(5) {
                val style = ctx.styles[id ?: return null] ?: return null
                if (style.numId != null) return style.numId to (style.ilvl ?: 0)
                id = style.basedOn
            }
            return null
        }

        private fun paragraph(p: El) {
            val pPr = p.child("pPr")
            val (styled, font) = ctx.paragraph(pPr?.child("pStyle")?.attr("val"))
            val num = pPr?.child("numPr")
            val inherited = if (num?.child("numId") == null) styleNumbering(pPr) else null
            val numId = (num?.child("numId")?.attr("val") ?: inherited?.first)?.takeIf { it != "0" && ctx.numbering[it] != null }
            val ilvl = num?.child("ilvl")?.attr("val")?.toIntOrNull() ?: inherited?.second ?: 0
            // A list's level indents its paragraphs, unless the paragraph says otherwise.
            var para = styled
            if (numId != null) para = ctx.para(para, ctx.numbering[numId]?.get(ilvl)?.pPr)
            // A table's style sets the paragraphs of its cells that have no style of their own.
            if (pPr?.child("pStyle") == null) table?.child("tblPr")?.child("tblStyle")?.attr("val")?.let { id -> ctx.chain(id).forEach { para = ctx.para(para, it.el.child("pPr")) } }
            para = ctx.para(para, pPr)
            val inner = StringBuilder()
            runs(p, inner, font)
            val level = heading(pPr)
            val classes = buildList {
                if (numId != null && level == null) add("li")
                if (para.breakBefore) add("pbb")
            }
            val attrs = StringBuilder()
            if (classes.isNotEmpty()) attrs.append(" class=\"").append(classes.joinToString(" ")).append('"')
            attrs.append(" style=\"").append(style(para, font, numId != null && level == null, ilvl)).append('"')
            val marker = if (numId != null && level == null) "<span class=\"mk\">" + marker(numId, ilvl) + "\u00A0</span>" else ""
            val tag = if (level != null) "h$level" else "p"
            // A page break inside a paragraph cuts it in two, a break between them.
            val pieces = inner.split(PAGE_BREAK)
            var marked = false
            pieces.forEachIndexed { i, piece ->
                if (i > 0) out.append("<hr class=\"pb\">")
                if (pieces.size > 1 && piece.isBlank()) return@forEachIndexed
                out.append('<').append(tag).append(attrs).append('>')
                if (!marked) out.append(marker)
                marked = true
                out.append(piece.ifBlank { if (marker.isEmpty()) "&nbsp;" else "" })
                out.append("</").append(tag).append('>')
            }
            // A section that starts on a new page.
            pPr?.child("sectPr")?.let { if (it.child("type")?.attr("val") != "continuous") out.append("<div class=\"pb\"></div>") }
        }

        // The setting of a paragraph as the paged layout reads it; text flowing to the screen leaves it aside.
        private fun style(para: Para, font: Font, listed: Boolean, level: Int): String = buildString {
            para.align?.let { if (it != "left") append("text-align:").append(it).append(';') }
            append("--sb:").append(points(para.before ?: 0f)).append(";--sa:").append(points(para.after ?: 0f)).append(';')
            para.line?.let { append("--lh:").append(it).append(';') }
            para.left?.let { append("--il:").append(points(it)).append(';') }
            para.right?.let { if (it != 0f) append("--ir:").append(points(it)).append(';') }
            para.first?.let { append("--ti:").append(points(it)).append(';') }
            if (listed) {
                append("--lv:").append(level).append(';')
                para.first?.let { if (it < 0) append("--hg:").append(points(-it)).append(';') }
            }
            append(fontStyle(font, null))
        }

        // What of [font] differs from [around], as the paged layout reads it.
        private fun fontStyle(font: Font, around: Font?): String = buildString {
            if (font.size != null && font.size != around?.size) append("--sz:").append(points(font.size)).append(';')
            if (font.face != null && font.face != around?.face) append("--ff:").append(family(font.face)).append(';')
            if (font.color != null && font.color != around?.color) append("--fc:").append(font.color).append(';')
            if (font.mark != null && font.mark != around?.mark) append("--hl:").append(font.mark).append(';')
        }

        private fun marker(numId: String, ilvl: Int): String {
            val levels = ctx.numbering[numId]
            val level = levels?.get(ilvl)
            val format = level?.format ?: "bullet"
            val text = level?.text ?: "•"
            val counts = ctx.counters.getOrPut(numId) { IntArray(9) }
            counts[ilvl.coerceIn(0, 8)]++
            for (deeper in ilvl + 1 until 9) counts[deeper] = 0
            if (format == "bullet" || format == "none") return if (text.isBlank() || text[0].code in 0xF000..0xF0FF) "•" else esc(text)
            // `%1.%2.` names the counters of the levels it shows.
            return esc(Regex("%(\\d)").replace(text) { m -> number(counts[(m.groupValues[1].toInt() - 1).coerceIn(0, 8)], levels?.get(m.groupValues[1].toInt() - 1)?.format ?: format) })
        }

        private fun runs(parent: El, out: StringBuilder, font: Font) {
            for (el in parent.elements) {
                when (el.local) {
                    "r" -> run(el, out, font)
                    "hyperlink" -> {
                        val href = rels[el.rel()]?.target ?: el.attr("anchor")?.let { "#$it" }
                        if (href != null) out.append("<a href=\"").append(esc(href)).append("\">")
                        runs(el, out, font)
                        if (href != null) out.append("</a>")
                    }
                    "fldSimple" -> {
                        val link = Regex("HYPERLINK\\s+\"([^\"]+)\"").find(el.attr("instr").orEmpty())?.groupValues?.get(1)
                        if (link != null) out.append("<a href=\"").append(esc(link)).append("\">")
                        runs(el, out, font)
                        if (link != null) out.append("</a>")
                    }
                    "ins", "smartTag", "customXml", "sdt", "sdtContent", "bdo", "dir" -> runs(el, out, font)
                    "oMath", "oMathPara" -> out.append("<i>").append(esc(el.all("t").joinToString("") { it.text() })).append("</i>")
                }
            }
        }

        private fun run(r: El, out: StringBuilder, paragraph: Font) {
            val rPr = r.child("rPr")
            var font = paragraph
            ctx.chain(rPr?.child("rStyle")?.attr("val")).forEach { font = ctx.font(font, it.el.child("rPr")) }
            font = ctx.font(font, rPr)
            val tags = buildList {
                if (font.bold == true) add("b")
                if (font.italic == true) add("i")
                if (font.under == true) add("u")
                if (font.strike == true) add("s")
                when (font.script) {
                    "superscript" -> add("sup")
                    "subscript" -> add("sub")
                }
            }
            val text = StringBuilder()
            for (el in r.elements) {
                when (el.local) {
                    "fldChar" -> when (el.attr("fldCharType")) {
                        "begin" -> {
                            field++
                            showing = false
                        }
                        "separate" -> {
                            showing = true
                            fieldLink?.let { text.append("<a href=\"").append(esc(it)).append("\">") }
                        }
                        "end" -> {
                            if (fieldLink != null && showing) text.append("</a>")
                            fieldLink = null
                            field = maxOf(0, field - 1)
                            showing = true
                        }
                    }
                    "instrText" -> Regex("HYPERLINK\\s+\"([^\"]+)\"").find(el.text())?.let { fieldLink = it.groupValues[1] }
                    "t" -> if (showing) text.append(esc(el.text()))
                    "tab" -> if (showing) text.append(" ")
                    "br", "cr" -> if (showing) text.append(if (el.attr("type") == "page") PAGE_BREAK else "<br>")
                    "noBreakHyphen" -> text.append('-')
                    "sym" -> el.attr("char")?.toIntOrNull(16)?.let { if (it !in 0xF000..0xF0FF) text.appendCodePoint(it) }
                    "drawing", "pict", "object" -> if (showing) picture(el, text)
                    "footnoteReference", "endnoteReference" -> {
                        val id = el.attr("id").orEmpty()
                        val n = ctx.noteNumbers.getOrPut((if (el.local == "footnoteReference") "f" else "e") + id) {
                            ctx.notes += (if (el.local == "footnoteReference") "f" else "e") + id
                            ctx.notes.size
                        }
                        text.append("<sup><a href=\"#note").append(n).append("\">").append(n).append("</a></sup>")
                    }
                }
            }
            if (text.isEmpty()) return
            val set = fontStyle(font, paragraph)
            if (set.isNotEmpty()) out.append("<span style=\"").append(set).append("\">")
            tags.forEach { out.append('<').append(it).append('>') }
            out.append(text)
            tags.asReversed().forEach { out.append("</").append(it).append('>') }
            if (set.isNotEmpty()) out.append("</span>")
        }

        private fun picture(el: El, out: StringBuilder) {
            val id = el.find("blip")?.attr("embed") ?: el.find("imagedata")?.rel()
            val target = rels[id]?.takeIf { !it.external }?.target ?: return
            val alt = el.find("docPr")?.attr("descr").orEmpty()
            val width = el.find("extent")?.attr("cx")?.toFloatOrNull()?.let { it / EMU_PER_POINT }
            if (!drawable(target)) return
            out.append("<img src=\"/a/").append(esc(target)).append("\" alt=\"").append(esc(alt)).append('"')
            if (width != null) out.append(" style=\"--iw:").append(points(width)).append('"')
            out.append('>')
        }

        private fun table(tbl: El) {
            // Cells of rows, with how many columns each spans and whether it continues the one above.
            class Cell(val el: El, val column: Int, val span: Int, val merged: Boolean) {
                var rows = 1
            }
            val grid = tbl.children("tr").map { tr ->
                var column = 0
                val cells = (tr.children("tc") + tr.children("sdt").flatMap { it.child("sdtContent")?.children("tc").orEmpty() }).map { tc ->
                    val pr = tc.child("tcPr")
                    val span = pr?.child("gridSpan")?.attr("val")?.toIntOrNull() ?: 1
                    val v = pr?.child("vMerge")
                    Cell(tc, column, span, v != null && v.attr("val") != "restart").also { column += span }
                }
                cells
            }
            grid.forEachIndexed { r, row ->
                row.filter { it.merged }.forEach { cell ->
                    for (above in r - 1 downTo 0) {
                        val top = grid[above].firstOrNull { it.column == cell.column } ?: break
                        if (!top.merged) {
                            top.rows++
                            break
                        }
                    }
                }
            }
            out.append("<div class=\"wide\"><table").append(if (bordered(tbl)) "" else " class=\"nb\"").append('>')
            for (row in grid) {
                out.append("<tr>")
                for (cell in row) {
                    if (cell.merged) continue
                    out.append("<td")
                    if (cell.span > 1) out.append(" colspan=\"").append(cell.span).append('"')
                    if (cell.rows > 1) out.append(" rowspan=\"").append(cell.rows).append('"')
                    val pr = cell.el.child("tcPr")
                    val width = pr?.child("tcW")?.takeIf { it.attr("type") == "dxa" }?.attr("w")?.toFloatOrNull()?.let { it / 20f }
                    val shade = pr?.child("shd")?.attr("fill")?.takeIf { it.length == 6 && it != "auto" }
                    if (width != null || shade != null) {
                        out.append(" style=\"")
                        width?.let { out.append("--cw:").append(points(it)).append(';') }
                        shade?.let { out.append("--sh:#").append(it).append(';') }
                        out.append('"')
                    }
                    out.append('>')
                    out.append(Writer(ctx, tbl).also { it.blocks(cell.el) }.out)
                    out.append("</td>")
                }
                out.append("</tr>")
            }
            out.append("</table></div>")
        }

        // A table has lines where it or its style draws them; Word's plain table has none.
        private fun bordered(tbl: El): Boolean {
            val pr = tbl.child("tblPr")
            val borders = pr?.child("tblBorders") ?: ctx.chain(pr?.child("tblStyle")?.attr("val")).asReversed().firstNotNullOfOrNull { it.el.child("tblPr")?.child("tblBorders") }
            return borders?.elements?.any { it.attr("val") !in setOf("nil", "none") } == true
        }

        fun footnotes() {
            if (ctx.notes.isEmpty()) return
            val foot = pkg.xml(resolve(part, "footnotes.xml"))?.children("footnote")?.associateBy { it.attr("id") }.orEmpty()
            val end = pkg.xml(resolve(part, "endnotes.xml"))?.children("endnote")?.associateBy { it.attr("id") }.orEmpty()
            out.append("<hr><div class=\"muted\" style=\"font-size:13px\">")
            var i = 0
            while (i < ctx.notes.size) {
                val key = ctx.notes[i]
                i++
                val note = (if (key[0] == 'f') foot else end)[key.substring(1)] ?: continue
                val inner = Writer(ctx).also { it.blocks(note) }.out.toString()
                out.append("<div id=\"note").append(i).append("\" style=\"display:flex;gap:.3em\"><sup>").append(i).append("</sup><div>").append(inner).append("</div></div>")
            }
            out.append("</div>")
        }
    }

    private fun points(value: Float) = String.format(Locale.US, "%.1fpt", value)

    // The faces the phone has: a font with serifs is set in the phone's serif, a fixed one in the app's mono, the rest in Arimo.
    private fun family(face: String): String {
        val name = face.lowercase()
        return when {
            MONO.any { name.contains(it) } -> "Mono,monospace"
            SERIF.any { name.contains(it) } -> "serif"
            else -> "Arimo,sans-serif"
        }
    }

    private val SERIF = listOf("times", "cambria", "georgia", "garamond", "book antiqua", "palatino", "serif", "tinos", "constantia", "bookman", "century", "minion")
    private val MONO = listOf("courier", "consolas", "mono", "lucida console", "cousine", "menlo")

    private val HIGHLIGHTS = mapOf(
        "yellow" to "#FFFF00", "green" to "#00FF00", "cyan" to "#00FFFF", "magenta" to "#FF00FF", "blue" to "#0000FF", "red" to "#FF0000",
        "darkBlue" to "#000080", "darkCyan" to "#008080", "darkGreen" to "#008000", "darkMagenta" to "#800080", "darkRed" to "#800000",
        "darkYellow" to "#808000", "darkGray" to "#808080", "lightGray" to "#C0C0C0", "black" to "#000000", "white" to "#FFFFFF",
    )

    // Word's single line is about this many times the size of its font.
    private const val LINE = 1.15f

    // What Word leaves round a paragraph spaced "automatically", as a web page would.
    private const val AUTO_SPACE = 14f

    private const val EMU_PER_POINT = 12700f

    // Stands for a page break in a paragraph's text until the paragraph is written.
    private const val PAGE_BREAK = "\u0001"

    /** [n] written as a list of [format] counts it: decimal, letters or Roman numerals. */
    fun number(n: Int, format: String): String = when (format) {
        "lowerLetter" -> letters(n).lowercase()
        "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "decimalZero" -> n.toString().padStart(2, '0')
        else -> n.toString()
    }

    private fun letters(n: Int): String {
        var k = n
        val s = StringBuilder()
        while (k > 0) {
            k--
            s.insert(0, 'A' + k % 26)
            k /= 26
        }
        return s.toString()
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val marks = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var k = n
        return buildString {
            for (i in values.indices) while (k >= values[i]) {
                append(marks[i])
                k -= values[i]
            }
        }
    }
}
