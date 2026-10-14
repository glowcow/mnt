package dev.glowcow.mnt.ui.files

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glowcow.mnt.MntApp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.files.Clip
import dev.glowcow.mnt.files.Conflict
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Opener
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.TransferProgress
import dev.glowcow.mnt.ui.components.DetailHeader
import dev.glowcow.mnt.ui.components.DetailPage
import dev.glowcow.mnt.ui.components.EmptyState
import dev.glowcow.mnt.ui.components.IconButton48
import dev.glowcow.mnt.ui.components.IslandIcons
import dev.glowcow.mnt.ui.components.LocalOrigins
import dev.glowcow.mnt.ui.components.NodeRow
import dev.glowcow.mnt.ui.components.PillButton
import dev.glowcow.mnt.ui.components.ProgressLine
import dev.glowcow.mnt.ui.components.folderKey
import dev.glowcow.mnt.ui.components.nodeMeta
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.view.Format

/** The sheet a folder's page has open. */
enum class FolderSheet { SORT, NEW_FOLDER, RENAME, DELETE, MORE, DETAILS, ZIP, CONFLICT }

@Composable
fun FolderScreen(
    path: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onView: (String) -> Unit,
    vm: FolderViewModel = viewModel { FolderViewModel(path, (this[APPLICATION_KEY] as MntApp).container) },
) {
    val c = MntTheme.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    val content by vm.content.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val clip by vm.clip.collectAsStateWithLifecycle()
    val holds by vm.holds.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<FolderSheet?>(null) }
    var conflicts by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    fun toast(text: Int) = Toast.makeText(context, resources.getString(text), Toast.LENGTH_SHORT).show()

    // A transfer shows itself in a notification, so the first one asks for the permission.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun started(ok: Boolean) {
        if (!ok) return toast(R.string.transfer_busy)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun paste(clip: Clip) {
        if (clip.paths.any { Paths.within(path, it) }) return toast(R.string.paste_into_itself)
        conflicts = vm.conflicts(clip)
        if (conflicts > 0) sheet = FolderSheet.CONFLICT else started(vm.paste(Conflict.SKIP))
    }
    fun open(node: Node) {
        when {
            selection.isNotEmpty() -> vm.toggle(node.path)
            node.directory -> onOpen(node.path)
            Format.of(node.name) != null -> onView(node.path)
            !Opener.open(context, node.path) -> toast(R.string.open_none)
        }
    }

    LifecycleResumeEffect(Unit) {
        vm.reload()
        onPauseOrDispose {}
    }
    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }

    val bar: (@Composable () -> Unit)? = when {
        selection.isNotEmpty() -> ({
            SelectionBar(
                onCopy = { vm.pick(move = false) },
                onMove = { vm.pick(move = true) },
                onDetails = { sheet = FolderSheet.DETAILS },
                onDelete = { sheet = FolderSheet.DELETE },
                onMore = { sheet = FolderSheet.MORE },
            )
        })
        progress != null -> ({ progress?.let { ProgressBar(it, vm::stop) } })
        clip != null -> ({ clip?.let { ClipBar(it, onPaste = { paste(it) }, onCancel = vm::dropClip) } })
        else -> null
    }

    // Opened from a row of the Files tab, the page grows out of that row.
    val origins = LocalOrigins.current
    val from = remember { origins.take(folderKey(path)) }
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
                    SelectAllButton(all = (content as? FolderContent.Listed)?.nodes.orEmpty().all { it.path in selection }, onClick = vm::selectAll)
                }
            } else {
                val pinned = path in settings.pinned
                DetailHeader(vm.title, onBack) {
                    IconButton48(
                        if (pinned) MntIcons.PinFilled else MntIcons.Pin,
                        stringResource(if (pinned) R.string.action_unpin else R.string.action_pin),
                        size = 19.dp,
                    ) { vm.togglePinned() }
                    IconButton48(MntIcons.Sort, stringResource(R.string.action_sort)) { sheet = FolderSheet.SORT }
                    IconButton48(MntIcons.Plus, stringResource(R.string.action_new_folder)) { sheet = FolderSheet.NEW_FOLDER }
                }
            }
            Crumbs(vm.crumbs, onOpen)
        },
        bar = bar,
    ) { top, bottom ->
        when (val shown = content) {
            FolderContent.Loading -> Unit
            FolderContent.Closed -> Centered(top, bottom) { EmptyState(stringResource(R.string.folder_closed), vm.reason() ?: stringResource(R.string.folder_closed_text)) }
            is FolderContent.Listed -> if (shown.nodes.isEmpty()) {
                Centered(top, bottom) { EmptyState(stringResource(R.string.folder_empty)) }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = top, bottom = bottom)) {
                    items(shown.nodes, key = { it.path }) { node ->
                        NodeRow(node, nodeMeta(node, holds[node.path]), selected = node.path in selection, onLongClick = { vm.toggle(node.path) }) { open(node) }
                    }
                }
            }
        }
    }

    FolderSheets(
        sheet = sheet,
        vm = vm,
        sortBy = settings.sortBy,
        descending = settings.sortDescending,
        conflicts = conflicts,
        onSheet = { sheet = it },
        onStarted = ::started,
        onShare = { if (!Opener.share(context, vm.selected().map { it.path })) toast(R.string.open_none) },
        onFailed = { toast(R.string.action_failed) },
    )
}

/** Ticks every row of a list; filled and in the accent while they all are, when a tap takes that back. */
@Composable
fun SelectAllButton(all: Boolean, onClick: () -> Unit) {
    IconButton48(
        if (all) MntIcons.SelectAllFilled else MntIcons.SelectAll,
        stringResource(if (all) R.string.action_deselect_all else R.string.action_select_all),
        tint = if (all) MntTheme.colors.accent else MntTheme.colors.text,
        onClick = onClick,
    )
}

/** The way down to the folder, one step after another; a tap on a step goes there. */
@Composable
private fun Crumbs(crumbs: List<Crumb>, onOpen: (String) -> Unit) {
    val c = MntTheme.colors
    val scroll = rememberScrollState()
    // The end of a long way is what matters: where the page is.
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 8.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, crumb ->
            if (i > 0) Text("/", color = c.muted, fontSize = 13.sp)
            val here = i == crumbs.lastIndex
            Box(Modifier.clickable(enabled = !here) { onOpen(crumb.path) }.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(crumb.label, color = if (here) c.text else c.muted, fontSize = 13.sp, fontWeight = if (here) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Centered(top: Dp, bottom: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(top = top, bottom = bottom), contentAlignment = Alignment.Center) { content() }
}

/** What can be done with the ticked rows. */
@Composable
private fun SelectionBar(onCopy: () -> Unit, onMove: () -> Unit, onDetails: () -> Unit, onDelete: () -> Unit, onMore: () -> Unit) {
    IslandIcons {
        IconButton48(MntIcons.Copy, stringResource(R.string.action_copy), onClick = onCopy)
        IconButton48(MntIcons.Move, stringResource(R.string.action_move), onClick = onMove)
        IconButton48(MntIcons.Info, stringResource(R.string.action_details), onClick = onDetails)
        IconButton48(MntIcons.Delete, stringResource(R.string.action_delete), onClick = onDelete)
        IconButton48(MntIcons.More, stringResource(R.string.action_more), onClick = onMore)
    }
}

/** The files taken in another folder, waiting to be put into this one or packed into an archive here. */
@Composable
private fun ClipBar(clip: Clip, onPaste: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton48(MntIcons.Close, stringResource(R.string.action_cancel), onClick = onCancel)
        Text(
            clip.zip ?: stringResource(R.string.selected_count, clip.paths.size),
            color = MntTheme.colors.text,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        val paste = when {
            clip.zip != null -> R.string.paste_zip
            clip.move -> R.string.paste_move
            else -> R.string.paste_copy
        }
        PillButton(stringResource(paste), onClick = onPaste)
    }
}

/** A running transfer: what it does, how far and how fast, and the way to stop it. */
@Composable
fun ProgressBar(progress: TransferProgress, onStop: () -> Unit) {
    val c = MntTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(transferTitle(progress), color = c.text, fontSize = 15.sp, maxLines = 1)
            if (progress.name.isNotEmpty()) Text(progress.name, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            ProgressLine(progress.fraction, Modifier.padding(top = 4.dp))
        }
        PillButton(stringResource(R.string.action_stop), onClick = onStop)
    }
}
