package dev.glowcow.mnt.view.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import dev.glowcow.mnt.files.Paths
import java.io.File
import java.io.IOException

/**
 * Pictures the platform decodes: JPEG, PNG, WebP, GIF, HEIF, AVIF, BMP, ICO. A picture the region decoder knows is
 * read by parts, so a large one is never held whole; the rest are decoded whole, shrunk to the screen.
 */
object PlatformReader {
    /** [file] decoded about as large as [screen], the long side of the screen in pixels. Blocking. */
    fun open(file: File, screen: Int): Picture {
        if (Paths.extension(file.name) in MAY_MOVE) animated(file, screen)?.let { return it }
        val picture = regions(file, screen) ?: whole(file, screen)
        // The platform reads no gain map from a HEIF photo, an iPhone's among them.
        if (picture.gain != null || Paths.extension(file.name) !in HEIF) return picture
        val gain = HeifGain.read(file, GAIN_SIDE) ?: return picture
        return picture.withGain(gain)
    }

    // A picture that moves is played by the platform's drawable, which shrinks it on its own.
    private fun animated(file: File, screen: Int): Picture? {
        var size = 0 to 0
        val drawable = try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                if (!info.isAnimated) throw Still()
                size = info.size.width to info.size.height
                decoder.setTargetSampleSize(sampleFor(maxOf(info.size.width, info.size.height).toFloat() / screen))
            }
        } catch (_: Still) {
            return null
        }
        if (drawable !is AnimatedImageDrawable) return null
        val still = createBitmap(1, 1)
        return Picture(still, 1, size.first, size.second, animation = drawable)
    }

    private fun regions(file: File, screen: Int): Picture? {
        val decoder = try {
            BitmapRegionDecoder.newInstance(file.path)
        } catch (_: IOException) {
            return null
        }
        return regions(decoder, screen, orientation(file))
    }

    /** A picture read by parts through [decoder], shrunk to about [screen] as a whole, stood up by [orientation]. */
    fun regions(decoder: BitmapRegionDecoder, screen: Int, orientation: Orientation, raw: Boolean = false): Picture? {
        val w = decoder.width
        val h = decoder.height
        val sample = sampleFor(maxOf(w, h).toFloat() / screen)
        val bitmap = decoder.decodeRegion(Rect(0, 0, w, h), options(sample))
        if (bitmap == null) {
            decoder.recycle()
            return null
        }
        val gain = bitmap.gainmap?.let { Gain.of(it) }
        bitmap.gainmap = null
        return Picture(bitmap, sample, w, h, orientation, gain, regions = Parts(decoder), hdr = hdr(bitmap), raw = raw)
    }

    private fun whole(file: File, screen: Int): Picture {
        var size = 0 to 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            size = info.size.width to info.size.height
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleFor(maxOf(info.size.width, info.size.height).toFloat() / screen))
        }
        val gain = bitmap.gainmap?.let { Gain.of(it) }
        bitmap.gainmap = null
        // The decoder has turned it upright already.
        return Picture(bitmap, size.first / bitmap.width.coerceAtLeast(1), size.first, size.second, gain = gain, hdr = hdr(bitmap))
    }

    private class Parts(private val decoder: BitmapRegionDecoder) : Regions {
        override fun decode(rect: Rect, sample: Int): Region? {
            if (rect.isEmpty) return null
            val bitmap = synchronized(this) { if (decoder.isRecycled) null else decoder.decodeRegion(rect, options(sample)) } ?: return null
            val gain = bitmap.gainmap?.let { Gain.of(it) }
            bitmap.gainmap = null
            return Region(bitmap, Rect(rect), sample, gain)
        }

        override fun close() = synchronized(this) { decoder.recycle() }
    }

    private fun options(sample: Int) = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    private fun orientation(file: File): Orientation =
        Orientation.of(runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1))

    private fun hdr(bitmap: Bitmap): Boolean = bitmap.colorSpace.let {
        it == ColorSpace.get(ColorSpace.Named.BT2020_PQ) || it == ColorSpace.get(ColorSpace.Named.BT2020_HLG)
    }

    private class Still : RuntimeException()

    private val MAY_MOVE = setOf("gif", "webp", "png", "apng", "avif", "heic", "heif")
    private val HEIF = setOf("heic", "heif", "hif")

    // A gain map is decoded no larger than this: it is smooth, and the base carries the detail.
    private const val GAIN_SIDE = 2048
}
