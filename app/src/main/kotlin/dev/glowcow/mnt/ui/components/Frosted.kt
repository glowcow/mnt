package dev.glowcow.mnt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.glowcow.mnt.ui.theme.MntTheme

/**
 * Frosted glass: the part of [page] under this element, at [origin], blurred and tinted with [ground].
 * With [fade] it comes in gradually over that much of its top — of its bottom when [fadeDown].
 */
@Composable
fun Modifier.frosted(page: GraphicsLayer, ground: Color, fade: Dp = 0.dp, fadeDown: Boolean = false, origin: () -> Offset): Modifier {
    val frost = rememberGraphicsLayer()
    return drawBehind {
        val radius = FROST_BLUR.toPx()
        val at = origin()
        frost.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
        // The page is redrawn over an opaque ground, so nothing sharp shows through the blur.
        frost.record {
            drawRect(ground)
            translate(-at.x, -at.y) { drawLayer(page) }
        }
        val glass = {
            // The blur spills past the bounds of its layer.
            clipRect { drawLayer(frost) }
            drawRect(ground.copy(alpha = FROST_TINT))
        }
        if (fade <= 0.dp) {
            glass()
        } else {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                glass()
                val edge = (fade.toPx() / size.height).coerceIn(0f, 1f)
                val mask = if (fadeDown) {
                    Brush.verticalGradient(0f to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent)
                } else {
                    Brush.verticalGradient(0f to Color.Transparent, edge to Color.Black, 1f to Color.Black)
                }
                drawRect(mask, blendMode = BlendMode.DstIn)
                canvas.restore()
            }
        }
    }
}

/** The room between a header and the first block of a grouped page, the gap between two blocks. */
val HeaderGap = 12.dp

/** The line under a frosted header: there only while the page has gone under the header, so a page at its top has none. */
@Composable
fun HeaderLine(shown: Boolean) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(if (shown) MntTheme.colors.line else Color.Transparent))
}

/** Keeps touches from reaching the page that shows through the glass. */
fun Modifier.solid() = pointerInput(Unit) {}

private val FROST_BLUR = 20.dp

// One strength of glass for every bar and panel of the app.
private const val FROST_TINT = 0.5f

// The edge of glass is the text colour, thin: a solid line is a dark stripe where something light shows through.
const val GLASS_LINE = 0.12f
