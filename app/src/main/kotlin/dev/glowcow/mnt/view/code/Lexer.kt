package dev.glowcow.mnt.view.code

/** What a stretch of code is, and so the colour it takes. */
enum class Tone(val css: String) {
    KEYWORD("k"), TYPE("t"), STRING("s"), NUMBER("n"), COMMENT("c"), LITERAL("l"), META("m"), ATTR("a"),
    TAG("g"), VARIABLE("v"), FUNC("f"), SECTION("h"), ADDED("i"), REMOVED("d"), ERROR("e"), WARNING("w"), INFO("o"),
}

/** [start] inclusive, [end] exclusive, in characters of the text. */
data class Span(val start: Int, val end: Int, val tone: Tone)

/** Finds the stretches of a text worth a colour, in order and apart from each other. */
fun interface Lexer {
    fun lex(text: String): List<Span>
}

/** Plain text: nothing is coloured. */
val Plain = Lexer { emptyList() }

/** Walks a text by hand: what the lexers here have in common. */
internal class Cursor(val text: String) {
    val out = ArrayList<Span>()
    var i = 0
    val n = text.length

    fun at(offset: Int = 0): Char = if (i + offset in 0 until n) text[i + offset] else '\u0000'

    fun starts(s: String, from: Int = i): Boolean = text.startsWith(s, from)

    fun lineEnd(from: Int = i): Int = text.indexOf('\n', from).let { if (it < 0) n else it }

    fun lineStart(from: Int = i): Int = text.lastIndexOf('\n', from - 1) + 1

    fun add(start: Int, end: Int, tone: Tone?) {
        if (tone == null || end <= start) return
        // Two touching stretches of one tone read as one.
        val last = out.lastOrNull()
        if (last != null && last.end == start && last.tone == tone) out[out.lastIndex] = Span(last.start, end, tone) else out += Span(start, end, tone)
    }

    /** Takes a stretch lexed on its own, from [from] in this text. */
    fun embed(from: Int, spans: List<Span>) = spans.forEach { add(from + it.start, from + it.end, it.tone) }

    /** The end of a number that starts here: hex, binary, octal, decimal with a fraction and an exponent, and a suffix. */
    fun number(from: Int = i): Int {
        var j = from
        if (text.startsWith("0x", j, true) || text.startsWith("0b", j, true) || text.startsWith("0o", j, true)) {
            j += 2
            while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
            return j
        }
        while (j < n && (text[j].isDigit() || text[j] == '_')) j++
        // A fraction, or more of them: a version or an address, `1.2.3`, `10.1.1.9`.
        while (j < n - 1 && text[j] == '.' && text[j + 1].isDigit()) {
            j++
            while (j < n && (text[j].isDigit() || text[j] == '_')) j++
        }
        if (j < n && (text[j] == 'e' || text[j] == 'E') && j + 1 < n && (text[j + 1].isDigit() || (text[j + 1] in "+-" && j + 2 < n && text[j + 2].isDigit()))) {
            j += 2
            while (j < n && text[j].isDigit()) j++
        }
        while (j < n && text[j].isLetter()) j++
        return j
    }

    /** The end of a quoted stretch opened at [from] by [open]; [escape] skips the next character; a line break ends it unless [multiline]. */
    fun quoted(from: Int, open: String, close: String, escape: Char?, multiline: Boolean): Int {
        var j = from + open.length
        while (j < n) {
            val c = text[j]
            if (escape != null && c == escape && j + 1 < n) {
                j += 2
                continue
            }
            if (text.startsWith(close, j)) return j + close.length
            if (c == '\n' && !multiline) return j
            j++
        }
        return n
    }
}

internal fun Char.isWordStart() = isLetter() || this == '_'

/** Every match in [text], walked with one matcher: Android copies the whole text into each new one, and `findAll` makes one a match. */
internal inline fun Regex.each(text: CharSequence, action: (java.util.regex.Matcher) -> Unit) {
    val m = toPattern().matcher(text)
    while (m.find()) action(m)
}
