package dev.glowcow.mnt.audio.upnp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/** A speaker or a receiver on the network that plays what it is given a link to: a UPnP media renderer. */
data class Renderer(
    val id: String,
    val name: String,
    val host: String,
    /** The control addresses of its services, and the types it answers to. */
    val transport: String,
    val transportType: String,
    val rendering: String?,
    val renderingType: String?,
    /** What it is and who made it, as `Era 300` and `Sonos`; the address of its picture. */
    val model: String? = null,
    val maker: String? = null,
    val icon: String? = null,
) {
    /** Its name with what it is, `era • Era 300`, as a list of speakers shows it. */
    val title: String get() = model?.takeUnless { it.equals(name, ignoreCase = true) }?.let { "$name • $it" } ?: name
}

/** Finds the renderers of the network the phone is on: asks by SSDP, then reads each one's description. */
object Renderers {
    /** Blocking: what answers within [waitMs]. */
    fun search(context: Context, waitMs: Int = 3000): List<Renderer> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("mnt-ssdp").apply { setReferenceCounted(false) }
        lock.acquire()
        val places = LinkedHashSet<String>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 300
                val group = InetAddress.getByName(SSDP_HOST)
                val end = System.currentTimeMillis() + waitMs
                var asked = 0
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    // Asked a few times over: a packet of UDP may be lost, and some renderers answer only to their service.
                    if (asked < 3 && System.currentTimeMillis() > end - waitMs + asked * 600) {
                        for (target in TARGETS) {
                            val ask = search(target).toByteArray()
                            socket.send(DatagramPacket(ask, ask.size, group, SSDP_PORT))
                        }
                        asked++
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    header(String(packet.data, 0, packet.length), "LOCATION")?.let { places += it }
                }
            }
        } catch (_: IOException) {
        } finally {
            lock.release()
        }
        return places.mapNotNull { runCatching { describe(it) }.getOrNull() }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    private fun search(target: String) =
        "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_HOST:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\nUSER-AGENT: Android UPnP/1.0 Mnt\r\n\r\n"

    private fun header(answer: String, name: String): String? =
        answer.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    /** Reads the description at [location]: the renderer among its devices, with its services. */
    fun describe(location: String): Renderer? {
        val connection = URL(location).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        val parser = Xml.newPullParser()
        try {
            connection.inputStream.use { input ->
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(input, null)
                var base = location
                // Sonos keeps the name its owner gave it apart from the long name of the device.
                var room: String? = null
                val devices = ArrayList<Device>()
                val stack = ArrayList<Device>()
                var service: MutableMap<String, String>? = null
                var icon: MutableMap<String, String>? = null
                var text = StringBuilder()
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    val name = parser.name?.substringAfter(':')
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> {
                            text = StringBuilder()
                            when (name) {
                                "device" -> stack += Device()
                                "service" -> service = HashMap()
                                "icon" -> icon = HashMap()
                            }
                        }
                        XmlPullParser.TEXT -> text.append(parser.text)
                        XmlPullParser.END_TAG -> {
                            val value = text.toString().trim()
                            val device = stack.lastOrNull()
                            when {
                                name == "URLBase" && value.isNotEmpty() -> base = value
                                name == "roomName" && value.isNotEmpty() -> room = room ?: value
                                name == "device" -> stack.removeAt(stack.lastIndex).also { devices += it }
                                name == "service" -> {
                                    service?.let { device?.services?.add(it) }
                                    service = null
                                }
                                name == "icon" -> {
                                    icon?.let { device?.icons?.add(it) }
                                    icon = null
                                }
                                service != null && name != null -> service!![name] = value
                                icon != null && name != null -> icon!![name] = value
                                device != null && name in FIELDS -> device.fields[name!!] = value
                            }
                            text = StringBuilder()
                        }
                    }
                }
                val renderer = devices.firstOrNull { d -> d.services.any { it["serviceType"]?.contains(":AVTransport:") == true } } ?: return null
                val transport = renderer.services.first { it["serviceType"]?.contains(":AVTransport:") == true }
                val rendering = renderer.services.firstOrNull { it["serviceType"]?.contains(":RenderingControl:") == true }
                // The device that holds the renderer says what it is when the renderer itself does not; it is the last to close.
                val root = devices.last()
                fun field(name: String) = renderer.fields[name] ?: root.fields[name]
                val maker = field("manufacturer")?.let(::maker)
                val model = (field("displayName") ?: field("modelName"))?.let { m -> maker?.let { m.removePrefix(it).trim() } ?: m }?.ifBlank { null }
                // A PNG the size of a row's picture or larger, else the largest there is.
                val icons = renderer.icons.ifEmpty { root.icons }.sortedBy { it["width"]?.toIntOrNull() ?: 0 }
                val picture = (icons.firstOrNull { it["mimetype"] == "image/png" && (it["width"]?.toIntOrNull() ?: 0) >= ICON_PX } ?: icons.lastOrNull())?.get("url")
                return Renderer(
                    id = renderer.fields["UDN"] ?: location,
                    name = room ?: renderer.fields["friendlyName"]?.let(::clean)?.ifBlank { null } ?: URI(location).host,
                    host = URI(location).host,
                    transport = URI(base).resolve(transport["controlURL"].orEmpty()).toString(),
                    transportType = transport["serviceType"]!!,
                    rendering = rendering?.get("controlURL")?.let { URI(base).resolve(it).toString() },
                    renderingType = rendering?.get("serviceType"),
                    model = model,
                    maker = maker,
                    icon = picture?.let { runCatching { URI(base).resolve(it).toString() }.getOrNull() },
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    // A device's long name less what it says to machines: an address before it, an id and "Media Renderer" after it.
    internal fun clean(name: String): String = name.trim()
        .replace(Regex("""\s+-\s+RINCON_\w+$"""), "")
        .replace(Regex("""^\d{1,3}(\.\d{1,3}){3}\s+-\s+"""), "")
        .replace(Regex("""\s+Media\s*Renderer$""", RegexOption.IGNORE_CASE), "")
        .trim()

    // A maker as people call it: `Sonos, Inc.` is Sonos.
    internal fun maker(name: String): String? = name.substringBefore(',').trim()
        .replace(Regex("""\s+(Inc|Corp|Corporation|Ltd|GmbH|AG|LLC|Co)\.?$""", RegexOption.IGNORE_CASE), "")
        .trim().ifBlank { null }

    private class Device {
        val fields = HashMap<String, String>()
        val services = ArrayList<Map<String, String>>()
        val icons = ArrayList<Map<String, String>>()
    }

    private val FIELDS = setOf("deviceType", "friendlyName", "UDN", "manufacturer", "modelName", "displayName")
    private const val ICON_PX = 96

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val TIMEOUT_MS = 3000
    private val TARGETS = listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1")
}
