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
        val formats = styles?.find("cellXfs")?.children("xf")?.map { it.attr("numFmtId")?.toIntOrNull() ?: 0 }.orEmpty()
        val pages = book.find("sheets")?.children("sheet").orEmpty()
            .filter { it.attr("state") != "hidden" && it.attr("state") != "veryHidden" }
            .mapNotNull { s ->
                val target = rels[s.rel()]?.target ?: return@mapNotNull null
                val sheet = Sheet(s.attr("name").orEmpty())
                pkg.stream(target)?.use { read(it, sheet, strings, formats, codes, date1904) } ?: return@mapNotNull null
                Converted.Part(sheet.name, Sheets.html(sheet))
            }
        Converted(pages)
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
                            if (raw == null && !formula.isNullOrEmpty()) sheet.put(row, column, "=$formula") else when (type) {
                                "s" -> raw?.toIntOrNull()?.let { strings.getOrNull(it) }?.let { sheet.put(row, column, it) }
                                "inlineStr" -> inline?.let { sheet.put(row, column, it.toString()) }
                                "str", "e" -> raw?.let { sheet.put(row, column, it) }
                                "b" -> raw?.let { sheet.put(row, column, if (it == "1") "TRUE" else "FALSE") }
                                else -> raw?.toDoubleOrNull()?.let {
                                    val id = formats.getOrElse(style) { 0 }
                                    sheet.put(row, column, Sheets.format(it, id, codes[id], date1904), number = true)
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
