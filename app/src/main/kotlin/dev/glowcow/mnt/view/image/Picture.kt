package dev.glowcow.mnt.view.image

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * A picture made ready to be drawn: [bitmap] holds the stored pixels at one [sample]th of their [width]×[height],
 * which [orientation] turns upright. [gain] lifts it to HDR; [animation] plays in its place when it moves.
 */
class Picture(
    val bitmap: Bitmap,
    val sample: Int,
    val width: Int,
    val height: Int,
    val orientation: Orientation = Orientation.NORMAL,
    val gain: Gain? = null,
    val animation: Drawable? = null,
    /** Decodes a part of the picture finer than [bitmap]; null when the reader cannot. */
    val regions: Regions? = null,
    /** The pixels are HDR themselves, PQ or HLG: the window has to be HDR to show them. */
    val hdr: Boolean = false,
    /** A RAW photo: shown as the camera's own preview, or [developed] from its data. */
    val raw: Boolean = false,
    val developed: Boolean = false,
) : Closeable {
    /** The size it stands at, upright. */
    val uprightWidth: Int get() = if (orientation.swaps) height else width
    val uprightHeight: Int get() = if (orientation.swaps) width else height

    val alpha: Boolean get() = bitmap.hasAlpha()

    override fun close() {
        regions?.close()
    }
}

/** What lifts an SDR picture to HDR, as Ultra HDR and ISO 21496-1 store it: per colour, how much a pixel may be brightened. */
class Gain(
    /** The gain of each channel, red, green and blue, as alpha; one for all three when the map is grey. */
    val channels: List<Bitmap>,
    val ratioMin: FloatArray,
    val ratioMax: FloatArray,
    val gamma: FloatArray,
    val epsilonSdr: FloatArray,
    val epsilonHdr: FloatArray,
    /** At this much headroom of the screen the gain starts to show, and at [fullHdr] it shows whole. */
    val minHdr: Float,
    val fullHdr: Float,
) {
    companion object {
        /** The platform's gain map as the app's; null when it lifts an HDR base down, which the app does not draw. */
        fun of(map: Gainmap): Gain? {
            if (map.gainmapDirection != Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR) return null
            val contents = map.gainmapContents
            val channels = if (contents.config == Bitmap.Config.ALPHA_8) listOf(contents) else split(contents)
            return Gain(channels, map.ratioMin, map.ratioMax, map.gamma, map.epsilonSdr, map.epsilonHdr, map.minDisplayRatioForHdrTransition, map.displayRatioForFullHdr)
        }

        // A map in colour is three maps of one channel each: alpha is the one thing a shader samples untouched by colour management.
        private fun split(contents: Bitmap): List<Bitmap> {
            val source = if (contents.config == Bitmap.Config.ARGB_8888) contents else contents.copy(Bitmap.Config.ARGB_8888, false)
            val w = source.width
            val h = source.height
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)
            return (0..2).map { channel ->
                val shift = 16 - 8 * channel
                val bytes = ByteArray(w * h) { (pixels[it] shr shift).toByte() }
                createBitmap(w, h, Bitmap.Config.ALPHA_8).apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
            }
        }
    }
}

/** A part of a picture decoded finer than the whole: [bitmap] covers [rect] of the stored pixels at one [sample]th. */
class Region(val bitmap: Bitmap, val rect: Rect, val sample: Int, val gain: Gain?)

/** Decodes parts of one picture; blocking, off the main thread. */
interface Regions : Closeable {
    /** [rect] of the stored pixels at one [sample]th; null when that cannot be done. */
    fun decode(rect: Rect, sample: Int): Region?
}

/** How the stored pixels stand up, by EXIF's numbers: 1 as they are, 6 turned a quarter clockwise, the rest as EXIF says. */
class Orientation private constructor(val exif: Int) {
    /** Turned by a quarter: the upright width is the stored height. */
    val swaps: Boolean get() = exif in 5..8

    /** Maps the stored pixels of a picture [w]×[h] onto the upright one. */
    fun matrix(w: Float, h: Float): Matrix = Matrix().apply {
        val v = when (exif) {
            2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f)
            3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f)
            6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f)
            7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        }
        setValues(floatArrayOf(v[0], v[1], v[2], v[3], v[4], v[5], 0f, 0f, 1f))
    }

    /** The stored pixels under [upright], a part of the upright picture of a stored [w]×[h]. */
    fun stored(upright: RectF, w: Int, h: Int): Rect {
        val back = Matrix()
        matrix(w.toFloat(), h.toFloat()).invert(back)
        val r = RectF(upright)
        back.mapRect(r)
        return Rect(r.left.toInt().coerceIn(0, w), r.top.toInt().coerceIn(0, h), kotlin.math.ceil(r.right).toInt().coerceIn(0, w), kotlin.math.ceil(r.bottom).toInt().coerceIn(0, h))
    }

    companion object {
        val NORMAL = Orientation(1)

        fun of(exif: Int): Orientation = if (exif in 2..8) Orientation(exif) else NORMAL
    }
}

/** The largest power of two that [ratio] holds, at least 1: the step a decoder can shrink by. */
fun sampleFor(ratio: Float): Int {
    var s = 1
    while (s * 2 <= ratio) s *= 2
    return s
}
