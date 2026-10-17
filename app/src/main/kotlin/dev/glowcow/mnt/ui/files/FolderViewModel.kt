package dev.glowcow.mnt.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glowcow.mnt.AppContainer
import dev.glowcow.mnt.Haptics
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.files.Clip
import dev.glowcow.mnt.files.Conflict
import dev.glowcow.mnt.files.Facts
import dev.glowcow.mnt.files.Holds
import dev.glowcow.mnt.files.Listing
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.Remote
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
    private var before = emptySet<String>()

    val progress: StateFlow<TransferProgress?> = c.transfers.progress
    val clip: StateFlow<Clip?> = c.transfers.clip

    // The place outside the phone's file system this folder lies in; null on the phone and its drives.
    private val remote = Remote.id(path)

    /** The volume by its name, or the folder's own name below it. */
    val title: String
    val crumbs: List<Crumb>

    init {
        val root = c.roots.of(path)
        val steps = path.removePrefix(root?.path.orEmpty()).split('/').filter { it.isNotEmpty() }
        var at = root?.path.orEmpty()
        crumbs = listOfNotNull(root?.let { Crumb(it.label, it.path) }) + steps.map { step ->
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
            val started = System.nanoTime()
            val nodes = c.files.list(path)
            // A server's top folder opened the first time is the server reached, or not.
            if (read.value == null && remote != null && path == Remote.root(remote)) {
                if (nodes == null) c.haptics.done(false) else if ((System.nanoTime() - started) / 1_000_000 >= Haptics.WAIT_MS) c.haptics.done(true)
            }
            val hidden = c.settings.settings.first().showHidden
            // Looking into every folder of a place on the network would take as many trips there.
            val folders = if (remote == null) nodes.orEmpty().filter { it.directory } else emptyList()
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

    /** Ticks every row; with every row ticked, goes back to what was ticked before. */
    fun selectAll() {
        val all = (content.value as? FolderContent.Listed)?.nodes.orEmpty().mapTo(HashSet()) { it.path }
        if (selection.value == all) {
            selection.value = before.intersect(all)
        } else {
            before = selection.value
            selection.value = all
        }
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

    /** Takes the ticked rows to be packed elsewhere into an archive called [name]. */
    fun pickZip(name: String) {
        c.transfers.clip.value = Clip(selected().map { it.path }, move = false, zip = if (name.endsWith(ZIP, ignoreCase = true)) name else name + ZIP)
        clearSelection()
    }

    /** The name offered for an archive of the ticked rows: that of the one, or of this folder for several. */
    fun zipName(): String {
        val one = selected().singleOrNull()
        val stem = when {
            one == null -> title
            one.directory || Paths.extension(one.name).isEmpty() -> one.name
            else -> one.name.substringBeforeLast('.')
        }
        return stem + ZIP
    }

    fun dropClip() {
        c.transfers.clip.value = null
    }

    /** How many of the items to paste would land on a name that is taken here. */
    fun conflicts(clip: Clip): Int {
        // An archive takes a spare name instead.
        if (clip.zip != null) return 0
        val here = names()
        return clip.paths.count { Paths.parent(it) != path && Paths.name(it) in here }
    }

    /** False when another transfer is running. */
    fun paste(conflict: Conflict): Boolean {
        val clip = clip.value ?: return true
        val taken = names()
        val started = clip.zip?.let { c.transfers.zip(clip.paths, Paths.child(path, Paths.free(it) { name -> name in taken })) }
            ?: c.transfers.copy(clip.paths, path, clip.move, conflict)
        if (!started) return false
        dropClip()
        return true
    }

    /** Whether what is deleted here can go to a bin: on the phone's own storage always, elsewhere when the setting says so. */
    fun binned(): Boolean = remote == null && (c.volumes.of(path)?.primary == true || settings.value.binExternal)

    /** Whether what lies here can be given another name. */
    fun renames(): Boolean = c.files.renames(path)

    /** Why this folder of a place on the network could not be read, in that place's own words. */
    fun reason(): String? = remote?.let { c.remotes.error(it) }

    /** Into the bin, or [forGood] — also where there is no bin. */
    fun delete(forGood: Boolean): Boolean {
        val paths = selected().map { it.path }
        if (!(if (forGood || !binned()) c.transfers.delete(paths) else c.transfers.discard(paths))) return false
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

    suspend fun facts(node: Node): Facts? = c.files.facts(node.path)

    fun display(path: String): String = c.roots.display(path)

    fun stop() = c.transfers.stop()

    fun setSort(by: SortBy, descending: Boolean) = viewModelScope.launch { c.settings.setSort(by, descending) }

    fun togglePinned() = viewModelScope.launch { c.settings.togglePinned(path) }

    private companion object {
        const val COUNT_WAIT_MS = 150L
        const val COUNT_AT_ONCE = 16
        const val ZIP = ".zip"
    }
}
