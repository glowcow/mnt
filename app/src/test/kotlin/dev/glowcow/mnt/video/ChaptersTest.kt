package dev.glowcow.mnt.video

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class ChaptersTest {
    private fun id(value: Long): ByteArray {
        val bytes = ArrayList<Byte>()
        var v = value
        while (v > 0) {
            bytes.add(0, (v and 0xFF).toByte())
            v = v shr 8
        }
        return bytes.toByteArray()
    }

    // An element with an eight-byte size, as muxers write the large ones.
    private fun el(id: Long, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        val size = ByteArray(8) { i -> (body.size.toLong() shr (8 * (7 - i)) and 0xFF).toByte() }
        size[0] = (size[0].toInt() or 0x01).toByte()
        return id(id) + size + body
    }

    private fun uint(id: Long, value: Long) = el(id, ByteArray(8) { i -> (value shr (8 * (7 - i)) and 0xFF).toByte() })

    private fun text(id: Long, value: String) = el(id, value.toByteArray())

    private fun atom(startMs: Long, vararg names: Pair<String, String>, hidden: Boolean = false) = el(
        0xB6,
        uint(0x91, startMs * 1_000_000),
        *(if (hidden) arrayOf(uint(0x98, 1)) else emptyArray()),
        *names.map { (lang, name) -> el(0x80, text(0x85, name), text(0x437C, lang)) }.toTypedArray(),
    )

    private val chapters = el(
        0x1043A770,
        el(0x45B9, atom(90_000, "eng" to "Second", "rus" to "Вторая"), atom(0, "eng" to "First"), atom(120_000, "eng" to "Hidden", hidden = true)),
    )

    private fun reader(file: ByteArray): (Long, Int) -> ByteArray = { offset, length ->
        file.copyOfRange(offset.toInt().coerceAtMost(file.size), (offset + length).toInt().coerceAtMost(file.size))
    }

    @Test fun chaptersInTheHead() {
        val file = el(0x1A45DFA3, text(0x4282, "matroska")) + el(0x18538067, el(0x1549A966, uint(0x2AD7B1, 1_000_000)), chapters, el(0x1F43B675, ByteArray(16)))
        assertEquals(listOf(Chapter(0, "First"), Chapter(90_000, "Second")), Chapters.of(reader(file), "en"))
        assertEquals("Вторая", Chapters.of(reader(file), "ru")[1].title)
    }

    @Test fun chaptersWhereTheSeekHeadSays() {
        val cluster = el(0x1F43B675, ByteArray(100_000))
        // The seek head points past the cluster, from the start of the segment's data.
        fun seekHead(position: Long) = el(0x114D9B74, el(0x4DBB, el(0x53AB, id(0x1043A770)), uint(0x53AC, position)))
        val headLength = seekHead(0).size
        val segmentBody = seekHead(headLength.toLong() + cluster.size) + cluster + chapters
        val file = el(0x1A45DFA3, text(0x4282, "matroska")) + el(0x18538067, segmentBody)
        assertEquals(2, Chapters.of(reader(file), "en").size)
    }

    @Test fun notMatroska() {
        assertEquals(emptyList<Chapter>(), Chapters.of(reader(ByteArray(100) { 7 }), "en"))
    }
}
