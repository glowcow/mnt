package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.html.Paper
import java.io.File

/** OpenDocument: text (odt), spreadsheets (ods) and presentations (odp), all from `content.xml`. */
object Odf {
    fun text(file: File): Converted = open(file) { pkg, content, styles ->
        val out = StringBuilder()
        val writer = Writer(styles)
        content.find("text")?.let { writer.blocks(it, out) }
        writer.notes(out)
        listOf(Converted.Part("", out.toString(), paper(pkg)))
    }

    fun presentation(file: File): Converted = open(file) { _, content, styles ->
        val out = StringBuilder()
        val writer = Writer(styles)
        content.all("page").forEachIndexed { i, page ->
            out.append("<section class=\"slide\"><div class=\"no\">").append(i + 1).append("</div>")
            for (frame in page.elements) writer.frame(frame, out)
            page.find("notes")?.let { notes ->
                val text = StringBuilder()
                // Only the frame of the notes: the others hold the slide's picture and its number.
                notes.all("frame").filter { it.attr("class") == "notes" }.forEach { f -> f.child("text-box")?.let { writer.blocks(it, text) } }
                if (text.isNotBlank()) out.append("<div class=\"muted\" style=\"font-size:13px;border-top:1px solid var(--line);margin-top:8px\">").append(text).append("</div>")
            }
            out.append("</section>")
        }
        listOf(Converted.Part("", out.toString()))
    }

    fun spreadsheet(file: File): Converted = open(file) { _, content, styles ->
        val cells = CellStyles(styles)
        content.find("spreadsheet")?.children("table").orEmpty().filter { it.attr("style-name") !in styles.hidden }.map { table ->
            val sheet = Sheet(table.attr("name").orEmpty())
            sheet.looks = cells.looks
            sheet.fontSize = cells.fontSize
            val digit = cells.fontSize * 4 / 3 * 0.556f
            // Columns: their widths, whether they are shown, and the style of a cell that names none.
            val columnLooks = HashMap<Int, Int>()
            var col = 0
            for (column in columns(table)) {
                val times = (column.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtMost(Sheets.MAX_COLUMNS)
                val width = styles.byName[column.attr("style-name")]?.child("table-column-properties")?.attr("column-width")?.let { points(it) }
                val look = cells.index(column.attr("default-cell-style-name"))
                for (k in col until minOf(col + times, Sheets.MAX_COLUMNS)) {
                    if (width != null) sheet.widths[k] = width * 4 / 3 / digit
                    if (column.attr("visibility") == "collapse") sheet.hiddenColumns += k
                    if (look != 0) columnLooks[k] = look
                }
                col += times
            }
            var r = 0
            for (row in rows(table)) {
                val repeat = row.attr("number-rows-repeated")?.toIntOrNull() ?: 1
                // A row repeated thousands of times is the empty rest of the sheet.
                if (row.children("table-cell").all { it.nodes.isEmpty() } && repeat > 1) {
                    r += repeat
                    continue
                }
                val height = styles.byName[row.attr("style-name")]?.child("table-row-properties")?.attr("row-height")?.let { points(it) }
                val rowLook = cells.index(row.attr("default-cell-style-name"))
                repeat(minOf(repeat, 1000)) {
                    if (height != null) sheet.heights[r] = height
                    if (row.attr("visibility") == "collapse") sheet.hiddenRows += r
                    var c = 0
                    for (cell in row.elements) {
                        if (cell.local != "table-cell" && cell.local != "covered-table-cell") continue
                        val times = cell.attr("number-columns-repeated")?.toIntOrNull() ?: 1
                        val text = cell.children("p").joinToString("\n") { p -> plain(p) }
                        val number = cell.attr("value-type") in setOf("float", "percentage", "currency")
                        for (k in 0 until minOf(times, Sheets.MAX_COLUMNS - c)) {
                            val look = cell.attr("style-name")?.let { cells.index(it) } ?: columnLooks[c + k] ?: rowLook
                            sheet.put(r, c + k, if (cell.local == "table-cell") text else "", number, look)
                        }
                        if (cell.local == "table-cell" && text.isNotEmpty() || cell.attr("number-rows-spanned") != null || cell.attr("number-columns-spanned") != null) {
                            val spanR = cell.attr("number-rows-spanned")?.toIntOrNull() ?: 1
                            val spanC = cell.attr("number-columns-spanned")?.toIntOrNull() ?: 1
                            if (spanR > 1 || spanC > 1) sheet.merge(r, c, r + spanR - 1, c + spanC - 1)
                        }
                        c += times
                        if (c > Sheets.MAX_COLUMNS) break
                    }
                    r++
                }
            }
            Converted.Part(sheet.name, Sheets.html(sheet))
        }
    }

    private fun columns(table: El): List<El> = table.elements.flatMap {
        when (it.local) {
            "table-column" -> listOf(it)
            "table-column-group", "table-header-columns", "table-columns" -> columns(it)
            else -> emptyList()
        }
    }

    /** A length of ODF (`2.1in`, `0.74pt`, `5mm`) in points. */
    private fun points(length: String): Float? {
        val n = length.takeWhile { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull() ?: return null
        return when (length.drop(length.indexOfFirst { it.isLetter() }.coerceAtLeast(0)).lowercase()) {
            "in" -> n * 72
            "cm" -> n * 72 / 2.54f
            "mm" -> n * 72 / 25.4f
            "pc" -> n * 12
            "px" -> n * 0.75f
            else -> n
        }
    }

    // The looks of a spreadsheet's cell styles, each resolved along its parents; 0 is the default one.
    private class CellStyles(private val styles: Styles) {
        val looks = arrayListOf(CellLook())
        private val indices = HashMap<String, Int>()
        val fontSize: Float = (styles.cellDefault?.child("text-properties")?.attr("font-size")
            ?: styles.byName["Default"]?.child("text-properties")?.attr("font-size"))?.let { points(it) } ?: 10f

        fun index(name: String?): Int {
            name ?: return 0
            return indices.getOrPut(name) {
                looks += look(name)
                looks.size - 1
            }
        }

        // A property of the style or of the nearest of its parents that has it.
        private fun prop(name: String, kind: String, attr: String): String? {
            var s: String? = name
            repeat(8) {
                val el = styles.byName[s] ?: return styles.cellDefault?.child(kind)?.attr(attr)
                el.child(kind)?.attr(attr)?.let { return it }
                s = el.attr("parent-style-name") ?: return styles.cellDefault?.child(kind)?.attr(attr)
            }
            return null
        }

        private fun color(value: String?): Int? = value?.takeIf { it.startsWith("#") }?.drop(1)?.toIntOrNull(16)

        private fun edge(value: String?): Edge? {
            val parts = value?.split(' ')?.filter { it.isNotEmpty() } ?: return null
            if (parts.isEmpty() || parts[0] == "none" || parts.contains("none")) return null
            val width = points(parts[0]) ?: 0.75f
            val style = parts.getOrNull(1) ?: "solid"
            val px = if (width <= 1.1f) 1 else if (width <= 2f) 2 else 3
            return Edge(px, if (style in setOf("dashed", "dotted", "double")) style else "solid", color(parts.getOrNull(2)))
        }

        private fun look(name: String): CellLook {
            val cell = "table-cell-properties"
            val text = "text-properties"
            val para = "paragraph-properties"
            val all = prop(name, cell, "border")
            val size = prop(name, text, "font-size")?.let { points(it) }
            val fixed = prop(name, cell, "text-align-source") != "value-type"
            return CellLook(
                bold = prop(name, text, "font-weight").let { it == "bold" || (it?.toIntOrNull() ?: 400) >= 600 },
                italic = prop(name, text, "font-style") == "italic",
                underline = prop(name, text, "text-underline-style").let { it != null && it != "none" },
                strike = prop(name, text, "text-line-through-style").let { it != null && it != "none" },
                size = size?.takeIf { it != fontSize },
                face = Xlsx.face(prop(name, text, "font-name")),
                color = color(prop(name, text, "color"))?.takeIf { it != 0 },
                fill = color(prop(name, cell, "background-color")),
                left = edge(prop(name, cell, "border-left") ?: all),
                right = edge(prop(name, cell, "border-right") ?: all),
                top = edge(prop(name, cell, "border-top") ?: all),
                bottom = edge(prop(name, cell, "border-bottom") ?: all),
                align = if (!fixed) null else when (prop(name, para, "text-align")) {
                    "start", "left" -> "left"
                    "center" -> "center"
                    "end", "right" -> "right"
                    "justify" -> "justify"
                    else -> null
                },
                valign = when (prop(name, cell, "vertical-align")) {
                    "top" -> "top"
                    "middle" -> "middle"
                    else -> null
                },
                wrap = prop(name, cell, "wrap-option") == "wrap",
            )
        }
    }

    private fun rows(table: El): List<El> = table.elements.flatMap {
        when (it.local) {
            "table-row" -> listOf(it)
            "table-row-group", "table-header-rows", "table-rows" -> rows(it)
            else -> emptyList()
        }
    }

    private fun plain(el: El): String = buildString {
        for (node in el.nodes) {
            if (node is String) {
                append(node)
                continue
            }
            node as El
            when (node.local) {
                "s" -> repeat(node.attr("c")?.toIntOrNull() ?: 1) { append(' ') }
                "tab" -> append('\t')
                "line-break" -> append('\n')
                "annotation", "note" -> Unit
                else -> append(plain(node))
            }
        }
    }

    // The sheet of the first master page: its size and margins, in any unit OpenDocument writes.
    private fun paper(pkg: Package): Paper {
        val styles = pkg.xml("styles.xml") ?: return Paper.A4
        val name = styles.find("master-styles")?.child("master-page")?.attr("page-layout-name")
        val layout = styles.all("page-layout").firstOrNull { name == null || it.attr("name") == name }?.child("page-layout-properties") ?: return Paper.A4
        fun length(attr: String, default: Float): Float {
            val value = layout.attr(attr) ?: return default
            val number = value.takeWhile { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull() ?: return default
            return when (value.substring(value.indexOfFirst { it.isLetter() }.takeIf { it >= 0 } ?: value.length)) {
                "cm" -> number * 72f / 2.54f
                "mm" -> number * 72f / 25.4f
                "in" -> number * 72f
                "pt" -> number
                "pc" -> number * 12f
                else -> default
            }
        }
        val a = Paper.A4
        return Paper(length("page-width", a.width), length("page-height", a.height), length("margin-top", a.top), length("margin-right", a.right), length("margin-bottom", a.bottom), length("margin-left", a.left))
    }

    private fun open(file: File, read: (Package, El, Styles) -> List<Converted.Part>): Converted {
        val pkg = Package(file)
        try {
            val content = pkg.xml("content.xml") ?: return Converted(emptyList(), closing = pkg::close)
            val all = (listOfNotNull(content.find("automatic-styles"), pkg.xml("styles.xml")?.find("styles"), pkg.xml("styles.xml")?.find("automatic-styles")))
                .flatMap { it.children("style") }
            val parents = all.associate { it.attr("name").orEmpty() to it.attr("parent-style-name") }
            val byName = all.associateBy { it.attr("name").orEmpty() }
            val cellDefault = pkg.xml("styles.xml")?.find("styles")?.children("default-style")?.firstOrNull { it.attr("family") == "table-cell" }
            // A sheet whose style says it is not shown, and the list styles that number their items.
            val hidden = all.filter { it.child("table-properties")?.attr("display") == "false" }.mapNotNull { it.attr("name") }.toSet()
            val numbered = listOfNotNull(content.find("automatic-styles"), pkg.xml("styles.xml")?.find("styles"))
                .flatMap { it.children("list-style") }
                .filter { it.elements.firstOrNull()?.local == "list-level-style-number" }
                .mapNotNull { it.attr("name") }.toSet()
            val tags = all
                .associate { s ->
                    val props = s.child("text-properties")
                    val tags = buildSet {
                        if (props?.attr("font-weight") == "bold") add("b")
                        if (props?.attr("font-style") == "italic") add("i")
                        if (props?.attr("text-underline-style").let { it != null && it != "none" }) add("u")
                        if (props?.attr("text-line-through-style").let { it != null && it != "none" }) add("s")
                        props?.attr("text-position")?.let { if (it.startsWith("super")) add("sup") else if (it.startsWith("sub")) add("sub") }
                    }
                    s.attr("name").orEmpty() to tags
                }
            val before = all.filter { it.child("paragraph-properties")?.attr("break-before") == "page" }.mapNotNull { it.attr("name") }.toSet()
            val after = all.filter { it.child("paragraph-properties")?.attr("break-after") == "page" }.mapNotNull { it.attr("name") }.toSet()
            return Converted(read(pkg, content, Styles(tags, parents, hidden, numbered, before, after, byName, cellDefault)), { name -> pkg.bytes(name) }, pkg::close)
        } catch (e: Exception) {
            pkg.close()
            throw e
        }
    }

    /** The text formatting of each style by name, and the style each is based on. */
    private class Styles(
        val tags: Map<String, Set<String>>,
        private val parents: Map<String, String?>,
        val hidden: Set<String>,
        val numbered: Set<String>,
        private val breakBefore: Set<String> = emptySet(),
        private val breakAfter: Set<String> = emptySet(),
        val byName: Map<String, El> = emptyMap(),
        /** The default style of a spreadsheet's cells. */
        val cellDefault: El? = null,
    ) {
        /** A page break a paragraph's style puts before it, or after it. */
        fun breaks(style: String?, before: Boolean): Boolean = (if (before) breakBefore else breakAfter).let { set -> style != null && set.contains(style) }

        /** Whether a paragraph's style is, or is based on, [name]. */
        fun derives(style: String?, name: String): Boolean {
            var s = style
            repeat(6) {
                if (s == null) return false
                if (s == name) return true
                s = parents[s]
            }
            return false
        }
    }

    private class Writer(private val styles: Styles) {
        private val notes = ArrayList<String>()
        private var list: String? = null

        fun blocks(parent: El, out: StringBuilder) {
            for (el in parent.elements) {
                when (el.local) {
                    "p" -> {
                        val inner = inline(el)
                        val style = el.attr("style-name")
                        if (styles.breaks(style, before = true)) out.append("<hr class=\"pb\">")
                        when {
                            styles.derives(style, "Title") -> out.append("<h1>").append(inner).append("</h1>")
                            styles.derives(style, "Subtitle") -> out.append("<h2>").append(inner).append("</h2>")
                            else -> out.append("<p>").append(if (inner.isBlank()) "&nbsp;" else inner).append("</p>")
                        }
                        if (styles.breaks(style, before = false)) out.append("<hr class=\"pb\">")
                    }
                    "h" -> {
                        val level = (el.attr("outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6)
                        out.append("<h").append(level).append('>').append(inline(el)).append("</h").append(level).append('>')
                    }
                    "list" -> {
                        // A nested list keeps the style of the one it stands in unless it names its own.
                        val style = el.attr("style-name") ?: list
                        val tag = if (style in styles.numbered) "ol" else "ul"
                        out.append('<').append(tag).append('>')
                        for (item in el.children("list-item") + el.children("list-header")) {
                            out.append("<li>")
                            val outer = list
                            list = style
                            blocks(item, out)
                            list = outer
                            out.append("</li>")
                        }
                        out.append("</").append(tag).append('>')
                    }
                    "table" -> table(el, out)
                    "section", "index-body", "table-of-content", "alphabetical-index", "illustration-index", "bibliography" -> blocks(el, out)
                    "frame" -> frame(el, out)
                }
            }
        }

        fun frame(frame: El, out: StringBuilder) {
            if (frame.local == "g") return frame.elements.forEach { frame(it, out) }
            if (frame.local != "frame" && frame.local != "custom-shape" && frame.local != "rect") return
            frame.child("image")?.attr("href")?.let { if (drawable(it) && !it.contains("://")) out.append("<p><img src=\"/a/").append(esc(it)).append("\"></p>") }
            frame.child("table")?.let { table(it, out) }
            val box = frame.child("text-box") ?: frame.takeIf { it.local != "frame" } ?: return
            val text = StringBuilder()
            blocks(box, text)
            if (text.isBlank()) return
            if (frame.attr("class") == "title") out.append("<h2>").append(text.removePrefix("<p>").removeSuffix("</p>")).append("</h2>") else out.append(text)
        }

        private fun table(table: El, out: StringBuilder) {
            out.append("<div class=\"wide\"><table>")
            for (row in rows(table)) {
                out.append("<tr>")
                for (cell in row.children("table-cell")) {
                    out.append("<td")
                    cell.attr("number-columns-spanned")?.let { if (it != "1") out.append(" colspan=\"").append(it).append('"') }
                    cell.attr("number-rows-spanned")?.let { if (it != "1") out.append(" rowspan=\"").append(it).append('"') }
                    out.append('>')
                    blocks(cell, out)
                    out.append("</td>")
                }
                out.append("</tr>")
            }
            out.append("</table></div>")
        }

        fun inline(el: El): String = buildString {
            for (node in el.nodes) {
                if (node is String) {
                    append(esc(node))
                    continue
                }
                node as El
                when (node.local) {
                    "s" -> repeat(node.attr("c")?.toIntOrNull() ?: 1) { append("&nbsp;") }
                    "tab" -> append(" ")
                    "line-break" -> append("<br>")
                    "a" -> append("<a href=\"").append(esc(node.attr("href").orEmpty())).append("\">").append(inline(node)).append("</a>")
                    "span" -> {
                        val tags = styles.tags[node.attr("style-name")].orEmpty()
                        tags.forEach { append('<').append(it).append('>') }
                        append(inline(node))
                        tags.reversed().forEach { append("</").append(it).append('>') }
                    }
                    "note" -> {
                        val body = StringBuilder()
                        node.child("note-body")?.let { blocks(it, body) }
                        notes += body.toString()
                        append("<sup><a href=\"#note").append(notes.size).append("\">").append(notes.size).append("</a></sup>")
                    }
                    "frame" -> {
                        // A picture in a line of text stands in it, not in a paragraph of its own.
                        val image = node.child("image")?.attr("href")
                        if (image != null && drawable(image) && !image.contains("://")) append("<img src=\"/a/").append(esc(image)).append("\">") else frame(node, this)
                    }
                    "annotation", "bookmark", "bookmark-start", "bookmark-end", "soft-page-break", "sequence-decls" -> Unit
                    else -> append(inline(node))
                }
            }
        }

        fun notes(out: StringBuilder) {
            if (notes.isEmpty()) return
            out.append("<hr><div class=\"muted\" style=\"font-size:13px\">")
            notes.forEachIndexed { i, note -> out.append("<div id=\"note").append(i + 1).append("\"><sup>").append(i + 1).append("</sup> ").append(note).append("</div>") }
            out.append("</div>")
        }
    }
}
