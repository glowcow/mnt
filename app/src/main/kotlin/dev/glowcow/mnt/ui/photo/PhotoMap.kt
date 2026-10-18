package dev.glowcow.mnt.ui.photo

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glowcow.mnt.container
import dev.glowcow.mnt.maps.Tiles
import dev.glowcow.mnt.ui.theme.MntTheme
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.floor

/** OpenStreetMap round where a photo was taken, a dot on the spot; a tap goes to [onClick]. */
@Composable
fun PhotoMap(latitude: Double, longitude: Double, onClick: () -> Unit) {
    val c = MntTheme.colors
    val tiles = LocalContext.current.container.tiles
    val loaded = remember(latitude, longitude) { mutableStateMapOf<Pair<Int, Int>, Bitmap>() }
    // A pixel of a tile is a dp of the screen, as a map app draws it, so its lettering reads.
    val cx = Tiles.x(longitude, ZOOM) * Tiles.SIZE
    val cy = Tiles.y(latitude, ZOOM) * Tiles.SIZE
    Box(
        Modifier.fillMaxWidth().height(HEIGHT).clip(RoundedCornerShape(20.dp)).background(c.group).clickable(role = Role.Button, onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = density.toDouble()
            val halfW = size.width / 2 / scale
            val halfH = size.height / 2 / scale
            val tone = if (c.isDark) DIM else null
            for (tx in floor((cx - halfW) / Tiles.SIZE).toInt()..floor((cx + halfW) / Tiles.SIZE).toInt()) {
                for (ty in floor((cy - halfH) / Tiles.SIZE).toInt()..floor((cy + halfH) / Tiles.SIZE).toInt()) {
                    val bitmap = loaded[tx to ty] ?: continue
                    val left = ((tx * Tiles.SIZE - cx) * scale + size.width / 2).toFloat()
                    val top = ((ty * Tiles.SIZE - cy) * scale + size.height / 2).toFloat()
                    val side = (Tiles.SIZE * scale).toInt()
                    drawImage(bitmap.asImageBitmap(), dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(side, side), colorFilter = tone)
                }
            }
            val centre = Offset(size.width / 2, size.height / 2)
            drawCircle(Color.White, DOT.toPx() + RING.toPx(), centre)
            drawCircle(c.accent, DOT.toPx(), centre)
            drawCircle(Color.Black.copy(alpha = 0.2f), DOT.toPx() + RING.toPx(), centre, style = Stroke(1.dp.toPx()))
        }
        Text(
            CREDIT,
            color = c.text,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(c.group.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    // The tiles round the spot, two at a time as the tile server asks, from the cache when they are there.
    LaunchedEffect(latitude, longitude) {
        val reachX = 1
        val reachY = 1
        val x0 = floor(cx / Tiles.SIZE).toInt()
        val y0 = floor(cy / Tiles.SIZE).toInt()
        val gate = Semaphore(2)
        coroutineScope {
            (x0 - reachX..x0 + reachX).flatMap { x -> (y0 - reachY..y0 + reachY).map { y -> x to y } }.map { (x, y) ->
                async { gate.withPermit { tiles.tile(ZOOM, x, y) }?.let { loaded[x to y] = it } }
            }.awaitAll()
        }
    }
}

private const val ZOOM = 15
private val HEIGHT = 160.dp
private val DOT = 6.dp
private val RING = 2.dp
private const val CREDIT = "© OpenStreetMap contributors"

/** The map on a dark page: a little over half of its colour and half of its light. */
private val DIM = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(0.55f)
        timesAssign(ColorMatrix().apply { setToScale(0.5f, 0.5f, 0.5f, 1f) })
    },
)
