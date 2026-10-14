package dev.glowcow.mnt.ui.view

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import dev.glowcow.mnt.R
import dev.glowcow.mnt.ui.components.DetailPage
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.components.IconButton48
import dev.glowcow.mnt.ui.components.ProgressLine
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme

/** A web page opened from a link, inside the app: the way back through its pages, reload, and the way out to the phone's browser. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(url: String, onClose: () -> Unit) {
    val c = MntTheme.colors
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf(url) }
    var progress by remember { mutableFloatStateOf(0f) }
    var back by remember { mutableStateOf(false) }
    var scrolled by remember { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = back) { web?.goBack() }

    DetailPage(
        ground = c.groupBg,
        scrolled = scrolled,
        glass = false,
        header = {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton48(MntIcons.Close, stringResource(R.string.action_close), onClick = onClose)
                Column(Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title.ifBlank { host(address) }, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(host(address), color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton48(MntIcons.Reload, stringResource(R.string.browser_reload)) { web?.reload() }
                IconButton48(MntIcons.More, stringResource(R.string.action_more)) { more = true }
            }
            // How far the page has come; a line no thicker than the one under a header.
            if (progress in 0.01f..0.99f) ProgressLine(progress, Modifier.padding(horizontal = 16.dp).height(2.dp)) else androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
        },
    ) { top, bottom ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(top = top, bottom = bottom),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(c.groupBg.toArgb())
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        builtInZoomControls = true
                        displayZoomControls = false
                        setSupportMultipleWindows(false)
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    setOnScrollChangeListener { _, _, y, _, _ -> scrolled = y > 0 }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress / 100f
                        }

                        override fun onReceivedTitle(view: WebView, text: String?) {
                            title = text.orEmpty()
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        // A page of the web stays here; any other kind of address is another app's.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme?.lowercase()
                            if (scheme == "http" || scheme == "https") return false
                            outside(context, request.url)
                            return true
                        }

                        override fun onPageStarted(view: WebView, page: String, favicon: Bitmap?) {
                            address = page
                        }

                        override fun doUpdateVisitedHistory(view: WebView, page: String, reload: Boolean) {
                            address = page
                            back = view.canGoBack()
                        }
                    }
                    setDownloadListener { link, _, _, _, _ -> outside(context, link.toUri()) }
                    loadUrl(url)
                    web = this
                }
            },
            onRelease = {
                web = null
                it.destroy()
            },
        )
    }

    if (more) {
        GroupSheet(host(address), onDismiss = { more = false }) { pick ->
            Group {
                GroupRow(stringResource(R.string.browser_open_outside), icon = MntIcons.External, onClick = { pick { outside(context, address.toUri()) } }, trailing = {})
                GroupDivider()
                GroupRow(stringResource(R.string.browser_copy_link), icon = MntIcons.Link, onClick = {
                    pick { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newRawUri(title, address.toUri())) }
                }, trailing = {})
                GroupDivider()
                GroupRow(stringResource(R.string.action_share), icon = MntIcons.Share, onClick = {
                    pick {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address)
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    }
                }, trailing = {})
            }
        }
    }
}

private fun host(url: String): String = Uri.parse(url).host?.removePrefix("www.") ?: url

/** Hands an address to whatever app takes it: the phone's browser, mail, a call. */
fun outside(context: Context, uri: Uri): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
