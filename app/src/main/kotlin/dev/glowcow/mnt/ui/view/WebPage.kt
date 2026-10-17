package dev.glowcow.mnt.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebChromeClient
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
import dev.glowcow.mnt.view.html.Spot
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

/** The sheets of a page laid out on them: which is at the middle of the screen (from 0), of how many, and the way to another. */
class Sheets {
    internal var view: WebView? = null
    var page by mutableIntStateOf(0)
        private set
    var count by mutableIntStateOf(0)
        private set

    // The page writes `mnt-pages:3/12` into its title as it scrolls.
    internal fun heard(title: String?) {
        val (at, of) = title?.removePrefix("mnt-pages:")?.takeIf { it != title }?.split('/')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: listOf(1, 0)
        page = at - 1
        count = of
    }

    fun go(page: Int) {
        view?.evaluateJavascript("window.mntPage&&mntPage(${page.coerceIn(0, maxOf(0, count - 1))})", null)
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
    sheets: Sheets,
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
                    javaScriptEnabled = doc.own
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
                // A pull past the edge stretches the whole view, the glass the page draws with it, away from the bars.
                overScrollMode = View.OVER_SCROLL_NEVER
                setOnScrollChangeListener { _, _, y, _, _ -> scrolled(y > 0) }
                setFindListener { active, total, _ -> finder.found(active, total) }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView, title: String?) = sheets.heard(title)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val url = request.url
                        if (url.scheme != "https" || url.host != HOST) return missing()
                        val at = url.encodedPath.orEmpty()
                        font(context, at)?.let { return it }
                        val served = runCatching { doc.serve(at, current) }.getOrNull() ?: return missing()
                        val text = served.mime.startsWith("text/") || served.mime.endsWith("xml")
                        return WebResourceResponse(served.mime, if (text) "UTF-8" else null, 200, "OK", if (text) POLICY else emptyMap(), served.stream)
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
                sheets.view = this
            }
        },
        update = { view ->
            view.setBackgroundColor(look.ground.toInt())
            view.settings.javaScriptEnabled = doc.own
            // New colours lay out the page shown again; a new document or a place asked for goes there. The bars' heights
            // and the island's place only move the page's edges and its glass.
            val bare = look.copy(top = 0, bottom = 0, island = Spot())
            when {
                shown.doc !== doc || shown.start != start -> view.loadUrl(Page.ORIGIN + start.first)
                shown.look != bare -> view.reload()
                shown.top != look.top || shown.bottom != look.bottom || shown.island != look.island ->
                    view.evaluateJavascript("document.documentElement.style.cssText+=';--top:${look.top}px;--bottom:${look.bottom}px;${look.island.css()}';window.mntGlass&&mntGlass()", null)
            }
            shown.doc = doc
            shown.look = bare
            shown.start = start
            shown.top = look.top
            shown.bottom = look.bottom
            shown.island = look.island
        },
        onRelease = { view ->
            finder.view = null
            sheets.view = null
            view.destroy()
        },
    )
}

private class Loaded(var doc: WebDoc? = null, var look: Look? = null, var start: Pair<String, Int>? = null, var top: Int = 0, var bottom: Int = 0, var island: Spot = Spot())

private val POLICY = mapOf("Content-Security-Policy" to Page.CSP)

private const val HOST = "mnt.local"

private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

// The app's own faces, for the pages to be set in what the rest of the app is, and Tinos for documents set in Times.
private fun font(context: Context, path: String): WebResourceResponse? {
    val id = when (path) {
        "/font/arimo.ttf" -> R.font.arimo
        "/font/mono.ttf" -> R.font.jetbrains_mono
        else -> return TINOS[path.removePrefix("/font/")]?.let { WebResourceResponse("font/woff2", null, context.resources.openRawResource(it)) }
    }
    return WebResourceResponse("font/ttf", null, context.resources.openRawResource(id))
}

private val TINOS = mapOf(
    "tinos-latin-400-normal.woff2" to R.raw.tinos_latin_400_normal,
    "tinos-latin-400-italic.woff2" to R.raw.tinos_latin_400_italic,
    "tinos-latin-700-normal.woff2" to R.raw.tinos_latin_700_normal,
    "tinos-latin-700-italic.woff2" to R.raw.tinos_latin_700_italic,
    "tinos-cyrillic-400-normal.woff2" to R.raw.tinos_cyrillic_400_normal,
    "tinos-cyrillic-400-italic.woff2" to R.raw.tinos_cyrillic_400_italic,
    "tinos-cyrillic-700-normal.woff2" to R.raw.tinos_cyrillic_700_normal,
    "tinos-cyrillic-700-italic.woff2" to R.raw.tinos_cyrillic_700_italic,
)
