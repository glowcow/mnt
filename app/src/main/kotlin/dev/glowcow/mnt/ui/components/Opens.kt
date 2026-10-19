package dev.glowcow.mnt.ui.components

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * Where on the screen the rows of a tab lie, so the page a row opens can grow out of it. A row is
 * seen wherever it is laid out, marked when it is tapped, and taken once by the page it opens.
 */
class Origins {
    private val seen = HashMap<String, Rect>()
    private val marked = HashMap<String, Rect>()

    fun see(key: String, bounds: Rect) {
        seen[key] = bounds
    }

    fun mark(key: String) {
        seen[key]?.let { marked[key] = it }
    }

    fun take(key: String): Rect? = marked.remove(key)
}

val LocalOrigins = staticCompositionLocalOf { Origins() }

/** Tells [LocalOrigins] where this row lies, under the [key] of the page it opens. */
@Composable
fun Modifier.origin(key: String): Modifier {
    val origins = LocalOrigins.current
    return onGloballyPositioned { origins.see(key, it.boundsInRoot()) }
}

/** Which way the pages are going: on, to a new one, or back to the one below. */
class Direction {
    var back = false
}

val LocalDirection = staticCompositionLocalOf { Direction() }

/**
 * How a page comes and goes; only the page on top moves. One opened from the row at [from] grows out of
 * it and shrinks back, held by its top in a frame as wide as itself; any other fades in over the one below.
 */
@Composable
fun Modifier.pageMotion(from: Rect? = null): Modifier {
    val direction = LocalDirection.current
    val open by LocalNavAnimatedContentScope.current.transition.animateFloat(
        { if (from != null) tween(MOVE_MS, easing = FastOutSlowInEasing) else tween(FADE_MS) },
    ) {
        when (it) {
            EnterExitState.Visible -> 1f
            EnterExitState.PreEnter -> if (direction.back) 1f else 0f
            EnterExitState.PostExit -> if (direction.back) 0f else 1f
        }
    }
    if (from == null) return graphicsLayer { alpha = open }
    return grown(from) { open }
}

/**
 * A page grown out of the place [from] by [open], 0 to 1: held by its top in a frame as wide as itself, scaled from
 * that place's width to its own, its corners straightening out and showing through for the first part of the way.
 */
fun Modifier.grown(from: Rect, corner: Dp = CORNER, open: () -> Float): Modifier = graphicsLayer {
    val t = open()
    val width = lerp(from.width, size.width, t)
    val height = lerp(from.height, size.height, t)
    val scale = width / size.width
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = scale
    scaleY = scale
    translationX = lerp(from.left, 0f, t)
    translationY = lerp(from.top, 0f, t)
    alpha = (t / SHOWN_BY).coerceIn(0f, 1f)
    clip = true
    // Measured on the page before it is scaled: the frame's height there, and corners that straighten out.
    shape = Frame(height / scale, corner.toPx() * (1f - t) / scale)
}

private class Frame(private val height: Float, private val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(Rect(0f, 0f, size.width, height), CornerRadius(corner)))
}

/** Whether the page on top is dark whatever the theme, as code in a dark scheme is; null when it follows the theme. */
val LocalPageDark = staticCompositionLocalOf { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }

/** The key of a folder's page and of the rows that open it. */
fun folderKey(path: String) = "folder:$path"

/** The key of the page of a device's volumes and of the row that opens it. */
fun deviceKey(id: String) = "device:$id"

const val BIN_KEY = "bin"

/** How long a page takes to grow out of a row or to shrink back into it; no page moves for longer. */
const val MOVE_MS = 300

private const val FADE_MS = 160

private const val SHOWN_BY = 0.3f
private val CORNER = 20.dp
