package dev.glowcow.mnt.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.glowcow.mnt.files.SortBy
import dev.glowcow.mnt.view.code.CodeScheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Palette { WARM, CLASSIC }

/** The screen's rate set to suit a video's: never, when it changes without a blank, or always. */
enum class FrameRate { OFF, SEAMLESS, ALWAYS }

/** A video turns the screen to its own shape at once, or only as the phone is turned. */
enum class Turn { VIDEO, PHONE }

enum class SubtitleSize(val scale: Float) { SMALL(0.8f), NORMAL(1f), LARGE(1.25f), LARGEST(1.5f) }

/** Where a video opened again starts: the viewer is asked, or it goes on where it was left, or starts over. */
enum class Resuming { ASK, ALWAYS, NEVER }

/** What the previous and next buttons of a video go to: the videos of its folder, or its own chapters. */
enum class Buttons { FILES, CHAPTERS }

/** Whose decoders a video tries first: the phone's, or FFmpeg's in the app. */
enum class Decoding { PHONE, FFMPEG }

/** How videos play, kept as one value. */
@Serializable
data class VideoSettings(
    val frameRate: FrameRate = FrameRate.SEAMLESS,
    /** Seconds a double tap at a side goes back or on. */
    val skipSeconds: Int = 10,
    val turn: Turn = Turn.VIDEO,
    /** Where a video opened again starts; places are kept unless it is never. */
    val resume: Resuming = Resuming.ASK,
    /** A drag up and down on the left half sets the brightness. */
    val swipeBrightness: Boolean = false,
    /** A drag up and down on the right half sets the volume. */
    val swipeVolume: Boolean = true,
    /** A drag along the picture seeks. */
    val swipeSeek: Boolean = true,
    /** Previous and next go through the chapters of a video that has them. */
    val buttons: Buttons = Buttons.FILES,
    /** At its end a video goes on to the next of its folder. */
    val playNext: Boolean = true,
    /** Leaving the app while a video plays shrinks it to picture in picture. */
    val pip: Boolean = true,
    /** Language tags of the audio and the subtitles picked first; empty takes the file's own, [OFF] shows no subtitles. */
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val decoding: Decoding = Decoding.PHONE,
) {
    companion object {
        const val OFF = "off"
        val SKIPS = listOf(5, 10, 15, 30)
    }
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: Palette = Palette.CLASSIC,
    /** Look for a new version of the app once a week. */
    val appUpdate: Boolean = false,
    /** Show files and folders whose name starts with a dot. */
    val showHidden: Boolean = false,
    /** Keep a bin on memory cards and drives too; without it a delete there is for good. */
    val binExternal: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortDescending: Boolean = false,
    /** Folders pinned to the Files tab, in the order they were pinned. */
    val pinned: List<String> = emptyList(),
    /** Names of the well-known folders left out of the Files tab. */
    val hiddenPlaces: Set<String> = emptySet(),
    /** What was searched for, the latest first. */
    val searches: List<String> = emptyList(),
    /** The colours code is shown in while the app is light, and while it is dark. */
    val codeLight: CodeScheme = CodeScheme.defaultLight,
    val codeDark: CodeScheme = CodeScheme.defaultDark,
    /** Long lines of text and code are wrapped to the screen instead of scrolled sideways. */
    val wrapLines: Boolean = true,
    val lineNumbers: Boolean = true,
    /** Word documents are laid out in pages, as they are printed, instead of flowing to the width of the screen. */
    val pages: Boolean = true,
    /** A link opens in the app's own browser page instead of the phone's browser. */
    val linksInApp: Boolean = true,
    /** HDR photos are shown with their bright parts as bright as the screen can, by the app's own drawing. */
    val ultraHdr: Boolean = true,
    /** Pictures in a folder show themselves small in place of their icons. */
    val thumbnails: Boolean = true,
    /** Thumbnails of pictures on servers and USB devices too, which fetches them. */
    val thumbnailsFar: Boolean = true,
    /** Videos show a frame of theirs in place of their icons too. */
    val thumbnailsVideo: Boolean = true,
    /** A photo's details show a map of where it was taken, downloaded from OpenStreetMap. */
    val photoMap: Boolean = false,
    /** The player draws the loudness of the track along its seek bar, which reads the whole file of a track. */
    val waves: Boolean = false,
    val video: VideoSettings = VideoSettings(),
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val paletteKey = stringPreferencesKey("palette")
    private val appUpdateKey = booleanPreferencesKey("app_update")
    private val showHiddenKey = booleanPreferencesKey("show_hidden")
    private val binExternalKey = booleanPreferencesKey("bin_external")
    private val sortByKey = stringPreferencesKey("sort_by")
    private val sortDescendingKey = booleanPreferencesKey("sort_descending")
    private val pinnedKey = stringPreferencesKey("pinned")
    private val searchesKey = stringPreferencesKey("searches")
    private val hiddenPlacesKey = stringSetPreferencesKey("hidden_places")
    private val codeLightKey = stringPreferencesKey("code_light")
    private val codeDarkKey = stringPreferencesKey("code_dark")
    private val wrapLinesKey = booleanPreferencesKey("wrap_lines")
    private val lineNumbersKey = booleanPreferencesKey("line_numbers")
    private val linksInAppKey = booleanPreferencesKey("links_in_app")
    private val pagesKey = booleanPreferencesKey("pages")
    private val ultraHdrKey = booleanPreferencesKey("ultra_hdr")
    private val thumbnailsKey = booleanPreferencesKey("thumbnails")
    private val thumbnailsFarKey = booleanPreferencesKey("thumbnails_far")
    private val thumbnailsVideoKey = booleanPreferencesKey("thumbnails_video")
    private val photoMapKey = booleanPreferencesKey("photo_map")
    private val wavesKey = booleanPreferencesKey("waves")
    private val videoKey = stringPreferencesKey("video")

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            palette = p[paletteKey]?.let { runCatching { Palette.valueOf(it) }.getOrNull() } ?: Palette.CLASSIC,
            appUpdate = p[appUpdateKey] ?: false,
            showHidden = p[showHiddenKey] ?: false,
            binExternal = p[binExternalKey] ?: false,
            sortBy = p[sortByKey]?.let { runCatching { SortBy.valueOf(it) }.getOrNull() } ?: SortBy.NAME,
            sortDescending = p[sortDescendingKey] ?: false,
            pinned = p[pinnedKey].list(),
            searches = p[searchesKey].list(),
            hiddenPlaces = p[hiddenPlacesKey].orEmpty(),
            codeLight = p[codeLightKey].scheme() ?: CodeScheme.defaultLight,
            codeDark = p[codeDarkKey].scheme() ?: CodeScheme.defaultDark,
            wrapLines = p[wrapLinesKey] ?: true,
            lineNumbers = p[lineNumbersKey] ?: true,
            linksInApp = p[linksInAppKey] ?: true,
            pages = p[pagesKey] ?: true,
            ultraHdr = p[ultraHdrKey] ?: true,
            thumbnails = p[thumbnailsKey] ?: true,
            thumbnailsFar = p[thumbnailsFarKey] ?: true,
            thumbnailsVideo = p[thumbnailsVideoKey] ?: true,
            photoMap = p[photoMapKey] ?: false,
            waves = p[wavesKey] ?: false,
            video = p[videoKey]?.let { runCatching { lenient.decodeFromString<VideoSettings>(it) }.getOrNull() } ?: VideoSettings(),
        )
    }

    /** Replaces every setting at once, as when a backup is restored over what is here. */
    suspend fun restore(s: AppSettings) = context.dataStore.edit {
        it[themeKey] = s.theme.name
        it[paletteKey] = s.palette.name
        it[appUpdateKey] = s.appUpdate
        it[showHiddenKey] = s.showHidden
        it[binExternalKey] = s.binExternal
        it[sortByKey] = s.sortBy.name
        it[sortDescendingKey] = s.sortDescending
        it[pinnedKey] = Json.encodeToString(s.pinned)
        it[hiddenPlacesKey] = s.hiddenPlaces
        it[searchesKey] = Json.encodeToString(s.searches.take(SEARCHES_KEPT))
        it[codeLightKey] = s.codeLight.name
        it[codeDarkKey] = s.codeDark.name
        it[wrapLinesKey] = s.wrapLines
        it[lineNumbersKey] = s.lineNumbers
        it[linksInAppKey] = s.linksInApp
        it[pagesKey] = s.pages
        it[ultraHdrKey] = s.ultraHdr
        it[thumbnailsKey] = s.thumbnails
        it[thumbnailsFarKey] = s.thumbnailsFar
        it[thumbnailsVideoKey] = s.thumbnailsVideo
        it[photoMapKey] = s.photoMap
        it[wavesKey] = s.waves
        it[videoKey] = lenient.encodeToString(s.video)
    }

    /** Adds pinned folders and searches to the ones kept, those already here staying where they are. */
    suspend fun add(pinned: List<String>, searches: List<String>) = context.dataStore.edit {
        it[pinnedKey] = Json.encodeToString((it[pinnedKey].list() + pinned).distinct())
        it[searchesKey] = Json.encodeToString((it[searchesKey].list() + searches).distinct().take(SEARCHES_KEPT))
    }

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[themeKey] = mode.name }

    suspend fun setPalette(palette: Palette) = context.dataStore.edit { it[paletteKey] = palette.name }

    suspend fun setAppUpdate(on: Boolean) = context.dataStore.edit { it[appUpdateKey] = on }

    /** The colours of code for the app's light theme, or for its dark one. */
    suspend fun setCodeScheme(dark: Boolean, scheme: CodeScheme) = context.dataStore.edit { it[if (dark) codeDarkKey else codeLightKey] = scheme.name }

    suspend fun setWrapLines(on: Boolean) = context.dataStore.edit { it[wrapLinesKey] = on }

    suspend fun setLineNumbers(on: Boolean) = context.dataStore.edit { it[lineNumbersKey] = on }

    suspend fun setPages(on: Boolean) = context.dataStore.edit { it[pagesKey] = on }

    suspend fun setLinksInApp(on: Boolean) = context.dataStore.edit { it[linksInAppKey] = on }

    suspend fun setUltraHdr(on: Boolean) = context.dataStore.edit { it[ultraHdrKey] = on }

    suspend fun setThumbnails(on: Boolean) = context.dataStore.edit { it[thumbnailsKey] = on }

    suspend fun setThumbnailsFar(on: Boolean) = context.dataStore.edit { it[thumbnailsFarKey] = on }

    suspend fun setThumbnailsVideo(on: Boolean) = context.dataStore.edit { it[thumbnailsVideoKey] = on }

    suspend fun setPhotoMap(on: Boolean) = context.dataStore.edit { it[photoMapKey] = on }

    suspend fun setWaves(on: Boolean) = context.dataStore.edit { it[wavesKey] = on }

    /** Changes the video settings by [change]. */
    suspend fun setVideo(change: (VideoSettings) -> VideoSettings) = context.dataStore.edit {
        val now = it[videoKey]?.let { text -> runCatching { lenient.decodeFromString<VideoSettings>(text) }.getOrNull() } ?: VideoSettings()
        it[videoKey] = lenient.encodeToString(change(now))
    }

    suspend fun setShowHidden(on: Boolean) = context.dataStore.edit { it[showHiddenKey] = on }

    suspend fun setBinExternal(on: Boolean) = context.dataStore.edit { it[binExternalKey] = on }

    suspend fun setSort(by: SortBy, descending: Boolean) = context.dataStore.edit {
        it[sortByKey] = by.name
        it[sortDescendingKey] = descending
    }

    /** Pins [path], or unpins it when it is pinned. */
    suspend fun togglePinned(path: String) = context.dataStore.edit {
        val pinned = it[pinnedKey].list()
        it[pinnedKey] = Json.encodeToString(if (path in pinned) pinned - path else pinned + path)
    }

    /** Puts [query] at the head of the searches kept; an older one that differs only in case gives way to it. */
    suspend fun rememberSearch(query: String) = context.dataStore.edit {
        val kept = it[searchesKey].list().filterNot { old -> old.equals(query, ignoreCase = true) }
        it[searchesKey] = Json.encodeToString((listOf(query) + kept).take(SEARCHES_KEPT))
    }

    /** Lists the well-known folder called [place] on the Files tab, or leaves it out. */
    suspend fun setPlaceShown(place: String, shown: Boolean) = context.dataStore.edit {
        it[hiddenPlacesKey] = it[hiddenPlacesKey].orEmpty().let { hidden -> if (shown) hidden - place else hidden + place }
    }

    suspend fun clearSearches() = context.dataStore.edit { it.remove(searchesKey) }

    private fun String?.scheme(): CodeScheme? = this?.let { runCatching { CodeScheme.valueOf(it) }.getOrNull() }

    private fun String?.list(): List<String> = this?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

    /** True the first time it is called for [task]. */
    suspend fun firstRun(task: String): Boolean {
        val key = booleanPreferencesKey("done_$task")
        var first = false
        context.dataStore.edit {
            first = it[key] != true
            it[key] = true
        }
        return first
    }
}

private const val SEARCHES_KEPT = 20

// A value written by another version reads with what it lacks at its default.
private val lenient = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}
