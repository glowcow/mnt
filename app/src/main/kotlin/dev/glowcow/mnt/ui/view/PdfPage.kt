package dev.glowcow.mnt.ui.view

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.util.size
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.compose.rememberPdfViewerState
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

/** A PDF drawn by the system's sandboxed renderer: pages to zoom, text to select and copy, links, a password when it has one. */
@OptIn(ExperimentalPdfApi::class)
@Composable
fun PdfPage(uri: Uri, search: PdfSearch, onLink: (String) -> Unit, onFailed: (String?) -> Unit, top: Dp, bottom: Dp) {
    val context = LocalContext.current
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
    // Back lets go of a selection before it leaves the page.
    androidx.activity.compose.BackHandler(enabled = state.currentSelection != null) { state.clearCurrentSelection() }
    Box(Modifier.fillMaxSize()) {
        PdfViewer(
            pdfDocument = document,
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = top, bottom = bottom),
            onUrlLinkClicked = { link ->
                onLink(link.toString())
                true
            },
        )
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

/** The colour a match of a search is marked in, from the accent: [strong] for the one in sight. */
fun matchColor(accent: androidx.compose.ui.graphics.Color, strong: Boolean): Int = accent.copy(alpha = if (strong) 0.55f else 0.25f).toArgb()
