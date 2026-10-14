package dev.glowcow.mnt.view.doc

import java.io.File

/** A Word document of Office Open XML: paragraphs, headings, lists, tables, links, pictures and footnotes. */
object Docx {
    fun convert(file: File): Converted {
        val pkg = Package(file)
        try {
            val main = pkg.xml("[Content_Types].xml")?.children("Override")
                ?.firstOrNull { it.attr("ContentType").orEmpty().contains("document.main") }
                ?.attr("PartName")?.trimStart('/') ?: "word/document.xml"
            val body = pkg.xml(main)?.find("body") ?: return Converted(listOf(Converted.Part("", "")), closing = pkg::close)
            val writer = Writer(pkg, main)
            writer.blocks(body)
            writer.footnotes()
            return Converted(listOf(Converted.Part("", writer.out.toString())), { name -> pkg.bytes(name) }, pkg::close)
        } catch (e: Exception) {
            pkg.close()
            throw e
        }
    }

    private class Style(val name: String, val level: Int?, val basedOn: String?, val numId: String?, val ilvl: Int?)

    private class Writer(private val pkg: Package, private val part: String) {
        val out = StringBuilder()
        private val rels = Rels(pkg, part)
        private val styles: Map<String, Style> = pkg.xml(resolve(part, "styles.xml"))?.children("style")?.associate {
            val id = it.attr("styleId").orEmpty()
            val level = it.child("pPr")?.child("outlineLvl")?.attr("val")?.toIntOrNull()
            val num = it.child("pPr")?.child("numPr")
            id to Style(it.child("name")?.attr("val").orEmpty(), level, it.child("basedOn")?.attr("val"), num?.child("numId")?.attr("val"), num?.child("ilvl")?.attr("val")?.toIntOrNull())
        }.orEmpty()

        // numId → level → (format, text of the marker)
        private val numbering: Map<String, Map<Int, Pair<String, String>>> = run {
            val xml = pkg.xml(resolve(part, "numbering.xml")) ?: return@run emptyMap()
            val abstract = xml.children("abstractNum").associate { a ->
                a.attr("abstractNumId").orEmpty() to a.children("lvl").associate { l ->
                    (l.attr("ilvl")?.toIntOrNull() ?: 0) to (l.child("numFmt")?.attr("val").orEmpty() to l.child("lvlText")?.attr("val").orEmpty())
                }
            }
            xml.children("num").associate { it.attr("numId").orEmpty() to abstract[it.child("abstractNumId")?.attr("val")].orEmpty() }
        }
        private val counters = HashMap<String, IntArray>()
        private val notes = ArrayList<String>()
        private val noteNumbers = HashMap<String, Int>()

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
                val style = styles[id ?: return null] ?: return null
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
                val style = styles[id ?: return null] ?: return null
                if (style.numId != null) return style.numId to (style.ilvl ?: 0)
                id = style.basedOn
            }
            return null
        }

        private fun paragraph(p: El) {
            val pPr = p.child("pPr")
            val inner = StringBuilder()
            runs(p, inner)
            val level = heading(pPr)
            val align = when (pPr?.child("jc")?.attr("val")) {
                "center" -> "center"
                "right", "end" -> "right"
                "both", "distribute" -> "justify"
                else -> null
            }
            val style = if (align != null) " style=\"text-align:$align\"" else ""
            val num = pPr?.child("numPr")
            val inherited = if (num?.child("numId") == null) styleNumbering(pPr) else null
            val numId = num?.child("numId")?.attr("val") ?: inherited?.first
            if (level != null) {
                out.append("<h").append(level).append(style).append('>').append(inner).append("</h").append(level).append('>')
            } else if (numId != null && numId != "0" && numbering[numId] != null) {
                val ilvl = num?.child("ilvl")?.attr("val")?.toIntOrNull() ?: inherited?.second ?: 0
                out.append("<p style=\"margin:.2em 0 .2em ").append(1.4 * ilvl + 0.4).append("em;padding-left:1.4em;text-indent:-1.4em\">")
                out.append(marker(numId, ilvl)).append(" ").append(inner).append("</p>")
            } else if (inner.isBlank()) {
                out.append("<p").append(style).append(">&nbsp;</p>")
            } else {
                out.append("<p").append(style).append('>').append(inner).append("</p>")
            }
        }

        private fun marker(numId: String, ilvl: Int): String {
            val (format, text) = numbering[numId]?.get(ilvl) ?: ("bullet" to "•")
            val counts = counters.getOrPut(numId) { IntArray(9) }
            counts[ilvl.coerceIn(0, 8)]++
            for (deeper in ilvl + 1 until 9) counts[deeper] = 0
            if (format == "bullet" || format == "none") return if (text.isBlank() || text[0].code in 0xF000..0xF0FF) "•" else esc(text)
            // `%1.%2.` names the counters of the levels it shows.
            return esc(Regex("%(\\d)").replace(text) { m -> number(counts[(m.groupValues[1].toInt() - 1).coerceIn(0, 8)], numbering[numId]?.get(m.groupValues[1].toInt() - 1)?.first ?: format) })
        }

        private fun runs(parent: El, out: StringBuilder) {
            for (el in parent.elements) {
                when (el.local) {
                    "r" -> run(el, out)
                    "hyperlink" -> {
                        val href = rels[el.rel()]?.target ?: el.attr("anchor")?.let { "#$it" }
                        if (href != null) out.append("<a href=\"").append(esc(href)).append("\">")
                        runs(el, out)
                        if (href != null) out.append("</a>")
                    }
                    "fldSimple" -> {
                        val link = Regex("HYPERLINK\\s+\"([^\"]+)\"").find(el.attr("instr").orEmpty())?.groupValues?.get(1)
                        if (link != null) out.append("<a href=\"").append(esc(link)).append("\">")
                        runs(el, out)
                        if (link != null) out.append("</a>")
                    }
                    "ins", "smartTag", "customXml", "sdt", "sdtContent", "bdo", "dir" -> runs(el, out)
                    "oMath", "oMathPara" -> out.append("<i>").append(esc(el.all("t").joinToString("") { it.text() })).append("</i>")
                }
            }
        }

        private fun run(r: El, out: StringBuilder) {
            val rPr = r.child("rPr")
            fun on(name: String) = rPr?.child(name)?.let { it.attr("val") !in setOf("0", "false", "none") } == true
            val tags = buildList {
                if (on("b")) add("b")
                if (on("i")) add("i")
                if (on("u")) add("u")
                if (on("strike") || on("dstrike")) add("s")
                when (rPr?.child("vertAlign")?.attr("val")) {
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
                    "tab" -> if (showing) text.append(" ")
                    "br", "cr" -> if (showing) text.append(if (el.attr("type") == "page") "<hr>" else "<br>")
                    "noBreakHyphen" -> text.append('-')
                    "sym" -> el.attr("char")?.toIntOrNull(16)?.let { if (it !in 0xF000..0xF0FF) text.appendCodePoint(it) }
                    "drawing", "pict", "object" -> if (showing) picture(el, text)
                    "footnoteReference", "endnoteReference" -> {
                        val id = el.attr("id").orEmpty()
                        val n = noteNumbers.getOrPut((if (el.local == "footnoteReference") "f" else "e") + id) {
                            notes += (if (el.local == "footnoteReference") "f" else "e") + id
                            notes.size
                        }
                        text.append("<sup><a href=\"#note").append(n).append("\">").append(n).append("</a></sup>")
                    }
                }
            }
            if (text.isEmpty()) return
            tags.forEach { out.append('<').append(it).append('>') }
            out.append(text)
            tags.asReversed().forEach { out.append("</").append(it).append('>') }
        }

        private fun picture(el: El, out: StringBuilder) {
            val id = el.find("blip")?.attr("embed") ?: el.find("imagedata")?.rel()
            val target = rels[id]?.takeIf { !it.external }?.target ?: return
            val alt = el.find("docPr")?.attr("descr").orEmpty()
            if (drawable(target)) out.append("<img src=\"/a/").append(esc(target)).append("\" alt=\"").append(esc(alt)).append("\">")
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
            out.append("<div class=\"wide\"><table>")
            for (row in grid) {
                out.append("<tr>")
                for (cell in row) {
                    if (cell.merged) continue
                    out.append("<td")
                    if (cell.span > 1) out.append(" colspan=\"").append(cell.span).append('"')
                    if (cell.rows > 1) out.append(" rowspan=\"").append(cell.rows).append('"')
                    out.append('>')
                    val inner = Writer(pkg, part).also { it.blocks(cell.el) }.out
                    out.append(inner)
                    out.append("</td>")
                }
                out.append("</tr>")
            }
            out.append("</table></div>")
        }

        fun footnotes() {
            if (notes.isEmpty()) return
            val foot = pkg.xml(resolve(part, "footnotes.xml"))?.children("footnote")?.associateBy { it.attr("id") }.orEmpty()
            val end = pkg.xml(resolve(part, "endnotes.xml"))?.children("endnote")?.associateBy { it.attr("id") }.orEmpty()
            out.append("<hr><div class=\"muted\" style=\"font-size:13px\">")
            notes.forEachIndexed { i, key ->
                val note = (if (key[0] == 'f') foot else end)[key.substring(1)] ?: return@forEachIndexed
                val inner = Writer(pkg, part).also { it.blocks(note) }.out.toString()
                out.append("<div id=\"note").append(i + 1).append("\"><sup>").append(i + 1).append("</sup> ").append(inner.removePrefix("<p>").removeSuffix("</p>")).append("</div>")
            }
            out.append("</div>")
        }
    }

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
