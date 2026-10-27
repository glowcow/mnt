package dev.glowcow.mnt.ui.video

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.MetadataRetriever
import dev.glowcow.mnt.audio.Sounds
import dev.glowcow.mnt.audio.StoreDataSource
import dev.glowcow.mnt.container
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.data.Buttons
import dev.glowcow.mnt.data.Resuming
import dev.glowcow.mnt.data.SubtitleBackground
import dev.glowcow.mnt.data.SubtitleColor
import dev.glowcow.mnt.data.SubtitleEncoding
import dev.glowcow.mnt.data.SubtitlePosition
import dev.glowcow.mnt.data.SubtitleSize
import dev.glowcow.mnt.data.VideoSettings
import dev.glowcow.mnt.files.Listing
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.Remote
import dev.glowcow.mnt.files.RemoteProvider
import dev.glowcow.mnt.video.Chapter
import dev.glowcow.mnt.video.Chapters
import dev.glowcow.mnt.video.Videos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import java.io.File
import java.util.Locale

/** How the picture takes the screen: whole inside it, filling it with the edges cut, or stretched to it. */
enum class Fit { FIT, FILL, STRETCH }

/** One track of a kind the viewer can choose, audio or subtitles, by VLC's [id]; [format] describes it for its name. */
data class Choice(val id: Int, val format: Format, val selected: Boolean)

/** The picture's size as decoded, and the shape of its pixels. */
data class Picture(val width: Int, val height: Int, val pixelWidthHeightRatio: Float) {
    companion object {
        val UNKNOWN = Picture(0, 0, 1f)
    }
}

/**
 * The video at [path] and the others of its folder, played one after another by LibVLC, VLC's own engine: the
 * phone's decoder where it takes the stream, else VLC's FFmpeg on the processor.
 */
@OptIn(UnstableApi::class)
class VideoViewModel(val path: String, private val app: Application) : ViewModel() {
    private val c = app.container
    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var vlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var session: MediaSession? = null
    private val audio = app.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var nodes: List<Node> = emptyList()
    private var videos: List<Node> = emptyList()
    private var index = -1
    private var opening: Job? = null

    var playing by mutableStateOf(false)
        private set
    var waiting by mutableStateOf(true)
        private set
    var ended by mutableStateOf(false)
        private set
    var duration by mutableLongStateOf(C.TIME_UNSET)
        private set
    var size by mutableStateOf(Picture.UNKNOWN)
        private set

    /** The picture's stream as its file describes it: codec, profile, depth, colour, rate. */
    var format by mutableStateOf<Format?>(null)
        private set

    /** The path of the video on now. */
    var current by mutableStateOf<String?>(null)
        private set
    var hasPrevious by mutableStateOf(false)
        private set
    var hasNext by mutableStateOf(false)
        private set
    var audioTracks by mutableStateOf<List<Choice>>(emptyList())
        private set
    var subtitles by mutableStateOf<List<Choice>>(emptyList())
        private set
    var subtitlesOff by mutableStateOf(false)
        private set

    /** How much later than the file says the subtitles come, milliseconds; another video starts with none. */
    private var delay by mutableLongStateOf(0L)
    var subtitleDelay: Long
        get() = delay
        set(to) {
            player?.spuDelay = to * 1000
            delay = to
        }
    private var rate by mutableFloatStateOf(1f)
    var speed: Float
        get() = rate
        set(to) {
            player?.rate = to
            rate = to
        }
    var failed by mutableStateOf(false)
        private set

    /** The phone's decoder VLC hands the picture to, when one takes its codec and profile; null decodes on the processor. */
    var hardwareDecoder by mutableStateOf<String?>(null)
        private set
    var hardware by mutableStateOf<Boolean?>(null)
        private set

    /** The chapters of the video on now; none until read, one is none. */
    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        private set

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
            session = MediaSession(app, "video").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = play()
                    override fun onPause() = pause()
                    override fun onSkipToNext() = next()
                    override fun onSkipToPrevious() = previous()
                    override fun onSeekTo(pos: Long) = seek(pos)
                })
                isActive = true
            }
            app.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            nodes = c.files.list(Paths.parent(path)) ?: listOfNotNull(c.files.stat(path))
            videos = Listing.arrange(nodes.filter { !it.directory && Videos.plays(it.name) }, s.sortBy, s.sortDescending, s.showHidden)
                .ifEmpty { listOfNotNull(c.files.stat(path)) }
            sizes = videos.associate { it.path to it.size }
            open(videos.indexOfFirst { it.path == path }.coerceAtLeast(0))
        }
        // Where the video stands is kept now and then, so a crash or a killed app loses little.
        viewModelScope.launch {
            while (true) {
                delay(KEEP_MS)
                if (playing) keep()
            }
        }
    }

    private var made: List<String>? = null

    // VLC's engine for [options], made again when they change: the subtitles' look is set only on making it.
    private suspend fun engine(options: List<String>): MediaPlayer {
        player?.takeIf { options == made }?.let { return it }
        // Gone before the new engine is made, for nothing to ask the released one meanwhile.
        player?.let {
            player = null
            it.setEventListener(null)
            it.stop()
            it.vlcVout.detachViews()
            it.release()
        }
        vlc?.release()
        vlc = null
        val libVlc = withContext(Dispatchers.Default) { LibVLC(app, ArrayList(options)) }
        vlc = libVlc
        made = options
        return MediaPlayer(libVlc).apply { setEventListener(::event) }.also { p ->
            player = p
            views?.let { (picture, words) -> attach(picture, words) }
        }
    }

    // As VLC's own app sets it up, with the subtitles' look of the settings; VLC counts their margin in the picture's pixels.
    private fun options(video: VideoSettings, margin: Int): List<String> {
        val box = video.subtitleBackground == SubtitleBackground.BOX
        return listOf(
            "--audio-time-stretch",
            "--avcodec-skiploopfilter=1",
            "--avcodec-skip-frame=0",
            "--avcodec-skip-idct=0",
            "--audio-resampler=soxr",
            "--no-sub-autodetect-file",
            "--sub-margin=$margin",
            "--freetype-rel-fontsize=${FONT_SIZES.getValue(video.subtitleSize)}",
            "--freetype-color=${if (video.subtitleColor == SubtitleColor.YELLOW) YELLOW else WHITE}",
            "--freetype-outline-thickness=${if (box) 0 else OUTLINE}",
            "--freetype-shadow-opacity=0",
            "--freetype-background-color=0",
            "--freetype-background-opacity=${if (box) BOX_OPACITY else 0}",
            "-v",
        ) + when (video.subtitleEncoding) {
            SubtitleEncoding.AUTO -> emptyList()
            SubtitleEncoding.CYRILLIC -> listOf("--subsdec-encoding=Windows-1251")
            SubtitleEncoding.UNICODE -> listOf("--subsdec-encoding=UTF-8")
        }
    }

    private var views: Pair<SurfaceView, SurfaceView>? = null

    /** The views VLC draws on: the picture, and the subtitles over it, laid out by the screen. */
    fun attach(picture: SurfaceView, words: SurfaceView) {
        views = picture to words
        val vout = player?.vlcVout ?: return
        if (vout.areViewsAttached()) vout.detachViews()
        words.setZOrderMediaOverlay(true)
        words.holder.setFormat(PixelFormat.TRANSLUCENT)
        vout.setVideoView(picture)
        vout.setSubtitlesView(words)
        vout.attachViews { _, width, height, visibleWidth, visibleHeight, sarNum, sarDen ->
            val w = visibleWidth.takeIf { it > 0 } ?: width
            val h = visibleHeight.takeIf { it > 0 } ?: height
            size = Picture(w, h, if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f)
        }
    }

    fun detach(picture: SurfaceView) {
        if (views?.first !== picture) return
        views = null
        player?.vlcVout?.takeIf { it.areViewsAttached() }?.detachViews()
    }

    /** Out of sight the surfaces go, and VLC lets go of them; back, it draws on the new ones. */
    fun hide() {
        val p = player ?: return
        p.setVideoTrackEnabled(false)
        p.vlcVout.takeIf { it.areViewsAttached() }?.detachViews()
    }

    fun show() {
        val p = player ?: return
        val (picture, words) = views ?: return
        if (p.vlcVout.areViewsAttached()) return
        attach(picture, words)
        p.setVideoTrackEnabled(true)
        redraw = !p.isPlaying
    }

    // Back from the background paused, the picture is drawn again once VLC has its output back: it shows none till then.
    private var redraw = false

    /** The size the picture takes on the screen, for VLC to draw it and the subtitles at; stretched, it takes that shape. */
    fun layOut(width: Int, height: Int, stretch: Boolean) {
        val p = player ?: return
        if (width <= 0 || height <= 0) return
        p.vlcVout.setWindowSize(width, height)
        p.aspectRatio = if (stretch) "$width:$height" else null
    }

    private fun open(at: Int) {
        val node = videos.getOrNull(at) ?: return
        val first = index < 0
        index = at
        current = node.path
        hasPrevious = at > 0
        hasNext = at < videos.lastIndex
        failed = false
        ended = false
        waiting = true
        chapters = emptyList()
        delay = 0L
        audioTracks = emptyList()
        subtitles = emptyList()
        size = Picture.UNKNOWN
        format = null
        hardware = null
        hardwareDecoder = null
        duration = C.TIME_UNSET
        if (!first) begun++
        opening?.cancel()
        opening = viewModelScope.launch {
            val s = settings.value?.video ?: VideoSettings()
            val subs = subtitleFiles(node)
            val stream = describe(node)
            val p = engine(options(s, subtitleMargin(s.subtitlePosition, stream)))
            val media = mediaFor(node) ?: run {
                failed = true
                return@launch
            }
            media.setHWDecoderEnabled(true, false)
            s.audioLanguage.takeIf { it.isNotEmpty() }?.let { media.addOption(":audio-language=$it") }
            when (s.subtitleLanguage) {
                "" -> Unit
                VideoSettings.OFF -> media.addOption(":sub-language=none")
                else -> media.addOption(":sub-language=${s.subtitleLanguage}")
            }
            subs.forEach { media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, SLAVE_PRIORITY, it)) }
            val left = if (s.resume == Resuming.NEVER) 0L else c.resume.at(node.path)
            if (left > 0) media.addOption(":start-time=${left / 1000.0}")
            // Asked whether to go on, it waits on the first picture of that place.
            asking = left.takeIf { it > 0 && s.resume == Resuming.ASK }
            if (asking != null) media.addOption(":start-paused")
            p.media = media
            media.release()
            if (asking != null) p.play() else play()
            readChapters(node)
        }
    }

    // A file of the phone by its path; a server's through the app's provider, which reads it on demand.
    private fun mediaFor(node: Node): Media? {
        val libVlc = vlc ?: return null
        runCatching { descriptor?.close() }
        descriptor = null
        if (Remote.id(node.path) == null) return Media(libVlc, node.path)
        val fd = runCatching { app.contentResolver.openFileDescriptor(RemoteProvider.uri(app, node.path), "r") }.getOrNull() ?: return null
        descriptor = fd
        return Media(libVlc, fd.fileDescriptor)
    }

    // The subtitles named after the video beside it; a server's are copied to the phone for VLC to read.
    private suspend fun subtitleFiles(node: Node): List<String> {
        val beside = Videos.item(node, nodes).localConfiguration?.subtitleConfigurations.orEmpty().map { Sounds.path(it.uri) }
        if (Remote.id(node.path) == null) return beside.map { Uri.fromFile(File(it)).toString() }
        val dir = File(app.cacheDir, "subtitles").apply { deleteRecursively() }
        return withContext(Dispatchers.IO) {
            beside.mapNotNull { from ->
                runCatching {
                    val to = File(dir, Paths.name(from)).apply { parentFile?.mkdirs() }
                    c.files.read(from).use { input -> to.outputStream().use { input.copyTo(it) } }
                    Uri.fromFile(to).toString()
                }.getOrNull()
            }
        }
    }

    // The picture's stream as Media3 reads the head of the file: its profile, depth and colour, which VLC does not tell.
    private suspend fun describe(node: Node): Format? {
        val source = DataSource.Factory { StoreDataSource(c.files) { if (it == node.path) node.size else null } }
        val found = runCatching {
            withContext(Dispatchers.IO) {
                MetadataRetriever.Builder(app, MediaItem.fromUri(Sounds.uri(node.path)))
                    .setMediaSourceFactory(DefaultMediaSourceFactory(source))
                    .build().use { it.retrieveTrackGroups().get() }
            }
        }.getOrNull()
        val video = found?.let { groups -> (0 until groups.length).map { groups[it].getFormat(0) }.firstOrNull { MimeTypes.isVideo(it.sampleMimeType) } }
        if (current != node.path || video == null) return video
        format = video
        judge(video)
        return video
    }

    // VLC counts the margin in the picture's own pixels; above the controls it is their height on the screen in those.
    private fun subtitleMargin(position: SubtitlePosition, stream: Format?): Int {
        val turned = stream != null && stream.rotationDegrees % 180 != 0
        val width = (if (turned) stream?.height else stream?.width)?.takeIf { it > 0 } ?: PLAIN_WIDTH
        val height = (if (turned) stream?.width else stream?.height)?.takeIf { it > 0 } ?: PLAIN_HEIGHT
        return when (position) {
            SubtitlePosition.BOTTOM -> 0
            SubtitlePosition.HIGHER -> (height * HIGHER_SHARE).toInt()
            SubtitlePosition.ABOVE_CONTROLS -> {
                // The screen turns to the picture's shape, which then fills it one way or the other.
                val m = app.resources.displayMetrics
                val long = maxOf(m.widthPixels, m.heightPixels).toFloat()
                val short = minOf(m.widthPixels, m.heightPixels).toFloat()
                val shown = if (width >= height) minOf(short, long * height / width) else minOf(long, short * height / width)
                (CONTROLS_DP * m.density * height / shown).toInt()
            }
        }
    }

    // MKV chapters by the app's own reader, in the phone's language where they have it; others as VLC reads them.
    private suspend fun readChapters(node: Node) {
        if (Paths.extension(node.name) !in MATROSKA) return
        val found = withContext(Dispatchers.IO) {
            runCatching { Chapters.of({ offset, length -> c.files.read(node.path, offset).use { it.readNBytes(length) } }, Locale.getDefault().language) }.getOrDefault(emptyList())
        }
        if (current == node.path) chapters = found.takeIf { it.size > 1 }.orEmpty()
    }

    // As VLC's MediaCodec module picks: a hardware decoder of the phone that takes the codec, and its profile when known.
    private fun hardwareFor(f: Format): String? {
        // Of Dolby Vision VLC plays the HEVC under it.
        if (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return hardwareFor(f.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build())
        val mime = f.sampleMimeType ?: return null
        val profile = profileOf(f)
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                (profile == null || info.getCapabilitiesForType(mime).profileLevels.any { it.profile == profile })
        }?.name
    }

    private fun profileOf(f: Format): Int? {
        val codecs = f.codecs ?: return null
        return when {
            codecs.startsWith("avc") -> AVC_PROFILES[codecs.substringAfter('.').take(2).toIntOrNull(16)]
            codecs.startsWith("hvc") || codecs.startsWith("hev") -> HEVC_PROFILES[codecs.split('.').getOrNull(1)?.filter(Char::isDigit)?.toIntOrNull()]
            else -> null
        }
    }

    private fun event(e: MediaPlayer.Event) {
        val p = player ?: return
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                playing = true
                waiting = false
            }
            MediaPlayer.Event.Paused -> {
                if (playing) keep()
                playing = false
                waiting = false
            }
            MediaPlayer.Event.Stopped -> playing = false
            MediaPlayer.Event.Buffering -> waiting = e.buffering < 100f
            MediaPlayer.Event.LengthChanged -> duration = e.lengthChanged
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                readTracks(p)
                readPicture(p)
            }
            MediaPlayer.Event.Vout -> {
                if (redraw && e.voutCount > 0) {
                    redraw = false
                    p.time = p.time
                }
                readPicture(p)
                if (chapters.isEmpty()) chapters = p.getChapters(-1).orEmpty().map { Chapter(it.timeOffset, it.name?.takeIf(String::isNotBlank)) }.takeIf { it.size > 1 }.orEmpty()
            }
            MediaPlayer.Event.EndReached -> {
                current?.let { c.resume.keep(it, duration, duration) }
                playing = false
                if (settings.value?.video?.playNext != false && hasNext) open(index + 1) else ended = true
            }
            MediaPlayer.Event.EncounteredError -> {
                playing = false
                waiting = false
                failed = true
            }
        }
        updateSession()
    }

    // The size of the picture VLC shows, turned as the file says; its GL output tells no layout of its own.
    private fun readPicture(p: MediaPlayer) {
        val v = p.currentVideoTrack ?: return
        if (v.width <= 0 || v.height <= 0) return
        val ratio = if (v.sarNum > 0 && v.sarDen > 0) v.sarNum.toFloat() / v.sarDen else 1f
        size = if (v.orientation in TURNED) Picture(v.height, v.width, 1f / ratio) else Picture(v.width, v.height, ratio)
    }

    // VLC's tracks as Media3's formats, which the screen names them by.
    private fun readTracks(p: MediaPlayer) {
        val media = p.media
        val details = try {
            (0 until (media?.trackCount ?: 0)).mapNotNull { media?.getTrack(it) }.associateBy { it.id }
        } finally {
            media?.release()
        }
        fun choices(list: Array<MediaPlayer.TrackDescription>?, selected: Int, type: Int): List<Choice> =
            list.orEmpty().filter { it.id >= 0 }.map { d ->
                val t = details[d.id]
                val made = Format.Builder()
                    .setId(d.id.toString())
                    .setSampleMimeType(mimeOf(t?.fourcc, type))
                    .setLanguage(t?.language?.takeIf(String::isNotBlank))
                    .setLabel(t?.description?.takeIf(String::isNotBlank))
                if (t is IMedia.AudioTrack) made.setChannelCount(t.channels).setSampleRate(t.rate)
                if (t != null && t.bitrate > 0) made.setAverageBitrate(t.bitrate)
                Choice(d.id, made.build(), d.id == selected)
            }
        audioTracks = choices(p.audioTracks, p.audioTrack, IMedia.Track.Type.Audio)
        subtitles = choices(p.spuTracks, p.spuTrack, IMedia.Track.Type.Text)
        subtitlesOff = p.spuTrack < 0
        // What Media3 cannot read (WMV, RealMedia) VLC still tells the codec, size and rate of.
        if (format == null) {
            (details.values.firstOrNull { it is IMedia.VideoTrack } as? IMedia.VideoTrack)?.let { v ->
                val found = Format.Builder().setSampleMimeType(mimeOf(v.fourcc, IMedia.Track.Type.Video)).setWidth(v.width).setHeight(v.height)
                    .setFrameRate(if (v.frameRateDen > 0) v.frameRateNum.toFloat() / v.frameRateDen else Format.NO_VALUE.toFloat())
                    .setAverageBitrate(v.bitrate.takeIf { it > 0 } ?: Format.NO_VALUE)
                    .build()
                format = found
                judge(found)
            }
        }
    }

    // Where the picture of [f] is decoded, once known for the video on now.
    private fun judge(f: Format) {
        val id = current
        viewModelScope.launch {
            val decoder = withContext(Dispatchers.Default) { hardwareFor(f) }
            if (current != id || format !== f) return@launch
            hardwareDecoder = decoder
            hardware = decoder != null
        }
    }

    /** Plays [choice], or turns subtitles off when it is null. */
    fun choose(type: Int, choice: Choice?) {
        val p = player ?: return
        if (type == C.TRACK_TYPE_AUDIO) choice?.let { p.audioTrack = it.id } else p.spuTrack = choice?.id ?: -1
        readTracks(p)
    }

    fun toggle() = if (playing) pause() else play()

    fun play() {
        val p = player ?: return
        // Failed, it is tried again; seen to its end, it starts over.
        if (failed || ended) {
            open(index)
            return
        }
        if (gainFocus()) p.play()
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    /** Goes on from where the video was left, or plays it from its start. */
    fun answer(goOn: Boolean) {
        val p = player ?: return
        if (asking == null) return
        asking = null
        if (!goOn) p.time = 0
        play()
    }

    fun seek(to: Long) {
        val p = player ?: return
        p.time = to.coerceIn(0, p.length.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }

    fun skip(by: Long) = seek(position + by)

    private val byChapters: Boolean get() = settings.value?.video?.buttons == Buttons.CHAPTERS && chapters.isNotEmpty()

    /** The next chapter while the buttons go by chapters, else the next video. */
    fun next() {
        if (byChapters) {
            chapters.firstOrNull { it.start > position + CHAPTER_SLACK_MS }?.let { return seek(it.start) }
        }
        if (hasNext) {
            keep()
            open(index + 1)
        }
    }

    /** Back to the start of the chapter, or to the one before when it has only just begun; else the video before. */
    fun previous() {
        if (byChapters) {
            val now = position
            val at = chapters.indexOfLast { it.start <= now }
            if (at >= 0 && now - chapters[at].start > CHAPTER_SLACK_MS * 6) return seek(chapters[at].start)
            if (at > 0) return seek(chapters[at - 1].start)
        }
        if (hasPrevious) {
            keep()
            open(index - 1)
        }
    }

    /** Whether the buttons have anywhere to go. */
    val canPrevious: Boolean get() = hasPrevious || byChapters
    val canNext: Boolean get() = hasNext || byChapters

    // While the viewer is asked, VLC stands at that place but tells none.
    val position: Long get() = asking ?: player?.time?.coerceAtLeast(0) ?: 0L

    private fun keep() {
        val p = player ?: return
        val id = current ?: return
        if (resuming != Resuming.NEVER && asking == null) c.resume.keep(id, p.time, p.length)
    }

    // The sound of a video takes the speaker from music; losing it for good or for a while pauses the video.
    private fun gainFocus(): Boolean {
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener { change -> if (change < 0) pause() }
            .build().also { focus = it }
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    // Headphones pulled out pause the video, as they do the music.
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = pause()
    }

    // The session that the headphones' buttons and the picture in picture window drive.
    private fun updateSession() {
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, if (playing) speed else 0f)
                .build(),
        )
    }

    override fun onCleared() {
        keep()
        runCatching { app.unregisterReceiver(noisy) }
        focus?.let { audio.abandonAudioFocusRequest(it) }
        session?.release()
        player?.let {
            it.setEventListener(null)
            it.stop()
            it.vlcVout.detachViews()
            it.release()
        }
        player = null
        vlc?.release()
        vlc = null
        runCatching { descriptor?.close() }
    }

    private companion object {
        const val KEEP_MS = 10_000L
        const val CHAPTER_SLACK_MS = 500L
        const val SLAVE_PRIORITY = 4
        val MATROSKA = setOf("mkv", "webm", "mka")

        // VLC's orientations that lie on their side: left or right on top.
        val TURNED = 4..7

        // Subtitles a little higher stand this share of the picture up; above the controls, past their height.
        const val HIGHER_SHARE = 0.08f
        const val PLAIN_WIDTH = 1920
        const val PLAIN_HEIGHT = 1080
        const val CONTROLS_DP = 80
        const val WHITE = 0xFFFFFF
        const val YELLOW = 0xFFFF00
        const val OUTLINE = 6
        const val BOX_OPACITY = 153

        // VLC's size of the subtitles is the height of the picture over it: the smaller, the larger the words.
        val FONT_SIZES = mapOf(SubtitleSize.SMALL to 20, SubtitleSize.NORMAL to 16, SubtitleSize.LARGE to 13, SubtitleSize.LARGEST to 10)

        const val WMV = "video/x-ms-wmv"

        val AVC_PROFILES = mapOf(
            66 to CodecProfileLevel.AVCProfileBaseline,
            77 to CodecProfileLevel.AVCProfileMain,
            88 to CodecProfileLevel.AVCProfileExtended,
            100 to CodecProfileLevel.AVCProfileHigh,
            110 to CodecProfileLevel.AVCProfileHigh10,
            122 to CodecProfileLevel.AVCProfileHigh422,
            244 to CodecProfileLevel.AVCProfileHigh444,
        )
        val HEVC_PROFILES = mapOf(1 to CodecProfileLevel.HEVCProfileMain, 2 to CodecProfileLevel.HEVCProfileMain10)

        // VLC's codecs, by their four characters, as Media3's types.
        fun mimeOf(fourcc: Int?, type: Int): String = when (fourcc?.let { f -> String(CharArray(4) { (f shr (8 * it) and 0xFF).toChar() }).trim().lowercase() }) {
            "h264", "avc1" -> MimeTypes.VIDEO_H264
            "hevc", "h265" -> MimeTypes.VIDEO_H265
            "av01" -> MimeTypes.VIDEO_AV1
            "vp90", "vp9" -> MimeTypes.VIDEO_VP9
            "vp80", "vp8" -> MimeTypes.VIDEO_VP8
            "mpgv", "mp2v", "mp1v" -> MimeTypes.VIDEO_MPEG2
            "mp4v", "xvid", "divx", "dx50" -> MimeTypes.VIDEO_MP4V
            "wvc1", "vc-1", "wmv3" -> MimeTypes.VIDEO_VC1
            "wmv1", "wmv2" -> WMV
            "h263" -> MimeTypes.VIDEO_H263
            "a52", "ac3" -> MimeTypes.AUDIO_AC3
            "eac3" -> MimeTypes.AUDIO_E_AC3
            "dts" -> MimeTypes.AUDIO_DTS
            "mp4a" -> MimeTypes.AUDIO_AAC
            "mpga", "mp3", "mp2a" -> MimeTypes.AUDIO_MPEG
            "opus" -> MimeTypes.AUDIO_OPUS
            "vorb" -> MimeTypes.AUDIO_VORBIS
            "flac" -> MimeTypes.AUDIO_FLAC
            "alac" -> MimeTypes.AUDIO_ALAC
            "mlp", "trhd" -> MimeTypes.AUDIO_TRUEHD
            "araw", "s16l", "s16b", "s24l", "s24b", "twos", "sowt", "lpcm" -> MimeTypes.AUDIO_RAW
            "subt" -> MimeTypes.APPLICATION_SUBRIP
            "ssa" -> MimeTypes.TEXT_SSA
            "bdpg" -> MimeTypes.APPLICATION_PGS
            "spu" -> MimeTypes.APPLICATION_VOBSUB
            "wvtt" -> MimeTypes.TEXT_VTT
            "ttml" -> MimeTypes.APPLICATION_TTML
            "tx3g" -> MimeTypes.APPLICATION_TX3G
            "dvbs" -> MimeTypes.APPLICATION_DVBSUBS
            else -> when (type) {
                IMedia.Track.Type.Video -> MimeTypes.VIDEO_UNKNOWN
                IMedia.Track.Type.Audio -> MimeTypes.AUDIO_UNKNOWN
                else -> MimeTypes.TEXT_UNKNOWN
            }
        }
    }
}
