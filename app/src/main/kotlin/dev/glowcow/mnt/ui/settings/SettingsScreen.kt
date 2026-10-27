package dev.glowcow.mnt.ui.settings

import android.Manifest
import android.app.LocaleManager
import android.net.Uri
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glowcow.mnt.MntApp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.audio.AudioCache
import dev.glowcow.mnt.backup.Backup
import dev.glowcow.mnt.backup.RestoreOutcome
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.data.Palette
import dev.glowcow.mnt.data.SettingsStore
import dev.glowcow.mnt.data.ThemeMode
import dev.glowcow.mnt.data.Buttons
import dev.glowcow.mnt.data.FrameRate
import dev.glowcow.mnt.data.Resuming
import dev.glowcow.mnt.data.SubtitleBackground
import dev.glowcow.mnt.data.SubtitleColor
import dev.glowcow.mnt.data.SubtitleEncoding
import dev.glowcow.mnt.data.SubtitlePosition
import dev.glowcow.mnt.data.SubtitleSize
import dev.glowcow.mnt.data.Turn
import dev.glowcow.mnt.data.VideoSettings
import dev.glowcow.mnt.files.Place
import dev.glowcow.mnt.ui.components.CheckRow
import dev.glowcow.mnt.ui.components.ChoiceSheet
import dev.glowcow.mnt.ui.components.DetailScreen
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupField
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.components.PreviewSheet
import dev.glowcow.mnt.ui.components.SwitchRow
import dev.glowcow.mnt.ui.components.TabScreen
import dev.glowcow.mnt.ui.components.TopTab
import dev.glowcow.mnt.ui.files.label
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.ui.theme.colorsOf
import dev.glowcow.mnt.ui.view.CodePreview
import dev.glowcow.mnt.maps.TileStore
import dev.glowcow.mnt.update.AppRelease
import dev.glowcow.mnt.update.AppUpdateState
import dev.glowcow.mnt.update.AppUpdater
import dev.glowcow.mnt.view.code.CodeScheme
import dev.glowcow.mnt.view.image.Thumbnails
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val store: SettingsStore,
    val updater: AppUpdater,
    private val backup: Backup,
    private val tiles: TileStore,
    private val audio: AudioCache,
    private val thumbnails: Thumbnails,
) : ViewModel() {
    /** Bytes of downloaded map on the phone; null until counted. */
    var mapCache by mutableStateOf<Long?>(null)
        private set

    /** Bytes of tracks of the servers kept on the phone; null until counted. */
    var audioCache by mutableStateOf<Long?>(null)
        private set

    /** Bytes of thumbnails kept on the phone; null until counted. */
    var thumbnailCache by mutableStateOf<Long?>(null)
        private set

    init {
        viewModelScope.launch { mapCache = tiles.size() }
        viewModelScope.launch { audioCache = audio.size() }
        viewModelScope.launch { thumbnailCache = withContext(Dispatchers.IO) { thumbnails.size() } }
    }

    fun clearThumbnails() = viewModelScope.launch {
        thumbnailCache = withContext(Dispatchers.IO) {
            thumbnails.clear()
            thumbnails.size()
        }
    }

    fun clearAudioCache() = viewModelScope.launch {
        audio.clear()
        audioCache = audio.size()
    }

    fun setPhotoMap(on: Boolean) = viewModelScope.launch { store.setPhotoMap(on) }

    fun setWaves(on: Boolean) = viewModelScope.launch { store.setWaves(on) }

    fun setVideo(change: (VideoSettings) -> VideoSettings) = viewModelScope.launch { store.setVideo(change) }

    fun clearMapCache() = viewModelScope.launch {
        tiles.clear()
        mapCache = tiles.size()
    }

    /** A backup is being written or read. */
    var busy by mutableStateOf(false)
        private set

    fun saveBackup(uri: Uri, password: String, onDone: (Boolean) -> Unit) = work { onDone(backup.save(uri, password)) }

    /** Tells whether the file at [uri] wants a password; null when it cannot be read. */
    fun inspectBackup(uri: Uri, onDone: (Boolean?) -> Unit) = work { onDone(backup.isEncrypted(uri)) }

    fun restoreBackup(uri: Uri, password: String, overwrite: Boolean, onDone: (RestoreOutcome) -> Unit) = work {
        onDone(backup.restore(uri, password, overwrite))
    }

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    /** Null until the stored settings are read: a switch drawn from a default would be seen moving to its real place. */
    val settings: StateFlow<AppSettings?> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { store.setTheme(mode) }
    fun setPalette(palette: Palette) = viewModelScope.launch { store.setPalette(palette) }
    fun setShowHidden(on: Boolean) = viewModelScope.launch { store.setShowHidden(on) }
    fun setCodeScheme(dark: Boolean, scheme: CodeScheme) = viewModelScope.launch { store.setCodeScheme(dark, scheme) }
    fun setPages(on: Boolean) = viewModelScope.launch { store.setPages(on) }

    fun setWrapLines(on: Boolean) = viewModelScope.launch { store.setWrapLines(on) }
    fun setLineNumbers(on: Boolean) = viewModelScope.launch { store.setLineNumbers(on) }
    fun setLinksInApp(on: Boolean) = viewModelScope.launch { store.setLinksInApp(on) }

    fun setUltraHdr(on: Boolean) = viewModelScope.launch { store.setUltraHdr(on) }

    fun setThumbnails(on: Boolean) = viewModelScope.launch { store.setThumbnails(on) }

    fun setThumbnailsFar(on: Boolean) = viewModelScope.launch { store.setThumbnailsFar(on) }

    fun setThumbnailsVideo(on: Boolean) = viewModelScope.launch { store.setThumbnailsVideo(on) }

    fun setBinExternal(on: Boolean) = viewModelScope.launch { store.setBinExternal(on) }
    fun setAppUpdate(on: Boolean) = viewModelScope.launch { store.setAppUpdate(on) }
    fun clearSearches() = viewModelScope.launch { store.clearSearches() }
    fun setPlaceShown(place: Place, shown: Boolean) = viewModelScope.launch { store.setPlaceShown(place.name, shown) }
}

/** The subjects the settings are grouped by, each a page of its own. */
enum class SettingsPage { APPEARANCE, FILES, VIEWER, VIDEO, BACKUP, CREDITS }

@Composable
fun SettingsScreen(
    onTab: (TopTab) -> Unit,
    onPage: (SettingsPage) -> Unit,
    vm: SettingsViewModel = viewModel { (this[APPLICATION_KEY] as MntApp).container.let { SettingsViewModel(it.settings, it.appUpdater, it.backup, it.tiles, it.audioCache, it.thumbnails) } },
) {
    val c = MntTheme.colors
    val stored by vm.settings.collectAsStateWithLifecycle()
    val update by vm.updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locales = remember(context) { context.getSystemService(LocaleManager::class.java) }
    val language = locales.applicationLocales.takeUnless { it.isEmpty }?.get(0)?.language
    var release by remember { mutableStateOf<AppRelease?>(null) }
    // The weekly check reports through a notification, so turning it on asks for the permission.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun setAppUpdate(on: Boolean) {
        vm.setAppUpdate(on)
        if (on) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val settings = stored
    TabScreen(TopTab.SETTINGS, onTab) { top, bottom ->
        if (settings != null) Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = top, bottom = bottom + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Group {
                GroupRow(
                    stringResource(R.string.settings_appearance),
                    subtitle = listOf(
                        stringResource(THEMES.first { it.first == settings.theme }.second),
                        stringResource(PALETTES.first { it.first == settings.palette }.second),
                        LANGUAGES.firstOrNull { it.first == language }?.second ?: stringResource(R.string.language_system),
                    ).distinct().joinToString(" · "),
                    icon = MntIcons.Palette,
                    onClick = { onPage(SettingsPage.APPEARANCE) },
                )
                GroupDivider()
                GroupRow(stringResource(R.string.settings_files), subtitle = stringResource(R.string.settings_files_hint), icon = MntIcons.Folder, onClick = { onPage(SettingsPage.FILES) })
                GroupDivider()
                GroupRow(stringResource(R.string.settings_viewer), subtitle = stringResource(R.string.settings_viewer_hint), icon = MntIcons.FileText, onClick = { onPage(SettingsPage.VIEWER) })
                GroupDivider()
                GroupRow(stringResource(R.string.settings_video), subtitle = stringResource(R.string.settings_video_hint), icon = MntIcons.Film, onClick = { onPage(SettingsPage.VIDEO) })
                GroupDivider()
                GroupRow(stringResource(R.string.settings_backup), subtitle = stringResource(R.string.settings_backup_save_hint), icon = MntIcons.Floppy, onClick = { onPage(SettingsPage.BACKUP) })
            }
            Group {
                GroupRow(
                    stringResource(R.string.settings_version),
                    subtitle = when (val s = update) {
                        AppUpdateState.Idle -> if (vm.updater.supported) stringResource(R.string.app_update_check) else stringResource(R.string.app_update_dev)
                        AppUpdateState.Checking -> stringResource(R.string.app_update_checking)
                        AppUpdateState.UpToDate -> stringResource(R.string.app_update_latest)
                        AppUpdateState.Failed -> stringResource(R.string.app_update_failed)
                        is AppUpdateState.Available -> stringResource(R.string.app_update_available, s.release.version)
                        is AppUpdateState.Downloading -> stringResource(R.string.app_update_downloading, (s.progress * 100).toInt())
                    },
                    value = vm.updater.current,
                    onClick = when (val s = update) {
                        AppUpdateState.Checking, is AppUpdateState.Downloading -> null
                        is AppUpdateState.Available -> ({ release = s.release })
                        else -> if (vm.updater.supported) vm.updater::checkNow else null
                    },
                )
                if (vm.updater.supported) {
                    GroupDivider()
                    SwitchRow(stringResource(R.string.settings_app_update), stringResource(R.string.settings_app_update_hint), settings.appUpdate, ::setAppUpdate)
                }
            }
            Group {
                GroupRow(stringResource(R.string.settings_credits), icon = MntIcons.Scroll, onClick = { onPage(SettingsPage.CREDITS) })
            }
        }
    }

    release?.let { r ->
        GroupSheet(stringResource(R.string.app_update_available, r.version), onDismiss = { release = null }) { pick ->
            if (r.notes.isNotEmpty()) {
                Text(
                    r.notes,
                    color = c.muted,
                    fontSize = 14.sp,
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, bottom = 14.dp),
                )
            }
            Group { GroupRow(stringResource(R.string.app_update_install), onClick = { pick { vm.updater.install(r) } }) }
        }
    }
}

/** One subject of the settings on a page of its own. */
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel { (this[APPLICATION_KEY] as MntApp).container.let { SettingsViewModel(it.settings, it.appUpdater, it.backup, it.tiles, it.audioCache, it.thumbnails) } },
) {
    val stored by vm.settings.collectAsStateWithLifecycle()
    val title = stringResource(
        when (page) {
            SettingsPage.APPEARANCE -> R.string.settings_appearance
            SettingsPage.FILES -> R.string.settings_files
            SettingsPage.VIEWER -> R.string.settings_viewer
            SettingsPage.VIDEO -> R.string.settings_video
            SettingsPage.BACKUP -> R.string.settings_backup
            SettingsPage.CREDITS -> R.string.settings_credits
        },
    )
    val settings = stored
    when (page) {
        SettingsPage.APPEARANCE -> Appearance(title, settings, vm, onBack)
        SettingsPage.FILES -> DetailScreen(title, onBack) { if (settings != null) Files(settings, vm) }
        SettingsPage.VIEWER -> Viewer(title, settings, vm, onBack)
        SettingsPage.VIDEO -> VideoPage(title, settings, vm, onBack)
        SettingsPage.BACKUP -> BackupPage(title, vm, onBack)
        SettingsPage.CREDITS -> DetailScreen(title, onBack) {
            Group {
                GroupRow(stringResource(R.string.settings_licence), value = "GPL-3.0-or-later")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_icons), value = "Material Icon Theme, Phosphor · MIT")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_font), value = "Arimo · SIL OFL 1.1")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_title_font), value = "Cormorant Garamond · SIL OFL 1.1")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_code_font), value = "JetBrains Mono · SIL OFL 1.1")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_document_font), value = "Tinos · SIL OFL 1.1")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_djvu), value = "DjVuLibre · GPL-2.0-or-later")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_archives), value = "libarchive, XZ Utils, Zstandard, bzip2 · BSD")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_raw), value = "LibRaw · LGPL-2.1")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_pictures), value = "libjxl, Highway, libtiff, libde265, AndroidSVG · BSD, Apache-2.0, LGPL-3.0")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_map_data), value = "OpenStreetMap · ODbL")
                GroupDivider()
                GroupRow(stringResource(R.string.settings_sound_decoding), value = "LibVLC, FFmpeg, Jellyfin's Media3 decoder · LGPL-2.1, GPL-3.0")
            }
        }
    }
}

@Composable
private fun Appearance(title: String, settings: AppSettings?, vm: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    // The system keeps the per-app language, shared with Settings → Apps → App language.
    val locales = remember(context) { context.getSystemService(LocaleManager::class.java) }
    var language by remember { mutableStateOf(locales.applicationLocales.takeUnless { it.isEmpty }?.get(0)?.language) }
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    fun setLanguage(tag: String?) {
        language = tag
        locales.applicationLocales = tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
    }
    DetailScreen(title, onBack) {
        if (settings != null) Group {
            GroupRow(stringResource(R.string.settings_theme), value = stringResource(THEMES.first { it.first == settings.theme }.second), onClick = { picker = Picker.THEME })
            GroupDivider()
            GroupRow(stringResource(R.string.settings_palette), value = stringResource(PALETTES.first { it.first == settings.palette }.second), onClick = { picker = Picker.PALETTE })
            GroupDivider()
            GroupRow(
                stringResource(R.string.settings_language),
                value = LANGUAGES.firstOrNull { it.first == language }?.second ?: stringResource(R.string.language_system),
                onClick = { picker = Picker.LANGUAGE },
            )
        }
    }
    if (settings != null) when (picker) {
        Picker.THEME -> ChoiceSheet(
            title = stringResource(R.string.settings_theme),
            options = THEMES.map { (mode, label) -> mode to stringResource(label) },
            selected = settings.theme,
            onSelect = { vm.setTheme(it) },
            onDismiss = { picker = null },
        )
        Picker.PALETTE -> ChoiceSheet(
            title = stringResource(R.string.settings_palette),
            options = PALETTES.map { (palette, label) -> palette to stringResource(label) },
            selected = settings.palette,
            onSelect = { vm.setPalette(it) },
            onDismiss = { picker = null },
        )
        Picker.LANGUAGE -> ChoiceSheet(
            title = stringResource(R.string.settings_language),
            options = listOf<Pair<String?, String>>(null to stringResource(R.string.language_system)) + LANGUAGES,
            selected = language,
            onSelect = ::setLanguage,
            onDismiss = { picker = null },
        )
        else -> Unit
    }
}

@Composable
private fun ColumnScope.Files(settings: AppSettings, vm: SettingsViewModel) {
    var places by rememberSaveable { mutableStateOf(false) }
    Group {
        GroupRow(
            stringResource(R.string.settings_places),
            subtitle = stringResource(R.string.settings_places_hint),
            value = "${Place.entries.size - settings.hiddenPlaces.size} / ${Place.entries.size}",
            onClick = { places = true },
        )
        GroupDivider()
        SwitchRow(stringResource(R.string.settings_hidden), stringResource(R.string.settings_hidden_hint), settings.showHidden, vm::setShowHidden)
        GroupDivider()
        SwitchRow(stringResource(R.string.settings_bin_external), stringResource(R.string.settings_bin_external_hint), settings.binExternal, vm::setBinExternal)
        GroupDivider()
        SwitchRow(stringResource(R.string.settings_thumbnails), stringResource(R.string.settings_thumbnails_hint), settings.thumbnails, vm::setThumbnails)
        if (settings.thumbnails) {
            GroupDivider()
            SwitchRow(stringResource(R.string.settings_thumbnails_far), stringResource(R.string.settings_thumbnails_far_hint), settings.thumbnailsFar, vm::setThumbnailsFar)
            GroupDivider()
            SwitchRow(stringResource(R.string.settings_thumbnails_video), stringResource(R.string.settings_thumbnails_video_hint), settings.thumbnailsVideo, vm::setThumbnailsVideo)
        }
        GroupDivider()
        GroupRow(
            stringResource(R.string.settings_thumbnails_clear),
            subtitle = stringResource(R.string.settings_thumbnails_clear_hint),
            value = vm.thumbnailCache?.let { android.text.format.Formatter.formatShortFileSize(LocalContext.current, it) },
            onClick = if ((vm.thumbnailCache ?: 0L) > 0L) ({ vm.clearThumbnails() }) else null,
            trailing = {},
        )
        GroupDivider()
        // The value is how many searches are kept; with none there is nothing to tap for.
        GroupRow(
            stringResource(R.string.settings_search_clear),
            subtitle = stringResource(R.string.settings_search_clear_hint),
            value = settings.searches.size.toString(),
            onClick = if (settings.searches.isEmpty()) null else ({ vm.clearSearches() }),
            trailing = {},
        )
    }
    // Several may be on at once, so the sheet stays open while they are ticked.
    if (places) {
        GroupSheet(stringResource(R.string.settings_places), onDismiss = { places = false }) {
            Group {
                Place.entries.forEachIndexed { i, place ->
                    if (i > 0) GroupDivider()
                    CheckRow(stringResource(place.label), place.name !in settings.hiddenPlaces) { vm.setPlaceShown(place, it) }
                }
            }
        }
    }
}

@Composable
private fun Viewer(title: String, settings: AppSettings?, vm: SettingsViewModel, onBack: () -> Unit) {
    var picking by rememberSaveable { mutableStateOf<Boolean?>(null) }
    DetailScreen(title, onBack) {
        if (settings == null) return@DetailScreen
        Group {
            GroupRow(stringResource(R.string.settings_code_light), value = stringResource(SCHEMES.getValue(settings.codeLight)), onClick = { picking = false })
            GroupDivider()
            GroupRow(stringResource(R.string.settings_code_dark), value = stringResource(SCHEMES.getValue(settings.codeDark)), onClick = { picking = true })
        }
        Group {
            SwitchRow(stringResource(R.string.settings_wrap), stringResource(R.string.settings_wrap_hint), settings.wrapLines, vm::setWrapLines)
            GroupDivider()
            SwitchRow(stringResource(R.string.settings_numbers), stringResource(R.string.settings_numbers_hint), settings.lineNumbers, vm::setLineNumbers)
        }
        Group {
            SwitchRow(stringResource(R.string.settings_pages), stringResource(R.string.settings_pages_hint), settings.pages, vm::setPages)
        }
        Group {
            SwitchRow(stringResource(R.string.settings_ultra_hdr), stringResource(R.string.settings_ultra_hdr_hint), settings.ultraHdr, vm::setUltraHdr)
        }
        Group {
            SwitchRow(stringResource(R.string.settings_photo_map), stringResource(R.string.settings_photo_map_hint), settings.photoMap, vm::setPhotoMap)
            GroupDivider()
            // With nothing downloaded there is nothing to clear.
            GroupRow(
                stringResource(R.string.settings_map_clear),
                value = vm.mapCache?.let { android.text.format.Formatter.formatShortFileSize(LocalContext.current, it) },
                onClick = if ((vm.mapCache ?: 0L) > 0L) ({ vm.clearMapCache() }) else null,
                trailing = {},
            )
        }
        Group {
            SwitchRow(stringResource(R.string.settings_waves), stringResource(R.string.settings_waves_hint), settings.waves, vm::setWaves)
            GroupDivider()
            // With nothing kept there is nothing to clear.
            GroupRow(
                stringResource(R.string.settings_audio_clear),
                subtitle = stringResource(R.string.settings_audio_clear_hint),
                value = vm.audioCache?.let { android.text.format.Formatter.formatShortFileSize(LocalContext.current, it) },
                onClick = if ((vm.audioCache ?: 0L) > 0L) ({ vm.clearAudioCache() }) else null,
                trailing = {},
            )
        }
        Group {
            SwitchRow(stringResource(R.string.settings_links), stringResource(R.string.settings_links_hint), settings.linksInApp, vm::setLinksInApp)
        }
    }
    val dark = picking
    if (settings != null && dark != null) {
        val wide = LocalConfiguration.current.isScreenWideColorGamut
        PreviewSheet(
            title = stringResource(if (dark) R.string.settings_code_dark else R.string.settings_code_light),
            options = CodeScheme.entries.map { it to stringResource(SCHEMES.getValue(it)) },
            selected = if (dark) settings.codeDark else settings.codeLight,
            onSelect = { vm.setCodeScheme(dark, it) },
            onDismiss = { picking = null },
        ) { scheme -> CodePreview(scheme, colorsOf(settings.palette, scheme.dark ?: dark, wide)) }
    }
}

@Composable
private fun BackupPage(title: String, vm: SettingsViewModel, onBack: () -> Unit) {
    val c = MntTheme.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    // Where a backup goes and where it comes from is the user's pick in the system file picker.
    var sheet by rememberSaveable { mutableStateOf<BackupSheet?>(null) }
    var password by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<Uri?>(null) }
    var locked by remember { mutableStateOf(false) }
    var overwrite by remember { mutableStateOf(false) }
    fun toast(text: Int) = Toast.makeText(context, resources.getString(text), Toast.LENGTH_SHORT).show()
    fun restored(outcome: RestoreOutcome) {
        toast(
            when (outcome) {
                RestoreOutcome.RESTORED -> R.string.backup_restored
                RestoreOutcome.WRONG_PASSWORD -> R.string.backup_wrong_password
                RestoreOutcome.INVALID -> R.string.backup_invalid
            },
        )
        // A mistyped password is asked for again, for the same file.
        if (outcome == RestoreOutcome.WRONG_PASSWORD) sheet = BackupSheet.RESTORE
    }
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveBackup(uri, password) { toast(if (it) R.string.backup_saved else R.string.backup_save_failed) }
        password = ""
    }
    val restoreFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.inspectBackup(uri) { encrypted ->
                if (encrypted == null) return@inspectBackup restored(RestoreOutcome.INVALID)
                source = uri
                locked = encrypted
                overwrite = false
                sheet = BackupSheet.RESTORE
            }
        }
    }
    DetailScreen(title, onBack) {
        Group {
            GroupRow(
                stringResource(R.string.settings_backup_save),
                subtitle = stringResource(R.string.settings_backup_save_hint),
                onClick = if (vm.busy) null else ({ sheet = BackupSheet.SAVE }),
            )
            GroupDivider()
            GroupRow(
                stringResource(R.string.settings_backup_restore),
                subtitle = stringResource(R.string.settings_backup_restore_hint),
                onClick = if (vm.busy) null else ({ restoreFrom.launch(arrayOf("*/*")) }),
            )
        }
    }
    when (sheet) {
        BackupSheet.SAVE -> GroupSheet(stringResource(R.string.settings_backup_save), onDismiss = { sheet = null }) { pick ->
            Text(stringResource(R.string.backup_password_note), color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
            PasswordField(password) { password = it }
            Group(Modifier.padding(top = 12.dp)) {
                GroupRow(stringResource(R.string.backup_save_pick), onClick = { pick { saveTo.launch(backupName()) } })
            }
        }
        BackupSheet.RESTORE -> GroupSheet(stringResource(R.string.settings_backup_restore), onDismiss = { sheet = null }) { pick ->
            var typed by remember { mutableStateOf("") }
            if (locked) {
                Text(stringResource(R.string.backup_encrypted), color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
                PasswordField(typed) { typed = it }
            }
            Group(Modifier.padding(top = if (locked) 12.dp else 0.dp)) {
                SwitchRow(stringResource(R.string.backup_overwrite), stringResource(R.string.backup_overwrite_hint), overwrite) { overwrite = it }
            }
            Group(Modifier.padding(top = 12.dp)) {
                GroupRow(stringResource(R.string.backup_restore), onClick = { pick { source?.let { vm.restoreBackup(it, typed, overwrite, ::restored) } } })
            }
        }
        null -> Unit
    }
}

private enum class Picker { THEME, PALETTE, LANGUAGE }

private enum class VideoPicker { RATE, SKIP, TURN, RESUME, BUTTONS, AUDIO, SUBTITLES, SIZE, POSITION, COLOR, BACKGROUND, ENCODING }

@Composable
private fun VideoPage(title: String, settings: AppSettings?, vm: SettingsViewModel, onBack: () -> Unit) {
    var picking by rememberSaveable { mutableStateOf<VideoPicker?>(null) }
    val v = settings?.video
    fun set(change: (VideoSettings) -> VideoSettings) = vm.setVideo(change)
    val fileOwn = stringResource(R.string.settings_video_file_language)
    val off = stringResource(R.string.video_off)
    fun languageName(tag: String) = when (tag) {
        "" -> fileOwn
        VideoSettings.OFF -> off
        else -> LANGUAGES.firstOrNull { it.first == tag }?.second ?: tag
    }
    DetailScreen(title, onBack) {
        if (v == null) return@DetailScreen
        Group {
            GroupRow(stringResource(R.string.settings_video_rate), subtitle = stringResource(R.string.settings_video_rate_hint), value = stringResource(RATES.getValue(v.frameRate)), onClick = { picking = VideoPicker.RATE })
            GroupDivider()
            GroupRow(stringResource(R.string.settings_video_turn), value = stringResource(TURNS.getValue(v.turn)), onClick = { picking = VideoPicker.TURN })
            GroupDivider()
            SwitchRow(stringResource(R.string.settings_video_status), stringResource(R.string.settings_video_status_hint), v.statusBar) { on -> set { it.copy(statusBar = on) } }
            GroupDivider()
            SwitchRow(stringResource(R.string.settings_video_pip), stringResource(R.string.settings_video_pip_hint), v.pip) { on -> set { it.copy(pip = on) } }
        }
        Group {
            GroupRow(stringResource(R.string.settings_video_skip), value = stringResource(R.string.video_seconds, v.skipSeconds), onClick = { picking = VideoPicker.SKIP })
            GroupDivider()
            SwitchRow(stringResource(R.string.settings_video_brightness), stringResource(R.string.settings_video_brightness_hint), v.swipeBrightness) { on -> set { it.copy(swipeBrightness = on) } }
            GroupDivider()
            SwitchRow(stringResource(R.string.settings_video_volume), stringResource(R.string.settings_video_volume_hint), v.swipeVolume) { on -> set { it.copy(swipeVolume = on) } }
            GroupDivider()
            SwitchRow(stringResource(R.string.settings_video_seek), stringResource(R.string.settings_video_seek_hint), v.swipeSeek) { on -> set { it.copy(swipeSeek = on) } }
        }
        Group {
            GroupRow(stringResource(R.string.settings_video_resume), subtitle = stringResource(R.string.settings_video_resume_hint), value = stringResource(RESUMING.getValue(v.resume)), onClick = { picking = VideoPicker.RESUME })
            GroupDivider()
            SwitchRow(stringResource(R.string.settings_video_next), stringResource(R.string.settings_video_next_hint), v.playNext) { on -> set { it.copy(playNext = on) } }
            GroupDivider()
            GroupRow(stringResource(R.string.settings_video_buttons), subtitle = stringResource(R.string.settings_video_buttons_hint), value = stringResource(BUTTONS.getValue(v.buttons)), onClick = { picking = VideoPicker.BUTTONS })
        }
        Group {
            GroupRow(stringResource(R.string.settings_video_audio), value = languageName(v.audioLanguage), onClick = { picking = VideoPicker.AUDIO })
            GroupDivider()
            GroupRow(stringResource(R.string.settings_video_subtitles), value = languageName(v.subtitleLanguage), onClick = { picking = VideoPicker.SUBTITLES })
            GroupDivider()
            GroupRow(stringResource(R.string.settings_video_size), value = stringResource(SIZES.getValue(v.subtitleSize)), onClick = { picking = VideoPicker.SIZE })
            GroupDivider()
            GroupRow(stringResource(R.string.settings_video_position), value = stringResource(POSITIONS.getValue(v.subtitlePosition)), onClick = { picking = VideoPicker.POSITION })
            GroupDivider()
            GroupRow(stringResource(R.string.settings_video_color), value = stringResource(COLORS.getValue(v.subtitleColor)), onClick = { picking = VideoPicker.COLOR })
            GroupDivider()
            GroupRow(stringResource(R.string.settings_video_background), value = stringResource(BACKGROUNDS.getValue(v.subtitleBackground)), onClick = { picking = VideoPicker.BACKGROUND })
            GroupDivider()
            GroupRow(stringResource(R.string.settings_video_encoding), value = stringResource(ENCODINGS.getValue(v.subtitleEncoding)), onClick = { picking = VideoPicker.ENCODING })
        }
    }
    if (v == null) return
    val dismiss = { picking = null }
    when (picking) {
        VideoPicker.RATE -> ChoiceSheet(stringResource(R.string.settings_video_rate), FrameRate.entries.map { it to stringResource(RATES.getValue(it)) }, v.frameRate, { r -> set { it.copy(frameRate = r) } }, dismiss)
        VideoPicker.TURN -> ChoiceSheet(stringResource(R.string.settings_video_turn), Turn.entries.map { it to stringResource(TURNS.getValue(it)) }, v.turn, { t -> set { it.copy(turn = t) } }, dismiss)
        VideoPicker.RESUME -> ChoiceSheet(stringResource(R.string.settings_video_resume), Resuming.entries.map { it to stringResource(RESUMING.getValue(it)) }, v.resume, { r -> set { it.copy(resume = r) } }, dismiss)
        VideoPicker.BUTTONS -> ChoiceSheet(stringResource(R.string.settings_video_buttons), Buttons.entries.map { it to stringResource(BUTTONS.getValue(it)) }, v.buttons, { b -> set { it.copy(buttons = b) } }, dismiss)
        VideoPicker.SKIP -> ChoiceSheet(stringResource(R.string.settings_video_skip), VideoSettings.SKIPS.map { it to stringResource(R.string.video_seconds, it) }, v.skipSeconds, { n -> set { it.copy(skipSeconds = n) } }, dismiss)
        VideoPicker.AUDIO -> ChoiceSheet(stringResource(R.string.settings_video_audio), (listOf("") + LANGUAGES.map { it.first }).map { it to languageName(it) }, v.audioLanguage, { l -> set { it.copy(audioLanguage = l) } }, dismiss)
        VideoPicker.SUBTITLES -> ChoiceSheet(stringResource(R.string.settings_video_subtitles), (listOf("", VideoSettings.OFF) + LANGUAGES.map { it.first }).map { it to languageName(it) }, v.subtitleLanguage, { l -> set { it.copy(subtitleLanguage = l) } }, dismiss)
        VideoPicker.SIZE -> ChoiceSheet(stringResource(R.string.settings_video_size), SubtitleSize.entries.map { it to stringResource(SIZES.getValue(it)) }, v.subtitleSize, { z -> set { it.copy(subtitleSize = z) } }, dismiss)
        VideoPicker.POSITION -> ChoiceSheet(stringResource(R.string.settings_video_position), SubtitlePosition.entries.map { it to stringResource(POSITIONS.getValue(it)) }, v.subtitlePosition, { p -> set { it.copy(subtitlePosition = p) } }, dismiss)
        VideoPicker.COLOR -> ChoiceSheet(stringResource(R.string.settings_video_color), SubtitleColor.entries.map { it to stringResource(COLORS.getValue(it)) }, v.subtitleColor, { k -> set { it.copy(subtitleColor = k) } }, dismiss)
        VideoPicker.BACKGROUND -> ChoiceSheet(stringResource(R.string.settings_video_background), SubtitleBackground.entries.map { it to stringResource(BACKGROUNDS.getValue(it)) }, v.subtitleBackground, { b -> set { it.copy(subtitleBackground = b) } }, dismiss)
        VideoPicker.ENCODING -> ChoiceSheet(stringResource(R.string.settings_video_encoding), SubtitleEncoding.entries.map { it to stringResource(ENCODINGS.getValue(it)) }, v.subtitleEncoding, { e -> set { it.copy(subtitleEncoding = e) } }, dismiss)
        null -> Unit
    }
}

private val RATES = mapOf(FrameRate.OFF to R.string.video_off, FrameRate.SEAMLESS to R.string.settings_video_rate_seamless, FrameRate.ALWAYS to R.string.settings_video_rate_always)
private val RESUMING = mapOf(Resuming.ASK to R.string.settings_video_resume_ask, Resuming.ALWAYS to R.string.settings_video_resume_always, Resuming.NEVER to R.string.settings_video_resume_never)
private val BUTTONS = mapOf(Buttons.FILES to R.string.settings_video_buttons_files, Buttons.CHAPTERS to R.string.settings_video_buttons_chapters)
private val TURNS = mapOf(Turn.VIDEO to R.string.settings_video_turn_video, Turn.PHONE to R.string.settings_video_turn_phone)
private val POSITIONS = mapOf(
    SubtitlePosition.BOTTOM to R.string.settings_video_position_bottom,
    SubtitlePosition.HIGHER to R.string.settings_video_position_higher,
    SubtitlePosition.ABOVE_CONTROLS to R.string.settings_video_position_above,
)
private val COLORS = mapOf(SubtitleColor.WHITE to R.string.settings_video_color_white, SubtitleColor.YELLOW to R.string.settings_video_color_yellow)
private val BACKGROUNDS = mapOf(SubtitleBackground.OUTLINE to R.string.settings_video_background_outline, SubtitleBackground.BOX to R.string.settings_video_background_box)
private val ENCODINGS = mapOf(
    SubtitleEncoding.AUTO to R.string.settings_video_encoding_auto,
    SubtitleEncoding.CYRILLIC to R.string.settings_video_encoding_cyrillic,
    SubtitleEncoding.UNICODE to R.string.settings_video_encoding_unicode,
)
private val SIZES = mapOf(
    SubtitleSize.SMALL to R.string.settings_video_size_small,
    SubtitleSize.NORMAL to R.string.settings_video_size_normal,
    SubtitleSize.LARGE to R.string.settings_video_size_large,
    SubtitleSize.LARGEST to R.string.settings_video_size_largest,
)

private enum class BackupSheet { SAVE, RESTORE }

/** `mnt-2026-10-10.mnt` */
private fun backupName() = "mnt-${LocalDate.now()}.mnt"

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit) =
    Group { GroupField(stringResource(R.string.backup_password), value, onChange, secret = true) }

private val THEMES = listOf(
    ThemeMode.SYSTEM to R.string.theme_system,
    ThemeMode.LIGHT to R.string.theme_light,
    ThemeMode.DARK to R.string.theme_dark,
)

private val SCHEMES = mapOf(
    CodeScheme.LIGHT to R.string.theme_light,
    CodeScheme.DARK to R.string.theme_dark,
    CodeScheme.SOLARIZED_LIGHT to R.string.code_solarized_light,
    CodeScheme.SOLARIZED_DARK to R.string.code_solarized_dark,
    CodeScheme.MONOKAI to R.string.code_monokai,
    CodeScheme.DRACULA to R.string.code_dracula,
    CodeScheme.NONE to R.string.code_none,
)

private val PALETTES = listOf(
    Palette.CLASSIC to R.string.palette_classic,
    Palette.WARM to R.string.palette_warm,
)

/** Language tags with names written in that language. */
private val LANGUAGES = listOf(
    "en" to "English",
    "be" to "Беларуская",
    "de" to "Deutsch",
    "es" to "Español",
    "fr" to "Français",
    "it" to "Italiano",
    "pl" to "Polski",
    "ru" to "Русский",
    "sr" to "Српски",
    "he" to "עברית",
)
