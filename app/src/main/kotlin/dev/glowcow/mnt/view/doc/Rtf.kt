package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.html.Paper
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.math.abs

/** Rich Text Format: paragraphs, bold, italic, underline, tables, links and the PNG and JPEG pictures inside. */
object Rtf {
    // Groups whose text is not part of the document.
    private val skipped = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata",
        "colorschememapping", "latentstyles", "datastore", "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf",
        "pgdsctbl", "revtbl", "filetbl", "mmathPr", "fldinst", "bkmkstart", "bkmkend", "xe", "tc", "objdata", "pntext", "pntxta", "pntxtb", "listtext", "nonshppict",
    )

    private class State(var bold: Boolean = false, var italic: Boolean = false, var under: Boolean = false, var strike: Boolean = false, var skip: Boolean = false, var skipU: Int = 1) {
        fun copy() = State(bold, italic, under, strike, skip, skipU)
    }

    fun convert(bytes: ByteArray): Converted {
        val pictures = ArrayList<ByteArray>()
        val body = html(bytes, pictures)
        return Converted(listOf(Converted.Part("", body, paper(bytes))), { name -> name.removePrefix("pic").substringBefore('.').toIntOrNull()?.let { pictures.getOrNull(it) } })
    }

    // The document's sheet, from `\paperw`, `\margl` and the like before the text: twentieths of a point.
    private fun paper(bytes: ByteArray): Paper {
        val raw = String(bytes, 0, minOf(bytes.size, 512 * 1024), Charsets.ISO_8859_1)
        fun word(name: String, default: Float) = Regex("\\\\$name(-?\\d+)").find(raw)?.groupValues?.get(1)?.toFloatOrNull()?.let { abs(it) / 20f } ?: default
        val a = Paper.A4
        return Paper(word("paperw", a.width), word("paperh", a.height), word("margt", a.top), word("margr", a.right), word("margb", a.bottom), word("margl", a.left))
    }

    // The names of the paragraph styles, by number: `{\s1 … heading 1;}`.
    private fun headings(bytes: ByteArray): Map<Int, Int> {
        val raw = String(bytes, 0, minOf(bytes.size, 256 * 1024), Charsets.ISO_8859_1)
        val sheet = raw.indexOf("{\\stylesheet").takeIf { it >= 0 } ?: return emptyMap()
        return Regex("\\{\\\\s(\\d+)[^{};]*?\\s([A-Za-z][^\\\\{};]*);").findAll(raw.substring(sheet, minOf(raw.length, sheet + 64 * 1024))).mapNotNull { m ->
            val name = m.groupValues[2].trim().lowercase()
            val level = when {
                name == "title" -> 1
                name == "subtitle" -> 2
                name.startsWith("heading ") -> name.removePrefix("heading ").trim().toIntOrNull()?.coerceIn(1, 6)
                else -> null
            }
            level?.let { m.groupValues[1].toInt() to it }
        }.toMap()
    }

    fun html(bytes: ByteArray, pictures: MutableList<ByteArray>): String {
        val out = StringBuilder()
        val heading = headings(bytes)
        // What the paragraph being read is: its style, whether it stands in a table, whether it is an item of a list.
        var style = 0
        var inTable = false
        var listed = false
        val para = StringBuilder()
        var row: MutableList<String>? = null
        val rows = ArrayList<List<String>>()
        var cell = StringBuilder()
        var charset = Charset.forName("windows-1252")
        val stack = ArrayList<State>()
        var state = State()
        var shown = State()
        var link: String? = null
        // The address of a HYPERLINK field, kept for the result that follows it.
        var pendingLink: String? = null
        var linkDepth = 0
        val pending = ByteArrayOutputStream()
        var i = 0
        val n = bytes.size

        fun target() = if (inTable || row != null) cell else para
        fun flushBytes() {
            if (pending.size() == 0) return
            text(target(), String(pending.toByteArray(), charset), shown, state)
            pending.reset()
        }
        fun endTable() {
            if (rows.isEmpty()) return
            out.append("<div class=\"wide\"><table>")
            rows.forEach { r -> out.append("<tr>").append(r.joinToString("") { "<td>$it</td>" }).append("</tr>") }
            out.append("</table></div>")
            rows.clear()
        }
        fun endPara() {
            flushBytes()
            close(target(), shown)
            shown = State()
            if (inTable || row != null) {
                cell.append("<br>")
                return
            }
            endTable()
            val inner = para.toString()
            val level = heading[style]
            out.append(
                when {
                    level != null -> "<h$level>$inner</h$level>"
                    listed -> "<p style=\"margin:.2em 0 .2em .4em\">•\u2002$inner</p>"
                    inner.isBlank() -> "<p>&nbsp;</p>"
                    else -> "<p>$inner</p>"
                },
            )
            para.setLength(0)
        }

        while (i < n) {
            val c = bytes[i].toInt().toChar()
            when (c) {
                '{' -> {
                    flushBytes()
                    stack += state
                    state = state.copy()
                    i++
                    // {\*\dest …} is a group a reader that does not know it leaves out.
                    if (i + 1 < n && bytes[i] == '\\'.code.toByte() && bytes[i + 1] == '*'.code.toByte()) {
                        val word = word(bytes, i + 2)
                        if (word.first != "shppict") state.skip = true
                    }
                }
                '}' -> {
                    flushBytes()
                    close(target(), shown)
                    shown = State()
                    state = stack.removeLastOrNull() ?: State()
                    if (link != null && !state.skip && stack.size < linkDepth) {
                        target().append("</a>")
                        link = null
                    }
                    i++
                }
                '\\' -> {
                    if (i + 1 >= n) break
                    val next = bytes[i + 1].toInt().toChar()
                    when {
                        next == '\'' && i + 3 < n -> {
                            if (!state.skip) pending.write(String(bytes, i + 2, 2, Charsets.US_ASCII).toIntOrNull(16) ?: '?'.code)
                            i += 4
                        }
                        next == '\\' || next == '{' || next == '}' -> {
                            if (!state.skip) pending.write(next.code)
                            i += 2
                        }
                        next == '~' -> {
                            if (!state.skip) pending.write(0xA0)
                            i += 2
                        }
                        next == '\n' || next == '\r' -> {
                            if (!state.skip) endPara()
                            i += 2
                        }
                        next.isLetter() -> {
                            val (name, arg, end) = word(bytes, i + 1)
                            i = end
                            if (name in skipped) {
                                state.skip = true
                                continue
                            }
                            if (name == "ansicpg" && arg != null) charset = runCatching { Charset.forName("windows-$arg") }.getOrDefault(charset)
                            if (name == "pict") {
                                flushBytes()
                                val (picture, after) = picture(bytes, i)
                                i = after
                                if (picture != null && !state.skip) {
                                    pictures += picture
                                    target().append("<img src=\"/a/pic").append(pictures.size - 1).append("\">")
                                }
                                continue
                            }
                            if (name == "fldrslt" && pendingLink != null) {
                                flushBytes()
                                link = pendingLink
                                linkDepth = stack.size
                                pendingLink = null
                                target().append("<a href=\"").append(esc(link!!)).append("\">")
                                continue
                            }
                            if (state.skip) continue
                            when (name) {
                                "par", "sect" -> endPara()
                                "page" -> {
                                    endPara()
                                    if (row == null && !inTable) out.append("<hr class=\"pb\">")
                                }
                                "line" -> {
                                    flushBytes()
                                    target().append("<br>")
                                }
                                "tab" -> pending.write(0x09)
                                "intbl" -> inTable = true
                                "s" -> style = arg ?: 0
                                "ls" -> listed = true
                                "cell" -> {
                                    flushBytes()
                                    close(cell, shown)
                                    shown = State()
                                    if (row == null) row = ArrayList()
                                    row?.add(cell.toString().removeSuffix("<br>"))
                                    cell = StringBuilder()
                                }
                                "row" -> {
                                    row?.let { rows += it }
                                    row = null
                                }
                                "pard" -> {
                                    style = 0
                                    inTable = false
                                    listed = false
                                }
                                "b" -> { flushBytes(); state.bold = arg != 0 }
                                "i" -> { flushBytes(); state.italic = arg != 0 }
                                "ul" -> { flushBytes(); state.under = arg != 0 }
                                "ulnone" -> { flushBytes(); state.under = false }
                                "strike" -> { flushBytes(); state.strike = arg != 0 }
                                "plain" -> {
                                    flushBytes()
                                    state.bold = false
                                    state.italic = false
                                    state.under = false
                                    state.strike = false
                                }
                                "uc" -> state.skipU = arg ?: 1
                                "u" -> {
                                    flushBytes()
                                    val code = (arg ?: 0).let { if (it < 0) it + 65536 else it }
                                    text(target(), String(Character.toChars(code)), shown, state)
                                    // The characters that stand in for it in readers that do not know \u.
                                    var skip = state.skipU
                                    while (skip > 0 && i < n) {
                                        if (bytes[i] == '\\'.code.toByte() && i + 1 < n && bytes[i + 1] == '\''.code.toByte()) i += 4 else i++
                                        skip--
                                    }
                                }
                                "emdash" -> pendingText(target(), "—", shown, state, ::flushBytes)
                                "endash" -> pendingText(target(), "–", shown, state, ::flushBytes)
                                "bullet" -> pendingText(target(), "•", shown, state, ::flushBytes)
                                "lquote" -> pendingText(target(), "‘", shown, state, ::flushBytes)
                                "rquote" -> pendingText(target(), "’", shown, state, ::flushBytes)
                                "ldblquote" -> pendingText(target(), "“", shown, state, ::flushBytes)
                                "rdblquote" -> pendingText(target(), "”", shown, state, ::flushBytes)
                            }
                        }
                        else -> i += 2
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (!state.skip) pending.write(bytes[i].toInt() and 0xFF)
                    i++
                }
            }
            if (c == '{' && i < n) fieldInstruction(bytes, i)?.let { pendingLink = it }
        }
        flushBytes()
        if (para.isNotBlank()) endPara()
        endTable()
        return out.toString()
    }

    private fun fieldInstruction(bytes: ByteArray, at: Int): String? {
        val head = String(bytes, at, minOf(300, bytes.size - at), Charsets.ISO_8859_1)
        if (!head.startsWith("\\*\\fldinst") && !head.startsWith("\\fldinst")) return null
        return Regex("HYPERLINK\\s+\"([^\"]+)\"").find(head)?.groupValues?.get(1)
    }

    private fun pendingText(out: StringBuilder, text: String, shown: State, state: State, flush: () -> Unit) {
        flush()
        text(out, text, shown, state)
    }

    private fun text(out: StringBuilder, text: String, shown: State, state: State) {
        if (text.isEmpty()) return
        // Formatting is opened and closed as it changes, the tags nested in one order.
        if (shown.bold != state.bold || shown.italic != state.italic || shown.under != state.under || shown.strike != state.strike) {
            close(out, shown)
            if (state.bold) out.append("<b>")
            if (state.italic) out.append("<i>")
            if (state.under) out.append("<u>")
            if (state.strike) out.append("<s>")
            shown.bold = state.bold
            shown.italic = state.italic
            shown.under = state.under
            shown.strike = state.strike
        }
        out.append(esc(text).replace("\t", " "))
    }

    private fun close(out: StringBuilder, shown: State) {
        if (shown.strike) out.append("</s>")
        if (shown.under) out.append("</u>")
        if (shown.italic) out.append("</i>")
        if (shown.bold) out.append("</b>")
        shown.bold = false
        shown.italic = false
        shown.under = false
        shown.strike = false
    }

    /** A control word at [from]: its name, its number and where what follows starts (past one space that ends it). */
    private fun word(bytes: ByteArray, from: Int): Triple<String, Int?, Int> {
        var j = from
        while (j < bytes.size && bytes[j].toInt().toChar().isLetter()) j++
        val name = String(bytes, from, j - from, Charsets.US_ASCII)
        val numberStart = j
        if (j < bytes.size && bytes[j] == '-'.code.toByte()) j++
        while (j < bytes.size && bytes[j].toInt().toChar().isDigit()) j++
        val arg = if (j > numberStart) String(bytes, numberStart, j - numberStart, Charsets.US_ASCII).toIntOrNull() else null
        if (j < bytes.size && bytes[j] == ' '.code.toByte()) j++
        return Triple(name, arg, j)
    }

    /** A picture's bytes, written in hex up to the end of its group, when it is a PNG or a JPEG; and where its group ends. */
    private fun picture(bytes: ByteArray, from: Int): Pair<ByteArray?, Int> {
        var depth = 0
        var j = from
        var kind: String? = null
        val hex = ByteArrayOutputStream()
        var high = -1
        while (j < bytes.size) {
            val c = bytes[j].toInt().toChar()
            when {
                c == '{' -> {
                    depth++
                    j++
                }
                c == '}' -> {
                    if (depth == 0) break
                    depth--
                    j++
                }
                c == '\\' -> {
                    val (name, _, end) = word(bytes, j + 1)
                    if (name == "pngblip") kind = "png"
                    if (name == "jpegblip") kind = "jpg"
                    j = if (end > j + 1) end else j + 2
                }
                depth == 0 && c.digitToIntOrNull(16) != null -> {
                    val v = c.digitToInt(16)
                    if (high < 0) high = v else {
                        hex.write(high * 16 + v)
                        high = -1
                    }
                    j++
                }
                else -> j++
            }
        }
        return (if (kind != null) hex.toByteArray() else null) to j
    }
}
