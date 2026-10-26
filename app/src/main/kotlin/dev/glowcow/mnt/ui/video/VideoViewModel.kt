package dev.glowcow.mnt.ui.video

import android.app.Application
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import dev.glowcow.mnt.audio.StoreDataSource
import dev.glowcow.mnt.container
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.data.Buttons
import dev.glowcow.mnt.data.FrameRate
import dev.glowcow.mnt.data.Resuming
import dev.glowcow.mnt.data.VideoSettings
import dev.glowcow.mnt.files.Listing
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.video.Chapter
import dev.glowcow.mnt.video.Chapters
import dev.glowcow.mnt.video.VideoRenderers
import dev.glowcow.mnt.video.Videos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How the picture takes the screen: whole inside it, filling it with the edges cut, or stretched to it. */
enum class Fit { FIT, FILL, STRETCH }

/** One track of a kind the viewer can choose: audio or subtitles. */
data class Choice(val group: Tracks.Group, val index: Int, val format: Format, val selected: Boolean)

/** The video at [path] and the others of its folder, played one after another in the app's own player. */
@OptIn(UnstableApi::class)
class VideoViewModel(val path: String, private val app: Application) : ViewModel() {
    private val c = app.container
    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Made once the settings are read: which decoders go first is one of them. */
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    private var session: MediaSession? = null

    var playing by mutableStateOf(false)
        private set
    var waiting by mutableStateOf(true)
        private set
    var ended by mutableStateOf(false)
        private set
    var duration by mutableLongStateOf(C.TIME_UNSET)
        private set
    var size by mutableStateOf(VideoSize.UNKNOWN)
        private set
    var format by mutableStateOf<Format?>(null)
        private set
    var item by mutableStateOf<MediaItem?>(null)
        private set
    var hasPrevious by mutableStateOf(false)
        private set
    var hasNext by mutableStateOf(false)
        private set
    var audio by mutableStateOf<List<Choice>>(emptyList())
        private set
    var subtitles by mutableStateOf<List<Choice>>(emptyList())
        private set
    var subtitlesOff by mutableStateOf(false)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The decoders the picture and the sound go through now, and whether the picture's is the phone's hardware. */
    var videoDecoder by mutableStateOf<String?>(null)
        private set
    var audioDecoder by mutableStateOf<String?>(null)
        private set
    var hardware by mutableStateOf<Boolean?>(null)
        private set

    /** The chapters of the video on now, read from its file; none until read, and none in most files but MKV. */
    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        private set
    private var chaptersOf: String? = null

    /** Sizes of the folder's videos, for their details. */
    var sizes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    /** Bumped when another video of the folder begins, for the screen to show its controls. */
    var begun by mutableIntStateOf(0)
        private set

    var fit by mutableStateOf(Fit.FIT)

    /** Where the video on now was left, while the viewer is asked whether to go on from there; null otherwise. */
    var asking by mutableStateOf<Long?>(null)
        private set

    private val resuming: Resuming get() = settings.value?.video?.resume ?: Resuming.ASK

    init {
        viewModelScope.launch {
            val s = settings.filterNotNull().first()
            val made = build(s.video)
            player = made
            session = runCatching { MediaSession.Builder(app, made).setId("video").build() }.getOrNull()
            val nodes = c.files.list(Paths.parent(path)) ?: listOfNotNull(c.files.stat(path))
            val videos = Listing.arrange(nodes.filter { !it.directory && Videos.plays(it.name) }, s.sortBy, s.sortDescending, s.showHidden)
                .ifEmpty { listOfNotNull(c.files.stat(path)) }
            sizes = videos.associate { it.path to it.size }
            val at = videos.indexOfFirst { it.path == path }.coerceAtLeast(0)
            val left = if (s.video.resume == Resuming.NEVER) 0L else c.resume.at(videos.getOrNull(at)?.path ?: path)
            made.setMediaItems(videos.map { Videos.item(it, nodes) }, at, left)
            made.prepare()
            if (left > 0 && s.video.resume == Resuming.ASK) asking = left else made.play()
        }
        // Where the video stands is kept now and then, so a crash or a killed app loses little.
        viewModelScope.launch {
            while (true) {
                delay(KEEP_MS)
                if (playing) keep()
            }
        }
    }

    private fun build(video: VideoSettings): ExoPlayer {
        val source = DataSource.Factory { StoreDataSource(c.files) { sizes[it] } }
        return ExoPlayer.Builder(app, VideoRenderers(app, video.decoding))
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(video.skipSeconds * 1000L)
            .setSeekForwardIncrementMs(video.skipSeconds * 1000L)
            .build()
            .apply {
                // Always is the screen's own call, made on the surface; the player's own changes only seamless ones.
                videoChangeFrameRateStrategy = if (video.frameRate == FrameRate.SEAMLESS) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                pauseAtEndOfMediaItems = !video.playNext
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(video.audioLanguage.ifEmpty { null })
                    .setPreferredTextLanguage(video.subtitleLanguage.takeIf { it.isNotEmpty() && it != VideoSettings.OFF })
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, video.subtitleLanguage == VideoSettings.OFF)
                    .build()
                addListener(listener)
                addAnalyticsListener(decoders)
            }
    }

    private val decoders = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
            hardware = hardwareCodecs.contains(decoderName)
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
        }
    }

    // The phone's decoders that run on its hardware; FFmpeg's and Google's own run on the processor.
    private val hardwareCodecs: Set<String> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder && it.isHardwareAccelerated }.mapTo(HashSet()) { it.name }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = read(player)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            // The one before is left where it ended; the next starts where it was left last time.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                val id = mediaItem?.mediaId ?: return
                begun++
                val left = if (resuming == Resuming.NEVER) 0L else c.resume.at(id)
                if (left <= 0) return
                p.seekTo(left)
                if (resuming == Resuming.ASK) {
                    p.pause()
                    asking = left
                }
            }
        }

        // A video left for another is kept where it was left.
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val p = player ?: return
            val id = old.mediaItem?.mediaId ?: return
            if (old.mediaItemIndex == new.mediaItemIndex || resuming == Resuming.NEVER) return
            val length = p.currentTimeline.takeIf { old.mediaItemIndex < it.windowCount }?.getWindow(old.mediaItemIndex, Timeline.Window())?.durationMs ?: C.TIME_UNSET
            c.resume.keep(id, old.positionMs, length)
        }

        override fun onPlayerError(e: PlaybackException) {
            error = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message?.takeIf(String::isNotBlank) }.lastOrNull() ?: e.errorCodeName
        }
    }

    private fun read(p: Player) {
        val wasPlaying = playing
        playing = p.isPlaying
        waiting = p.playbackState == Player.STATE_BUFFERING
        ended = p.playbackState == Player.STATE_ENDED
        duration = p.duration
        size = p.videoSize
        format = (p as? ExoPlayer)?.videoFormat
        item = p.currentMediaItem
        hasPrevious = p.hasPreviousMediaItem()
        hasNext = p.hasNextMediaItem()
        speed = p.playbackParameters.speed
        if (p.playerError == null) error = null
        val tracks = p.currentTracks
        audio = choices(tracks, C.TRACK_TYPE_AUDIO)
        subtitles = choices(tracks, C.TRACK_TYPE_TEXT)
        subtitlesOff = C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes || subtitles.none { it.selected }
        item?.mediaId?.takeIf { it != chaptersOf }?.let(::readChapters)
        // Paused, it is kept where it stands.
        if (wasPlaying && !playing) keep()
    }

    private fun choices(tracks: Tracks, type: Int): List<Choice> = tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { Choice(group, it, group.getTrackFormat(it), group.isTrackSelected(it)) }
    }

    /** Plays [choice], or turns subtitles off when it is null. */
    fun choose(type: Int, choice: Choice?) {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
        if (choice == null) {
            params.setTrackTypeDisabled(type, true)
        } else {
            params.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
        }
        p.trackSelectionParameters = params.build()
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            return
        }
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
        p.play()
    }

    fun pause() = player?.pause()

    /** Goes on from where the video was left, or plays it from its start. */
    fun answer(goOn: Boolean) {
        val p = player ?: return
        if (asking == null) return
        asking = null
        if (!goOn) p.seekTo(0)
        p.play()
    }

    fun seek(to: Long) = player?.seekTo(to.coerceAtLeast(0))

    fun skip(by: Long) {
        val p = player ?: return
        val end = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + by).coerceIn(0, end))
    }

    private fun readChapters(id: String) {
        chaptersOf = id
        chapters = emptyList()
        if (Paths.extension(id) !in MATROSKA) return
        val language = Locale.getDefault().language
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { Chapters.of({ offset, length -> c.files.read(id, offset).use { it.readNBytes(length) } }, language) }.getOrDefault(emptyList())
            }
            // One chapter is no chapters at all.
            if (chaptersOf == id) chapters = found.takeIf { it.size > 1 }.orEmpty()
        }
    }

    private val byChapters: Boolean get() = settings.value?.video?.buttons == Buttons.CHAPTERS && chapters.isNotEmpty()

    /** The next chapter while the buttons go by chapters, else the next video. */
    fun next() {
        val p = player ?: return
        if (byChapters) {
            chapters.firstOrNull { it.start > p.currentPosition + CHAPTER_SLACK_MS }?.let { return p.seekTo(it.start) }
        }
        p.seekToNextMediaItem()
    }

    /** Back to the start of the chapter, or to the one before when it has only just begun; else the video before. */
    fun previous() {
        val p = player ?: return
        if (byChapters) {
            val now = p.currentPosition
            val at = chapters.indexOfLast { it.start <= now }
            if (at >= 0 && now - chapters[at].start > CHAPTER_SLACK_MS * 6) return p.seekTo(chapters[at].start)
            if (at > 0) return p.seekTo(chapters[at - 1].start)
        }
        p.seekToPreviousMediaItem()
    }

    /** Whether the buttons have anywhere to go. */
    val canPrevious: Boolean get() = hasPrevious || byChapters
    val canNext: Boolean get() = hasNext || byChapters

    fun setSpeed(to: Float) = player?.setPlaybackSpeed(to)

    /** Tries the video again after a failure. */
    fun retry() {
        error = null
        player?.prepare()
        player?.play()
    }

    val position: Long get() = player?.currentPosition ?: 0L

    val buffered: Long get() = player?.bufferedPosition ?: 0L

    private fun keep() {
        val p = player ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        if (resuming != Resuming.NEVER) c.resume.keep(id, p.currentPosition, p.duration)
    }

    override fun onCleared() {
        keep()
        session?.release()
        player?.release()
        player = null
    }

    private companion object {
        const val KEEP_MS = 10_000L
        const val CHAPTER_SLACK_MS = 500L
        val MATROSKA = setOf("mkv", "webm", "mka")
    }
}
