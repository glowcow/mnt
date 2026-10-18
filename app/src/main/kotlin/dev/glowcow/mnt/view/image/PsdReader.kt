package dev.glowcow.mnt.view.image

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * Photoshop's PSD and PSB, by the picture they keep of all their layers together: RGB, grey, CMYK or a palette, 8 or
 * 16 bits a channel, raw or packed by runs. Read in one pass, every [step]th row and column kept.
 */
object PsdReader {
    /** What a Photoshop file holds, shrunk: [rgba] premultiplied, [width]×[height], one [step]th of [fullWidth]×[fullHeight]. */
    class Decoded(val rgba: ByteArray, val width: Int, val height: Int, val alpha: Boolean, val step: Int, val fullWidth: Int, val fullHeight: Int)

    fun open(file: File, maxSide: Int): Picture {
        val d = decode(file, maxSide)
        return Picture(bitmapOf(d.rgba, d.width, d.height, d.alpha), d.step, d.fullWidth, d.fullHeight)
    }

    fun decode(file: File, maxSide: Int): Decoded {
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            if (input.readInt() != SIGNATURE) throw IOException("Not a Photoshop file")
            val version = input.readUnsignedShort()
            val big = version == 2
            if (version != 1 && !big) throw IOException("Unknown Photoshop version $version")
            input.skipFully(6)
            val channels = input.readUnsignedShort()
            val height = input.readInt()
            val width = input.readInt()
            val depth = input.readUnsignedShort()
            val mode = input.readUnsignedShort()
            if (depth != 8 && depth != 16) throw IOException("$depth bits a channel are not read")
            val palette = ByteArray(input.readInt()).also { input.readFully(it) }
            input.skipFully(input.readInt().toLong() and 0xFFFFFFFFL)
            input.skipFully(if (big) input.readLong() else input.readInt().toLong() and 0xFFFFFFFFL)
            val compression = input.readUnsignedShort()
            if (compression > 1) throw IOException("Zipped Photoshop data is not read")

            val colours = when (mode) {
                MODE_RGB -> 3
                MODE_CMYK -> 4
                else -> 1
            }
            val alpha = mode != MODE_BITMAP && channels > colours
            val kept = minOf(channels, colours + if (alpha) 1 else 0)
            val step = stepFor(width, height, maxSide)
            val outW = max(1, width / step)
            val outH = max(1, height / step)
            val planes = Array(kept) { ByteArray(outW * outH) }
            val bytesPerSample = depth / 8
            val row = ByteArray(width * bytesPerSample)
            // With runs, every row of every channel has its length up front.
            val lengths = if (compression == 1) IntArray(channels * height) { if (big) input.readInt() else input.readUnsignedShort() } else null
            val packed = ByteArray(width * bytesPerSample * 2 + 2)
            for (c in 0 until channels) {
                for (y in 0 until height) {
                    if (lengths != null) {
                        val n = lengths[c * height + y]
                        if (n > packed.size) throw IOException("A row of the file is damaged")
                        input.readFully(packed, 0, n)
                        unpack(packed, n, row)
                    } else {
                        input.readFully(row)
                    }
                    if (c >= kept || y % step != 0 || y / step >= outH) continue
                    val plane = planes[c]
                    val at = (y / step) * outW
                    for (x in 0 until outW) plane[at + x] = row[x * step * bytesPerSample]
                }
                if (c + 1 >= kept) break
            }
            val rgba = ByteArray(outW * outH * 4)
            for (i in 0 until outW * outH) {
                var r: Int
                var g: Int
                var b: Int
                when (mode) {
                    MODE_RGB -> {
                        r = planes[0][i].u(); g = planes[1][i].u(); b = planes[2][i].u()
                    }
                    // Photoshop keeps CMYK inverted: 255 is no ink.
                    MODE_CMYK -> {
                        val k = planes[3][i].u()
                        r = planes[0][i].u() * k / 255; g = planes[1][i].u() * k / 255; b = planes[2][i].u() * k / 255
                    }
                    MODE_INDEXED -> {
                        val p = planes[0][i].u()
                        r = palette.getOrElse(p) { 0 }.u(); g = palette.getOrElse(256 + p) { 0 }.u(); b = palette.getOrElse(512 + p) { 0 }.u()
                    }
                    else -> {
                        r = planes[0][i].u(); g = r; b = r
                    }
                }
                val a = if (alpha) planes[kept - 1][i].u() else 255
                rgba[i * 4] = (r * a / 255).toByte()
                rgba[i * 4 + 1] = (g * a / 255).toByte()
                rgba[i * 4 + 2] = (b * a / 255).toByte()
                rgba[i * 4 + 3] = a.toByte()
            }
            return Decoded(rgba, outW, outH, alpha, step, width, height)
        }
    }

    // PackBits: a count below 128 copies that many and one, above it repeats the next byte 257 less that many times.
    private fun unpack(src: ByteArray, n: Int, dst: ByteArray) {
        var i = 0
        var o = 0
        while (i < n && o < dst.size) {
            val count = src[i++].toInt()
            if (count >= 0) {
                val len = minOf(count + 1, dst.size - o, n - i)
                System.arraycopy(src, i, dst, o, len)
                i += count + 1
                o += len
            } else if (count != -128) {
                val len = minOf(1 - count, dst.size - o)
                val v = src.getOrElse(i++) { 0 }
                dst.fill(v, o, o + len)
                o += len
            }
        }
    }

    private fun stepFor(w: Int, h: Int, max: Int): Int {
        var step = 1
        while (max(w, h) / step > max) step++
        return step
    }

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun Byte.u(): Int = toInt() and 0xFF

    private const val SIGNATURE = 0x38425053
    private const val MODE_BITMAP = 0
    private const val MODE_INDEXED = 2
    private const val MODE_RGB = 3
    private const val MODE_CMYK = 4
}
