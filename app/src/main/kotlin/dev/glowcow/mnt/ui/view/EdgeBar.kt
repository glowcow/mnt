package dev.glowcow.mnt.ui.view

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.glowcow.mnt.ui.theme.MntTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/**
 * The scroll bar of every page of the viewer, as a web view draws its own: 4 dp at the right edge, a third of black
 * (of white in the dark theme), between the header and the bars at the bottom, gone half a second after the page
 * stops. [moved] changes as the page scrolls; [where] gets the height of the page's view and tells how far it is
 * scrolled and how tall the whole is, read while drawing, so scrolling does not compose the bar again.
 */
@Composable
internal fun EdgeBar(top: Dp, bottom: Dp, moved: () -> Any, modifier: Modifier = Modifier, where: Density.(view: Float) -> Pair<Float, Float>?) {
    val dark = MntTheme.colors.isDark
    var shown by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 0 else BAR_FADE), label = "bar")
    LaunchedEffect(Unit) {
        snapshotFlow(moved).drop(1).collectLatest {
            shown = true
            delay(BAR_STAYS)
            shown = false
        }
    }
    val shade = if (dark) Color.White.copy(alpha = BAR_SHADE) else Color.Black.copy(alpha = BAR_SHADE)
    Box(
        modifier.fillMaxHeight().padding(top = top, bottom = bottom).width(BAR_WIDTH).graphicsLayer { this.alpha = alpha }.drawBehind {
            // Read here, so each move draws the bar again where the page now is.
            moved()
            val track = size.height
            val view = track + (top + bottom).toPx()
            val (scrolled, total) = where(view) ?: return@drawBehind
            if (total <= view + 1) return@drawBehind
            val length = (track * view / total).coerceAtLeast(BAR_MIN.toPx())
            drawRect(shade, Offset(0f, (track - length) * (scrolled / (total - view)).coerceIn(0f, 1f)), Size(size.width, length))
        },
    )
}

private val BAR_WIDTH = 4.dp
private val BAR_MIN = 24.dp
private const val BAR_SHADE = 0.32f
private const val BAR_STAYS = 500L
private const val BAR_FADE = 300
