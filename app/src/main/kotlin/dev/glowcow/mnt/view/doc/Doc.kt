package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.doc.Cfb.Companion.u16
import dev.glowcow.mnt.view.doc.Cfb.Companion.u32
import dev.glowcow.mnt.view.html.Paper
import java.io.IOException
import kotlin.math.abs

/**
 * A Word 97–2003 document: the text of its body from the piece table, bold, italic, underline and the rest from
 * the runs, headings and lists from the paragraphs, tables, links and the pictures that stand in the text.
 */
object Doc {
    private class Run(val from: Int, val to: Int, val props: ByteArray)

    private class Props(var bold: Boolean = false, var italic: Boolean = false, var under: Boolean = false, var strike: Boolean = false, var script: Int = 0, var picture: Int = -1)

    private class Para(var style: Int = 0, var list: Int = 0, var level: Int = 0, var table: Boolean = false, var rowEnd: Boolean = false, var outline: Int = 9, var align: Int = 0)

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
        val stis = styles(table, fc(1), lcb(1))
        val pictures = ArrayList<ByteArray>()

        val out = StringBuilder()
        val para = StringBuilder()
        var cell = StringBuilder()
        var row: MutableList<String>? = null
        val rows = ArrayList<List<String>>()
        // Fields: whether their result is being shown, and the link a HYPERLINK field makes of it.
        val fields = ArrayList<Pair<StringBuilder, Boolean>>()
        var shown = Props()
        var linked = false

        fun target() = if (row != null) cell else para
        fun close(into: StringBuilder) {
            if (shown.script == 1) into.append("</sup>")
            if (shown.script == 2) into.append("</sub>")
            if (shown.strike) into.append("</s>")
            if (shown.under) into.append("</u>")
            if (shown.italic) into.append("</i>")
            if (shown.bold) into.append("</b>")
            shown = Props()
        }
        fun open(into: StringBuilder, p: Props) {
            if (p.bold == shown.bold && p.italic == shown.italic && p.under == shown.under && p.strike == shown.strike && p.script == shown.script) return
            close(into)
            if (p.bold) into.append("<b>")
            if (p.italic) into.append("<i>")
            if (p.under) into.append("<u>")
            if (p.strike) into.append("<s>")
            if (p.script == 1) into.append("<sup>")
            if (p.script == 2) into.append("<sub>")
            shown = Props(p.bold, p.italic, p.under, p.strike, p.script)
        }
        fun endTable() {
            if (rows.isEmpty()) return
            out.append("<div class=\"wide\"><table>")
            rows.forEach { r -> out.append("<tr>").append(r.joinToString("") { "<td>$it</td>" }).append("</tr>") }
            out.append("</table></div>")
            rows.clear()
        }
        fun endParagraph(p: Para) {
            close(target())
            if (linked) {
                target().append("</a>")
                linked = false
            }
            if (p.table) {
                cell.append("<br>")
                return
            }
            if (row == null) endTable()
            val inner = para.toString()
            para.setLength(0)
            val sti = stis.getOrElse(p.style) { -1 }
            // Title and Subtitle are built-in styles 62 and 74.
            val level = when {
                sti in 1..9 -> sti
                sti == 62 -> 1
                sti == 74 -> 2
                p.outline in 0..5 -> p.outline + 1
                else -> 0
            }
            val align = when (p.align) {
                1 -> " style=\"text-align:center\""
                2 -> " style=\"text-align:right\""
                3 -> " style=\"text-align:justify\""
                else -> ""
            }
            when {
                level > 0 -> out.append("<h").append(level.coerceAtMost(6)).append(align).append('>').append(inner).append("</h").append(level.coerceAtMost(6)).append('>')
                p.list > 0 -> out.append("<p style=\"margin:.2em 0 .2em ").append(1.4 * p.level + 0.4).append("em\">• ").append(inner).append("</p>")
                inner.isBlank() -> out.append("<p>&nbsp;</p>")
                else -> out.append("<p").append(align).append('>').append(inner).append("</p>")
            }
        }

        var cp = 0
        for ((cpStart, cpEnd, fcStart, compressed) in pieces) {
            if (cpStart >= ccpText) break
            for (k in cpStart until minOf(cpEnd, ccpText)) {
                val fcAt = fcStart + (k - cpStart) * if (compressed) 1 else 2
                val ch = if (compressed) cp1252(word.getOrElse(fcAt) { 0 }.toInt() and 0xFF) else u16(word, fcAt).toChar()
                cp = k
                val props = decode(find(chars, fcAt)?.props, data)
                val inField = fields.lastOrNull()
                when (ch.code) {
                    0x13 -> fields += StringBuilder() to false
                    0x14 -> if (inField != null) {
                        fields[fields.lastIndex] = inField.first to true
                        val href = Regex("HYPERLINK\\s+(?:\\\\l\\s+)?\"([^\"]+)\"").find(inField.first)?.groupValues?.get(1)
                        if (href != null && fields.size == 1) {
                            close(target())
                            target().append("<a href=\"").append(esc(href)).append("\">")
                            linked = true
                        }
                    }
                    0x15 -> if (inField != null) {
                        fields.removeAt(fields.lastIndex)
                        if (linked && fields.isEmpty()) {
                            close(target())
                            target().append("</a>")
                            linked = false
                        }
                    }
                    else -> when {
                        // The instruction of a field is not shown, only its result.
                        inField != null && !inField.second -> inField.first.append(ch)
                        ch == '\r' -> endParagraph(find(paras, fcAt)?.let { para(it.props) } ?: Para())
                        ch == '\u0007' -> {
                            val p = find(paras, fcAt)?.let { para(it.props) } ?: Para()
                            close(target())
                            if (p.rowEnd) {
                                row?.let { rows += it }
                                row = null
                            } else {
                                if (row == null) row = ArrayList()
                                row?.add(cell.toString().removeSuffix("<br>"))
                                cell = StringBuilder()
                            }
                        }
                        ch == '\u000B' -> target().append("<br>")
                        ch == '\u000C' -> {
                            endParagraph(Para())
                            if (row == null) out.append("<hr class=\"pb\">")
                        }
                        ch == '\u0001' -> if (props.picture >= 0 && data != null) picture(data, props.picture)?.let {
                            pictures += it
                            target().append("<img src=\"/a/pic").append(pictures.size - 1).append("\">")
                        }
                        ch == '\t' -> target().append(" ")
                        ch == '\u001E' -> target().append('-')
                        ch.code < 0x20 || ch == '\u001F' -> Unit
                        else -> {
                            if (row == null && find(paras, fcAt)?.let { para(it.props).table } == true) row = ArrayList()
                            open(target(), props)
                            target().append(esc(ch.toString()))
                        }
                    }
                }
            }
        }
        if (para.isNotEmpty()) endParagraph(Para())
        endTable()
        return Converted(listOf(Converted.Part("", out.toString(), paper(word, table, fc(6), lcb(6)))), { name -> name.removePrefix("pic").toIntOrNull()?.let { pictures.getOrNull(it) } })
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

    // The style of each istd: its sti says which built-in style it is, 1 to 9 being the headings.
    private fun styles(table: ByteArray, fc: Int, lcb: Int): List<Int> {
        if (lcb <= 0) return emptyList()
        val cbStshi = u16(table, fc)
        val count = u16(table, fc + 2)
        var at = fc + 2 + cbStshi
        return (0 until count).map {
            val cb = u16(table, at)
            val sti = if (cb > 0) u16(table, at + 2) and 0x0FFF else -1
            at += 2 + cb
            sti
        }
    }

    // The first section's sheet: its size and margins from the section's properties, in twentieths of a point.
    private fun paper(word: ByteArray, table: ByteArray, fc: Int, lcb: Int): Paper {
        // The table of sections: n + 1 character positions, then n descriptors of 12 bytes.
        val n = (lcb - 4) / 16
        if (n < 1 || fc + 4 * (n + 1) + 12 > table.size) return Paper.A4
        val sepx = u32(table, fc + 4 * (n + 1) + 2)
        if (sepx <= 0 || sepx + 2 > word.size) return Paper.A4
        val grpprl = word.copyOfRange(sepx + 2, minOf(word.size, sepx + 2 + u16(word, sepx)))
        val a = Paper.A4
        var width = a.width
        var height = a.height
        var top = a.top
        var right = a.right
        var bottom = a.bottom
        var left = a.left
        sprms(grpprl, 0) { sprm, at, _ ->
            val value = abs(u16(grpprl, at).toShort().toInt()) / 20f
            when (sprm) {
                0xB01F -> width = value
                0xB020 -> height = value
                0xB021 -> left = value
                0xB022 -> right = value
                0x9023 -> top = value
                0x9024 -> bottom = value
            }
        }
        return Paper(width, height, top, right, bottom, left)
    }

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

    private fun decode(grpprl: ByteArray?, data: ByteArray?): Props {
        val p = Props()
        if (grpprl == null) return p
        // An operand of 1 is on; 0x81 turns over what the style says, taken here as on.
        fun on(b: Int) = b == 1 || b == 0x81
        sprms(grpprl, 0) { sprm, at, _ ->
            val b = grpprl.getOrElse(at) { 0 }.toInt() and 0xFF
            when (sprm) {
                0x0835 -> p.bold = on(b)
                0x0836 -> p.italic = on(b)
                0x0837, 0x2A53 -> p.strike = on(b)
                0x2A3E -> p.under = b != 0
                0x2A48 -> p.script = b
                0x6A03 -> p.picture = u32(grpprl, at)
            }
        }
        return p
    }

    private fun para(papx: ByteArray): Para {
        val p = Para()
        if (papx.size < 2) return p
        p.style = u16(papx, 0)
        sprms(papx, 2) { sprm, at, _ ->
            val b = papx.getOrElse(at) { 0 }.toInt() and 0xFF
            when (sprm) {
                0x460B -> p.list = u16(papx, at)
                0x260A -> p.level = b
                0x2416 -> p.table = b != 0
                0x2417, 0x244C -> p.rowEnd = b != 0
                0x2640 -> p.outline = b
                0x2403, 0x2461 -> p.align = b
            }
        }
        return p
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
