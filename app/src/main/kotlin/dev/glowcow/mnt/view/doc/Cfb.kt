package dev.glowcow.mnt.view.doc

import java.io.IOException

/** A compound file of Office 97–2003 (OLE2): a little file system of streams, found here by name. */
class Cfb(private val data: ByteArray) {
    private val sector: Int
    private val miniSector: Int
    private val cutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private val miniStream: ByteArray

    private class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        if (data.size < 512 || u32(data, 0) != 0xE011CFD0.toInt() || u32(data, 4) != 0xE11AB1A1.toInt()) throw IOException("Not a compound file")
        sector = 1 shl u16(data, 0x1E)
        miniSector = 1 shl u16(data, 0x20)
        cutoff = u32(data, 0x38)
        // The sectors that hold the allocation table: 109 named in the header, the rest in a chain of their own.
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) u32(data, 0x4C + i * 4).takeIf { it >= 0 }?.let { fatSectors += it }
        var difat = u32(data, 0x44)
        var guard = 0
        while (difat >= 0 && guard++ < 10_000) {
            val at = offset(difat)
            if (at + sector > data.size) break
            for (i in 0 until sector / 4 - 1) u32(data, at + i * 4).takeIf { it >= 0 }?.let { fatSectors += it }
            difat = u32(data, at + sector - 4)
        }
        fat = IntArray(fatSectors.size * sector / 4)
        fatSectors.forEachIndexed { k, s ->
            val at = offset(s)
            for (i in 0 until sector / 4) if (at + i * 4 + 4 <= data.size) fat[k * sector / 4 + i] = u32(data, at + i * 4)
        }
        val dir = chain(u32(data, 0x30))
        entries = (0 until dir.size / 128).map { i ->
            val at = i * 128
            val length = u16(dir, at + 64).coerceIn(0, 64)
            val name = String(dir, at, maxOf(0, length - 2), Charsets.UTF_16LE)
            Entry(name, dir[at + 66].toInt(), u32(dir, at + 116), u32(dir, at + 120).toLong() and 0xFFFFFFFFL)
        }
        val miniFatBytes = chain(u32(data, 0x3C))
        miniFat = IntArray(miniFatBytes.size / 4) { u32(miniFatBytes, it * 4) }
        val root = entries.firstOrNull { it.type == 5 }
        miniStream = if (root != null) chain(root.start).let { it.copyOf(minOf(it.size.toLong(), root.size).toInt()) } else ByteArray(0)
    }

    private fun offset(s: Int) = (s + 1) * sector

    // The bytes of a chain of sectors from [start], each in its turn.
    private fun chain(start: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < fat.size && guard++ < 1_000_000) {
            val at = offset(s)
            if (at >= data.size) break
            out.write(data, at, minOf(sector, data.size - at))
            s = fat[s]
        }
        return out.toByteArray()
    }

    private fun miniChain(start: Int, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < miniFat.size + 1 && out.size() < size && guard++ < 1_000_000) {
            val at = s * miniSector
            if (at >= miniStream.size) break
            out.write(miniStream, at, minOf(miniSector, miniStream.size - at))
            s = if (s < miniFat.size) miniFat[s] else -2
        }
        return out.toByteArray()
    }

    fun has(name: String): Boolean = entries.any { it.type == 2 && it.name.equals(name, true) }

    /** The stream called [name], whole; null when there is none. */
    fun stream(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.type == 2 && it.name.equals(name, true) } ?: return null
        val size = entry.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = if (entry.size < cutoff) miniChain(entry.start, size) else chain(entry.start)
        return if (bytes.size > size) bytes.copyOf(size) else bytes
    }

    companion object {
        fun u16(b: ByteArray, at: Int): Int = if (at + 2 > b.size || at < 0) 0 else (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun s16(b: ByteArray, at: Int): Int = u16(b, at).toShort().toInt()

        fun u32(b: ByteArray, at: Int): Int =
            if (at + 4 > b.size || at < 0) 0 else (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

        fun f64(b: ByteArray, at: Int): Double = java.lang.Double.longBitsToDouble((u32(b, at).toLong() and 0xFFFFFFFFL) or (u32(b, at + 4).toLong() shl 32))
    }
}
