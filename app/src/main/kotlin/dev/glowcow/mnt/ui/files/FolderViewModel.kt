package dev.glowcow.mnt.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glowcow.mnt.AppContainer
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.files.Clip
import dev.glowcow.mnt.files.Conflict
import dev.glowcow.mnt.files.Holds
import dev.glowcow.mnt.files.Listing
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.SortBy
import dev.glowcow.mnt.files.Transfer
import dev.glowcow.mnt.files.TransferProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What a folder's page shows. */
sealed interface FolderContent {
    data object Loading : FolderContent

    /** The folder cannot be read: it is gone, or the system keeps it shut. */
    data object Closed : FolderContent

    class Listed(val nodes: List<Node>) : FolderContent
}

/** A step of the way down to a folder. */
class Crumb(val label: String, val path: String)

/** What the details sheet tells about the chosen items. */
class Details(val bytes: Long, val items: Long)

class FolderViewModel(val path: String, private val c: AppContainer) : ViewModel() {
    // What the folder holds as read from the store; null until read, and empty with [closed] when it cannot be.
    private val read = MutableStateFlow<List<Node>?>(null)
    private val closed = MutableStateFlow(false)

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val content: StateFlow<FolderContent> = combine(read, closed, settings) { nodes, closed, s ->
        when {
            closed -> FolderContent.Closed
            nodes == null -> FolderContent.Loading
            else -> FolderContent.Listed(Listing.arrange(nodes, s.sortBy, s.sortDescending, s.showHidden))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FolderContent.Loading)

    /** What each folder of the list holds, by its path; a folder is missing until it is counted. */
    val holds = MutableStateFlow<Map<String, Holds>>(emptyMap())
    private var counting: Job? = null

    /** Paths of the rows that are ticked. */
    val selection = MutableStateFlow<Set<String>>(emptySet())

    val progress: StateFlow<TransferProgress?> = c.transfers.progress
    val clip: StateFlow<Clip?> = c.transfers.clip

    /** The volume by its name, or the folder's own name below it. */
    val title: String
    val crumbs: List<Crumb>

    init {
        val volume = c.volumes.of(path)
        val steps = path.removePrefix(volume?.path.orEmpty()).split('/').filter { it.isNotEmpty() }
        var at = volume?.path.orEmpty()
        crumbs = listOfNotNull(volume?.let { Crumb(it.label, it.path) }) + steps.map { step ->
            at = Paths.child(at.ifEmpty { "/" }, step)
            Crumb(step, at)
        }
        title = crumbs.lastOrNull()?.label ?: path
        // A transfer that ends anywhere may have changed what is here.
        viewModelScope.launch { c.transfers.changes.collect { reload() } }
    }

    fun reload() {
        counting?.cancel()
        counting = viewModelScope.launch {
            val nodes = c.files.list(path)
            val hidden = c.settings.settings.first().showHidden
            val folders = nodes.orEmpty().filter { it.directory }
            // The list waits a moment for its folders to be counted, so it shows up whole instead of
            // changing line by line; what takes longer comes after it, all at once.
            val counted = HashMap<String, Holds>()
            val whole = withTimeoutOrNull(COUNT_WAIT_MS) { count(folders, hidden, counted) } != null
            holds.value = holds.value.filterKeys { known -> folders.any { it.path == known } } + counted
            closed.value = nodes == null
            read.value = nodes.orEmpty()
            // What is no longer here cannot stay ticked.
            selection.value = selection.value.intersect(nodes.orEmpty().mapTo(HashSet()) { it.path })
            if (!whole) {
                count(folders.filter { it.path !in counted }, hidden, counted)
                holds.value = counted.toMap()
            }
        }
    }

    // A few folders at a time: reading them side by side takes as long as the slowest, not as all of them.
    private suspend fun count(folders: List<Node>, hidden: Boolean, into: MutableMap<String, Holds>) {
        for (batch in folders.chunked(COUNT_AT_ONCE)) {
            val counted = coroutineScope {
                batch.map { folder ->
                    async {
                        val inside = c.files.list(folder.path)?.filter { hidden || !it.hidden } ?: return@async null
                        val within = inside.count { it.directory }
                        folder.path to Holds(files = inside.size - within, folders = within)
                    }
                }.awaitAll()
            }
            into += counted.filterNotNull()
        }
    }

    fun toggle(path: String) {
        selection.value = selection.value.let { if (path in it) it - path else it + path }
    }

    fun selectAll() {
        selection.value = (content.value as? FolderContent.Listed)?.nodes.orEmpty().mapTo(HashSet()) { it.path }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /** The ticked rows, in the order of the list. */
    fun selected(): List<Node> = (content.value as? FolderContent.Listed)?.nodes.orEmpty().filter { it.path in selection.value }

    /** Takes the ticked rows to be pasted elsewhere. */
    fun pick(move: Boolean) {
        c.transfers.clip.value = Clip(selected().map { it.path }, move)
        clearSelection()
    }

    fun dropClip() {
        c.transfers.clip.value = null
    }

    /** How many of the items to paste would land on a name that is taken here. */
    fun conflicts(clip: Clip): Int {
        val here = read.value.orEmpty().mapTo(HashSet()) { it.name }
        return clip.paths.count { Paths.parent(it) != path && Paths.name(it) in here }
    }

    /** False when another transfer is running. */
    fun paste(conflict: Conflict): Boolean {
        val clip = clip.value ?: return true
        if (!c.transfers.copy(clip.paths, path, clip.move, conflict)) return false
        dropClip()
        return true
    }

    fun delete(): Boolean {
        if (!c.transfers.delete(selected().map { it.path })) return false
        clearSelection()
        return true
    }

    fun names(): Set<String> = read.value.orEmpty().mapTo(HashSet()) { it.name }

    fun createFolder(name: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        onDone(c.files.mkdir(Paths.child(path, name)))
        reload()
    }

    fun rename(node: Node, name: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        onDone(c.files.rename(node.path, Paths.child(path, name)))
        clearSelection()
        reload()
    }

    suspend fun details(nodes: List<Node>): Details {
        val transfer = Transfer(c.files)
        return Details(nodes.sumOf { transfer.bytes(it.path) }, nodes.sumOf { transfer.count(it.path) })
    }

    fun display(path: String): String = c.volumes.display(path)

    fun stop() = c.transfers.stop()

    fun setSort(by: SortBy, descending: Boolean) = viewModelScope.launch { c.settings.setSort(by, descending) }

    fun togglePinned() = viewModelScope.launch { c.settings.togglePinned(path) }

    private companion object {
        const val COUNT_WAIT_MS = 150L
        const val COUNT_AT_ONCE = 16
    }
}
