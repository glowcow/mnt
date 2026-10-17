package dev.glowcow.mnt.view.doc

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.chrono.IsoChronology
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToLong

/**
 * Excel's number formats, as a cell shows a number: sections for positive, negative and zero, colours, literal text,
 * dates and times, percents, decimals, thousands and exponents. Separators and month names are the [locale]'s, as
 * Excel takes them from the system.
 */
object NumberFormat {
    /** What a cell shows, and the colour its format asks for (0xRRGGBB), if any. */
    data class Shown(val text: String, val color: Int? = null)

    fun format(value: Double, id: Int, code: String?, date1904: Boolean = false, locale: Locale = Locale.getDefault()): Shown {
        if (value.isNaN() || value.isInfinite()) return Shown(value.toString())
        val full = code?.takeIf { it.isNotBlank() } ?: builtin(id, locale)
        val sections = split(full)
        var index = 0
        var negative = value < 0
        val conditional = sections.indexOfFirst { CONDITION.containsMatchIn(it) }
        if (conditional >= 0) {
            index = sections.indexOfFirst { s -> CONDITION.find(s)?.let { holds(value, it.groupValues[1], it.groupValues[2].toDouble()) } ?: false }
            if (index < 0) index = sections.indices.firstOrNull { !CONDITION.containsMatchIn(sections[it]) } ?: 0
            // A section picked by its condition shows the number with its sign only when it has no minus of its own.
            negative = value < 0 && index == 0
        } else {
            when {
                value < 0 && sections.size > 1 && !isText(sections[1]) -> { index = 1; negative = false }
                value == 0.0 && sections.size > 2 && !isText(sections[2]) -> index = 2
            }
        }
        var section = sections.getOrElse(index) { sections[0] }
        if (isText(section)) section = sections[0]
        val color = COLOR.find(section)?.groupValues?.get(1)?.let { COLORS[it.lowercase()] }
        val shown = if (date(section)) date(value, section, date1904, locale) else number(if (negative) value else abs(value), section, locale)
        return Shown(shown, color)
    }

    /** Whether the format [code] (or the built-in format [id]) shows a date or a time. */
    fun isDate(id: Int, code: String?): Boolean {
        if (id in 14..22 || id in 45..47 || id in 27..36 || id in 50..58) return true
        return code != null && date(split(code)[0])
    }

    private val CONDITION = Regex("\\[(<=|>=|<>|<|>|=)\\s*(-?[0-9.]+)]")
    private val COLOR = Regex("\\[(black|blue|cyan|green|magenta|red|white|yellow)]", RegexOption.IGNORE_CASE)
    private val COLORS = mapOf(
        "black" to 0x000000, "blue" to 0x0000FF, "cyan" to 0x00FFFF, "green" to 0x00FF00,
        "magenta" to 0xFF00FF, "red" to 0xFF0000, "white" to 0xFFFFFF, "yellow" to 0xFFFF00,
    )

    private fun holds(v: Double, op: String, n: Double) = when (op) {
        "<" -> v < n
        "<=" -> v <= n
        ">" -> v > n
        ">=" -> v >= n
        "=" -> v == n
        else -> v != n
    }

    private fun isText(section: String) = bare(section).contains('@')

    // The section's own letters: no quoted text, escaped characters, brackets, spacing or fill.
    private fun bare(section: String) = section.replace(Regex("\"[^\"]*\"|\\\\.|_.|\\*.|\\[[^\\]]*]"), "")

    private fun date(section: String): Boolean =
        bare(section).lowercase().replace("general", "").any { it in "ymdhs" } || ELAPSED.containsMatchIn(section)

    private val ELAPSED = Regex("\\[(h+|m+|s+)]", RegexOption.IGNORE_CASE)

    // Semicolons inside quotes or brackets do not part sections.
    private fun split(code: String): List<String> {
        val out = ArrayList<String>()
        val s = StringBuilder()
        var quoted = false
        var bracket = false
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            when {
                ch == '\\' && i + 1 < code.length && !quoted -> { s.append(ch).append(code[i + 1]); i++ }
                ch == '"' -> { quoted = !quoted; s.append(ch) }
                ch == '[' && !quoted -> { bracket = true; s.append(ch) }
                ch == ']' && !quoted -> { bracket = false; s.append(ch) }
                ch == ';' && !quoted && !bracket -> { out += s.toString(); s.clear() }
                else -> s.append(ch)
            }
            i++
        }
        out += s.toString()
        return out
    }

    /** The text of a section without what is not shown: `[$₽-419]` keeps its symbol, colours and locales go. */
    private fun literalBrackets(section: String) = section.replace(Regex("\\[\\$([^\\]-]*)(-[0-9A-Fa-f]+)?]")) { Regex.escapeReplacement(quote(it.groupValues[1])) }
        .replace(Regex("\\[(?![hms]+])[^\\]]*]", RegexOption.IGNORE_CASE), "")

    private fun quote(text: String) = if (text.isEmpty()) "" else "\"" + text.replace("\"", "") + "\""

    // A piece of a section: text shown as it is, or a run of one placeholder letter.
    private class Token(val text: String, val literal: Boolean)

    private fun tokens(section: String): List<Token> {
        val s = literalBrackets(section)
        val out = ArrayList<Token>()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '"' -> {
                    val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                    out += Token(s.substring(i + 1, end), true)
                    i = end + 1
                    continue
                }
                ch == '\\' && i + 1 < s.length -> { out += Token(s[i + 1].toString(), true); i += 2; continue }
                ch == '_' && i + 1 < s.length -> { out += Token(" ", true); i += 2; continue }
                ch == '*' && i + 1 < s.length -> { i += 2; continue }
                ch == '[' -> {
                    val end = s.indexOf(']', i).let { if (it < 0) s.length else it }
                    out += Token("[" + s.substring(i + 1, end).lowercase() + "]", false)
                    i = end + 1
                    continue
                }
                s.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> { out += Token("ampm", false); i += 5; continue }
                s.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> { out += Token("ap", false); i += 3; continue }
                s.regionMatches(i, "General", 0, 7, ignoreCase = true) -> { out += Token("general", false); i += 7; continue }
                ch.lowercaseChar() in "ymdhse" || ch in "0#?.,%/@" -> {
                    var j = i + 1
                    val l = ch.lowercaseChar()
                    if (l in "ymdhs") while (j < s.length && s[j].lowercaseChar() == l) j++
                    if (l == 'e' && j < s.length && s[j] in "+-") j++
                    out += Token(s.substring(i, j).lowercase(), false)
                    i = j
                    continue
                }
                else -> out += Token(ch.toString(), true)
            }
            i++
        }
        return out
    }

    private fun date(value: Double, section: String, date1904: Boolean, locale: Locale): String {
        if (value < 0) return "#".repeat(8)
        var days = floor(value).toLong()
        var seconds = ((value - days) * 86400).roundToLong()
        if (seconds >= 86400) { days++; seconds -= 86400 }
        // Day 60 of the 1900 system is a 29 February that never was.
        val base = if (date1904) LocalDate.of(1904, 1, 1) else if (days < 61) LocalDate.of(1899, 12, 31) else LocalDate.of(1899, 12, 30)
        val at = LocalDateTime.of(base.plusDays(days), LocalTime.MIDNIGHT).plusSeconds(seconds)
        val parts = tokens(section)
        val twelve = parts.any { it.text == "ampm" || it.text == "ap" }
        val out = StringBuilder()
        for ((n, t) in parts.withIndex()) {
            if (t.literal) { out.append(t.text); continue }
            val k = t.text
            // `m` is minutes after an hour or before a second, a month anywhere else.
            fun minutes(): Boolean {
                val before = parts.subList(0, n).lastOrNull { !it.literal }?.text
                val after = parts.subList(n + 1, parts.size).firstOrNull { !it.literal }?.text
                return before?.startsWith("h") == true || before?.startsWith("[h") == true || after?.startsWith("s") == true
            }
            when {
                k.startsWith("[h") || k.startsWith("[m") || k.startsWith("[s") -> {
                    val total = (value * 86400).roundToLong()
                    out.append(
                        when (k[1]) {
                            'h' -> total / 3600
                            'm' -> total / 60
                            else -> total
                        }.toString().padStart(k.length - 2, '0'),
                    )
                }
                k.startsWith("y") -> out.append(if (k.length <= 2) "%02d".format(at.year % 100) else at.year.toString())
                k.startsWith("m") && k.length <= 2 && minutes() -> out.append(if (k.length == 2) "%02d".format(at.minute) else at.minute.toString())
                k.startsWith("m") -> out.append(
                    when (k.length) {
                        1 -> at.monthValue.toString()
                        2 -> "%02d".format(at.monthValue)
                        3 -> at.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale).trimEnd('.')
                        4 -> at.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
                        else -> at.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).take(1)
                    },
                )
                k.startsWith("d") -> out.append(
                    when (k.length) {
                        1 -> at.dayOfMonth.toString()
                        2 -> "%02d".format(at.dayOfMonth)
                        3 -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')
                        else -> at.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                    },
                )
                k.startsWith("h") -> {
                    val h = if (twelve) (at.hour + 11) % 12 + 1 else at.hour
                    out.append(if (k.length >= 2) "%02d".format(h) else h.toString())
                }
                k.startsWith("s") -> out.append(if (k.length >= 2) "%02d".format(at.second) else at.second.toString())
                k == "ampm" -> out.append(if (at.hour < 12) "AM" else "PM")
                k == "ap" -> out.append(if (at.hour < 12) "A" else "P")
                k == "." || k == "0" -> if (k == ".") out.append(DecimalFormatSymbols.getInstance(locale).decimalSeparator) else out.append('0')
                k == "general" -> out.append(general(value, locale))
                else -> out.append(k)
            }
        }
        return out.toString()
    }

    private fun number(value: Double, section: String, locale: Locale): String {
        val parts = tokens(section)
        if (parts.none { !it.literal && it.text != "@" } || parts.any { it.text == "general" }) {
            return parts.joinToString("") { if (it.literal) it.text else if (it.text == "general") general(value, locale) else "" }
        }
        // Fractions are shown as decimals: near enough for a viewer.
        if (parts.any { !it.literal && it.text == "/" }) return general(value, locale)
        val placeholders = parts.indices.filter { !parts[it].literal && parts[it].text.first() in "0#?.," || !parts[it].literal && parts[it].text.startsWith("e") }
        val first = placeholders.firstOrNull() ?: return parts.joinToString("") { it.text }
        val last = placeholders.last()
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        var percent = 0
        for ((i, t) in parts.withIndex()) {
            if (t.literal) {
                if (i < first) prefix.append(t.text) else if (i > last) suffix.append(t.text)
            } else if (t.text == "%") {
                percent++
                if (i < first) prefix.append('%') else suffix.append('%')
            }
        }
        val pattern = (first..last).filter { !parts[it].literal && parts[it].text != "%" }.joinToString("") { parts[it].text }
        val symbols = DecimalFormatSymbols.getInstance(locale)
        var v = value
        repeat(percent) { v *= 100 }
        val exponent = pattern.indexOfFirst { it == 'e' }
        val sign = if (v < 0) "-" else ""
        v = abs(v)
        val body = if (exponent >= 0) {
            val mantissa = pattern.substring(0, exponent)
            val digits = pattern.substring(exponent).count { it == '0' }.coerceAtLeast(1)
            val e = if (v == 0.0) 0 else floor(log10(v)).toInt()
            val m = v / Math.pow(10.0, e.toDouble())
            val (decMin, decMax) = decimals(mantissa)
            val mt = plain(m, mantissa.substringBefore('.'), decMin, decMax, false, symbols)
            mt + "E" + (if (e < 0) "-" else "+") + abs(e).toString().padStart(digits, '0')
        } else {
            val intPart = pattern.substringBefore('.')
            // Commas after the last digit divide by a thousand each.
            val trailing = (if (pattern.contains('.')) pattern else intPart).let { it.length - it.trimEnd(',').length }
            repeat(trailing) { v /= 1000 }
            val grouping = intPart.trimEnd(',').contains(',')
            val (decMin, decMax) = decimals(pattern)
            plain(v, intPart.trimEnd(','), decMin, decMax, grouping, symbols)
        }
        return prefix.toString() + sign + body + suffix
    }

    private fun decimals(pattern: String): Pair<Int, Int> {
        if (!pattern.contains('.')) return 0 to 0
        val d = pattern.substringAfter('.').filter { it in "0#?" }
        return d.count { it == '0' || it == '?' } to d.length
    }

    private fun plain(v: Double, intPattern: String, decMin: Int, decMax: Int, grouping: Boolean, symbols: DecimalFormatSymbols): String {
        val rounded = BigDecimal(v).setScale(decMax, RoundingMode.HALF_UP)
        val whole = rounded.toBigInteger().toString()
        var fraction = if (decMax > 0) rounded.toPlainString().substringAfter('.', "").padEnd(decMax, '0') else ""
        while (fraction.length > decMin && fraction.endsWith('0')) fraction = fraction.dropLast(1)
        val intMin = intPattern.count { it == '0' || it == '?' }
        var digits = if (whole == "0" && intMin == 0) "" else whole.padStart(intMin, '0')
        if (grouping && digits.length > 3) {
            val g = StringBuilder()
            for ((i, ch) in digits.withIndex()) {
                if (i > 0 && (digits.length - i) % 3 == 0) g.append(symbols.groupingSeparator)
                g.append(ch)
            }
            digits = g.toString()
        }
        return if (fraction.isEmpty()) digits else digits + symbols.decimalSeparator + fraction
    }

    /** A number without a format: an integer as one, a fraction to ten digits that matter. */
    fun general(value: Double, locale: Locale = Locale.getDefault()): String {
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        val text = "%.10g".format(Locale.US, value)
        val trimmed = if (text.contains('e')) text else text.trimEnd('0').trimEnd('.')
        return trimmed.replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    }

    // Built-in formats are not written into a file; some follow the system's way of writing dates.
    private fun builtin(id: Int, locale: Locale) = when (id) {
        1 -> "0"
        2 -> "0.00"
        3 -> "#,##0"
        4 -> "#,##0.00"
        5, 37 -> "#,##0 ;(#,##0)"
        6, 38 -> "#,##0 ;[Red](#,##0)"
        7, 39 -> "#,##0.00;(#,##0.00)"
        8, 40 -> "#,##0.00;[Red](#,##0.00)"
        9 -> "0%"
        10 -> "0.00%"
        11 -> "0.00E+00"
        12 -> "# ?/?"
        13 -> "# ??/??"
        14 -> shortDate(locale)
        15 -> "d-mmm-yy"
        16 -> "d-mmm"
        17 -> "mmm-yy"
        18 -> "h:mm AM/PM"
        19 -> "h:mm:ss AM/PM"
        20 -> "h:mm"
        21 -> "h:mm:ss"
        22 -> shortDate(locale) + " h:mm"
        45 -> "mm:ss"
        46 -> "[h]:mm:ss"
        47 -> "mm:ss.0"
        48 -> "##0.0E+0"
        49 -> "@"
        in 27..36, in 50..58 -> shortDate(locale)
        else -> "General"
    }

    // The system's short date with the year in full, as Excel shows format 14.
    private fun shortDate(locale: Locale): String {
        val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
        return pattern.replace(Regex("y+"), "yyyy").replace("M", "m").replace(Regex("'[^']*'"), "")
    }
}
