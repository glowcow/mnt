package dev.glowcow.mnt.video

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import dev.glowcow.mnt.audio.Sounds
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Paths

/** What the app's video player plays, the subtitles that lie beside a video, and what its name says. */
object Videos {
    // What the player's extractors take; ASF (WMV) and RealMedia have none.
    private val plays = setOf("mkv", "mp4", "m4v", "webm", "mov", "avi", "ts", "m2ts", "mts", "flv", "3gp", "mpg", "mpeg", "vob", "ogv")

    // Subtitles beside a video, by their ending, as the player names their kind.
    private val subtitles = mapOf(
        "srt" to MimeTypes.APPLICATION_SUBRIP,
        "ass" to MimeTypes.TEXT_SSA,
        "ssa" to MimeTypes.TEXT_SSA,
        "vtt" to MimeTypes.TEXT_VTT,
        "ttml" to MimeTypes.APPLICATION_TTML,
    )

    fun plays(name: String): Boolean = Paths.extension(name) in plays

    /** A video to the player with the subtitles among [nodes] named after it: `Film.srt`, `Film.en.srt`, `Film.Forced.ru.ass`. */
    fun item(video: Node, nodes: List<Node>): MediaItem {
        val stem = stem(video.name)
        val beside = nodes.filter { !it.directory && Paths.extension(it.name) in subtitles }.mapNotNull { node ->
            val rest = node.name.substringBeforeLast('.')
            when {
                rest.equals(stem, ignoreCase = true) -> node to ""
                rest.startsWith("$stem.", ignoreCase = true) -> node to rest.substring(stem.length + 1)
                else -> null
            }
        }
        return MediaItem.Builder()
            .setMediaId(video.path)
            .setUri(Sounds.uri(video.path))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(stem).build())
            .setSubtitleConfigurations(
                beside.map { (node, tags) ->
                    val parts = tags.split('.').filter { it.isNotEmpty() }
                    val language = parts.lastOrNull { LANGUAGE.matches(it) }
                    MediaItem.SubtitleConfiguration.Builder(Sounds.uri(node.path))
                        .setMimeType(subtitles.getValue(Paths.extension(node.name)))
                        .setLanguage(language?.lowercase())
                        .setLabel(node.name)
                        .setSelectionFlags(if (parts.any { it.equals("forced", ignoreCase = true) }) C.SELECTION_FLAG_FORCED else 0)
                        .setId(node.path)
                        .build()
                },
            )
            .build()
    }

    fun stem(name: String): String = name.substringBeforeLast('.').ifEmpty { name }

    /** What the name of a video says: a title to show, and the season and the episode when it names them. */
    data class Named(val title: String, val season: Int?, val episode: Int?)

    fun named(name: String): Named {
        val stem = stem(name)
        // A name of the scene has dots for spaces: `Show.Name.S01E07.1080p.WEB-DL`.
        val spaced = if (' ' !in stem && stem.count { it == '.' } >= 2) stem.replace('.', ' ').replace('_', ' ') else stem
        val found = EPISODE.find(spaced) ?: return Named(clean(spaced), null, null)
        val season = found.groupValues[1].toInt()
        val episode = found.groupValues[2].toInt()
        // `Show - S01E07 - Title`: what follows names the episode; otherwise the show is what comes before.
        val after = spaced.substring(found.range.last + 1).trim().removePrefix("-").trim()
        val before = spaced.substring(0, found.range.first).trim().removeSuffix("-").trim()
        val title = clean(after).takeIf { it.isNotEmpty() } ?: clean(before).ifEmpty { spaced }
        return Named(title, season, episode)
    }

    // What a release adds to a name — the year before its tags, the tags of quality and source — is cut off.
    private fun clean(text: String): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        var end = words.indexOfFirst { SCENE.matches(it) }
        if (end < 0) end = words.size
        if (end > 1 && YEAR.matches(words[end - 1])) end--
        return words.take(end).joinToString(" ").trim().removeSuffix("-").trim()
    }

    /** Where the video at [path] belongs: its folder, or the show above a folder of a season (`Show/Season 01`). */
    fun place(path: String): String? {
        val folder = Paths.parent(path)
        val name = Paths.name(folder)
        val above = Paths.name(Paths.parent(folder))
        return (if (SEASON.matches(name) && above.isNotEmpty()) above else name).takeIf { it.isNotEmpty() }
    }

    /** `4K`, `HD` or `SD` for a picture of [width] by [height]. */
    fun quality(width: Int, height: Int): String? = when {
        width <= 0 || height <= 0 -> null
        width >= 3200 || height >= 2000 -> "4K"
        width >= 1200 || height >= 700 -> "HD"
        else -> "SD"
    }

    private val EPISODE = Regex("""(?i)\bS(\d{1,2})\s?E(\d{1,3})\b""")
    private val SCENE = Regex("""(?i)(\d{3,4}p|uhd|webrip|web-dl|bdrip|bdremux|remux|bluray|hdtv|hdr|dovi|x26[45]|h\.?26[45]|hevc|avc|dd\+?\d?|ddp\d?|aac|dts|nf|amzn|atvp|rus|eng|proper|repack)([-.].*)?""")
    private val YEAR = Regex("""\(?(19|20)\d\d\)?""")
    private val SEASON = Regex("""(?iu)(season|сезон|staffel|saison|temporada|stagione|sezon)?\s*\d{1,2}|s\d{1,2}""")
    private val LANGUAGE = Regex("""(?i)[a-z]{2,3}""")
}
