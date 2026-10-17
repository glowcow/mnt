package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.doc.Cfb.Companion.u16
import dev.glowcow.mnt.view.doc.Cfb.Companion.u32
import dev.glowcow.mnt.view.html.Paper
import dev.glowcow.mnt.view.html.Running
import java.io.IOException
import kotlin.math.abs

/**
 * A Word 97–2003 document: the text of its body from the piece table, bold, italic, underline and the rest from
 * the runs, headings and lists from the paragraphs, tables, links and the pictures that stand in the text.
 */
object Doc {
    private class Run(val from: Int, val to: Int, val props: ByteArray)

    /** How characters are set: sizes in half-points, the font by its place in the font table, colours as 0xRRGGBB. */
    private data class Chp(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val under: Boolean = false,
        val strike: Boolean = false,
        val caps: Boolean = false,
        val smallCaps: Boolean = false,
        val vanish: Boolean = false,
        val script: Int = 0,
        val size: Int = 20,
        val font: Int = 0,
        val color: Int? = null,
        val mark: Int? = null,
        val picture: Int = -1,
    )

    /** How a paragraph is set: lengths in twentieths of a point, line spacing in 240ths of a line or in twips. */
    private data class Pap(
        val style: Int = 0,
        val list: Int = 0,
        val level: Int = 0,
        val table: Boolean = false,
        val rowEnd: Boolean = false,
        val align: Int = 0,
        val before: Int = 0,
        val after: Int = 0,
        val left: Int = 0,
        val right: Int = 0,
        val first: Int = 0,
        val line: Int = 240,
        val lineRule: Int = 1,
        val pageBefore: Boolean = false,
        val rightTab: Boolean = false,
        val def: TableDef? = null,
        val gap: Int? = null,
        val borders: List<Brc?>? = null,
        val height: Int = 0,
        val cantSplit: Boolean = false,
        val header: Boolean = false,
    )

    /** A row's cells: where their edges stand, in twips from the margin, and what each says of itself. */
    private class TableDef(val edges: IntArray, val cells: List<CellDef>) {
        // sprmTSetBrc80 and sprmTSetBrc: a line for the sides it names (top 1, left 2, bottom 4, right 8) of a range of cells.
        fun lines(g: ByteArray, at: Int, size: Int): TableDef {
            val first = g.getOrElse(at + 1) { 0 }.toInt() and 0xFF
            val last = g.getOrElse(at + 2) { 0 }.toInt() and 0xFF
            val sides = g.getOrElse(at + 3) { 0 }.toInt() and 0x0F
            val line = if (size == 4) brc80(g, at + 4) else brc(g, at + 4)
            return TableDef(edges, cells.mapIndexed { i, c ->
                if (i !in first until last) c else CellDef(c.flags, c.lines.mapIndexed { side, old -> if (sides and (1 shl side) != 0) line else old })
            })
        }
    }

    /** A cell's merge flags and its lines: top, left, bottom, right; null where it says nothing. */
    private class CellDef(val flags: Int, val lines: List<Brc?>)

    /** A line: its width in points and colour; [none] when the line is asked to be absent. */
    private class Brc(val width: Float, val color: Int, val none: Boolean)

    private class Std(val base: Int, val sti: Int, val kind: Int, val papx: ByteArray?, val chpx: ByteArray?)

    /** The document's styles, each resolved along the styles it is based on. */
    private class Styles(private val stds: List<Std?>, private val font: Int, private val data: ByteArray?) {
        private val chps = HashMap<Int, Chp>()
        private val paps = HashMap<Int, Pap>()

        fun sti(istd: Int) = stds.getOrNull(istd)?.sti ?: -1

        fun chp(istd: Int, depth: Int = 0): Chp = chps[istd] ?: run {
            val std = stds.getOrNull(istd)
            val base = if (std == null || std.base == 0x0FFF || std.base == istd || depth > 10) Chp(font = font) else chp(std.base, depth + 1)
            (std?.chpx?.let { chp(it, base, base) } ?: base).also { chps[istd] = it }
        }

        fun pap(istd: Int, depth: Int = 0): Pap = paps[istd] ?: run {
            val std = stds.getOrNull(istd)
            val base = if (std == null || std.base == 0x0FFF || std.base == istd || depth > 10) Pap() else pap(std.base, depth + 1)
            (std?.papx?.let { pap(it, 0, base.copy(style = istd)) } ?: base.copy(style = istd)).also { paps[istd] = it }
        }

        /** [grpprl] laid over [start]; [style] is what a toggle of 0x80 or 0x81 refers to. */
        fun chp(grpprl: ByteArray, start: Chp, style: Chp): Chp {
            var c = start
            fun toggle(b: Int, styled: Boolean) = when (b) {
                0 -> false
                1 -> true
                0x80 -> styled
                0x81 -> !styled
                else -> styled
            }
            sprms(grpprl, 0) { sprm, at, _ ->
                val b = grpprl.getOrElse(at) { 0 }.toInt() and 0xFF
                c = when (sprm) {
                    0x0835 -> c.copy(bold = toggle(b, style.bold))
                    0x0836 -> c.copy(italic = toggle(b, style.italic))
                    0x0837, 0x2A53 -> c.copy(strike = toggle(b, style.strike))
                    0x083A -> c.copy(smallCaps = toggle(b, style.smallCaps))
                    0x083B -> c.copy(caps = toggle(b, style.caps))
                    0x083C -> c.copy(vanish = toggle(b, style.vanish))
                    0x2A3E -> c.copy(under = b != 0)
                    0x2A48 -> c.copy(script = b)
                    0x4A43 -> c.copy(size = u16(grpprl, at))
                    0x4A4F -> c.copy(font = u16(grpprl, at))
                    0x2A42 -> c.copy(color = ICO.getOrNull(b))
                    0x6870 -> c.copy(color = if (grpprl.getOrElse(at + 3) { 0 }.toInt() and 0xFF == 0xFF) null else rgb(grpprl, at))
                    0x2A0C -> c.copy(mark = ICO.getOrNull(b)?.takeIf { b != 0 })
                    0x6A03 -> c.copy(picture = u32(grpprl, at))
                    0x2A33 -> style
                    0x4A30 -> stds.getOrNull(u16(grpprl, at))?.chpx?.let { chp(it, c, c) } ?: c
                    else -> c
                }
            }
            return c
        }

        /** The paragraph properties of [grpprl] from [from], laid over [start]. */
        fun pap(grpprl: ByteArray, from: Int, start: Pap): Pap {
            var p = start
            sprms(grpprl, from) { sprm, at, _ ->
                val b = grpprl.getOrElse(at) { 0 }.toInt() and 0xFF
                fun s16() = u16(grpprl, at).toShort().toInt()
                p = when (sprm) {
                    0x4600 -> pap(u16(grpprl, at)).let { st -> st.copy(table = p.table, rowEnd = p.rowEnd) }
                    0x460B -> p.copy(list = u16(grpprl, at))
                    0x260A -> p.copy(level = b)
                    0x2416 -> p.copy(table = b != 0)
                    0x2417, 0x244C -> p.copy(rowEnd = b != 0)
                    0x2403, 0x2461 -> p.copy(align = b)
                    0x2407 -> p.copy(pageBefore = b != 0)
                    0x840E, 0x845D -> p.copy(right = s16())
                    0x840F, 0x845E -> p.copy(left = s16())
                    0x8411, 0x8460 -> p.copy(first = s16())
                    0xA413 -> p.copy(before = u16(grpprl, at))
                    0xA414 -> p.copy(after = u16(grpprl, at))
                    0x6412 -> p.copy(line = s16(), lineRule = u16(grpprl, at + 2))
                    0xC60D, 0xC615 -> if (rightTab(grpprl, at, sprm == 0xC615)) p.copy(rightTab = true) else p
                    0xD608 -> p.copy(def = tableDef(grpprl, at))
                    0x9602 -> p.copy(gap = s16())
                    0x9407 -> p.copy(height = s16())
                    0x3403, 0x3644 -> p.copy(cantSplit = b != 0)
                    0x3404 -> p.copy(header = b != 0)
                    0xD620 -> p.copy(def = p.def?.lines(grpprl, at, 4))
                    0xD62F -> p.copy(def = p.def?.lines(grpprl, at, 8))
                    0xD605 -> p.copy(borders = (0 until 6).map { brc80(grpprl, at + 1 + it * 4) })
                    0xD613 -> p.copy(borders = (0 until 6).map { brc(grpprl, at + 1 + it * 8) })
                    // Properties too many for a page of their own wait in the Data stream.
                    0x6646 -> data?.let { d -> u32(grpprl, at).takeIf { it >= 0 && it + 2 <= d.size }?.let { o -> pap(d.copyOfRange(o + 2, minOf(d.size, o + 2 + u16(d, o))), 0, p) } } ?: p
                    else -> p
                }
            }
            return p
        }
    }

    fun convert(bytes: ByteArray): Converted {
        val cfb = Cfb(bytes)
        val word = cfb.stream("WordDocument") ?: throw IOException("No WordDocument stream")
        if (u16(word, 0) != 0xA5EC) throw IOException("Not a Word document")
        val flags = u16(word, 0x0A)
        if (flags and 0x0100 != 0) throw IOException("The document is encrypted")
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw IOException("No table stream")
        val data = cfb.stream("Data")

        // The FIB: fixed part, then counts of shorts, of ints and of offset–length pairs.
        val csw = u16(word, 32)
        val lw = 34 + csw * 2
        val cslw = u16(word, lw)
        val ccpText = u32(word, lw + 2 + 3 * 4)
        val rg = lw + 2 + cslw * 4 + 2
        fun fc(i: Int) = u32(word, rg + i * 8)
        fun lcb(i: Int) = u32(word, rg + i * 8 + 4)

        val pieces = pieces(table, fc(33), lcb(33))
        val chars = runs(word, table, fc(12), lcb(12), chpx = true)
        val paras = runs(word, table, fc(13), lcb(13), chpx = false)
        val styles = styles(table, fc(1), lcb(1), data)
        val fonts = fonts(table, fc(15), lcb(15))
        val sections = sections(word, table, fc(6), lcb(6))
        // A section mark that starts a section on the same page is no page break.
        val continuous = sections.zipWithNext().filter { (_, next) -> next.bkc == 0 }.mapTo(HashSet()) { (it, _) -> it.end - 1 }
        val pictures = ArrayList<ByteArray>()

        // A story of the document as HTML: the body, or a header or footer, by its character positions.
        fun story(from: Int, to: Int): String {
            val out = StringBuilder()
            val para = StringBuilder()
            var cell = StringBuilder()
            var row: MutableList<String>? = null
            val rows = ArrayList<Pair<List<String>, Pap>>()
            // Fields: whether their result is being shown, and the link a HYPERLINK field makes of it.
            val fields = ArrayList<Pair<StringBuilder, Boolean>>()
            var shown: Chp? = null
            var spanned = false
            var base = Chp()
            var linked = false

            fun props(fcAt: Int): Pap = find(paras, fcAt)?.props?.takeIf { it.size >= 2 }?.let { styles.pap(it, 2, styles.pap(u16(it, 0))) } ?: styles.pap(0)

            fun family(c: Chp) = fonts.getOrNull(c.font)?.let { (name, ff) -> Docx.family(if (ff == 1 && Docx.family(name).startsWith("Arimo")) "serif" else name) }
            // What of a run's setting differs from its paragraph's, as the paged layout reads it.
            fun looksOf(c: Chp, around: Chp?): String = buildString {
                if (c.size != around?.size) append("--sz:").append(Docx.points(c.size / 2f)).append(';')
                if (c.font != around?.font) family(c)?.let { append("--ff:").append(it).append(';') }
                if (c.color != around?.color && c.color != null) append("--fc:").append(Edge.hex(c.color)).append(';')
                if (c.mark != around?.mark && c.mark != null) append("--hl:").append(Edge.hex(c.mark)).append(';')
                if (c.caps && around?.caps != true) append("text-transform:uppercase;")
                if (c.smallCaps && around?.smallCaps != true) append("font-variant:small-caps;")
            }
            fun close() {
                val s = shown ?: return
                if (s.script == 1) para.append("</sup>")
                if (s.script == 2) para.append("</sub>")
                if (s.strike) para.append("</s>")
                if (s.under) para.append("</u>")
                if (s.italic) para.append("</i>")
                if (s.bold) para.append("</b>")
                if (spanned) para.append("</span>")
                spanned = false
                shown = null
            }
            fun open(c: Chp) {
                val now = c.copy(picture = -1, vanish = false)
                if (now == shown) return
                close()
                val look = looksOf(now, base)
                spanned = look.isNotEmpty()
                if (spanned) para.append("<span style=\"").append(look).append("\">")
                if (now.bold) para.append("<b>")
                if (now.italic) para.append("<i>")
                if (now.under) para.append("<u>")
                if (now.strike) para.append("<s>")
                if (now.script == 1) para.append("<sup>")
                if (now.script == 2) para.append("<sub>")
                shown = now
            }

            fun endTable() {
                if (rows.isEmpty()) return
                // The columns are every edge any row has: a cell spans those between its own.
                val grid = rows.flatMap { r -> r.second.def?.edges?.toList().orEmpty() }.distinct().sorted()
                out.append("<div class=\"wide\"><table")
                if (grid.size > 1) {
                    out.append(" style=\"--tw:").append(Docx.points((grid.last() - grid.first()) / 20f))
                    out.append(";--tl:").append(Docx.points(grid.first() / 20f)).append("\"><colgroup>")
                    for (i in 1 until grid.size) out.append("<col style=\"--cw:").append(Docx.points((grid[i] - grid[i - 1]) / 20f)).append("\">")
                    out.append("</colgroup")
                }
                out.append('>')
                // A cell merged into the one above is left out, and that one spans the rows.
                val spans = HashMap<Pair<Int, Int>, Int>()
                val skipped = HashSet<Pair<Int, Int>>()
                for ((r, entry) in rows.withIndex()) {
                    val d = entry.second.def ?: continue
                    for (c in entry.first.indices) {
                        val f = d.cells.getOrNull(c)?.flags ?: 0
                        if (f and 0x20 != 0 && f and 0x40 == 0) {
                            var up = r - 1
                            while (up >= 0 && Pair(up, c) in skipped) up--
                            if (up >= 0) {
                                spans[Pair(up, c)] = (spans[Pair(up, c)] ?: 1) + 1
                                skipped += Pair(r, c)
                            }
                        }
                    }
                }
                for ((r, entry) in rows.withIndex()) {
                    val (cells, p) = entry
                    val d = p.def
                    out.append("<tr")
                    // A row Word may not break across pages goes to the next sheet whole.
                    // A heading row stands again at the top of every sheet the table goes on to.
                    val classes = listOfNotNull("ns".takeIf { p.cantSplit }, "hr".takeIf { p.header })
                    if (classes.isNotEmpty()) out.append(" class=\"").append(classes.joinToString(" ")).append('"')
                    if (p.height != 0) out.append(" style=\"height:").append(Docx.points(kotlin.math.abs(p.height) / 20f)).append('"')
                    out.append('>')
                    for ((c, html) in cells.withIndex()) {
                        if (Pair(r, c) in skipped) continue
                        out.append("<td")
                        spans[Pair(r, c)]?.let { out.append(" rowspan=\"").append(it).append('"') }
                        if (d != null && c + 1 < d.edges.size) {
                            val span = grid.indexOf(d.edges[c + 1]) - grid.indexOf(d.edges[c])
                            if (span > 1) out.append(" colspan=\"").append(span).append('"')
                        }
                        if (d != null) {
                            val style = StringBuilder()
                            val own = d.cells.getOrNull(c)?.lines
                            val outer = p.borders
                            val last = c == cells.size - 1
                            // A cell's own line, else the table's: its outer edge, or the line between cells.
                            listOf(
                                "top" to (own?.get(0) ?: outer?.get(if (r == 0) 0 else 4)),
                                "left" to (own?.get(1) ?: outer?.get(if (c == 0) 1 else 5)),
                                "bottom" to (own?.get(2) ?: outer?.get(if (r == rows.size - 1) 2 else 4)),
                                "right" to (own?.get(3) ?: outer?.get(if (last) 3 else 5)),
                            ).forEach { (side, line) ->
                                style.append("border-").append(side).append(':')
                                if (line == null || line.none) style.append("none") else style.append(Docx.points(line.width)).append(" solid ").append(Edge.hex(line.color))
                                style.append(';')
                            }
                            p.gap?.let { style.append("padding:0 ").append(Docx.points(it / 20f)).append(';') }
                            out.append(" style=\"").append(style).append('"')
                        }
                        out.append('>').append(html).append("</td>")
                    }
                    out.append("</tr>")
                }
                out.append("</table></div>")
                rows.clear()
            }

            fun endParagraph(p: Pap, into: StringBuilder) {
                close()
                if (linked) {
                    para.append("</a>")
                    linked = false
                }
                var inner = para.toString()
                para.setLength(0)
                val sti = styles.sti(p.style)
                // Title and Subtitle are built-in styles 62 and 74.
                val level = when {
                    sti in 1..9 -> sti
                    sti == 62 -> 1
                    sti == 74 -> 2
                    else -> 0
                }
                val style = StringBuilder()
                when (p.align) {
                    1 -> style.append("text-align:center;")
                    2 -> style.append("text-align:right;")
                    3, 4, 5 -> style.append("text-align:justify;")
                }
                style.append("--sb:").append(Docx.points(p.before / 20f)).append(";--sa:").append(Docx.points(p.after / 20f)).append(';')
                when {
                    p.lineRule == 1 -> if (p.line != 240) style.append("--lh:").append(String.format(java.util.Locale.US, "%.3f", Docx.LINE * p.line / 240f)).append(';')
                    p.line < 0 -> style.append("--lh:").append(Docx.points(-p.line / 20f)).append(';')
                    p.line > 0 -> style.append("--lh:max(1.15em,").append(Docx.points(p.line / 20f)).append(");")
                }
                if (p.left != 0) style.append("--il:").append(Docx.points(p.left / 20f)).append(';')
                if (p.right != 0) style.append("--ir:").append(Docx.points(p.right / 20f)).append(';')
                if (p.first != 0) style.append("--ti:").append(Docx.points(p.first / 20f)).append(';')
                style.append(looksOf(base, null))
                // Tabs keep their places: a right-aligned stop sends what follows the last one to the right edge.
                if (inner.contains('\t')) {
                    val cut = inner.lastIndexOf('\t')
                    inner = if (p.rightTab) inner.substring(0, cut) + "<span style=\"float:right\">" + inner.substring(cut + 1) + "</span>" else inner
                    if (inner.contains('\t')) style.append("white-space:pre-wrap;tab-size:35.4pt;")
                }
                // Word keeps every space: a run of them lines things up, as the signatures under a page do.
                if (inner.contains("  ") && !style.contains("white-space")) style.append("white-space:pre-wrap;")
                if (p.pageBefore && into === out) out.append("<hr class=\"pb\">")
                val tag = if (level > 0) "h" + level.coerceAtMost(6) else "p"
                into.append('<').append(tag).append(" style=\"").append(style).append("\">")
                if (p.list > 0) into.append("• ")
                into.append(inner.ifBlank { "&nbsp;" }).append("</").append(tag).append('>')
            }

            for ((cpStart, cpEnd, fcStart, compressed) in pieces) {
                if (cpStart >= to) break
                if (cpEnd <= from) continue
                for (k in maxOf(cpStart, from) until minOf(cpEnd, to)) {
                    val fcAt = fcStart + (k - cpStart) * if (compressed) 1 else 2
                    val ch = if (compressed) cp1252(word.getOrElse(fcAt) { 0 }.toInt() and 0xFF) else u16(word, fcAt).toChar()
                    val p = props(fcAt)
                    base = styles.chp(p.style)
                    val c = find(chars, fcAt)?.props?.let { styles.chp(it, base, base) } ?: base
                    val inField = fields.lastOrNull()
                    when (ch.code) {
                        0x13 -> fields += StringBuilder() to false
                        0x14 -> if (inField != null) {
                            val code = inField.first.trim().split(Regex("\\s+")).firstOrNull()?.uppercase()
                            // A page's number is the sheet's; the result Word kept stays hidden with the instruction.
                            if (code == "PAGE" || code == "NUMPAGES") {
                                open(c)
                                para.append(if (code == "PAGE") "<span class=\"pn\"></span>" else "<span class=\"np\"></span>")
                            } else {
                                fields[fields.lastIndex] = inField.first to true
                                val href = Regex("HYPERLINK\\s+(?:\\\\l\\s+)?\"([^\"]+)\"").find(inField.first)?.groupValues?.get(1)
                                if (href != null && fields.size == 1) {
                                    close()
                                    para.append("<a href=\"").append(esc(href)).append("\">")
                                    linked = true
                                }
                            }
                        }
                        0x15 -> if (inField != null) {
                            fields.removeAt(fields.lastIndex)
                            if (linked && fields.isEmpty()) {
                                close()
                                para.append("</a>")
                                linked = false
                            }
                        }
                        else -> when {
                            // The instruction of a field is not shown, only its result.
                            inField != null && !inField.second -> inField.first.append(ch)
                            c.vanish && ch != '\r' && ch != '\u0007' -> Unit
                            ch == '\r' -> {
                                if (!p.table && row == null) endTable()
                                endParagraph(p, if (p.table) cell else out)
                            }
                            ch == '\u0007' -> if (p.rowEnd) {
                                para.setLength(0)
                                shown = null
                                row?.let { rows += it to p }
                                row = null
                            } else {
                                endParagraph(p, cell)
                                if (row == null) row = ArrayList()
                                row?.add(cell.toString())
                                cell = StringBuilder()
                            }
                            ch == '\u000B' -> para.append("<br>")
                            ch == '\u000C' -> {
                                if (para.isNotEmpty()) endParagraph(p, if (p.table) cell else out)
                                if (row == null && k !in continuous) {
                                    endTable()
                                    out.append("<hr class=\"pb\">")
                                }
                            }
                            ch == '\u0001' -> if (c.picture >= 0 && data != null) picture(data, c.picture)?.let {
                                pictures += it
                                para.append("<img src=\"/a/pic").append(pictures.size - 1).append("\">")
                            }
                            ch == '\t' -> {
                                open(c)
                                para.append('\t')
                            }
                            ch == '\u001E' -> para.append('-')
                            ch.code < 0x20 || ch == '\u001F' -> Unit
                            else -> {
                                if (row == null && p.table) row = ArrayList()
                                open(c)
                                para.append(esc(ch.toString()))
                            }
                        }
                    }
                }
            }
            if (para.isNotEmpty()) endParagraph(Pap(), out)
            endTable()
            return out.toString()
        }

        val body = story(0, ccpText)
        val first = sections.firstOrNull()
        val paper = first?.paper ?: Paper.A4
        // Headers and footers follow the footnotes: six stories of separators, then six for each section — even and
        // odd header, even and odd footer, the first page's header and footer. The first section's are every sheet's.
        val hdd = ccpText + u32(word, lw + 2 + 4 * 4)
        val marks = if (lcb(11) >= 8) IntArray(lcb(11) / 4) { u32(table, fc(11) + it * 4) } else IntArray(0)
        fun hf(i: Int): String? = if (i + 1 < marks.size && marks[i + 1] > marks[i]) story(hdd + marks[i], hdd + marks[i + 1]).takeIf { it.contains(Regex(">[^<\\s&]|<img|class=\"pn")) } else null
        val running = if (first == null || marks.size < 13) null else Running(
            header = hf(7).orEmpty(),
            footer = hf(9).orEmpty(),
            headerAt = first.headerAt,
            footerAt = first.footerAt,
            firstHeader = if (first.titlePage) hf(10).orEmpty() else null,
            firstFooter = if (first.titlePage) hf(11).orEmpty() else null,
        ).takeIf { it.header.isNotEmpty() || it.footer.isNotEmpty() || !it.firstHeader.isNullOrEmpty() || !it.firstFooter.isNullOrEmpty() }
        return Converted(listOf(Converted.Part("", body, paper, running)), { name -> name.removePrefix("pic").toIntOrNull()?.let { pictures.getOrNull(it) } })
    }

    private data class Piece(val cpStart: Int, val cpEnd: Int, val fc: Int, val compressed: Boolean)

    // The piece table: where each stretch of characters lies in the WordDocument stream, and in which width.
    private fun pieces(table: ByteArray, fcClx: Int, lcbClx: Int): List<Piece> {
        var at = fcClx
        val end = fcClx + lcbClx
        while (at < end) {
            when (table[at].toInt()) {
                1 -> at += 3 + Cfb.s16(table, at + 1)
                2 -> {
                    val lcb = u32(table, at + 1)
                    val plc = at + 5
                    val n = (lcb - 4) / 12
                    return (0 until n).map { i ->
                        val fc = u32(table, plc + (n + 1) * 4 + i * 8 + 2)
                        val compressed = fc and 0x40000000 != 0
                        Piece(u32(table, plc + i * 4), u32(table, plc + (i + 1) * 4), if (compressed) (fc and 0x3FFFFFFF) / 2 else fc, compressed)
                    }
                }
                else -> break
            }
        }
        throw IOException("No piece table")
    }

    // The runs of character or paragraph properties, read from the pages of the bin table.
    private fun runs(word: ByteArray, table: ByteArray, fc: Int, lcb: Int, chpx: Boolean): List<Run> {
        if (lcb <= 4) return emptyList()
        val n = (lcb - 4) / 8
        val out = ArrayList<Run>()
        for (i in 0 until n) {
            val page = (u32(table, fc + (n + 1) * 4 + i * 4) and 0x3FFFFF) * 512
            if (page + 512 > word.size) continue
            val count = word[page + 511].toInt() and 0xFF
            for (k in 0 until count) {
                val from = u32(word, page + k * 4)
                val to = u32(word, page + (k + 1) * 4)
                val props = if (chpx) {
                    val off = (word[page + (count + 1) * 4 + k].toInt() and 0xFF) * 2
                    if (off == 0) ByteArray(0) else word.copyOfRange(page + off + 1, minOf(page + 512, page + off + 1 + (word[page + off].toInt() and 0xFF)))
                } else {
                    val off = (word[page + (count + 1) * 4 + k * 13].toInt() and 0xFF) * 2
                    if (off == 0) {
                        ByteArray(0)
                    } else {
                        var cb = word[page + off].toInt() and 0xFF
                        var start = page + off + 1
                        val length = if (cb == 0) {
                            cb = word[page + off + 1].toInt() and 0xFF
                            start++
                            cb * 2
                        } else {
                            cb * 2 - 1
                        }
                        word.copyOfRange(start, minOf(page + 512, start + length))
                    }
                }
                out += Run(from, to, props)
            }
        }
        return out.sortedBy { it.from }
    }

    private fun find(runs: List<Run>, fc: Int): Run? {
        var lo = 0
        var hi = runs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = runs[mid]
            when {
                fc < r.from -> hi = mid - 1
                fc >= r.to -> lo = mid + 1
                else -> return r
            }
        }
        return null
    }

    // The style sheet: each style's base, its sti (1 to 9 are the headings), kind, and its paragraph and run properties.
    private fun styles(table: ByteArray, fc: Int, lcb: Int, data: ByteArray?): Styles {
        if (lcb <= 0) return Styles(emptyList(), 0, data)
        val cbStshi = u16(table, fc)
        val count = u16(table, fc + 2)
        val cbBase = u16(table, fc + 4)
        // The font of text no style names: the first of the three standard ones.
        val font = if (cbStshi >= 14) u16(table, fc + 2 + 12) else 0
        var at = fc + 2 + cbStshi
        val stds = (0 until count).map {
            val cb = u16(table, at)
            val start = at + 2
            at = start + cb
            if (cb == 0 || start + cbBase > table.size) return@map null
            val sti = u16(table, start) and 0x0FFF
            val kind = u16(table, start + 2) and 0x000F
            val base = u16(table, start + 2) ushr 4
            val upxCount = u16(table, start + 4) and 0x000F
            // The name, then the UPXs, each on an even offset from the STD's start.
            var k = start + cbBase
            val nameLength = u16(table, k)
            k += 2 + (nameLength + 1) * 2
            val upx = ArrayList<ByteArray>()
            repeat(upxCount) {
                if ((k - start) % 2 != 0) k++
                if (k + 2 > start + cb) return@repeat
                val size = u16(table, k)
                upx += table.copyOfRange(k + 2, minOf(start + cb, k + 2 + size))
                k += 2 + size
            }
            when (kind) {
                1 -> Std(base, sti, kind, upx.getOrNull(0)?.takeIf { it.size >= 2 }?.copyOfRange(2, upx[0].size), upx.getOrNull(1))
                2 -> Std(base, sti, kind, null, upx.getOrNull(0))
                else -> Std(base, sti, kind, null, null)
            }
        }
        return Styles(stds, font, data)
    }

    // The font table: each font's name and its family (1 is a face with serifs, 3 a fixed one).
    private fun fonts(table: ByteArray, fc: Int, lcb: Int): List<Pair<String, Int>> {
        if (lcb <= 4) return emptyList()
        var count = u16(table, fc)
        var at = fc + 2
        if (count == 0xFFFF) {
            count = u16(table, at)
            at += 2
        }
        at += 2
        val out = ArrayList<Pair<String, Int>>()
        repeat(count) {
            if (at >= table.size) return out
            val size = table[at].toInt() and 0xFF
            val ff = (table.getOrElse(at + 1) { 0 }.toInt() shr 4) and 0x07
            val name = StringBuilder()
            var k = at + 40
            while (k + 1 < minOf(table.size, at + 1 + size)) {
                val ch = u16(table, k)
                if (ch == 0) break
                name.append(ch.toChar())
                k += 2
            }
            out += name.toString() to ff
            at += 1 + size
        }
        return out
    }

    /** A section: the character position it ends at, how it begins (0: on the same page), and its sheet. */
    private class Section(val end: Int, val bkc: Int, val paper: Paper, val headerAt: Float, val footerAt: Float, val titlePage: Boolean)

    // The sections and their properties, in twentieths of a point.
    private fun sections(word: ByteArray, table: ByteArray, fc: Int, lcb: Int): List<Section> {
        // The table of sections: n + 1 character positions, then n descriptors of 12 bytes.
        val n = (lcb - 4) / 16
        if (n < 1 || fc + 4 * (n + 1) + 12 * n > table.size) return emptyList()
        return (0 until n).map { i ->
            val end = u32(table, fc + 4 * (i + 1))
            val sepx = u32(table, fc + 4 * (n + 1) + i * 12 + 2)
            val a = Paper.A4
            var width = a.width
            var height = a.height
            var top = a.top
            var right = a.right
            var bottom = a.bottom
            var left = a.left
            var bkc = 2
            var headerAt = 36f
            var footerAt = 36f
            var titlePage = false
            if (sepx > 0 && sepx + 2 <= word.size) {
                val grpprl = word.copyOfRange(sepx + 2, minOf(word.size, sepx + 2 + u16(word, sepx)))
                sprms(grpprl, 0) { sprm, at, _ ->
                    val value = abs(u16(grpprl, at).toShort().toInt()) / 20f
                    when (sprm) {
                        0x3009 -> bkc = grpprl.getOrElse(at) { 2 }.toInt() and 0xFF
                        0x300A -> titlePage = grpprl.getOrElse(at) { 0 }.toInt() != 0
                        0xB017 -> headerAt = value
                        0xB018 -> footerAt = value
                        0xB01F -> width = value
                        0xB020 -> height = value
                        0xB021 -> left = value
                        0xB022 -> right = value
                        0x9023 -> top = value
                        0x9024 -> bottom = value
                    }
                }
            }
            Section(end, bkc, Paper(width, height, top, right, bottom, left), headerAt, footerAt, titlePage)
        }
    }

    // sprmTDefTable: the count of cells, their edges, then a TC80 of 20 bytes for each.
    private fun tableDef(g: ByteArray, at: Int): TableDef? {
        val count = g.getOrElse(at + 2) { 0 }.toInt() and 0xFF
        if (count == 0 || at + 3 + (count + 1) * 2 > g.size) return null
        val edges = IntArray(count + 1) { u16(g, at + 3 + it * 2).toShort().toInt() }
        val tc = at + 3 + (count + 1) * 2
        val cells = (0 until count).map { i ->
            val o = tc + i * 20
            if (o + 20 > g.size) CellDef(0, listOf(null, null, null, null)) else CellDef(u16(g, o), (0 until 4).map { brc80(g, o + 4 + it * 4) })
        }
        return TableDef(edges, cells)
    }

    // A Brc80: width in eighths of a point, kind (0 none), colour as an index; all ones, or all zeros in a cell's own
    // TC80 as Word 2000 writes it, says nothing.
    private fun brc80(g: ByteArray, at: Int): Brc? {
        if (at + 4 > g.size || u32(g, at) == -1 || u32(g, at) == 0) return null
        val type = g[at + 1].toInt() and 0xFF
        if (type == 0xFF) return null
        return Brc((g[at].toInt() and 0xFF) / 8f, ICO.getOrNull(g[at + 2].toInt() and 0xFF) ?: 0, type == 0)
    }

    // A Brc: a colour, then the width in eighths of a point and the kind.
    private fun brc(g: ByteArray, at: Int): Brc? {
        if (at + 8 > g.size || u32(g, at + 4) == -1) return null
        val type = g[at + 5].toInt() and 0xFF
        if (type == 0xFF) return null
        val auto = g[at + 3].toInt() and 0xFF == 0xFF
        return Brc((g[at + 4].toInt() and 0xFF) / 8f, if (auto) 0 else rgb(g, at), type == 0)
    }

    // Whether a change of tab stops adds one that sets text against the right.
    private fun rightTab(g: ByteArray, at: Int, close: Boolean): Boolean {
        var k = at + 1
        val del = g.getOrElse(k) { 0 }.toInt() and 0xFF
        // sprmPChgTabs gives a tolerance for each stop it deletes.
        k += 1 + del * if (close) 4 else 2
        if (k >= g.size) return false
        val add = g[k].toInt() and 0xFF
        val kinds = k + 1 + add * 2
        return (0 until add).any { (g.getOrElse(kinds + it) { 0 }.toInt() and 0x07) == 2 }
    }

    private fun rgb(g: ByteArray, at: Int): Int =
        (g.getOrElse(at) { 0 }.toInt() and 0xFF shl 16) or (g.getOrElse(at + 1) { 0 }.toInt() and 0xFF shl 8) or (g.getOrElse(at + 2) { 0 }.toInt() and 0xFF)

    // The sixteen colours a run or a line may name by number; 0 is automatic.
    private val ICO = listOf(
        0x000000, 0x000000, 0x0000FF, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0xFFFF00, 0xFFFFFF,
        0x000080, 0x008080, 0x008000, 0x800080, 0x800000, 0x808000, 0x808080, 0xC0C0C0,
    )

    private inline fun sprms(grpprl: ByteArray, from: Int, each: (Int, Int, Int) -> Unit) {
        var at = from
        while (at + 2 <= grpprl.size) {
            val sprm = u16(grpprl, at)
            val operand = at + 2
            val size = when (sprm ushr 13) {
                0, 1 -> 1
                2, 4, 5 -> 2
                3 -> 4
                7 -> 3
                else -> if (sprm == 0xD608 || sprm == 0xD606) u16(grpprl, operand) + 1 else (grpprl.getOrElse(operand) { 0 }.toInt() and 0xFF) + 1
            }
            each(sprm, operand, size)
            at = operand + size
        }
    }

    // A picture in the Data stream: a PICF header, then Office drawing records with the image somewhere inside.
    private fun picture(data: ByteArray, at: Int): ByteArray? {
        if (at < 0 || at + 6 > data.size) return null
        val lcb = u32(data, at)
        val end = minOf(data.size, at + lcb)
        var k = at + u16(data, at + 4)
        while (k < end - 8) {
            val b = data[k].toInt() and 0xFF
            if (b == 0x89 && data[k + 1] == 'P'.code.toByte() && data[k + 2] == 'N'.code.toByte() && data[k + 3] == 'G'.code.toByte()) return data.copyOfRange(k, end)
            if (b == 0xFF && (data[k + 1].toInt() and 0xFF) == 0xD8 && (data[k + 2].toInt() and 0xFF) == 0xFF) return data.copyOfRange(k, end)
            k++
        }
        return null
    }

    private val high = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ"

    private fun cp1252(b: Int): Char = if (b in 0x80..0x9F) high[b - 0x80] else b.toChar()
}
