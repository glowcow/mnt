package dev.glowcow.mnt.audio.upnp

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the queue plays: the phone or a renderer of the network, the renderers found, and the way to change it. */
class Casting(private val context: Context, private val scope: CoroutineScope) {
    var renderers by mutableStateOf<List<Renderer>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    /** The renderer that plays now; null for the phone. Set by the player's service as it moves the queue. */
    var target by mutableStateOf<Renderer?>(null)
        internal set

    /** The player's service, while it runs: moves the queue to a renderer or back to the phone. */
    internal var mover: ((Renderer?) -> Unit)? = null

    private var looking: Job? = null
    private val pictures = HashMap<String, ImageBitmap?>()

    fun search() {
        if (looking?.isActive == true) return
        looking = scope.launch {
            searching = true
            val found = withContext(Dispatchers.IO) { Renderers.search(context) }
            // The one playing stays listed even when it did not answer this time.
            renderers = (found + listOfNotNull(target)).distinctBy { it.id }.sortedBy { it.name.lowercase() }
            searching = false
        }
    }

    fun use(renderer: Renderer?) {
        mover?.invoke(renderer)
    }

    /** The picture [renderer] shows of itself, read once; null when it has none or it cannot be read. */
    fun knownPicture(renderer: Renderer): ImageBitmap? = pictures[renderer.icon]

    suspend fun picture(renderer: Renderer): ImageBitmap? {
        val url = renderer.icon ?: return null
        if (url in pictures) return pictures[url]
        val read = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = PICTURE_MS
                connection.readTimeout = PICTURE_MS
                try {
                    connection.inputStream.use { BitmapFactory.decodeStream(BoundedInput(it, MAX_PICTURE)) }?.asImageBitmap()
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
        pictures[url] = read
        return read
    }

    // Stops at [left] bytes: a picture larger than that is not one a renderer means to show.
    private class BoundedInput(input: InputStream, private var left: Int) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            return super.read(b, off, minOf(len, left)).also { if (it > 0) left -= it }
        }
    }

    private companion object {
        const val PICTURE_MS = 3000
        const val MAX_PICTURE = 512 * 1024
    }
}
