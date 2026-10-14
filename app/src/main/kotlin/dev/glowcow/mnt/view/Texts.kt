package dev.glowcow.mnt.view

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Text as it lies in a file: whether it is text at all and in which encoding. */
object Texts {
    /** The encodings one may pick by hand, by their names in Java. */
    val choices = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "windows-1251", "KOI8-R", "IBM866", "windows-1252", "ISO-8859-1", "Shift_JIS", "GB18030")

    /** True when [head], the start of a file, reads as text: no zero bytes outside UTF-16, few control characters. */
    fun isText(head: ByteArray, size: Int = head.size): Boolean {
        if (size == 0) return true
        if (bom(head, size) != null) return true
        var control = 0
        for (i in 0 until size) {
            val b = head[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x20 && b != 9 && b != 10 && b != 13 && b != 12 && b != 27) control++
        }
        return control * 20 < size
    }

    /** The encoding [bytes] are written in: a byte order mark, else UTF-8 when they decode as it, else a guess between Cyrillic and Western. */
    fun charset(bytes: ByteArray, size: Int = bytes.size): Charset {
        bom(bytes, size)?.let { return it.first }
        if (utf8(bytes, size)) return Charsets.UTF_8
        // Letters of Windows-1251 Cyrillic fill 0xC0–0xFF; Western text has few bytes there and many 0x80–0xBF of UTF-8 gone wrong.
        var high = 0
        var cyrillic = 0
        for (i in 0 until size) {
            val b = bytes[i].toInt() and 0xFF
            if (b >= 0x80) high++
            if (b >= 0xC0) cyrillic++
        }
        return Charset.forName(if (high > 0 && cyrillic * 10 >= high * 7) "windows-1251" else "windows-1252")
    }

    /** [size] bytes as text in [charset], without a byte order mark. */
    fun decode(bytes: ByteArray, size: Int, charset: Charset): String {
        val skip = bom(bytes, size)?.second ?: 0
        return String(bytes, skip, size - skip, charset)
    }

    private fun bom(b: ByteArray, size: Int): Pair<Charset, Int>? = when {
        size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> null
    }

    // A character cut at the end of what was read is not a fault of the text.
    private fun utf8(bytes: ByteArray, size: Int): Boolean {
        var end = size
        var back = 0
        while (back < 3 && end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) {
            end--
            back++
        }
        if (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0xC0) end--
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, end))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }
}
