package dev.glowcow.mnt.audio

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment

/** The words of a track, line by line; [timed] when each line knows when it is sung. */
data class Lyrics(val lines: List<Line>) {
    data class Line(val at: Long?, val text: String)

    val timed: Boolean get() = lines.isNotEmpty() && lines.all { it.at != null }

    /** The line sung at [position] milliseconds; -1 before the first. */
    fun lineAt(position: Long): Int = lines.indexOfLast { (it.at ?: Long.MAX_VALUE) <= position }

    companion object {
        /** Words as tags and `.lrc` files hold them: LRC with its times, or plain lines. */
        fun parse(text: String): Lyrics? {
            var offset = 0L
            val timed = ArrayList<Line>()
            val plain = ArrayList<String>()
            for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val line = raw.trim().removePrefix("\uFEFF")
                OFFSET.matchEntire(line)?.let { offset = it.groupValues[1].toLong() }
                val stamps = STAMP.findAll(line).toList()
                if (stamps.isNotEmpty() && line.startsWith("[") && stamps.first().range.first == 0) {
                    val words = line.substring(stamps.last().range.last + 1).trim()
                    var at = 0
                    for (stamp in stamps) {
                        if (stamp.range.first != at) break
                        at = stamp.range.last + 1
                        val (m, s, f) = stamp.destructured
                        val fraction = if (f.isEmpty()) 0L else f.padEnd(3, '0').take(3).toLong()
                        timed += Line((m.toLong() * 60_000 + s.toLong() * 1000 + fraction - offset).coerceAtLeast(0), words)
                    }
                } else if (!TAG.matches(line)) {
                    plain += raw.trimEnd()
                }
            }
            if (timed.isNotEmpty()) return Lyrics(timed.sortedBy { it.at })
            val kept = plain.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            return if (kept.isEmpty()) null else Lyrics(kept.map { Line(null, it) })
        }

        /** The words a track's tags carry, as the player read them: ID3, MP4 or Vorbis comments. */
        @OptIn(UnstableApi::class)
        fun of(metadata: Metadata): String? {
            for (i in 0 until metadata.length()) {
                when (val entry = metadata.get(i)) {
                    is BinaryFrame -> if (entry.id == "USLT") uslt(entry.data)?.let { return it }
                    is TextInformationFrame -> if (entry.id == "USLT" || entry.description?.uppercase() in KEYS) entry.values.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
                    is VorbisComment -> if (entry.key.uppercase() in KEYS && entry.value.isNotBlank()) return entry.value
                }
            }
            return null
        }

        // ID3's USLT: an encoding, a language, a description ended by a zero, then the words.
        internal fun uslt(data: ByteArray): String? {
            if (data.size < 5) return null
            val charset = when (data[0].toInt()) {
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> Charsets.ISO_8859_1
            }
            val wide = data[0].toInt() == 1 || data[0].toInt() == 2
            var at = 4
            if (wide) {
                while (at + 1 < data.size && !(data[at].toInt() == 0 && data[at + 1].toInt() == 0)) at += 2
                at += 2
            } else {
                while (at < data.size && data[at].toInt() != 0) at++
                at++
            }
            if (at >= data.size) return null
            return String(data, at, data.size - at, charset).trimEnd('\u0000').takeIf { it.isNotBlank() }
        }

        private val KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "USLT")
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
        private val TAG = Regex("""\[[a-zA-Z]+:.*]""")
    }
}
