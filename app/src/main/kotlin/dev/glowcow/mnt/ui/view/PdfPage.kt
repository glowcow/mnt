package dev.glowcow.mnt.ui.view

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import androidx.core.util.size
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.compose.FastScrollConfiguration
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.compose.rememberPdfViewerState
import androidx.pdf.view.PdfView
import dev.glowcow.mnt.R
import dev.glowcow.mnt.ui.components.EmptyState
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupField
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.theme.MntTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Searches a PDF: the matches of all its pages, each laid over the page, the one in sight brighter. */
@OptIn(ExperimentalPdfApi::class)
class PdfSearch(private val scope: CoroutineScope, private val color: Int, private val brighter: Int) : Search {
    internal var document: PdfDocument? = null
    internal var state: PdfViewerState? = null
    private var matches: List<PdfRect> = emptyList()
    private var job: Job? = null
    override var count by mutableIntStateOf(0)
        private set
    override var current by mutableIntStateOf(0)
        private set

    override fun find(text: String) {
        job?.cancel()
        val doc = document ?: return
        if (text.isBlank()) {
            matches = emptyList()
            count = 0
            current = 0
            state?.setHighlights(emptyList())
            return
        }
        job = scope.launch {
            val found = doc.searchDocument(text, 0 until doc.pageCount)
            matches = (0 until found.size).flatMap { i ->
                val page = found.keyAt(i)
                found.valueAt(i).mapNotNull { m -> m.bounds.firstOrNull()?.let { PdfRect(page, it) } }
            }
            count = matches.size
            current = if (matches.isEmpty()) 0 else 1
            show()
        }
    }

    override fun step(forward: Boolean) {
        if (matches.isEmpty()) return
        current = (current - 1 + if (forward) 1 else matches.size - 1) % matches.size + 1
        show()
    }

    private fun show() {
        val state = state ?: return
        state.setHighlights(matches.mapIndexed { i, r -> Highlight(r, if (i == current - 1) brighter else color) })
        matches.getOrNull(current - 1)?.let { r -> scope.launch { state.scrollToPosition(PdfPoint(r.pageNum, r.left, r.top)) } }
    }
}

/**
 * Where in a PDF the screen is: the page at the middle of what the bars leave, of how many, and the way to another.
 * Read [page] where it may change often: in a derived state or while drawing.
 */
@OptIn(ExperimentalPdfApi::class)
class PdfPages(private val scope: CoroutineScope) {
    var document by mutableStateOf<PdfDocument?>(null)
        internal set
    internal var state: PdfViewerState? = null

    /** The pages' sizes in points, once read, and the zoom that fits a page to the screen's width. */
    internal var heights by mutableStateOf<FloatArray?>(null)
    internal var widths: FloatArray? = null
    internal var fitZoom = 1f
    internal var middle = 0f

    val count: Int get() = document?.pageCount ?: 0

    val page: Int
        get() {
            val s = state ?: return 0
            val first = s.firstVisiblePage
            val h = heights?.getOrNull(first) ?: return first
            return if (s.firstVisiblePageOffset.y + h * s.zoom < middle && first + 1 < count) first + 1 else first
        }

    fun go(page: Int) {
        val s = state ?: return
        scope.launch { s.scrollToPage(page.coerceIn(0, count - 1)) }
    }
}

/**
 * A PDF drawn by the system's sandboxed renderer: pages to zoom, text to select and copy, links, a password when it has one.
 * Pages run under the header and [onScrolled] says when they have gone under it; a document shorter than the screen stands in the middle.
 */
@OptIn(ExperimentalPdfApi::class)
@Composable
fun PdfPage(
    uri: Uri,
    search: PdfSearch,
    pages: PdfPages,
    onLink: (String) -> Unit,
    onFailed: (String?) -> Unit,
    onScrolled: (Boolean) -> Unit,
    top: Dp,
    bottom: Dp,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var document by remember { mutableStateOf<PdfDocument?>(null) }
    var password by remember { mutableStateOf<String?>(null) }
    var locked by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    LaunchedEffect(uri, password) {
        try {
            document = SandboxedPdfLoader(context).openDocument(uri, password)
            locked = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: PdfPasswordException) {
            wrong = password != null
            locked = true
        } catch (e: Exception) {
            onFailed(e.message)
        }
    }
    DisposableEffect(document) {
        val opened = document
        onDispose { runCatching { opened?.close() } }
    }
    val state = rememberPdfViewerState()
    search.document = document
    search.state = state
    pages.state = state
    LaunchedEffect(document) {
        val doc = document ?: return@LaunchedEffect
        // Pages are measured one by one up to a point; past it, all are taken as the size of the first.
        val sizes = if (doc.pageCount > MEASURED_PAGES) doc.getPageInfo(0).let { first -> List(doc.pageCount) { first } } else doc.getPageInfos(0 until doc.pageCount)
        pages.widths = FloatArray(sizes.size) { sizes[it].width.toFloat().coerceAtLeast(1f) }
        pages.heights = FloatArray(sizes.size) { sizes[it].height.toFloat() }
        pages.document = doc
    }
    // Back lets go of a selection before it leaves the page.
    androidx.activity.compose.BackHandler(enabled = state.currentSelection != null) { state.clearCurrentSelection() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val doc = document
        val heights = pages.heights
        val widths = pages.widths
        val side = with(density) { SIDE.toPx() }
        val room = with(density) { (maxHeight - top - bottom).toPx() }
        val wide = with(density) { maxWidth.toPx() } - side * 2
        pages.middle = with(density) { top.toPx() } + room / 2
        if (top > 0.dp && doc != null && heights != null && widths != null) {
            // The renderer fits the widest page to the screen and keeps its gaps in the document's units: 12 dp at that zoom.
            val fitZoom = wide / widths.max()
            pages.fitZoom = fitZoom
            // The renderer centres a short document in all of its height, padding or not: one that fits is kept clear of the bars instead.
            val fit = doc.pageCount <= FEW_PAGES && heights.indices.sumOf { (heights[it] * fitZoom).toDouble() } + with(density) { GAP.toPx() } * (heights.size - 1) + side * 2 <= room
            // The renderer takes its padding once, when it is made: it is made again when the bars change.
            key(top, bottom, fit) {
                PdfViewer(
                    pdfDocument = doc,
                    state = state,
                    modifier = if (fit) Modifier.fillMaxSize().padding(top = top, bottom = bottom) else Modifier.fillMaxSize(),
                    verticalPageSpacing = with(density) { (GAP.toPx() / fitZoom).toDp() },
                    // Sheets with the ground at their sides, as a Word document's are. The renderer fits a page into its padding
                    // but centres it in its whole width and then moves it by the left padding: all of it goes to the right.
                    contentPadding = if (fit) PaddingValues(end = SIDE * 2) else PaddingValues(top = top + SIDE, end = SIDE * 2, bottom = bottom + SIDE),
                    // No handle and no page label of the renderer's: the pages scroll as the app's other pages do.
                    fastScrollConfig = FastScrollConfiguration.withDrawableIdsAndDp(
                        fastScrollPageIndicatorBackgroundDrawableRes = android.R.color.transparent,
                        fastScrollVerticalThumbDrawableRes = android.R.color.transparent,
                    ),
                    onUrlLinkClicked = { link ->
                        onLink(link.toString())
                        true
                    },
                )
                PageShadows(doc, widths, heights)
            }
            val start = with(density) { (top + SIDE).toPx() }
            val scrolled by remember(fit) { derivedStateOf { !fit && (state.firstVisiblePage > 0 || state.firstVisiblePageOffset.y < start - 1) } }
            LaunchedEffect(scrolled) { onScrolled(scrolled) }
            ScrollBar(state, pages, if (fit) 0.dp else top + SIDE, if (fit) 0.dp else bottom + SIDE, Modifier.align(Alignment.TopEnd))
        }
        if (locked && document == null) {
            Box(Modifier.fillMaxSize().padding(top = top, bottom = bottom), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.viewer_locked), stringResource(R.string.viewer_locked_text))
            }
        }
    }
    if (locked) {
        var typed by remember { mutableStateOf("") }
        GroupSheet(stringResource(R.string.viewer_locked), onDismiss = { locked = false }) { pick ->
            if (wrong) Text(stringResource(R.string.viewer_wrong_password), color = MntTheme.colors.muted, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
            Group { GroupField(stringResource(R.string.backup_password), typed, { typed = it }, secret = true, focused = true) }
            Group(Modifier.padding(top = 12.dp)) { GroupRow(stringResource(R.string.viewer_unlock), onClick = { pick { password = typed } }) }
        }
    }
}

/**
 * The shadow of each page on the ground round it, as a Word document's sheets have. It is laid over the renderer's own
 * view, which draws it in the same frame as the pages: drawn beside the view, it would trail them by a frame.
 */
@OptIn(ExperimentalPdfApi::class)
@Composable
private fun PageShadows(doc: PdfDocument, widths: FloatArray, heights: FloatArray) {
    val host = LocalView.current
    val density = LocalDensity.current.density
    LaunchedEffect(doc) {
        // The renderer's view is there a frame after it is composed.
        var view: PdfView? = null
        while (view == null) {
            withFrameNanos { }
            view = findPdfView(host, doc)
        }
        val shadows = Shadows(view, widths, heights, density)
        view.overlay.add(shadows)
        view.addOnViewportChangedListener(shadows)
        try {
            awaitCancellation()
        } finally {
            view.removeOnViewportChangedListener(shadows)
            view.overlay.remove(shadows)
        }
    }
}

private fun findPdfView(view: View, doc: PdfDocument): PdfView? {
    if (view is PdfView) return view.takeIf { it.pdfDocument === doc }
    if (view !is ViewGroup) return null
    for (i in 0 until view.childCount) findPdfView(view.getChildAt(i), doc)?.let { return it }
    return null
}

// The shadow of a sheet in the paged layout, at the zoom a sheet is shown at: 2.5 dp of blur, half a dp down, black at 30 %.
// Each page is placed where the view draws it at the moment it is drawn, so the shadow never lags behind it.
@OptIn(ExperimentalPdfApi::class)
private class Shadows(private val view: PdfView, private val widths: FloatArray, private val heights: FloatArray, density: Float) :
    Drawable(), PdfView.OnViewportChangedListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        setShadowLayer(2.5f * density, 0f, 0.5f * density, 0x4D000000)
    }
    private val rect = RectF()

    override fun onViewportChanged(firstVisiblePage: Int, visiblePagesCount: Int, pageLocations: SparseArray<RectF>, zoomLevel: Float) = invalidateSelf()

    override fun draw(canvas: Canvas) {
        // The overlay is drawn on the view's canvas, which is moved by its scroll; the points are on the screen.
        canvas.withTranslation(view.scrollX.toFloat(), view.scrollY.toFloat()) {
            val first = view.firstVisiblePage
            for (i in maxOf(0, first - 1)..minOf(widths.size - 1, first + view.visiblePagesCount)) {
                val start = view.pdfToViewPoint(PdfPoint(i, 0f, 0f)) ?: continue
                val end = view.pdfToViewPoint(PdfPoint(i, widths[i], heights[i])) ?: continue
                rect.set(start.x, start.y, end.x, end.y)
                withSave {
                    clipOutRect(rect)
                    drawRect(rect, paint)
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    init {
        setBounds(0, 0, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2)
    }
}

/**
 * The bar at the edge that shows where in the document the screen is, drawn as a web page's is: thin, at the very
 * edge, there while the pages move and a moment after. [top] and [bottom] pad the pages.
 */
@OptIn(ExperimentalPdfApi::class)
@Composable
private fun ScrollBar(state: PdfViewerState, pages: PdfPages, top: Dp, bottom: Dp, modifier: Modifier) {
    val dark = MntTheme.colors.isDark
    var shown by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 0 else BAR_FADE), label = "bar")
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.gestureState, state.firstVisiblePage, state.firstVisiblePageOffset) }.drop(1).collectLatest { (gesture, _, _) ->
            shown = true
            if (gesture == PdfViewerState.GESTURE_STATE_IDLE) {
                delay(BAR_STAYS)
                shown = false
            }
        }
    }
    val heights = pages.heights ?: return
    val shade = if (dark) Color.White.copy(alpha = BAR_SHADE) else Color.Black.copy(alpha = BAR_SHADE)
    // Where the pages are is read while drawing, so scrolling does not compose the bar again.
    Box(
        modifier.fillMaxHeight().width(BAR_WIDTH).graphicsLayer { this.alpha = alpha }.drawBehind {
            val view = size.height
            val zoom = state.zoom
            val gap = GAP.toPx() * zoom / pages.fitZoom
            val total = heights.sum() * zoom + gap * (heights.size - 1) + (top + bottom).toPx()
            if (total <= view + 1) return@drawBehind
            var before = 0f
            for (i in 0 until state.firstVisiblePage.coerceIn(0, heights.size)) before += heights[i] * zoom + gap
            val scrolled = (before - (state.firstVisiblePageOffset.y - top.toPx())).coerceIn(0f, total - view)
            val length = (view * view / total).coerceAtLeast(BAR_MIN.toPx())
            drawRect(shade, Offset(0f, (view - length) * scrolled / (total - view)), Size(size.width, length))
        },
    )
}

// As a web view draws its bar: 4 dp at the edge, a third of black, gone half a second after the page stops.
private val BAR_WIDTH = 4.dp
private val BAR_MIN = 24.dp
private const val BAR_SHADE = 0.32f
private const val BAR_STAYS = 500L
private const val BAR_FADE = 300

// Up to this many pages are measured one by one for the bar.
private const val MEASURED_PAGES = 2000

// The ground at a page's sides and under the header, and between two pages: as round a Word document's sheets.
private val SIDE = 8.dp
private val GAP = 12.dp

// A document of this many pages or fewer may fit the screen whole.
private const val FEW_PAGES = 4

/** The colour a match of a search is marked in, from the accent: [strong] for the one in sight. */
fun matchColor(accent: androidx.compose.ui.graphics.Color, strong: Boolean): Int = accent.copy(alpha = if (strong) 0.55f else 0.25f).toArgb()
