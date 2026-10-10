package dev.glowcow.mnt.files

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** A mounted volume: the phone's own storage, or a partition of a card or of a drive. */
data class Volume(
    val path: String,
    val label: String,
    val primary: Boolean,
    /** The device it is a part of, as [Mounts.disk] names it; null for the phone's own storage. */
    val disk: String?,
    /** The file system as people call it — FAT, exFAT, NTFS; null when the table of mounts does not tell. */
    val format: String?,
    /** Bytes. */
    val total: Long,
    val free: Long,
)

/** Something that holds files: the phone itself, a memory card, a drive on the USB port — with the volumes mounted from it. */
data class Device(
    val id: String,
    /** What the device calls itself; null when it does not say. */
    val name: String?,
    val kind: Kind,
    val volumes: List<Volume>,
) {
    enum class Kind { PHONE, CARD, DRIVE }

    val total: Long get() = volumes.sumOf { it.total }
    val free: Long get() = volumes.sumOf { it.free }
}

/** The devices and volumes mounted right now, kept current as cards and drives come and go. */
class Volumes(private val context: Context) {
    private val storage = context.getSystemService(StorageManager::class.java)
    private val usb = context.getSystemService(UsbManager::class.java)
    private val later = Handler(Looper.getMainLooper())

    // The name the owner gave the phone in the system settings, or its maker and model.
    private val phone: String = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()?.takeIf { it.isNotEmpty() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private val _devices = MutableStateFlow(read())
    val devices: StateFlow<List<Device>> = _devices

    val list: List<Volume> get() = _devices.value.flatMap { it.volumes }

    init {
        storage.registerStorageVolumeCallback(context.mainExecutor, object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                refresh()
                // A volume is announced before its file system answers: its size reads as nothing at first.
                SETTLE_MS.forEach { later.postDelayed(::refresh, it) }
            }
        })
    }

    /** Reads the list again: free space changes without any callback. */
    fun refresh() {
        _devices.value = read()
    }

    /** The volume [path] lies on. */
    fun of(path: String): Volume? = list.filter { Paths.within(path, it.path) }.maxByOrNull { it.path.length }

    /** [path] as a person reads it: the volume by its name, then the folders down to it. */
    fun display(path: String): String {
        val volume = of(path) ?: return path
        return volume.label + path.removePrefix(volume.path)
    }

    // What is free right now is what the row shows, not what the system could free by dropping caches.
    @SuppressLint("UsableSpace")
    private fun read(): List<Device> {
        val mounts = runCatching { File("/proc/self/mountinfo").readText() }.getOrDefault("")
        val volumes = storage.storageVolumes.mapNotNull { volume ->
            val root = volume.directory?.takeIf { volume.state == Environment.MEDIA_MOUNTED } ?: return@mapNotNull null
            Volume(
                path = root.path,
                // The phone's own storage goes by the phone's name, not by the system's words for it.
                label = if (volume.isPrimary) phone else volume.getDescription(context),
                primary = volume.isPrimary,
                // A removable volume the table does not list is a device of its own.
                disk = if (volume.isPrimary) null else Mounts.disk(mounts, root.path) ?: root.path,
                format = Mounts.format(mounts, root.path),
                total = root.totalSpace,
                free = root.usableSpace,
            )
        }
        // The name of a drive is known only while it is the one drive plugged in: nothing ties a name to a volume.
        val drive = usb?.deviceList?.values?.filter { it.isStorage() }?.singleOrNull()?.let { DriveName.of(it.manufacturerName, it.productName) }
        return volumes.groupBy { it.disk }.map { (disk, parts) ->
            when {
                disk == null -> Device(PHONE, parts.first().label, Device.Kind.PHONE, parts)
                disk.startsWith("${Mounts.MMC}:") -> Device(disk, null, Device.Kind.CARD, parts)
                else -> Device(disk, drive, Device.Kind.DRIVE, parts)
            }
        }
    }

    private fun UsbDevice.isStorage(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }

    private companion object {
        const val PHONE = "phone"
        val SETTLE_MS = listOf(1_500L, 5_000L)
    }
}

/** What a drive on the USB port is called, put together from what it says of itself. */
object DriveName {
    // Words a drive says in place of a name.
    private val NO_PRODUCT = Regex("""(usb )?(mass storage( device)?|flash (disk|drive)|storage( device| media)?|disk|drive|device)( \d(\.\d)?)?""", RegexOption.IGNORE_CASE)
    private val NO_MAKER = Regex("""generic|general|usb|vendor|unknown|n/?a""", RegexOption.IGNORE_CASE)

    /** The maker and the product, without the words that name nothing; null when nothing is left. */
    fun of(manufacturer: String?, product: String?): String? {
        val maker = manufacturer?.trim().orEmpty().takeUnless { it.isEmpty() || NO_MAKER.matches(it) }
        val model = product?.trim().orEmpty().takeUnless { it.isEmpty() || NO_PRODUCT.matches(it) }
        return when {
            maker == null -> model
            model == null || model.startsWith(maker, ignoreCase = true) -> model ?: maker
            else -> "$maker $model"
        }
    }
}

/** Reads the kernel's table of mounts, `/proc/self/mountinfo`. */
object Mounts {
    /** The major number of the kernel's memory card driver; a drive on the USB port is a SCSI disk, 8. */
    const val MMC = 179

    /**
     * The whole device that what is mounted at [path] is a partition of, as `major:first minor`;
     * null when nothing is mounted there. A SCSI disk owns 16 minor numbers, a memory card 8.
     */
    fun disk(mountinfo: String, path: String): String? {
        val number = mountinfo.lineSequence().map { it.split(' ') }.firstOrNull { it.getOrNull(4) == path }?.getOrNull(2) ?: return null
        val major = number.substringBefore(':').toIntOrNull() ?: return null
        val minor = number.substringAfter(':').toIntOrNull() ?: return null
        val span = if (major == MMC) 8 else 16
        return "$major:${minor / span * span}"
    }

    /** The file system mounted at [path] by the name people know it; null for one the table only calls by its driver. */
    fun format(mountinfo: String, path: String): String? {
        val line = mountinfo.lineSequence().firstOrNull { it.split(' ').getOrNull(4) == path } ?: return null
        return when (line.substringAfter(" - ", "").substringBefore(' ').lowercase()) {
            "vfat", "msdos" -> "FAT"
            "exfat", "fuse.exfat" -> "exFAT"
            "ntfs", "ntfs3", "fuse.ntfs", "fuse.ntfs-3g" -> "NTFS"
            "ext4" -> "ext4"
            "f2fs" -> "F2FS"
            else -> null
        }
    }
}

/** A well-known folder of the phone's own storage. */
enum class Place(val folder: String) {
    DOWNLOADS(Environment.DIRECTORY_DOWNLOADS),
    CAMERA(Environment.DIRECTORY_DCIM),
    PICTURES(Environment.DIRECTORY_PICTURES),
    MUSIC(Environment.DIRECTORY_MUSIC),
    MOVIES(Environment.DIRECTORY_MOVIES),
    DOCUMENTS(Environment.DIRECTORY_DOCUMENTS);

    fun path(volume: Volume): String = File(volume.path, folder).path
}
