package dev.glowcow.mnt.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import dev.glowcow.mnt.R
import dev.glowcow.mnt.files.DriveName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** A device on the USB port that speaks MTP: [ready] once the user let the app talk to it. */
data class MtpPort(val id: String, val name: String, val ready: Boolean)

/** The cameras, phones and players plugged into the USB port, kept current as they come and go. */
class MtpDevices(private val context: Context) {
    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)
    private val open = HashMap<String, Pair<MtpDevice, MtpStore>>()

    private val _ports = MutableStateFlow<List<MtpPort>>(emptyList())
    val ports: StateFlow<List<MtpPort>> = _ports

    init {
        val changes = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(PERMISSION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        context.registerReceiver(receiver, changes, Context.RECEIVER_EXPORTED)
        refresh()
    }

    private fun found(): Map<String, UsbDevice> = usb?.deviceList?.values.orEmpty().filter { it.speaksMtp() }.associateBy { "mtp${it.deviceId}" }

    @Synchronized
    fun refresh() {
        val found = found()
        open.keys.filter { it !in found }.forEach { gone -> open.remove(gone)?.first?.let { runCatching { it.close() } } }
        _ports.value = found.map { (id, device) -> MtpPort(id, DriveName.of(device.manufacturerName, device.productName) ?: context.getString(R.string.device_mtp), usb?.hasPermission(device) == true) }
    }

    /** The files of the device [id]; null when it is gone or the app may not talk to it. */
    @Synchronized
    fun store(id: String): MtpStore? {
        open[id]?.let { return it.second }
        val device = found()[id]?.takeIf { usb?.hasPermission(it) == true } ?: return null
        val link = usb?.openDevice(device) ?: return null
        val mtp = MtpDevice(device)
        if (!mtp.open(link)) return null
        return MtpStore(mtp, File(context.cacheDir, "mtp")).also { open[id] = mtp to it }
    }

    /** Asks the user to let the app talk to the device [id]; the list changes when they have answered. */
    fun ask(id: String) {
        val device = found()[id] ?: return
        val answer = PendingIntent.getBroadcast(context, 0, Intent(PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
        usb?.requestPermission(device, answer)
    }

    private fun UsbDevice.speaksMtp(): Boolean = (0 until interfaceCount).map { getInterface(it) }.any {
        // A camera's class, or the maker's own with the name that says it.
        it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
            it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && it.interfaceSubclass == 0xFF && it.interfaceProtocol == 0 && it.name == "MTP"
    }

    private companion object {
        const val PERMISSION = "dev.glowcow.mnt.USB_PERMISSION"
    }
}
