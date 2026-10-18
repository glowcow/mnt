package dev.glowcow.mnt.ui.components

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.archive.Archives
import dev.glowcow.mnt.container
import dev.glowcow.mnt.files.Remote
import dev.glowcow.mnt.view.image.Readers
import dev.glowcow.mnt.files.Holds
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.ui.theme.FileIcons
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme

/** The picture of a file or a folder, as VS Code's explorer draws it, the variant for a light ground in a light theme. */
@Composable
fun nodeIcon(node: Node): ImageVector {
    val light = !MntTheme.colors.isDark
    return remember(node.name, node.directory, light) { if (node.directory) FileIcons.folder(node.name, light) else FileIcons.file(node.name, light) }
}

/**
 * How large a file is, or what a folder [holds] once that is counted, then when it was changed:
 * `1.2 MB | 5 Oct 2026`, `12 files, 3 folders | 5 Oct 2026`.
 */
@Composable
fun nodeMeta(node: Node, holds: Holds? = null): String {
    val context = LocalContext.current
    val date = DateUtils.formatDateTime(context, node.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
    val first = when {
        // A place that keeps no time for a folder says nothing of it.
        !node.directory -> Formatter.formatShortFileSize(context, node.size).also { if (node.modified <= 0) return it }
        holds == null -> return if (node.modified > 0) date else ""
        holds.files == 0 && holds.folders == 0 -> stringResource(R.string.folder_holds_nothing)
        else -> listOfNotNull(
            holds.files.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_files, it, it) },
            holds.folders.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_folders, it, it) },
        ).joinToString(", ")
    }
    return "$first · $date"
}

/**
 * A file or a folder in a list on a plain page, with the picture of its kind in that picture's own
 * colours. A [selected] one carries a check mark where the picture was.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodeRow(node: Node, subtitle: String, selected: Boolean = false, thumbnail: Bitmap? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = MntTheme.colors
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(
            // A hidden file is there but faint, the whole row of it.
            Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(if (node.hidden && !selected) HIDDEN else 1f).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Box(Modifier.size(TICK).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(MntIcons.Check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                    }
                } else if (thumbnail != null) {
                    val image = remember(thumbnail) { thumbnail.asImageBitmap() }
                    Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                } else {
                    Icon(nodeIcon(node), null, tint = Color.Unspecified, modifier = Modifier.size(36.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(node.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                // With nothing to say under it the name stands alone, in the middle of the row.
                if (subtitle.isNotEmpty()) Text(subtitle, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
        }
        // The line starts where the text does and ends where the page's margin begins.
        Box(Modifier.padding(start = 68.dp, end = 16.dp).fillMaxWidth().height(1.dp).background(c.line))
    }
}

private const val HIDDEN = 0.45f

// The mark of a ticked row: a disc smaller than the picture it stands in for.
private val TICK = 28.dp

/**
 * The thumbnail of [node] once it is made, when [settings] want thumbnails and it is a picture: on the phone and its
 * drives, on servers and devices too when [far] allows; never inside an archive, where each one is a read through it.
 */
@Composable
fun rememberThumbnail(node: Node, on: Boolean, far: Boolean): Bitmap? {
    if (!on || node.directory || !Readers.shows(node.name) || Archives.of(node.path) != null) return null
    if (Remote.id(node.path) != null && !far) return null
    val thumbnails = LocalContext.current.container.thumbnails
    val size = with(LocalDensity.current) { 40.dp.roundToPx() }
    var bitmap by remember(node.path, node.size, node.modified) { mutableStateOf(thumbnails.cached(node)) }
    if (bitmap == null) LaunchedEffect(node.path, node.size, node.modified) { bitmap = thumbnails.of(node, size) }
    return bitmap
}
