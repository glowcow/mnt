package dev.glowcow.mnt.view.html

import dev.glowcow.mnt.view.code.Lexer
import dev.glowcow.mnt.view.code.Span

/** Code as HTML: each line a row of its own, its stretches in the classes of their tones. */
object CodeHtml {
    /**
     * [first] is the number of the first line; a [file] fills the page, a block of code sits in a document.
     * The line breaks stay in the text, so what is copied is the code as it was.
     */
    fun render(text: String, lexer: Lexer, first: Int = 1, file: Boolean = true): String {
        val spans = runCatching { lexer.lex(text) }.getOrDefault(emptyList())
        val lines = text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
        val digits = (first + lines - 1).coerceAtLeast(1).toString().length
        val out = StringBuilder(text.length * 2 + 256)
        out.append("<div class=\"code").append(if (file) " file" else "").append("\" style=\"--first:").append(first - 1).append(";--gw:").append(digits).append("ch\"><pre>")
        var at = 0
        var next = 0
        var open: Span? = null
        out.append("<span class=\"r\">")
        // Walks the text once; a stretch that runs over a line break is closed before it and opened again after.
        while (at < text.length) {
            if (open == null && next < spans.size && spans[next].start == at) {
                open = spans[next++]
                out.append("<span class=\"").append(open.tone.css).append("\">")
            }
            val c = text[at]
            if (c == '\n') {
                if (open != null) out.append("</span>")
                out.append("\n</span>")
                if (at + 1 < text.length) {
                    out.append("<span class=\"r\">")
                    if (open != null && open.end > at + 1) out.append("<span class=\"").append(open.tone.css).append("\">") else open = null
                }
            } else {
                when (c) {
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    '&' -> out.append("&amp;")
                    '\r' -> Unit
                    else -> out.append(c)
                }
            }
            at++
            if (open != null && open.end == at) {
                if (c != '\n') out.append("</span>")
                open = null
            }
            // A span that starts inside another (it cannot here) or that was skipped is dropped.
            while (next < spans.size && spans[next].start < at && open == null) next++
        }
        if (open != null) out.append("</span>")
        if (!text.endsWith('\n')) out.append("\n</span>")
        out.append("</pre></div>")
        return out.toString()
    }
}
