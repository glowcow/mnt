package dev.glowcow.mnt.audio.upnp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.glowcow.mnt.audio.Sounds
import dev.glowcow.mnt.audio.Tag
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.Executors

/**
 * The queue played by a [renderer] on the network: it is handed a link to each track in turn, served by [server],
 * and asked every second where it is. What the session asks of a player is turned into UPnP actions.
 */
@OptIn(UnstableApi::class)
class UpnpPlayer(
    private val renderer: Renderer,
    private val server: FileServer,
    private val tags: suspend (String) -> Tag?,
    private val onFailed: (String) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()

    private var items: List<MediaItem> = emptyList()
    private val metadata = HashMap<String, MediaMetadata>()
    private var index = 0
    private var playWhenReady = false
    private var state = Player.STATE_IDLE
    private var position = 0L
    private var positionAt = SystemClock.elapsedRealtime()
    private var duration = C.TIME_UNSET
    private var repeat = Player.REPEAT_MODE_OFF
    private var shuffle = false
    private var volume = 50
    private var error: PlaybackException? = null
    private var released = false

    // What the renderer was last told and has shown since: an end it reports is believed only after it played.
    private var loaded = -1
    private var heardPlaying = false
    private var quietUntil = 0L

    private val local: InetAddress by lazy {
        DatagramSocket().use {
            it.connect(InetAddress.getByName(renderer.host), 1900)
            it.localAddress
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (released) return
            net.execute { ask() }
            main.postDelayed(this, POLL_MS)
        }
    }

    init {
        net.execute {
            runCatching { renderer.rendering?.let { Soap.call(it, renderer.renderingType!!, "GetVolume", "InstanceID" to "0", "Channel" to "Master") }?.get("CurrentVolume")?.toIntOrNull() }
                .getOrNull()?.let { v -> main.post { volume = v; invalidateState() } }
        }
        main.postDelayed(poll, POLL_MS)
    }

    override fun getState(): State {
        val now = SystemClock.elapsedRealtime()
        val playing = playWhenReady && state == Player.STATE_READY
        val at = if (playing) position + (now - positionAt) else position
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else state)
            .setPlayerError(error)
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(shuffle)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build())
            .setDeviceVolume(volume)
            .setPlaylist(
                items.mapIndexed { i, item ->
                    MediaItemData.Builder("$i:${item.mediaId}")
                        .setMediaItem(item)
                        .setMediaMetadata(metadata[item.mediaId] ?: item.mediaMetadata)
                        .setDurationUs(if (i == index && duration != C.TIME_UNSET) duration * 1000 else C.TIME_UNSET)
                        .setIsSeekable(true)
                        .build()
                },
            )
            .setCurrentMediaItemIndex(index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
            .setContentPositionMs(if (playing) PositionSupplier.getExtrapolating(at, 1f) else PositionSupplier.getConstant(at))
            .build()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems.toList()
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        moveTo(if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs)
        if (state != Player.STATE_IDLE) load()
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        error = null
        if (items.isNotEmpty()) {
            state = Player.STATE_BUFFERING
            load()
        }
        return done()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        moveTo(currentAt())
        hush()
        if (loaded == index) net.execute { act(if (playWhenReady) "Play" else "Pause", *(if (playWhenReady) arrayOf("Speed" to "1") else emptyArray())) }
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val to = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (mediaItemIndex != index || loaded != index) {
            index = mediaItemIndex
            moveTo(to)
            duration = C.TIME_UNSET
            if (state != Player.STATE_IDLE) {
                state = Player.STATE_BUFFERING
                load()
            }
        } else {
            moveTo(to)
            hush()
            net.execute { act("Seek", "Unit" to "REL_TIME", "Target" to Soap.time(to)) }
        }
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        state = Player.STATE_IDLE
        moveTo(currentAt())
        loaded = -1
        net.execute { act("Stop") }
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        main.removeCallbacks(poll)
        net.execute { runCatching { Soap.call(renderer.transport, renderer.transportType, "Stop", "InstanceID" to "0") } }
        net.shutdown()
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return done()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffle = shuffleModeEnabled
        return done()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        volume = deviceVolume.coerceIn(0, 100)
        val v = volume
        net.execute { runCatching { renderer.rendering?.let { Soap.call(it, renderer.renderingType!!, "SetVolume", "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to v.toString()) } } }
        return done()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volume + VOLUME_STEP, flags)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volume - VOLUME_STEP, flags)

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        items = items.toMutableList().apply { addAll(index.coerceAtMost(size), mediaItems) }
        if (index <= this.index && items.size > mediaItems.size) shift(mediaItems.size)
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val playingGone = index in fromIndex until toIndex
        items = items.toMutableList().apply { subList(fromIndex, toIndex).clear() }
        when {
            items.isEmpty() -> {
                index = 0
                state = Player.STATE_IDLE
                loaded = -1
                net.execute { act("Stop") }
            }
            playingGone -> {
                index = fromIndex.coerceAtMost(items.size - 1)
                moveTo(0)
                if (state != Player.STATE_IDLE) load()
            }
            index >= toIndex -> shift(-(toIndex - fromIndex))
        }
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val list = items.toMutableList()
        val moved = list.subList(fromIndex, toIndex).toList()
        val current = items.getOrNull(index)
        list.subList(fromIndex, toIndex).clear()
        list.addAll(newIndex.coerceAtMost(list.size), moved)
        items = list
        index = items.indexOfFirst { it === current }.coerceAtLeast(0)
        loaded = if (loaded >= 0) index else -1
        return done()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        items = items.toMutableList().apply {
            subList(fromIndex, toIndex).clear()
            addAll(fromIndex, mediaItems)
        }
        return done()
    }

    private fun shift(by: Int) {
        index += by
        if (loaded >= 0) loaded += by
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun currentAt(): Long = if (playWhenReady && state == Player.STATE_READY) position + (SystemClock.elapsedRealtime() - positionAt) else position

    private fun moveTo(to: Long) {
        position = to.coerceAtLeast(0)
        positionAt = SystemClock.elapsedRealtime()
    }

    // For a moment after an action the renderer still reports what it did before.
    private fun hush() {
        quietUntil = SystemClock.elapsedRealtime() + QUIET_MS
    }

    // Hands the track at [index] to the renderer, from where [position] says, and plays it if asked to.
    private fun load() {
        val at = index
        val item = items.getOrNull(at) ?: return
        val start = position
        loaded = at
        heardPlaying = false
        hush()
        net.execute {
            try {
                val tag = runCatching { kotlinx.coroutines.runBlocking { tags(item.mediaId) } }.getOrNull()
                val url = if (Sounds.web(item.mediaId)) item.mediaId else server.url(local, item.mediaId)
                val cover = item.mediaMetadata.artworkUri?.takeIf { it.scheme == Sounds.SCHEME }?.let { server.url(local, Sounds.path(it)) }
                val meta = MediaMetadata.Builder()
                    .setTitle(tag?.title ?: Sounds.stem(item.mediaId))
                    .setArtist(tag?.artist)
                    .setAlbumTitle(tag?.album)
                    .setArtworkUri(item.mediaMetadata.artworkUri)
                    .build()
                main.post {
                    metadata[item.mediaId] = meta
                    if (tag?.duration != null && index == at) duration = tag.duration
                    invalidateState()
                }
                if (runCatching { act("Stop") }.isFailure) Unit
                act("SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to didl(url, item.mediaId, meta, tag, cover))
                if (start > SEEK_FROM_MS) runCatching { act("Seek", "Unit" to "REL_TIME", "Target" to Soap.time(start)) }
                val play = playWhenReady
                if (play) act("Play", "Speed" to "1")
                main.post {
                    if (index == at) {
                        state = Player.STATE_READY
                        moveTo(start)
                        invalidateState()
                    }
                }
            } catch (e: IOException) {
                main.post {
                    error = PlaybackException(e.message, e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                    state = Player.STATE_IDLE
                    playWhenReady = false
                    invalidateState()
                    onFailed(renderer.name)
                }
            }
        }
    }

    private fun act(action: String, vararg args: Pair<String, String>): Map<String, String> =
        Soap.call(renderer.transport, renderer.transportType, action, "InstanceID" to "0", *args)

    // Where the renderer is and what it does; the end of a track it played moves the queue on.
    private fun ask() {
        val transport = runCatching { act("GetTransportInfo")["CurrentTransportState"] }.getOrNull() ?: return
        val info = runCatching { act("GetPositionInfo") }.getOrNull()
        main.post {
            if (released || loaded != index || SystemClock.elapsedRealtime() < quietUntil) return@post
            val rel = Soap.millis(info?.get("RelTime"))
            Soap.millis(info?.get("TrackDuration"))?.takeIf { it > 0 }?.let { duration = it }
            when (transport) {
                "PLAYING" -> {
                    heardPlaying = true
                    state = Player.STATE_READY
                    if (!playWhenReady) playWhenReady = true
                    rel?.let(::moveTo)
                }
                "PAUSED_PLAYBACK" -> {
                    state = Player.STATE_READY
                    playWhenReady = false
                    rel?.let(::moveTo)
                }
                "TRANSITIONING" -> state = Player.STATE_BUFFERING
                "STOPPED", "NO_MEDIA_PRESENT" -> if (heardPlaying && playWhenReady) ended()
            }
            invalidateState()
        }
    }

    private fun ended() {
        val next = when {
            repeat == Player.REPEAT_MODE_ONE -> index
            index + 1 < items.size -> index + 1
            repeat == Player.REPEAT_MODE_ALL -> 0
            else -> -1
        }
        if (next < 0) {
            state = Player.STATE_ENDED
            playWhenReady = false
            loaded = -1
            return
        }
        index = next
        moveTo(0)
        duration = C.TIME_UNSET
        state = Player.STATE_BUFFERING
        load()
    }

    private fun didl(url: String, id: String, meta: MediaMetadata, tag: Tag?, cover: String?): String {
        val mime = Mimes.of(id)
        val time = tag?.duration?.let { " duration=\"${Soap.time(it)}.000\"" }.orEmpty()
        return buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
            append("""<item id="1" parentID="0" restricted="1">""")
            append("<dc:title>").append(Soap.escape(meta.title?.toString().orEmpty())).append("</dc:title>")
            meta.artist?.let { append("<upnp:artist>").append(Soap.escape(it.toString())).append("</upnp:artist>") }
            meta.albumTitle?.let { append("<upnp:album>").append(Soap.escape(it.toString())).append("</upnp:album>") }
            cover?.let { append("<upnp:albumArtURI>").append(Soap.escape(it)).append("</upnp:albumArtURI>") }
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("""<res protocolInfo="http-get:*:$mime:${Mimes.FEATURES}"$time>""").append(Soap.escape(url)).append("</res>")
            append("</item></DIDL-Lite>")
        }
    }

    /** Where it plays, by name. */
    val name: String get() = renderer.name

    val host: String get() = runCatching { URI(renderer.transport).host }.getOrDefault(renderer.host)

    private companion object {
        const val POLL_MS = 1000L
        const val QUIET_MS = 2500L
        const val SEEK_FROM_MS = 1000L
        const val VOLUME_STEP = 5

        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_RELEASE,
        ).build()
    }
}
