package dev.glowcow.mnt.ui

import android.app.Activity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import dev.glowcow.mnt.ui.components.grown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.glowcow.mnt.audio.Sounds
import dev.glowcow.mnt.ui.components.FolderShown
import dev.glowcow.mnt.ui.components.LocalFolderShown
import dev.glowcow.mnt.ui.components.LocalFolderTab
import dev.glowcow.mnt.ui.components.LocalMiniPlayer
import dev.glowcow.mnt.ui.player.FullPlayer
import dev.glowcow.mnt.ui.player.MiniPlayer
import dev.glowcow.mnt.ui.player.VolumePanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import android.widget.Toast
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.glowcow.mnt.R
import dev.glowcow.mnt.container
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.TransferKind
import dev.glowcow.mnt.ui.components.LocalDirection
import dev.glowcow.mnt.ui.components.LocalPageDark
import dev.glowcow.mnt.ui.components.MOVE_MS
import dev.glowcow.mnt.ui.components.TopTab
import dev.glowcow.mnt.ui.files.BinScreen
import dev.glowcow.mnt.ui.files.DeviceScreen
import dev.glowcow.mnt.ui.files.FilesScreen
import dev.glowcow.mnt.ui.files.FolderScreen
import dev.glowcow.mnt.ui.files.ServerScreen
import dev.glowcow.mnt.ui.search.SearchScreen
import dev.glowcow.mnt.ui.settings.SettingsPage
import dev.glowcow.mnt.ui.settings.SettingsPageScreen
import dev.glowcow.mnt.ui.settings.SettingsScreen
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.ui.photo.PhotoScreen
import dev.glowcow.mnt.ui.video.VideoScreen
import dev.glowcow.mnt.video.Videos
import dev.glowcow.mnt.ui.view.BrowserScreen
import dev.glowcow.mnt.ui.view.ViewerScreen
import dev.glowcow.mnt.ui.view.outside
import dev.glowcow.mnt.view.image.Readers
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable data object FilesRoute : NavKey
@Serializable data object SearchRoute : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data class FolderRoute(val path: String) : NavKey
@Serializable data class DeviceRoute(val id: String) : NavKey
@Serializable data object BinRoute : NavKey
@Serializable data class ServerRoute(val id: String?) : NavKey
@Serializable data class ViewerRoute(val path: String) : NavKey
@Serializable data class PhotoRoute(val path: String) : NavKey
@Serializable data class VideoRoute(val path: String) : NavKey
@Serializable data class BrowserRoute(val url: String) : NavKey
@Serializable data class SettingsPageRoute(val page: SettingsPage) : NavKey

private fun TopTab.route(): NavKey = when (this) {
    TopTab.FILES -> FilesRoute
    TopTab.SEARCH -> SearchRoute
    TopTab.SETTINGS -> SettingsRoute
}

@Composable
fun MntRoot(openTab: Flow<TopTab>) {
    val backStack = rememberNavBackStack(FilesRoute)

    // The folders left for another tab, to go back to from its island; forgotten on the start page.
    var trail by remember { mutableStateOf<List<NavKey>>(emptyList()) }

    // Files is the root: any other tab sits on top of it, so back returns there.
    fun selectTab(tab: TopTab) {
        if (tab == TopTab.FILES) trail = emptyList() else if (backStack.lastOrNull() is FolderRoute) trail = backStack.drop(1).toList()
        backStack.retainAll { it == FilesRoute }
        if (backStack.isEmpty()) backStack.add(FilesRoute)
        if (tab != TopTab.FILES) backStack.add(tab.route())
    }

    fun backToFolders() {
        if (trail.isEmpty()) return
        backStack.retainAll { it == FilesRoute }
        if (backStack.isEmpty()) backStack.add(FilesRoute)
        backStack.addAll(trail)
    }
    val top = backStack.lastOrNull()
    LaunchedEffect(top) { if (top == FilesRoute) trail = emptyList() }
    val folderShown = remember { FolderShown() }
    val folderTab: (() -> Unit)? = if (top is FolderRoute || trail.isNotEmpty() && top != FilesRoute) ::backToFolders else null

    // A folder already on the way back is returned to, not opened a second time over itself.
    fun openFolder(path: String) {
        val at = backStack.lastIndexOf(FolderRoute(path))
        if (at < 0) backStack.add(FolderRoute(path)) else while (backStack.lastIndex > at) backStack.removeLastOrNull()
    }

    LaunchedEffect(openTab) { openTab.collect { selectTab(it) } }

    // How a transfer ended is said once, whatever page is open by then.
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings by context.container.settings.settings.collectAsStateWithLifecycle(null)

    // A track plays its folder and a playlist what it lists, in the app's own player.
    val playback = context.container.playback
    fun openFile(path: String) {
        val name = Paths.name(path)
        when {
            Sounds.plays(name) -> playback.playFolder(path)
            Sounds.lists(name) -> playback.playList(path)
            Videos.plays(name) -> backStack.add(VideoRoute(path))
            else -> backStack.add(if (Readers.shows(name)) PhotoRoute(path) else ViewerRoute(path))
        }
    }

    // A page of the web opens in the app's own browser while the settings say so; anything else goes to the app that takes it.
    fun openLink(url: String) {
        val uri = url.toUri()
        val web = uri.scheme?.lowercase() in setOf("http", "https")
        if (web && settings?.linksInApp != false) {
            backStack.add(BrowserRoute(url))
        } else if (!outside(context, uri)) {
            Toast.makeText(context, resources.getString(R.string.open_none), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        context.container.transfers.results.collect { result ->
            val text = when {
                result.stopped -> resources.getString(R.string.transfer_stopped)
                result.full -> resources.getString(R.string.transfer_no_room)
                result.failed > 0 -> resources.getString(R.string.transfer_failed, result.failed)
                else -> resources.getString(
                    when (result.kind) {
                        TransferKind.COPY -> R.string.transfer_copied
                        TransferKind.MOVE -> R.string.transfer_moved
                        TransferKind.DELETE -> R.string.transfer_deleted
                        TransferKind.ZIP -> R.string.transfer_zipped
                        TransferKind.UNPACK -> R.string.transfer_unpacked
                    },
                )
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    val c = MntTheme.colors
    val pageDark = remember { mutableStateOf<Boolean?>(null) }

    // The full player comes up from below over every page: 0 down, 1 over the whole screen.
    val scope = rememberCoroutineScope()
    val open = remember { Animatable(0f) }
    val fling = with(LocalDensity.current) { FLING.toPx() }
    var screen by remember { mutableFloatStateOf(1f) }
    fun expand() = scope.launch { open.animateTo(1f, tween(PLAYER_MS, easing = FastOutSlowInEasing)) }
    fun collapse() = scope.launch { open.animateTo(0f, tween(PLAYER_MS, easing = FastOutSlowInEasing)) }
    // A drag goes on from the end it started at: a small pull either way is enough to go to the other.
    var from by remember { mutableFloatStateOf(-1f) }
    fun drag(by: Float) = scope.launch {
        if (from < 0f) from = open.targetValue
        open.snapTo((open.value - by / screen).coerceIn(0f, 1f))
    }
    // Let go, it goes the way it was flung, or on to the other end once pulled far enough.
    fun settle(speed: Float) = scope.launch {
        val start = if (from < 0f) open.targetValue else from
        from = -1f
        val pulled = abs(open.value - start) > PULL
        val up = if (abs(speed) > fling) speed < 0 else if (pulled) start < 0.5f else start >= 0.5f
        open.animateTo(if (up) 1f else 0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow), -speed / screen)
    }
    LaunchedEffect(playback) { playback.opens.receiveAsFlow().collect { expand() } }
    val playerShown = playback.shown
    LaunchedEffect(playerShown) { if (!playerShown) open.snapTo(0f) }
    val covering by remember { derivedStateOf { playback.shown && open.value > 0f } }
    // Where the mini player stands, for the full player to grow out of it and to shrink back into it.
    var miniAt by remember { mutableStateOf<Rect?>(null) }
    val mini = remember {
        movableContentOf {
            Box(Modifier.onGloballyPositioned { miniAt = it.boundsInRoot() }) { MiniPlayer(playback, { expand() }, { drag(it) }, { settle(it) }) }
        }
    }
    val lightIcons = if (covering && open.value > 0.5f) !c.isDark else !(pageDark.value ?: c.isDark)
    // Lists lie on the plain ground, the pages of blocks on the grouped one.
    val ground = when (backStack.lastOrNull()) {
        is FolderRoute, SearchRoute, BinRoute -> c.bg
        // Pictures and videos are shown on black in any theme.
        is PhotoRoute, is VideoRoute -> Color.Black
        else -> c.groupBg
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        // Two pages cross-fading let the window show through; it has to be the theme's ground, not the system's.
        window.setBackgroundDrawable(ground.toArgb().toDrawable())
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightIcons
            isAppearanceLightNavigationBars = lightIcons
        }
    }

    // The pages move themselves (pageMotion); here both are only kept on the screen for as long as
    // that may take, the one that leaves on top when going back.
    val direction = LocalDirection.current
    val stay = fadeIn(snap()) togetherWith fadeOut(snap(delayMillis = MOVE_MS))
    val stayBelow = ContentTransform(fadeIn(snap()), fadeOut(snap(delayMillis = MOVE_MS)), targetContentZIndex = -1f)
    // A page dark in any theme says so, for the bar icons to stay light over it.
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.fillMaxSize().onSizeChanged {
            rootSize = it
            screen = it.height.toFloat().coerceAtLeast(1f)
        },
    ) {
    // Under the full player the pages are out of reach, of touch and of the screen reader alike.
    val hidden by remember { derivedStateOf { playback.shown && open.value == 1f } }
    CompositionLocalProvider(LocalPageDark provides pageDark, LocalMiniPlayer provides if (playerShown) mini else null, LocalFolderTab provides folderTab, LocalFolderShown provides folderShown) {
        NavDisplay(
            modifier = if (hidden) Modifier.clearAndSetSemantics {} else Modifier,
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            transitionSpec = {
                direction.back = false
                stay
            },
            popTransitionSpec = {
                direction.back = true
                stayBelow
            },
            predictivePopTransitionSpec = {
                direction.back = true
                stayBelow
            },
            entryProvider = entryProvider {
                entry<FilesRoute> { FilesScreen(onTab = ::selectTab, onOpen = ::openFolder, onDevice = { backStack.add(DeviceRoute(it)) }, onBin = { backStack.add(BinRoute) }, onServer = { backStack.add(ServerRoute(it)) }) }
                entry<ServerRoute> { route -> ServerScreen(route.id, onBack = { backStack.remove(route) }) }
                entry<BinRoute> { BinScreen(onBack = { backStack.remove(BinRoute) }) }
                entry<DeviceRoute> { route -> DeviceScreen(route.id, onBack = { backStack.remove(route) }, onOpen = ::openFolder) }
                entry<FolderRoute> { route -> FolderScreen(route.path, onBack = { backStack.removeLastOrNull() }, onOpen = ::openFolder, onView = ::openFile, onTab = ::selectTab) }
                entry<SearchRoute> { SearchScreen(onTab = ::selectTab, onOpen = ::openFolder, onView = ::openFile) }
                entry<SettingsRoute> { SettingsScreen(onTab = ::selectTab, onPage = { backStack.add(SettingsPageRoute(it)) }) }
                entry<SettingsPageRoute> { route -> SettingsPageScreen(route.page, onBack = { backStack.remove(route) }) }
                entry<ViewerRoute> { route -> ViewerScreen(route.path, onBack = { backStack.remove(route) }, onLink = ::openLink, onFile = ::openFile) }
                entry<PhotoRoute> { route -> PhotoScreen(route.path, onBack = { backStack.remove(route) }) }
                entry<VideoRoute> { route -> VideoScreen(route.path, onBack = { backStack.remove(route) }) }
                entry<BrowserRoute> { route -> BrowserScreen(route.url, onClose = { backStack.remove(route) }) }
            },
        )
    }
    if (covering) {
        // Where the mini player would stand when none is laid out: a page without one, or the first track played.
        val density = LocalDensity.current
        val below = WindowInsets.navigationBars.getBottom(density)
        val from = miniAt ?: with(density) {
            val side = 16.dp.toPx()
            val bottom = rootSize.height - below - 8.dp.toPx()
            Rect(side, bottom - 56.dp.toPx(), rootSize.width - side, bottom)
        }
        Box(Modifier.fillMaxSize().grown(from, MINI_CORNER) { open.value }) {
            FullPlayer(
                playback,
                onDrag = { drag(it) },
                onDragStop = { settle(it) },
                onFolder = {
                    collapse()
                    openFolder(it)
                },
            )
        }
    }
    VolumePanel(playback)
    }
    // Back takes the full player down as the gesture goes, before it leaves any page.
    PredictiveBackHandler(enabled = covering && open.targetValue == 1f) { progress ->
        try {
            progress.collect { open.snapTo(1f - it.progress) }
            collapse()
        } catch (e: CancellationException) {
            expand()
            throw e
        }
    }
}

private const val PLAYER_MS = 300

// The mini player's corners, which the full player's start from.
private val MINI_CORNER = 28.dp

// How fast a let-go drag must be, a second, to be a fling, and how far one must go to be more than a slip.
private val FLING = 400.dp
private const val PULL = 0.15f
