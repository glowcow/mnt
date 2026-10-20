package dev.glowcow.mnt.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.glowcow.mnt.MainActivity
import dev.glowcow.mnt.R
import dev.glowcow.mnt.audio.upnp.FileServer
import dev.glowcow.mnt.audio.upnp.Renderer
import dev.glowcow.mnt.audio.upnp.UpnpPlayer
import dev.glowcow.mnt.container
import java.io.File
import java.util.concurrent.Executors

/** Plays the queue with the app closed: the media session the system's controls and the app's screens drive. */
@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private val server by lazy { FileServer(container.files) }
    private var awake: Pair<WifiManager.WifiLock, PowerManager.WakeLock>? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var failures = 0

    private val saveLater = object : Runnable {
        override fun run() {
            save()
            handler.postDelayed(this, SAVE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val sources = Sources(this, container.files, container.audioCache)
        exo = ExoPlayer.Builder(this, PlayerRenderers(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val player = exo
        SavedQueue.read(saved(this))?.let { queue ->
            player.setMediaItems(queue.items.map { Sounds.item(it.id, it.cover) }, queue.index.coerceIn(0, queue.items.lastIndex), queue.position)
            player.shuffleModeEnabled = queue.shuffle
            player.repeatMode = queue.repeat
        }
        player.addListener(listener)
        val open = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLAYER, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(sources).setMaximumOutputDimension(COVER_PX).build()))
            .setCallback(callback)
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).setNotificationId(NOTIFICATION_ID).setChannelId(CHANNEL).setChannelName(R.string.player_channel).build()
                .apply { setSmallIcon(R.drawable.ic_stat_mnt) },
        )
        // A queue only put back after a restart is no reason for a notification until it plays.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        container.casting.mover = ::move
        container.playback.turner = ::turn
    }

    /** Moves the queue to [renderer], or back to the phone: where it was, playing on a renderer, paused on the phone. */
    private fun move(renderer: Renderer?) {
        val session = session ?: return
        val from = session.player
        if (renderer?.id == container.casting.target?.id && (renderer == null) == (from === exo)) return
        val to: Player = if (renderer == null) exo else UpnpPlayer(renderer, server, { container.playback.tags.read(it) }, ::unreachable, ::refused)
        val items = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
        from.removeListener(listener)
        to.addListener(listener)
        to.shuffleModeEnabled = from.shuffleModeEnabled
        to.repeatMode = from.repeatMode
        if (items.isNotEmpty()) {
            to.setMediaItems(items, from.currentMediaItemIndex, from.currentPosition)
            to.prepare()
            to.playWhenReady = renderer != null && from.playWhenReady
        }
        session.player = to
        from.stop()
        if (from === exo) exo.clearMediaItems() else from.release()
        container.casting.target = renderer
        if (renderer == null) server.stop()
        stayAwake()
    }

    private fun unreachable(name: String) {
        Toast.makeText(this, getString(R.string.player_unreachable, name), Toast.LENGTH_SHORT).show()
        move(null)
    }

    private fun refused(name: String) {
        Toast.makeText(this, getString(R.string.player_refused, name), Toast.LENGTH_SHORT).show()
    }

    private fun turn(volume: Int) {
        (session?.player as? UpnpPlayer)?.setDeviceVolume(volume, 0)
    }

    // A renderer reads from the phone while it plays: Wi-Fi and the processor stay up for it.
    private fun stayAwake() {
        val player = session?.player
        val wanted = player is UpnpPlayer && player.playWhenReady
        if (wanted && awake == null) {
            val wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "mnt:cast").apply { setReferenceCounted(false); acquire() }
            val cpu = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mnt:cast").apply { setReferenceCounted(false); acquire(AWAKE_MS) }
            awake = wifi to cpu
        } else if (!wanted) {
            awake?.let { (wifi, cpu) -> wifi.release(); cpu.release() }
            awake = null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        handler.removeCallbacks(saveLater)
        container.casting.mover = null
        container.playback.turner = null
        container.casting.target = null
        session?.run {
            save()
            if (player !== exo) player.release()
            release()
        }
        exo.release()
        server.stop()
        awake?.let { (wifi, cpu) -> wifi.release(); cpu.release() }
        awake = null
        session = null
        writer.shutdown()
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TRACKS_CHANGED) && player === exo) {
                name(player)
                words(player)
            }
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) stayAwake()
            // An emptied queue leaves the renderer: the next track plays on the phone again.
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) && player.mediaItemCount == 0 && player !== exo) handler.post { move(null) }
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                )
            ) {
                save()
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                handler.removeCallbacks(saveLater)
                if (player.isPlaying) {
                    failures = 0
                    handler.postDelayed(saveLater, SAVE_MS)
                }
            }
        }

        // A track that cannot be played is passed over, until every one of the queue has failed.
        override fun onPlayerError(error: PlaybackException) {
            val player = session?.player ?: return
            if (player !== exo) return
            if (++failures >= player.mediaItemCount || !player.hasNextMediaItem()) return
            player.seekToNextMediaItem()
            player.prepare()
        }
    }

    // A track whose tags hold no title goes by its file name, here and in the system's controls. The tags are
    // known once its tracks are: on the way from one item to the next the tracks are none for a moment.
    private fun name(player: Player) {
        if (player.currentTracks.isEmpty) return
        val item = player.currentMediaItem ?: return
        if (player.mediaMetadata.title != null || item.mediaMetadata.title != null) return
        val named = item.mediaMetadata.buildUpon().setTitle(Sounds.stem(item.mediaId)).build()
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(named).build())
    }

    // The words a track's tags hold, told to the screens as soon as the player has read them.
    private fun words(player: Player) {
        val id = player.currentMediaItem?.mediaId ?: return
        val text = player.currentTracks.groups.asSequence()
            .flatMap { group -> (0 until group.length).asSequence().map { group.getTrackFormat(it) } }
            .firstNotNullOfOrNull { it.metadata?.let(Lyrics::of) }
        container.playback.heard(id, text)
    }

    private fun save() {
        val player = session?.player ?: return
        val queue = SavedQueue.of(player)
        val file = saved(this)
        writer.execute { if (queue == null) file.delete() else queue.write(file) }
    }

    private val callback = object : MediaSession.Callback {
        // What a controller hands over is named by id alone: the session makes the tracks of it.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { Sounds.item(it.mediaId, it.mediaMetadata.artworkUri?.takeIf { uri -> uri.scheme == Sounds.SCHEME }?.let(Sounds::path)) },
        )

        // Play pressed on headphones or in the system's controls with nothing in the queue: the one that was left.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val queue = SavedQueue.read(saved(this@PlayerService)) ?: return Futures.immediateFailedFuture(UnsupportedOperationException())
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(queue.items.map { Sounds.item(it.id, it.cover) }, queue.index.coerceIn(0, queue.items.lastIndex), queue.position),
            )
        }
    }

    companion object {
        private const val CHANNEL = "player"
        private const val NOTIFICATION_ID = 4
        private const val SAVE_MS = 10_000L
        private const val COVER_PX = 1024

        // Taken again at every play, so a long evening of music outlasts it only while nothing is pressed.
        private const val AWAKE_MS = 6 * 60 * 60 * 1000L

        /** Where the queue is kept between runs of the app. */
        fun saved(context: Context) = File(context.filesDir, "player.json")
    }
}
