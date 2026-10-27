package dev.glowcow.mnt.ui.video

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.util.Rational
import android.view.Surface
import android.view.SurfaceHolder
import android.view.HapticFeedbackConstants
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import dev.glowcow.mnt.R
import dev.glowcow.mnt.container
import dev.glowcow.mnt.data.FrameRate
import dev.glowcow.mnt.data.Turn
import dev.glowcow.mnt.data.VideoSettings
import dev.glowcow.mnt.files.Opener
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.ui.components.ChoiceSheet
import dev.glowcow.mnt.ui.components.EmptyState
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.components.IconButton48
import dev.glowcow.mnt.ui.components.LocalPageDark
import dev.glowcow.mnt.ui.components.PillButton
import dev.glowcow.mnt.ui.components.pageMotion
import dev.glowcow.mnt.ui.player.clock
import dev.glowcow.mnt.ui.theme.MntColorsProvider
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.ui.theme.colorsOf
import dev.glowcow.mnt.video.Chapter
import dev.glowcow.mnt.video.Videos
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class VideoSheet { SUBTITLES, AUDIO, SETTINGS, SPEED, FIT, INFO, CHAPTERS, DELAY }

/** What a drag shows while it goes: a level of the screen or the sound, or where a seek goes. */
private data class Readout(val icon: ImageVector? = null, val level: Float? = null, val text: String? = null)

/** Double taps at one side, added up: [by] milliseconds back or on. */
private data class Skipped(val forward: Boolean, val by: Long, val count: Int)

/**
 * A video of a folder on a black page, the others before and after it: the controls over the picture, as in a
 * film player, come and go with a tap; double taps skip, drags set the brightness, the volume or the place.
 */
@Composable
fun VideoScreen(
    path: String,
    onBack: () -> Unit,
    vm: VideoViewModel = viewModel(key = path) { VideoViewModel(path, this[APPLICATION_KEY]!!) },
) {
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    val bars = LocalPageDark.current
    DisposableEffect(Unit) {
        bars.value = true
        onDispose { bars.value = null }
    }
    // The dark colours of the scheme in use, on black.
    MntColorsProvider(colorsOf(settings.palette, true, LocalConfiguration.current.isScreenWideColorGamut)) {
        Video(vm, settings.video, onBack)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Video(vm: VideoViewModel, video: VideoSettings, onBack: () -> Unit) {
    val c = MntTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val activity = context as? ComponentActivity
    var shown by rememberSaveable { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf<VideoSheet?>(null) }
    var readout by remember { mutableStateOf<Readout?>(null) }
    var skipped by remember { mutableStateOf<Skipped?>(null) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var videoRect by remember { mutableStateOf<android.graphics.Rect?>(null) }

    // The controls go by themselves a while after the last touch, while the video plays.
    LaunchedEffect(shown, vm.playing, touched, sheet) {
        if (shown && vm.playing && sheet == null) {
            delay(HIDE_MS)
            shown = false
        }
    }
    LaunchedEffect(vm.begun) { if (vm.begun > 0) shown = true }
    LaunchedEffect(readout) { if (readout != null) delay(READOUT_MS).also { readout = null } }
    LaunchedEffect(skipped) { if (skipped != null) delay(READOUT_MS).also { skipped = null } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.pause()
        vm.hide()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.show() }

    // The system's bars stay away, the status bar but with the controls if the settings say so; a swipe from the
    // edge brings them back for a moment.
    DisposableEffect(activity) {
        val insets = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { insets?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(vm.playing) {
        view.keepScreenOn = vm.playing
        onDispose { view.keepScreenOn = false }
    }

    val picture = vm.size
    val aspect = if (picture.width > 0 && picture.height > 0) picture.width * picture.pixelWidthHeightRatio / picture.height else 0f
    // The screen turns to the video's shape at once, or is left to the phone; turned by hand, it stays so for this video.
    val wide = aspect >= 1f
    var turned by remember(vm.current) { mutableStateOf<Boolean?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(activity, video.turn, aspect > 0f, wide, turned) {
        (turned ?: wide.takeIf { video.turn == Turn.VIDEO && aspect > 0f })?.let { across ->
            activity?.requestedOrientation = if (across) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // Leaving the app while it plays shrinks it to a window of its shape.
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            activity?.removeOnPictureInPictureModeChangedListener(listener)
            runCatching { activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
        }
    }
    val statusShown = video.statusBar && shown && !inPip && !vm.failed
    LaunchedEffect(activity, statusShown) {
        val insets = activity?.window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        if (statusShown) insets.show(WindowInsetsCompat.Type.statusBars()) else insets.hide(WindowInsetsCompat.Type.statusBars())
    }
    val pipParams = remember(aspect, videoRect, video.pip, vm.playing) {
        val ratio = if (aspect > 0f) aspect.coerceIn(PIP_MIN, PIP_MAX) else 16f / 9f
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000))
            .setSourceRectHint(videoRect)
            .setAutoEnterEnabled(video.pip && vm.playing)
            .setSeamlessResizeEnabled(false)
            .build()
    }
    LaunchedEffect(activity, pipParams) { runCatching { activity?.setPictureInPictureParams(pipParams) } }

    // The brightness of this window, as the eye sees it: the screen's own is the square of it.
    var light by remember { mutableFloatStateOf(-1f) }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
    fun setLight(to: Float) {
        light = to.coerceIn(0f, 1f)
        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = (light * light).coerceAtLeast(MIN_BRIGHTNESS) } }
    }
    fun lightNow(): Float = light.takeIf { it >= 0f }
        ?: sqrt(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(0f, 1f)
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    // The volume keys show the page's own scale; the system's panel stays away.
    DisposableEffect(context) {
        val container = context.container
        container.volumeKeys = { step ->
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (step > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            readout = Readout(MntIcons.Volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat())
        }
        onDispose { container.volumeKeys = null }
    }

    val frame = frame(box, aspect, vm.fit)
    // Grown by two fingers over the picture's own size, and moved while grown; back whole for another video or fit.
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(vm.begun, vm.fit) {
        zoom = 1f
        pan = Offset.Zero
    }
    val grown = IntSize((frame.width * zoom).roundToInt(), (frame.height * zoom).roundToInt())
    // The zoom at which the picture covers the screen, the other stop besides its own size.
    val cover = if (frame.width > 0 && frame.height > 0) maxOf(box.width / frame.width.toFloat(), box.height / frame.height.toFloat()) else 1f
    val stops = rememberUpdatedState(listOfNotNull(1f, cover.takeIf { it > 1f + STICK && it < MAX_ZOOM }))
    val boxNow = rememberUpdatedState(box)
    val frameNow = rememberUpdatedState(frame)
    fun held(at: Offset, by: Float): Offset {
        val b = boxNow.value
        val f = frameNow.value
        val x = ((f.width * by - b.width) / 2f).coerceAtLeast(0f)
        val y = ((f.height * by - b.height) / 2f).coerceAtLeast(0f)
        return Offset(at.x.coerceIn(-x, x), at.y.coerceIn(-y, y))
    }
    Box(Modifier.fillMaxSize().pageMotion().background(Color.Black).onSizeChanged { box = it }) {
        Surface(vm, frame, grown, pan, vm.fit == Fit.STRETCH, video.frameRate, vm.format?.frameRate) { videoRect = it }
        // Taps, double taps, drags and pinches on the picture.
        val brightness = rememberUpdatedState(video.swipeBrightness)
        val volume = rememberUpdatedState(video.swipeVolume)
        val seeking = rememberUpdatedState(video.swipeSeek)
        val skipMs = video.skipSeconds * 1000L
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(inPip) {
                    if (inPip) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 0 not yet known, then a pinch, a seek, the brightness or the volume; -1 nothing.
                        var mode = 0
                        var moved = Offset.Zero
                        var spread = 0f
                        var startZoom = 1f
                        var last = Offset.Zero
                        var startLevel = 0f
                        val startAt = vm.position
                        var seekTo = startAt
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2 && (mode == 0 || mode == PINCH)) {
                                mode = PINCH
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val apart = (a - b).getDistance()
                                val centre = (a + b) / 2f
                                if (spread == 0f) {
                                    spread = apart
                                    startZoom = zoom
                                    last = centre
                                } else {
                                    var next = (startZoom * apart / spread).coerceIn(1f, MAX_ZOOM)
                                    // Its own size and covering the screen hold the picture a little, and are felt as reached.
                                    val stop = stops.value.firstOrNull { abs(next - it) / it < STICK }
                                    if (stop != null) {
                                        if (zoom != stop) view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                                        next = stop
                                    }
                                    // The point under the fingers stays under them.
                                    val f = centre - Offset(size.width / 2f, size.height / 2f)
                                    pan = held((pan - f) * (next / zoom) + f + (centre - last), next)
                                    zoom = next
                                    last = centre
                                    readout = Readout(text = "${(zoom * 100).roundToInt()} %")
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (mode == 0) {
                                moved += change.positionChange()
                                if (moved.getDistance() > viewConfiguration.touchSlop) {
                                    // One finger always seeks or sets a level; a grown picture moves with two.
                                    mode = when {
                                        abs(moved.x) > abs(moved.y) -> if (seeking.value) SEEK else -1
                                        down.position.x < size.width / 2 -> if (brightness.value) LIGHT else -1
                                        else -> if (volume.value) VOLUME else -1
                                    }
                                    startLevel = when (mode) {
                                        LIGHT -> lightNow()
                                        VOLUME -> audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()
                                        else -> 0f
                                    }
                                }
                            }
                            val along = change.position - down.position
                            when (mode) {
                                SEEK -> {
                                    val length = vm.duration.takeIf { it > 0 } ?: 0L
                                    seekTo = (startAt + (along.x / size.width * SEEK_WIDTH_MS).toLong()).coerceIn(0, length.coerceAtLeast(0))
                                    val by = seekTo - startAt
                                    readout = Readout(text = (if (by < 0) "−" else "+") + clock(abs(by)) + "  ·  " + clock(seekTo))
                                    change.consume()
                                }
                                LIGHT, VOLUME -> {
                                    val level = (startLevel - along.y / (size.height * LEVEL_HEIGHT)).coerceIn(0f, 1f)
                                    if (mode == LIGHT) {
                                        setLight(level)
                                        readout = Readout(MntIcons.Sun, level)
                                    } else {
                                        val steps = (level * maxVolume).roundToInt()
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, steps, 0)
                                        readout = Readout(MntIcons.Volume, steps / maxVolume.toFloat())
                                    }
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        when (mode) {
                            SEEK -> vm.seek(seekTo)
                        }
                    }
                }
                .pointerInput(inPip, skipMs) {
                    if (inPip) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            shown = !shown
                            touched++
                        },
                        onDoubleTap = { at ->
                            val third = size.width / 3f
                            if (at.x in third..2 * third) {
                                vm.toggle()
                                return@detectTapGestures
                            }
                            val forward = at.x > 2 * third
                            val before = skipped?.takeIf { it.forward == forward }
                            skipped = Skipped(forward, (before?.by ?: 0L) + skipMs, (before?.count ?: 0) + 1)
                            vm.skip(if (forward) skipMs else -skipMs)
                        },
                    )
                },
        )

        if (vm.failed) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(stringResource(R.string.video_failed))
                Box(Modifier.padding(top = 16.dp)) {
                    PillButton(stringResource(R.string.viewer_open_with)) {
                        vm.current?.let { if (!Opener.open(context, it)) Toast.makeText(context, R.string.open_none, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }

        skipped?.let { s ->
            Text(
                (if (s.forward) "+" else "−") + stringResource(R.string.video_seconds, (s.by / 1000).toInt()),
                color = c.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(if (s.forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 48.dp)
                    .clip(CircleShape)
                    .background(SCRIM)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (!inPip) {
            AnimatedVisibility(shown && !vm.failed, enter = fadeIn(tween(SHOW_MS)), exit = fadeOut(tween(HIDE_FADE_MS))) {
                Controls(
                    vm,
                    video.statusBar,
                    onBack = onBack,
                    onPip = { runCatching { activity?.enterPictureInPictureMode(pipParams) } },
                    onSheet = {
                        sheet = it
                        touched++
                    },
                    onTurn = {
                        turned = !landscape
                        touched++
                    },
                    onTouch = { touched++ },
                )
            }
        }
        readout?.let { r -> ReadoutPill(r, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 56.dp)) }
    }

    // A video left part way asks whether to go on from there; put away, it goes on.
    vm.asking?.let { left ->
        var picked by remember { mutableStateOf(false) }
        GroupSheet(stringResource(R.string.video_resume_title), onDismiss = { if (!picked) vm.answer(true) }) { pick ->
            Group {
                GroupRow(stringResource(R.string.video_resume_continue, clock(left)), trailing = {}, onClick = {
                    picked = true
                    pick { vm.answer(true) }
                })
                GroupDivider()
                GroupRow(stringResource(R.string.video_resume_start), trailing = {}, onClick = {
                    picked = true
                    pick { vm.answer(false) }
                })
            }
        }
    }

    when (sheet) {
        VideoSheet.SUBTITLES -> {
            val numbered = vm.subtitles.mapIndexed { i, it -> it to trackName(it, i + 1) }
            ChoiceSheet(
                title = stringResource(R.string.video_subtitles),
                options = listOf<Pair<Choice?, String>>(null to stringResource(R.string.video_off)) + numbered,
                selected = if (vm.subtitlesOff) null else vm.subtitles.firstOrNull { it.selected },
                onSelect = { vm.choose(C.TRACK_TYPE_TEXT, it) },
                onDismiss = { sheet = null },
            )
        }
        VideoSheet.AUDIO -> ChoiceSheet(
            title = stringResource(R.string.video_audio),
            options = vm.audioTracks.mapIndexed { i, it -> it to trackName(it, i + 1) },
            selected = vm.audioTracks.firstOrNull { it.selected },
            onSelect = { vm.choose(C.TRACK_TYPE_AUDIO, it) },
            onDismiss = { sheet = null },
        )
        VideoSheet.SETTINGS -> GroupSheet(stringResource(R.string.video_settings), onDismiss = { sheet = null }) { pick ->
            Group {
                GroupRow(stringResource(R.string.video_speed), value = speedName(vm.speed), onClick = { pick { sheet = VideoSheet.SPEED } })
                GroupDivider()
                GroupRow(stringResource(R.string.video_picture), value = stringResource(FITS.getValue(vm.fit)), onClick = { pick { sheet = VideoSheet.FIT } })
                if (vm.chapters.isNotEmpty()) {
                    GroupDivider()
                    GroupRow(stringResource(R.string.video_chapters), value = vm.chapters.size.toString(), onClick = { pick { sheet = VideoSheet.CHAPTERS } })
                }
                if (!vm.subtitlesOff && vm.subtitles.isNotEmpty()) {
                    GroupDivider()
                    GroupRow(stringResource(R.string.video_delay), value = delayName(vm.subtitleDelay), onClick = { pick { sheet = VideoSheet.DELAY } })
                }
                GroupDivider()
                GroupRow(stringResource(R.string.video_info), onClick = { pick { sheet = VideoSheet.INFO } })
            }
        }
        VideoSheet.CHAPTERS -> {
            val now = vm.position
            ChoiceSheet(
                title = stringResource(R.string.video_chapters),
                options = vm.chapters.mapIndexed { i, it -> it to clock(it.start) + "  ·  " + chapterName(it, i) },
                selected = vm.chapters.lastOrNull { it.start <= now },
                onSelect = { chapter -> chapter?.let { vm.seek(it.start) } },
                onDismiss = { sheet = null },
            )
        }
        VideoSheet.INFO -> GroupSheet(stringResource(R.string.video_info), onDismiss = { sheet = null }) { MediaInfo(vm) }
        VideoSheet.DELAY -> GroupSheet(stringResource(R.string.video_delay), onDismiss = { sheet = null }) { pick ->
            Group {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton48(MntIcons.Minus, stringResource(R.string.video_delay_earlier)) { vm.subtitleDelay -= DELAY_STEP_MS }
                    Text(
                        delayName(vm.subtitleDelay),
                        color = c.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton48(MntIcons.Plus, stringResource(R.string.video_delay_later)) { vm.subtitleDelay += DELAY_STEP_MS }
                }
                GroupDivider()
                GroupRow(stringResource(R.string.video_delay_none), onClick = { pick { vm.subtitleDelay = 0 } })
            }
        }
        VideoSheet.SPEED -> ChoiceSheet(
            title = stringResource(R.string.video_speed),
            options = SPEEDS.map { it to speedName(it) },
            selected = vm.speed,
            onSelect = { vm.speed = it },
            onDismiss = { sheet = null },
        )
        VideoSheet.FIT -> ChoiceSheet(
            title = stringResource(R.string.video_picture),
            options = Fit.entries.map { it to stringResource(FITS.getValue(it)) },
            selected = vm.fit,
            onSelect = { vm.fit = it },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

/** The size of the picture on a screen of [box]: whole inside it, covering it, or stretched to it. */
private fun frame(box: IntSize, aspect: Float, fit: Fit): IntSize {
    if (aspect <= 0f || box.width <= 0 || box.height <= 0 || fit == Fit.STRETCH) return box
    val wider = box.width / box.height.toFloat() > aspect
    val byHeight = if (fit == Fit.FIT) wider else !wider
    return if (byHeight) IntSize((box.height * aspect).roundToInt(), box.height) else IntSize(box.width, (box.width / aspect).roundToInt())
}

/**
 * The picture and its subtitles: two surfaces VLC draws on at [frame] in size, shown [grown] in the middle. The screen's
 * rate follows the video's when it can do so without a blink, or always, as [rate] says.
 */
@Composable
private fun Surface(vm: VideoViewModel, frame: IntSize, grown: IntSize, pan: Offset, stretch: Boolean, rate: FrameRate, frameRate: Float?, onRect: (android.graphics.Rect) -> Unit) {
    val density = LocalDensity.current
    val want = rememberUpdatedState(if (rate == FrameRate.OFF) 0f else frameRate?.takeIf { it > 0f } ?: 0f)
    val change = rememberUpdatedState(if (rate == FrameRate.ALWAYS) Surface.CHANGE_FRAME_RATE_ALWAYS else Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
    fun setRate(surface: Surface?) {
        if (want.value > 0f && surface?.isValid == true) surface.setFrameRate(want.value, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, change.value)
    }
    LaunchedEffect(vm, frame, stretch) { vm.layOut(frame.width, frame.height, stretch) }
    val modifier = if (grown.width > 0 && grown.height > 0) {
        with(density) { Modifier.offset { IntOffset(pan.x.roundToInt(), pan.y.roundToInt()) }.requiredSize(grown.width.toDp(), grown.height.toDp()) }
    } else {
        Modifier.fillMaxSize()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    val picture = SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = setRate(holder.surface)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                        })
                    }
                    val words = SurfaceView(context)
                    addView(picture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    addView(words, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    vm.attach(picture, words)
                }
            },
            update = { layout ->
                val picture = layout.getChildAt(0) as SurfaceView
                // Grown by the fingers, the same picture is drawn larger by the screen, not by VLC at that size.
                if (frame.width > 0 && frame.height > 0) {
                    (0 until layout.childCount).forEach { (layout.getChildAt(it) as SurfaceView).holder.setFixedSize(frame.width, frame.height) }
                }
                setRate(picture.holder.surface)
            },
            onRelease = { vm.detach(it.getChildAt(0) as SurfaceView) },
            modifier = modifier.onGloballyPositioned {
                val r = it.boundsInWindow()
                onRect(android.graphics.Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
            },
        )
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimatedVisibilityScope.Controls(vm: VideoViewModel, statusBar: Boolean, onBack: () -> Unit, onPip: () -> Unit, onSheet: (VideoSheet) -> Unit, onTurn: () -> Unit, onTouch: () -> Unit) {
    val c = MntTheme.colors
    val id = vm.current ?: vm.path
    val named = Videos.named(Paths.name(id))
    Box(Modifier.fillMaxSize()) {
        // Veils at the top and the bottom, for what stands there to read over a light picture.
        Box(Modifier.fillMaxWidth().fillMaxHeight(VEIL).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(VEIL_DARK, Color.Transparent))))
        Box(Modifier.fillMaxWidth().fillMaxHeight(VEIL).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, VEIL_DARK))))

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .animateEnterExit(enter = slideInVertically(tween(SHOW_MS)) { -it / SLIDE_PART }, exit = slideOutVertically(tween(HIDE_FADE_MS)) { -it / SLIDE_PART })
                // With the status bar it keeps the bar's room, so nothing moves when it comes; without, it goes up to the edge.
                .windowInsetsPadding(
                    (if (statusBar) WindowInsets.safeDrawing.union(WindowInsets.statusBarsIgnoringVisibility) else WindowInsets.displayCutout.union(WindowInsets.navigationBars))
                        .only(WindowInsetsSides.Top),
                )
                .evenSides()
                .padding(start = 24.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(named.title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val minutes = vm.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { ((it + 30_000) / 60_000).toInt() }
                val parts = listOfNotNull(
                    named.season?.let { stringResource(R.string.video_episode, it, named.episode ?: 0) },
                    Videos.place(id),
                    minutes?.let { stringResource(R.string.video_minutes, it) },
                )
                val quality = Videos.quality(vm.size.width, vm.size.height)
                val hdr = vm.format?.colorInfo?.colorTransfer.let { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = c.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    quality?.let { Pill(it) }
                    if (hdr) Pill("HDR")
                    // Where the picture is decoded: the phone's hardware, or its processor.
                    vm.hardware?.let { Pill(if (it) "HW" else "SW") }
                }
            }
            IconButton48(MntIcons.PictureInPicture, stringResource(R.string.video_pip), size = 24.dp, onClick = onPip)
            IconButton48(MntIcons.Close, stringResource(R.string.action_close), size = 24.dp, onClick = onBack)
        }

        Row(
            Modifier.align(Alignment.Center).animateEnterExit(enter = scaleIn(tween(SHOW_MS), initialScale = GROWN_FROM), exit = scaleOut(tween(HIDE_FADE_MS), targetScale = GROWN_FROM)),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Disc(MntIcons.SkipBack, stringResource(R.string.video_previous), SIDE_DISC, 26.dp, SCRIM, c.text, vm.canPrevious) {
                vm.previous()
                onTouch()
            }
            Box(
                Modifier.size(PLAY_DISC).clip(CircleShape).background(c.accent).clickable(role = Role.Button) {
                    vm.toggle()
                    onTouch()
                },
                contentAlignment = Alignment.Center,
            ) {
                if (vm.waiting && !vm.ended) {
                    CircularProgressIndicator(color = c.onAccent, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                } else {
                    val playing = vm.playing
                    Icon(
                        if (playing) MntIcons.Pause else MntIcons.Play,
                        stringResource(if (playing) R.string.player_pause else R.string.player_play),
                        tint = c.onAccent,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Disc(MntIcons.SkipForward, stringResource(R.string.video_next), SIDE_DISC, 26.dp, SCRIM, c.text, vm.canNext) {
                vm.next()
                onTouch()
            }
        }

        // The row of buttons stands at the bottom; the line above it stays clear of the gesture strip, where a drag switched apps.
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .animateEnterExit(enter = slideInVertically(tween(SHOW_MS)) { it / SLIDE_PART }, exit = slideOutVertically(tween(HIDE_FADE_MS)) { it / SLIDE_PART })
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .evenSides()
                // The glyphs of the bottom row stand as far above the edge as under the times.
                .padding(start = 24.dp, end = 24.dp, bottom = BOTTOM_GAP),
        ) {
            val now by produceState(vm.position, vm) {
                while (true) {
                    value = vm.position
                    delay(TICK_MS)
                }
            }
            Seek(vm, now, onTouch)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The glyphs, not their targets, stand in line with the times above.
                Box(Modifier.offset(x = (-12).dp)) {
                    IconButton48(MntIcons.Gear, stringResource(R.string.video_settings), size = 24.dp) { onSheet(VideoSheet.SETTINGS) }
                }
                // The chapter it is in; a tap lists them all.
                val at = vm.chapters.indexOfLast { it.start <= now }
                Box(Modifier.weight(1f)) {
                    if (at >= 0) {
                        Text(
                            chapterName(vm.chapters[at], at),
                            color = c.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(CircleShape).clickable { onSheet(VideoSheet.CHAPTERS) }.padding(vertical = 12.dp),
                        )
                    }
                }
                Row(Modifier.offset(x = 12.dp)) {
                    if (vm.subtitles.isNotEmpty()) IconButton48(MntIcons.Subtitles, stringResource(R.string.video_subtitles), size = 24.dp) { onSheet(VideoSheet.SUBTITLES) }
                    if (vm.audioTracks.size > 1) IconButton48(MntIcons.AudioTrack, stringResource(R.string.video_audio), size = 24.dp) { onSheet(VideoSheet.AUDIO) }
                    IconButton48(MntIcons.Rotate, stringResource(R.string.video_turn), size = 24.dp, onClick = onTurn)
                }
            }
        }
    }
}

/** The time gone, the line to seek along, and the time left. */
@Composable
private fun Seek(vm: VideoViewModel, now: Long, onTouch: () -> Unit) {
    val c = MntTheme.colors
    var held by remember { mutableFloatStateOf(-1f) }
    val length = vm.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    val fraction = if (held >= 0f) held else if (length > 0) (now.toFloat() / length).coerceIn(0f, 1f) else 0f
    val style = TextStyle(fontFeatureSettings = "tnum")
    val at = (fraction * length).toLong()
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(clock(at), color = c.text, fontSize = 14.sp, style = style)
        Canvas(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .height(SEEK_HEIGHT)
                .pointerInput(length) {
                    detectTapGestures {
                        if (length > 0) vm.seek((it.x / size.width * length).toLong())
                        onTouch()
                    }
                }
                .pointerInput(length) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            held = (it.x / size.width).coerceIn(0f, 1f)
                            onTouch()
                        },
                        onDragEnd = {
                            if (length > 0) vm.seek((held * length).toLong())
                            held = -1f
                            onTouch()
                        },
                        onDragCancel = { held = -1f },
                    ) { change, _ ->
                        held = (change.position.x / size.width).coerceIn(0f, 1f)
                        onTouch()
                    }
                },
        ) {
            val y = size.height / 2
            val thick = TRACK.toPx()
            val r = (if (held >= 0f) THUMB_HELD else THUMB).toPx() / 2
            val x = size.width * fraction
            val corner = CornerRadius(thick / 2)
            drawRoundRect(Color.White.copy(alpha = REST), Offset(0f, y - thick / 2), Size(size.width, thick), corner)
            drawRoundRect(c.accent, Offset(0f, y - thick / 2), Size(x, thick), corner)
            // Where each chapter begins, a notch in the line.
            if (length > 0) {
                val notch = NOTCH.toPx()
                vm.chapters.drop(1).forEach { chapter ->
                    val cx = size.width * chapter.start / length
                    drawRect(Color.Black, Offset(cx - notch / 2, y - thick / 2), Size(notch, thick))
                }
            }
            drawCircle(Color.White, r, Offset(x.coerceIn(r, size.width - r), y))
            // While held, the time it would go to stands in a pill over the dot, clear of the finger.
            if (held >= 0f) {
                val text = measurer.measure(clock(at), label)
                val padX = PILL_PAD_X.toPx()
                val padY = PILL_PAD_Y.toPx()
                val w = text.size.width + 2 * padX
                val h = text.size.height + 2 * padY
                val left = (x - w / 2).coerceIn(0f, maxOf(0f, size.width - w))
                val top = y - r - PILL_GAP.toPx() - h
                drawRoundRect(SCRIM, Offset(left, top), Size(w, h), CornerRadius(h / 2))
                drawText(text, topLeft = Offset(left + padX, top + padY))
            }
        }
        Text(if (length > 0) "-" + clock(length - at) else "", color = c.text, fontSize = 14.sp, style = style)
    }
}

// The rows stand in the middle between the sides: the camera's cutout, on one side only, is shared out to both,
// which keeps their width. A navigation bar at a side is kept clear of in full.
@Composable
private fun Modifier.evenSides(): Modifier {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val cutout = WindowInsets.displayCutout
    val half = with(density) { ((cutout.getLeft(density, direction) + cutout.getRight(density, direction)) / 2).toDp() }
    return windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)).padding(horizontal = half)
}

@Composable
private fun Disc(icon: ImageVector, description: String, size: Dp, glyph: Dp, ground: Color, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(ground).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = DIMMED), modifier = Modifier.size(glyph))
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text,
        color = MntTheme.colors.text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(SCRIM).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ReadoutPill(r: Readout, modifier: Modifier) {
    val c = MntTheme.colors
    Row(
        modifier.clip(CircleShape).background(SCRIM).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        r.icon?.let { Icon(it, null, tint = c.text, modifier = Modifier.size(20.dp)) }
        r.level?.let { level ->
            Box(Modifier.width(120.dp).height(TRACK).clip(CircleShape).background(Color.White.copy(alpha = REST))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(level).background(c.accent))
            }
        }
        r.text?.let { Text(it, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = "tnum")) }
    }
}

/** What the file is and how it plays now: the file, the picture, the sound, and the decoders of each. */
@OptIn(UnstableApi::class)
@Composable
private fun MediaInfo(vm: VideoViewModel) {
    val context = LocalContext.current
    val id = vm.current ?: vm.path
    val hardware = stringResource(R.string.video_info_hardware)
    val processor = stringResource(R.string.video_info_processor)
    Group {
        GroupRow(stringResource(R.string.video_info_name), value = Paths.name(id))
        vm.sizes[id]?.let {
            GroupDivider()
            GroupRow(stringResource(R.string.video_info_size), value = android.text.format.Formatter.formatFileSize(context, it))
        }
        GroupDivider()
        GroupRow(stringResource(R.string.video_info_container), value = Paths.extension(Paths.name(id)).uppercase())
        if (vm.duration > 0) {
            GroupDivider()
            GroupRow(stringResource(R.string.video_info_length), value = clock(vm.duration))
        }
    }
    vm.format?.let { f ->
        Group(Modifier.padding(top = 12.dp)) {
            val depth = f.colorInfo?.lumaBitdepth?.takeIf { it != Format.NO_VALUE && it > 8 }
            GroupRow(stringResource(R.string.video_info_codec), value = listOfNotNull(VIDEO_CODINGS[f.sampleMimeType] ?: f.sampleMimeType, depth?.let { stringResource(R.string.player_bits, it) }).joinToString(" · "))
            GroupDivider()
            GroupRow(stringResource(R.string.video_info_resolution), value = "${f.width} × ${f.height}")
            if (f.frameRate > 0f) {
                GroupDivider()
                GroupRow(stringResource(R.string.video_info_frame_rate), value = String.format(Locale.ROOT, "%.3f", f.frameRate).trimEnd('0').trimEnd('.') + " fps")
            }
            bitrate(f)?.let {
                GroupDivider()
                GroupRow(stringResource(R.string.video_info_bitrate), value = stringResource(R.string.video_info_kbps, it / 1000))
            }
            GroupDivider()
            GroupRow(stringResource(R.string.video_info_range), value = range(f))
            vm.hardware?.let { onPhone ->
                GroupDivider()
                GroupRow(stringResource(R.string.video_info_decoder), value = (vm.hardwareDecoder ?: "FFmpeg") + " · " + if (onPhone) hardware else processor)
            }
        }
    }
    vm.audioTracks.firstOrNull { it.selected }?.let { choice ->
        val f = choice.format
        Group(Modifier.padding(top = 12.dp)) {
            GroupRow(stringResource(R.string.video_audio), value = trackName(choice, vm.audioTracks.indexOf(choice) + 1))
            if (f.sampleRate != Format.NO_VALUE) {
                GroupDivider()
                GroupRow(stringResource(R.string.video_info_sample_rate), value = stringResource(R.string.player_khz, dev.glowcow.mnt.ui.player.khz(f.sampleRate)))
            }
            bitrate(f)?.let {
                GroupDivider()
                GroupRow(stringResource(R.string.video_info_bitrate), value = stringResource(R.string.video_info_kbps, it / 1000))
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun bitrate(f: Format): Int? = listOf(f.averageBitrate, f.bitrate, f.peakBitrate).firstOrNull { it != Format.NO_VALUE && it > 0 }

@OptIn(UnstableApi::class)
private fun range(f: Format): String = when {
    f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dvh") == true -> "Dolby Vision"
    f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
    f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
    else -> "SDR"
}

@Composable
private fun chapterName(chapter: Chapter, index: Int): String = chapter.title ?: stringResource(R.string.video_chapter, index + 1)

/** A track by its language, its title, whether it is forced, and how it is coded: `English · Commentary · E-AC-3 · 5.1`. */
@OptIn(UnstableApi::class)
@Composable
private fun trackName(choice: Choice, number: Int): String {
    val f = choice.format
    val locale = LocalConfiguration.current.locales[0]
    val language = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(locale).takeIf(String::isNotBlank) ?: it }
        ?.replaceFirstChar { it.titlecase(locale) }
    val title = f.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
    val forced = if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) stringResource(R.string.video_forced) else null
    return listOfNotNull(language ?: stringResource(R.string.video_track, number), title, forced, coding(f)).joinToString(" · ")
}

@OptIn(UnstableApi::class)
private fun coding(f: Format): String? {
    val name = CODINGS[f.sampleMimeType] ?: return null
    val channels = when (f.channelCount) {
        Format.NO_VALUE, 0 -> null
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        else -> f.channelCount.toString()
    }
    return listOfNotNull(name, channels).joinToString(" · ")
}

// `+0.3 s`, `−1.2 s`, `0 s`.
@Composable
private fun delayName(ms: Long): String {
    if (ms == 0L) return stringResource(R.string.video_seconds, 0)
    val sign = if (ms > 0) "+" else "−"
    val locale = LocalConfiguration.current.locales[0]
    return sign + stringResource(R.string.video_seconds_fraction, String.format(locale, "%.1f", abs(ms) / 1000f))
}

private fun speedName(speed: Float): String = (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else String.format(Locale.ROOT, "%s", speed)) + "×"

private val CODINGS = mapOf(
    MimeTypes.AUDIO_AC3 to "AC-3",
    MimeTypes.AUDIO_E_AC3 to "E-AC-3",
    MimeTypes.AUDIO_E_AC3_JOC to "E-AC-3 Atmos",
    MimeTypes.AUDIO_DTS to "DTS",
    MimeTypes.AUDIO_DTS_HD to "DTS-HD",
    MimeTypes.AUDIO_DTS_EXPRESS to "DTS Express",
    MimeTypes.AUDIO_TRUEHD to "TrueHD",
    MimeTypes.AUDIO_AAC to "AAC",
    MimeTypes.AUDIO_MPEG to "MP3",
    MimeTypes.AUDIO_MPEG_L2 to "MP2",
    MimeTypes.AUDIO_OPUS to "Opus",
    MimeTypes.AUDIO_VORBIS to "Vorbis",
    MimeTypes.AUDIO_FLAC to "FLAC",
    MimeTypes.AUDIO_ALAC to "ALAC",
    MimeTypes.AUDIO_RAW to "PCM",
    MimeTypes.APPLICATION_SUBRIP to "SRT",
    MimeTypes.TEXT_SSA to "ASS",
    MimeTypes.TEXT_VTT to "WebVTT",
    MimeTypes.APPLICATION_PGS to "PGS",
    MimeTypes.APPLICATION_VOBSUB to "VobSub",
    MimeTypes.APPLICATION_TTML to "TTML",
    MimeTypes.APPLICATION_TX3G to "TX3G",
    MimeTypes.APPLICATION_DVBSUBS to "DVB",
)

private val VIDEO_CODINGS = mapOf(
    MimeTypes.VIDEO_H264 to "H.264",
    MimeTypes.VIDEO_H265 to "HEVC",
    MimeTypes.VIDEO_AV1 to "AV1",
    "video/x-vnd.on2.vp9" to "VP9",
    "video/x-vnd.on2.vp8" to "VP8",
    MimeTypes.VIDEO_MPEG2 to "MPEG-2",
    MimeTypes.VIDEO_MP4V to "MPEG-4",
    MimeTypes.VIDEO_H263 to "H.263",
    MimeTypes.VIDEO_DOLBY_VISION to "Dolby Vision",
    MimeTypes.VIDEO_VC1 to "VC-1",
    "video/x-ms-wmv" to "WMV",
)

private val FITS = mapOf(Fit.FIT to R.string.video_fit, Fit.FILL to R.string.video_fill, Fit.STRETCH to R.string.video_stretch)
private const val DELAY_STEP_MS = 100L
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

// What a gesture on the picture turned out to be.
private const val PINCH = 1
private const val SEEK = 2
private const val LIGHT = 3
private const val VOLUME = 4

// A drag across the whole width seeks this far; one up the whole of this share of the height goes from none to full.
private const val SEEK_WIDTH_MS = 120_000L
private const val LEVEL_HEIGHT = 0.7f

// How far two fingers grow the picture, and how near a stop holds it there.
private const val MAX_ZOOM = 5f
private const val STICK = 0.04f

// Picture in picture takes no window narrower or wider than this.
private const val PIP_MIN = 0.42f
private const val PIP_MAX = 2.38f

private const val MIN_BRIGHTNESS = 0.01f
private const val HIDE_MS = 5_000L
private const val READOUT_MS = 1000L
// The controls fade in as their rows slide in from the edges and the discs grow to size; they go a little faster.
private const val SHOW_MS = 250
private const val HIDE_FADE_MS = 200
private const val SLIDE_PART = 3
private const val GROWN_FROM = 0.9f
private const val TICK_MS = 250L

private val SCRIM = Color.Black.copy(alpha = 0.45f)
private val VEIL_DARK = Color.Black.copy(alpha = 0.6f)
private const val VEIL = 0.3f
private val BOTTOM_GAP = 10.dp
private const val DIMMED = 0.35f
private const val REST = 0.2f

private val SIDE_DISC = 56.dp
private val PLAY_DISC = 72.dp
private val SEEK_HEIGHT = 28.dp
private val TRACK = 4.dp
private val THUMB = 18.dp
private val PILL_PAD_X = 12.dp
private val PILL_PAD_Y = 6.dp
private val PILL_GAP = 12.dp
private val THUMB_HELD = 24.dp
private val NOTCH = 2.dp

