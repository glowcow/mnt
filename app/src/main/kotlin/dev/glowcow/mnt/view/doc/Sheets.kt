package dev.glowcow.mnt.view.doc

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** A sheet of a workbook as shown: the text of each cell by row and column from 0, and the cells merged into one. */
class Sheet(val name: String) {
    val rows = TreeMap<Int, TreeMap<Int, Cell>>()
    val merges = ArrayList<IntArray>()

    /** How the cells look, by the index a cell names; empty for a sheet that keeps no styles (CSV). */
    var looks: List<CellLook> = emptyList()

    /** Column widths in Excel's characters with their padding, row heights in points; the defaults for the rest. */
    val widths = HashMap<Int, Float>()
    var defaultWidth = DEFAULT_WIDTH
    val heights = HashMap<Int, Float>()
    var defaultHeight = 15f
    val hiddenRows = HashSet<Int>()
    val hiddenColumns = HashSet<Int>()

    /** Whether the grey lines between cells are shown. */
    var grid = true

    /** The size of the workbook's own font, in points: what its column widths are measured in. */
    var fontSize = 11f

    /** A cell: its text, whether it is a number, how it [look]s, and the colour its number format gives it. */
    class Cell(val text: String, val number: Boolean = false, val look: Int = 0, val color: Int? = null)

    /** Puts a cell; an empty one only when its look shows (a fill or an edge). */
    fun put(row: Int, column: Int, text: String, number: Boolean = false, look: Int = 0, color: Int? = null) {
        if (row < 0 || column < 0) return
        if (text.isEmpty() && looks.getOrNull(look)?.shows != true) return
        rows.getOrPut(row) { TreeMap() }[column] = Cell(text, number, look, color)
    }

    /** Merges the cells from [r1],[c1] to [r2],[c2], all inclusive. */
    fun merge(r1: Int, c1: Int, r2: Int, c2: Int) {
        if (r2 >= r1 && c2 >= c1 && (r2 > r1 || c2 > c1)) merges += intArrayOf(r1, c1, r2, c2)
    }

    companion object {
        /** Excel's column with no width of its own: eight digits and the padding. */
        const val DEFAULT_WIDTH = 8.43f + 0.71f
    }
}

/** How a cell looks: font, fill, edges and alignment as a workbook keeps them; colours are 0xRRGGBB, null when automatic. */
data class CellLook(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Points; null for the workbook's own size. */
    val size: Float? = null,
    /** `serif` or `mono` for a face of that kind; null for a sans face. */
    val face: String? = null,
    val color: Int? = null,
    val fill: Int? = null,
    val left: Edge? = null,
    val right: Edge? = null,
    val top: Edge? = null,
    val bottom: Edge? = null,
    /** `left`, `center`, `right` or `justify`; null to go by the value: numbers right, text left. */
    val align: String? = null,
    /** `top` or `middle`; null for the bottom. */
    val valign: String? = null,
    val wrap: Boolean = false,
    val indent: Int = 0,
) {
    /** Whether an empty cell of this look can be seen. */
    val shows: Boolean get() = fill != null || left != null || right != null || top != null || bottom != null
}

/** A cell's edge: how thick, in CSS pixels, drawn how, in what colour (null: black). */
data class Edge(val width: Int, val style: String, val color: Int?) {
    // The stronger of two edges that meet is drawn, as Excel does: the thicker, then the solid.
    val weight: Int get() = width * 10 + if (style == "solid") 2 else if (style == "double") 3 else 1

    fun css() = "${width}px $style ${color?.let { hex(it) } ?: "#000"}"

    companion object {
        /** An edge by the name a workbook gives it: `thin`, `medium`, `thick`, `double`, `dashed`… */
        fun of(name: String?, color: Int?): Edge? = when (name?.lowercase()) {
            null, "", "none" -> null
            "thin", "hair" -> Edge(1, "solid", color)
            "medium" -> Edge(2, "solid", color)
            "thick" -> Edge(3, "solid", color)
            "double" -> Edge(3, "double", color)
            "dotted" -> Edge(1, "dotted", color)
            "mediumdashed", "mediumdashdot", "mediumdashdotdot", "slantdashdot" -> Edge(2, "dashed", color)
            else -> Edge(1, "dashed", color)
        }

        fun hex(rgb: Int) = "#%06x".format(rgb and 0xFFFFFF)
    }
}

/** Colours of a workbook: its theme's, Excel's old palette, and the tints laid over them. */
object Colors {
    /** The 64 colours of the palette a colour may name by its index. */
    val INDEXED = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    )

    /** A colour of the palette; 64 and above are the system's text and ground: automatic. */
    fun indexed(i: Int, palette: IntArray = INDEXED): Int? = palette.getOrNull(i)

    /** [rgb] made lighter (a [tint] above 0) or darker (below), as Excel works it out in HLS. */
    fun tint(rgb: Int, tint: Double): Int {
        if (tint == 0.0) return rgb
        val r = (rgb shr 16 and 0xFF) / 255.0
        val g = (rgb shr 8 and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        var l = (max + min) / 2
        val d = max - min
        val s = if (d == 0.0) 0.0 else d / (1 - abs(2 * l - 1))
        val h = when {
            d == 0.0 -> 0.0
            max == r -> 60 * (((g - b) / d).mod(6.0))
            max == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        l = if (tint < 0) l * (1 + tint) else l * (1 - tint) + tint
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60).mod(2.0) - 1))
        val m = l - c / 2
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return ch(r1) shl 16 or (ch(g1) shl 8) or ch(b1)
    }
}

object Sheets {
    /** At most this many cells go into a page: a phone lays out no more in good time. */
    private const val CELLS = 150_000

    /** A sheet as a table with column letters and row numbers; with [header] its first row heads the columns instead. */
    fun html(sheet: Sheet, header: Boolean = false): String {
        if (sheet.rows.isEmpty()) return "<p class=\"muted\">∅</p>"
        if (sheet.looks.isNotEmpty()) return styled(sheet)
        val lastRow = sheet.rows.lastKey()
        val lastColumn = sheet.rows.values.maxOf { it.lastKey() }.coerceAtMost(MAX_COLUMNS - 1)
        val columns = lastColumn + 1
        val rowsShown = minOf(lastRow + 1, (CELLS / columns).coerceAtLeast(1))
        val covered = HashSet<Long>()
        val spans = HashMap<Long, IntArray>()
        for (m in sheet.merges) {
            spans[key(m[0], m[1])] = intArrayOf(m[2] - m[0] + 1, m[3] - m[1] + 1)
            for (r in m[0]..m[2]) for (c in m[1]..m[3]) if (r != m[0] || c != m[1]) covered += key(r, c)
        }
        val out = StringBuilder(rowsShown * columns * 12)
        if (rowsShown <= lastRow) out.append("<p class=\"note\">").append(rowsShown).append(" / ").append(lastRow + 1).append("</p>")
        out.append("<table class=\"sheet\"><thead><tr><th></th>")
        val first = if (header) sheet.rows[0] else null
        for (c in 0 until columns) out.append("<th>").append(if (first != null) esc(first[c]?.text.orEmpty()) else letters(c)).append("</th>")
        out.append("</tr></thead><tbody>")
        for (r in (if (header) 1 else 0) until rowsShown) {
            val row = sheet.rows[r]
            out.append("<tr><th>").append(if (header) r else r + 1).append("</th>")
            for (c in 0 until columns) {
                val k = key(r, c)
                if (k in covered) continue
                val cell = row?.get(c)
                out.append("<td")
                if (cell?.number == true) out.append(" class=\"num\"")
                spans[k]?.let {
                    if (it[0] > 1) out.append(" rowspan=\"").append(it[0]).append('"')
                    if (it[1] > 1) out.append(" colspan=\"").append(it[1]).append('"')
                }
                out.append('>')
                if (cell != null) out.append(esc(cell.text))
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</tbody></table>")
        return out.toString()
    }

    // A workbook's sheet as Excel draws it: white as paper in either theme, its columns as wide and its rows as tall
    // as kept, its fonts, fills and edges, and text that runs on over the empty cells to its right.
    private fun styled(sheet: Sheet): String {
        val fontPx = sheet.fontSize * 4 / 3
        // Widths count the digits of the workbook's font; Arimo's are 0.556 of its size.
        val digit = fontPx * 0.556f
        val looks = sheet.looks
        fun look(r: Int, c: Int) = looks.getOrNull(sheet.rows[r]?.get(c)?.look ?: 0)
        // A number is never cut: its column widens to hold the widest, where Excel would show ####.
        val numbers = HashMap<Int, Float>()
        for ((_, row) in sheet.rows) for ((c, cell) in row) if (cell.number) {
            val size = looks.getOrNull(cell.look)?.size?.let { it * 4 / 3 } ?: fontPx
            numbers[c] = maxOf(numbers[c] ?: 0f, cell.text.length * size * 0.56f + 8)
        }
        fun width(c: Int) = maxOf(((sheet.widths[c] ?: sheet.defaultWidth) * digit).roundToInt(), numbers[c]?.roundToInt() ?: 0).coerceAtLeast(2)
        fun height(r: Int) = ((sheet.heights[r] ?: sheet.defaultHeight) * 4 / 3).roundToInt()

        val anchors = HashMap<Long, IntArray>()
        val covered = HashSet<Long>()
        for (m in sheet.merges) {
            anchors[key(m[0], m[1])] = m
            for (r in m[0]..m[2]) for (c in m[1]..m[3]) if (r != m[0] || c != m[1]) covered += key(r, c)
        }
        val lastRow = sheet.rows.lastKey()
        var lastColumn = maxOf(sheet.rows.values.maxOf { it.lastKey() }, sheet.merges.maxOfOrNull { it[3] } ?: 0)
        // Text that runs on past the last column brings the columns it covers.
        for ((_, row) in sheet.rows) for ((c, cell) in row) {
            if (cell.number || cell.text.isEmpty() || looks.getOrNull(cell.look)?.let { it.wrap || it.align != null && it.align != "left" } == true) continue
            if (row.higherKey(c) != null) continue
            val size = (looks.getOrNull(cell.look)?.size?.let { it * 4 / 3 } ?: fontPx)
            var need = cell.text.lines().maxOf { it.length } * size * 0.55f
            var k = c
            while (need > 0 && k < MAX_COLUMNS - 1) {
                if (k !in sheet.hiddenColumns) need -= width(k)
                if (need > 0) k++
            }
            lastColumn = maxOf(lastColumn, k)
        }
        lastColumn = lastColumn.coerceAtMost(MAX_COLUMNS - 1)
        val columns = (0..lastColumn).filter { it !in sheet.hiddenColumns }
        val rowsShown = minOf(lastRow, (CELLS / columns.size.coerceAtLeast(1)).coerceAtLeast(1) - 1)
        val head = (fontPx * 2.6f).roundToInt()

        val out = StringBuilder((rowsShown + 1) * columns.size * 16)
        out.append("<style>")
        for ((i, l) in looks.withIndex()) {
            val css = lookCss(l, fontPx)
            if (css.isNotEmpty()) out.append(".xl .s").append(i).append('{').append(css).append('}')
        }
        out.append("</style>")
        if (rowsShown < lastRow) out.append("<p class=\"note\">").append(rowsShown + 1).append(" / ").append(lastRow + 1).append("</p>")
        out.append("<table class=\"sheet xl").append(if (sheet.grid) "" else " nogrid").append("\" style=\"font-size:")
            .append(fontPx.roundToInt()).append("px;width:").append(head + columns.sumOf { width(it) }).append("px\"><colgroup><col style=\"width:")
            .append(head).append("px\">")
        for (c in columns) out.append("<col style=\"width:").append(width(c)).append("px\">")
        out.append("</colgroup><thead><tr><th></th>")
        for (c in columns) out.append("<th>").append(letters(c)).append("</th>")
        out.append("</tr></thead><tbody>")
        for (r in 0..rowsShown) {
            if (r in sheet.hiddenRows) continue
            val row = sheet.rows[r]
            out.append("<tr style=\"height:").append(height(r)).append("px\"><th>").append(r + 1).append("</th>")
            for (c in columns) {
                val k = key(r, c)
                if (k in covered) continue
                val cell = row?.get(c)
                val l = looks.getOrNull(cell?.look ?: 0)
                val m = anchors[k]
                val r2 = m?.get(2) ?: r
                val c2 = m?.get(3) ?: c
                out.append("<td")
                val classes = StringBuilder()
                if (cell != null && cell.look != 0 && looks.getOrNull(cell.look)?.let { lookCss(it, fontPx).isNotEmpty() } == true) classes.append('s').append(cell.look)
                if (cell?.number == true && l?.align == null) classes.append(if (classes.isEmpty()) "" else " ").append("num")
                // Text runs on over the empty cells to its right, as far as the next one that holds something.
                var room = 0
                if (cell != null && m == null && !cell.number && cell.text.isNotEmpty() && l?.wrap != true && (l?.align == null || l.align == "left")) {
                    var k2 = c + 1
                    while (k2 <= lastColumn && row?.get(k2)?.text.isNullOrEmpty() && key(r, k2) !in covered && anchors[key(r, k2)] == null) {
                        if (k2 !in sheet.hiddenColumns) room += width(k2)
                        k2++
                    }
                    if (room > 0) classes.append(if (classes.isEmpty()) "" else " ").append("sp")
                }
                if (classes.isNotEmpty()) out.append(" class=\"").append(classes).append('"')
                if (m != null) {
                    val rs = (r..r2).count { it !in sheet.hiddenRows }
                    val cs = (c..c2).count { it !in sheet.hiddenColumns }
                    if (rs > 1) out.append(" rowspan=\"").append(rs).append('"')
                    if (cs > 1) out.append(" colspan=\"").append(cs).append('"')
                }
                // Each cell draws its right and bottom edges, the stronger of its own and its neighbour's; the first row
                // and column draw their top and left too.
                val style = StringBuilder()
                val right = stronger(look(r, c2)?.right, look(r, c2 + 1)?.left)
                val bottom = stronger(look(r2, c)?.bottom, look(r2 + 1, c)?.top)
                if (right != null) style.append("border-right:").append(right.css()).append(';')
                if (bottom != null) style.append("border-bottom:").append(bottom.css()).append(';')
                if (r == 0 && l?.top != null) style.append("border-top:").append(l.top.css()).append(';')
                if (c == columns.first() && l?.left != null) style.append("border-left:").append(l.left.css()).append(';')
                if (style.isNotEmpty()) out.append(" style=\"").append(style).append('"')
                out.append('>')
                if (cell != null && cell.text.isNotEmpty()) {
                    if (room > 0) out.append("<span style=\"max-width:").append(width(c) + room - 6).append("px").append(cell.color?.let { ";color:" + Edge.hex(it) } ?: "").append("\">")
                    else if (cell.color != null) out.append("<span style=\"color:").append(Edge.hex(cell.color)).append("\">")
                    out.append(esc(cell.text))
                    if (room > 0 || cell.color != null) out.append("</span>")
                }
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</tbody></table>")
        return out.toString()
    }

    private fun stronger(a: Edge?, b: Edge?): Edge? = if (a == null) b else if (b == null || a.weight >= b.weight) a else b

    private fun lookCss(l: CellLook, fontPx: Float): String = buildString {
        if (l.bold) append("font-weight:700;")
        if (l.italic) append("font-style:italic;")
        if (l.underline || l.strike) append("text-decoration:").append(if (l.underline) "underline " else "").append(if (l.strike) "line-through" else "").append(';')
        l.size?.let { if (abs(it * 4 / 3 - fontPx) > 0.1f) append("font-size:").append("%.1f".format(java.util.Locale.US, it * 4 / 3)).append("px;") }
        when (l.face) {
            "serif" -> append("font-family:Tinos,serif;")
            "mono" -> append("font-family:Mono,monospace;")
        }
        l.color?.let { append("color:").append(Edge.hex(it)).append(';') }
        l.fill?.let { append("background:").append(Edge.hex(it)).append(';') }
        l.align?.let { append("text-align:").append(it).append(';') }
        l.valign?.let { append("vertical-align:").append(it).append(';') }
        if (l.wrap) append("white-space:pre-wrap;")
        if (l.indent > 0) append("padding-left:").append(3 + l.indent * 9).append("px;")
    }

    private fun key(r: Int, c: Int) = r.toLong() shl 20 or c.toLong()

    /** `A`, `B`, … `Z`, `AA` for columns from 0. */
    fun letters(column: Int): String {
        var k = column + 1
        val s = StringBuilder()
        while (k > 0) {
            k--
            s.insert(0, 'A' + k % 26)
            k /= 26
        }
        return s.toString()
    }

    /** The row and column from 0 of a reference like `B12`. */
    fun ref(ref: String): Pair<Int, Int>? {
        var c = 0
        var i = 0
        while (i < ref.length && ref[i].isLetter()) {
            c = c * 26 + (ref[i].uppercaseChar() - 'A' + 1)
            i++
        }
        val r = ref.substring(i).toIntOrNull() ?: return null
        return r - 1 to c - 1
    }

    const val MAX_COLUMNS = 300

    /** Whether the format [code] (or the built-in format [id]) shows a date or a time. */
    fun isDate(id: Int, code: String?): Boolean = NumberFormat.isDate(id, code)

    /** A number as a cell with the format [code] (or the built-in [id]) shows it. */
    fun format(value: Double, id: Int, code: String?, date1904: Boolean = false): String = NumberFormat.format(value, id, code, date1904).text

    /** A number without a format: an integer as one, a fraction to ten digits that matter. */
    fun general(value: Double): String = NumberFormat.general(value)
}
