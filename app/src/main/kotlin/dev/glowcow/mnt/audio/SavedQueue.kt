package dev.glowcow.mnt.audio

import androidx.media3.common.Player
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The queue as it stood, kept so the player comes back to it after the app was closed. */
@Serializable
data class SavedQueue(
    val items: List<SavedTrack>,
    val index: Int,
    val position: Long,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
) {
    fun write(file: File) {
        val part = File(file.path + ".part")
        part.writeText(Json.encodeToString(this))
        part.renameTo(file)
    }

    companion object {
        fun read(file: File): SavedQueue? = runCatching { Json.decodeFromString<SavedQueue>(file.readText()) }.getOrNull()?.takeIf { it.items.isNotEmpty() }

        fun of(player: Player): SavedQueue? {
            if (player.mediaItemCount == 0) return null
            val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }.map {
                SavedTrack(it.mediaId, it.mediaMetadata.artworkUri?.takeIf { uri -> uri.scheme == Sounds.SCHEME }?.let(Sounds::path))
            }
            return SavedQueue(items, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode)
        }
    }
}

@Serializable
data class SavedTrack(val id: String, val cover: String? = null)
