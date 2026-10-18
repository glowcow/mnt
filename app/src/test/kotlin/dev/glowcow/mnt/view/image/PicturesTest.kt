package dev.glowcow.mnt.view.image

import dev.glowcow.mnt.ui.photo.GainShader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PicturesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun readersByName() {
        assertEquals(Reader.PLATFORM, Pictures.of("IMG_0001.HEIC"))
        assertEquals(Reader.RAW, Pictures.of("_DSC0009.ARW"))
        assertEquals(Reader.RAW, Pictures.of("photo.cr3"))
        assertEquals(Reader.JXL, Pictures.of("a.jxl"))
        assertEquals(Reader.TIFF, Pictures.of("scan.tif"))
        assertEquals(Reader.SVG, Pictures.of("logo.svg"))
        assertEquals(Reader.PSD, Pictures.of("work.psb"))
        assertNull(Pictures.of("notes.txt"))
    }

    @Test fun samples() {
        assertEquals(1, sampleFor(0.5f))
        assertEquals(1, sampleFor(1.9f))
        assertEquals(2, sampleFor(2f))
        assertEquals(8, sampleFor(11f))
    }

    @Test fun gainWeight() {
        val gain = Gain(emptyList(), FloatArray(3) { 1f }, FloatArray(3) { 4f }, FloatArray(3) { 1f }, FloatArray(3), FloatArray(3), minHdr = 1f, fullHdr = 4f)
        assertEquals(0f, GainShader.weight(gain, 1f), 1e-6f)
        assertEquals(0.5f, GainShader.weight(gain, 2f), 1e-6f)
        assertEquals(1f, GainShader.weight(gain, 4f), 1e-6f)
        assertEquals(1f, GainShader.weight(gain, 9f), 1e-6f)
        // A map that is whole from its first step on is all or nothing.
        val sharp = Gain(emptyList(), FloatArray(3), FloatArray(3), FloatArray(3), FloatArray(3), FloatArray(3), minHdr = 2f, fullHdr = 2f)
        assertEquals(0f, GainShader.weight(sharp, 1.5f), 1e-6f)
        assertEquals(1f, GainShader.weight(sharp, 2f), 1e-6f)
    }

    @Test fun isoGainMetadata() {
        // An iPhone's tone-mapped item: one channel, the base SDR, its alternate 2^1.892 times as bright.
        val hex = "000000000040000000000000000100f2373f00800000ffd273d58000000000f2373f008000000064bffd00800000000029f140000000000029f140000000"
        val m = HeifGain.Metadata.parse(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())!!
        assertEquals(1, m.channels)
        assertEquals(0f, m.baseHeadroom, 1e-6f)
        assertEquals(1.8923f, m.alternateHeadroom, 1e-3f)
        assertEquals(-0.0014f, m.min[0], 1e-4f)
        assertEquals(0.7871f, m.gamma[0], 1e-3f)
        assertEquals(1e-5f, m.baseOffset[0], 1e-6f)
    }

    @Test fun isoGainMetadataOfAnotherVersion() {
        assertNull(HeifGain.Metadata.parse(byteArrayOf(1, 0, 0, 0, 0, 0)))
    }

    @Test fun psdRgbWithRunsAndAlpha() {
        // 4×3, red, green, blue and alpha planes; packed by runs.
        val w = 4
        val h = 3
        val planes = listOf(ByteArray(w * h) { 200.toByte() }, ByteArray(w * h) { 100 }, ByteArray(w * h) { 0 }, ByteArray(w * h) { if (it == 0) 0 else 255.toByte() })
        val d = PsdReader.decode(psd(w, h, 8, mode = 3, planes, runs = true), 100)
        assertEquals(4, d.width)
        assertEquals(3, d.height)
        assertTrue(d.alpha)
        // The first pixel lets everything through; the next one is whole.
        assertEquals(listOf(0, 0, 0, 0), pixel(d, 0))
        assertEquals(listOf(200, 100, 0, 255), pixel(d, 1))
    }

    @Test fun psdGrey16Raw() {
        // 16 bits a channel keep their high byte.
        val samples = byteArrayOf(0x12, 0x34, 0x7F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x10)
        val d = PsdReader.decode(psd(2, 2, 16, mode = 1, listOf(samples), runs = false), 100)
        assertFalse(d.alpha)
        assertEquals(listOf(0x12, 0x12, 0x12, 255), pixel(d, 0))
        assertEquals(listOf(0x7F, 0x7F, 0x7F, 255), pixel(d, 1))
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 255), pixel(d, 2))
    }

    @Test fun psdCmykIsInverted() {
        // Photoshop keeps no ink as 255: all four at 255 is paper white.
        val planes = List(4) { ByteArray(1) { 255.toByte() } }
        val d = PsdReader.decode(psd(1, 1, 8, mode = 4, planes, runs = false), 100)
        assertEquals(listOf(255, 255, 255, 255), pixel(d, 0))
    }

    @Test fun psdShrinksWhileReading() {
        val w = 300
        val h = 10
        val plane = ByteArray(w * h) { (it % w).toByte() }
        val d = PsdReader.decode(psd(w, h, 8, mode = 1, listOf(plane), runs = true), 100)
        assertEquals(3, d.step)
        assertEquals(100, d.width)
        assertEquals(3, d.height)
        assertEquals(300, d.fullWidth)
        // Every third column is kept.
        assertEquals(3, pixel(d, 1)[0])
    }

    @Test(expected = IOException::class)
    fun notAPsd() {
        PsdReader.decode(folder.newFile("x.psd").apply { writeBytes(ByteArray(64)) }, 100)
    }

    private fun pixel(d: PsdReader.Decoded, i: Int) = (0..3).map { d.rgba[i * 4 + it].toInt() and 0xFF }

    // A Photoshop file of one picture, [planes] a channel each, raw or packed as literal runs.
    private fun psd(w: Int, h: Int, depth: Int, mode: Int, planes: List<ByteArray>, runs: Boolean): File {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(0x38425053)
            writeShort(1)
            write(ByteArray(6))
            writeShort(planes.size)
            writeInt(h)
            writeInt(w)
            writeShort(depth)
            writeShort(mode)
            writeInt(0)
            writeInt(0)
            writeInt(0)
            writeShort(if (runs) 1 else 0)
            val rowBytes = w * depth / 8
            if (runs) {
                val rows = planes.flatMap { plane -> (0 until h).map { y -> plane.copyOfRange(y * rowBytes, (y + 1) * rowBytes) } }
                val packed = rows.map { row -> row.toList().chunked(128).flatMap { listOf((it.size - 1).toByte()) + it }.toByteArray() }
                packed.forEach { writeShort(it.size) }
                packed.forEach { write(it) }
            } else {
                planes.forEach { write(it) }
            }
            flush()
        }
        return folder.newFile().apply { writeBytes(bytes.toByteArray()) }
    }
}
