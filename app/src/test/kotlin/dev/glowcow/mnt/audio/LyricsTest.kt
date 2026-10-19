package dev.glowcow.mnt.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test fun lrcWithTimesTagsAndOffset() {
        val lyrics = Lyrics.parse("[ar:Someone]\n[offset:+500]\n[00:12.50]Second\n[00:01.00][00:30.00]Both\n\n[01:02.3]Late\n")!!
        assertTrue(lyrics.timed)
        assertEquals(listOf(500L, 12_000L, 29_500L, 61_800L), lyrics.lines.map { it.at })
        assertEquals(listOf("Both", "Second", "Both", "Late"), lyrics.lines.map { it.text })
        assertEquals(-1, lyrics.lineAt(100))
        assertEquals(1, lyrics.lineAt(20_000))
        assertEquals(3, lyrics.lineAt(90_000))
    }

    @Test fun plainWordsKeepTheirLines() {
        val lyrics = Lyrics.parse("\r\nFirst\r\n\r\nSecond\r\n\r\n")!!
        assertFalse(lyrics.timed)
        assertEquals(listOf("First", "", "Second"), lyrics.lines.map { it.text })
        assertNull(Lyrics.parse("  \n\n"))
    }

    @Test fun usltOfEachEncoding() {
        val latin = byteArrayOf(0) + "eng".toByteArray() + "desc".toByteArray() + 0 + "Words".toByteArray()
        assertEquals("Words", Lyrics.uslt(latin))
        val utf16 = byteArrayOf(1) + "eng".toByteArray() + "\uFEFFd".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0) + "\uFEFFСлова".toByteArray(Charsets.UTF_16LE)
        assertEquals("Слова", Lyrics.uslt(utf16))
        val utf8 = byteArrayOf(3) + "rus".toByteArray() + byteArrayOf(0) + "Слова".toByteArray()
        assertEquals("Слова", Lyrics.uslt(utf8))
        assertNull(Lyrics.uslt(byteArrayOf(0, 1)))
    }
}
