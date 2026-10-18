package dev.glowcow.mnt.view.image

import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Paths
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** How a picture is HDR: lifted by a gain map up to [ratio] times SDR white, or HDR in its own pixels. */
sealed interface Hdr {
    data class Gained(val ratio: Float) : Hdr
    data object Pq : Hdr
    data object Hlg : Hdr
}

/** What a picture tells of itself, from its pixels and its EXIF; each null when it does not say. */
class PictureFacts(
    val node: Node,
    val width: Int,
    val height: Int,
    val format: String,
    val colour: String?,
    val hdr: Hdr?,
    /** When it was taken, by the clock of the camera. */
    val taken: LocalDateTime?,
    val camera: String?,
    val lens: String?,
    /** The f-number. */
    val aperture: Double?,
    /** Seconds the shutter was open. */
    val exposure: Double?,
    val iso: Int?,
    /** Millimetres, and the same on a 35 mm frame. */
    val focal: Double?,
    val focal35: Int?,
    val flash: Boolean?,
    val autoWhite: Boolean?,
    /** Exposure compensation in stops. */
    val bias: Double?,
    val latitude: Double?,
    val longitude: Double?,
    /** Metres above the sea. */
    val altitude: Double?,
    val software: String?,
    val author: String?,
    val copyright: String?,
    /** Every EXIF tag the file holds, by its name. */
    val tags: List<Pair<String, String>>,
) {
    companion object {
        /** Reads [file], the picture at [node], with [picture] once it is decoded. Blocking. */
        fun read(file: File, node: Node, picture: Picture?): PictureFacts {
            val exif = runCatching { ExifInterface(file) }.getOrNull()
            fun text(tag: String) = exif?.getAttribute(tag)?.trim()?.trimEnd('\u0000')?.takeIf { it.isNotEmpty() }
            fun number(tag: String) = exif?.getAttributeDouble(tag, Double.NaN)?.takeIf { !it.isNaN() && it > 0 }
            fun whole(tag: String) = exif?.getAttributeInt(tag, -1)?.takeIf { it >= 0 }
            // A RAW file says what EXIF's reader cannot read of it (CR3) through LibRaw.
            val numbers = DoubleArray(10) { Double.NaN }
            val raw = if (Pictures.of(file.name) == Reader.RAW) runCatching { NativePictures.rawFacts(file.path, numbers) }.getOrNull() else null
            fun rawText(i: Int) = raw?.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
            fun rawNumber(i: Int) = numbers[i].takeIf { raw != null && !it.isNaN() && it > 0 }
            val make = text(ExifInterface.TAG_MAKE) ?: rawText(0)
            val model = text(ExifInterface.TAG_MODEL) ?: rawText(1)
            val camera = when {
                make == null -> model
                model == null -> make
                // "Canon" + "Canon EOS R5" is said once.
                model.startsWith(make, ignoreCase = true) -> model
                else -> "$make $model"
            }
            // A place of 0, 0 is a GPS block left empty, not the Gulf of Guinea.
            val place = (exif?.latLong?.toList() ?: listOf(numbers[5], numbers[6]).takeIf { raw != null && !numbers[5].isNaN() && !numbers[6].isNaN() })
                ?.takeUnless { kotlin.math.abs(it[0]) < 1e-6 && kotlin.math.abs(it[1]) < 1e-6 }
            val flash = whole(ExifInterface.TAG_FLASH)?.let { it and 1 == 1 }
            // A RAW photo is as large as its data, not as the preview shown.
            val width = rawNumber(8)?.toInt() ?: picture?.uprightWidth ?: whole(ExifInterface.TAG_IMAGE_WIDTH) ?: 0
            val height = rawNumber(9)?.toInt() ?: picture?.uprightHeight ?: whole(ExifInterface.TAG_IMAGE_LENGTH) ?: 0
            return PictureFacts(
                node = node,
                width = width,
                height = height,
                format = format(node.name),
                colour = picture?.bitmap?.colorSpace?.let { colour(it) },
                hdr = picture?.let { hdr(it) },
                taken = (text(ExifInterface.TAG_DATETIME_ORIGINAL) ?: text(ExifInterface.TAG_DATETIME))?.let { date(it) }
                    ?: rawNumber(4)?.let { LocalDateTime.ofEpochSecond(it.toLong(), 0, java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochSecond(it.toLong()))) },
                camera = camera,
                lens = text(ExifInterface.TAG_LENS_MODEL) ?: rawText(2),
                aperture = number(ExifInterface.TAG_F_NUMBER) ?: rawNumber(2),
                exposure = number(ExifInterface.TAG_EXPOSURE_TIME) ?: rawNumber(1),
                iso = whole(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.takeIf { it > 0 } ?: rawNumber(0)?.toInt(),
                focal = number(ExifInterface.TAG_FOCAL_LENGTH) ?: rawNumber(3),
                focal35 = whole(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.takeIf { it > 0 },
                flash = flash,
                autoWhite = whole(ExifInterface.TAG_WHITE_BALANCE)?.let { it == 0 },
                bias = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN)?.takeIf { !it.isNaN() && it != 0.0 },
                latitude = place?.getOrNull(0),
                longitude = place?.getOrNull(1),
                altitude = (exif?.getAltitude(Double.NaN)?.takeIf { !it.isNaN() } ?: numbers[7].takeIf { raw != null && !it.isNaN() })?.takeIf { place != null },
                software = text(ExifInterface.TAG_SOFTWARE) ?: rawText(3),
                author = text(ExifInterface.TAG_ARTIST) ?: rawText(4),
                copyright = text(ExifInterface.TAG_COPYRIGHT),
                tags = exif?.let { tags(it) }.orEmpty(),
            )
        }

        private fun format(name: String): String = when (val e = Paths.extension(name)) {
            "jpg", "jpeg", "jpe", "jfif" -> "JPEG"
            "heic", "heif", "hif" -> "HEIF"
            "tif", "tiff" -> "TIFF"
            "jxl" -> "JPEG XL"
            "apng" -> "PNG"
            else -> e.uppercase()
        }

        private fun colour(space: ColorSpace): String = when (space) {
            ColorSpace.get(ColorSpace.Named.SRGB), ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB) -> "sRGB"
            ColorSpace.get(ColorSpace.Named.DISPLAY_P3) -> "Display P3"
            ColorSpace.get(ColorSpace.Named.DCI_P3) -> "DCI-P3"
            ColorSpace.get(ColorSpace.Named.ADOBE_RGB) -> "Adobe RGB"
            ColorSpace.get(ColorSpace.Named.PRO_PHOTO_RGB) -> "ProPhoto RGB"
            ColorSpace.get(ColorSpace.Named.BT2020), ColorSpace.get(ColorSpace.Named.BT2020_PQ), ColorSpace.get(ColorSpace.Named.BT2020_HLG) -> "BT.2020"
            else -> space.name
        }

        private fun hdr(picture: Picture): Hdr? = when {
            picture.gain != null -> Hdr.Gained(picture.gain.fullHdr)
            picture.bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.BT2020_PQ) -> Hdr.Pq
            picture.bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.BT2020_HLG) -> Hdr.Hlg
            else -> null
        }

        private fun date(text: String): LocalDateTime? = runCatching { LocalDateTime.parse(text.take(19), EXIF_DATE) }.getOrNull()

        // Every tag the platform knows, by the names of its constants, with what the file holds for it.
        private fun tags(exif: ExifInterface): List<Pair<String, String>> = TAG_NAMES.mapNotNull { (name, tag) ->
            exif.getAttribute(tag)?.trim()?.trimEnd('\u0000')?.takeIf { it.isNotEmpty() && it.length < 400 }?.let { name to it }
        }

        private val TAG_NAMES: List<Pair<String, String>> by lazy {
            ExifInterface::class.java.fields
                .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
                .mapNotNull { f -> (f.get(null) as? String)?.let { it to it } }
                .distinctBy { it.second }
                .sortedBy { it.first }
        }

        private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    }
}
