package dev.glowcow.mnt.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ContentTransform
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
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
import dev.glowcow.mnt.ui.components.MOVE_MS
import dev.glowcow.mnt.ui.components.TopTab
import dev.glowcow.mnt.ui.files.BinScreen
import dev.glowcow.mnt.ui.files.DeviceScreen
import dev.glowcow.mnt.ui.files.FilesScreen
import dev.glowcow.mnt.ui.files.FolderScreen
import dev.glowcow.mnt.ui.search.SearchScreen
import dev.glowcow.mnt.ui.settings.SettingsScreen
import dev.glowcow.mnt.ui.theme.MntTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable data object FilesRoute : NavKey
@Serializable data object SearchRoute : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data class FolderRoute(val path: String) : NavKey
@Serializable data class DeviceRoute(val id: String) : NavKey
@Serializable data object BinRoute : NavKey

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
    LaunchedEffect(Unit) {
        context.container.transfers.results.collect { result ->
            val text = when {
                result.stopped -> resources.getString(R.string.transfer_stopped)
                result.failed > 0 -> resources.getString(R.string.transfer_failed, result.failed)
                else -> resources.getString(
                    when (result.kind) {
                        TransferKind.COPY -> R.string.transfer_copied
                        TransferKind.MOVE -> R.string.transfer_moved
                        TransferKind.DELETE -> R.string.transfer_deleted
                    },
                )
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    val c = MntTheme.colors
    val lightIcons = !c.isDark
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
            entry<FilesRoute> { FilesScreen(onTab = ::selectTab, onOpen = ::openFolder, onDevice = { backStack.add(DeviceRoute(it)) }, onBin = { backStack.add(BinRoute) }) }
            entry<BinRoute> { BinScreen(onBack = { backStack.remove(BinRoute) }) }
            entry<DeviceRoute> { route -> DeviceScreen(route.id, onBack = { backStack.remove(route) }, onOpen = ::openFolder) }
            entry<FolderRoute> { route -> FolderScreen(route.path, onBack = { backStack.removeLastOrNull() }, onOpen = ::openFolder) }
            entry<SearchRoute> { SearchScreen(onTab = ::selectTab, onOpen = ::openFolder) }
            entry<SettingsRoute> { SettingsScreen(onTab = ::selectTab) }
        },
    )
}

