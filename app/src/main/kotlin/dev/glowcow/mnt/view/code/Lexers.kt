package dev.glowcow.mnt.view.code

/** XML and HTML: tags, their attributes, comments; in HTML the scripts and styles inside are lexed as what they are. */
class Markup(private val html: Boolean) : Lexer {
    override fun lex(text: String): List<Span> = with(Cursor(text)) {
        while (i < n) {
            when {
                starts("<!--") -> {
                    val end = text.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                    add(i, end, Tone.COMMENT)
                    i = end
                }
                starts("<![CDATA[") -> {
                    val end = text.indexOf("]]>", i).let { if (it < 0) n else it + 3 }
                    add(i, end, Tone.STRING)
                    i = end
                }
                starts("<?") || starts("<!") -> {
                    val end = text.indexOf('>', i).let { if (it < 0) n else it + 1 }
                    add(i, end, Tone.META)
                    i = end
                }
                at() == '<' && (at(1).isLetter() || at(1) == '/' && at(2).isLetter()) -> tag(this)
                at() == '&' -> {
                    val end = text.indexOf(';', i).takeIf { it in i + 2..i + 10 && text.substring(i + 1, it).all { c -> c.isLetterOrDigit() || c == '#' } }
                    if (end != null) {
                        add(i, end + 1, Tone.LITERAL)
                        i = end + 1
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
        out
    }

    private fun tag(cursor: Cursor) = with(cursor) {
        val closing = at(1) == '/'
        var j = i + if (closing) 2 else 1
        val nameStart = j
        while (j < n && (text[j].isLetterOrDigit() || text[j] in "-_:.")) j++
        val name = text.substring(nameStart, j).lowercase()
        add(nameStart, j, Tone.TAG)
        // Attributes up to the end of the tag.
        while (j < n && text[j] != '>') {
            val c = text[j]
            when {
                c == '"' || c == '\'' -> {
                    val end = quoted(j, c.toString(), c.toString(), null, true)
                    add(j, end, Tone.STRING)
                    j = end
                }
                c.isLetter() || c == '_' || c == ':' || c == '@' -> {
                    val start = j
                    while (j < n && (text[j].isLetterOrDigit() || text[j] in "-_:.@")) j++
                    add(start, j, Tone.ATTR)
                }
                else -> j++
            }
        }
        i = minOf(n, j + 1)
        if (!html || closing || text[i - 1] != '>' || text.getOrNull(i - 2) == '/') return
        // What a script or a style holds is lexed as code, up to its closing tag.
        val inner = when (name) {
            "script" -> Languages.JAVASCRIPT
            "style" -> Languages.CSS
            else -> return
        }
        val end = text.indexOf("</$name", i, ignoreCase = true).let { if (it < 0) n else it }
        embed(i, inner.lex(text.substring(i, end)))
        i = end
    }
}

/** CSS, SCSS and Less: selectors, properties, values, at-rules. */
class Css(private val lineComments: Boolean) : Lexer {
    override fun lex(text: String): List<Span> = with(Cursor(text)) {
        var depth = 0
        while (i < n) {
            val c = at()
            when {
                starts("/*") -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    add(i, end, Tone.COMMENT)
                    i = end
                }
                lineComments && starts("//") -> {
                    val end = lineEnd()
                    add(i, end, Tone.COMMENT)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val end = quoted(i, c.toString(), c.toString(), '\\', false)
                    add(i, end, Tone.STRING)
                    i = end
                }
                c == '{' -> {
                    depth++
                    i++
                }
                c == '}' -> {
                    depth = maxOf(0, depth - 1)
                    i++
                }
                c == '@' || c == '$' -> {
                    var end = i + 1
                    while (end < n && (text[end].isLetterOrDigit() || text[end] in "-_")) end++
                    add(i, end, if (c == '@' && depth == 0) Tone.META else Tone.VARIABLE)
                    i = end
                }
                c == '!' && text.startsWith("!important", i) -> {
                    add(i, i + 10, Tone.KEYWORD)
                    i += 10
                }
                c == '#' && depth > 0 -> {
                    var end = i + 1
                    while (end < n && text[end].isLetterOrDigit()) end++
                    add(i, end, Tone.NUMBER)
                    i = end
                }
                (c.isDigit() || c == '.' && at(1).isDigit()) && (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] != '-') -> {
                    var end = number()
                    if (end < n && text[end] == '%') end++
                    add(i, end, Tone.NUMBER)
                    i = end
                }
                c.isLetter() || c == '-' || c == '_' || (c == '.' || c == '#' || c == ':') && depth == 0 -> {
                    val start = i
                    var end = i + 1
                    while (end < n && (text[end].isLetterOrDigit() || text[end] in "-_")) end++
                    val tone = when {
                        depth == 0 && c == '.' || depth == 0 && c == '#' -> Tone.TYPE
                        depth == 0 && c == ':' -> Tone.KEYWORD
                        depth == 0 -> Tone.TAG
                        end < n && text[end] == '(' -> Tone.FUNC
                        // A property is a name followed by a colon; a value is a name that is not.
                        nextNonSpace(text, end) == ':' && !inValue(text, start) -> Tone.ATTR
                        else -> Tone.LITERAL
                    }
                    add(start, end, tone)
                    i = end
                }
                else -> i++
            }
        }
        out
    }

    // True when [at] stands after a colon of a declaration that has not ended.
    private fun inValue(text: String, at: Int): Boolean {
        var j = at - 1
        while (j >= 0) {
            when (text[j]) {
                ':' -> return true
                ';', '{', '}' -> return false
            }
            j--
        }
        return false
    }
}

/** YAML: keys, values, comments, anchors, tags and blocks of text. */
object Yaml : Lexer {
    private val literals = setOf("true", "false", "yes", "no", "on", "off", "null", "~", "True", "False", "TRUE", "FALSE", "Yes", "No", "Null", "NULL")

    override fun lex(text: String): List<Span> = with(Cursor(text)) {
        var block = -1
        while (i < n) {
            val end = lineEnd()
            val line = text.substring(i, end)
            val indent = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
            // A block of text (`key: |`) goes on while its lines stand deeper than its key.
            if (block >= 0) {
                if (indent > block || line.isBlank()) {
                    add(i, end, Tone.STRING)
                    i = end + 1
                    continue
                }
                block = -1
            }
            line(this, i, end)
            val trimmed = line.trimEnd()
            if (trimmed.endsWith("|") || trimmed.endsWith(">") || trimmed.endsWith("|-") || trimmed.endsWith(">-") || trimmed.endsWith("|+") || trimmed.endsWith(">+")) {
                if (trimmed.contains(':') || trimmed.trimStart().startsWith("- ")) block = indent
            }
            i = end + 1
        }
        out
    }

    private fun line(c: Cursor, from: Int, end: Int) = with(c) {
        var j = from
        while (j < end && text[j] == ' ') j++
        if (text.startsWith("---", j) || text.startsWith("...", j) && j == from) {
            add(j, j + 3, Tone.META)
            j += 3
        }
        if (j < end && text[j] == '#') return add(j, end, Tone.COMMENT)
        while (j < end - 1 && text[j] == '-' && text[j + 1] == ' ') {
            add(j, j + 1, Tone.META)
            j += 2
            while (j < end && text[j] == ' ') j++
        }
        // A key is what comes before the first `: ` outside quotes.
        val colon = keyEnd(text, j, end)
        if (colon > j) {
            add(j, colon, Tone.ATTR)
            j = colon + 1
        }
        value(this, j, end)
    }

    private fun keyEnd(text: String, from: Int, end: Int): Int {
        if (from < end && text[from] in "\"'") {
            val close = text.indexOf(text[from], from + 1)
            return if (close in from + 1 until end && close + 1 < end && text[close + 1] == ':' && (close + 2 == end || text[close + 2] == ' ')) close + 1 else -1
        }
        var j = from
        while (j < end) {
            when (text[j]) {
                '#' -> if (j > from && text[j - 1] == ' ') return -1
                '{', '[', '"', '\'' -> if (j == from) return -1
                ':' -> if (j + 1 == end || text[j + 1] == ' ' || text[j + 1] == '\r') return j
            }
            j++
        }
        return -1
    }

    private fun value(c: Cursor, from: Int, end: Int) = with(c) {
        var j = from
        while (j < end) {
            val ch = text[j]
            when {
                ch == '#' && (j == from || text[j - 1] == ' ') -> {
                    add(j, end, Tone.COMMENT)
                    return
                }
                ch == '"' || ch == '\'' -> {
                    val close = quoted(j, ch.toString(), ch.toString(), if (ch == '"') '\\' else null, false)
                    add(j, minOf(close, end), Tone.STRING)
                    j = close
                }
                (ch == '&' || ch == '*') && j + 1 < end && text[j + 1].isLetterOrDigit() -> {
                    var e = j + 1
                    while (e < end && !text[e].isWhitespace()) e++
                    add(j, e, Tone.VARIABLE)
                    j = e
                }
                ch == '!' -> {
                    var e = j + 1
                    while (e < end && !text[e].isWhitespace()) e++
                    add(j, e, Tone.META)
                    j = e
                }
                ch == ' ' || ch in "{}[],:" -> j++
                else -> {
                    // A plain value: a word up to a comma, a bracket or a comment, told apart by what it is.
                    var e = j
                    while (e < end && text[e] !in ",[]{}" && !(text[e] == '#' && text[e - 1] == ' ')) e++
                    val word = text.substring(j, e).trimEnd()
                    val tone = when {
                        word in literals -> Tone.LITERAL
                        word.toDoubleOrNull() != null || word.startsWith("0x") -> Tone.NUMBER
                        else -> null
                    }
                    add(j, j + word.length, tone)
                    j = maxOf(e, j + 1)
                }
            }
        }
    }
}

/** Lines of `key = value` with sections: INI, TOML, properties, `.env`, systemd units, and configs of that kind. */
class Pairs(private val comments: String = "#;", private val toml: Boolean = false) : Lexer {
    private val literals = setOf("true", "false", "yes", "no", "on", "off", "null", "none", "True", "False", "TRUE", "FALSE", "Yes", "No")

    override fun lex(text: String): List<Span> = with(Cursor(text)) {
        while (i < n) {
            val end = lineEnd()
            var j = i
            while (j < end && (text[j] == ' ' || text[j] == '\t')) j++
            when {
                j >= end -> Unit
                text[j] in comments -> add(j, end, Tone.COMMENT)
                text[j] == '[' -> {
                    val close = text.lastIndexOf(']', end - 1).let { if (it < j) end else it + 1 }
                    add(j, close, Tone.SECTION)
                    tail(this, close, end)
                }
                else -> {
                    val sep = (j until end).firstOrNull { text[it] == '=' || !toml && text[it] == ':' && it + 1 < end && text[it + 1] == ' ' } ?: -1
                    if (sep > j) {
                        var keyEnd = sep
                        while (keyEnd > j && text[keyEnd - 1] == ' ') keyEnd--
                        // `export NAME=value` in a .env file.
                        val key = text.substring(j, keyEnd)
                        if (key.startsWith("export ")) {
                            add(j, j + 6, Tone.KEYWORD)
                            add(j + 7, keyEnd, Tone.ATTR)
                        } else {
                            add(j, keyEnd, Tone.ATTR)
                        }
                        i = value(this, sep + 1, end)
                        continue
                    }
                }
            }
            i = end + 1
        }
        out
    }

    private fun tail(c: Cursor, from: Int, end: Int) = with(c) {
        val hash = (from until end).firstOrNull { text[it] in comments }
        if (hash != null) add(hash, end, Tone.COMMENT)
    }

    /** Lexes a value from [from]; returns where the next line starts, past a string that runs over several. */
    private fun value(c: Cursor, from: Int, end: Int): Int = with(c) {
        var j = from
        while (j < end && text[j] == ' ') j++
        if (toml && (text.startsWith("\"\"\"", j) || text.startsWith("'''", j))) {
            val q = text.substring(j, j + 3)
            val close = text.indexOf(q, j + 3).let { if (it < 0) n else it + 3 }
            add(j, close, Tone.STRING)
            return lineEnd(close) + 1
        }
        while (j < end) {
            val ch = text[j]
            when {
                ch == '"' || ch == '\'' || ch == '`' -> {
                    val close = quoted(j, ch.toString(), ch.toString(), if (ch == '"') '\\' else null, false)
                    add(j, close, Tone.STRING)
                    j = close
                }
                ch == '$' && j + 1 < end && (text[j + 1] == '{' || text[j + 1].isLetter()) -> {
                    var e = j + 1
                    if (text[e] == '{') e = text.indexOf('}', e).let { if (it < 0 || it > end) end else it + 1 } else while (e < end && (text[e].isLetterOrDigit() || text[e] == '_')) e++
                    add(j, e, Tone.VARIABLE)
                    j = e
                }
                ch in comments && (j == from || text[j - 1] == ' ') && (toml || ch == '#' && text[j - 1] == ' ') -> {
                    add(j, end, Tone.COMMENT)
                    j = end
                }
                ch.isLetterOrDigit() || ch == '-' || ch == '.' -> {
                    var e = j
                    while (e < end && (text[e].isLetterOrDigit() || text[e] in "-._:+")) e++
                    val word = text.substring(j, e)
                    val tone = when {
                        word in literals -> Tone.LITERAL
                        word.first().isDigit() || word.first() == '-' && word.length > 1 && word[1].isDigit() -> if (word.all { it.isDigit() || it in "-._:+eExXabcdefABCDEFTZ" }) Tone.NUMBER else null
                        else -> null
                    }
                    add(j, e, tone)
                    j = e
                }
                else -> j++
            }
        }
        end + 1
    }
}

/** A configuration of directives, one to a line: the first word of each is the directive; nginx, Caddy, sshd and the like. */
val Conf: Lexer = Grammar(
    literals = "on off yes no true false none any all",
    lineComments = listOf("#"),
    blockComments = emptyList(),
    variables = "$",
    wordComments = true,
    nameChars = "-.",
    calls = false,
    firstWord = Tone.KEYWORD,
    declares = "",
    defines = "",
)

/** A unified diff: added and removed lines, the headers of files and of hunks. */
object Diff : Lexer {
    override fun lex(text: String): List<Span> = with(Cursor(text)) {
        while (i < n) {
            val end = lineEnd()
            val tone = when {
                starts("+++") || starts("---") || starts("diff ") || starts("index ") || starts("new file") || starts("deleted file") -> Tone.META
                starts("@@") -> Tone.SECTION
                starts("+") || starts(">") -> Tone.ADDED
                starts("-") || starts("<") -> Tone.REMOVED
                else -> null
            }
            add(i, end, tone)
            i = end + 1
        }
        out
    }
}

/** A log: times, levels, strings; subtitles take the same, for their times. */
object Log : Lexer {
    private val time = Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}([.,]\d+)?)?(Z|[+-]\d{2}:?\d{2})?|\b\d{1,2}:\d{2}:\d{2}([.,]\d+)?\b|\b[A-Z][a-z]{2} [ \d]\d \d{2}:\d{2}:\d{2}\b""")
    private val level = Regex("""\b(FATAL|CRITICAL|CRIT|PANIC|EMERG|ALERT|ERROR|ERR|SEVERE|FAIL(ED)?|WARNING|WARN|NOTICE|INFO|DEBUG|TRACE|VERBOSE)\b|-->|\bWEBVTT\b""", RegexOption.IGNORE_CASE)
    private val string = Regex(""""[^"\n]*"""")

    override fun lex(text: String): List<Span> {
        val spans = ArrayList<Span>()
        time.each(text) { spans += Span(it.start(), it.end(), Tone.NUMBER) }
        level.each(text) {
            val tone = when (it.group().uppercase()) {
                "FATAL", "CRITICAL", "CRIT", "PANIC", "EMERG", "ALERT", "ERROR", "ERR", "SEVERE", "FAIL", "FAILED" -> Tone.ERROR
                "WARNING", "WARN" -> Tone.WARNING
                "NOTICE", "INFO" -> Tone.INFO
                "-->", "WEBVTT" -> Tone.KEYWORD
                else -> Tone.COMMENT
            }
            spans += Span(it.start(), it.end(), tone)
        }
        string.each(text) { spans += Span(it.start(), it.end(), Tone.STRING) }
        return apart(spans)
    }
}

/** Markdown as written: headings, emphasis, code, links, lists and quotes; fenced code in its own language. */
object MarkdownSource : Lexer {
    override fun lex(text: String): List<Span> = with(Cursor(text)) {
        while (i < n) {
            val end = lineEnd()
            val line = text.substring(i, end)
            val trimmed = line.trimStart()
            val lead = i + line.length - trimmed.length
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    val fence = trimmed.takeWhile { it == trimmed[0] }
                    add(lead, end, Tone.META)
                    val body = end + 1
                    var close = body
                    while (close < n && !text.substring(close, lineEnd(close)).trimStart().startsWith(fence)) close = lineEnd(close) + 1
                    val language = Languages.byName(trimmed.drop(fence.length).trim().substringBefore(' '))
                    if (body < close && close <= n) embed(body, (language ?: Plain).lex(text.substring(body, minOf(close, n))))
                    if (close < n) add(close, lineEnd(close), Tone.META)
                    i = lineEnd(close) + 1
                    continue
                }
                trimmed.startsWith("#") -> add(lead, end, Tone.SECTION)
                trimmed.startsWith(">") -> add(lead, end, Tone.COMMENT)
                trimmed.matches(RULE) -> add(lead, end, Tone.META)
                else -> {
                    val marker = MARKER.find(trimmed)
                    if (marker != null) add(lead, lead + marker.value.length - 1, Tone.META)
                    inline(this, i, end)
                }
            }
            i = end + 1
        }
        out
    }

    private val RULE = Regex("""(\*\s*){3,}|(-\s*){3,}|(_\s*){3,}""")
    private val MARKER = Regex("""^([-*+]|\d+[.)])\s""")
    private val inline = Regex("""`[^`\n]+`|\*\*[^*\n]+\*\*|__[^_\n]+__|\[[^\]\n]*]\([^)\n]*\)|<https?://[^>\s]+>""")

    private fun inline(c: Cursor, from: Int, end: Int) = with(c) {
        inline.each(text.substring(from, end)) {
            val value = it.group()
            val start = from + it.start()
            val stop = from + it.end()
            when (value[0]) {
                '`' -> add(start, stop, Tone.STRING)
                '*', '_' -> add(start, stop, Tone.KEYWORD)
                '[' -> {
                    val split = start + value.indexOf("](") + 1
                    add(start, split, Tone.TAG)
                    add(split, stop, Tone.STRING)
                }
                else -> add(start, stop, Tone.STRING)
            }
        }
    }
}

/** A makefile: targets, variables, assignments, directives and the shell in its recipes. */
object Makefile : Lexer {
    private val ASSIGN = Regex("""^\s*([A-Za-z0-9_.-]+)\s*(\?=|:=|::=|\+=|!=|=)""")
    private val TARGET = Regex("""^([^:#=\s][^:#=]*?):(?!=)""")

    private val directives = setOf("ifeq", "ifneq", "ifdef", "ifndef", "else", "endif", "include", "-include", "sinclude", "define", "endef", "export", "unexport", "override", "private", "vpath")

    override fun lex(text: String): List<Span> = with(Cursor(text)) {
        while (i < n) {
            val end = lineEnd()
            val line = text.substring(i, end)
            when {
                line.startsWith("\t") -> embed(i, Languages.SHELL.lex(line))
                line.trimStart().startsWith("#") -> add(i, end, Tone.COMMENT)
                else -> {
                    val word = line.trimStart().takeWhile { !it.isWhitespace() }
                    val lead = i + line.length - line.trimStart().length
                    val assign = ASSIGN.find(line)
                    val target = TARGET.find(line)
                    when {
                        word in directives -> add(lead, lead + word.length, Tone.KEYWORD)
                        assign != null -> add(i + assign.groups[1]!!.range.first, i + assign.groups[1]!!.range.last + 1, Tone.ATTR)
                        target != null -> add(i, i + target.groups[1]!!.range.last + 1, Tone.FUNC)
                    }
                    variables(this, i, end)
                    val hash = line.indexOf(" #")
                    if (hash >= 0) add(i + hash + 1, end, Tone.COMMENT)
                }
            }
            i = end + 1
        }
        apart(out).also { out.clear() }
    }

    private fun variables(c: Cursor, from: Int, end: Int) = with(c) {
        VARIABLES.each(text.substring(from, end)) { add(from + it.start(), from + it.end(), Tone.VARIABLE) }
    }
}

private val VARIABLES = Regex("""\$[({][^)}\n]*[)}]|\$[@<^?*%]""")

/** A MikroTik RouterOS script or export: menus, commands, `name=value`, variables and the scripting words. */
object RouterOs : Lexer {
    private val commands = setOf(
        "add", "set", "remove", "print", "export", "import", "find", "get", "edit", "enable", "disable", "comment", "move", "reset", "monitor", "unset",
        "run", "ping", "reboot", "shutdown", "upgrade", "update", "check-for-updates", "install", "download", "flush", "make-static", "release", "renew",
    )
    private val literals = setOf("yes", "no", "true", "false", "nil", "auto", "none", "disabled", "enabled")

    override fun lex(text: String): List<Span> = with(Cursor(text)) {
        var menu = false
        while (i < n) {
            val c = at()
            when {
                c == '\n' -> {
                    menu = false
                    i++
                }
                c == '#' && (i == 0 || text[i - 1] == '\n' || text[i - 1] == ' ' || text[i - 1] == '\t') -> {
                    val end = lineEnd()
                    add(i, end, Tone.COMMENT)
                    i = end
                }
                c == '"' -> {
                    val end = quoted(i, "\"", "\"", '\\', true)
                    add(i, end, Tone.STRING)
                    i = end
                }
                c == '$' -> {
                    var end = i + 1
                    while (end < n && (text[end].isLetterOrDigit() || text[end] in "_-")) end++
                    add(i, end, Tone.VARIABLE)
                    i = end
                }
                c == ':' && at(1).isLetter() -> {
                    var end = i + 1
                    while (end < n && (text[end].isLetterOrDigit() || text[end] == '-')) end++
                    add(i, end, Tone.KEYWORD)
                    i = end
                }
                c == '/' && (i == 0 || text[i - 1] == '\n' || text[i - 1] == '[' || text[i - 1] == ' ' && menuStart(text, i)) -> {
                    // A menu: `/ip firewall filter`, its words up to the command or the first `name=value`.
                    menu = true
                    var end = i + 1
                    while (end < n && !text[end].isWhitespace() && text[end] != ']') end++
                    add(i, end, Tone.SECTION)
                    i = end
                }
                c.isLetterOrDigit() || c == '-' || c == '.' -> {
                    var end = i
                    while (end < n && (text[end].isLetterOrDigit() || text[end] in "-_./:")) end++
                    // A colon that closes an address or a time is part of it; one before `=` is not there.
                    val word = text.substring(i, end)
                    val tone = when {
                        end < n && text[end] == '=' -> {
                            menu = false
                            Tone.ATTR
                        }
                        word in commands -> {
                            menu = false
                            Tone.FUNC
                        }
                        word in literals -> Tone.LITERAL
                        word[0].isDigit() && word.all { it.isDigit() || it in "./:-" || it in 'a'..'f' || it in 'A'..'F' || it in "smhdw" } -> Tone.NUMBER
                        menu && i > 0 && text[i - 1] == ' ' -> Tone.SECTION
                        else -> null
                    }
                    add(i, end, tone)
                    i = end
                }
                else -> i++
            }
        }
        out
    }

    private fun menuStart(text: String, at: Int): Boolean = text.lastIndexOf('\n', at).let { start -> text.substring(start + 1, at).isBlank() }
}

/** Sorts spans that may overlap and keeps, of two that do, the one that started first. */
internal fun apart(spans: List<Span>): List<Span> {
    val sorted = spans.sortedWith(compareBy({ it.start }, { -it.end }))
    val out = ArrayList<Span>(sorted.size)
    var end = 0
    for (s in sorted) {
        if (s.start < end) continue
        out += s
        end = s.end
    }
    return out
}

internal fun nextNonSpace(text: String, from: Int): Char {
    var j = from
    while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
    return if (j < text.length) text[j] else '\u0000'
}
