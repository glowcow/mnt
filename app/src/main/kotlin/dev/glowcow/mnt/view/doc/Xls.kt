package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.doc.Cfb.Companion.f64
import dev.glowcow.mnt.view.doc.Cfb.Companion.u16
import dev.glowcow.mnt.view.doc.Cfb.Companion.u32
import java.io.IOException

/** An Excel 97–2003 workbook (BIFF8): its visible sheets, the cells' values shown as their formats say. */
object Xls {
    private class Record(val type: Int, val data: ByteArray, val at: Int)

    fun convert(bytes: ByteArray): Converted {
        val cfb = Cfb(bytes)
        val book = cfb.stream("Workbook") ?: cfb.stream("Book") ?: throw IOException("No Workbook stream")
        val records = records(book)
        var strings: List<String> = emptyList()
        val formats = HashMap<Int, String>()
        val xfs = ArrayList<Int>()
        val fonts = ArrayList<ByteArray>()
        val xfData = ArrayList<ByteArray>()
        val palette = Colors.INDEXED.copyOf()
        var date1904 = false
        val sheets = ArrayList<Triple<String, Int, Int>>()
        var i = 0
        // The globals come first, up to their EOF.
        while (i < records.size && records[i].type != 0x000A) {
            val r = records[i]
            when (r.type) {
                0x002F -> throw IOException("The workbook is encrypted")
                0x00FC -> {
                    val parts = arrayListOf(r.data)
                    while (i + 1 < records.size && records[i + 1].type == 0x003C) parts += records[++i].data
                    strings = sst(parts)
                }
                0x041E -> formats[u16(r.data, 0)] = unicode(r.data, 2, u16(r.data, 2), 4).first
                0x00E0 -> {
                    xfs += u16(r.data, 2)
                    xfData += r.data
                }
                0x0031 -> fonts += r.data
                0x0092 -> for (c in 0 until minOf(u16(r.data, 0), 56)) {
                    val at = 2 + c * 4
                    if (at + 2 < r.data.size) palette[8 + c] = (r.data[at].toInt() and 0xFF shl 16) or (r.data[at + 1].toInt() and 0xFF shl 8) or (r.data[at + 2].toInt() and 0xFF)
                }
                0x0022 -> date1904 = u16(r.data, 0) == 1
                0x0085 -> {
                    val cch = r.data[6].toInt() and 0xFF
                    sheets += Triple(unicode(r.data, 6, cch, 7).first, u32(r.data, 0), (r.data[4].toInt() and 0x03))
                }
            }
            i++
        }
        val byOffset = records.withIndex().associate { it.value.at to it.index }
        val fontSize = fonts.firstOrNull()?.let { u16(it, 0) / 20f } ?: 10f
        val looks = xfData.map { look(it, fonts, palette, fontSize) }
        val pages = sheets.filter { it.third == 0 }.mapNotNull { (name, offset, _) ->
            val start = byOffset[offset] ?: return@mapNotNull null
            val sheet = Sheet(name)
            sheet.looks = looks
            sheet.fontSize = fontSize
            sheet.defaultHeight = 12.75f
            var k = start + 1
            var formulaCell: Pair<Int, Int>? = null
            var formulaXf = 0
            while (k < records.size && records[k].type != 0x000A) {
                val r = records[k]
                val d = r.data
                fun number(row: Int, col: Int, xf: Int, value: Double) {
                    val id = xfs.getOrElse(xf) { 0 }
                    val shown = NumberFormat.format(value, id, formats[id], date1904)
                    sheet.put(row, col, shown.text, number = true, look = xf, color = shown.color)
                }
                when (r.type) {
                    0x00FD -> strings.getOrNull(u32(d, 6))?.let { sheet.put(u16(d, 0), u16(d, 2), it, look = u16(d, 4)) }
                    0x0204, 0x00D6 -> sheet.put(u16(d, 0), u16(d, 2), unicode(d, 6, u16(d, 6), 8).first, look = u16(d, 4))
                    0x0201 -> sheet.put(u16(d, 0), u16(d, 2), "", look = u16(d, 4))
                    0x00BE -> for (c in 0 until (d.size - 6) / 2) sheet.put(u16(d, 0), u16(d, 2) + c, "", look = u16(d, 4 + c * 2))
                    0x007D -> {
                        val hidden = u16(d, 8) and 0x01 != 0
                        for (c in u16(d, 0)..minOf(u16(d, 2), Sheets.MAX_COLUMNS - 1)) {
                            sheet.widths[c] = u16(d, 4) / 256f
                            if (hidden) sheet.hiddenColumns += c
                        }
                    }
                    0x0055 -> sheet.defaultWidth = u16(d, 0) + 0.71f
                    0x0099 -> sheet.defaultWidth = u16(d, 0) / 256f
                    0x0225 -> sheet.defaultHeight = u16(d, 2) / 20f
                    0x0208 -> {
                        val flags = u16(d, 12)
                        if (flags and 0x20 != 0) sheet.hiddenRows += u16(d, 0)
                        if (flags and 0x40 != 0) sheet.heights[u16(d, 0)] = (u16(d, 6) and 0x7FFF) / 20f
                    }
                    0x023E -> if (u16(d, 0) and 0x02 == 0) sheet.grid = false
                    0x0203 -> number(u16(d, 0), u16(d, 2), u16(d, 4), f64(d, 6))
                    0x027E -> number(u16(d, 0), u16(d, 2), u16(d, 4), rk(u32(d, 6)))
                    0x00BD -> {
                        val row = u16(d, 0)
                        val first = u16(d, 2)
                        val count = (d.size - 6) / 6
                        for (c in 0 until count) number(row, first + c, u16(d, 4 + c * 6), rk(u32(d, 6 + c * 6)))
                    }
                    0x0205 -> {
                        val value = d[6].toInt() and 0xFF
                        sheet.put(u16(d, 0), u16(d, 2), if (d[7].toInt() == 0) (if (value != 0) "TRUE" else "FALSE") else error(value), look = u16(d, 4))
                    }
                    0x0006 -> {
                        val row = u16(d, 0)
                        val col = u16(d, 2)
                        val xf = u16(d, 4)
                        if (u16(d, 12) == 0xFFFF) {
                            when (d[6].toInt()) {
                                0 -> {
                                    formulaCell = row to col
                                    formulaXf = xf
                                }
                                1 -> sheet.put(row, col, if (d[8].toInt() != 0) "TRUE" else "FALSE", look = xf)
                                2 -> sheet.put(row, col, error(d[8].toInt() and 0xFF), look = xf)
                                3 -> sheet.put(row, col, "", look = xf)
                            }
                        } else {
                            number(row, col, u16(d, 4), f64(d, 6))
                        }
                    }
                    0x0207 -> formulaCell?.let { (row, col) ->
                        sheet.put(row, col, unicode(d, 0, u16(d, 0), 2).first, look = formulaXf)
                        formulaCell = null
                    }
                    0x00E5 -> for (m in 0 until u16(d, 0)) {
                        val at = 2 + m * 8
                        sheet.merge(u16(d, at), u16(d, at + 4), u16(d, at + 2), u16(d, at + 6))
                    }
                }
                k++
            }
            Converted.Part(name, Sheets.html(sheet))
        }
        return Converted(pages)
    }

    // A cell format (XF) as a look: its font (FONT), alignment, edges and fill, colours from the workbook's palette.
    private fun look(x: ByteArray, fonts: List<ByteArray>, palette: IntArray, baseSize: Float): CellLook {
        if (x.size < 20) return CellLook()
        // There is no font 4: the numbers above it are one more than their place.
        val ifnt = u16(x, 0).let { if (it >= 4) it - 1 else it }
        val font = fonts.getOrNull(ifnt)
        fun color(icv: Int): Int? = if (icv in 0..63) palette[icv] else null
        val align = x[6].toInt() and 0xFF
        val styles = u16(x, 10)
        val sides = u16(x, 12)
        val more = u32(x, 14)
        val fill = u16(x, 18)
        fun edge(style: Int, icv: Int) = Edge.of(EDGES.getOrNull(style), color(icv))
        val size = font?.let { u16(it, 0) / 20f }
        return CellLook(
            bold = font != null && u16(font, 6) >= 600,
            italic = font != null && u16(font, 2) and 0x02 != 0,
            underline = font != null && (font[10].toInt() and 0xFF) != 0,
            strike = font != null && u16(font, 2) and 0x08 != 0,
            size = size?.takeIf { it != baseSize },
            face = font?.let { Xlsx.face(unicode(it, 14, it[14].toInt() and 0xFF, 15).first) },
            color = font?.let { color(u16(it, 4)) }?.takeIf { it != 0 },
            fill = if (more ushr 26 == 0) null else color(fill and 0x7F),
            left = edge(styles and 0xF, sides and 0x7F),
            right = edge(styles shr 4 and 0xF, sides shr 7 and 0x7F),
            top = edge(styles shr 8 and 0xF, more and 0x7F),
            bottom = edge(styles shr 12 and 0xF, more shr 7 and 0x7F),
            align = when (align and 0x7) {
                1, 4 -> "left"
                2, 6, 7 -> "center"
                3 -> "right"
                5 -> "justify"
                else -> null
            },
            valign = when (align shr 4 and 0x7) {
                0 -> "top"
                1, 3, 4 -> "middle"
                else -> null
            },
            wrap = align and 0x08 != 0,
            indent = x[8].toInt() and 0x0F,
        )
    }

    private val EDGES = listOf(
        "none", "thin", "medium", "dashed", "dotted", "thick", "double", "hair",
        "mediumDashed", "dashDot", "mediumDashDot", "dashDotDot", "mediumDashDotDot", "slantDashDot",
    )

    private fun records(stream: ByteArray): List<Record> {
        val out = ArrayList<Record>()
        var at = 0
        while (at + 4 <= stream.size) {
            val type = u16(stream, at)
            val length = u16(stream, at + 2)
            val end = minOf(stream.size, at + 4 + length)
            out += Record(type, stream.copyOfRange(at + 4, end), at)
            at = end
        }
        return out
    }

    /** A string of [count] characters whose flags byte stands at [flags]; its text and where it ends. */
    private fun unicode(d: ByteArray, @Suppress("UNUSED_PARAMETER") lengthAt: Int, count: Int, flags: Int): Pair<String, Int> {
        val wide = (d.getOrElse(flags) { 0 }.toInt() and 0x01) != 0
        val from = flags + 1
        val size = if (wide) count * 2 else count
        val end = minOf(d.size, from + size)
        val text = if (wide) String(d, from, end - from, Charsets.UTF_16LE) else String(d, from, end - from, Charsets.ISO_8859_1)
        return text to end
    }

    // The shared strings, which may run on into CONTINUE records, where each part of a string has a flags byte of its own.
    private fun sst(parts: List<ByteArray>): List<String> {
        var part = 0
        var at = 8
        val count = u32(parts[0], 4)
        fun data() = parts[part]
        fun ensure() {
            while (part < parts.size && at >= data().size) {
                part++
                at = 0
            }
        }
        fun byte(): Int {
            ensure()
            if (part >= parts.size) return 0
            return data()[at++].toInt() and 0xFF
        }
        fun skip(n: Int) {
            var left = n
            while (left > 0 && part < parts.size) {
                ensure()
                if (part >= parts.size) break
                val step = minOf(left, data().size - at)
                at += step
                left -= step
            }
        }
        val out = ArrayList<String>(minOf(count, 1_000_000).coerceAtLeast(0))
        repeat(count.coerceIn(0, 1_000_000)) {
            ensure()
            if (part >= parts.size) return out
            val cch = byte() or (byte() shl 8)
            val flags = byte()
            var wide = flags and 0x01 != 0
            val runs = if (flags and 0x08 != 0) byte() or (byte() shl 8) else 0
            val ext = if (flags and 0x04 != 0) byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24) else 0
            val text = StringBuilder(cch)
            while (text.length < cch && part < parts.size) {
                if (at >= data().size) {
                    // A string cut by a CONTINUE goes on with a flags byte that says its width there.
                    part++
                    at = 0
                    if (part >= parts.size) break
                    wide = (byte() and 0x01) != 0
                    continue
                }
                if (wide) {
                    if (at + 1 >= data().size) {
                        at = data().size
                        continue
                    }
                    text.append(((data()[at].toInt() and 0xFF) or ((data()[at + 1].toInt() and 0xFF) shl 8)).toChar())
                    at += 2
                } else {
                    text.append((data()[at].toInt() and 0xFF).toChar())
                    at++
                }
            }
            skip(runs * 4 + ext)
            out += text.toString()
        }
        return out
    }

    private fun rk(rk: Int): Double {
        val value = if (rk and 0x02 != 0) (rk shr 2).toDouble() else java.lang.Double.longBitsToDouble((rk and -4).toLong() shl 32)
        return if (rk and 0x01 != 0) value / 100 else value
    }

    private fun error(code: Int) = when (code) {
        0x00 -> "#NULL!"
        0x07 -> "#DIV/0!"
        0x0F -> "#VALUE!"
        0x17 -> "#REF!"
        0x1D -> "#NAME?"
        0x24 -> "#NUM!"
        0x2A -> "#N/A"
        else -> "#ERR"
    }
}
