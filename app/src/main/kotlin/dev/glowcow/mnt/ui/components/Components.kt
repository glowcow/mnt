package dev.glowcow.mnt.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.ui.theme.AppFont
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.ui.theme.TitleFont
import dev.glowcow.mnt.ui.theme.inked

enum class TopTab(val icon: ImageVector, val label: Int) {
    // The house: from any page it goes back to the start.
    FILES(MntIcons.Home, R.string.tab_files),
    SEARCH(MntIcons.Search, R.string.tab_search),
    SETTINGS(MntIcons.Settings, R.string.tab_settings),
}

/**
 * The tabs' island. A folder page passes no [current] tab: its own icon, after the house, is lit there; on the other
 * tabs that icon leads back to the folders left, and it is not there on the start page. It grows in and out of the
 * island when it comes or goes from one page to the next.
 */
@Composable
fun BottomBar(current: TopTab?, onSelect: (TopTab) -> Unit) {
    val c = MntTheme.colors
    val folder = LocalFolderTab.current
    val memory = LocalFolderShown.current
    val shown = remember { MutableTransitionState(memory.shown) }
    shown.targetState = folder != null
    SideEffect { memory.shown = folder != null }
    // The way back is kept while the icon goes, for it to be drawn to the end.
    var open by remember { mutableStateOf(folder) }
    if (folder != null) open = folder
    IslandIcons {
        for (tab in TopTab.entries) {
            if (tab != TopTab.FILES) {
                IconButton48(tab.icon, stringResource(tab.label), tint = if (tab == current) c.text else c.muted, size = 24.dp) { onSelect(tab) }
                continue
            }
            // The house and the folder in one piece, so the gap before the folder grows with it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton48(tab.icon, stringResource(tab.label), tint = if (tab == current) c.text else c.muted, size = 24.dp) { onSelect(tab) }
                AnimatedVisibility(
                    shown,
                    enter = expandHorizontally(tween(FOLDER_MS), Alignment.Start) + fadeIn(tween(FOLDER_MS)),
                    exit = shrinkHorizontally(tween(FOLDER_MS), Alignment.Start) + fadeOut(tween(FOLDER_MS)),
                ) {
                    Row {
                        Spacer(Modifier.width(4.dp))
                        IconButton48(MntIcons.Folder, stringResource(R.string.tab_folder), tint = if (current == null) c.text else c.muted, size = 24.dp) { if (current != null) open?.invoke() }
                    }
                }
            }
        }
    }
}

/** Whether the island last drawn had the folder's icon, for the next one to grow it in or out from there. */
class FolderShown {
    var shown = false
}

val LocalFolderShown = staticCompositionLocalOf { FolderShown() }

private const val FOLDER_MS = 180

/** Takes the user back to the folders they left for another tab; null where there are none to go back to. */
val LocalFolderTab = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The title of a page, on one line: Cormorant Garamond, [large] on a tab and the size a title was
 * before in a row with buttons; the app's own bold face where the language is Hebrew, which that face lacks.
 */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val own = LocalConfiguration.current.locales[0].language.let { it == "iw" || it == "he" }
    Text(
        text,
        modifier,
        color = MntTheme.colors.text,
        fontFamily = if (own) AppFont else TitleFont,
        fontSize = when {
            own -> 22.sp
            large -> TAB_TITLE
            else -> PAGE_TITLE
        },
        fontWeight = if (own) FontWeight.Bold else FontWeight.Normal,
        style = TextStyle(fontFeatureSettings = "lnum"),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val TAB_TITLE = 36.sp

// Cormorant's capitals are lower than Arimo's: at this size they stand as tall as a 22 sp title did.
private val PAGE_TITLE = 25.sp

/** An icon to tap, 48 dp to touch; one that cannot be used now is dimmed and takes no taps. */
@Composable
fun IconButton48(
    icon: ImageVector,
    description: String,
    tint: Color = MntTheme.colors.text,
    size: Dp = 22.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val shown = if (enabled) tint else MntTheme.colors.muted.copy(alpha = 0.4f)
        Icon(icon, description, tint = shown, modifier = Modifier.size(size).inked(shown))
    }
}

/** The primary action of a page: a dark pill with a light label, 48 dp to touch. */
@Composable
fun PillButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MntTheme.colors
    Box(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) c.bg else c.muted,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            // One that cannot be used takes the colour of a block: a chip is lost on the ground of a grouped page.
            modifier = Modifier.clip(CircleShape).background(if (enabled) c.text else c.group).padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

/** How far a transfer has got, 0–1: a thin bar that fills in the accent. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val c = MntTheme.colors
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.chip)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(c.accent))
    }
}

/** What a page says when it has nothing to list: a title and a line under it, in the middle. */
@Composable
fun EmptyState(title: String, text: String? = null) {
    val c = MntTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (text != null) Text(text, color = c.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
