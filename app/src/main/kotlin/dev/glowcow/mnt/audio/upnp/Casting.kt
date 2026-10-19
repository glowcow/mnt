package dev.glowcow.mnt.audio.upnp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
}
