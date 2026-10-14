package dev.glowcow.mnt.view.doc

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** A sheet of a workbook as shown: the text of each cell by row and column from 0, and the cells merged into one. */
class Sheet(val name: String) {
    val rows = TreeMap<Int, TreeMap<Int, Cell>>()
    val merges = ArrayList<IntArray>()

    class Cell(val text: String, val number: Boolean = false)

    fun put(row: Int, column: Int, text: String, number: Boolean = false) {
        if (text.isEmpty() || row < 0 || column < 0) return
        rows.getOrPut(row) { TreeMap() }[column] = Cell(text, number)
    }

    /** Merges the cells from [r1],[c1] to [r2],[c2], all inclusive. */
    fun merge(r1: Int, c1: Int, r2: Int, c2: Int) {
        if (r2 >= r1 && c2 >= c1 && (r2 > r1 || c2 > c1)) merges += intArrayOf(r1, c1, r2, c2)
    }
}

object Sheets {
    /** At most this many cells go into a page: a phone lays out no more in good time. */
    private const val CELLS = 150_000

    /** A sheet as a table with column letters and row numbers; with [header] its first row heads the columns instead. */
    fun html(sheet: Sheet, header: Boolean = false): String {
        if (sheet.rows.isEmpty()) return "<p class=\"muted\">∅</p>"
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
    fun isDate(id: Int, code: String?): Boolean {
        if (id in 14..22 || id in 45..47 || id in 27..36 || id in 50..58) return true
        if (code == null) return false
        // Letters inside quotes, after a backslash or in brackets (colours, locales) are not parts of a date.
        val bare = code.replace(Regex("\"[^\"]*\"|\\\\.|\\[[^]]*]"), "").lowercase()
        return bare.any { it in "dmyhs" } && !bare.contains("general")
    }

    /** A number as a cell with the format [code] shows it, near enough: dates, times, percentages, decimals and thousands. */
    fun format(value: Double, id: Int, code: String?, date1904: Boolean = false): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val c = code ?: builtin(id)
        if (isDate(id, code)) return date(value, c, date1904)
        val section = c.split(';').let { if (value < 0 && it.size > 1) it[1] else it[0] }.replace(Regex("\"[^\"]*\"|\\[[^]]*]|_.|\\\\|\\*."), "")
        if (section.isBlank() || section.equals("General", true) || section == "@") return general(value)
        val percent = section.contains('%')
        val v = if (percent) value * 100 else value
        val decimals = section.substringAfter('.', "").takeWhile { it == '0' || it == '#' }.length
        val grouping = section.contains(',')
        val text = String.format(Locale.US, if (grouping) "%,.${decimals}f" else "%.${decimals}f", if (value < 0 && c.split(';').size > 1) abs(v) else v)
        return text + if (percent) "%" else ""
    }

    private fun builtin(id: Int) = when (id) {
        1 -> "0"
        2 -> "0.00"
        3 -> "#,##0"
        4 -> "#,##0.00"
        9 -> "0%"
        10 -> "0.00%"
        11 -> "0.00E+00"
        14, 15, 16, 17 -> "yyyy-mm-dd"
        18, 20 -> "h:mm"
        19, 21, 45, 46, 47 -> "h:mm:ss"
        22 -> "yyyy-mm-dd h:mm"
        37, 38 -> "#,##0"
        39, 40 -> "#,##0.00"
        else -> "General"
    }

    /** A number without a format: an integer as one, a fraction to ten digits that matter. */
    fun general(value: Double): String {
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        val text = "%.10g".format(Locale.US, value)
        return if (text.contains('e')) text else text.trimEnd('0').trimEnd('.')
    }

    private fun date(value: Double, code: String, date1904: Boolean): String {
        val days = floor(value).toLong()
        val seconds = ((value - days) * 86400).roundToLong()
        // Day 60 of the 1900 system is a 29 February that never was.
        val base = if (date1904) LocalDate.of(1904, 1, 1) else if (days < 61) LocalDate.of(1899, 12, 31) else LocalDate.of(1899, 12, 30)
        val time = LocalDateTime.of(base.plusDays(days), java.time.LocalTime.MIDNIGHT).plusSeconds(seconds)
        val lower = code.lowercase().replace(Regex("\"[^\"]*\"|\\[[^]]*]"), "")
        val hasDate = lower.contains('d') || lower.contains('y') || Regex("m{3,}").containsMatchIn(lower)
        val hasTime = lower.contains('h') || lower.contains('s')
        val datePart = "%04d-%02d-%02d".format(time.year, time.monthValue, time.dayOfMonth)
        val timePart = if (lower.contains('s')) "%02d:%02d:%02d".format(time.hour, time.minute, time.second) else "%02d:%02d".format(time.hour, time.minute)
        return when {
            hasDate && hasTime -> "$datePart $timePart"
            hasTime && !hasDate -> timePart
            else -> datePart
        }
    }
}
