package dev.glowcow.mnt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme

/**
 * A page opened from a tab: the content runs under a frosted [header] and [bar] and gets the heights they
 * cover. [scrolled] says the page has gone under the header, [from] is the row of a tab it was opened from.
 * Content that cannot be recorded, a web view, is not [glass]: it is drawn once, below an opaque header, or,
 * when it draws the glass itself ([clear]), under a header with no ground of its own.
 */
@Composable
fun DetailPage(
    ground: Color,
    scrolled: Boolean,
    header: @Composable ColumnScope.() -> Unit,
    from: Rect? = null,
    bar: (@Composable () -> Unit)? = null,
    glass: Boolean = true,
    clear: Boolean = false,
    content: @Composable (top: Dp, bottom: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val page = rememberGraphicsLayer()
    var headerHeight by remember { mutableIntStateOf(0) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val barHeight = remember { mutableIntStateOf(0) }
    val spot = remember { mutableStateOf<Rect?>(null) }
    if (bar == null) spot.value = null
    Box(Modifier.fillMaxSize().pageMotion(from).background(ground).imePadding().onSizeChanged { pageSize = it }) {
        Box(
            if (!glass) Modifier.fillMaxSize() else Modifier.fillMaxSize().drawWithContent {
                page.record { this@drawWithContent.drawContent() }
                drawLayer(page)
            },
        ) {
            CompositionLocalProvider(LocalIsland provides spot) {
                content(with(density) { headerHeight.toDp() }, with(density) { barHeight.intValue.toDp() })
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHeight = it.height }
                .then(
                    when {
                        glass -> Modifier.frosted(page, ground) { Offset.Zero }
                        clear -> Modifier
                        else -> Modifier.background(ground)
                    },
                )
                .solid()
                .statusBarsPadding(),
        ) {
            header()
            HeaderLine(shown = scrolled)
        }
        Island(page, ground, { pageSize }, { barHeight.intValue = it }, glass, clear, { spot.value = it }, content = bar)
    }
}

/** Where the island of a [DetailPage] stands on its page, in pixels; null when it has none. */
val LocalIsland = staticCompositionLocalOf { mutableStateOf<Rect?>(null) }

/** The top row of a page opened from a tab: the way out, the title, the page's actions. */
@Composable
fun DetailHeader(
    title: String,
    onLeave: () -> Unit,
    leave: ImageVector = MntIcons.Back,
    leaveLabel: String = stringResource(R.string.back),
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton48(leave, leaveLabel, onClick = onLeave)
        PageTitle(title, Modifier.weight(1f).padding(start = 4.dp, end = 8.dp))
        actions()
    }
}

/** A grouped page opened from a tab: a column of blocks that scrolls under the frosted header. */
@Composable
fun DetailScreen(
    title: String,
    onBack: () -> Unit,
    from: Rect? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    DetailPage(MntTheme.colors.groupBg, scrolled = scrolled, header = { DetailHeader(title, onBack, actions = actions) }, from = from) { top, bottom ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = top + HeaderGap, bottom = bottom + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
