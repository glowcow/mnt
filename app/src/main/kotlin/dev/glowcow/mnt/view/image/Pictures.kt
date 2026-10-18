package dev.glowcow.mnt.view.image

import dev.glowcow.mnt.files.Paths

/** Who reads a picture: the platform's decoders, or one of the app's own for what the platform does not know. */
enum class Reader { PLATFORM, RAW, JXL, TIFF, SVG, PSD }

/** Which files the app shows as pictures, told by the end of their name. */
object Pictures {
    private val byExtension: Map<String, Reader> = buildMap {
        fun put(reader: Reader, vararg extensions: String) = extensions.forEach { put(it, reader) }
        put(Reader.PLATFORM, "jpg", "jpeg", "jpe", "jfif", "png", "apng", "gif", "webp", "heic", "heif", "hif", "avif", "bmp", "dib", "ico", "cur", "wbmp")
        put(Reader.RAW, "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "rwl", "pef", "ptx", "srw", "3fr", "fff", "iiq", "erf", "kdc", "dcr", "mrw", "mos", "x3f", "raw", "gpr")
        put(Reader.JXL, "jxl")
        put(Reader.TIFF, "tif", "tiff")
        put(Reader.SVG, "svg")
        put(Reader.PSD, "psd", "psb")
    }

    /** Who reads a file called [name]; null when it is no picture the app shows. */
    fun of(name: String): Reader? = byExtension[Paths.extension(name)]

    fun isPicture(name: String): Boolean = of(name) != null

    /** The extensions of every picture the app shows. */
    val extensions: Set<String> get() = byExtension.keys
}
