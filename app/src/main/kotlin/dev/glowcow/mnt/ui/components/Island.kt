package dev.glowcow.mnt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.glowcow.mnt.ui.theme.MntTheme

/**
 * The bar at the bottom of a page: an island of glass over [page], as wide as what it holds and clear
 * of the edges, the page fading into its [ground] under it, and the mini player above it while something
 * plays ([player]). [onHeight] hears how much of the bottom of the page, [pageSize] large, they take — the
 * navigation bar alone when there is neither. Over a page that is not recorded they are not [glass]:
 * blurring nothing over a web view keeps the web view drawing every frame. A page that draws its own glass
 * ([clear]) is told where the island stands ([onSpot]) and draws the glass and the shadow under it; any
 * other is under an opaque island.
 */
@Composable
fun BoxScope.Island(
    page: GraphicsLayer,
    ground: Color,
    pageSize: () -> IntSize,
    onHeight: (Int) -> Unit,
    glass: Boolean = true,
    clear: Boolean = false,
    onSpot: (Rect) -> Unit = {},
    player: Boolean = true,
    content: (@Composable () -> Unit)?,
) {
    val c = MntTheme.colors
    val density = LocalDensity.current
    val mini = if (player) LocalMiniPlayer.current else null
    var zone by remember { mutableStateOf(IntSize.Zero) }
    val spots = remember { Spots() }
    // Where a piece of glass of the zone lies on the page: the zone stands at the page's bottom, as wide as it.
    fun origin(of: LayoutCoordinates?): Offset {
        val inZone = of?.let { spots.zone?.takeIf { z -> z.isAttached && it.isAttached }?.localPositionOf(it, Offset.Zero) } ?: Offset.Zero
        return Offset(inZone.x, pageSize().height - zone.height + inZone.y)
    }
    var islandSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(zone, islandSize) {
        if (content != null && zone.height > 0 && islandSize.width > 0) onSpot(Rect(origin(spots.island), islandSize.toSize()))
    }
    val shown = content != null || mini != null
    // The veil starts a little above the island and is all but the ground at the edge of the screen.
    if (shown) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { zone.height.toDp() } + VEIL_RISE)
                .background(Brush.verticalGradient(0f to ground.copy(alpha = 0f), 1f to ground.copy(alpha = VEIL))),
        )
    }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .onPlaced { spots.zone = it }
            .onSizeChanged {
                zone = it
                onHeight(it.height)
            }
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = if (shown) ISLAND_GAP else 0.dp),
        verticalArrangement = Arrangement.spacedBy(ISLAND_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mini != null) {
            var size by remember { mutableStateOf(IntSize.Zero) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .onPlaced { spots.mini = it }
                    .onSizeChanged { size = it }
                    // Over a web view there is no page to blur: the mini player is opaque there.
                    .then(if (glass) Modifier.shadow(SHADOW, ISLAND, ambientColor = SHADE, spotColor = SHADE).frosted(page, c.group) { origin(spots.mini) } else Modifier.shadow(SHADOW, ISLAND, ambientColor = SHADE, spotColor = SHADE).background(c.group, ISLAND))
                    .border(1.dp, c.text.copy(alpha = GLASS_LINE), ISLAND)
                    .clip(ISLAND)
                    .heightIn(min = 56.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                mini()
            }
        }
        if (content != null) {
            Box(
                Modifier
                    .onPlaced { spots.island = it }
                    .onSizeChanged { islandSize = it }
                    .then(
                        when {
                            glass -> Modifier
                                .shadow(SHADOW, ISLAND, ambientColor = SHADE, spotColor = SHADE)
                                .frosted(page, c.group) { origin(spots.island) }
                            // The page draws the glass and the shadow: a shadow of the view's would show through the glass.
                            clear -> Modifier
                            else -> Modifier.shadow(SHADOW, ISLAND, ambientColor = SHADE, spotColor = SHADE).background(c.group, ISLAND)
                        },
                    )
                    .border(1.dp, c.text.copy(alpha = GLASS_LINE), ISLAND)
                    .solid()
                    .heightIn(min = 56.dp),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }
}

// Where the zone and its pieces were laid out, read when the glass is drawn: no state, nothing composed again.
private class Spots {
    var zone: LayoutCoordinates? = null
    var mini: LayoutCoordinates? = null
    var island: LayoutCoordinates? = null
}

/** The mini player of what plays, for every bottom of a page to hold above its island; null while nothing does. */
val LocalMiniPlayer = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** The icons of an island, each 48 dp to touch: the island is as wide as there are of them. */
@Composable
fun IslandIcons(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

// An island of icons is 56 dp high, so its ends are half circles.
private val ISLAND = RoundedCornerShape(28.dp)

private val ISLAND_GAP = 8.dp

private val SHADOW = 12.dp

// The shadow is soft in a light theme and all but lost on a dark ground.
private val SHADE = Color.Black.copy(alpha = 0.35f)

private val VEIL_RISE = 24.dp

private const val VEIL = 0.92f
