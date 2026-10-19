package dev.glowcow.mnt.audio

import android.graphics.ImageDecoder
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.glowcow.mnt.archive.ArchiveSource
import dev.glowcow.mnt.archive.StoreSource
import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Remote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.ByteBuffer

/** What the tags of a track say, and how it is coded: bits a second, samples a second, bits a sample; null where unknown. */
data class Tag(
    val title: String?,
    val artist: String?,
    val album: String?,
    val duration: Long?,
    val mime: String? = null,
    val bitrate: Int? = null,
    val rate: Int? = null,
    val bits: Int? = null,
)

/** Reads the tags of tracks that are not playing yet, and the pictures of the one that is. */
class Tags(private val files: FileStore) {
    private val known = LruCache<String, Tag>(KNOWN)
    private val covers = LruCache<Any, ImageBitmap>(COVERS)
    private val gate = Semaphore(2)

    fun known(id: String): Tag? = known[id]

    suspend fun read(id: String): Tag? {
        known[id]?.let { return it }
        if (Sounds.web(id)) return null
        return gate.withPermit { runInterruptible(Dispatchers.IO) { retrieve(id) } }?.also { known.put(id, it) }
    }

    private fun retrieve(path: String): Tag? {
        val retriever = MediaMetadataRetriever()
        var source: ArchiveSource? = null
        return try {
            if (Remote.id(path) == null) {
                retriever.setDataSource(path)
            } else {
                val size = runBlocking { files.stat(path) }?.size ?: return null
                source = StoreSource(files, path, size)
                retriever.setDataSource(SourceMedia(source))
            }
            Tag(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifEmpty { null },
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.trim()?.ifEmpty { null },
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.ifEmpty { null },
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull(),
            )
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
            source?.close()
        }
    }

    /** The picture of a track if it is at hand already. */
    fun peek(data: ByteArray?, path: String?): ImageBitmap? = (data?.contentHashCode() ?: path)?.let { covers[it] }

    /** The picture of a track: the one its file carries ([data]), else the folder's at [path]; at most [side] pixels a side. */
    suspend fun cover(data: ByteArray?, path: String?, side: Int): ImageBitmap? {
        val key: Any = data?.contentHashCode() ?: path ?: return null
        covers[key]?.let { return it }
        return runInterruptible(Dispatchers.IO) {
            try {
                val bytes = data ?: files.read(path!!).use { it.readNBytes(MAX_COVER) }
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val scale = minOf(1f, side.toFloat() / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }?.also { covers.put(key, it) }
    }

    private companion object {
        const val KNOWN = 4096
        const val COVERS = 6
        const val MAX_COVER = 16 shl 20
    }
}

// A file of another place for the platform's readers, which ask for bytes here and there in it.
internal class SourceMedia(private val source: ArchiveSource) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = try {
        if (position >= source.size || size <= 0) {
            -1
        } else {
            source.seek(position, 0)
            val block = ByteArray(minOf(size.toLong(), source.size - position).toInt())
            val n = source.read(block)
            if (n <= 0) -1 else n.also { block.copyInto(buffer, offset, 0, n) }
        }
    } catch (_: Exception) {
        -1
    }

    override fun getSize(): Long = source.size

    override fun close() = Unit
}
