package dev.glowcow.mnt.view

import dev.glowcow.mnt.view.code.CodeScheme
import dev.glowcow.mnt.view.doc.CellLook
import dev.glowcow.mnt.view.doc.Colors
import dev.glowcow.mnt.view.doc.Edge
import dev.glowcow.mnt.view.doc.NumberFormat
import dev.glowcow.mnt.view.doc.Sheet
import dev.glowcow.mnt.view.doc.Sheets
import dev.glowcow.mnt.view.html.Look
import dev.glowcow.mnt.view.html.Page
import dev.glowcow.mnt.view.html.Paper
import dev.glowcow.mnt.view.html.Running
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Number formats, colours and the look of a sheet as Excel draws it; headers and footers on Word's sheets. */
class SheetsTest {
    private val ru = Locale.forLanguageTag("ru")

    private fun shown(value: Double, code: String?, id: Int = 0, locale: Locale = Locale.US) = NumberFormat.format(value, id, code, locale = locale).text

    // Built-in formats follow the system's separators, as Excel shows 320 000 on a Russian mac.
    @Test
    fun builtins() {
        assertEquals("320 000", shown(320000.0, null, 3, ru))
        assertEquals("320,000", shown(320000.0, null, 3))
        assertEquals("20%", shown(0.2, null, 9))
        assertEquals("Apr-25", shown(45777.0, null, 17))
        assertEquals("1.50E+03", shown(1500.0, null, 11))
    }

    @Test
    fun custom() {
        assertEquals("1\u00A0234,50 ₽", shown(1234.5, "#,##0.00\\ [$₽-419]", locale = ru))
        assertEquals("(12)", shown(-12.0, "0;(0)"))
        assertEquals("12 kg", shown(12.0, "0\" kg\""))
        assertEquals("-", shown(0.0, "0;-0;\"-\""))
        assertEquals("1.2K", shown(1234.0, "0.0,\"K\""))
        assertEquals(0xFF0000, NumberFormat.format(-5.0, 0, "0;[Red]-0").color)
        assertEquals("14.10.2026 13:30", shown(46309.5625, "dd.mm.yyyy hh:mm"))
        assertEquals("October 2026", shown(46309.0, "mmmm yyyy"))
        assertEquals("27:00:00", shown(1.125, "[h]:mm:ss"))
    }

    // Excel's tints, as LibreOffice works them out: a fill four fifths of the way from the theme's green to white,
    // a line half as dark as white.
    @Test
    fun tints() {
        assertEquals(0xE2F0D9, Colors.tint(0x70AD47, 0.7999816888943144))
        assertEquals(0x808080, Colors.tint(0xFFFFFF, -0.499984740745262))
    }

    @Test
    fun styled() {
        val sheet = Sheet("s")
        sheet.looks = listOf(
            CellLook(),
            CellLook(bold = true, color = 0xC00000),
            CellLook(fill = 0xE2EFDA, right = Edge(3, "solid", 0xC00000)),
            CellLook(wrap = true),
        )
        sheet.widths[0] = 20f
        sheet.heights[1] = 30f
        sheet.put(0, 0, "A note that runs on over the empty cells", look = 1)
        sheet.put(0, 3, "stop")
        sheet.put(1, 0, "", look = 2)
        sheet.put(1, 1, "wrapped", look = 3)
        val html = Sheets.html(sheet)
        assertTrue(html.contains(".xl .s1{font-weight:700;color:#c00000;}"))
        assertTrue(html.contains("<td class=\"s1 sp\"><span style=\"max-width:"))
        assertTrue(html.contains("border-right:3px solid #c00000"))
        assertTrue(html.contains("<tr style=\"height:40px\">"))
        // A column is as wide as Excel keeps it, in the digits of the workbook's font.
        assertTrue(html.contains("<col style=\"width:" + Math.round(20 * 11 * 4 / 3f * 0.556f) + "px\">"))
        assertFalse(html.contains("s0"))
    }

    // A sheet of Word gets its header and footer, which `mntDress` copies onto every sheet.
    @Test
    fun running() {
        val look = Look(0xFFFAFAFA, 0xFF111111, 0xFF666666, 0xFFE2E2E2, 0xFFFFFFFF, 0xFF1F6FEB, false, CodeScheme.LIGHT, 0xFFF1F1F1, 0xFF111111, 0xFF666666, pages = true)
        val html = Page.html("<p>Body</p>", look, paper = Paper.A4, running = Running("<p><span class=\"pn\"></span></p>", "<p>(Bank)</p>", 36f, 28f))
        assertTrue(html.contains("<div class=\"hd\"><p><span class=\"pn\"></span></p></div><div class=\"ft\"><p>(Bank)</p></div>"))
        assertTrue(html.contains("window.mntDress="))
        assertTrue(html.contains("font-family:Tinos"))
    }
}
