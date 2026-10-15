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
import kotlinx.serialization.json.Json

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Palette { WARM, CLASSIC }

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
