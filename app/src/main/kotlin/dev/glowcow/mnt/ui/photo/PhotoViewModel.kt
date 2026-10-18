package dev.glowcow.mnt.ui.photo

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glowcow.mnt.container
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.files.Listing
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.Remote
import dev.glowcow.mnt.view.image.Picture
import dev.glowcow.mnt.view.image.PictureFacts
import dev.glowcow.mnt.view.image.Readers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/** Where a picture of the row is on its way to the screen. */
sealed interface Shot {
    data object Loading : Shot

    /** A server's file coming into the phone: how much of it has come. */
    data class Fetching(val fraction: Float) : Shot

    data class Ready(val picture: Picture) : Shot

    data class Failed(val reason: String?) : Shot
}

/** The pictures of a folder, from the one opened: each made ready as it comes near the screen, let go as it leaves. */
class PhotoViewModel(val path: String, private val app: Application) : ViewModel() {
    private val c = app.container
    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The pictures of the folder in its order; the opened one alone until the folder is read. */
    var items by mutableStateOf<List<Node>>(listOf(Node(path, Paths.name(path), false, 0, 0)))
        private set

    /** Where [items] start on the screen: the opened picture. */
    var start by mutableIntStateOf(0)
        private set

    /** Bumped when [items] are read, for the pager to go to [start]. */
    var listed by mutableIntStateOf(0)
        private set

    private val shots = mutableStateMapOf<String, Shot>()
    private val jobs = HashMap<String, Job>()
    private val fetched = HashMap<String, File>()

    /** Nothing can be deleted here: inside an archive. */
    val writes: Boolean = c.files.writes(path)

    init {
        viewModelScope.launch {
            val s = settings.filterNotNull().first()
            val nodes = c.files.list(Paths.parent(path)) ?: return@launch
            val pictures = Listing.arrange(nodes.filter { !it.directory && Readers.shows(it.name) }, s.sortBy, s.sortDescending, s.showHidden)
            val at = pictures.indexOfFirst { it.path == path }
            if (at < 0) return@launch
            items = pictures
            start = at
            listed++
        }
    }

    fun shot(path: String): Shot = shots[path] ?: Shot.Loading

    /** RAW photos shown developed from their data rather than by the camera's preview. */
    var developed by mutableStateOf<Set<String>>(emptySet())
        private set

    /** A RAW photo being developed: the picture before it stays on the screen meanwhile. */
    var developing by mutableStateOf<String?>(null)
        private set

    /**
     * Makes the pictures of [near] ready for a screen whose long side is [screen] pixels, the first one first, and lets
     * go of every one not in [keep].
     */
    fun focus(near: List<String>, keep: Set<String>, screen: Int) {
        keepOnly(keep)
        near.forEach { want(it, screen) }
    }

    private fun want(path: String, screen: Int) {
        if (path in jobs) return
        jobs[path] = viewModelScope.launch {
            shots[path] = try {
                val file = file(path)
                val develop = path in developed
                Shot.Ready(withContext(Dispatchers.IO) { Readers.open(file, screen, develop, spareMb()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Shot.Failed(null)
            } catch (e: Throwable) {
                Shot.Failed(e.message?.takeIf { it.isNotBlank() })
            }
        }
    }

    /** Shows the RAW photo at [path] developed from its data, or by the camera's preview again. */
    fun develop(path: String, on: Boolean, screen: Int) {
        developed = if (on) developed + path else developed - path
        developing = path
        val before = jobs.remove(path)
        before?.cancel()
        jobs[path] = viewModelScope.launch {
            val next = try {
                Shot.Ready(withContext(Dispatchers.IO) { Readers.open(file(path), screen, on, spareMb()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Shot.Failed(e.message?.takeIf { it.isNotBlank() })
            }
            (shots[path] as? Shot.Ready)?.picture?.close()
            shots[path] = next
            if (developing == path) developing = null
        }
    }

    // LibRaw may take a quarter of the memory the system has to spare.
    private fun spareMb(): Int {
        val info = android.app.ActivityManager.MemoryInfo()
        app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(info)
        return (info.availMem / 4 / (1024 * 1024)).toInt().coerceIn(MIN_DEVELOP_MB, MAX_DEVELOP_MB)
    }

    // Lets go of every picture but those of [keep]; a picture closes off the main thread, as it may wait for a part being decoded.
    private fun keepOnly(keep: Set<String>) {
        for (path in jobs.keys - keep) {
            jobs.remove(path)?.cancel()
            val picture = (shots.remove(path) as? Shot.Ready)?.picture
            val file = fetched.remove(path)
            if (picture != null || file != null) viewModelScope.launch(Dispatchers.IO) {
                picture?.close()
                file?.delete()
            }
        }
    }

    /** What the file and its picture tell of themselves. */
    suspend fun facts(node: Node): PictureFacts? = runCatching {
        val file = file(node.path)
        withContext(Dispatchers.IO) { PictureFacts.read(file, node, (shot(node.path) as? Shot.Ready)?.picture) }
    }.getOrNull()

    /** Sends the picture at [path] to the bin, or away for good where there is none; false when a transfer is under way. */
    fun delete(path: String, forGood: Boolean): Boolean {
        if (!(if (forGood || !binned()) c.transfers.delete(listOf(path)) else c.transfers.discard(listOf(path)))) return false
        val at = items.indexOfFirst { it.path == path }
        if (at >= 0) items = items.toMutableList().apply { removeAt(at) }
        keepOnly(shots.keys - path)
        return true
    }

    /** Whether a delete here can go to the bin: on the phone's own storage always, elsewhere when the setting says so. */
    fun binned(): Boolean = Remote.id(path) == null && (c.volumes.of(path)?.primary == true || settings.value?.binExternal == true)

    // A picture is read from a file of the phone: a server's or an archive's is fetched into the cache first.
    private suspend fun file(path: String): File {
        if (Remote.id(path) == null) return File(path)
        fetched[path]?.let { return it }
        val node = c.files.stat(path) ?: throw java.io.FileNotFoundException(path)
        val dir = File(app.cacheDir, "photo").apply { mkdirs() }
        val target = File(dir, "${path.hashCode().toUInt()}-${Paths.name(path).takeLast(60)}")
        val total = node.size.coerceAtLeast(1)
        try {
            runInterruptible(Dispatchers.IO) {
                c.files.read(path).use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        var done = 0L
                        var said = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (done - said > total / 50) {
                                said = done
                                val fraction = done.toFloat() / total
                                viewModelScope.launch { if (shots[path] !is Shot.Ready) shots[path] = Shot.Fetching(fraction) }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { target.delete() }
            throw e
        }
        fetched[path] = target
        return target
    }

    private companion object {
        const val MIN_DEVELOP_MB = 256
        const val MAX_DEVELOP_MB = 2048
    }

    override fun onCleared() {
        jobs.values.forEach { it.cancel() }
        shots.values.forEach { (it as? Shot.Ready)?.picture?.close() }
        fetched.values.forEach { it.delete() }
    }
}
