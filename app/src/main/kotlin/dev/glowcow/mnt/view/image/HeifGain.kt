package dev.glowcow.mnt.view.image

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.pow

/**
 * The gain map of a HEIF photo, which the platform's decoders leave out: ISO 21496-1's tone-mapped item, as iOS 18
 * writes it, or Apple's own map of an older iPhone with its headroom in the maker note. The map, a HEVC picture of
 * its own, is decoded by the platform from a HEIF file made of it alone.
 */
internal object HeifGain {
    /** The gain map of [file], its long side at most about [maxSide], or null when it has none the app can draw. Blocking. */
    fun read(file: File, maxSide: Int): Gain? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val heif = Heif.read(raf) ?: return null
            val tmap = heif.items.values.firstOrNull { it.type == "tmap" && heif.refs("dimg", it.id).firstOrNull() == heif.primary }
            if (tmap != null) {
                val metadata = Metadata.parse(heif.data(raf, tmap)) ?: return null
                val map = heif.items[heif.refs("dimg", tmap.id).getOrNull(1)] ?: return null
                val channels = decode(heif, raf, map, maxSide, metadata.channels) ?: return null
                return metadata.gain(channels)
            }
            val apple = heif.items.values.firstOrNull { item ->
                heif.refs("auxl", item.id).firstOrNull() == heif.primary && heif.props(item).any { it.type == "auxC" && it.auxType() == APPLE_GAIN_MAP }
            } ?: return null
            val headroom = appleHeadroom(file) ?: return null
            val channels = decode(heif, raf, apple, maxSide, 1) ?: return null
            val one = FloatArray(3) { 1f }
            Gain(channels, one, FloatArray(3) { headroom }, one, FloatArray(3), FloatArray(3), minHdr = 1f, fullHdr = headroom, linear = true)
        }
    }.getOrNull()

    // The map's picture as a HEIF file of its own, its tiles too when it is a grid, decoded as [count] channels of alpha.
    private fun decode(heif: Heif, raf: RandomAccessFile, map: Heif.Item, maxSide: Int, count: Int): List<Bitmap>? {
        val tiles = if (map.type == "grid") heif.refs("dimg", map.id).mapNotNull { heif.items[it] } else emptyList()
        if ((listOf(map) + tiles).any { it.type != "hvc1" && it.type != "grid" }) return null
        // An iPhone's map is monochrome HEVC, which Android's HEVC decoders refuse.
        val config = heif.props(tiles.firstOrNull() ?: map).firstOrNull { it.type == "hvcC" } ?: return null
        if (config.bytes.size > 8 + 16 && config.bytes[8 + 16].toInt() and 3 == 0) {
            return if (count == 1) listOf(HevcLuma.decode(heif, raf, map, tiles)) else null
        }
        val bytes = MiniHeif.build(map, tiles, { heif.props(it).filter { p -> p.type in KEPT } }, { heif.data(raf, it) })
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleFor(maxOf(info.size.width, info.size.height).toFloat() / maxSide))
        }
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return (0 until count).map { channel ->
            val shift = 16 - 8 * channel
            val alpha = ByteArray(w * h) { (pixels[it] shr shift).toByte() }
            createBitmap(w, h, Bitmap.Config.ALPHA_8).apply { copyPixelsFromBuffer(ByteBuffer.wrap(alpha)) }
        }
    }

    // Apple's headroom of a photo by its maker note's tags 33 and 48, as Apple's own guide to its HDR photos gives it.
    private fun appleHeadroom(file: File): Float? {
        val note = ExifInterface(file).getAttributeBytes(ExifInterface.TAG_MAKER_NOTE) ?: return null
        if (note.size < 16 || String(note, 0, 9, Charsets.US_ASCII) != "Apple iOS") return null
        val big = note[12] == 'M'.code.toByte()
        fun int(at: Int, n: Int): Long {
            var v = 0L
            for (i in 0 until n) v = v or ((note[at + i].toLong() and 0xFF) shl (8 * if (big) n - 1 - i else i))
            return v
        }
        val values = HashMap<Int, Float>()
        val count = int(14, 2).toInt()
        for (i in 0 until count) {
            val at = 16 + i * 12
            if (at + 12 > note.size) break
            val tag = int(at, 2).toInt()
            val type = int(at + 2, 2).toInt()
            if ((tag != 33 && tag != 48) || (type != 5 && type != 10)) continue
            val offset = int(at + 8, 4).toInt()
            if (offset < 0 || offset + 8 > note.size) continue
            val n = int(offset, 4).let { if (type == 10) it.toInt().toLong() else it }
            val d = int(offset + 4, 4).let { if (type == 10) it.toInt().toLong() else it }
            if (d != 0L) values[tag] = n.toFloat() / d
        }
        val m33 = values[33] ?: return null
        val m48 = values[48] ?: 0f
        val stops = if (m33 < 1f) {
            if (m48 <= 0.01f) -20f * m48 + 1.8f else -0.101f * m48 + 1.601f
        } else {
            if (m48 <= 0.01f) -70f * m48 + 3f else -0.303f * m48 + 2.303f
        }
        return 2f.pow(max(stops, 0f)).takeIf { it > 1f }
    }

    /** ISO 21496-1's numbers, as the tone-mapped item keeps them: log2 of ratios and headrooms, per channel or for all. */
    class Metadata(
        val channels: Int,
        val baseHeadroom: Float,
        val alternateHeadroom: Float,
        val min: FloatArray,
        val max: FloatArray,
        val gamma: FloatArray,
        val baseOffset: FloatArray,
        val alternateOffset: FloatArray,
    ) {
        /** The app's gain of [maps]; null for a map that takes an HDR base down, which the app does not draw. */
        fun gain(maps: List<Bitmap>): Gain? {
            if (alternateHeadroom <= baseHeadroom) return null
            fun three(v: FloatArray, f: (Float) -> Float) = FloatArray(3) { f(v[if (channels == 3) it else 0]) }
            val exp2 = { v: Float -> 2f.pow(v) }
            return Gain(
                maps, three(min, exp2), three(max, exp2), three(gamma) { it }, three(baseOffset) { it }, three(alternateOffset) { it },
                minHdr = 2f.pow(baseHeadroom), fullHdr = 2f.pow(alternateHeadroom),
            )
        }

        companion object {
            fun parse(b: ByteArray): Metadata? {
                val r = Reader(b)
                if (r.u8() != 0 || r.u16() != 0) return null
                r.u16()
                val flags = r.u8()
                val channels = if (flags and 0x80 != 0) 3 else 1
                val common = flags and 0x08 != 0
                val den = if (common) r.u32() else 0L
                fun unsigned(): Float {
                    val n = r.u32()
                    val d = if (common) den else r.u32()
                    return if (d == 0L) 0f else (n.toDouble() / d).toFloat()
                }
                fun signed(): Float {
                    val n = r.u32().toInt()
                    val d = if (common) den else r.u32()
                    return if (d == 0L) 0f else (n.toDouble() / d).toFloat()
                }
                val base = unsigned()
                val alternate = unsigned()
                val min = FloatArray(channels)
                val max = FloatArray(channels)
                val gamma = FloatArray(channels)
                val baseOffset = FloatArray(channels)
                val alternateOffset = FloatArray(channels)
                for (c in 0 until channels) {
                    min[c] = signed()
                    max[c] = signed()
                    gamma[c] = unsigned()
                    baseOffset[c] = signed()
                    alternateOffset[c] = signed()
                }
                if (gamma.any { it <= 0f }) return null
                return Metadata(channels, base, alternate, min, max, gamma, baseOffset, alternateOffset)
            }
        }
    }

    private class Reader(private val b: ByteArray) {
        var at = 0

        fun u8(): Int = b[at++].toInt() and 0xFF

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
    }

    // The properties the map's picture needs to be decoded; its colour and turn are left out, so its values come as they are.
    private val KEPT = setOf("hvcC", "ispe", "pixi")
    private const val APPLE_GAIN_MAP = "urn:com:apple:photo:2020:aux:hdrgainmap"
}

/** The items of a HEIF file as its meta box lists them: types, references, properties and where their data lie. */
internal class Heif private constructor(private val meta: ByteArray, private val metaAt: Long) {
    class Item(val id: Int, val type: String) {
        var props: List<Int> = emptyList()
        var method = 0
        var extents: List<LongArray> = emptyList()
    }

    /** A property box, whole: [bytes] from its size on. */
    class Prop(val type: String, val bytes: ByteArray) {
        fun auxType(): String = String(bytes, 12, bytes.size - 12, Charsets.US_ASCII).substringBefore('\u0000')
    }

    val items = HashMap<Int, Item>()
    var primary = -1
        private set
    private val refs = HashMap<String, HashMap<Int, List<Int>>>()
    private val props = ArrayList<Prop>()
    private var idat: ByteArray = ByteArray(0)

    fun refs(type: String, from: Int): List<Int> = refs[type]?.get(from).orEmpty()

    fun props(item: Item): List<Prop> = item.props.mapNotNull { props.getOrNull(it - 1) }

    /** What [item] holds, its extents one after another. */
    fun data(raf: RandomAccessFile, item: Item): ByteArray {
        val total = item.extents.sumOf { it[1] }
        require(total in 1..MAX_ITEM)
        val out = ByteArray(total.toInt())
        var at = 0
        for ((offset, length) in item.extents.map { it[0] to it[1].toInt() }) {
            if (item.method == 1) System.arraycopy(idat, offset.toInt(), out, at, length)
            else {
                raf.seek(offset)
                raf.readFully(out, at, length)
            }
            at += length
        }
        return out
    }

    private fun parse() {
        for ((type, from, to) in boxes(4, meta.size)) when (type) {
            "pitm" -> primary = if (meta[from].toInt() == 0) u(from + 4, 2).toInt() else u(from + 4, 4).toInt()
            "iinf" -> {
                val v = meta[from].toInt()
                for ((t, b, _) in boxes(from + 4 + if (v == 0) 2 else 4, to)) if (t == "infe") {
                    val iv = meta[b].toInt()
                    if (iv < 2) continue
                    val idSize = if (iv == 2) 2 else 4
                    val id = u(b + 4, idSize).toInt()
                    items[id] = Item(id, String(meta, b + 4 + idSize + 2, 4, Charsets.US_ASCII))
                }
            }
            "iref" -> {
                val idSize = if (meta[from].toInt() == 0) 2 else 4
                for ((t, b, _) in boxes(from + 4, to)) {
                    val src = u(b, idSize).toInt()
                    val n = u(b + idSize, 2).toInt()
                    refs.getOrPut(t) { HashMap() }[src] = (0 until n).map { u(b + idSize + 2 + it * idSize, idSize).toInt() }
                }
            }
            "iprp" -> for ((t, b, e) in boxes(from, to)) when (t) {
                "ipco" -> for ((pt, pb, pe) in boxes(b, e)) props += Prop(pt, meta.copyOfRange(pb - 8, pe))
                "ipma" -> ipma(b)
            }
            "iloc" -> iloc(from)
            "idat" -> idat = meta.copyOfRange(from, to)
        }
    }

    private fun ipma(at: Int) {
        val v = meta[at].toInt()
        val wide = meta[at + 3].toInt() and 1 != 0
        var p = at + 4
        val n = u(p, 4).toInt()
        p += 4
        repeat(n) {
            val id = u(p, if (v < 1) 2 else 4).toInt()
            p += if (v < 1) 2 else 4
            val k = meta[p++].toInt() and 0xFF
            val list = ArrayList<Int>(k)
            repeat(k) {
                if (wide) {
                    list += u(p, 2).toInt() and 0x7FFF
                    p += 2
                } else {
                    list += meta[p++].toInt() and 0x7F
                }
            }
            items[id]?.props = list
        }
    }

    private fun iloc(at: Int) {
        val v = meta[at].toInt()
        var p = at + 4
        val offsetSize = (meta[p].toInt() shr 4) and 15
        val lengthSize = meta[p].toInt() and 15
        val baseSize = (meta[p + 1].toInt() shr 4) and 15
        val indexSize = if (v == 1 || v == 2) meta[p + 1].toInt() and 15 else 0
        p += 2
        val idSize = if (v < 2) 2 else 4
        val n = u(p, idSize).toInt()
        p += idSize
        repeat(n) {
            val id = u(p, idSize).toInt()
            p += idSize
            var method = 0
            if (v == 1 || v == 2) {
                method = u(p, 2).toInt() and 15
                p += 2
            }
            p += 2
            val base = u(p, baseSize)
            p += baseSize
            val count = u(p, 2).toInt()
            p += 2
            val extents = (0 until count).map {
                p += indexSize
                val offset = u(p, offsetSize)
                p += offsetSize
                val length = u(p, lengthSize)
                p += lengthSize
                longArrayOf(base + offset, length)
            }
            items[id]?.let {
                it.method = method
                it.extents = extents
            }
        }
    }

    private fun u(at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (meta[at + i].toLong() and 0xFF)
        return v
    }

    // The boxes between [from] and [to]: their type, where their body starts and where they end.
    private fun boxes(from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = from
        while (p + 8 <= to) {
            var size = u(p, 4)
            var body = p + 8
            if (size == 1L) {
                size = u(p + 8, 8)
                body = p + 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < 8 || p + size > to) break
            out += Triple(String(meta, p + 4, 4, Charsets.US_ASCII), body, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    companion object {
        /** The meta box of a HEIF file read and parsed; null when there is none near the start. */
        fun read(raf: RandomAccessFile): Heif? {
            var at = 0L
            val head = ByteArray(16)
            while (at + 8 <= raf.length()) {
                raf.seek(at)
                raf.readFully(head, 0, 8)
                var size = ByteBuffer.wrap(head, 0, 4).int.toLong() and 0xFFFFFFFFL
                var header = 8
                if (size == 1L) {
                    raf.readFully(head, 8, 8)
                    size = ByteBuffer.wrap(head, 8, 8).long
                    header = 16
                }
                val type = String(head, 4, 4, Charsets.US_ASCII)
                if (type == "meta") {
                    if (size - header !in 4..MAX_META) return null
                    val meta = ByteArray((size - header).toInt())
                    raf.readFully(meta)
                    return Heif(meta, at + header).also { it.parse() }
                }
                if (size < header || type == "mdat") return null
                at += size
            }
            return null
        }

        private const val MAX_META = 16L * 1024 * 1024
        private const val MAX_ITEM = 64L * 1024 * 1024
    }
}

/** A HEIF file of one picture made of items of another, their properties and data copied over. */
internal object MiniHeif {
    fun build(primary: Heif.Item, tiles: List<Heif.Item>, props: (Heif.Item) -> List<Heif.Prop>, data: (Heif.Item) -> ByteArray): ByteArray {
        val all = listOf(primary) + tiles
        val payloads = all.map(data)
        val kept = ArrayList<Heif.Prop>()
        val assoc = all.map { item -> props(item).map { p -> (kept.indexOf(p).takeIf { it >= 0 } ?: kept.size.also { kept += p }) + 1 } }
        require(kept.size < 128)
        val ftyp = box("ftyp") { write("heic".toByteArray()); writeInt(0); write("mif1heic".toByteArray()) }
        fun meta(dataAt: Long): ByteArray = full("meta", 0) {
            write(full("hdlr", 0) { writeInt(0); write("pict".toByteArray()); write(ByteArray(13)) })
            write(full("pitm", 0) { writeShort(primary.id) })
            write(full("iinf", 0) {
                writeShort(all.size)
                all.forEach { item -> write(full("infe", 2) { writeShort(item.id); writeShort(0); write(item.type.toByteArray()); writeByte(0) }) }
            })
            if (tiles.isNotEmpty()) write(full("iref", 0) {
                write(box("dimg") { writeShort(primary.id); writeShort(tiles.size); tiles.forEach { writeShort(it.id) } })
            })
            write(box("iprp") {
                write(box("ipco") { kept.forEach { write(it.bytes) } })
                write(full("ipma", 0) {
                    writeInt(all.size)
                    all.forEachIndexed { i, item ->
                        writeShort(item.id)
                        writeByte(assoc[i].size)
                        assoc[i].forEach { index -> writeByte(index or if (kept[index - 1].type == "hvcC") 0x80 else 0) }
                    }
                })
            })
            write(full("iloc", 0) {
                writeByte(0x44)
                writeByte(0)
                writeShort(all.size)
                var at = dataAt
                all.forEachIndexed { i, item ->
                    writeShort(item.id)
                    writeShort(0)
                    writeShort(1)
                    writeInt(at.toInt())
                    writeInt(payloads[i].size)
                    at += payloads[i].size
                }
            })
        }
        val metaSize = meta(0).size
        val dataAt = (ftyp.size + metaSize + 8).toLong()
        val out = ByteArrayOutputStream()
        out.write(ftyp)
        out.write(meta(dataAt))
        val total = payloads.sumOf { it.size }
        DataOutputStream(out).writeInt(total + 8)
        out.write("mdat".toByteArray())
        payloads.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
        val content = ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()
        return ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(content.size + 8)
                write(type.toByteArray())
                write(content)
            }
        }.toByteArray()
    }

    private fun full(type: String, version: Int, body: DataOutputStream.() -> Unit): ByteArray = box(type) {
        writeInt(version shl 24)
        body()
    }
}

/** The luma of a HEVC item, a grid's tiles laid out, by libde265: a gain map has nothing else. */
internal object HevcLuma {
    fun decode(heif: Heif, raf: RandomAccessFile, map: Heif.Item, tiles: List<Heif.Item>): Bitmap {
        val parts = tiles.ifEmpty { listOf(map) }
        val config = heif.props(parts[0]).first { it.type == "hvcC" }.bytes
        val size = heif.props(parts[0]).first { it.type == "ispe" }.bytes
        val tileW = ByteBuffer.wrap(size, 12, 4).int
        val tileH = ByteBuffer.wrap(size, 16, 4).int
        var columns = 1
        var outW = tileW
        var outH = tileH
        if (tiles.isNotEmpty()) {
            val grid = heif.data(raf, map)
            columns = (grid[3].toInt() and 0xFF) + 1
            val wide = grid[1].toInt() and 1 != 0
            outW = if (wide) ByteBuffer.wrap(grid, 4, 4).int else ByteBuffer.wrap(grid, 4, 2).short.toInt() and 0xFFFF
            outH = if (wide) ByteBuffer.wrap(grid, 8, 4).int else ByteBuffer.wrap(grid, 6, 2).short.toInt() and 0xFFFF
        }
        require(outW in 1..MAX_SIDE && outH in 1..MAX_SIDE && tileW in 1..MAX_SIDE && tileH in 1..MAX_SIDE)
        val lengthSize = (config[8 + 21].toInt() and 3) + 1
        val params = parameterSets(config)
        val out = ByteArray(outW * outH)
        parts.forEachIndexed { t, part ->
            val x0 = t % columns * tileW
            val y0 = t / columns * tileH
            if (x0 >= outW || y0 >= outH) return@forEachIndexed
            val luma = NativePictures.hevcLuma(params + annexB(heif.data(raf, part), lengthSize), tileW, tileH) ?: throw java.io.IOException("A tile of the gain map cannot be decoded")
            val width = minOf(tileW, outW - x0)
            for (y in 0 until minOf(tileH, outH - y0)) System.arraycopy(luma, y * tileW, out, (y0 + y) * outW + x0, width)
        }
        return createBitmap(outW, outH, Bitmap.Config.ALPHA_8).apply { copyPixelsFromBuffer(ByteBuffer.wrap(out)) }
    }

    // The VPS, SPS and PPS of a hvcC box, each after a start code.
    private fun parameterSets(box: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var p = 8 + 22
        val arrays = box[p++].toInt() and 0xFF
        repeat(arrays) {
            p++
            val n = ((box[p].toInt() and 0xFF) shl 8) or (box[p + 1].toInt() and 0xFF)
            p += 2
            repeat(n) {
                val len = ((box[p].toInt() and 0xFF) shl 8) or (box[p + 1].toInt() and 0xFF)
                p += 2
                out.write(START)
                out.write(box, p, len)
                p += len
            }
        }
        return out.toByteArray()
    }

    // NAL units each led by its length, as HEIF keeps them, led by start codes instead.
    private fun annexB(data: ByteArray, lengthSize: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        var p = 0
        while (p + lengthSize <= data.size) {
            var len = 0
            repeat(lengthSize) { len = (len shl 8) or (data[p++].toInt() and 0xFF) }
            if (len <= 0 || p + len > data.size) break
            out.write(START)
            out.write(data, p, len)
            p += len
        }
        return out.toByteArray()
    }

    private val START = byteArrayOf(0, 0, 0, 1)
    private const val MAX_SIDE = 16384
}
