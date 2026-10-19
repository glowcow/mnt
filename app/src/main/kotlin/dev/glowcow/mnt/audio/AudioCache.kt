package dev.glowcow.mnt.audio

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Tracks of the servers, kept on the phone by the piece as they are read: the player, the tags and the wave take
 * each piece of a file from the network once, and again only when the file changed there.
 */
@OptIn(UnstableApi::class)
class AudioCache(context: Context, private val files: FileStore) {
    private val cache = SimpleCache(File(context.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(MAX_BYTES), StandaloneDatabaseProvider(context))

    // What each file was when last asked, so the many opens of one track ask the server once; kept past its time
    // to name the file by when the server cannot be asked.
    private val known = HashMap<String, Pair<Long, Node>>()

    /** Reads `mnt:` uris of the servers through the cache. */
    fun factory(): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory { StoreDataSource(files) }
        .setCacheKeyFactory { key(Sounds.path(it.uri)) }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** The file at [path], of [size] bytes, for the platform's readers, which ask for bytes here and there in it. */
    fun media(path: String, size: Long): MediaDataSource = CachedMedia(factory().createDataSource(), Sounds.uri(path), size)

    /** Bytes kept on the phone. */
    suspend fun size(): Long = withContext(Dispatchers.IO) { cache.cacheSpace }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(known) { known.clear() }
        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
    }

    // The path with the size and the time of the file as it is now: a file changed on the server is read anew.
    private fun key(path: String): String {
        val now = SystemClock.elapsedRealtime()
        val kept = synchronized(known) { known[path] }
        val node = kept?.takeIf { now - it.first < FRESH_MS }?.second
            ?: runCatching { runBlocking { files.stat(path) } }.getOrNull()?.also { synchronized(known) { known[path] = now to it } }
            ?: kept?.second
        return node?.let { "$path\u0000${it.size}\u0000${it.modified}" } ?: path
    }

    /** Reads on from where it stands, a little skipped over by reading; a jump further opens the source again there. */
    private class CachedMedia(private val source: DataSource, private val uri: Uri, private val size: Long) : MediaDataSource() {
        private var at = -1L
        private val skip = ByteArray(SKIP_BUFFER)

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            if (length <= 0) return 0
            return try {
                if (at < 0 || position < at || position - at > SKIP_AHEAD) {
                    close()
                    source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                    at = position
                }
                while (at < position) {
                    val n = source.read(skip, 0, minOf(skip.size.toLong(), position - at).toInt())
                    if (n == C.RESULT_END_OF_INPUT) return -1
                    at += n
                }
                val n = source.read(buffer, offset, length)
                if (n == C.RESULT_END_OF_INPUT) -1 else n.also { at += it }
            } catch (_: IOException) {
                close()
                -1
            }
        }

        override fun getSize(): Long = size

        @Synchronized
        override fun close() {
            if (at >= 0) runCatching { source.close() }
            at = -1
        }
    }

    private companion object {
        const val MAX_BYTES = 512L shl 20
        const val FRESH_MS = 60_000L
        const val SKIP_AHEAD = 512L shl 10
        const val SKIP_BUFFER = 64 shl 10
    }
}
