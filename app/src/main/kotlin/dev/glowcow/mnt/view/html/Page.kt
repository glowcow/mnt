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
    /** How much of the top and of the bottom of the page the app's bars cover, in CSS pixels. */
    val top: Int = 0,
    val bottom: Int = 0,
    /** The island at the bottom, in CSS pixels: its glass is the page's to draw. Of no width when there is none. */
    val island: Spot = Spot(),
    /** Documents with a [Paper] are laid out on it. */
    val pages: Boolean = false,
)

/** A box on the screen in CSS pixels. */
data class Spot(val x: Int = 0, val y: Int = 0, val width: Int = 0, val height: Int = 0) {
    /** The page's variables for it. */
    fun css(): String = "--ix:${x}px;--iy:${y}px;--iw:${width}px;--ih:${height}px;--iv:${if (width > 0) "visible" else "hidden"}"
}

/** A sheet a document is printed on, in points: its size and its margins. */
data class Paper(val width: Float, val height: Float, val top: Float, val right: Float, val bottom: Float, val left: Float) {
    companion object {
        val A4 = Paper(595.3f, 841.9f, 56.7f, 56.7f, 56.7f, 56.7f)
    }
}

/** Builds the pages the viewer shows: a document's body in the app's faces and colours. */
object Page {
    /** Where the pages and what they need are served from; nothing is fetched from anywhere else. */
    const val ORIGIN = "https://mnt.local"

    /** Only the app's own scripts run in a page: those that carry this. */
    val NONCE: String = java.util.UUID.randomUUID().toString().replace("-", "")

    /** The policy every page is served with: a script of a document or a book does not run. */
    val CSP = "script-src 'nonce-$NONCE'; object-src 'none'; base-uri 'none'"

    /**
     * A page of [body]; with [paper], and pages asked for, the body is laid out on sheets of it, and a [book] on sheets
     * the size of the screen.
     */
    fun html(body: String, look: Look, extra: String = "", head: String = "", paper: Paper? = null, book: Boolean = false): String = buildString {
        val paged = paper != null && look.pages || book
        append("<!doctype html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<meta name=\"color-scheme\" content=\"").append(if (look.dark) "dark" else "light").append("\">")
        append("<style>").append(css(look)).append(extra)
        if (book) append(SHEETS_CSS).append(BOOK_CSS) else if (paged) append(paperCss(paper!!))
        append("</style>").append(script(SHEETS)).append(head)
        if (paged) {
            append("</head><body class=\"paged").append(if (book) " book" else "").append("\"><div id=\"pages\"><div class=\"pg\"><div class=\"pc\" id=\"flow\">").append(body).append("</div></div></div>")
            append(script(if (book) BOOK else paginate(paper!!)))
        } else {
            append("</head><body>").append(body)
        }
        append(glass()).append("</body></html>")
    }

    /** A script of the app's own, which the page's policy lets run. */
    fun script(js: String) = "<script nonce=\"$NONCE\">$js</script>"

    /** The frosted strip under the app's header: the page's own, so the web view is drawn once. */
    fun glass() = "<div id=\"mnt-glass\"></div><div id=\"mnt-island\"></div>" + script(GLASS)

    // While the page is zoomed the strip and the island's glass are held to the screen, at the size they have unzoomed.
    private const val GLASS = "(function(){var g=document.getElementById('mnt-glass'),l=document.getElementById('mnt-island'),v=window.visualViewport;if(!v)return;" +
        "function px(n){return parseFloat(getComputedStyle(document.documentElement).getPropertyValue(n))||0}" +
        "function f(){var s=v.scale,z=s>1.01;" +
        "if(g){g.style.transform=z?'translate('+v.offsetLeft+'px,'+v.offsetTop+'px) scale('+(1/s)+')':'';g.style.width=z?(v.width*s)+'px':''}" +
        "if(l){var x=px('--ix'),y=px('--iy');l.style.transform=z?'translate('+(v.offsetLeft+x/s-x)+'px,'+(v.offsetTop+y/s-y)+'px) scale('+(1/s)+')':''}}" +
        "v.addEventListener('resize',f);v.addEventListener('scroll',f);window.mntGlass=f;f()})();"

    private fun pt(value: Float) = String.format(java.util.Locale.US, "%.2fpt", value)

    // Word's sheets: white as paper in either theme, shrunk to the screen's width, to be zoomed into; their shadow is
    // shrunk with them. Clipped, a sheet
    // wider than the screen does not widen the page while it is measured.
    private fun paperCss(paper: Paper) = buildString {
        append(SHEETS_CSS)
        append(".pg{box-sizing:border-box;width:").append(pt(paper.width)).append(";min-height:").append(pt(paper.height))
        append(";padding:").append(pt(paper.top)).append(' ').append(pt(paper.right)).append(' ').append(pt(paper.bottom)).append(' ').append(pt(paper.left))
        append(";background:#fff;color:#000;box-shadow:0 1px 5px rgba(0,0,0,.3);font:11pt/1.15 Arimo,sans-serif;")
        append("--fg:#000;--muted:#595959;--line:#8C8C8C;--block:#F2F2F2;--accent:#0B57D0}")
        append(
            """
            .pg .wide{overflow:visible}
            .pg table{margin:0}
            .pg td,.pg th{padding:0 5.4pt}
            .pg .nb td,.pg .nb th{border-color:transparent}
            .pg .pb{display:none}
            .pg [style*="--sz"]{font-size:var(--sz)}
            .pg [style*="--ff"]{font-family:var(--ff)}
            .pg [style*="--fc"]{color:var(--fc)}
            .pg [style*="--hl"]{background:var(--hl)}
            .pg [style*="--sh"]{background:var(--sh)}
            .pg [style*="--sb"]{margin:0;padding-top:var(--sb);padding-bottom:var(--sa,0)}
            .pg [style*="--lh"]{line-height:var(--lh)}
            .pg [style*="--il"]{margin-left:var(--il)!important;padding-left:0!important}
            .pg [style*="--ir"]{margin-right:var(--ir)}
            .pg [style*="--ti"]{text-indent:var(--ti)!important}
            .pg [style*="--hg"] .mk{display:inline-block;min-width:var(--hg);text-indent:0}
            .pg [style*="--cw"]{width:var(--cw)}
            .pg img[style*="--iw"]{width:var(--iw)}
            """.trimIndent(),
        )
    }

    private fun paginate(paper: Paper): String {
        val px = 4f / 3f
        val limit = (paper.height - paper.top - paper.bottom) * px
        return "addEventListener('load',function(){document.fonts.ready.then(function(){mntPaginate(${paper.width * px},$limit,true,false)})});"
    }

    // A book's sheets: as large as what the bars leave of the screen, in the app's colours, its text at the app's size.
    // A place gone to stands where a sheet's text starts: under the bar, the gap and the sheet's margin.
    private const val BOOK_CSS = "html:has(.book){scroll-padding-top:calc(var(--top) + 28px)}.book .pg{width:var(--pw);min-height:var(--ph);padding:20px 18px;background:var(--block)!important}" +
        ".book .pg img,.book .pg svg,.book .pg video{max-height:var(--lim)!important;object-fit:contain}"

    // A sheet is the screen less 8 px round it; the flow is dealt out when the pictures and the faces are in.
    private const val BOOK = "addEventListener('load',function(){document.fonts.ready.then(function(){var r=document.documentElement,cs=getComputedStyle(r);" +
        "var t=parseFloat(cs.getPropertyValue('--top'))||0,b=parseFloat(cs.getPropertyValue('--bottom'))||0,W=r.clientWidth-16,H=innerHeight-t-b-16;" +
        "r.style.setProperty('--pw',W+'px');r.style.setProperty('--ph',H+'px');r.style.setProperty('--lim',(H-40)+'px');mntPaginate(W,H-40,false,true)})});"

    /** The sheets a page is laid out on, 12 px apart and 8 px from the bars and the sides, with a shadow: Word's, a book's, a DjVu's. */
    const val SHEETS_CSS = "body.paged{padding:calc(var(--top) + 8px) 0 calc(var(--bottom) + 8px)}#pages{visibility:hidden;overflow:clip}#pages.ready{visibility:visible}" +
        ".pg{box-sizing:border-box;margin:0 auto var(--gap,12px);box-shadow:0 .5px 2.5px rgba(0,0,0,.3)}.pg:last-child{margin-bottom:0}.pc{display:flow-root}"

    // `mntPaginate` measures the flow once on a sheet and deals it out: a block that would run past the foot of a sheet
    // starts the next, a table is cut between its rows, `pb` breaks a page and `pbb` starts one; [flat] first opens
    // the boxes a book wraps its text in. `mntReport` writes the sheet at the middle of the screen into the title, where
    // the app reads it, and `mntPage` goes to a sheet.
    private const val SHEETS = """(function(){var M=window;
function inset(n){return parseFloat(getComputedStyle(document.documentElement).getPropertyValue(n))||0}
function sheets(){return document.querySelectorAll('.pg')}
var shown='';
M.mntReport=function(){var s=sheets(),n=s.length,t='';if(n>1){var mid=(inset('--top')+innerHeight-inset('--bottom'))/2,at=0;
for(var i=0;i<n;i++){if(s[i].getBoundingClientRect().top<=mid)at=i;else break}t='mnt-pages:'+(at+1)+'/'+n}
if(t!==shown){shown=t;document.title=t}};
M.mntPage=function(i){var s=sheets()[i];if(s)scrollTo(0,s.getBoundingClientRect().top+scrollY-inset('--top')-8)};
var tick=false;addEventListener('scroll',function(){if(tick)return;tick=true;requestAnimationFrame(function(){tick=false;M.mntReport()})});
addEventListener('load',function(){M.mntReport()});
M.mntPaginate=function(W,LIMIT,zoomed,flat){var pages=document.getElementById('pages'),flow=document.getElementById('flow');if(!flow)return;
if(flat){var again=true;while(again){again=false;[].slice.call(flow.children).forEach(function(k){
if(!/^(DIV|SECTION|ARTICLE|MAIN|HEADER|FOOTER|ASIDE|NAV|BODY)$/.test(k.tagName)||!k.children.length)return;
if([].some.call(k.childNodes,function(c){return c.nodeType===3&&c.textContent.trim()}))return;
if(k.id){var a=document.createElement('div');a.id=k.id;flow.insertBefore(a,k)}
while(k.firstChild)flow.insertBefore(k.firstChild,k);flow.removeChild(k);again=true})}}
var base=flow.getBoundingClientRect().top,plan=[],cur=[],y0=null;
function top(e){return e.getBoundingClientRect().top-base}function bottom(e){return e.getBoundingClientRect().bottom-base}
function cut(){if(cur.length)plan.push(cur);cur=[];y0=null}
[].slice.call(flow.children).forEach(function(k){
if(k.classList.contains('pb')){cut();return}if(k.classList.contains('pbb'))cut();
if(y0===null)y0=top(k);if(bottom(k)-y0<=LIMIT){cur.push(k);return}
var t=k.tagName=='TABLE'?k:k.querySelector(':scope>table');
if(!t||t.rows.length<2){if(cur.length){cut();y0=top(k)}cur.push(k);return}
var rows=[].slice.call(t.rows),from=0;
for(var i=0;i<rows.length;i++){if(bottom(rows[i])-y0>LIMIT&&(i>from||cur.length)){if(i>from)cur.push({w:k,t:t,rows:rows.slice(from,i)});cut();y0=top(rows[i]);from=i}}
cur.push({w:k,t:t,rows:rows.slice(from)})});
cut();if(!plan.length)plan.push([]);var out=document.createDocumentFragment();
plan.forEach(function(list){var pg=document.createElement('div'),pc=document.createElement('div');pg.className='pg';pc.className='pc';pg.appendChild(pc);
list.forEach(function(it){if(it.nodeType){pc.appendChild(it);return}
var t=it.t.cloneNode(false),b=document.createElement('tbody');t.appendChild(b);it.rows.forEach(function(r){b.appendChild(r)});
if(it.w===it.t){pc.appendChild(t)}else{var w=it.w.cloneNode(false);w.appendChild(t);pc.appendChild(w)}});out.appendChild(pg)});
pages.textContent='';pages.appendChild(out);
function fit(){var z=zoomed?Math.min(1,(document.documentElement.clientWidth-16)/W):1;pages.style.zoom=z;pages.style.setProperty('--gap',(12/z)+'px');M.mntReport()}
fit();addEventListener('resize',fit);pages.classList.add('ready')};
})();"""

    /** The faces, colours and the parts every page is made of: text, tables, code, slides, pages of a book. */
    fun css(look: Look): String = buildString {
        append("@font-face{font-family:Arimo;src:url(/font/arimo.ttf);font-weight:400 700}")
        append("@font-face{font-family:Mono;src:url(/font/mono.ttf);font-weight:100 800}")
        append(":root{")
        append("--bg:").append(hex(look.ground)).append(";--fg:").append(hex(look.text)).append(";--muted:").append(hex(look.muted))
        append(";--line:").append(hex(look.line)).append(";--block:").append(hex(look.block)).append(";--accent:").append(hex(look.accent))
        append(";--cg:").append(hex(look.codeGround)).append(";--ct:").append(hex(look.codeText)).append(";--cn:").append(hex(look.codeGutter))
        append(";--top:").append(look.top).append("px;--bottom:").append(look.bottom).append("px;").append(look.island.css())
        append("}")
        append(
            """
            html{-webkit-text-size-adjust:100%;background:var(--bg);scroll-padding-top:var(--top)}
            body{margin:0;padding:calc(var(--top) + 12px) 16px calc(var(--bottom) + 32px);background:var(--bg);color:var(--fg);font:15px/1.55 Arimo,sans-serif;overflow-wrap:break-word}
            #mnt-island{position:fixed;left:var(--ix);top:var(--iy);width:var(--iw);height:var(--ih);visibility:var(--iv);border-radius:calc(var(--ih) / 2);z-index:2147483646;pointer-events:none;transform-origin:0 0;-webkit-backdrop-filter:blur(12px);backdrop-filter:blur(12px);background:color-mix(in srgb,var(--block) 50%,transparent);box-shadow:0 6px 16px rgba(0,0,0,.22),0 0 4px rgba(0,0,0,.1)}
            #mnt-glass{position:fixed;left:0;top:0;width:100%;height:var(--top);z-index:2147483647;pointer-events:none;transform-origin:0 0;-webkit-backdrop-filter:blur(12px);backdrop-filter:blur(12px);background:color-mix(in srgb,var(--bg) 50%,transparent)}
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
            .li{margin:.2em 0 .2em calc(1.4em * var(--lv,0) + .4em);padding-left:1.4em;text-indent:-1.4em}
            .sheet thead th{top:var(--top);z-index:2}
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
        append(".file{box-sizing:border-box;border-radius:0;margin:calc(-12px - var(--top)) -16px calc(-32px - var(--bottom));min-height:100vh;padding:calc(12px + var(--top)) 0 calc(32px + var(--bottom))}")
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
