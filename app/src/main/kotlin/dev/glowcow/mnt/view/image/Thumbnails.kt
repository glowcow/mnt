package dev.glowcow.mnt.view.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.os.ParcelFileDescriptor
import android.util.LruCache
import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Remote
import dev.glowcow.mnt.files.RemoteProvider
import dev.glowcow.mnt.video.VlcFrames
import dev.glowcow.mnt.video.Videos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.videolan.libvlc.Media
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Small square pictures of pictures, for the rows of a folder: from what the file or its place keeps ready (EXIF's,
 * a camera's) when that is large enough, else decoded small. Kept in memory and in [dir], under the file's path, size
 * and time; a server's file is fetched into [scratch] only when it is no larger than [FAR_WHOLE].
 */
class Thumbnails(private val context: Context, private val files: FileStore, private val dir: File, private val scratch: File) {
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 32 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    // What could not be made is not tried again while the app runs.
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val phone = Semaphore(LOCAL_AT_ONCE)
    private val places = ConcurrentHashMap<String, Semaphore>()

    init {
        thread(name = "thumbnails-trim", priority = Thread.MIN_PRIORITY) { trim() }
    }

    /** The thumbnail of [node] if it is in memory already. */
    fun cached(node: Node): Bitmap? = memory.get(key(node))

    /** The thumbnail of [node], [size] pixels a side; null when it cannot be made. */
    suspend fun of(node: Node, size: Int): Bitmap? {
        val key = key(node)
        memory.get(key)?.let { return it }
        if (key in failed) return null
        val gate = Remote.id(node.path)?.let { places.getOrPut(it) { Semaphore(FAR_AT_ONCE) } } ?: phone
        val made = gate.withPermit {
            runInterruptible(Dispatchers.IO) {
                load(key) ?: runCatching { make(node, size) }.getOrNull()?.let { square(it, size) }?.also { save(key, it) }
            }
        }
        if (made == null) failed += key else memory.put(key, made)
        return made
    }

    /** Bytes of thumbnails kept on the phone. */
    fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Forgets every thumbnail, in memory and on the phone. */
    fun clear() {
        memory.evictAll()
        failed.clear()
        dir.deleteRecursively()
    }

    private fun make(node: Node, size: Int): Bitmap? {
        if (Videos.plays(node.name)) return frame(node, size)
        if (Remote.id(node.path) == null) return fromFile(File(node.path), size)
        // A camera on the USB port has one ready; a JPEG keeps one in its first kilobytes.
        files.thumbnail(node.path)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it } }
        // A RAW file keeps a small picture near its start, past what a JPEG needs.
        val raw = Pictures.of(node.name) == Reader.RAW
        val head = files.read(node.path).use { it.readNBytes(if (raw) RAW_HEAD else HEAD) }
        runCatching { exifThumbnail(ExifInterface(ByteArrayInputStream(head)), size) }.getOrNull()?.let { return it }
        if (node.size > FAR_WHOLE) return null
        scratch.mkdirs()
        val copy = File.createTempFile("thumb", "." + node.name.substringAfterLast('.', "bin"), scratch)
        try {
            files.read(node.path).use { input -> copy.outputStream().use { input.copyTo(it) } }
            return fromFile(copy, size)
        } finally {
            copy.delete()
        }
    }

    // A frame of a video a tenth of the way in, past a dark opening, or further while the frame is all but black: VLC's,
    // whatever the format. A server's file is read through the app's provider, only round the frame.
    private fun frame(node: Node, size: Int): Bitmap? {
        if (Remote.id(node.path) == null) return VlcFrames.square(context, { Media(it, node.path) }, size, FRAME_SHARES, ::dark)
        val opened = mutableListOf<ParcelFileDescriptor>()
        try {
            return VlcFrames.square(context, { vlc ->
                context.contentResolver.openFileDescriptor(RemoteProvider.uri(context, node.path), "r")?.let { fd ->
                    opened += fd
                    Media(vlc, fd.fileDescriptor)
                }
            }, size, FRAME_SHARES, ::dark)
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    // Whether a frame is all but black, from a grid of its pixels.
    private fun dark(frame: Bitmap): Boolean {
        var sum = 0f
        var count = 0
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val p = frame.getPixel(frame.width * (2 * x + 1) / (2 * GRID), frame.height * (2 * y + 1) / (2 * GRID))
            sum += (0.2126f * (p shr 16 and 0xFF) + 0.7152f * (p shr 8 and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
            count++
        }
        return sum / count < DARK
    }

    private fun fromFile(file: File, size: Int): Bitmap? {
        if (Pictures.of(file.name) != Reader.PLATFORM) return Readers.thumbnail(file, size)
        runCatching { exifThumbnail(ExifInterface(file), size) }.getOrNull()?.let { return it }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleFor(minOf(info.size.width, info.size.height).toFloat() / size))
        }
    }

    // EXIF's own small picture, turned upright, when it is no smaller than most of [size].
    private fun exifThumbnail(exif: ExifInterface, size: Int): Bitmap? {
        if (!exif.hasThumbnail()) return null
        val small = exif.thumbnailBitmap ?: return null
        if (minOf(small.width, small.height) < size * 3 / 4) return null
        val turn = Orientation.of(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
        if (turn == Orientation.NORMAL) return small
        return Bitmap.createBitmap(small, 0, 0, small.width, small.height, turn.matrix(small.width.toFloat(), small.height.toFloat()), true)
    }

    // The middle of the picture, [size] a side.
    private fun square(bitmap: Bitmap, size: Int): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val scale = size.toFloat() / side
        val m = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, m, true)
    }

    private fun load(key: String): Bitmap? = File(dir, "$key.webp").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    private fun save(key: String, bitmap: Bitmap) {
        dir.mkdirs()
        val part = File(dir, "$key.part")
        runCatching {
            part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, it) }
            part.renameTo(File(dir, "$key.webp"))
        }.onFailure { part.delete() }
    }

    // The oldest go once there are more than the cache may hold.
    private fun trim() {
        val all = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = all.sumOf { it.length() }
        for (file in all) {
            if (total <= KEEP_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private fun key(node: Node): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${node.path}\u0000${node.size}\u0000${node.modified}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val LOCAL_AT_ONCE = 3
        const val FAR_AT_ONCE = 2
        const val HEAD = 128 * 1024
        const val RAW_HEAD = 1024 * 1024
        const val FAR_WHOLE = 20L * 1024 * 1024
        const val QUALITY = 80
        const val KEEP_BYTES = 64L * 1024 * 1024
        val FRAME_SHARES = listOf(0.1f, 0.3f, 0.5f)
        const val GRID = 8
        const val DARK = 0.06f
    }
}
