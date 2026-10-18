package dev.glowcow.mnt.view.image

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.caverock.androidsvg.SVG
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** A bitmap of [rgba], premultiplied, [w]×[h], in Display P3 when [wide]. */
internal fun bitmapOf(rgba: ByteArray, w: Int, h: Int, alpha: Boolean, wide: Boolean = false): Bitmap {
    val space = ColorSpace.get(if (wide) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB)
    return createBitmap(w, h, Bitmap.Config.ARGB_8888, alpha, space).apply { copyPixelsFromBuffer(ByteBuffer.wrap(rgba)) }
}

/**
 * RAW photos by LibRaw: the camera's own preview at once, read by parts like any JPEG, or the data [develop]ed at
 * half size. A preview much smaller than the screen is developed instead.
 */
object RawReader {
    fun open(file: File, screen: Int, develop: Boolean, memoryMb: Int): Picture {
        if (!develop) preview(file, screen)?.let { return it }
        val facts = LongArray(NativePictures.FACTS)
        val rgba = NativePictures.rawDevelop(file.path, memoryMb, facts) ?: throw IOException("The RAW data cannot be developed")
        val w = facts[NativePictures.WIDTH].toInt()
        val h = facts[NativePictures.HEIGHT].toInt()
        return Picture(bitmapOf(rgba, w, h, alpha = false), 1, w, h, raw = true, developed = true)
    }

    private fun preview(file: File, screen: Int): Picture? {
        val facts = LongArray(NativePictures.FACTS)
        val data = NativePictures.rawPreview(file.path, facts) ?: return null
        val turn = Orientation.of(facts[NativePictures.ORIENTATION].toInt())
        if (facts[NativePictures.FORMAT] == 1L) {
            val w = facts[NativePictures.WIDTH].toInt()
            val h = facts[NativePictures.HEIGHT].toInt()
            return if (max(w, h) < screen / 2) null else Picture(bitmapOf(data, w, h, alpha = false), 1, w, h, turn, raw = true)
        }
        // The JPEG's own header tells its size; LibRaw does not always.
        val decoder = try {
            BitmapRegionDecoder.newInstance(data, 0, data.size)
        } catch (_: IOException) {
            return null
        }
        if (max(decoder.width, decoder.height) < screen / 2) {
            decoder.recycle()
            return null
        }
        return PlatformReader.regions(decoder, screen, turn, raw = true)
    }
}

/** JPEG XL by libjxl, shrunk while it is decoded. */
object JxlReader {
    fun open(file: File, maxSide: Int): Picture {
        if (file.length() > MAX_FILE) throw IOException("The file is too large")
        val facts = LongArray(NativePictures.FACTS)
        val rgba = NativePictures.jxl(file.readBytes(), maxSide, facts) ?: throw IOException("The JPEG XL picture cannot be read")
        return shrunk(rgba, facts)
    }

    private const val MAX_FILE = 256L * 1024 * 1024
}

/** TIFF by libtiff, its first picture, shrunk while it is read. */
object TiffReader {
    fun open(file: File, maxSide: Int): Picture {
        val facts = LongArray(NativePictures.FACTS)
        val rgba = NativePictures.tiff(file.path, maxSide, facts) ?: throw IOException("The TIFF picture cannot be read")
        return shrunk(rgba, facts)
    }
}

// What a native reader made: the shrunk pixels, the size they stand for, how they stand up.
private fun shrunk(rgba: ByteArray, facts: LongArray): Picture {
    val w = facts[NativePictures.WIDTH].toInt()
    val h = facts[NativePictures.HEIGHT].toInt()
    val bitmap = bitmapOf(rgba, w, h, facts[NativePictures.ALPHA] == 1L, facts[NativePictures.WIDE] == 1L)
    val fullW = facts[NativePictures.FULL_WIDTH].toInt()
    return Picture(bitmap, max(1, fullW / w), fullW, facts[NativePictures.FULL_HEIGHT].toInt(), Orientation.of(facts[NativePictures.ORIENTATION].toInt()))
}

/** SVG by AndroidSVG: drawn as large as the screen, and drawn again sharper for the part grown into. */
object SvgReader {
    fun open(file: File, screen: Int): Picture {
        val svg = file.inputStream().use { SVG.getFromInputStream(it) }
        val box = svg.documentViewBox
        val docW = svg.documentWidth.takeIf { it > 0 } ?: box?.width() ?: 512f
        val docH = svg.documentHeight.takeIf { it > 0 } ?: box?.height() ?: 512f
        if (box == null) svg.setDocumentViewBox(0f, 0f, docW, docH)
        // The whole is this large, so that growing it four times over a screen still draws it at its own sharpness.
        val full = FULL / max(docW, docH)
        val w = (docW * full).roundToInt().coerceAtLeast(1)
        val h = (docH * full).roundToInt().coerceAtLeast(1)
        svg.setDocumentWidth("${w}px")
        svg.setDocumentHeight("${h}px")
        val regions = Drawing(svg, w, h)
        val sample = sampleFor(max(w, h).toFloat() / screen)
        val whole = regions.decode(Rect(0, 0, w, h), sample) ?: throw IOException("The SVG picture cannot be drawn")
        return Picture(whole.bitmap, sample, w, h, regions = regions)
    }

    private class Drawing(private val svg: SVG, private val w: Int, private val h: Int) : Regions {
        override fun decode(rect: Rect, sample: Int): Region? {
            val bw = ceil(rect.width() / sample.toFloat()).toInt().coerceAtLeast(1)
            val bh = ceil(rect.height() / sample.toFloat()).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(bw, bh)
            val canvas = Canvas(bitmap)
            canvas.scale(1f / sample, 1f / sample)
            canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
            synchronized(this) { svg.renderToCanvas(canvas) }
            return Region(bitmap, Rect(rect), sample, null)
        }

        override fun close() = Unit
    }

    private const val FULL = 8192f
}
