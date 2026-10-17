package dev.glowcow.mnt.view.doc

import java.io.File
import org.xmlpull.v1.XmlPullParser

/** An Excel workbook of Office Open XML: each visible sheet a page, its numbers shown as their formats say. */
object Xlsx {
    fun convert(file: File): Converted = Package(file).use { pkg ->
        val book = pkg.xml("xl/workbook.xml") ?: return Converted(emptyList())
        val rels = Rels(pkg, "xl/workbook.xml")
        val date1904 = book.find("workbookPr")?.attr("date1904").let { it == "1" || it == "true" }
        val strings = pkg.xml("xl/sharedStrings.xml")?.children("si")?.map { si -> si.all("t").joinToString("") { it.text() } }.orEmpty()
        val styles = pkg.xml("xl/styles.xml")
        val codes = styles?.find("numFmts")?.children("numFmt")?.associate { (it.attr("numFmtId")?.toIntOrNull() ?: -1) to it.attr("formatCode").orEmpty() }.orEmpty()
        val xfs = styles?.find("cellXfs")?.children("xf").orEmpty()
        val formats = xfs.map { it.attr("numFmtId")?.toIntOrNull() ?: 0 }
        val palette = Palette(pkg.xml(rels.ofType("theme").firstOrNull()?.target ?: "xl/theme/theme1.xml"), styles)
        val fonts = styles?.find("fonts")?.children("font").orEmpty()
        val looks = looks(styles, xfs, palette)
        val fontSize = fonts.firstOrNull()?.child("sz")?.attr("val")?.toFloatOrNull() ?: 11f
        val pages = book.find("sheets")?.children("sheet").orEmpty()
            .filter { it.attr("state") != "hidden" && it.attr("state") != "veryHidden" }
            .mapNotNull { s ->
                val target = rels[s.rel()]?.target ?: return@mapNotNull null
                val sheet = Sheet(s.attr("name").orEmpty())
                sheet.looks = looks
                sheet.fontSize = fontSize
                pkg.stream(target)?.use { read(it, sheet, strings, formats, codes, date1904) } ?: return@mapNotNull null
                Converted.Part(sheet.name, Sheets.html(sheet))
            }
        Converted(pages)
    }

    /** The colours a workbook names: its theme's by number, its own palette by index, or written out. */
    private class Palette(theme: El?, styles: El?) {
        private val scheme: List<Int> = theme?.find("clrScheme")?.nodes.orEmpty().filterIsInstance<El>().map { slot ->
            (slot.child("srgbClr")?.attr("val") ?: slot.child("sysClr")?.attr("lastClr"))?.toIntOrNull(16) ?: 0
        }
        private val indexed: IntArray = styles?.find("indexedColors")?.children("rgbColor")?.mapNotNull { rgb(it.attr("rgb")) }?.toIntArray()
            ?.takeIf { it.isNotEmpty() } ?: Colors.INDEXED

        // Light 1 and dark 1, light 2 and dark 2 come in the theme the other way round.
        fun theme(i: Int): Int? = scheme.getOrNull(
            when (i) {
                0 -> 1
                1 -> 0
                2 -> 3
                3 -> 2
                else -> i
            },
        )

        /** A `<color>` element as 0xRRGGBB; null when automatic. */
        fun of(color: El?): Int? {
            color ?: return null
            if (color.attr("auto") == "1") return null
            val base = rgb(color.attr("rgb"))
                ?: color.attr("theme")?.toIntOrNull()?.let { theme(it) }
                ?: color.attr("indexed")?.toIntOrNull()?.let { Colors.indexed(it, indexed) }
                ?: return null
            return Colors.tint(base, color.attr("tint")?.toDoubleOrNull() ?: 0.0)
        }

        private fun rgb(argb: String?): Int? = argb?.takeLast(6)?.toIntOrNull(16)
    }

    // A look for every cell format: its font, its fill, its edges and its alignment.
    private fun looks(styles: El?, xfs: List<El>, palette: Palette): List<CellLook> {
        styles ?: return emptyList()
        val fonts = styles.find("fonts")?.children("font").orEmpty()
        val fills = styles.find("fills")?.children("fill").orEmpty()
        val borders = styles.find("borders")?.children("border").orEmpty()
        val baseSize = fonts.firstOrNull()?.child("sz")?.attr("val")?.toFloatOrNull()
        fun on(el: El?) = el != null && el.attr("val").let { it == null || it == "1" || it == "true" }
        return xfs.map { xf ->
            val font = fonts.getOrNull(xf.attr("fontId")?.toIntOrNull() ?: 0)
            val fill = fills.getOrNull(xf.attr("fillId")?.toIntOrNull() ?: 0)?.child("patternFill")
            val border = borders.getOrNull(xf.attr("borderId")?.toIntOrNull() ?: 0)
            val align = xf.child("alignment")
            fun edge(side: String) = border?.child(side)?.let { Edge.of(it.attr("style"), palette.of(it.child("color"))) }
            val pattern = fill?.attr("patternType")
            CellLook(
                bold = on(font?.child("b")),
                italic = on(font?.child("i")),
                underline = font?.child("u")?.attr("val") != "none" && font?.child("u") != null,
                strike = on(font?.child("strike")),
                size = font?.child("sz")?.attr("val")?.toFloatOrNull()?.takeIf { it != baseSize },
                face = face(font?.child("name")?.attr("val")),
                color = palette.of(font?.child("color")),
                // A pattern other than solid is shown as its colour; the grey of `gray125` has none.
                fill = if (pattern == null || pattern == "none") null else palette.of(fill.child("fgColor")) ?: palette.of(fill.child("bgColor")),
                left = edge("left"),
                right = edge("right"),
                top = edge("top"),
                bottom = edge("bottom"),
                align = when (align?.attr("horizontal")) {
                    "left", "fill" -> "left"
                    "center", "centerContinuous", "distributed" -> "center"
                    "right" -> "right"
                    "justify" -> "justify"
                    else -> null
                },
                valign = when (align?.attr("vertical")) {
                    "top" -> "top"
                    "center", "justify", "distributed" -> "middle"
                    else -> null
                },
                wrap = align?.attr("wrapText").let { it == "1" || it == "true" },
                indent = align?.attr("indent")?.toIntOrNull() ?: 0,
            )
        }
    }

    /** `serif` or `mono` for a font of that kind by its name; null for a sans one. */
    fun face(name: String?): String? {
        val n = name?.lowercase() ?: return null
        return when {
            listOf("courier", "consolas", "mono", "lucida console").any { it in n } -> "mono"
            listOf("times", "cambria", "georgia", "garamond", "palatino", "book antiqua", "bookman", "century schoolbook").any { it in n } ||
                n.contains("serif") && !n.contains("sans") -> "serif"
            else -> null
        }
    }

    // A sheet may be large: it is read as a stream, cell by cell.
    private fun read(input: java.io.InputStream, sheet: Sheet, strings: List<String>, formats: List<Int>, codes: Map<Int, String>, date1904: Boolean) {
        val p = Xml.parser(input)
        var row = -1
        var column = 0
        var type: String? = null
        var style = 0
        var value: StringBuilder? = null
        var inline: StringBuilder? = null
        var formula: StringBuilder? = null
        var into: StringBuilder? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "row" -> {
                        row = (p.getAttributeValue(null, "r")?.toIntOrNull() ?: (row + 2)) - 1
                        column = 0
                        p.getAttributeValue(null, "ht")?.toFloatOrNull()?.let { sheet.heights[row] = it }
                        if (p.getAttributeValue(null, "hidden").let { it == "1" || it == "true" }) sheet.hiddenRows += row
                    }
                    "sheetView" -> if (p.getAttributeValue(null, "showGridLines").let { it == "0" || it == "false" }) sheet.grid = false
                    "sheetFormatPr" -> {
                        p.getAttributeValue(null, "defaultRowHeight")?.toFloatOrNull()?.let { sheet.defaultHeight = it }
                        val base = p.getAttributeValue(null, "baseColWidth")?.toFloatOrNull()
                        sheet.defaultWidth = p.getAttributeValue(null, "defaultColWidth")?.toFloatOrNull() ?: base?.let { it + 0.71f } ?: sheet.defaultWidth
                    }
                    "col" -> {
                        val min = (p.getAttributeValue(null, "min")?.toIntOrNull() ?: 1) - 1
                        val max = (p.getAttributeValue(null, "max")?.toIntOrNull() ?: 1).coerceAtMost(Sheets.MAX_COLUMNS) - 1
                        val width = p.getAttributeValue(null, "width")?.toFloatOrNull()
                        val hidden = p.getAttributeValue(null, "hidden").let { it == "1" || it == "true" }
                        for (c in min..max) {
                            if (width != null) sheet.widths[c] = width
                            if (hidden) sheet.hiddenColumns += c
                        }
                    }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")?.let { Sheets.ref(it) }
                        if (ref != null) {
                            row = ref.first
                            column = ref.second
                        }
                        type = p.getAttributeValue(null, "t")
                        style = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                        value = null
                        inline = null
                        formula = null
                    }
                    "v" -> value = StringBuilder().also { into = it }
                    "f" -> formula = StringBuilder().also { into = it }
                    "t" -> if (type == "inlineStr") into = (inline ?: StringBuilder().also { inline = it })
                    "mergeCell" -> {
                        val ref = p.getAttributeValue(null, "ref")?.split(':')
                        val a = ref?.getOrNull(0)?.let { Sheets.ref(it) }
                        val b = ref?.getOrNull(1)?.let { Sheets.ref(it) }
                        if (a != null && b != null) sheet.merge(a.first, a.second, b.first, b.second)
                    }
                }
                XmlPullParser.TEXT -> into?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
                    "v", "t", "f" -> into = null
                    "c" -> {
                        if (column < Sheets.MAX_COLUMNS) {
                            // A formula a program wrote without working it out is shown as written.
                            val raw = value?.toString()?.takeIf { it.isNotEmpty() }
                            if (raw == null && !formula.isNullOrEmpty()) sheet.put(row, column, "=$formula", look = style) else when (type) {
                                "s" -> sheet.put(row, column, raw?.toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty(), look = style)
                                "inlineStr" -> sheet.put(row, column, inline?.toString().orEmpty(), look = style)
                                "str", "e" -> sheet.put(row, column, raw.orEmpty(), look = style)
                                "b" -> sheet.put(row, column, raw?.let { if (it == "1") "TRUE" else "FALSE" }.orEmpty(), look = style)
                                else -> {
                                    val number = raw?.toDoubleOrNull()
                                    if (number == null) {
                                        sheet.put(row, column, "", look = style)
                                    } else {
                                        val id = formats.getOrElse(style) { 0 }
                                        val shown = NumberFormat.format(number, id, codes[id], date1904)
                                        sheet.put(row, column, shown.text, number = true, look = style, color = shown.color)
                                    }
                                }
                            }
                        }
                        column++
                    }
                }
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
