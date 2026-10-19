package dev.glowcow.mnt.audio.upnp

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Asks a service of a renderer to do something: a SOAP action, what it answers as names and values. */
object Soap {
    /** Blocking. Throws [IOException] when the renderer cannot be reached or refuses. */
    fun call(url: String, service: String, action: String, vararg args: Pair<String, String>): Map<String, String> {
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
            append("""<u:$action xmlns:u="$service">""")
            for ((name, value) in args) append("<$name>").append(escape(value)).append("</$name>")
            append("</u:$action></s:Body></s:Envelope>")
        }.toByteArray()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val answer = stream?.use { read(it.reader().readText()) }.orEmpty()
            if (code !in 200..299) throw IOException("$action: HTTP $code ${answer["errorCode"].orEmpty()} ${answer["errorDescription"].orEmpty()}".trim())
            return answer
        } finally {
            connection.disconnect()
        }
    }

    // Every element with text of its own, by its name without a prefix.
    private fun read(xml: String): Map<String, String> {
        val found = HashMap<String, String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())
        var text = StringBuilder()
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> text = StringBuilder()
                    XmlPullParser.TEXT -> text.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        if (text.isNotBlank()) found[parser.name.substringAfter(':')] = text.toString().trim()
                        text = StringBuilder()
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found
    }

    fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    /** `H:MM:SS` of the renderers, from milliseconds and back; null for what is not a time. */
    fun time(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(java.util.Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
    }

    fun millis(time: String?): Long? {
        val parts = time?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
    }

    private const val TIMEOUT_MS = 5000
}
