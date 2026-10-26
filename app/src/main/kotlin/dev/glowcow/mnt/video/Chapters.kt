package dev.glowcow.mnt.video

import java.io.IOException

/** A chapter of a video: where it starts, milliseconds, and its title when it has one. */
data class Chapter(val start: Long, val title: String?)

/**
 * The chapters of a Matroska file (MKV, WebM), which the player does not read: from the head of the file, or from
 * where its index of the top elements says they are. [read] gives up to `length` bytes from `offset`.
 */
object Chapters {
    fun of(read: (offset: Long, length: Int) -> ByteArray, language: String): List<Chapter> = try {
        chapters(read, language)
    } catch (_: IOException) {
        emptyList()
    }

    private fun chapters(read: (Long, Int) -> ByteArray, language: String): List<Chapter> {
        val head = read(0, HEAD)
        var at = Cursor(head, 0)
        if (at.id() != EBML) return emptyList()
        at.skip(at.size())
        if (at.id() != SEGMENT) return emptyList()
        at.size()
        val segment = at.pos.toLong()
        var where: Long? = null
        // The top elements in the head: chapters may be among them, or the seek head says where they are.
        while (at.pos < head.size - 12) {
            val id = at.id()
            val size = at.size()
            when (id) {
                CHAPTERS -> return if (at.pos + size <= head.size) editions(Cursor(head, at.pos), at.pos + size.toInt(), language) else far(read, at.pos.toLong(), size, language)
                SEEK_HEAD -> where = seek(Cursor(head, at.pos), (at.pos + size).toInt().coerceAtMost(head.size))?.let { segment + it }
                CLUSTER -> break
            }
            if (size < 0 || at.pos + size > head.size) break
            at.skip(size)
        }
        val start = where ?: return emptyList()
        val top = read(start, 12)
        at = Cursor(top, 0)
        if (at.id() != CHAPTERS) return emptyList()
        val size = at.size()
        if (size <= 0 || size > MAX) return emptyList()
        return far(read, start + at.pos, size, language)
    }

    private fun far(read: (Long, Int) -> ByteArray, offset: Long, size: Long, language: String): List<Chapter> {
        if (size <= 0 || size > MAX) return emptyList()
        val bytes = read(offset, size.toInt())
        return editions(Cursor(bytes, 0), bytes.size, language)
    }

    // Where the chapters lie, from the start of the segment's data.
    private fun seek(c: Cursor, end: Int): Long? {
        while (c.pos < end - 2) {
            val id = c.id()
            val size = c.size()
            if (id != SEEK) {
                c.skip(size)
                continue
            }
            val stop = c.pos + size.toInt()
            var target = -1L
            var position: Long? = null
            while (c.pos < stop) {
                val inner = c.id()
                val length = c.size().toInt()
                when (inner) {
                    SEEK_ID -> target = c.uint(length)
                    SEEK_POSITION -> position = c.uint(length)
                    else -> c.skip(length.toLong())
                }
            }
            if (target == CHAPTERS) return position
        }
        return null
    }

    // The default edition's chapters, else the first edition's; hidden ones left out.
    private fun editions(c: Cursor, end: Int, language: String): List<Chapter> {
        val found = ArrayList<Pair<Boolean, List<Chapter>>>()
        while (c.pos < end - 2) {
            val id = c.id()
            val size = c.size()
            if (id != EDITION) {
                c.skip(size)
                continue
            }
            val stop = c.pos + size.toInt()
            var default = false
            val atoms = ArrayList<Chapter>()
            while (c.pos < stop) {
                val inner = c.id()
                val length = c.size()
                when (inner) {
                    EDITION_DEFAULT -> default = c.uint(length.toInt()) == 1L
                    ATOM -> atom(c, c.pos + length.toInt(), language)?.let { atoms += it }
                    else -> c.skip(length)
                }
            }
            found += default to atoms.sortedBy { it.start }
        }
        return (found.firstOrNull { it.first } ?: found.firstOrNull())?.second.orEmpty()
    }

    private fun atom(c: Cursor, end: Int, language: String): Chapter? {
        var start = -1L
        var hidden = false
        val names = ArrayList<Pair<String?, String>>()
        while (c.pos < end) {
            val id = c.id()
            val size = c.size()
            when (id) {
                TIME_START -> start = c.uint(size.toInt()) / 1_000_000
                HIDDEN -> hidden = c.uint(size.toInt()) == 1L
                DISPLAY -> {
                    val stop = c.pos + size.toInt()
                    var text: String? = null
                    var lang: String? = null
                    while (c.pos < stop) {
                        val inner = c.id()
                        val length = c.size().toInt()
                        when (inner) {
                            STRING -> text = c.text(length)
                            LANGUAGE, LANGUAGE_BCP47 -> lang = lang ?: c.text(length)
                            else -> c.skip(length.toLong())
                        }
                    }
                    text?.takeIf { it.isNotBlank() }?.let { names += lang to it.trim() }
                }
                else -> c.skip(size)
            }
        }
        c.pos = end
        if (start < 0 || hidden) return null
        val title = names.firstOrNull { it.first?.startsWith(language) == true }?.second ?: names.firstOrNull()?.second
        return Chapter(start, title)
    }

    /** Reads EBML ids, sizes and values from [bytes]. */
    private class Cursor(val bytes: ByteArray, var pos: Int) {
        private fun byte(): Int {
            if (pos >= bytes.size) throw IOException("past the end")
            return bytes[pos++].toInt() and 0xFF
        }

        // An id keeps its length marker.
        fun id(): Long {
            val first = byte()
            val length = Integer.numberOfLeadingZeros(first) - 23
            if (length !in 1..4) throw IOException("bad id")
            var value = first.toLong()
            repeat(length - 1) { value = value shl 8 or byte().toLong() }
            return value
        }

        // A size loses its length marker; all ones is unknown, -1.
        fun size(): Long {
            val first = byte()
            val length = Integer.numberOfLeadingZeros(first) - 23
            if (length !in 1..8) throw IOException("bad size")
            var value = (first and (0xFF shr length)).toLong()
            var ones = value == (0xFF shr length).toLong()
            repeat(length - 1) {
                val b = byte()
                if (b != 0xFF) ones = false
                value = value shl 8 or b.toLong()
            }
            return if (ones) -1 else value
        }

        fun uint(length: Int): Long {
            var value = 0L
            repeat(length) { value = value shl 8 or byte().toLong() }
            return value
        }

        fun text(length: Int): String {
            if (pos + length > bytes.size) throw IOException("past the end")
            val s = String(bytes, pos, length, Charsets.UTF_8).trimEnd('\u0000')
            pos += length
            return s
        }

        fun skip(length: Long) {
            if (length < 0) throw IOException("unknown size")
            pos += length.toInt()
        }
    }

    private const val HEAD = 64 * 1024
    private const val MAX = 1 shl 20

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CLUSTER = 0x1F43B675L
    private const val CHAPTERS = 0x1043A770L
    private const val EDITION = 0x45B9L
    private const val EDITION_DEFAULT = 0x45DBL
    private const val ATOM = 0xB6L
    private const val TIME_START = 0x91L
    private const val HIDDEN = 0x98L
    private const val DISPLAY = 0x80L
    private const val STRING = 0x85L
    private const val LANGUAGE = 0x437CL
    private const val LANGUAGE_BCP47 = 0x437DL
}
