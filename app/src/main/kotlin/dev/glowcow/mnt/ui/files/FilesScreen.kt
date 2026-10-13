package dev.glowcow.mnt.ui.files

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glowcow.mnt.AppContainer
import dev.glowcow.mnt.MntApp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.Place
import dev.glowcow.mnt.files.TransferProgress
import dev.glowcow.mnt.files.Device
import dev.glowcow.mnt.files.label
import dev.glowcow.mnt.net.Mount
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.components.IconButton48
import dev.glowcow.mnt.ui.components.LocalOrigins
import dev.glowcow.mnt.ui.components.PillButton
import dev.glowcow.mnt.ui.components.ProgressLine
import dev.glowcow.mnt.ui.components.StorageGate
import dev.glowcow.mnt.ui.components.TabScreen
import dev.glowcow.mnt.ui.components.TopTab
import dev.glowcow.mnt.ui.components.BIN_KEY
import dev.glowcow.mnt.ui.components.deviceKey
import dev.glowcow.mnt.ui.components.folderKey
import dev.glowcow.mnt.ui.components.origin
import dev.glowcow.mnt.ui.theme.MntFlat
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class Bin(val folders: Int, val files: Int, val bytes: Long)

class FilesViewModel(private val c: AppContainer) : ViewModel() {
    val devices: StateFlow<List<Device>> = c.volumes.devices

    /** The servers the user added and the MTP devices on the USB port. */
    val mounts: StateFlow<List<Mount>> = c.remotes.mounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val progress: StateFlow<TransferProgress?> = c.transfers.progress

    // Counts the times the tab came to the front: access may have been granted meanwhile.
    private val looks = MutableStateFlow(0)

    /** The well-known folders the phone's own storage really has; read again after anything changed. */
    val places: StateFlow<List<Pair<Place, String>>> = combine(c.volumes.devices, c.transfers.changes, looks, c.settings.settings.map { it.hiddenPlaces }) { devices, _, _, hidden ->
        val own = devices.firstOrNull { it.kind == Device.Kind.PHONE }?.volumes?.firstOrNull() ?: return@combine emptyList()
        Place.entries.filter { it.name !in hidden }.map { it to it.path(own) }.filter { (_, path) -> c.files.stat(path)?.directory == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pinned: StateFlow<List<String>> = c.settings.settings.map { it.pinned }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What the bin holds: its items by kind and the bytes of all of them; null until read. */
    val bin: StateFlow<Bin?> = combine(c.volumes.devices, c.transfers.changes, looks) { _, _, _ ->
        val items = c.trash.entries()
        val folders = items.count { it.node.directory }
        Bin(folders, items.size - folders, items.sumOf { it.bytes })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh() {
        c.volumes.refresh()
        c.mtp.refresh()
        c.purge()
        looks.value++
    }

    fun display(path: String): String = c.roots.display(path)

    fun stop() = c.transfers.stop()

    /** Asks the user to let the app talk to an MTP device. */
    fun ask(id: String) = c.mtp.ask(id)

    fun forget(id: String) = c.remotes.forget(id)
}

@Composable
fun FilesScreen(
    onTab: (TopTab) -> Unit,
    onOpen: (String) -> Unit,
    onDevice: (String) -> Unit,
    onBin: () -> Unit,
    onServer: (String?) -> Unit,
    vm: FilesViewModel = viewModel { FilesViewModel((this[APPLICATION_KEY] as MntApp).container) },
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val mounts by vm.mounts.collectAsStateWithLifecycle()
    var server by rememberSaveable { mutableStateOf<String?>(null) }
    val places by vm.places.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val bin by vm.bin.collectAsStateWithLifecycle()
    val origins = LocalOrigins.current
    var about by rememberSaveable { mutableStateOf<String?>(null) }
    // A folder opened from this tab grows out of the row that was tapped.
    fun open(path: String) {
        origins.mark(folderKey(path))
        onOpen(path)
    }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    // The way to add a server stands at the end of the title row.
    val add: @Composable () -> Unit = {
        // The glyph, not its 48 dp target, ends where the blocks below do.
        Box(Modifier.offset(x = 13.dp)) { IconButton48(MntIcons.Plus, stringResource(R.string.server_add)) { onServer(null) } }
    }
    TabScreen(TopTab.FILES, onTab, trailing = add) { top, bottom ->
        StorageGate(top) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = top, bottom = bottom + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Group {
                    devices.forEachIndexed { i, device ->
                        if (i > 0) GroupDivider()
                        val parts = device.volumes.size
                        val space = spaceText(device.free, device.total)
                        GroupRow(
                            device.title(),
                            Modifier.origin(if (parts > 1) deviceKey(device.id) else folderKey(device.volumes.first().path)),
                            subtitle = if (parts > 1) "${pluralStringResource(R.plurals.count_volumes, parts, parts)} | $space" else space,
                            icon = device.kind.icon,
                            iconTint = Color.Unspecified,
                            iconSize = PLACE_ICON,
                            // A device with one volume opens straight into it.
                            onClick = {
                                if (parts > 1) {
                                    origins.mark(deviceKey(device.id))
                                    onDevice(device.id)
                                } else {
                                    open(device.volumes.first().path)
                                }
                            },
                            // What can be pulled out has a sheet of its own: what it is, and the way to eject it.
                            onLongClick = if (device.kind == Device.Kind.PHONE) null else ({ about = device.id }),
                            below = { UsageBar(device.free, device.total) },
                        )
                    }
                    // What is not the phone's own stands with the devices: the MTP devices on the port, then the servers.
                    mounts.forEach { mount ->
                        GroupDivider()
                        GroupRow(
                            mount.title,
                            Modifier.origin(folderKey(mount.path)),
                            subtitle = if (mount.ready) mount.detail else stringResource(R.string.mtp_ask),
                            icon = if (mount.protocol == null) MntFlat.Gadget else MntFlat.Server,
                            iconTint = Color.Unspecified,
                            iconSize = PLACE_ICON,
                            onClick = { if (mount.ready) open(mount.path) else vm.ask(mount.id) },
                            onLongClick = if (mount.protocol == null) null else ({ server = mount.id }),
                        )
                    }
                }
                // A running transfer stands between what holds files and the folders to go to.
                progress?.let { TransferBlock(it, vm::stop) }
                if (places.isNotEmpty() || pinned.isNotEmpty()) {
                    // One list of the folders to go to: the phone's well-known ones, then the ones pinned.
                    Group {
                        places.forEachIndexed { i, (place, path) ->
                            if (i > 0) GroupDivider()
                            GroupRow(
                                stringResource(place.label),
                                Modifier.origin(folderKey(path)),
                                icon = place.icon,
                                iconTint = Color.Unspecified,
                                iconSize = PLACE_ICON,
                                onClick = { open(path) },
                            )
                        }
                        pinned.forEachIndexed { i, path ->
                            if (i > 0 || places.isNotEmpty()) GroupDivider()
                            GroupRow(
                                Paths.name(path),
                                // A folder listed twice on the tab grows out of the first of its rows.
                                if (places.none { it.second == path }) Modifier.origin(folderKey(path)) else Modifier,
                                subtitle = vm.display(Paths.parent(path)),
                                icon = MntFlat.Folder,
                                iconTint = Color.Unspecified,
                                iconSize = PLACE_ICON,
                                onClick = { open(path) },
                                // A pin in place of the chevron tells a folder the user put here from the phone's own.
                                trailing = { Icon(MntIcons.PinFilled, stringResource(R.string.pinned), tint = MntTheme.colors.muted, modifier = Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                Group {
                    val held = bin
                    GroupRow(
                        stringResource(R.string.bin),
                        Modifier.origin(BIN_KEY),
                        subtitle = held?.let { binText(it) } ?: " ",
                        icon = if (held == null || held.folders + held.files == 0) MntFlat.Bin else MntFlat.BinFull,
                        iconTint = Color.Unspecified,
                        iconSize = PLACE_ICON,
                        onClick = {
                            origins.mark(BIN_KEY)
                            onBin()
                        },
                    )
                }
            }
        }
    }

    about?.let { id -> devices.firstOrNull { it.id == id }?.let { DeviceSheet(it) { about = null } } }
    server?.let { id ->
        mounts.firstOrNull { it.id == id }?.let { mount ->
            GroupSheet(mount.title, { server = null }) { pick ->
                Group {
                    GroupRow(stringResource(R.string.server_edit), onClick = { pick { onServer(id) } })
                    GroupDivider()
                    GroupRow(stringResource(R.string.server_remove), subtitle = stringResource(R.string.server_remove_hint), onClick = { pick { vm.forget(id) } })
                }
            }
        }
    }
}

/**
 * What a card or a drive is: its volumes with their file systems, and the way to the system's
 * storage settings — only the system may eject or format one.
 */
@Composable
private fun DeviceSheet(device: Device, onDismiss: () -> Unit) {
    val context = LocalContext.current
    GroupSheet(device.title(), onDismiss) { pick ->
        Group {
            device.volumes.forEachIndexed { i, volume ->
                if (i > 0) GroupDivider()
                GroupRow(volume.label, subtitle = listOfNotNull(volume.format, spaceText(volume.free, volume.total)).joinToString(" | "))
            }
        }
        Spacer(Modifier.height(12.dp))
        Group {
            GroupRow(
                stringResource(R.string.device_settings),
                subtitle = stringResource(R.string.device_settings_hint),
                onClick = { pick { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } },
            )
        }
    }
}

/** A running transfer as a block of a grouped page: what it does, how far and how fast, and the way to stop it. */
@Composable
fun TransferBlock(progress: TransferProgress, onStop: () -> Unit) {
    Group {
        GroupRow(
            transferTitle(progress),
            subtitle = progress.name.ifEmpty { null },
            below = { ProgressLine(progress.fraction, Modifier.padding(top = 6.dp)) },
            trailing = { PillButton(stringResource(R.string.action_stop), onClick = onStop) },
        )
    }
}

/** `Copying · 45% · 38 MB/s` — the rate once there is one to tell. */
@Composable
fun transferTitle(progress: TransferProgress): String {
    val context = LocalContext.current
    val percent = stringResource(R.string.transfer_progress, stringResource(progress.kind.label), (progress.fraction * 100).toInt())
    val rate = progress.speed?.let { stringResource(R.string.transfer_rate, Formatter.formatShortFileSize(context, it)) }
    return listOfNotNull(percent, rate).joinToString(" · ")
}

/** `2 folders, 5 files | 1.3 GB`, or that there is nothing. */
@Composable
private fun binText(bin: Bin): String {
    if (bin.folders + bin.files == 0) return stringResource(R.string.folder_holds_nothing)
    val counts = listOfNotNull(
        bin.folders.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_folders, it, it) },
        bin.files.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_files, it, it) },
    ).joinToString(", ")
    return "$counts | ${Formatter.formatShortFileSize(LocalContext.current, bin.bytes)}"
}

/** `58 GB free of 63 GB` */
@Composable
fun spaceText(free: Long, total: Long): String {
    val context = LocalContext.current
    return stringResource(R.string.volume_free, Formatter.formatShortFileSize(context, free), Formatter.formatShortFileSize(context, total))
}

/** What a device goes by: its own name, the label of its one volume, or what kind of thing it is. */
@Composable
fun Device.title(): String = name ?: volumes.singleOrNull()?.label ?: stringResource(if (kind == Device.Kind.CARD) R.string.device_card else R.string.device_drive)

/**
 * How full a device or a volume is: a thin bar under the text of its row — green while there is
 * room, amber from four fifths full, red from nine tenths.
 */
@Composable
fun UsageBar(free: Long, total: Long) {
    val used = if (total > 0) 1f - free.toFloat() / total else 0f
    val c = MntTheme.colors
    val color = c.bright(
        when {
            used < USAGE_TIGHT -> if (c.isDark) Color(0xFF5FCB72) else Color(0xFF2F9E44)
            used < USAGE_FULL -> if (c.isDark) Color(0xFFF5C542) else Color(0xFFE0A100)
            else -> if (c.isDark) Color(0xFFFF7B7E) else Color(0xFFDC3E42)
        },
    )
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.chip)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(used.coerceIn(0f, 1f)).clip(CircleShape).background(color))
    }
}

// The pictures of the Files tab stand on the block itself, a size up from a row's usual icon.
val PLACE_ICON = 32.dp

private const val USAGE_TIGHT = 0.8f
private const val USAGE_FULL = 0.9f

val Device.Kind.icon: ImageVector
    get() = when (this) {
        Device.Kind.PHONE -> MntFlat.Phone
        Device.Kind.CARD -> MntFlat.Card
        Device.Kind.DRIVE -> MntFlat.Drive
    }

val Place.label: Int
    get() = when (this) {
        Place.DOWNLOADS -> R.string.place_downloads
        Place.CAMERA -> R.string.place_camera
        Place.PICTURES -> R.string.place_pictures
        Place.MUSIC -> R.string.place_music
        Place.MOVIES -> R.string.place_movies
        Place.DOCUMENTS -> R.string.place_documents
    }

private val Place.icon: ImageVector
    get() = when (this) {
        Place.DOWNLOADS -> MntFlat.Download
        Place.CAMERA -> MntFlat.Camera
        Place.PICTURES -> MntFlat.Image
        Place.MUSIC -> MntFlat.Music
        Place.MOVIES -> MntFlat.Movies
        Place.DOCUMENTS -> MntFlat.Text
    }
