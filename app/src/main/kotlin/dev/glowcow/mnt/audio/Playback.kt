package dev.glowcow.mnt.audio

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.glowcow.mnt.R
import dev.glowcow.mnt.data.SettingsStore
import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Listing
import dev.glowcow.mnt.files.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException

/** The screens' side of the player: a controller of [PlayerService]'s session, and what it plays as state to draw. */
class Playback(private val context: Context, private val files: FileStore, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private var controller: MediaController? = null
    private var connecting = false
    private val waiting = ArrayList<(MediaController) -> Unit>()

    val tags = Tags(files)
    val waves = Waves(files, File(context.cacheDir, "waves"))
    private val words = HashMap<String, String?>()

    /** Told by the service: the words the tags of [id] hold, or that they hold none. */
    fun heard(id: String, text: String?) {
        words[id] = text
        heardWords++
    }

    /** Bumped as the words of a track become known, for a screen that waits for them. */
    var heardWords by mutableIntStateOf(0)
        private set

    /** The words of the track [id]: its tags', else a `.lrc` of its name beside it; null when there are none. */
    suspend fun lyrics(id: String): Lyrics? {
        words[id]?.let { text -> Lyrics.parse(text)?.let { return it } }
        if (Sounds.web(id)) return null
        val stem = id.substringBeforeLast('.')
        for (lrc in listOf("$stem.lrc", "$stem.LRC")) {
            if (files.stat(lrc) == null) continue
            val text = try {
                runInterruptible(Dispatchers.IO) { files.read(lrc).use { it.readNBytes(MAX_LIST).toString(Charsets.UTF_8) } }
            } catch (_: IOException) {
                continue
            }
            return Lyrics.parse(text)
        }
        return null
    }

    /** The queue in its own order, and the order it plays in: shuffled or not. */
    var tracks by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var order by mutableStateOf<List<Int>>(emptyList())
        private set
    var index by mutableIntStateOf(C.INDEX_UNSET)
        private set
    var playing by mutableStateOf(false)
        private set

    /** Waiting for the data of the track: a server slow to answer. */
    var loading by mutableStateOf(false)
        private set
    var metadata by mutableStateOf(MediaMetadata.EMPTY)
        private set
    var duration by mutableLongStateOf(C.TIME_UNSET)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set

    /** How loud the speaker plays, of [volumeMax]; and a count of the times it was changed here, for the panel that shows it. */
    var volume by mutableIntStateOf(0)
        private set
    var volumeMax by mutableIntStateOf(0)
        private set
    var volumeAsked by mutableIntStateOf(0)
        private set
    private var volumeSetAt = 0L

    /** The player's service, while it runs: sets the volume of the renderer it plays on. */
    internal var turner: ((Int) -> Unit)? = null

    /** The full player shows the words of the track where the cover is; kept while it is down. */
    var wordsShown by mutableStateOf(false)

    /** Asks the screens to show the full player. */
    val opens = Channel<Unit>(Channel.CONFLATED)

    val shown: Boolean get() = tracks.isNotEmpty()

    val current: MediaItem? get() = tracks.getOrNull(index)

    /** Where the track is now, milliseconds; asked by the screen as often as it draws the time. */
    val position: Long get() = controller?.currentPosition ?: 0L

    /** At the start of the app: a queue left from the last time comes back, paused. */
    fun restore() {
        if (PlayerService.saved(context).exists()) use {}
    }

    /** Plays the tracks of the folder [path] lies in, in its order, from that one. */
    fun playFolder(path: String) {
        scope.launch {
            val s = settings.settings.first()
            val nodes = files.list(Paths.parent(path)) ?: listOfNotNull(files.stat(path))
            val found = Listing.arrange(nodes.filter { !it.directory && Sounds.plays(it.name) }, s.sortBy, s.sortDescending, s.showHidden)
            val list = found.ifEmpty { listOfNotNull(files.stat(path)) }
            val cover = Sounds.cover(nodes)
            play(list.map { Sounds.item(it.path, cover) }, list.indexOfFirst { it.path == path }.coerceAtLeast(0))
        }
    }

    /** Plays what the playlist at [path] lists, each track with its folder's picture. */
    fun playList(path: String) {
        scope.launch {
            val ids = try {
                val bytes = runInterruptible(Dispatchers.IO) { files.read(path).use { it.readNBytes(MAX_LIST) } }
                Sounds.parse(Paths.name(path), bytes, Paths.parent(path))
            } catch (_: IOException) {
                emptyList()
            }
            if (ids.isEmpty()) return@launch Toast.makeText(context, context.getString(R.string.player_list_empty), Toast.LENGTH_SHORT).show()
            val folders = ids.filterNot(Sounds::web).map(Paths::parent).distinct()
            val covers = if (folders.size > MAX_COVER_FOLDERS) emptyMap() else folders.associateWith { files.list(it)?.let(Sounds::cover) }
            play(ids.map { Sounds.item(it, if (Sounds.web(it)) null else covers[Paths.parent(it)]) }, 0)
        }
    }

    private fun play(items: List<MediaItem>, at: Int) = use {
        it.setMediaItems(items, at, 0)
        it.prepare()
        it.play()
        opens.trySend(Unit)
    }

    fun toggle() = use {
        if (it.isPlaying) {
            it.pause()
        } else {
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition()
            it.play()
        }
    }

    fun next() = use { it.seekToNextMediaItem() }

    /** Back to the start of the track, or to the one before when it has only just begun. */
    fun previous() = use { it.seekToPrevious() }

    fun seek(to: Long) = use { it.seekTo(to) }

    fun jump(to: Int) = use {
        it.seekTo(to, 0)
        if (it.playbackState == Player.STATE_IDLE) it.prepare()
        it.play()
    }

    fun switchShuffle() = use { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Off, the whole queue over again, the one track over again. */
    fun switchRepeat() = use {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Stops and empties the queue: the player is gone until a track is played again. */
    fun close() = use {
        it.stop()
        it.clearMediaItems()
    }

    /** Turns the speaker up or down by [steps] of [VOLUME_STEP], as the phone's volume keys do while it plays. */
    fun volumeBy(steps: Int) = turnTo(volume + steps * VOLUME_STEP)

    fun turnTo(to: Int) {
        val v = to.coerceIn(0, volumeMax.coerceAtLeast(0))
        // Shown at once; the player's own word, a moment late, would pull the slider back.
        volume = v
        volumeSetAt = SystemClock.elapsedRealtime()
        volumeAsked++
        // Straight to the player: a controller that connected while the phone played drops volume commands for good.
        turner?.invoke(v)
    }

    fun show() {
        opens.trySend(Unit)
    }

    // The session lives in the app's own process: the controller is made once and kept.
    private fun use(action: (MediaController) -> Unit) {
        controller?.let { return action(it) }
        waiting += action
        if (connecting) return
        connecting = true
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@Playback.controller = null
                    read(null)
                }
            })
            .buildAsync()
        future.addListener({
            connecting = false
            val made = runCatching { future.get() }.getOrNull()
            if (made == null) {
                waiting.clear()
                return@addListener
            }
            controller = made
            made.addListener(listener)
            read(made)
            waiting.toList().forEach { it(made) }
            waiting.clear()
        }, context.mainExecutor)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = read(player)

        override fun onPlayerError(error: PlaybackException) {
            val name = current?.mediaId?.let(Sounds::stem) ?: return
            Toast.makeText(context, context.getString(R.string.player_failed, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun read(player: Player?) {
        val count = player?.mediaItemCount ?: 0
        val items = (0 until count).map { player!!.getMediaItemAt(it) }
        if (items != tracks) tracks = items
        val timeline = player?.currentTimeline
        order = if (timeline == null || timeline.isEmpty) emptyList() else buildList {
            val shuffled = player.shuffleModeEnabled
            var at = timeline.getFirstWindowIndex(shuffled)
            while (at != C.INDEX_UNSET && size < count) {
                add(at)
                at = timeline.getNextWindowIndex(at, Player.REPEAT_MODE_OFF, shuffled)
            }
        }
        index = if (count == 0) C.INDEX_UNSET else player!!.currentMediaItemIndex
        playing = player?.isPlaying == true
        loading = player?.playbackState == Player.STATE_BUFFERING
        metadata = player?.mediaMetadata ?: MediaMetadata.EMPTY
        duration = player?.duration ?: C.TIME_UNSET
        shuffle = player?.shuffleModeEnabled == true
        repeat = player?.repeatMode ?: Player.REPEAT_MODE_OFF
        volumeMax = player?.deviceInfo?.maxVolume ?: 0
        if (SystemClock.elapsedRealtime() - volumeSetAt > VOLUME_HELD_MS) volume = player?.deviceVolume ?: 0
    }

    private companion object {
        const val MAX_LIST = 4 shl 20
        const val MAX_COVER_FOLDERS = 16
        const val VOLUME_STEP = 2
        const val VOLUME_HELD_MS = 1500L
    }
}
