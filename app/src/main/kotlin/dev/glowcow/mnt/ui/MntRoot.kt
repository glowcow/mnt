package dev.glowcow.mnt.ui

import android.app.Activity
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
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
import dev.glowcow.mnt.ui.view.BrowserScreen
import dev.glowcow.mnt.ui.view.ViewerScreen
import dev.glowcow.mnt.ui.view.outside
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

    // Files is the root: any other tab sits on top of it, so back returns there.
    fun selectTab(tab: TopTab) {
        backStack.retainAll { it == FilesRoute }
        if (backStack.isEmpty()) backStack.add(FilesRoute)
        if (tab != TopTab.FILES) backStack.add(tab.route())
    }

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

    fun openFile(path: String) {
        backStack.add(ViewerRoute(path))
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
    val lightIcons = !(pageDark.value ?: c.isDark)
    // Lists lie on the plain ground, the pages of blocks on the grouped one.
    val ground = if (backStack.lastOrNull().let { it is FolderRoute || it == SearchRoute || it == BinRoute }) c.bg else c.groupBg
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
    CompositionLocalProvider(LocalPageDark provides pageDark) {
        NavDisplay(
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
                entry<FolderRoute> { route -> FolderScreen(route.path, onBack = { backStack.removeLastOrNull() }, onOpen = ::openFolder, onView = ::openFile) }
                entry<SearchRoute> { SearchScreen(onTab = ::selectTab, onOpen = ::openFolder, onView = ::openFile) }
                entry<SettingsRoute> { SettingsScreen(onTab = ::selectTab, onPage = { backStack.add(SettingsPageRoute(it)) }) }
                entry<SettingsPageRoute> { route -> SettingsPageScreen(route.page, onBack = { backStack.remove(route) }) }
                entry<ViewerRoute> { route -> ViewerScreen(route.path, onBack = { backStack.remove(route) }, onLink = ::openLink, onFile = ::openFile) }
                entry<BrowserRoute> { route -> BrowserScreen(route.url, onClose = { backStack.remove(route) }) }
            },
        )
    }
}
