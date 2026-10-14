package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.doc.Cfb.Companion.u16
import dev.glowcow.mnt.view.doc.Cfb.Companion.u32
import java.io.IOException

/** A PowerPoint 97–2003 presentation: slide after slide, the text of its placeholders and text boxes, and its pictures. */
object Ppt {
    private class Rec(val instance: Int, val type: Int, val start: Int, val end: Int, val container: Boolean)

    private fun rec(b: ByteArray, at: Int): Rec? {
        if (at + 8 > b.size || at < 0) return null
        val verInst = u16(b, at)
        val length = u32(b, at + 4)
        if (length < 0) return null
        return Rec(verInst ushr 4, u16(b, at + 2), at + 8, minOf(b.size, at + 8 + length), verInst and 0x0F == 0x0F)
    }

    private fun children(b: ByteArray, parent: Rec): List<Rec> {
        val out = ArrayList<Rec>()
        var at = parent.start
        while (at < parent.end) {
            val r = rec(b, at) ?: break
            out += r
            if (r.end <= at) break
            at = r.end
        }
        return out
    }

    private fun walk(b: ByteArray, parent: Rec, each: (Rec) -> Unit) {
        for (r in children(b, parent)) {
            each(r)
            if (r.container) walk(b, r, each)
        }
    }

    fun convert(bytes: ByteArray): Converted {
        val cfb = Cfb(bytes)
        val doc = cfb.stream("PowerPoint Document") ?: throw IOException("No PowerPoint Document stream")
        val pics = cfb.stream("Pictures")
        val user = cfb.stream("Current User")
        // The persist directory says where each object lies; the newest edit's word counts.
        val persist = HashMap<Int, Int>()
        var edit = user?.let { u32(it, 16) } ?: -1
        var docRef = -1
        var guard = 0
        while (edit in 0 until doc.size && guard++ < 100) {
            val ue = rec(doc, edit) ?: break
            if (ue.type != 0x0FF5) break
            if (docRef < 0) docRef = u32(doc, ue.start + 16)
            val dir = rec(doc, u32(doc, ue.start + 12)) ?: break
            var at = dir.start
            while (at + 4 <= dir.end) {
                val head = u32(doc, at)
                val first = head and 0xFFFFF
                val count = head ushr 20
                for (k in 0 until count) persist.putIfAbsent(first + k, u32(doc, at + 4 + k * 4))
                at += 4 + count * 4
            }
            val previous = u32(doc, ue.start + 8)
            if (previous <= 0 || previous == edit) break
            edit = previous
        }
        val document = persist[docRef]?.let { rec(doc, it) } ?: scan(doc, 0x03E8) ?: throw IOException("No document record")

        // Pictures are kept in one store and named by their place in it, from 1.
        val blips = ArrayList<ByteArray?>()
        walk(doc, document) { r ->
            if (r.type != 0xF007) return@walk
            val delay = u32(doc, r.start + 28)
            val name = doc[r.start + 33].toInt() and 0xFF
            blips += if (delay == -1 || pics == null) image(doc, r.start + 36 + name, r.end) else rec(pics, delay)?.let { image(pics, it.start, it.end) }
        }

        val out = StringBuilder()
        val pictures = ArrayList<ByteArray>()
        val list = children(doc, document).firstOrNull { it.type == 0x0FF0 && it.instance == 0 }
        var number = 0
        var texts = ArrayList<Pair<Int, String>>()
        var slideRef = -1
        fun flush() {
            if (slideRef < 0 && texts.isEmpty()) return
            number++
            out.append("<section class=\"slide\"><div class=\"no\">").append(number).append("</div>")
            texts.forEach { (type, text) -> paragraph(out, type, text) }
            persist[slideRef]?.let { rec(doc, it) }?.let { slide ->
                walk(doc, slide) { r ->
                    when (r.type) {
                        // Text in boxes of its own; a box that refers to the list's text was shown already.
                        0xF00D -> {
                            var type = 4
                            for (c in children(doc, r)) when (c.type) {
                                0x0F9F -> type = u32(doc, c.start)
                                0x0FA0, 0x0FA8 -> paragraph(out, type, text(doc, c))
                            }
                        }
                        0xF00B -> {
                            var at = r.start
                            for (k in 0 until r.instance) {
                                val opid = u16(doc, at)
                                if (opid and 0x3FFF == 0x0104) blips.getOrNull(u32(doc, at + 2) - 1)?.let {
                                    pictures += it
                                    out.append("<p><img src=\"/a/pic").append(pictures.size - 1).append("\"></p>")
                                }
                                at += 6
                            }
                        }
                    }
                }
            }
            out.append("</section>")
            texts = ArrayList()
        }
        if (list != null) {
            var type = 4
            for (r in children(doc, list)) {
                when (r.type) {
                    0x03F3 -> {
                        if (slideRef >= 0 || texts.isNotEmpty()) flush()
                        slideRef = u32(doc, r.start)
                    }
                    0x0F9F -> type = u32(doc, r.start)
                    0x0FA0, 0x0FA8 -> texts += type to text(doc, r)
                }
            }
            flush()
        }
        return Converted(listOf(Converted.Part("", out.toString())), { name -> name.removePrefix("pic").toIntOrNull()?.let { pictures.getOrNull(it) } })
    }

    private fun scan(b: ByteArray, type: Int): Rec? {
        var at = 0
        while (at + 8 <= b.size) {
            val r = rec(b, at) ?: return null
            if (r.type == type) return r
            at = r.end
        }
        return null
    }

    private fun text(b: ByteArray, r: Rec): String =
        if (r.type == 0x0FA0) String(b, r.start, r.end - r.start, Charsets.UTF_16LE) else String(b, r.start, r.end - r.start, Charsets.ISO_8859_1)

    // Title and centred title are headings; other text keeps its lines as paragraphs.
    private fun paragraph(out: StringBuilder, type: Int, text: String) {
        val clean = text.replace('\u000B', '\n').trim()
        if (clean.isEmpty()) return
        if (type == 0 || type == 6) {
            out.append("<h2>").append(esc(clean).replace("\r", "<br>").replace("\n", "<br>")).append("</h2>")
            return
        }
        clean.split('\r', '\n').filter { it.isNotBlank() }.forEach { out.append("<p>").append(esc(it)).append("</p>") }
    }

    // An image inside a blip record: from its PNG or JPEG signature to the end of the record.
    private fun image(b: ByteArray, from: Int, to: Int): ByteArray? {
        var k = from
        val end = minOf(to, b.size)
        while (k < end - 8) {
            val v = b[k].toInt() and 0xFF
            if (v == 0x89 && b[k + 1] == 'P'.code.toByte() && b[k + 2] == 'N'.code.toByte() && b[k + 3] == 'G'.code.toByte()) return b.copyOfRange(k, end)
            if (v == 0xFF && (b[k + 1].toInt() and 0xFF) == 0xD8 && (b[k + 2].toInt() and 0xFF) == 0xFF) return b.copyOfRange(k, end)
            k++
        }
        return null
    }
}
