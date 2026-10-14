package dev.glowcow.mnt.view.code

/** A quote that opens a string; [short] marks one that closes within a few characters or is no string at all (a Rust lifetime). */
class Quote(val open: String, val close: String = open, val escape: Char? = '\\', val multiline: Boolean = false, val short: Boolean = false)

/**
 * The words and marks of a language of the C, script or shell kind; words are given space-separated.
 * [declares] are the keywords after which a name is a function's, [defines] those after which it is a type's.
 */
class Grammar(
    keywords: String = "",
    types: String = "",
    literals: String = "",
    builtins: String = "",
    val lineComments: List<String> = listOf("//"),
    val blockComments: List<Pair<String, String>> = listOf("/*" to "*/"),
    val nested: Boolean = false,
    quotes: List<Quote> = listOf(Quote("\""), Quote("'")),
    val ignoreCase: Boolean = false,
    /** Characters that open a variable: `$name`, `${name}`, `@name`. */
    val variables: String = "",
    val variableTone: Tone = Tone.VARIABLE,
    /** `@Name` is an annotation or a decorator. */
    val annotations: Boolean = false,
    /** `#` at the start of a line opens a directive to the end of it. */
    val preprocessor: Boolean = false,
    /** A comment mark counts only at the start of a word, as `#` in a shell. */
    val wordComments: Boolean = false,
    /** Characters a name may hold besides letters, digits and `_`. */
    val nameChars: String = "",
    val capitalTypes: Boolean = false,
    val calls: Boolean = true,
    /** The word just after `(` is a function's name, as in a Lisp. */
    val callAfterParen: Boolean = false,
    /** The first word of a line takes this tone. */
    val firstWord: Tone? = null,
    /** A string followed by `:` is a key, as in JSON. */
    val keys: Boolean = false,
    /** `:name` is a symbol, as in Ruby or Clojure. */
    val symbols: Boolean = false,
    /** Letters that may stand right before a quote and belong to the string: `r"…"`, `f'…'`. */
    prefixes: String = "",
    declares: String = "fun def func function fn sub proc method macro",
    defines: String = "class struct interface enum trait object type record protocol extension impl module namespace union",
) : Lexer {
    private fun set(words: String) = words.split(' ', '\n').filter { it.isNotEmpty() }.map { if (ignoreCase) it.lowercase() else it }.toHashSet()

    private val keywords = set(keywords)
    private val types = set(types)
    private val literals = set(literals)
    private val builtins = set(builtins)
    private val declares = set(declares)
    private val defines = set(defines)
    private val prefixes = set(prefixes)
    private val quotes = quotes.sortedByDescending { it.open.length }

    private fun Char.isName() = isLetterOrDigit() || this == '_' || this in nameChars

    override fun lex(text: String): List<Span> = with(Cursor(text)) {
        var lineStart = true
        var next: Tone? = null
        var afterParen = false
        loop@ while (i < n) {
            val c = text[i]
            if (c == '\n') {
                lineStart = true
                i++
                continue
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                i++
                continue
            }
            val first = lineStart
            lineStart = false
            val paren = afterParen
            afterParen = false
            if (preprocessor && first && c == '#') {
                // A directive goes on past a line that ends in a backslash.
                var end = lineEnd()
                while (end < n && end > 0 && text[end - 1] == '\\') end = lineEnd(end + 1)
                add(i, end, Tone.META)
                i = end
                continue
            }
            for ((open, close) in blockComments) {
                if (!starts(open)) continue
                val end = if (nested) nestedEnd(text, i, open, close) else text.indexOf(close, i + open.length).let { if (it < 0) n else it + close.length }
                add(i, end, Tone.COMMENT)
                i = end
                continue@loop
            }
            for (mark in lineComments) {
                if (!text.startsWith(mark, i, ignoreCase) || (wordComments && i > 0 && !text[i - 1].isWhitespace())) continue
                val end = lineEnd()
                add(i, end, Tone.COMMENT)
                i = end
                continue@loop
            }
            if (string(this, i)) continue
            if (c.isDigit() && (i == 0 || !text[i - 1].isName())) {
                val end = number()
                add(i, end, Tone.NUMBER)
                i = end
                continue
            }
            if (c in variables && i + 1 < n) {
                val end = variable(text, i)
                if (end > i + 1) {
                    add(i, end, variableTone)
                    i = end
                    continue
                }
            }
            if (annotations && c == '@' && at(1).isWordStart()) {
                var end = i + 1
                while (end < n && (text[end].isName() || text[end] == '.')) end++
                add(i, end, Tone.META)
                i = end
                continue
            }
            if (symbols && c == ':' && at(1).isWordStart() && (i == 0 || !text[i - 1].isName() && text[i - 1] != ':')) {
                var end = i + 1
                while (end < n && text[end].isName()) end++
                add(i, end, Tone.LITERAL)
                i = end
                continue
            }
            if (c.isWordStart() || c in nameChars && c != '-') {
                var end = i + 1
                while (end < n && text[end].isName()) end++
                // A name may not end in a dash: `a-` is a name and a minus.
                while (end > i + 1 && text[end - 1] == '-') end--
                val word = text.substring(i, end)
                if (word in prefixes && end < n && quotes.any { text.startsWith(it.open, end) }) {
                    val from = i
                    if (string(this, end)) {
                        out[out.lastIndex] = Span(from, out.last().end, Tone.STRING)
                        continue
                    }
                }
                val key = if (ignoreCase) word.lowercase() else word
                val tone = when {
                    key in keywords -> Tone.KEYWORD
                    key in literals -> Tone.LITERAL
                    key in types -> Tone.TYPE
                    next != null -> next
                    first && firstWord != null -> firstWord
                    key in builtins -> Tone.FUNC
                    paren && callAfterParen -> Tone.FUNC
                    calls && nextChar(text, end) == '(' -> Tone.FUNC
                    capitalTypes && word[0].isUpperCase() -> Tone.TYPE
                    else -> null
                }
                next = when (key) {
                    in declares -> Tone.FUNC
                    in defines -> Tone.TYPE
                    else -> null
                }
                add(i, end, tone)
                i = end
                continue
            }
            if (c != '*' && c != '&') next = null
            if (c == '(') afterParen = true
            i++
        }
        out
    }

    /** Lexes a string that opens at [from], if one does; true when it did. */
    private fun string(cursor: Cursor, from: Int): Boolean = with(cursor) {
        for (q in quotes) {
            if (!starts(q.open, from)) continue
            val end = quoted(from, q.open, q.close, q.escape, q.multiline)
            if (q.short && (end - from > 6 || !text.startsWith(q.close, end - q.close.length) || end - from <= q.open.length)) return false
            if (keys && nextChar(text, end) == ':') add(from, end, Tone.ATTR) else add(from, end, Tone.STRING)
            i = end
            return true
        }
        return false
    }

    private fun variable(text: String, from: Int): Int {
        val n = text.length
        var j = from + 1
        if (j < n && text[j] == '{') return text.indexOf('}', j).let { if (it < 0 || text.indexOf('\n', j).let { nl -> nl in 0 until it }) from + 1 else it + 1 }
        if (j < n && text[j] == '(' && text[from] == '$') return from + 1
        // Special variables of a shell: $1, $?, $#, $@.
        if (j < n && text[from] == '$' && (text[j].isDigit() || text[j] in "?#@*!$-")) return j + 1
        while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
        // A batch file closes its variables: %name%.
        if (text[from] == '%' && j < n && text[j] == '%') j++
        return j
    }

    private fun nextChar(text: String, from: Int): Char {
        var j = from
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        return if (j < text.length) text[j] else '\u0000'
    }

    private fun nestedEnd(text: String, from: Int, open: String, close: String): Int {
        var depth = 0
        var j = from
        while (j < text.length) {
            when {
                text.startsWith(open, j) -> {
                    depth++
                    j += open.length
                }
                text.startsWith(close, j) -> {
                    depth--
                    j += close.length
                    if (depth == 0) return j
                }
                else -> j++
            }
        }
        return text.length
    }
}
