package dev.glowcow.mnt.view

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import dev.glowcow.mnt.view.html.Look
import dev.glowcow.mnt.view.html.Page
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException

/** A DjVu document read by DjVuLibre: its pages' sizes, their pictures and the words of their hidden text. */
class Djvu private constructor(private var handle: Long) : Closeable {
    /** A word and its box on the page, from the bottom left, in the page's own pixels. */
    class Word(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val text: String)

    val pages: Int = pages(handle)

    /** Width, height and resolution of [page]; null when it cannot be read. */
    @Synchronized
    fun size(page: Int): IntArray? = if (handle == 0L) null else size(handle, page)

    /** [page] drawn [width] pixels wide. */
    @Synchronized
    fun render(page: Int, width: Int): Bitmap? {
        val size = size(page) ?: return null
        val height = (size[1].toLong() * width / size[0].coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        bitmap.eraseColor(Color.WHITE)
        if (!render(handle, page, bitmap)) bitmap.eraseColor(Color.WHITE)
        return bitmap
    }

    @Synchronized
    fun words(page: Int): List<Word> {
        if (handle == 0L) return emptyList()
        return String(text(handle, page), Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val parts = line.split(' ', limit = 5)
            if (parts.size < 5) return@mapNotNull null
            Word(parts[0].toIntOrNull() ?: return@mapNotNull null, parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4])
        }.toList()
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) close(handle)
        handle = 0
    }

    companion object {
        init {
            System.loadLibrary("mntdjvu")
        }

        fun open(file: File): Djvu = open(file.path).takeIf { it != 0L }?.let { Djvu(it) } ?: throw IOException("Not a DjVu document")

        @JvmStatic private external fun open(path: String): Long

        @JvmStatic private external fun pages(handle: Long): Int

        @JvmStatic private external fun size(handle: Long, page: Int): IntArray?

        @JvmStatic private external fun render(handle: Long, page: Int, bitmap: Bitmap): Boolean

        @JvmStatic private external fun text(handle: Long, page: Int): ByteArray

        @JvmStatic private external fun close(handle: Long)
    }
}

/**
 * A DjVu as one page of pictures, each drawn [width] pixels wide when it comes into sight, with its words laid
 * over it unseen, so that they can be selected, copied and found.
 */
class DjvuDoc(file: File, private val width: Int) : WebDoc() {
    private val djvu = Djvu.open(file)
    private val sizes = (0 until djvu.pages).map { djvu.size(it) ?: intArrayOf(1, 1, 300) }

    override fun path(part: Int) = "/doc/0"

    override val scripted: Boolean get() = true

    override fun serve(path: String, look: Look): Served? = when {
        path == "/doc/0" -> Served("text/html", page(look).byteInputStream())
        path.startsWith("/p/") -> path.removePrefix("/p/").substringBefore('.').toIntOrNull()?.let { n ->
            val bitmap = djvu.render(n, width) ?: return null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            Served("image/jpeg", out.toByteArray().inputStream())
        }
        path.startsWith("/t/") -> path.removePrefix("/t/").toIntOrNull()?.let { n -> Served("text/html", words(n).byteInputStream()) }
        else -> null
    }

    // Each word in a box of its own, as a share of the page; the script stretches it to its box.
    private fun words(page: Int): String {
        val (w, h) = sizes[page].let { it[0].toDouble().coerceAtLeast(1.0) to it[1].toDouble().coerceAtLeast(1.0) }
        return buildString {
            for (word in djvu.words(page)) {
                val height = (word.y1 - word.y0).coerceAtLeast(1)
                append("<span style=\"left:").append(share(word.x0 * 100 / w)).append("%;top:").append(share((h - word.y1) * 100 / h))
                append("%;font-size:").append(share(height * 100 / w)).append("cqw\" data-w=\"").append(share((word.x1 - word.x0) * 100 / w)).append("\">")
                append(Page.escape(word.text)).append(' ').append("</span>")
            }
        }
    }

    private fun page(look: Look): String {
        val body = buildString {
            sizes.forEachIndexed { i, s ->
                append("<div class=\"pg\" style=\"aspect-ratio:").append(s[0]).append('/').append(s[1]).append("\">")
                append("<img loading=\"lazy\" src=\"/p/").append(i).append(".jpg\" alt=\"\"><div class=\"tx\" data-p=\"").append(i).append("\"></div></div>")
            }
        }
        val css = "body{padding:8px 8px 32px}.pg{position:relative;margin:0 auto 8px;background:#fff;container-type:inline-size;width:100%;box-shadow:0 0 0 1px var(--line)}" +
            ".pg img{display:block;width:100%;height:100%}.tx{position:absolute;inset:0;overflow:hidden}" +
            ".tx span{position:absolute;color:transparent;white-space:pre;line-height:1;transform-origin:0 0}"
        return Page.html(body, look, css, "<script>$SCRIPT</script>")
    }

    override fun close() = djvu.close()

    // A share written for CSS, with a point whatever the phone's language.
    private fun share(value: Double) = String.format(java.util.Locale.US, "%.3f", value)

    private companion object {
        const val JPEG_QUALITY = 88

        // Asks for a page's words as it comes near the screen and stretches each to the width of its box.
        const val SCRIPT = """
            addEventListener('DOMContentLoaded', () => {
              const seen = new IntersectionObserver(entries => entries.forEach(e => {
                if (!e.isIntersecting) return;
                seen.unobserve(e.target);
                const layer = e.target.querySelector('.tx');
                fetch('/t/' + layer.dataset.p).then(r => r.text()).then(html => {
                  layer.innerHTML = html;
                  const full = layer.offsetWidth;
                  layer.querySelectorAll('span').forEach(s => {
                    const w = s.offsetWidth;
                    if (w > 0) s.style.transform = 'scaleX(' + (parseFloat(s.dataset.w) * full / 100 / w) + ')';
                  });
                });
              }), {rootMargin: '150% 0px'});
              document.querySelectorAll('.pg').forEach(p => seen.observe(p));
            });
        """
    }
}
