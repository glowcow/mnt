package dev.glowcow.mnt.ui.player

import android.content.pm.ActivityInfo
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.C
import dev.glowcow.mnt.R
import dev.glowcow.mnt.audio.Playback
import dev.glowcow.mnt.audio.Sounds
import dev.glowcow.mnt.container
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.components.rememberLocalNetwork
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.ui.theme.inked

/** The queue in the order it plays, the track playing in the accent and in sight; a tap plays a track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(playback: Playback, onDismiss: () -> Unit) {
    val c = MntTheme.colors
    val order = playback.order
    val place = order.indexOf(playback.index)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.groupBg) {
        // A sheet is a window of its own, and a window shows Display P3 only when asked to.
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT }
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_queue), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
                if (place >= 0) Text(stringResource(R.string.viewer_part, place + 1, playback.tracks.size), fontSize = 14.sp, color = c.muted)
            }
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (place - 2).coerceAtLeast(0))
            val tall = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.7f
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = tall).clip(RoundedCornerShape(20.dp)).background(c.group),
                state = list,
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(order, key = { _, at -> at }) { _, at ->
                    val item = playback.tracks.getOrNull(at) ?: return@itemsIndexed
                    val now = at == playback.index
                    val tag by produceState(playback.tags.known(item.mediaId), item.mediaId) { value = playback.tags.read(item.mediaId) }
                    val name = if (now) title(playback.metadata, item.mediaId) else tag?.title ?: Sounds.stem(item.mediaId)
                    val by = if (now) artist(playback.metadata) else tag?.artist
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable { playback.jump(at) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, color = if (now) c.accent else c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (now) Modifier.inked(c.accent) else Modifier)
                            by?.let { Text(it, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        (playback.duration.takeIf { now && it > 0 } ?: tag?.duration)?.let { Text(clock(it), color = c.muted, fontSize = 13.sp, style = TextStyle(fontFeatureSettings = "tnum")) }
                    }
                }
            }
        }
    }
}

/** What is known of the track playing: its tags, how it is coded, the file. */
@Composable
internal fun DetailsSheet(playback: Playback, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val id = playback.current?.mediaId ?: return onDismiss()
    val meta = playback.metadata
    val tag by produceState(playback.tags.known(id), id) { value = playback.tags.read(id) }
    val node by produceState(null as dev.glowcow.mnt.files.Node?, id) { value = if (Sounds.web(id)) null else context.container.files.stat(id) }
    val length = playback.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: tag?.duration
    val rows = listOfNotNull(
        R.string.player_title to title(meta, id),
        artist(meta)?.let { R.string.player_artist to it },
        meta.albumTitle?.let { R.string.player_album to it.toString() },
        coding(tag, id)?.substringBefore(" · ")?.let { R.string.photo_format to it },
        tag?.bitrate?.let { R.string.player_bitrate to stringResource(R.string.player_kbps, it / 1000) },
        tag?.rate?.let { R.string.player_sample_rate to stringResource(R.string.player_khz, khz(it)) },
        tag?.bits?.let { R.string.player_bit_depth to stringResource(R.string.player_bits, it) },
        length?.let { R.string.player_duration to clock(it) },
        node?.let { R.string.photo_file_size to Formatter.formatFileSize(context, it.size) },
        node?.let { R.string.details_modified to DateUtils.formatDateTime(context, it.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME) },
    )
    GroupSheet(stringResource(R.string.player_details), onDismiss) {
        Group {
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) GroupDivider()
                GroupRow(stringResource(label), value = value)
            }
        }
        Group(Modifier.padding(top = 12.dp)) {
            GroupRow(stringResource(R.string.details_location), subtitle = if (Sounds.web(id)) id else context.container.roots.display(Paths.parent(id)))
        }
    }
}

/**
 * Where the queue plays, after Symfonium's *Play on*: a card for the phone and one for each speaker of the network
 * found as the sheet opens, its picture, its name with what it is, and who made it; the one playing is tinted.
 */
@Composable
internal fun CastSheet(onDismiss: () -> Unit) {
    val c = MntTheme.colors
    val context = LocalContext.current
    val casting = context.container.casting
    val network = rememberLocalNetwork()
    LaunchedEffect(Unit) { network(casting::search) }
    GroupSheet(stringResource(R.string.player_cast), onDismiss) { pick ->
        val target = casting.target
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val phone = remember { phoneName(context) }
            Place(stringResource(R.string.player_this_phone), phone, chosen = target == null, onClick = { pick { casting.use(null) } }) {
                Icon(MntIcons.Phone, null, tint = c.text, modifier = Modifier.size(24.dp))
            }
            for (renderer in casting.renderers) {
                Place(renderer.title, renderer.maker, chosen = target?.id == renderer.id, onClick = { pick { casting.use(renderer) } }) {
                    val picture by produceState(casting.knownPicture(renderer), renderer.icon) { value = casting.picture(renderer) }
                    val shown = picture
                    if (shown != null) {
                        Image(shown, null, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                    } else {
                        Icon(MntIcons.Speaker, null, tint = c.text, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        val note = when {
            casting.searching -> R.string.player_looking
            casting.renderers.isEmpty() -> R.string.player_none_found
            else -> null
        }
        note?.let { Text(stringResource(it), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 10.dp)) }
    }
}

// A card of a place to play on: a 40 dp picture, the name, who made it; the one playing on the accent's tint, with the check.
@Composable
private fun Place(title: String, subtitle: String?, chosen: Boolean, onClick: () -> Unit, picture: @Composable () -> Unit) {
    val c = MntTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (chosen) c.accent.copy(alpha = CHOSEN).compositeOver(c.group) else c.group)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { picture() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (chosen) Check()
    }
}

private const val CHOSEN = 0.16f

@Composable
private fun Check() {
    val c = MntTheme.colors
    Icon(MntIcons.Check, null, tint = c.accent, modifier = Modifier.size(20.dp).inked(c.accent))
}

/** What the phone is called: the name its owner gave it, else its model. */
internal fun phoneName(context: Context): String = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
