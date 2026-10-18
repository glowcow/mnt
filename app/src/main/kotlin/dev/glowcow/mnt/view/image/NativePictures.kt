package dev.glowcow.mnt.view.image

/** The app's native readers of pictures: LibRaw, libjxl and libtiff, in `libmntpictures.so`. Every call blocks. */
object NativePictures {
    init {
        System.loadLibrary("mntpictures")
    }

    /** Where the readers put what they found out, in the `facts` array each takes. */
    const val WIDTH = 0
    const val HEIGHT = 1
    const val FULL_WIDTH = 2
    const val FULL_HEIGHT = 3
    const val ORIENTATION = 4
    const val ALPHA = 5
    const val WIDE = 6
    const val FORMAT = 7
    const val FACTS = 8

    /** The largest picture the camera put into the RAW file at [path]: JPEG bytes, or RGBA when facts[FORMAT] is 1. */
    @JvmStatic external fun rawPreview(path: String, facts: LongArray): ByteArray?

    /** The RAW data developed at half size, upright, in sRGB, as RGBA, LibRaw allowed [memoryMb] for it. */
    @JvmStatic external fun rawDevelop(path: String, memoryMb: Int, facts: LongArray): ByteArray?

    /** Make, model, lens, software, artist; [numbers] get ISO, shutter, f-number, focal length, time, latitude, longitude, altitude, width, height. */
    @JvmStatic external fun rawFacts(path: String, numbers: DoubleArray): Array<String?>?

    /** A JPEG XL picture, upright, its long side at most [maxSide], as RGBA. */
    @JvmStatic external fun jxl(data: ByteArray, maxSide: Int, facts: LongArray): ByteArray?

    /** The first picture of a TIFF file, its long side at most [maxSide], as stored, as RGBA. */
    @JvmStatic external fun tiff(path: String, maxSide: Int, facts: LongArray): ByteArray?
}
