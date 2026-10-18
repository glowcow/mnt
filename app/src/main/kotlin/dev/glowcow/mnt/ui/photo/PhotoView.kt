package dev.glowcow.mnt.ui.photo

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import dev.glowcow.mnt.view.image.Gain
import dev.glowcow.mnt.view.image.Picture
import dev.glowcow.mnt.view.image.Region
import dev.glowcow.mnt.view.image.sampleFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How a picture lies on its page: [zoom] times the size that fits, its middle [offset] from the page's middle. */
@Stable
class ZoomState {
    var viewport by mutableStateOf(IntSize.Zero)
    var width by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    /** Screen pixels to a pixel of the picture when it fits the page. */
    val fit: Float get() = if (width <= 0f || height <= 0f || viewport.width == 0) 1f else min(viewport.width / width, viewport.height / height)

    /** The zoom at which the picture fills the page, cut at the sides or at the top and bottom. */
    val fill: Float get() = if (width <= 0f || height <= 0f || viewport.width == 0) 1f else max(viewport.width / width, viewport.height / height) / fit

    // At the most a pixel of the picture is drawn this many screen pixels wide, and never less than three times the fit or the fill.
    val maxZoom: Float get() = max(max(3f, MAX_PIXEL / fit), fill)

    /** Where a double tap goes from here: fitting, filling the page, a pixel of the picture to a pixel of the screen, and back. */
    fun nextStep(): Float {
        val steps = listOf(fill, 1f / fit).filter { it > 1f + STEP_APART }.sorted()
        return steps.firstOrNull { it > zoom * (1f + STEP_APART) } ?: 1f
    }

    val zoomed: Boolean get() = zoom > 1.01f

    fun clamp(o: Offset, z: Float = zoom): Offset {
        val roomX = max(0f, (width * fit * z - viewport.width) / 2)
        val roomY = max(0f, (height * fit * z - viewport.height) / 2)
        return Offset(o.x.coerceIn(-roomX, roomX), o.y.coerceIn(-roomY, roomY))
    }

    /** Grows by [factor] round [centroid], a point of the page, which stays where it is. */
    fun zoomBy(factor: Float, centroid: Offset) {
        val z = (zoom * factor).coerceIn(1f, maxZoom)
        offset = clamp(around(centroid, z), z)
        zoom = z
    }

    // Where the middle goes when the picture grows to [z] round [point].
    fun around(point: Offset, z: Float): Offset {
        val c = point - Offset(viewport.width / 2f, viewport.height / 2f)
        return c - (c - offset) * (z / zoom)
    }

    fun panBy(delta: Offset) {
        offset = clamp(offset + delta)
    }

    /** Whether the picture has room to move by [delta] along its main direction. */
    fun canPan(delta: Offset): Boolean {
        val moved = clamp(offset + delta)
        return if (abs(delta.x) >= abs(delta.y)) abs(moved.x - offset.x) > 0.5f else abs(moved.y - offset.y) > 0.5f
    }

    /** The upright picture onto the page. */
    fun matrix(): Matrix = Matrix().apply {
        val s = fit * zoom
        setTranslate(-width / 2, -height / 2)
        postScale(s, s)
        postTranslate(viewport.width / 2f + offset.x, viewport.height / 2f + offset.y)
    }

    /** The part of the upright picture the page shows. */
    fun visible(): RectF {
        val back = Matrix()
        matrix().invert(back)
        val r = RectF(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat())
        back.mapRect(r)
        if (!r.intersect(0f, 0f, width, height)) r.setEmpty()
        return r
    }
}

/**
 * One picture on its page: pinched or tapped twice to grow, moved with a finger and let go to glide, sharper parts
 * decoded as it grows. [gainOn] draws its gain map at [headroom]; [lightGround] lays a light ground under a picture
 * that lets it through. A tap goes to [onTap], a push upwards to [onSwipeUp].
 */
@Composable
fun PhotoView(
    picture: Picture,
    state: ZoomState,
    gainOn: Boolean,
    headroom: Float,
    lightGround: Boolean,
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val shader = remember { GainShader() }
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val groundPaint = remember { Paint().apply { color = LIGHT_GROUND } }
    var region by remember(picture) { mutableStateOf<Region?>(null) }
    var frame by remember { mutableLongStateOf(0L) }
    val swipe = with(density) { SWIPE_UP.toPx() }

    LaunchedEffect(picture) {
        state.width = picture.uprightWidth.toFloat()
        state.height = picture.uprightHeight.toFloat()
    }

    // A moving picture asks for a frame for as long as it is shown.
    val animation = picture.animation
    if (animation != null) LaunchedEffect(animation) {
        (animation as? Animatable)?.start()
        while (true) {
            androidx.compose.runtime.withFrameNanos { frame = it }
        }
    }

    // Drawn smaller than half its size, the picture is drawn from a copy halved as often, made once it is needed.
    val halves = remember(picture) { Halves(picture.bitmap) }
    if (animation == null) LaunchedEffect(picture) {
        snapshotFlow { halvings(picture.sample * state.fit * state.zoom) }.collectLatest { halves.make(it) }
    }

    // Once the picture rests, the part in sight is decoded at the size it is drawn, if the whole is coarser than that.
    val regions = picture.regions
    if (regions != null) LaunchedEffect(picture) {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        snapshotFlow { Triple(state.zoom, state.offset, state.viewport) }.debounce(REST_MS).collectLatest {
            val scale = state.fit * state.zoom
            val need = sampleFor(1f / scale)
            if (need >= picture.sample) {
                region = null
                return@collectLatest
            }
            val sight = state.visible()
            if (sight.isEmpty) return@collectLatest
            sight.inset(-sight.width() * MARGIN, -sight.height() * MARGIN)
            val stored = picture.orientation.stored(sight, picture.width, picture.height)
            region = withContext(Dispatchers.IO) { regions.decode(stored, need) } ?: region
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                state.viewport = size
                state.offset = state.clamp(state.offset)
            }
            .pointerInput(picture) {
                val slop = viewConfiguration.touchSlop
                val doubleTap = viewConfiguration.doubleTapTimeoutMillis
                var lastTap = 0L
                var lastAt = Offset.Zero
                var pending: Job? = null
                var glide: Job? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    glide?.cancel()
                    val velocity = VelocityTracker()
                    var mode = Mode.NONE
                    var travel = Offset.Zero
                    var upwards = 0f
                    var last = down.position
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // A second finger soon after a touch makes it a pinch, not a tap.
                            if (mode != Mode.ZOOM) pending?.cancel()
                            mode = Mode.ZOOM
                            state.zoomBy(event.calculateZoom(), event.calculateCentroid())
                            state.panBy(event.calculatePan())
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val change = pressed[0]
                            val delta = change.positionChange()
                            last = change.position
                            velocity.addPosition(change.uptimeMillis, change.position)
                            if (mode == Mode.NONE) {
                                travel += delta
                                if (travel.getDistance() > slop) {
                                    val vertical = abs(travel.y) > abs(travel.x)
                                    mode = when {
                                        state.zoomed && state.canPan(travel) -> Mode.PAN
                                        state.zoomed && vertical -> Mode.PAN
                                        !state.zoomed && vertical && travel.y < 0 -> Mode.UP
                                        else -> Mode.PAGER
                                    }
                                }
                            }
                            when (mode) {
                                Mode.PAN -> {
                                    state.panBy(delta)
                                    change.consume()
                                }
                                Mode.UP -> {
                                    upwards += delta.y
                                    change.consume()
                                }
                                else -> Unit
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    when (mode) {
                        Mode.NONE -> {
                            val now = System.currentTimeMillis()
                            if (now - lastTap < doubleTap && (last - lastAt).getDistance() < slop * 4) {
                                pending?.cancel()
                                lastTap = 0L
                                val target = state.nextStep()
                                scope.launch { zoomTo(state, target, last) }
                            } else {
                                lastTap = now
                                lastAt = last
                                pending = scope.launch {
                                    delay(doubleTap)
                                    onTap()
                                }
                            }
                        }
                        Mode.PAN -> {
                            val v = velocity.calculateVelocity()
                            glide = scope.launch {
                                var previous = Offset.Zero
                                AnimationState(Offset.VectorConverter, Offset.Zero, Offset(v.x, v.y)).animateDecay(exponentialDecay()) {
                                    val before = state.offset
                                    state.panBy(value - previous)
                                    previous = value
                                    if (state.offset == before) cancelAnimation()
                                }
                            }
                        }
                        Mode.UP -> if (-upwards > swipe) onSwipeUp()
                        else -> Unit
                    }
                }
            },
    ) {
        frame
        val w = state.width
        val h = state.height
        if (w <= 0f || state.viewport.width == 0) return@Canvas
        val page = state.matrix()
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            if (lightGround) {
                val r = RectF(0f, 0f, w, h)
                page.mapRect(r)
                c.drawRect(r, groundPaint)
            }
            if (animation != null) {
                val r = RectF(0f, 0f, w, h)
                page.mapRect(r)
                animation.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                animation.draw(c)
                return@drawIntoCanvas
            }
            // The stored pixels onto the page: by the sample, upright, then where the page has the picture.
            val upright = picture.orientation.matrix(picture.width.toFloat(), picture.height.toFloat()).apply { postConcat(page) }
            val base = Matrix().apply {
                setScale(picture.sample.toFloat(), picture.sample.toFloat())
                postConcat(upright)
            }
            val gain = picture.gain.takeIf { gainOn }
            val wholeGain = gain?.let { gainOver(picture.bitmap, base, it) }
            val shown = halves.at(halvings(picture.sample * state.fit * state.zoom))
            val shownMatrix = Matrix().apply {
                setScale(picture.bitmap.width.toFloat() / shown.width, picture.bitmap.height.toFloat() / shown.height)
                postConcat(base)
            }
            draw(c, shown, shownMatrix, gain, wholeGain, headroom, shader, bitmapPaint)
            region?.let { part ->
                val m = Matrix().apply {
                    setScale(part.sample.toFloat(), part.sample.toFloat())
                    postTranslate(part.rect.left.toFloat(), part.rect.top.toFloat())
                    postConcat(upright)
                }
                val clip = RectF(part.rect)
                upright.mapRect(clip)
                // A part comes with its own gain map, or lies under the whole picture's.
                val partGain = part.gain.takeIf { gainOn }
                val (g, gm) = if (partGain != null) partGain to gainOver(part.bitmap, m, partGain) else gain to wholeGain
                c.withClip(clip) { draw(c, part.bitmap, m, g, gm, headroom, shader, bitmapPaint) }
            }
        }
    }
}

/** Copies of a bitmap, each half the one before, made as they are asked for: shrunk more than twice by plain filtering, a fine grid beats against the screen's pixels. */
private class Halves(private val bitmap: android.graphics.Bitmap) {
    private val made = mutableStateListOf<android.graphics.Bitmap>()

    /** The bitmap halved [times] times, or as near to that as is made so far. */
    fun at(times: Int): android.graphics.Bitmap = if (times <= 0 || made.isEmpty()) bitmap else made[min(times, made.size) - 1]

    suspend fun make(times: Int) {
        while (made.size < times) {
            val from = made.lastOrNull() ?: bitmap
            if (from.width < 2 || from.height < 2) return
            made += withContext(Dispatchers.Default) { from.scale(from.width / 2, from.height / 2) }
        }
    }
}

// How many times a bitmap drawn at [scale] screen pixels a pixel is halved, to be drawn at no less than half its size.
private fun halvings(scale: Float): Int = if (scale >= 1f) 0 else Integer.numberOfTrailingZeros(sampleFor(1f / scale))

// The gain map of [gain] stretched over [bitmap], which [matrix] places.
private fun gainOver(bitmap: android.graphics.Bitmap, matrix: Matrix, gain: Gain): Matrix = Matrix().apply {
    val map = gain.channels[0]
    setScale(bitmap.width.toFloat() / map.width, bitmap.height.toFloat() / map.height)
    postConcat(matrix)
}

// A bitmap placed by [matrix]; with a gain map, through the app's shader, the map placed by [gainMatrix].
private fun draw(c: android.graphics.Canvas, bitmap: android.graphics.Bitmap, matrix: Matrix, gain: Gain?, gainMatrix: Matrix?, headroom: Float, shader: GainShader, plain: Paint) {
    val paint = if (gain == null || gainMatrix == null || headroom <= 1f) {
        plain.apply { this.shader = GainShader.bitmapShader(bitmap, matrix) }
    } else {
        shader.paint(bitmap, matrix, gain, gainMatrix, headroom)
    }
    val r = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    matrix.mapRect(r)
    c.drawRect(r, paint)
}

// Grows or shrinks to [target] round [point], gliding there.
private suspend fun zoomTo(state: ZoomState, target: Float, point: Offset) {
    val fromZoom = state.zoom
    val fromOffset = state.offset
    val toOffset = state.clamp(state.around(point, target), target)
    AnimationState(0f).animateTo(1f, spring(stiffness = 600f)) {
        state.zoom = fromZoom + (target - fromZoom) * value
        state.offset = state.clamp(fromOffset + (toOffset - fromOffset) * value)
    }
}

private enum class Mode { NONE, ZOOM, PAN, UP, PAGER }

private const val MAX_PIXEL = 4f

// Steps of a double tap closer than this part of each other are one.
private const val STEP_APART = 0.05f
private const val REST_MS = 150L
private const val MARGIN = 0.25f
private val SWIPE_UP = 64.dp

// The light ground under a picture that lets it through.
private const val LIGHT_GROUND = 0xFFF2F2F2.toInt()
