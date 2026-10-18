package dev.glowcow.mnt.view.image

import android.graphics.Bitmap
import java.io.File

/** Opens a picture with the reader its kind needs. */
object Readers {
    /** Whether a file called [name] is a picture the app shows. */
    fun shows(name: String): Boolean = Pictures.isPicture(name)

    /**
     * [file] made ready for a screen whose long side is [screen] pixels; a RAW photo [develop]ed from its data, with
     * at most [memoryMb] for it, instead of the camera's preview. Blocking.
     */
    fun open(file: File, screen: Int, develop: Boolean = false, memoryMb: Int = DEVELOP_MB): Picture = when (Pictures.of(file.name)) {
        Reader.RAW -> RawReader.open(file, screen, develop, memoryMb)
        Reader.JXL -> JxlReader.open(file, sharp(screen))
        Reader.TIFF -> TiffReader.open(file, sharp(screen))
        Reader.SVG -> SvgReader.open(file, screen)
        Reader.PSD -> PsdReader.open(file, sharp(screen))
        else -> PlatformReader.open(file, screen)
    }

    // A picture decoded whole is decoded twice the screen, for growing it, and never larger than this.
    private fun sharp(screen: Int): Int = minOf(MAX_WHOLE, screen * 2)

    private const val MAX_WHOLE = 4096
    private const val DEVELOP_MB = 1024

    /** A small upright picture of [file], at least [size] pixels on its short side when it is that large. Blocking. */
    fun thumbnail(file: File, size: Int): Bitmap? = open(file, size * 2).use { picture ->
        if (picture.animation != null) return null
        val turn = picture.orientation
        if (turn == Orientation.NORMAL) picture.bitmap
        else Bitmap.createBitmap(picture.bitmap, 0, 0, picture.bitmap.width, picture.bitmap.height, turn.matrix(picture.bitmap.width.toFloat(), picture.bitmap.height.toFloat()), true)
    }
}
