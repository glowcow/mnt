package dev.glowcow.mnt.maps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Tiles of OpenStreetMap's standard map, kept on the phone once downloaded: the tile server asks
 * its users to cache and to say who they are. [agent] is that name.
 */
class TileStore(private val dir: File, private val agent: String) {

    /** The tile from the cache, else from the network; null when it cannot be had. */
    suspend fun tile(zoom: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val side = 1 shl zoom
        if (y !in 0 until side) return@withContext null
        val column = x.mod(side)
        val file = File(dir, "$zoom/$column/$y.png")
        if (!file.isFile) {
            runCatching { download(zoom, column, y, file) }.onFailure { file.delete() }
        }
        if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    private fun download(zoom: Int, x: Int, y: Int, into: File) {
        val connection = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", agent)
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            into.parentFile?.mkdirs()
            val part = File(into.path + ".part")
            connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(into)) error("cannot store the tile")
        } finally {
            connection.disconnect()
        }
    }

    /** Bytes the cache takes. */
    suspend fun size(): Long = withContext(Dispatchers.IO) { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
