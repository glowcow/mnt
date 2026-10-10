package dev.glowcow.mnt.ui.files

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
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.SortBy
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupField
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme

/** The sheets of a folder's page. [onStarted] hears whether a transfer could begin, [onFailed] that a quick change could not be made. */
@Composable
fun FolderSheets(
    sheet: FolderSheet?,
    vm: FolderViewModel,
    sortBy: SortBy,
    descending: Boolean,
    conflicts: Int,
    onSheet: (FolderSheet?) -> Unit,
    onStarted: (Boolean) -> Unit,
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

        // There is no bin to take things back from, so a delete asks first.
        FolderSheet.DELETE -> GroupSheet(stringResource(R.string.delete_title), close) { pick ->
            Note(stringResource(R.string.delete_text))
            Group { GroupRow(stringResource(R.string.action_delete), onClick = { pick { onStarted(vm.delete()) } }) }
        }

        FolderSheet.MORE -> GroupSheet(stringResource(R.string.selected_count, vm.selected().size), close) { pick ->
            Group {
                if (vm.selected().size == 1) {
                    GroupRow(stringResource(R.string.action_rename), onClick = { pick { onSheet(FolderSheet.RENAME) } })
                    GroupDivider()
                }
                GroupRow(stringResource(R.string.action_details), onClick = { pick { onSheet(FolderSheet.DETAILS) } })
            }
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
        if (checked) Icon(MntIcons.Check, null, tint = accent, modifier = Modifier.size(20.dp))
    })
}

/** The explanation at the top of a sheet that asks. */
@Composable
private fun Note(text: String) {
    Text(text, color = MntTheme.colors.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
}

/** A sheet that takes a file name; it cannot be confirmed while the name is [taken] or no name at all. */
@Composable
private fun NameSheet(
    title: String,
    initial: String,
    confirm: String,
    taken: Set<String>,
    onDismiss: () -> Unit,
    selected: Int = 0,
    onConfirm: (String) -> Unit,
) =
    GroupSheet(title, onDismiss) { pick ->
        var name by rememberSaveable { mutableStateOf(initial) }
        val typed = name.trim()
        val clash = typed != initial && typed in taken
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
            GroupRow(confirm, onClick = if (Paths.valid(typed) && typed != initial && !clash) ({ pick { onConfirm(typed) } }) else null)
        }
    }

/** What the chosen items are: where, how large, how many, when changed. Sizes of folders are counted as the sheet opens. */
@Composable
private fun DetailsSheet(nodes: List<Node>, vm: FolderViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details by produceState<Details?>(null, nodes) { value = vm.details(nodes) }
    val single = nodes.singleOrNull()
    GroupSheet(stringResource(R.string.action_details), onDismiss) {
        Group {
            if (single != null) {
                GroupRow(stringResource(R.string.name_label), subtitle = single.name)
                GroupDivider()
            }
            GroupRow(stringResource(R.string.details_location), subtitle = vm.display(vm.path))
            GroupDivider()
            GroupRow(
                stringResource(R.string.sort_size),
                value = details?.let { Formatter.formatFileSize(context, it.bytes) } ?: stringResource(R.string.details_counting),
            )
            if (single?.directory != false) {
                GroupDivider()
                // A folder alone is told by what it holds, without itself.
                GroupRow(stringResource(R.string.details_items), value = details?.let { (it.items - if (single != null) 1 else 0).toString() } ?: "…")
            }
            if (single != null) {
                GroupDivider()
                GroupRow(
                    stringResource(R.string.details_modified),
                    value = DateUtils.formatDateTime(
                        context,
                        single.modified,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
                    ),
                )
            }
        }
    }
}

private val SORTS = listOf(
    SortBy.NAME to R.string.sort_name,
    SortBy.DATE to R.string.sort_date,
    SortBy.SIZE to R.string.sort_size,
    SortBy.TYPE to R.string.sort_type,
)
