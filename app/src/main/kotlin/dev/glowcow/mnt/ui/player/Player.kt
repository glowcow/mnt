package dev.glowcow.mnt.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import dev.glowcow.mnt.R
import dev.glowcow.mnt.audio.Lyrics
import dev.glowcow.mnt.audio.Playback
import dev.glowcow.mnt.audio.Sounds
import dev.glowcow.mnt.audio.Tag
import dev.glowcow.mnt.audio.Waves
import dev.glowcow.mnt.container
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.ui.components.IconButton48
import dev.glowcow.mnt.ui.components.RunningText
import dev.glowcow.mnt.ui.components.solid
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.ui.theme.inked
import kotlinx.coroutines.delay
import java.util.Locale

/** The title a track goes by: its tag, else its file name. */
internal fun title(meta: MediaMetadata, id: String?): String = meta.title?.toString() ?: meta.displayTitle?.toString() ?: id?.let(Sounds::stem).orEmpty()

internal fun artist(meta: MediaMetadata): String? = (meta.artist ?: meta.albumArtist)?.toString()

/** What plays as the player knows it, filled from the file's own tags while it has not read them: a queue put back after a restart. */
@Composable
internal fun shownMetadata(playback: Playback): MediaMetadata {
    val meta = playback.metadata
    val id = playback.current?.mediaId
    if (meta.title != null || id == null) return meta
    val tag by produceState(playback.tags.known(id), id) { value = playback.tags.read(id) }
    val known = tag ?: return meta
    return meta.buildUpon().setTitle(known.title).setArtist(known.artist).setAlbumTitle(known.album).build()
}

/**
 * The mini player over the bottom of a page: play or pause, what plays, and the cross that empties the queue.
 * A tap opens the full player; a drag up pulls it out, [onDrag] by pixels and [onDragStop] at the speed it was let go.
 */
@Composable
fun MiniPlayer(playback: Playback, onOpen: () -> Unit, onDrag: (Float) -> Unit, onDragStop: (Float) -> Unit) {
    val c = MntTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .draggable(rememberDraggableState(onDrag), Orientation.Vertical, onDragStopped = { onDragStop(it) })
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayPause(playback, size = 24.dp)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            val meta = shownMetadata(playback)
            Text(title(meta, playback.current?.mediaId), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            artist(meta)?.let { Text(it, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        IconButton48(MntIcons.Close, stringResource(R.string.player_close), tint = c.muted, size = 24.dp) { playback.close() }
    }
}

@Composable
private fun PlayPause(playback: Playback, size: Dp) {
    IconButton48(
        if (playback.playing) MntIcons.Pause else MntIcons.Play,
        stringResource(if (playback.playing) R.string.player_pause else R.string.player_play),
        size = size,
    ) { playback.toggle() }
}

/** A sheet the full player has open. */
internal enum class PlayerSheet { QUEUE, DETAILS, CAST }

/**
 * The player over the whole screen, after Symfonium's: the cover, what plays, the wave of the track to go along,
 * the controls, and a row of what more there is — about the track, the queue, its words, where it plays. It follows
 * a drag down ([onDrag], [onDragStop]); back takes it down too.
 */
@Composable
fun FullPlayer(playback: Playback, onDrag: (Float) -> Unit, onDragStop: (Float) -> Unit) {
    val c = MntTheme.colors
    var sheet by rememberSaveable { mutableStateOf<PlayerSheet?>(null) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.groupBg)
            .solid()
            .draggable(rememberDraggableState(onDrag), Orientation.Vertical, onDragStopped = { onDragStop(it) })
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val top: @Composable () -> Unit = { if (playback.wordsShown) Words(playback) else Cover(playback) }
        val actions: @Composable (Modifier) -> Unit = { modifier ->
            Actions(playback, playback.wordsShown, modifier, onLyrics = { playback.wordsShown = !playback.wordsShown }) { sheet = it }
        }
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)) { top() }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Titles(playback)
                    Middle(playback)
                    actions(Modifier)
                }
            }
        } else {
            // The cover is as wide as the screen while there is room; what is left goes between the parts below it.
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f, fill = false).padding(top = 16.dp)) {
                    Box(Modifier.weight(1f, fill = false).aspectRatio(1f).align(Alignment.CenterHorizontally)) { top() }
                    Titles(playback, Modifier.padding(top = 20.dp))
                }
                Middle(playback, Modifier.padding(top = 12.dp, bottom = 8.dp))
                actions(Modifier.padding(bottom = 8.dp))
            }
        }
    }
    when (sheet) {
        PlayerSheet.QUEUE -> QueueSheet(playback) { sheet = null }
        PlayerSheet.DETAILS -> DetailsSheet(playback) { sheet = null }
        PlayerSheet.CAST -> CastSheet { sheet = null }
        null -> Unit
    }
}

@Composable
private fun Cover(playback: Playback) {
    val c = MntTheme.colors
    val meta = playback.metadata
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val px = with(density) { side.roundToPx() }.coerceAtMost(COVER_PX)
        val art = meta.artworkUri?.takeIf { it.scheme == Sounds.SCHEME }?.let(Sounds::path)
        val cover by produceState(playback.tags.peek(meta.artworkData, art), meta.artworkData, art, px) {
            value = playback.tags.cover(meta.artworkData, art, px)
        }
        Box(
            Modifier.size(side).shadow(12.dp, COVER, ambientColor = SHADE, spotColor = SHADE).clip(COVER).background(c.group),
            contentAlignment = Alignment.Center,
        ) {
            val shown = cover
            if (shown != null) {
                Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(MntIcons.MusicNotes, null, tint = c.muted, modifier = Modifier.size(side / 4))
            }
        }
    }
}

/** The words of the track where the cover was: the line sung now stands out and is kept in sight; a tap on a line goes there. */
@Composable
private fun Words(playback: Playback) {
    val c = MntTheme.colors
    val id = playback.current?.mediaId
    val lyrics by produceState<Lyrics?>(null, id, playback.heardWords) { value = id?.let { playback.lyrics(it) } }
    val now by rememberPosition(playback)
    Box(Modifier.fillMaxSize().clip(COVER).background(c.group), contentAlignment = Alignment.Center) {
        val words = lyrics
        if (words == null) {
            Text(stringResource(R.string.player_no_lyrics), color = c.muted, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            return@Box
        }
        val list = rememberLazyListState()
        val sung = if (words.timed) words.lineAt(now) else -1
        LaunchedEffect(sung) { if (sung >= 0 && !list.isScrollInProgress) list.animateScrollToItem((sung - 2).coerceAtLeast(0)) }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(if (words.timed) 10.dp else 2.dp)) {
            itemsIndexed(words.lines) { i, line ->
                val at = line.at
                Text(
                    line.text.ifEmpty { "♪" },
                    color = if (!words.timed || i == sung) c.text else c.muted,
                    fontSize = if (words.timed) 19.sp else 16.sp,
                    fontWeight = if (i == sung) FontWeight.Bold else FontWeight.Normal,
                    modifier = if (at != null) Modifier.fillMaxWidth().clickable { playback.seek(at) } else Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Titles(playback: Playback, modifier: Modifier = Modifier) {
    val c = MntTheme.colors
    val meta = shownMetadata(playback)
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        ProvideTextStyle(TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold)) {
            RunningText(title(meta, playback.current?.mediaId), c.text, Modifier.fillMaxWidth())
        }
        artist(meta)?.let {
            Text(it, color = c.accent, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).inked(c.accent))
        }
        meta.albumTitle?.let { Text(it.toString(), color = c.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** Where the track is now, milliseconds, asked of the player four times a second while shown. */
@Composable
private fun rememberPosition(playback: Playback): androidx.compose.runtime.State<Long> = produceState(playback.position, playback) {
    while (true) {
        value = playback.position
        delay(TICK_MS)
    }
}

/**
 * The wave of the track, played part in the accent: a tap or a drag along it goes there. Under it the time gone,
 * how the track is coded — or the renderer it plays on — and the time left.
 */
@Composable
private fun Seek(playback: Playback, modifier: Modifier = Modifier) {
    val c = MntTheme.colors
    val now by rememberPosition(playback)
    var held by remember { mutableFloatStateOf(-1f) }
    val id = playback.current?.mediaId
    val wave by produceState(id?.let(playback.waves::known), id) { value = id?.let { playback.waves.of(it) } }
    val tag by produceState(id?.let(playback.tags::known), id) { value = id?.let { playback.tags.read(it) } }
    val length = playback.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: tag?.duration ?: 0L
    val fraction = if (held >= 0f) held else if (length > 0) (now.toFloat() / length).coerceIn(0f, 1f) else 0f
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(WAVE)
                .pointerInput(length) { detectTapGestures { if (length > 0) playback.seek((it.x / size.width * length).toLong()) } }
                .pointerInput(length) {
                    detectHorizontalDragGestures(
                        onDragStart = { held = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            if (length > 0) playback.seek((held * length).toLong())
                            held = -1f
                        },
                        onDragCancel = { held = -1f },
                    ) { change, _ -> held = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
        ) {
            val bar = BAR.toPx()
            val step = bar + GAP.toPx()
            val count = ((size.width + GAP.toPx()) / step).toInt().coerceAtLeast(1)
            val start = (size.width - (count * step - GAP.toPx())) / 2
            val base = size.height * BASE
            val gap = 2.dp.toPx()
            for (i in 0 until count) {
                val h = (level(wave, i, count) * base).coerceAtLeast(bar)
                val played = (i + 0.5f) / count <= fraction
                val colour = if (played) c.accent else c.accent.copy(alpha = REST)
                val x = start + i * step
                drawRoundRect(colour, Offset(x, base - h), Size(bar, h), CornerRadius(bar / 2))
                // A faint reflection under the line, as of a wave on water.
                val below = (h * MIRROR).coerceAtMost(size.height - base - gap)
                if (below > 0f) drawRoundRect(colour.copy(alpha = colour.alpha * MIRROR_ALPHA), Offset(x, base + gap), Size(bar, below), CornerRadius(bar / 2))
            }
        }
        Box(Modifier.fillMaxWidth()) {
            val style = TextStyle(fontFeatureSettings = "tnum")
            val at = (fraction * length).toLong()
            Text(clock(at), color = c.muted, fontSize = 13.sp, style = style, modifier = Modifier.align(Alignment.CenterStart))
            Text(if (length > 0) "-" + clock(length - at) else "", color = c.muted, fontSize = 13.sp, style = style, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

// How loud the wave is at bar [i] of [count]: the loudest of its bins, a low line while the wave is not known.
private fun level(wave: FloatArray?, i: Int, count: Int): Float {
    if (wave == null) return QUIET
    val from = i * Waves.BINS / count
    val to = ((i + 1) * Waves.BINS / count).coerceAtLeast(from + 1).coerceAtMost(Waves.BINS)
    var top = 0f
    for (b in from until to) top = maxOf(top, wave[b])
    return top.coerceAtLeast(QUIET)
}

/**
 * The wave with its times, the controls under it, and between them, half way from the foot of the wave to the top of
 * the play button, the pill of how the track is coded or where it plays.
 */
@Composable
private fun Middle(playback: Playback, modifier: Modifier = Modifier) {
    Layout(
        content = {
            Seek(playback)
            Transport(playback)
            Badge(playback)
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val seek = measurables[0].measure(loose.copy(minWidth = constraints.maxWidth))
        val transport = measurables[1].measure(loose.copy(minWidth = constraints.maxWidth))
        val badge = measurables.getOrNull(2)?.measure(loose)
        val gap = MIDDLE_GAP.roundToPx()
        val play = seek.height + gap + (transport.height - PLAY.roundToPx()) / 2
        layout(constraints.maxWidth, seek.height + gap + transport.height) {
            seek.place(0, 0)
            transport.place(0, seek.height + gap)
            badge?.place((constraints.maxWidth - badge.width) / 2, (WAVE.roundToPx() + play) / 2 - badge.height / 2)
        }
    }
}

/** A pill: the renderer the queue plays on, else how the track is coded. */
@Composable
private fun Badge(playback: Playback) {
    val c = MntTheme.colors
    val device = LocalContext.current.container.casting.target?.name
    val id = playback.current?.mediaId
    val tag by produceState(id?.let(playback.tags::known), id) { value = id?.let { playback.tags.read(it) } }
    val text = device ?: coding(tag, id) ?: return
    Row(
        Modifier.clip(CircleShape).background(c.group).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (device != null) Icon(MntIcons.CastFilled, null, tint = c.accent, modifier = Modifier.size(14.dp).inked(c.accent))
        Text(text, color = if (device != null) c.accent else c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (device != null) Modifier.widthIn(max = 180.dp).inked(c.accent) else Modifier)
    }
}

/** `FLAC · 24 bit · 96 kHz` for what keeps every bit, `MP3 · 320 kbps` for what does not. */
@Composable
internal fun coding(tag: Tag?, id: String?): String? {
    id ?: return null
    if (Sounds.web(id)) return null
    val ext = Paths.extension(Paths.name(id)).uppercase()
    val bits = tag?.bits
    val rate = tag?.rate
    val name = when (ext) {
        "M4A", "M4B" -> if (bits != null) "ALAC" else "AAC"
        "OGA" -> "OGG"
        "WEBA" -> "WEBM"
        else -> ext
    }
    return when {
        bits != null && rate != null -> "$name · " + stringResource(R.string.player_bits, bits) + " · " + stringResource(R.string.player_khz, khz(rate))
        tag?.bitrate != null -> "$name · " + stringResource(R.string.player_kbps, tag.bitrate / 1000)
        else -> name
    }
}

internal fun khz(rate: Int): String = if (rate % 1000 == 0) (rate / 1000).toString() else String.format(Locale.ROOT, "%.1f", rate / 1000f)

@Composable
private fun Transport(playback: Playback, modifier: Modifier = Modifier) {
    val c = MntTheme.colors
    val repeat = playback.repeat
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton48(
            if (repeat == Player.REPEAT_MODE_ONE) MntIcons.RepeatOnce else MntIcons.Repeat,
            stringResource(
                when (repeat) {
                    Player.REPEAT_MODE_ALL -> R.string.player_repeat_all
                    Player.REPEAT_MODE_ONE -> R.string.player_repeat_one
                    else -> R.string.player_repeat_off
                },
            ),
            tint = if (repeat != Player.REPEAT_MODE_OFF) c.accent else c.muted,
        ) { playback.switchRepeat() }
        IconButton48(MntIcons.SkipBack, stringResource(R.string.player_previous), size = 30.dp) { playback.previous() }
        Box(
            Modifier.size(PLAY).clip(CircleShape).background(c.accent).clickable(role = Role.Button) { playback.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playback.playing) MntIcons.Pause else MntIcons.Play,
                stringResource(if (playback.playing) R.string.player_pause else R.string.player_play),
                tint = c.onAccent,
                modifier = Modifier.size(32.dp),
            )
            if (playback.loading) CircularProgressIndicator(Modifier.size(PLAY), color = c.onAccent.copy(alpha = 0.5f), strokeWidth = 2.dp)
        }
        IconButton48(MntIcons.SkipForward, stringResource(R.string.player_next), size = 30.dp) { playback.next() }
        IconButton48(MntIcons.Shuffle, stringResource(R.string.player_shuffle), tint = if (playback.shuffle) c.accent else c.muted) { playback.switchShuffle() }
    }
}

/** The row at the bottom: about the track, the queue, its words, where it plays. */
@Composable
private fun Actions(playback: Playback, lyrics: Boolean, modifier: Modifier, onLyrics: () -> Unit, onSheet: (PlayerSheet) -> Unit) {
    val c = MntTheme.colors
    val casting = LocalContext.current.container.casting
    val cast = casting.target != null
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton48(MntIcons.Info, stringResource(R.string.player_details), size = 24.dp) { onSheet(PlayerSheet.DETAILS) }
        IconButton48(MntIcons.Queue, stringResource(R.string.player_queue), size = 24.dp) { onSheet(PlayerSheet.QUEUE) }
        IconButton48(if (lyrics) MntIcons.LyricsFilled else MntIcons.Lyrics, stringResource(R.string.player_lyrics), tint = if (lyrics) c.accent else c.text, size = 24.dp, onClick = onLyrics)
        IconButton48(if (cast) MntIcons.CastFilled else MntIcons.Cast, stringResource(R.string.player_cast), tint = if (cast) c.accent else c.text, size = 24.dp) { onSheet(PlayerSheet.CAST) }
    }
}

/** Minutes and seconds, hours when there are any. */
internal fun clock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

private val COVER = RoundedCornerShape(16.dp)
private val SHADE = Color.Black.copy(alpha = 0.35f)
private const val COVER_PX = 1536
private const val TICK_MS = 250L

// The wave's height, the play button's, and the room between the times under the wave and the controls.
private val WAVE = 56.dp
private val PLAY = 76.dp
private val MIDDLE_GAP = 56.dp

// The bars of the wave: how wide, how far apart, where their line runs, and the part not played yet.
private val BAR = 3.dp
private val GAP = 2.dp
private const val BASE = 0.74f
private const val REST = 0.3f
private const val QUIET = 0.06f
private const val MIRROR = 0.35f
private const val MIRROR_ALPHA = 0.25f
