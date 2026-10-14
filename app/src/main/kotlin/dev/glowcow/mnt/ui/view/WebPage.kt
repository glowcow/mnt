package dev.glowcow.mnt.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import dev.glowcow.mnt.R
import dev.glowcow.mnt.view.WebDoc
import dev.glowcow.mnt.view.html.Look
import dev.glowcow.mnt.view.html.Page
import java.io.ByteArrayInputStream

/** Looks for text in a page: how many matches it found, which of them is in sight (from 1), and the way to the others. */
interface Search {
    val count: Int
    val current: Int

    fun find(text: String)

    fun step(forward: Boolean)

    fun clear() = find("")
}

/** Searches the page of a [WebPage]. */
class Finder : Search {
    internal var view: WebView? = null
    override var count by mutableIntStateOf(0)
        private set
    override var current by mutableIntStateOf(0)
        private set

    internal fun found(active: Int, total: Int) {
        current = if (total == 0) 0 else active + 1
        count = total
    }

    override fun find(text: String) {
        if (text.isEmpty()) {
            view?.clearMatches()
            found(0, 0)
        } else {
            view?.findAllAsync(text)
        }
    }

    override fun step(forward: Boolean) {
        view?.findNext(forward)
    }
}

/**
 * A page of the viewer: [doc]'s pages served from the app, in [look], starting at [start]. Nothing comes from the
 * network; a link out goes to [onLink], one to a file beside the page to [onFile], and [onPath] hears each page shown.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPage(
    doc: WebDoc,
    look: Look,
    /** Where to go, and a count that changes each time it is asked for, so the same place can be asked for again. */
    start: Pair<String, Int>,
    finder: Finder,
    onLink: (String) -> Unit,
    onFile: (String) -> Unit,
    onPath: (String) -> Unit,
    onScrolled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(look)
    val link by rememberUpdatedState(onLink)
    val file by rememberUpdatedState(onFile)
    val path by rememberUpdatedState(onPath)
    val scrolled by rememberUpdatedState(onScrolled)
    val shown = remember { Loaded() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = doc.scripted
                    allowFileAccess = false
                    allowContentAccess = false
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                    useWideViewPort = true
                    loadWithOverviewMode = false
                    textZoom = 100
                    isAlgorithmicDarkeningAllowed = false
                }
                isVerticalScrollBarEnabled = true
                setOnScrollChangeListener { _, _, y, _, _ -> scrolled(y > 0) }
                setFindListener { active, total, _ -> finder.found(active, total) }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val url = request.url
                        if (url.scheme != "https" || url.host != HOST) return missing()
                        val at = url.encodedPath.orEmpty()
                        font(context, at)?.let { return it }
                        val served = runCatching { doc.serve(at, current) }.getOrNull() ?: return missing()
                        return WebResourceResponse(served.mime, if (served.mime.startsWith("text/") || served.mime.endsWith("xml")) "UTF-8" else null, served.stream)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.scheme == "https" && url.host == HOST) {
                            val at = url.encodedPath.orEmpty()
                            if (doc.stays(at)) return false
                            doc.file(at)?.let { file(it) }
                            return true
                        }
                        link(url.toString())
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        path(url.toUri().encodedPath.orEmpty())
                    }
                }
                finder.view = this
            }
        },
        update = { view ->
            view.setBackgroundColor(look.ground.toInt())
            view.settings.javaScriptEnabled = doc.scripted
            // New colours lay out the page shown again; a new document or a place asked for goes there.
            when {
                shown.doc !== doc || shown.start != start -> view.loadUrl(Page.ORIGIN + start.first)
                shown.look != look -> view.reload()
            }
            shown.doc = doc
            shown.look = look
            shown.start = start
        },
        onRelease = { view ->
            finder.view = null
            view.destroy()
        },
    )
}

private class Loaded(var doc: WebDoc? = null, var look: Look? = null, var start: Pair<String, Int>? = null)

private const val HOST = "mnt.local"

private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

// The app's own faces, for the pages to be set in what the rest of the app is.
private fun font(context: Context, path: String): WebResourceResponse? {
    val id = when (path) {
        "/font/arimo.ttf" -> R.font.arimo
        "/font/mono.ttf" -> R.font.jetbrains_mono
        else -> return null
    }
    return WebResourceResponse("font/ttf", null, context.resources.openRawResource(id))
}
