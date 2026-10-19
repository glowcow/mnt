package dev.glowcow.mnt.audio

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Paths
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** What the app's player plays, what lists tracks for it, and how a track is named to the player. */
object Sounds {
    // What the player's own extractors and the phone's decoders take.
    private val plays = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "awb", "mka", "weba")
    private val lists = setOf("m3u", "m3u8", "pls")
    private val covers = listOf("cover", "folder", "front", "album", "albumart")
    private val pictures = setOf("jpg", "jpeg", "png", "webp")

    fun plays(name: String): Boolean = Paths.extension(name) in plays

    fun lists(name: String): Boolean = Paths.extension(name) in lists

    /** The picture of an album among a folder's [nodes]: `cover.jpg`, `folder.png` and the like. */
    fun cover(nodes: List<Node>): String? {
        val found = nodes.filter { !it.directory && Paths.extension(it.name) in pictures }
            .associateBy { it.name.substringBeforeLast('.').lowercase() }
        return covers.firstNotNullOfOrNull { found[it] }?.path
    }

    /** The tracks a playlist file called [name] in [folder] lists, as paths of the same places or web addresses. */
    fun parse(name: String, bytes: ByteArray, folder: String): List<String> {
        val text = decode(bytes)
        val entries = if (Paths.extension(name) == "pls") {
            text.lineSequence().map { it.trim() }
                .mapNotNull { line -> PLS.matchEntire(line)?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() } }
                .sortedBy { it.first }.map { it.second }.toList()
        } else {
            text.lineSequence().map { it.trim().removePrefix("\uFEFF") }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }
        return entries.mapNotNull { resolve(it, folder) }.filter { web(it) || plays(Paths.name(it)) }
    }

    private fun resolve(entry: String, folder: String): String? {
        if (web(entry)) return entry
        val written = if (entry.startsWith("file://")) Uri.decode(entry.removePrefix("file://")) else entry
        val slashed = written.replace('\\', '/')
        // A path of another computer's drive means nothing here.
        if (DRIVE.containsMatchIn(slashed)) return null
        val joined = if (slashed.startsWith("/")) slashed else Paths.child(folder, slashed)
        return normalize(joined)
    }

    // Steps up and dots in the middle of a path, taken out; a server's `//<id>` root stays whole.
    private fun normalize(path: String): String? {
        val remote = path.startsWith("//")
        val parts = path.removePrefix(if (remote) "//" else "/").split('/')
        val kept = ArrayList<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (kept.size > (if (remote) 1 else 0)) kept.removeAt(kept.lastIndex) else return null
                else -> kept += part
            }
        }
        return (if (remote) "//" else "/") + kept.joinToString("/")
    }

    // A playlist of Windows keeps its own code page: what is not UTF-8 is read as Cyrillic Windows.
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1251"))
    }

    fun web(id: String): Boolean = id.startsWith("http://", ignoreCase = true) || id.startsWith("https://", ignoreCase = true)

    /** A track to the player: its id is its path or web address, [cover] a picture of the folder's. */
    fun item(id: String, cover: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri(id))
        .setMediaMetadata(MediaMetadata.Builder().setArtworkUri(cover?.let(::uri)).build())
        .build()

    fun uri(id: String): Uri = if (web(id)) id.toUri() else Uri.fromParts(SCHEME, id, null)

    /** The path of a file the player reads through `mnt:`. */
    fun path(uri: Uri): String = uri.schemeSpecificPart

    /** What a track is called while its tags say nothing: its file name without the ending. */
    fun stem(id: String): String = if (web(id)) id else Paths.name(id).let { name -> name.substringBeforeLast('.').ifEmpty { name } }

    const val SCHEME = "mnt"

    private val PLS = Regex("""(?i)file(\d+)=(.*)""")
    private val DRIVE = Regex("""^[A-Za-z]:/""")
}
