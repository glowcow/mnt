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
                0x00E0 -> xfs += u16(r.data, 2)
                0x0022 -> date1904 = u16(r.data, 0) == 1
                0x0085 -> {
                    val cch = r.data[6].toInt() and 0xFF
                    sheets += Triple(unicode(r.data, 6, cch, 7).first, u32(r.data, 0), (r.data[4].toInt() and 0x03))
                }
            }
            i++
        }
        val byOffset = records.withIndex().associate { it.value.at to it.index }
        val pages = sheets.filter { it.third == 0 }.mapNotNull { (name, offset, _) ->
            val start = byOffset[offset] ?: return@mapNotNull null
            val sheet = Sheet(name)
            var k = start + 1
            var formulaCell: Pair<Int, Int>? = null
            while (k < records.size && records[k].type != 0x000A) {
                val r = records[k]
                val d = r.data
                fun number(row: Int, col: Int, xf: Int, value: Double) {
                    val id = xfs.getOrElse(xf) { 0 }
                    sheet.put(row, col, Sheets.format(value, id, formats[id], date1904), number = true)
                }
                when (r.type) {
                    0x00FD -> strings.getOrNull(u32(d, 6))?.let { sheet.put(u16(d, 0), u16(d, 2), it) }
                    0x0204, 0x00D6 -> sheet.put(u16(d, 0), u16(d, 2), unicode(d, 6, u16(d, 6), 8).first)
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
                        sheet.put(u16(d, 0), u16(d, 2), if (d[7].toInt() == 0) (if (value != 0) "TRUE" else "FALSE") else error(value))
                    }
                    0x0006 -> {
                        val row = u16(d, 0)
                        val col = u16(d, 2)
                        if (u16(d, 12) == 0xFFFF) {
                            when (d[6].toInt()) {
                                0 -> formulaCell = row to col
                                1 -> sheet.put(row, col, if (d[8].toInt() != 0) "TRUE" else "FALSE")
                                2 -> sheet.put(row, col, error(d[8].toInt() and 0xFF))
                            }
                        } else {
                            number(row, col, u16(d, 4), f64(d, 6))
                        }
                    }
                    0x0207 -> formulaCell?.let { (row, col) ->
                        sheet.put(row, col, unicode(d, 0, u16(d, 0), 2).first)
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
