package dev.glowcow.mnt.ui.files

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glowcow.mnt.AppContainer
import dev.glowcow.mnt.MntApp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.TransferProgress
import dev.glowcow.mnt.files.Trash
import dev.glowcow.mnt.files.Trashed
import dev.glowcow.mnt.ui.components.BIN_KEY
import dev.glowcow.mnt.ui.components.DetailHeader
import dev.glowcow.mnt.ui.components.DetailPage
import dev.glowcow.mnt.ui.components.EmptyState
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.components.IconButton48
import dev.glowcow.mnt.ui.components.IslandIcons
import dev.glowcow.mnt.ui.components.LocalOrigins
import dev.glowcow.mnt.ui.components.NodeRow
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BinViewModel(private val c: AppContainer) : ViewModel() {
    /** What the bin holds, the latest first; null until read. */
    val items = MutableStateFlow<List<Trashed>?>(null)

    /** Ids of the rows that are ticked. */
    val selection = MutableStateFlow<Set<String>>(emptySet())
    private var before = emptySet<String>()

    val progress: StateFlow<TransferProgress?> = c.transfers.progress

    init {
        viewModelScope.launch { c.transfers.changes.collect { reload() } }
    }

    fun reload() = viewModelScope.launch {
        val read = c.trash.entries()
        items.value = read
        selection.value = selection.value.intersect(read.mapTo(HashSet()) { it.id })
    }

    fun toggle(id: String) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    /** Ticks every row; with every row ticked, goes back to what was ticked before. */
    fun selectAll() {
        val all = items.value.orEmpty().mapTo(HashSet()) { it.id }
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

    fun display(path: String): String = c.roots.display(path)

    fun selected(): List<Trashed> = items.value.orEmpty().filter { it.id in selection.value }

    /** Puts the ticked items back where they were; [onDone] hears how many could not be. */
    fun restore(onDone: (failed: Int) -> Unit) = viewModelScope.launch {
        val failed = selected().count { !c.trash.restore(it) }
        clearSelection()
        c.transfers.changes.value++
        onDone(failed)
    }

    /** Deletes the ticked items for good, or with [all] everything; false when another transfer is running. */
    fun delete(all: Boolean): Boolean {
        if (!c.transfers.delete((if (all) items.value.orEmpty() else selected()).flatMap { Trash.paths(it) })) return false
        clearSelection()
        return true
    }

    fun stop() = c.transfers.stop()
}

/** The sheet the bin's page has open: what is ticked, or the question before it deletes for good. */
enum class BinSheet { DETAILS, DELETE, CLEAR }

/** The bin: what was deleted on the mounted volumes, to be put back or removed for good. */
@Composable
fun BinScreen(onBack: () -> Unit, vm: BinViewModel = viewModel { BinViewModel((this[APPLICATION_KEY] as MntApp).container) }) {
    val c = MntTheme.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    val items by vm.items.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<BinSheet?>(null) }
    val list = rememberLazyListState()
    val now = remember(items) { System.currentTimeMillis() }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    LifecycleResumeEffect(Unit) {
        vm.reload()
        onPauseOrDispose {}
    }
    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }

    val bar: (@Composable () -> Unit)? = when {
        selection.isNotEmpty() -> ({
            IslandIcons {
                IconButton48(MntIcons.Restore, stringResource(R.string.action_restore)) {
                    vm.restore { failed ->
                        toast(if (failed > 0) resources.getString(R.string.transfer_failed, failed) else resources.getString(R.string.bin_restored))
                    }
                }
                IconButton48(MntIcons.Info, stringResource(R.string.action_details)) { sheet = BinSheet.DETAILS }
                IconButton48(MntIcons.Delete, stringResource(R.string.delete_for_good), tint = c.danger) { sheet = BinSheet.DELETE }
            }
        })
        progress != null -> ({ progress?.let { ProgressBar(it, vm::stop) } })
        else -> null
    }

    val origins = LocalOrigins.current
    val from = remember { origins.take(BIN_KEY) }
    DetailPage(
        ground = c.bg,
        scrolled = list.canScrollBackward,
        from = from,
        header = {
            if (selection.isNotEmpty()) {
                DetailHeader(
                    stringResource(R.string.selected_count, selection.size),
                    onLeave = vm::clearSelection,
                    leave = MntIcons.Close,
                    leaveLabel = stringResource(R.string.action_cancel),
                ) {
                    SelectAllButton(all = selection.size == items.orEmpty().size, onClick = vm::selectAll)
                }
            } else {
                DetailHeader(stringResource(R.string.bin), onBack) {
                    IconButton48(MntIcons.Delete, stringResource(R.string.bin_clear), enabled = !items.isNullOrEmpty()) { sheet = BinSheet.CLEAR }
                }
            }
            Text(
                stringResource(R.string.bin_text, Trash.KEEP_DAYS),
                color = c.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        },
        bar = bar,
    ) { top, bottom ->
        val shown = items ?: return@DetailPage
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(top = top, bottom = bottom), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.bin_empty))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = top, bottom = bottom)) {
                items(shown, key = { it.id }) { item ->
                    val days = Trash.daysLeft(item.deleted, now).coerceAtLeast(1)
                    val meta = "${Formatter.formatShortFileSize(context, item.bytes)} | ${pluralStringResource(R.plurals.days_left, days, days)}"
                    // There is nothing to open in the bin: a tap ticks the row like a long press.
                    NodeRow(item.node, meta, selected = item.id in selection, onLongClick = { vm.toggle(item.id) }) { vm.toggle(item.id) }
                }
            }
        }
    }

    sheet?.let { asked ->
        if (asked == BinSheet.DETAILS) return@let BinDetails(remember { vm.selected() }, vm::display) { sheet = null }
        val all = asked == BinSheet.CLEAR
        GroupSheet(stringResource(if (all) R.string.bin_clear_title else R.string.delete_for_good_title), { sheet = null }) { pick ->
            Note(stringResource(R.string.delete_for_good_text))
            Group {
                GroupRow(
                    stringResource(if (all) R.string.bin_clear else R.string.delete_for_good),
                    titleColor = c.danger,
                    onClick = { pick { if (!vm.delete(all)) toast(resources.getString(R.string.transfer_busy)) } },
                )
            }
        }
    }
}

/** What the ticked items of the bin are: one alone tells where it was deleted from, when, and until when and where it is kept. */
@Composable
private fun BinDetails(items: List<Trashed>, display: (String) -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val single = items.singleOrNull()
    GroupSheet(stringResource(R.string.action_details), onDismiss) {
        if (single != null) {
            Group {
                GroupRow(stringResource(R.string.name_label), subtitle = single.node.name)
                GroupDivider()
                GroupRow(stringResource(R.string.bin_from), subtitle = display(single.from))
            }
            Spacer(Modifier.height(12.dp))
        }
        Group {
            GroupRow(stringResource(R.string.sort_size), value = Formatter.formatFileSize(context, items.sumOf { it.bytes }))
            if (single == null) {
                GroupDivider()
                GroupRow(stringResource(R.string.details_items), value = items.size.toString())
            }
        }
        if (single == null) return@GroupSheet
        Spacer(Modifier.height(12.dp))
        Group {
            GroupRow(stringResource(R.string.bin_deleted), value = dateTime(context, single.deleted))
            GroupDivider()
            GroupRow(stringResource(R.string.bin_until), value = dateTime(context, Trash.until(single.deleted)))
        }
        Spacer(Modifier.height(12.dp))
        Group { GroupRow(stringResource(R.string.bin_kept), subtitle = display(Paths.parent(single.id))) }
    }
}
