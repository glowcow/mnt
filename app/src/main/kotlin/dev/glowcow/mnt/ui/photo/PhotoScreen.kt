package dev.glowcow.mnt.ui.photo

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.Display
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glowcow.mnt.R
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.files.Opener
import dev.glowcow.mnt.files.Trash
import dev.glowcow.mnt.ui.components.DetailHeader
import dev.glowcow.mnt.ui.components.EmptyState
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.components.IconButton48
import dev.glowcow.mnt.ui.components.Island
import dev.glowcow.mnt.ui.components.IslandIcons
import dev.glowcow.mnt.ui.components.LocalPageDark
import dev.glowcow.mnt.ui.components.PillButton
import dev.glowcow.mnt.ui.components.ProgressLine
import dev.glowcow.mnt.ui.components.frosted
import dev.glowcow.mnt.ui.components.pageMotion
import dev.glowcow.mnt.ui.components.solid
import dev.glowcow.mnt.ui.theme.MntColorsProvider
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.ui.theme.colorsOf
import dev.glowcow.mnt.view.image.Picture
import java.util.function.Consumer

private enum class PhotoSheet { DETAILS, TAGS, DELETE, MORE }

/**
 * The pictures of a folder, one a page, from the one at [path]: swiped through sideways, grown with two fingers or
 * a double tap, their details pushed up from below. Always on black, whatever the theme.
 */
@Composable
fun PhotoScreen(
    path: String,
    onBack: () -> Unit,
    vm: PhotoViewModel = viewModel(key = path) { PhotoViewModel(path, this[APPLICATION_KEY]!!) },
) {
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    val bars = LocalPageDark.current
    DisposableEffect(Unit) {
        bars.value = true
        onDispose { bars.value = null }
    }
    // The dark colours of the scheme in use, on black.
    MntColorsProvider(colorsOf(settings.palette, true, LocalConfiguration.current.isScreenWideColorGamut)) {
        Photos(vm, settings, onBack)
    }
}

@Composable
private fun Photos(vm: PhotoViewModel, settings: AppSettings, onBack: () -> Unit) {
    val c = MntTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val items = vm.items
    // Made afresh once the folder is read, standing at the opened picture among the others.
    val pager = key(vm.listed) { rememberPagerState(initialPage = vm.start) { items.size } }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var sheet by rememberSaveable { mutableStateOf<PhotoSheet?>(null) }
    var light by rememberSaveable { mutableStateOf(false) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val page = rememberGraphicsLayer()
    val window = LocalWindowInfo.current
    val screen = maxOf(window.containerSize.width, window.containerSize.height).coerceAtLeast(1)

    val current = items.getOrNull(pager.currentPage.coerceAtMost(items.lastIndex))
    // The pager keeps more pages than it shows, so what is decoded follows the page in sight, not the pages it keeps.
    LaunchedEffect(pager, pager.currentPage, items) {
        if (items.isEmpty()) return@LaunchedEffect onBack()
        val at = pager.currentPage.coerceAtMost(items.lastIndex)
        val near = (0..NEAR).flatMap { listOf(at + it, at - it) }.distinct().mapNotNull { items.getOrNull(it)?.path }
        val keep = items.subList((at - KEEP).coerceAtLeast(0), (at + KEEP + 1).coerceAtMost(items.size)).mapTo(HashSet()) { it.path }
        vm.focus(near, keep, screen)
    }
    val shown = current?.let { (vm.shot(it.path) as? Shot.Ready)?.picture }

    // The screen's headroom as it is now: the panel brightens in steps once the window asks for HDR.
    val headroom = remember { mutableFloatStateOf(1f) }
    val hdr = settings.ultraHdr && shown != null && (shown.gain != null || shown.hdr)
    HdrWindow(activity, hdr, shown, headroom)

    // With the bars away the system's bars go too, until a swipe from the edge brings them back.
    DisposableEffect(chrome) {
        val insets = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) insets?.show(WindowInsetsCompat.Type.systemBars()) else insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { insets?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(Modifier.fillMaxSize().pageMotion().background(Color.Black).onSizeChanged { pageSize = it }) {
        Box(
            Modifier.fillMaxSize().drawWithContent {
                page.record { this@drawWithContent.drawContent() }
                drawLayer(page)
            },
        ) {
            HorizontalPager(pager, beyondViewportPageCount = 1, key = { items.getOrNull(it)?.path ?: it }) { i ->
                val node = items.getOrNull(i) ?: return@HorizontalPager
                val zoom = remember(node.path) { ZoomState() }
                when (val shot = vm.shot(node.path)) {
                    is Shot.Ready -> PhotoView(
                        picture = shot.picture,
                        state = zoom,
                        gainOn = settings.ultraHdr,
                        headroom = headroom.floatValue,
                        lightGround = light && shot.picture.alpha,
                        onTap = { chrome = !chrome },
                        onSwipeUp = { sheet = PhotoSheet.DETAILS },
                    )
                    is Shot.Fetching -> Box(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentAlignment = Alignment.Center) {
                        Column {
                            Text(stringResource(R.string.viewer_fetching, (shot.fraction * 100).toInt()), color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
                            ProgressLine(shot.fraction)
                        }
                    }
                    is Shot.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyState(stringResource(R.string.viewer_failed), shot.reason ?: stringResource(R.string.viewer_failed_text))
                            Box(Modifier.padding(top = 16.dp)) { PillButton(stringResource(R.string.viewer_open_with)) { open(context, node.path) } }
                        }
                    }
                    Shot.Loading -> Unit
                }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(FADE_MS))) {
            Column(Modifier.fillMaxWidth().frosted(page, Color.Black) { Offset.Zero }.solid().statusBarsPadding()) {
                DetailHeader(current?.name.orEmpty(), onBack) {
                    if (vm.developing == current?.path) Text(stringResource(R.string.photo_developing), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    else if (items.size > 1) Text(stringResource(R.string.viewer_part, pager.currentPage + 1, items.size), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton48(MntIcons.More, stringResource(R.string.action_more)) { sheet = PhotoSheet.MORE }
                }
            }
        }
        if (chrome && current != null) {
            Island(page, Color.Black, { pageSize }, {}) {
                IslandIcons {
                    IconButton48(MntIcons.Share, stringResource(R.string.action_share)) { if (!Opener.share(context, listOf(current.path))) toast(context, R.string.open_none) }
                    IconButton48(MntIcons.Info, stringResource(R.string.action_details)) { sheet = PhotoSheet.DETAILS }
                    if (shown?.alpha == true) {
                        IconButton48(MntIcons.Checkerboard, stringResource(if (light) R.string.photo_dark_ground else R.string.photo_light_ground)) { light = !light }
                    }
                    if (vm.writes) IconButton48(MntIcons.Delete, stringResource(R.string.action_delete)) { sheet = PhotoSheet.DELETE }
                }
            }
        }
    }

    val node = current ?: return
    when (sheet) {
        PhotoSheet.DETAILS -> DetailsSheet(vm, node, onTags = { sheet = PhotoSheet.TAGS }, onDismiss = { sheet = null })
        PhotoSheet.TAGS -> TagsSheet(vm, node, onDismiss = { sheet = null })
        PhotoSheet.DELETE -> GroupSheet(stringResource(R.string.photo_delete_title), onDismiss = { sheet = null }) { pick ->
            val binned = remember { vm.binned() }
            Text(
                if (binned) stringResource(R.string.delete_text, Trash.KEEP_DAYS) else stringResource(R.string.delete_for_good_text),
                color = c.muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Group(Modifier.padding(top = 12.dp)) {
                if (binned) {
                    GroupRow(stringResource(R.string.delete_to_bin), onClick = { pick { if (!vm.delete(node.path, forGood = false)) toast(context, R.string.transfer_busy) } })
                    GroupDivider()
                }
                GroupRow(stringResource(R.string.delete_for_good), titleColor = c.danger, onClick = { pick { if (!vm.delete(node.path, forGood = true)) toast(context, R.string.transfer_busy) } })
            }
        }
        PhotoSheet.MORE -> GroupSheet(node.name, onDismiss = { sheet = null }) { pick ->
            val picture = (vm.shot(node.path) as? Shot.Ready)?.picture
            Group {
                // A RAW photo is the camera's preview at first; its data can be developed instead, and back.
                if (picture?.raw == true) {
                    val developed = picture.developed
                    GroupRow(
                        stringResource(if (developed) R.string.photo_preview else R.string.photo_develop),
                        icon = MntIcons.Aperture,
                        onClick = { pick { vm.develop(node.path, !developed, screen) } },
                        trailing = {},
                    )
                    GroupDivider()
                }
                GroupRow(stringResource(R.string.viewer_open_with), icon = MntIcons.External, onClick = { pick { open(context, node.path) } }, trailing = {})
            }
        }
        null -> Unit
    }
}

/**
 * Puts the window into HDR while [on], asking for the headroom [picture] needs, and keeps [headroom] at what the
 * screen gives right now; back to wide colour when it is off or the page goes.
 */
@Composable
private fun HdrWindow(activity: Activity?, on: Boolean, picture: Picture?, headroom: androidx.compose.runtime.MutableFloatState) {
    val want = picture?.gain?.fullHdr?.coerceIn(1f, MAX_HEADROOM) ?: if (picture?.hdr == true) MAX_HEADROOM else 1f
    DisposableEffect(activity, on, want) {
        val window = activity?.window
        val display: Display? = activity?.display
        if (window == null || display == null || !on) {
            headroom.floatValue = 1f
            return@DisposableEffect onDispose {}
        }
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        window.desiredHdrHeadroom = want
        val listener = Consumer<Display> { headroom.floatValue = if (it.isHdrSdrRatioAvailable) it.hdrSdrRatio else 1f }
        headroom.floatValue = if (display.isHdrSdrRatioAvailable) display.hdrSdrRatio else 1f
        if (display.isHdrSdrRatioAvailable) display.registerHdrSdrRatioChangedListener(activity.mainExecutor, listener)
        onDispose {
            if (display.isHdrSdrRatioAvailable) display.unregisterHdrSdrRatioChangedListener(listener)
            window.desiredHdrHeadroom = 0f
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
    }
}

private fun open(context: android.content.Context, path: String) {
    if (!Opener.open(context, path)) toast(context, R.string.open_none)
}

private fun toast(context: android.content.Context, text: Int) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

// The pictures on either side of the one in sight are decoded, so a swipe shows the next at once; those one further
// stay decoded, so a finger going back and forth across the middle does not decode them again.
private const val NEAR = 1
private const val KEEP = 2

private const val FADE_MS = 160

// No picture asks for more than this: past it the panel only gets hotter.
private const val MAX_HEADROOM = 8f
