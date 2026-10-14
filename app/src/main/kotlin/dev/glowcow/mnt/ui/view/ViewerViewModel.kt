package dev.glowcow.mnt.ui.view

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glowcow.mnt.container
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.Remote
import dev.glowcow.mnt.view.ConvertedDoc
import dev.glowcow.mnt.view.DjvuDoc
import dev.glowcow.mnt.view.FolderDoc
import dev.glowcow.mnt.view.Format
import dev.glowcow.mnt.view.Texts
import dev.glowcow.mnt.view.WebDoc
import dev.glowcow.mnt.view.code.Languages
import dev.glowcow.mnt.view.code.Lexer
import dev.glowcow.mnt.view.code.Plain
import dev.glowcow.mnt.view.doc.Converted
import dev.glowcow.mnt.view.doc.Csv
import dev.glowcow.mnt.view.doc.Doc
import dev.glowcow.mnt.view.doc.Docx
import dev.glowcow.mnt.view.doc.Epub
import dev.glowcow.mnt.view.doc.Markdown
import dev.glowcow.mnt.view.doc.Odf
import dev.glowcow.mnt.view.doc.Ppt
import dev.glowcow.mnt.view.doc.Pptx
import dev.glowcow.mnt.view.doc.Rtf
import dev.glowcow.mnt.view.doc.Xls
import dev.glowcow.mnt.view.doc.Xlsx
import dev.glowcow.mnt.view.html.CodeHtml
import dev.glowcow.mnt.view.html.Page
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the viewer's page holds. */
sealed interface Shown {
    data object Loading : Shown

    /** A server's file on its way into the phone: how much of it has come. */
    data class Fetching(val fraction: Float) : Shown

    data class Failed(val reason: String?) : Shown

    /** Pages of a web view, from [start]. */
    data class Web(val doc: WebDoc, val start: Pair<String, Int>) : Shown

    data class Pdf(val uri: Uri) : Shown

    /** Not the app's to show: another app does. */
    data object Elsewhere : Shown

    /** A shortcut to a page on the web. */
    data class Link(val url: String) : Shown
}

class ViewerViewModel(val path: String, private val app: Application) : ViewModel() {
    private val c = app.container
    val name: String = Paths.name(path)
    val format: Format = Format.of(name) ?: Format.UNKNOWN
    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var shown by mutableStateOf<Shown>(Shown.Loading)
        private set

    /** Markdown, HTML and tables shown as they are written. */
    var source by mutableStateOf(false)
        private set

    /** The encoding picked by hand; null to tell it from the text. */
    var encoding by mutableStateOf<String?>(null)
        private set

    /** The encoding the text is read in. */
    var reading by mutableStateOf<String?>(null)
        private set

    /** Which part of a long text is shown, of how many. */
    var part by mutableIntStateOf(0)
        private set
    var parts by mutableIntStateOf(1)
        private set

    /** The sheet or the chapter in sight. */
    var current by mutableIntStateOf(0)
        private set

    /** Shown as code: a text, or a document shown as it is written. */
    val code: Boolean get() = format == Format.TEXT || format == Format.UNKNOWN || source && format in setOf(Format.MARKDOWN, Format.HTML, Format.TABLE)

    /** Whether the page can be shown as it is written and as it looks. */
    val twoWays: Boolean get() = format in setOf(Format.MARKDOWN, Format.HTML, Format.TABLE)

    private var node: Node? = null
    private var local: File? = null
    private var fetched: File? = null
    private var job: Job? = null
    private var navigation = 0
    private val firstLines = HashMap<Int, Int>().apply { put(0, 1) }

    init {
        load()
    }

    fun load() {
        job?.cancel()
        val previous = (shown as? Shown.Web)?.doc
        shown = Shown.Loading
        job = viewModelScope.launch {
            val next = try {
                withContext(Dispatchers.IO) { open() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Shown.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
            if (previous != null && (next as? Shown.Web)?.doc !== previous) runCatching { previous.close() }
            shown = next
        }
    }

    fun toggleSource() {
        source = !source
        load()
    }

    fun pickEncoding(name: String?) {
        encoding = name
        load()
    }

    /** Steps to the next part of a long text, or back. */
    fun step(forward: Boolean) {
        val next = part + if (forward) 1 else -1
        if (next !in 0 until parts) return
        part = next
        load()
    }

    /** Goes to [path] of the page, as a chapter picked in a book's contents. */
    fun go(path: String) {
        val web = shown as? Shown.Web ?: return
        shown = web.copy(start = path to ++navigation)
    }

    fun failed(reason: String?) {
        shown = Shown.Failed(reason)
    }

    fun setWrap(on: Boolean) = viewModelScope.launch { c.settings.setWrapLines(on) }

    fun setNumbers(on: Boolean) = viewModelScope.launch { c.settings.setLineNumbers(on) }

    /** Hears which page the web view shows. */
    fun at(path: String) {
        val web = shown as? Shown.Web ?: return
        web.doc.part(path).takeIf { it >= 0 }?.let { current = it }
    }

    private suspend fun open(): Shown {
        val found = c.files.stat(path) ?: return Shown.Failed(null)
        node = found
        return when (format) {
            // The renderer reads a PDF here and there, a server's file is fetched whole first: each jump would be a request.
            Format.PDF -> Shown.Pdf(Uri.fromFile(file(found)))
            Format.LINK -> Shown.Link(link(read(found, LINK_SIZE)) ?: return Shown.Failed(null))
            Format.TEXT, Format.UNKNOWN -> text(found)
            Format.MARKDOWN -> {
                if (source) return text(found)
                val text = decode(read(found, WHOLE))
                web(FolderDoc(c.files, path, { look -> Page.html(Markdown.html(text), look) }))
            }
            Format.HTML -> if (source) text(found) else web(FolderDoc(c.files, path, null, read(found, WHOLE)))
            Format.TABLE -> {
                if (source) return text(found)
                val text = decode(read(found, WHOLE))
                web(ConvertedDoc(Converted(listOf(Converted.Part("", Csv.html(text, Paths.extension(name) == "tsv"))))))
            }
            else -> {
                val file = file(found)
                web(
                    when (format) {
                        Format.DOCX -> ConvertedDoc(Docx.convert(file))
                        Format.XLSX -> ConvertedDoc(Xlsx.convert(file))
                        Format.PPTX -> ConvertedDoc(Pptx.convert(file))
                        Format.ODT -> ConvertedDoc(Odf.text(file))
                        Format.ODS -> ConvertedDoc(Odf.spreadsheet(file))
                        Format.ODP -> ConvertedDoc(Odf.presentation(file))
                        Format.RTF -> ConvertedDoc(Rtf.convert(file.readBytes()))
                        Format.DOC -> ConvertedDoc(Doc.convert(file.readBytes()))
                        Format.XLS -> ConvertedDoc(Xls.convert(file.readBytes()))
                        Format.PPT -> ConvertedDoc(Ppt.convert(file.readBytes()))
                        Format.EPUB -> Epub(file)
                        Format.DJVU -> DjvuDoc(file, (app.resources.displayMetrics.widthPixels * 3 / 2).coerceIn(800, 2400))
                        else -> return Shown.Elsewhere
                    },
                )
            }
        }
    }

    private fun web(doc: WebDoc): Shown {
        current = 0
        return Shown.Web(doc, doc.path(0) to ++navigation)
    }

    // A text in parts of a megabyte, each from a line's start to a line's end; numbering goes on from the part before.
    private fun text(found: Node): Shown {
        val start = part.toLong() * PART
        val bytes = c.files.read(path, start).use { read(it, PART + SLACK) }
        if (part == 0 && format == Format.UNKNOWN && !Texts.isText(bytes, minOf(bytes.size, SNIFF))) return Shown.Elsewhere
        parts = ((found.size + PART - 1) / PART).toInt().coerceAtLeast(1)
        var from = 0
        if (part > 0) from = (bytes.indexOf('\n'.code.toByte()).takeIf { it in 0 until SLACK } ?: -1) + 1
        var to = bytes.size
        if (bytes.size > PART) to = (PART until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() }?.plus(1) ?: bytes.size
        val slice = bytes.copyOfRange(from, maxOf(from, to))
        val text = decode(slice)
        firstLines[part + 1] = (firstLines[part] ?: 1) + text.count { it == '\n' }
        val lexer = lexer(text)
        val html = CodeHtml.render(text, lexer, firstLines[part] ?: 1)
        return web(ConvertedDoc(Converted(listOf(Converted.Part("", html)))))
    }

    private fun lexer(text: String): Lexer {
        if (source) return when (format) {
            Format.MARKDOWN -> Languages.byName("md")
            Format.HTML -> Languages.byName("html")
            else -> null
        } ?: Plain
        return Languages.of(name)?.lexer ?: Languages.ofScript(text.substringBefore('\n'))?.lexer ?: Plain
    }

    private fun decode(bytes: ByteArray): String {
        val charset = encoding?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: reading?.takeIf { part > 0 }?.let { Charset.forName(it) } ?: Texts.charset(bytes)
        reading = charset.name()
        return Texts.decode(bytes, bytes.size, charset)
    }

    private fun read(found: Node, limit: Int): ByteArray = c.files.read(found.path).use { read(it, limit) }

    private fun read(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // A document is read from a file of the phone: a server's file is fetched into the cache first.
    private suspend fun file(found: Node): File {
        local?.let { return it }
        if (Remote.id(path) == null) return File(path).also { local = it }
        val dir = File(app.cacheDir, "view").apply { mkdirs() }
        val target = File(dir, "${path.hashCode().toUInt()}-${name.takeLast(60)}")
        val total = found.size.coerceAtLeast(1)
        c.files.read(path).use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(256 * 1024)
                var done = 0L
                var shownAt = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (done - shownAt > total / 50) {
                        shownAt = done
                        withContext(Dispatchers.Main) { shown = Shown.Fetching(done.toFloat() / total) }
                    }
                }
            }
        }
        fetched = target
        return target.also { local = it }
    }

    override fun onCleared() {
        job?.cancel()
        (shown as? Shown.Web)?.doc?.let { runCatching { it.close() } }
        fetched?.delete()
    }

    private companion object {
        const val PART = 1024 * 1024
        const val SLACK = 64 * 1024
        const val SNIFF = 8 * 1024
        const val WHOLE = 8 * 1024 * 1024
        const val LINK_SIZE = 64 * 1024

        /** The address a `.url` or a `.webloc` shortcut leads to. */
        fun link(bytes: ByteArray): String? {
            val text = String(bytes, Charsets.UTF_8)
            Regex("(?im)^\\s*URL\\s*=\\s*(\\S+)").find(text)?.let { return it.groupValues[1] }
            return Regex("<key>URL</key>\\s*<string>([^<]+)</string>").find(text)?.groupValues?.get(1)?.trim()
        }
    }
}
