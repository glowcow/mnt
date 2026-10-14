package dev.glowcow.mnt.view

import dev.glowcow.mnt.view.code.CodeScheme
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
import dev.glowcow.mnt.view.html.Look
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The readers of documents, each on a sample made by LibreOffice, python-docx, openpyxl, python-pptx and pandoc. */
class DocumentsTest {
    private fun sample(name: String): File {
        val file = File.createTempFile("sample", ".${name.substringAfterLast('.')}")
        file.deleteOnExit()
        javaClass.getResourceAsStream("/samples/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun bytes(name: String) = javaClass.getResourceAsStream("/samples/$name")!!.readBytes()

    private fun html(converted: Converted) = converted.pages.joinToString("\n") { it.body }

    private fun assertHas(text: String, vararg parts: String) = parts.forEach { assertTrue("no \"$it\" in:\n$text", text.contains(it)) }

    // What the Word sample holds, whichever format it was saved in.
    private fun word(converted: Converted, picture: (String) -> Unit) {
        val text = html(converted)
        assertHas(text, "Sample document", "Первая глава", "<b>bold</b>", "<i>italic</i>", "Кириллица", "First bullet", "Step two", "Merged cell", "R3C3", "The end of the document.")
        assertTrue(Regex("<h[12][^>]*>Sample document").containsMatchIn(text))
        val src = Regex("<img src=\"/a/([^\"]+)\"").find(text)?.groupValues?.get(1)
        assertNotNull("no picture in:\n$text", src)
        picture(src!!)
        assertNotNull(converted.asset(FolderDoc.decode(src)))
    }

    @Test
    fun docx() = Docx.convert(sample("sample.docx")).use { word(it) { src -> assertTrue(src.startsWith("word/media/")) } }

    @Test
    fun odt() = Odf.text(sample("sample.odt")).use { word(it) { src -> assertTrue(src.startsWith("Pictures/")) } }

    @Test
    fun rtf() = Rtf.convert(bytes("sample.rtf")).use { word(it) {} }

    @Test
    fun doc() = Doc.convert(bytes("sample.doc")).use { word(it) {} }

    @Test
    fun docTable() = Doc.convert(bytes("sample.doc")).use {
        val text = html(it)
        assertHas(text, "<td>R2C1</td>", "<td>R2C2</td>")
    }

    // What the Excel sample holds: its visible sheets, formats, dates, merges and formulas.
    private fun excel(converted: Converted, merged: Boolean = true, worked: Boolean = true) {
        assertEquals(listOf("Data", "Second"), converted.pages.map { it.name })
        val data = converted.pages[0].body
        assertHas(data, "Alpha", "Бета", "25%", "50.0%", "2026-10-14", "7.50", "Merged across three")
        if (worked) assertHas(data, ">24<", ">15<") else assertHas(data, "=B2*2")
        if (merged) assertTrue(data.contains("colspan=\"3\""))
        assertHas(converted.pages[1].body, "row 40", "1600")
    }

    // openpyxl writes formulas without their values, as a program does and Excel does not.
    @Test
    fun xlsx() = Xlsx.convert(sample("sample.xlsx")).use { excel(it, worked = false) }

    @Test
    fun xls() = Xls.convert(bytes("sample.xls")).use { excel(it) }

    @Test
    fun ods() = Odf.spreadsheet(sample("sample.ods")).use { converted ->
        assertEquals(listOf("Data", "Second"), converted.pages.map { it.name }.filter { it != "Hidden" })
        assertHas(converted.pages[0].body, "Alpha", "Бета", "Merged across three")
    }

    // What the PowerPoint sample holds: three slides, their titles, text, table, picture and notes.
    private fun deck(converted: Converted, notes: Boolean = true, table: Boolean = true) {
        val text = html(converted)
        assertEquals(3, Regex("class=\"slide\"").findAll(text).count())
        assertHas(text, "Sample deck", "подзаголовок", "Bullets", "First point", "Second point", "Table and picture")
        if (notes) assertHas(text, "Speaker notes here")
        if (table) assertHas(text, "A1", "B2")
        assertTrue("no picture in:\n$text", text.contains("<img src=\"/a/"))
    }

    @Test
    fun pptx() = Pptx.convert(sample("sample.pptx")).use { deck(it) }

    @Test
    fun odp() = Odf.presentation(sample("sample.odp")).use { deck(it) }

    @Test
    fun ppt() = Ppt.convert(bytes("sample.ppt")).use { deck(it, notes = false, table = false) }

    @Test
    fun csv() {
        val text = String(bytes("sample.csv"))
        assertEquals(';', Csv.separator(text, tsv = false))
        val sheet = Csv.parse(text, ';')
        assertEquals("semi; colon", sheet.rows[1]!![2]!!.text)
        assertEquals("multi\nline", sheet.rows[2]!![2]!!.text)
    }

    @Test
    fun epub() = Epub(sample("sample.epub")).use { book ->
        assertTrue(book.parts.size >= 2)
        assertTrue(book.contents.any { it.first == "Chapter one" })
        assertTrue(book.contents.any { it.first == "Глава вторая" })
        val chapter = book.link(book.contents.first { it.first == "Chapter one" }.second)
        val page = book.serve(chapter.substringBefore('#'), LOOK)!!.stream.readBytes().decodeToString()
        assertHas(page, "Text of the first chapter", "--bg:#FAFAFA")
    }

    @Test
    fun markdown() {
        val html = Markdown.html("# Title\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n- [x] done\n\n```kotlin\nval x = 1\n```\n")
        assertHas(html, "<h1>Title</h1>", "<table>", "<td>2</td>", "type=\"checkbox\"", "<span class=\"k\">val</span>", "<span class=\"n\">1</span>")
    }

    private companion object {
        val LOOK = Look(0xFFFAFAFA, 0xFF111111, 0xFF666666, 0xFFE2E2E2, 0xFFFFFFFF, 0xFF1F6FEB, false, CodeScheme.LIGHT, 0xFFF1F1F1, 0xFF111111, 0xFF666666)
    }
}
