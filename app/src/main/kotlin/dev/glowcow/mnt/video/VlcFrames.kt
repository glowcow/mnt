package dev.glowcow.mnt.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/**
 * A frame of a video for its thumbnail, decoded and drawn by VLC as the player would, whatever the format: VLC draws
 * it with OpenGL into an image reader of the picture's shape. One video at a time.
 */
object VlcFrames {
    private var vlc: LibVLC? = null

    /**
     * The middle of a frame, [size] pixels a side, at each share of the length in turn until [dark] says one is not;
     * null when VLC cannot open or draw it. [open] makes a new media of the video each time.
     */
    @Synchronized
    fun square(context: Context, open: (LibVLC) -> Media?, size: Int, shares: List<Float>, dark: (Bitmap) -> Boolean): Bitmap? {
        // A frame slow to decode on the processor is still drawn, not dropped as late.
        val libVlc = vlc ?: LibVLC(context.applicationContext, arrayListOf("--no-audio", "--no-spu", "--no-osd", "--vout=gles2", "--no-drop-late-frames", "--no-skip-frames")).also { vlc = it }
        val player = MediaPlayer(libVlc)
        var reader: ImageReader? = null
        try {
            val (length, picture) = parsed(libVlc, open) ?: probed(player, libVlc, open) ?: return null
            val window = windowFor(picture, size)
            val drawn = readerOf(window.first, window.second).also { reader = it }
            attach(player, drawn, window)
            var frame: Bitmap? = null
            for (share in shares) {
                val media = open(libVlc) ?: return frame
                media.addOption(":start-time=${(length * share).toLong().coerceIn(0, FRAME_AT_MOST_MS) / 1000.0}")
                media.addOption(":start-paused")
                player.media = media
                media.release()
                player.play()
                frame = await(player, drawn)?.let { square(it, size) }
                player.stop()
                if (frame != null && !dark(frame)) break
            }
            return frame
        } finally {
            player.stop()
            if (player.vlcVout.areViewsAttached()) player.vlcVout.detachViews()
            player.release()
            reader?.close()
        }
    }

    // The length and the picture of a file of the phone, as VLC reads its head.
    private fun parsed(libVlc: LibVLC, open: (LibVLC) -> Media?): Pair<Long, IMedia.VideoTrack>? {
        val media = open(libVlc) ?: return null
        try {
            if (!media.parse()) return null
            return pictureOf(media)?.let { media.duration to it }
        } finally {
            media.release()
        }
    }

    // A server's file comes as a descriptor, which VLC's reader of heads passes over: it is opened paused instead,
    // into a reader of no size to speak of, until the player tells its length and picture.
    private fun probed(player: MediaPlayer, libVlc: LibVLC, open: (LibVLC) -> Media?): Pair<Long, IMedia.VideoTrack>? {
        val media = open(libVlc) ?: return null
        media.addOption(":start-paused")
        readerOf(PROBE, PROBE).use { reader ->
            attach(player, reader, PROBE to PROBE)
            player.media = media
            player.play()
            try {
                val until = SystemClock.elapsedRealtime() + WAIT_MS
                while (SystemClock.elapsedRealtime() < until) {
                    reader.acquireLatestImage()?.close()
                    val picture = pictureOf(media)
                    if (picture != null && player.length > 0) return player.length to picture
                    if (player.playerState == IMedia.State.Error || player.playerState == IMedia.State.Ended) return null
                    Thread.sleep(POLL_MS)
                }
                return null
            } finally {
                player.stop()
                player.vlcVout.detachViews()
                media.release()
            }
        }
    }

    private fun pictureOf(media: Media): IMedia.VideoTrack? =
        (0 until media.trackCount).firstNotNullOfOrNull { media.getTrack(it) as? IMedia.VideoTrack }?.takeIf { it.width > 0 && it.height > 0 }

    // VLC's OpenGL draws with no alpha.
    private fun readerOf(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBX_8888, 2, HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)

    private fun attach(player: MediaPlayer, reader: ImageReader, window: Pair<Int, Int>) {
        player.vlcVout.setVideoSurface(reader.surface, null)
        player.vlcVout.setWindowSize(window.first, window.second)
        player.vlcVout.attachViews()
    }

    // The window the picture fills, its short side twice [size], as the picture stands once turned upright.
    private fun windowFor(v: IMedia.VideoTrack, size: Int): Pair<Int, Int> {
        val ratio = if (v.sarNum > 0 && v.sarDen > 0) v.sarNum.toFloat() / v.sarDen else 1f
        val wide = v.width * ratio
        val (w, h) = if (v.orientation in TURNED) v.height.toFloat() to wide else wide to v.height.toFloat()
        val short = size * OVERSAMPLE
        return if (w >= h) (short * w / h).toInt() to short else short to (short * h / w).toInt()
    }

    // The last frame VLC draws before it pauses on the place asked for.
    private fun await(player: MediaPlayer, reader: ImageReader): Bitmap? {
        var frame: Bitmap? = null
        val until = SystemClock.elapsedRealtime() + WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            reader.acquireLatestImage()?.use { frame = bitmapOf(it) }
            when (player.playerState) {
                IMedia.State.Paused -> if (frame != null) return frame
                IMedia.State.Error, IMedia.State.Ended -> return frame
            }
            Thread.sleep(POLL_MS)
        }
        return frame
    }

    private fun bitmapOf(image: Image): Bitmap {
        val plane = image.planes[0]
        val wide = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        wide.copyPixelsFromBuffer(plane.buffer)
        wide.setHasAlpha(false)
        return if (wide.width == image.width) wide else Bitmap.createBitmap(wide, 0, 0, image.width, image.height)
    }

    // The middle of the frame, [size] a side.
    private fun square(frame: Bitmap, size: Int): Bitmap {
        val side = minOf(frame.width, frame.height)
        val scale = size.toFloat() / side
        return Bitmap.createBitmap(frame, (frame.width - side) / 2, (frame.height - side) / 2, side, side, Matrix().apply { setScale(scale, scale) }, true)
    }

    // VLC's orientations that lie on their side: left or right on top.
    private val TURNED = 4..7
    private const val OVERSAMPLE = 2
    private const val PROBE = 16
    private const val WAIT_MS = 8_000L
    private const val POLL_MS = 20L
    private const val FRAME_AT_MOST_MS = 5L * 60 * 1000
}
