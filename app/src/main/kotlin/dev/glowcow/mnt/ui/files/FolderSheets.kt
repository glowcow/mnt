package dev.glowcow.mnt.ui.files

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.files.Conflict
import dev.glowcow.mnt.files.Facts
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Opener
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.SortBy
import dev.glowcow.mnt.files.Trash
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupField
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.ui.theme.inked

/** The sheets of a folder's page. [onStarted] hears whether a transfer could begin, [onFailed] that a quick change could not be made; [onShare] sends the ticked files. */
@Composable
fun FolderSheets(
    sheet: FolderSheet?,
    vm: FolderViewModel,
    sortBy: SortBy,
    descending: Boolean,
    conflicts: Int,
    onSheet: (FolderSheet?) -> Unit,
    onStarted: (Boolean) -> Unit,
    onShare: () -> Unit,
    onFailed: () -> Unit,
) {
    val close = { onSheet(null) }
    when (sheet) {
        FolderSheet.SORT -> GroupSheet(stringResource(R.string.action_sort), close) { pick ->
            Group {
                SORTS.forEachIndexed { i, (by, label) ->
                    if (i > 0) GroupDivider()
                    CheckRow(stringResource(label), by == sortBy) { pick { vm.setSort(by, descending) } }
                }
            }
            Spacer(Modifier.height(12.dp))
            Group {
                CheckRow(stringResource(R.string.sort_ascending), !descending) { pick { vm.setSort(sortBy, false) } }
                GroupDivider()
                CheckRow(stringResource(R.string.sort_descending), descending) { pick { vm.setSort(sortBy, true) } }
            }
        }

        FolderSheet.NEW_FOLDER -> NameSheet(
            title = stringResource(R.string.action_new_folder),
            initial = "",
            confirm = stringResource(R.string.new_folder_create),
            taken = remember { vm.names() },
            onDismiss = close,
        ) { name -> vm.createFolder(name) { if (!it) onFailed() } }

        FolderSheet.RENAME -> vm.selected().singleOrNull()?.let { node ->
            NameSheet(
                title = stringResource(R.string.action_rename),
                initial = node.name,
                confirm = stringResource(R.string.action_rename),
                taken = remember { vm.names() },
                onDismiss = close,
                // Typing replaces the name and leaves a file its extension.
                selected = if (node.directory) node.name.length else node.name.length - Paths.extension(node.name).length.let { if (it > 0) it + 1 else 0 },
            ) { name -> vm.rename(node, name) { if (!it) onFailed() } }
        }

        FolderSheet.DELETE -> GroupSheet(stringResource(R.string.delete_title), close) { pick ->
            val binned = remember { vm.binned() }
            Note(if (binned) stringResource(R.string.delete_text, Trash.KEEP_DAYS) else stringResource(R.string.delete_for_good_text))
            Group {
                if (binned) {
                    GroupRow(stringResource(R.string.delete_to_bin), onClick = { pick { onStarted(vm.delete(forGood = false)) } })
                    GroupDivider()
                }
                GroupRow(stringResource(R.string.delete_for_good), titleColor = MntTheme.colors.danger, onClick = { pick { onStarted(vm.delete(forGood = true)) } })
            }
        }

        FolderSheet.MORE -> GroupSheet(stringResource(R.string.selected_count, vm.selected().size), close) { pick ->
            val chosen = remember { vm.selected() }
            Group {
                // A device that speaks MTP gives nothing another name.
                if (chosen.size == 1 && vm.renames()) {
                    GroupRow(stringResource(R.string.action_rename), onClick = { pick { onSheet(FolderSheet.RENAME) } })
                    GroupDivider()
                }
                // Only files can be handed to another app.
                if (chosen.none { it.directory }) {
                    GroupRow(stringResource(R.string.action_share), onClick = { pick(onShare) })
                    GroupDivider()
                }
                GroupRow(stringResource(R.string.action_zip), onClick = { pick { onSheet(FolderSheet.ZIP) } })
            }
        }

        FolderSheet.ZIP -> remember { vm.zipName() }.let { name ->
            NameSheet(
                title = stringResource(R.string.action_zip),
                initial = name,
                confirm = stringResource(R.string.backup_save_pick),
                taken = emptySet(),
                onDismiss = close,
                selected = name.length - Paths.extension(name).length - 1,
                asIs = true,
                onConfirm = vm::pickZip,
            )
        }

        FolderSheet.DETAILS -> DetailsSheet(vm.selected(), vm, close)

        FolderSheet.CONFLICT -> GroupSheet(stringResource(R.string.conflict_title), close) { pick ->
            Note(stringResource(R.string.conflict_text, conflicts))
            Group {
                GroupRow(stringResource(R.string.conflict_replace), onClick = { pick { onStarted(vm.paste(Conflict.REPLACE)) } })
                GroupDivider()
                GroupRow(stringResource(R.string.conflict_keep), onClick = { pick { onStarted(vm.paste(Conflict.KEEP_BOTH)) } })
                GroupDivider()
                GroupRow(stringResource(R.string.conflict_skip), onClick = { pick { onStarted(vm.paste(Conflict.SKIP)) } })
            }
        }

        null -> Unit
    }
}

/** A row of a single choice: the current one carries a check mark. */
@Composable
private fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) {
    val accent = MntTheme.colors.accent
    GroupRow(label, onClick = onClick, trailing = {
        if (checked) Icon(MntIcons.Check, null, tint = accent, modifier = Modifier.size(20.dp).inked(accent))
    })
}

/** The explanation at the top of a sheet that asks. */
@Composable
fun Note(text: String) {
    Text(text, color = MntTheme.colors.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
}

/** A sheet that takes a file name; it cannot be confirmed while the name is [taken], no name at all or, unless [asIs], the one it opened with. */
@Composable
private fun NameSheet(
    title: String,
    initial: String,
    confirm: String,
    taken: Set<String>,
    onDismiss: () -> Unit,
    selected: Int = 0,
    asIs: Boolean = false,
    onConfirm: (String) -> Unit,
) =
    GroupSheet(title, onDismiss) { pick ->
        var name by rememberSaveable { mutableStateOf(initial) }
        val typed = name.trim()
        // The phone's storage does not tell names apart by case: one that differs in case alone is taken too, unless it is this item's own.
        val clash = !typed.equals(initial, ignoreCase = true) && taken.any { it.equals(typed, ignoreCase = true) }
        Group { GroupField(stringResource(R.string.name_label), name, { name = it }, focused = true, selected = selected) }
        if (clash) {
            Text(
                stringResource(R.string.name_taken),
                color = MntTheme.colors.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Group {
            GroupRow(confirm, onClick = if (Paths.valid(typed) && (asIs || typed != initial) && !clash) ({ pick { onConfirm(typed) } }) else null)
        }
    }

/**
 * What the chosen items are: where, how large, how many; one alone also tells its type, its times,
 * its permissions and whose it is. Sizes of folders are counted as the sheet opens.
 */
@Composable
private fun DetailsSheet(nodes: List<Node>, vm: FolderViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details by produceState<Details?>(null, nodes) { value = vm.details(nodes) }
    val single = nodes.singleOrNull()
    val facts by produceState<Facts?>(null, single) { value = single?.let { vm.facts(it) } }
    fun time(millis: Long) = dateTime(context, millis)
    GroupSheet(stringResource(R.string.action_details), onDismiss) {
        Group {
            if (single != null) {
                GroupRow(stringResource(R.string.name_label), subtitle = single.name)
                GroupDivider()
            }
            GroupRow(stringResource(R.string.details_location), subtitle = vm.display(vm.path))
        }
        Spacer(Modifier.height(12.dp))
        Group {
            val type = single?.takeIf { !it.directory }?.let { Opener.mime(it.name) }
            if (type != null) {
                GroupRow(stringResource(R.string.sort_type), value = type)
                GroupDivider()
            }
            GroupRow(
                stringResource(R.string.sort_size),
                value = details?.let { Formatter.formatFileSize(context, it.bytes) } ?: stringResource(R.string.details_counting),
            )
            if (single?.directory != false) {
                GroupDivider()
                // A folder alone is told by what it holds, without itself.
                GroupRow(stringResource(R.string.details_items), value = details?.let { (it.items - if (single != null) 1 else 0).toString() } ?: "…")
            }
        }
        if (single == null) return@GroupSheet
        Spacer(Modifier.height(12.dp))
        Group {
            facts?.created?.let {
                GroupRow(stringResource(R.string.details_created), value = time(it))
                GroupDivider()
            }
            GroupRow(stringResource(R.string.details_modified), value = time(single.modified))
        }
        val rows = listOfNotNull(
            facts?.mode?.let { R.string.details_permissions to "${permissions(it)} · ${it.toString(8)}" },
            facts?.owner?.let { R.string.details_owner to it },
            facts?.group?.let { R.string.details_group to it },
        )
        if (rows.isEmpty()) return@GroupSheet
        Spacer(Modifier.height(12.dp))
        Group {
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) GroupDivider()
                GroupRow(stringResource(label), value = value)
            }
        }
    }
}

/** A day and a time of it, as a sheet of details tells them. */
fun dateTime(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
)

/** `rwxr-x---` for the nine permission bits. */
private fun permissions(mode: Int): String = "rwxrwxrwx".mapIndexed { i, letter -> if (mode and (0x100 shr i) != 0) letter else '-' }.joinToString("")

private val SORTS = listOf(
    SortBy.NAME to R.string.sort_name,
    SortBy.DATE to R.string.sort_date,
    SortBy.SIZE to R.string.sort_size,
    SortBy.TYPE to R.string.sort_type,
)
