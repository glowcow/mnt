package dev.glowcow.mnt.video

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.inspector.MediaExtractorCompat

/**
 * A frame of a video, decoded by the phone, for its thumbnail: read through the player's extractors, which jump by
 * the file's index, so a file of a server is read only round the frame. The platform's own reader of MKV walks the
 * file from its start instead.
 */
@OptIn(UnstableApi::class)
object Frames {
    /**
     * The middle of a frame, [size] pixels a side, at each share of the length in turn until [dark] says one is not;
     * null when the video cannot be read or decoded.
     */
    fun square(source: DataSource.Factory, uri: Uri, size: Int, shares: List<Float>, dark: (Bitmap) -> Boolean): Bitmap? {
        val extractor = MediaExtractorCompat(DefaultExtractorsFactory(), source)
        try {
            extractor.setDataSource(uri, 0)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val length = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            // A phone's video keeps its turn apart from its frames.
            val turn = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            var frame: Bitmap? = null
            for (share in shares) {
                extractor.seekTo((length * share).toLong(), MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
                frame = decode(extractor, format, size)?.let { if (turn % 360 == 0) it else Bitmap.createBitmap(it, 0, 0, size, size, Matrix().apply { setRotate(turn.toFloat()) }, true) } ?: continue
                if (!dark(frame)) break
            }
            return frame
        } finally {
            extractor.release()
        }
    }

    private fun decode(extractor: MediaExtractorCompat, format: MediaFormat, size: Int): Bitmap? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        // A phone without Dolby Vision decodes the HEVC layer under it.
        val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull()
            ?: if (mime == MimeTypes.VIDEO_DOLBY_VISION) MediaCodec.createDecoderByType(MimeTypes.VIDEO_H265).also { format.setString(MediaFormat.KEY_MIME, MimeTypes.VIDEO_H265) } else return null
        try {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var ended = false
            val until = SystemClock.elapsedRealtime() + DECODE_MS
            while (SystemClock.elapsedRealtime() < until) {
                if (!ended) {
                    val input = codec.dequeueInputBuffer(WAIT_US)
                    if (input >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                        if (n < 0 || fed >= MAX_SAMPLES) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            ended = true
                        } else {
                            codec.queueInputBuffer(input, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                            fed++
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, WAIT_US)
                if (output < 0) continue
                val bitmap = codec.getOutputImage(output)?.use { square(it, size) }
                codec.releaseOutputBuffer(output, false)
                if (bitmap != null) return bitmap
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    // The middle square of a YUV frame, 8 or 10 bits, turned to RGB by BT.709 at [size] pixels a side.
    private fun square(image: Image, size: Int): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888 && image.format != ImageFormat.YCBCR_P010) return null
        val wide = image.format == ImageFormat.YCBCR_P010
        val crop = image.cropRect
        val side = minOf(crop.width(), crop.height())
        if (side <= 0) return null
        val left = crop.left + (crop.width() - side) / 2
        val top = crop.top + (crop.height() - side) / 2
        val (y, u, v) = image.planes
        fun sample(plane: Image.Plane, x: Int, row: Int): Int {
            // A 10-bit sample keeps its value in the high byte of two.
            val at = row * plane.rowStride + x * plane.pixelStride + if (wide) 1 else 0
            return plane.buffer.get(at).toInt() and 0xFF
        }
        val pixels = IntArray(size * size)
        for (ty in 0 until size) {
            val sy = top + ty * side / size
            for (tx in 0 until size) {
                val sx = left + tx * side / size
                val c = 1.164f * (sample(y, sx, sy) - 16)
                val d = sample(u, sx / 2, sy / 2) - 128
                val e = sample(v, sx / 2, sy / 2) - 128
                val r = (c + 1.793f * e).toInt().coerceIn(0, 255)
                val g = (c - 0.213f * d - 0.533f * e).toInt().coerceIn(0, 255)
                val b = (c + 2.112f * d).toInt().coerceIn(0, 255)
                pixels[ty * size + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private const val DECODE_MS = 8_000L
    private const val WAIT_US = 10_000L

    // A keyframe is the first sample after a seek; a few more cover a decoder that holds frames back.
    private const val MAX_SAMPLES = 90
}
