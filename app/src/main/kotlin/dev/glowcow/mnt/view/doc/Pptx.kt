package dev.glowcow.mnt.view.doc

import java.io.File

/** A PowerPoint presentation of Office Open XML: slide after slide, their text, tables, pictures and notes. */
object Pptx {
    fun convert(file: File): Converted {
        val pkg = Package(file)
        try {
            val deck = pkg.xml("ppt/presentation.xml") ?: return Converted(emptyList(), closing = pkg::close)
            val rels = Rels(pkg, "ppt/presentation.xml")
            val out = StringBuilder()
            deck.find("sldIdLst")?.children("sldId").orEmpty().forEachIndexed { i, id ->
                val part = rels[id.rel()]?.target ?: return@forEachIndexed
                val slide = pkg.xml(part) ?: return@forEachIndexed
                val slideRels = Rels(pkg, part)
                out.append("<section class=\"slide\"><div class=\"no\">").append(i + 1).append("</div>")
                slide.find("spTree")?.let { shapes(it, slideRels, out) }
                slideRels.ofType("notesSlide").firstOrNull()?.let { notes ->
                    val text = pkg.xml(notes.target)?.find("spTree")?.elements
                        ?.filter { it.find("ph")?.attr("type") == "body" }
                        ?.joinToString("") { sp -> sp.child("txBody")?.let { paragraphs(it, slideRels) }.orEmpty() }
                    if (!text.isNullOrBlank()) out.append("<div class=\"muted\" style=\"font-size:13px;border-top:1px solid var(--line);margin-top:8px\">").append(text).append("</div>")
                }
                out.append("</section>")
            }
            return Converted(listOf(Converted.Part("", out.toString())), { name -> pkg.bytes(name) }, pkg::close)
        } catch (e: Exception) {
            pkg.close()
            throw e
        }
    }

    // Shapes in the order they are seen: top to bottom, then left to right.
    private fun shapes(tree: El, rels: Rels, out: StringBuilder) {
        val ordered = tree.elements.filter { it.local in setOf("sp", "pic", "graphicFrame", "grpSp") }.sortedWith(
            compareBy({ offset(it, "y") }, { offset(it, "x") }),
        )
        for (shape in ordered) {
            when (shape.local) {
                "sp" -> {
                    val type = shape.find("ph")?.attr("type")
                    val body = shape.child("txBody") ?: continue
                    val text = paragraphs(body, rels)
                    if (text.isBlank()) continue
                    if (type == "title" || type == "ctrTitle") out.append("<h2>").append(text.removePrefix("<p>").removeSuffix("</p>").replace("</p><p>", "<br>")).append("</h2>") else out.append(text)
                }
                "pic" -> {
                    val target = rels[shape.find("blip")?.attr("embed")]?.takeIf { !it.external }?.target ?: continue
                    if (drawable(target)) out.append("<p><img src=\"/a/").append(esc(target)).append("\"></p>")
                }
                "graphicFrame" -> shape.find("tbl")?.let { table(it, rels, out) }
                "grpSp" -> shapes(shape, rels, out)
            }
        }
    }

    private fun offset(shape: El, axis: String): Long = shape.find("off")?.attr(axis)?.toLongOrNull() ?: 0

    /** The paragraphs of a text body, with bullets where they are drawn. */
    internal fun paragraphs(body: El, rels: Rels): String = buildString {
        for (p in body.children("p")) {
            val inner = StringBuilder()
            for (el in p.elements) {
                when (el.local) {
                    "r" -> {
                        val text = esc(el.child("t")?.text().orEmpty())
                        if (text.isEmpty()) continue
                        val pr = el.child("rPr")
                        val link = rels[pr?.child("hlinkClick")?.rel()]?.takeIf { it.external }?.target
                        var piece = text
                        if (pr?.attr("b") == "1") piece = "<b>$piece</b>"
                        if (pr?.attr("i") == "1") piece = "<i>$piece</i>"
                        if (pr?.attr("u").let { it != null && it != "none" }) piece = "<u>$piece</u>"
                        if (link != null) piece = "<a href=\"${esc(link)}\">$piece</a>"
                        inner.append(piece)
                    }
                    "fld" -> inner.append(esc(el.child("t")?.text().orEmpty()))
                    "br" -> inner.append("<br>")
                }
            }
            if (inner.isBlank()) continue
            val level = p.child("pPr")?.attr("lvl")?.toIntOrNull() ?: 0
            val bullet = p.child("pPr")?.child("buChar")?.attr("char")
            if (bullet != null || level > 0) {
                append("<p style=\"margin:.2em 0 .2em ").append(1.2 * level + 0.4).append("em\">").append(bullet?.let { "${esc(it)} " } ?: "").append(inner).append("</p>")
            } else {
                append("<p>").append(inner).append("</p>")
            }
        }
    }

    private fun table(tbl: El, rels: Rels, out: StringBuilder) {
        out.append("<div class=\"wide\"><table>")
        for (tr in tbl.children("tr")) {
            out.append("<tr>")
            for (tc in tr.children("tc")) {
                if (tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1") continue
                out.append("<td")
                tc.attr("gridSpan")?.let { out.append(" colspan=\"").append(it).append('"') }
                tc.attr("rowSpan")?.let { out.append(" rowspan=\"").append(it).append('"') }
                out.append('>').append(tc.child("txBody")?.let { paragraphs(it, rels) }.orEmpty()).append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table></div>")
    }
}
