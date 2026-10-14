package dev.glowcow.mnt.view.html

import dev.glowcow.mnt.view.code.CodeScheme
import dev.glowcow.mnt.view.code.Tone

/**
 * What a page of the viewer looks like: the app's colours as `0xAARRGGBB`, those of code, and how code is laid out.
 * [code] is the scheme already resolved against the app's colours.
 */
data class Look(
    val ground: Long,
    val text: Long,
    val muted: Long,
    val line: Long,
    val block: Long,
    val accent: Long,
    val dark: Boolean,
    val code: CodeScheme,
    val codeGround: Long,
    val codeText: Long,
    val codeGutter: Long,
    val wrap: Boolean = true,
    val numbers: Boolean = true,
)

/** Builds the pages the viewer shows: a document's body in the app's faces and colours. */
object Page {
    /** Where the pages and what they need are served from; nothing is fetched from anywhere else. */
    const val ORIGIN = "https://mnt.local"

    fun html(body: String, look: Look, extra: String = "", head: String = ""): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<meta name=\"color-scheme\" content=\"").append(if (look.dark) "dark" else "light").append("\">")
        append("<style>").append(css(look)).append(extra).append("</style>").append(head)
        append("</head><body>").append(body).append("</body></html>")
    }

    /** The faces, colours and the parts every page is made of: text, tables, code, slides, pages of a book. */
    fun css(look: Look): String = buildString {
        append("@font-face{font-family:Arimo;src:url(/font/arimo.ttf);font-weight:400 700}")
        append("@font-face{font-family:Mono;src:url(/font/mono.ttf);font-weight:100 800}")
        append(":root{")
        append("--bg:").append(hex(look.ground)).append(";--fg:").append(hex(look.text)).append(";--muted:").append(hex(look.muted))
        append(";--line:").append(hex(look.line)).append(";--block:").append(hex(look.block)).append(";--accent:").append(hex(look.accent))
        append(";--cg:").append(hex(look.codeGround)).append(";--ct:").append(hex(look.codeText)).append(";--cn:").append(hex(look.codeGutter))
        append("}")
        append(
            """
            html{-webkit-text-size-adjust:100%;background:var(--bg)}
            body{margin:0;padding:12px 16px 32px;background:var(--bg);color:var(--fg);font:15px/1.55 Arimo,sans-serif;overflow-wrap:break-word}
            ::selection{background:color-mix(in srgb,var(--accent) 35%,transparent)}
            a{color:var(--accent)}
            h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.2em 0 .5em}
            h1{font-size:1.6em}h2{font-size:1.35em}h3{font-size:1.15em}h4,h5,h6{font-size:1em}
            p{margin:.6em 0}
            img{max-width:100%;height:auto}
            hr{border:0;border-top:1px solid var(--line);margin:1.5em 0}
            blockquote{margin:.8em 0;padding:0 0 0 14px;border-left:3px solid var(--line);color:var(--muted)}
            ul,ol{padding-left:1.6em}
            table{border-collapse:collapse;margin:.8em 0}
            td,th{border:1px solid var(--line);padding:6px 10px;vertical-align:top;text-align:left}
            th{background:var(--block)}
            .wide{overflow-x:auto}
            code{font-family:Mono,monospace;font-size:.88em;font-variant-ligatures:none;font-feature-settings:'liga' 0,'calt' 0;background:var(--block);padding:.1em .35em;border-radius:5px}
            mark{background:color-mix(in srgb,var(--accent) 30%,transparent);color:inherit}
            .muted{color:var(--muted)}
            .note{color:var(--muted);font-size:13px;margin:0 0 12px}
            .slide{background:var(--block);border-radius:16px;padding:12px 16px;margin:0 0 12px}
            .slide>.no{color:var(--muted);font-size:12px;margin-bottom:4px}
            .slide h2{margin:.2em 0 .4em}
            .md table{display:block;overflow-x:auto;max-width:100%}
            .md li:has(>input[type=checkbox]){list-style:none;margin-left:-1.4em}
            td>p:first-child,th>p:first-child,li>p:first-child{margin-top:0}
            td>p:last-child,th>p:last-child,li>p:last-child{margin-bottom:0}
            .md .code .r::before{content:none;counter-increment:none}
            .md .code .r{padding-left:0;text-indent:0}
            .sheet{border-collapse:separate;border-spacing:0;font-size:13px;margin:0 -16px;font-variant-numeric:tabular-nums}
            .sheet td,.sheet th{border:0;border-right:1px solid var(--line);border-bottom:1px solid var(--line);padding:4px 8px;max-width:24em;white-space:pre-wrap;vertical-align:top}
            .sheet th{position:sticky;background:var(--block);color:var(--muted);font-weight:400;text-align:center}
            .sheet thead th{top:0;z-index:2}
            .sheet tbody th{left:0;z-index:1;text-align:right}
            .sheet thead th:first-child{left:0;z-index:3}
            .sheet td.num{text-align:right}
            """.trimIndent(),
        )
        append(code(look))
    }

    /** Code: a block in the scheme's colours, numbered lines that are never selected, wrapped or scrolled sideways. */
    private fun code(look: Look): String = buildString {
        // Code shows the characters typed: `->` stays two, not an arrow.
        append(".code{background:var(--cg);color:var(--ct);font:13px/1.55 Mono,monospace;font-variant-ligatures:none;font-feature-settings:'liga' 0,'calt' 0;border-radius:12px;padding:10px 0;margin:.8em 0;counter-reset:ln var(--first,0)}")
        append(".code pre{margin:0;font:inherit;padding:0 14px}")
        append(".r{display:block}")
        append(".r::before{counter-increment:ln;content:counter(ln);display:inline-block;color:var(--cn);text-align:right;-webkit-user-select:none;user-select:none;width:var(--gw,2ch);margin-right:14px}")
        if (!look.numbers) append(".r::before{content:none;counter-increment:none}")
        if (look.wrap) {
            append(".code pre{white-space:pre-wrap;overflow-wrap:anywhere}")
            if (look.numbers) append(".r{padding-left:calc(var(--gw,2ch) + 14px);text-indent:calc(-1 * (var(--gw,2ch) + 14px))}.r::before{text-indent:0}")
        } else {
            // The number stays at the left edge while the line scrolls, its ground over what passes under it.
            append(".code pre{white-space:pre;overflow-x:auto;padding-left:0}")
            append(".r::before{position:sticky;left:0;z-index:1;background:var(--cg);padding:0 14px;margin-right:0}")
            if (!look.numbers) append(".code pre{padding-left:14px}")
        }
        // A file of code is the page itself: no frame, to the edges.
        append(".file{border-radius:0;margin:-12px -16px -32px;min-height:100vh;padding:12px 0 32px}")
        // The tones colour code only: a table's cells have classes of their own.
        look.code.tones.forEach { (tone, color) -> append(".code .").append(tone.css).append("{color:").append(hex(color)).append("}") }
        if (look.code.tones.isNotEmpty()) {
            append(".code .").append(Tone.ADDED.css).append("{background:color-mix(in srgb,currentColor 12%,transparent)}")
            append(".code .").append(Tone.REMOVED.css).append("{background:color-mix(in srgb,currentColor 12%,transparent)}")
        }
    }

    fun hex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

    fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '&' -> out.append("&amp;")
                '"' -> out.append("&quot;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
