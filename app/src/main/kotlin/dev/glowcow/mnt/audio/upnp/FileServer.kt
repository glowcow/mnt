package dev.glowcow.mnt.audio.upnp

import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Paths
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Hands the files a renderer is told to play to it over HTTP, from any place the app reaches. Only what was shared is
 * served, each file under a random name; a renderer jumps about in a file by ranges.
 */
class FileServer(private val files: FileStore) {
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null
    private val shared = ConcurrentHashMap<String, String>()
    private val tokens = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()

    /** The address at which the file at [path] is served to a renderer that reaches the phone at [local]. */
    @Synchronized
    fun url(local: InetAddress, path: String): String {
        val port = start()
        val token = tokens.getOrPut(path) { ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }.also { shared[it] = path } }
        val host = local.hostAddress?.let { if (':' in it) "[$it]" else it }
        return "http://$host:$port/$token/" + URLEncoder.encode(Paths.name(path), "UTF-8").replace("+", "%20")
    }

    @Synchronized
    private fun start(): Int {
        server?.let { return it.localPort }
        val socket = ServerSocket(0)
        val pool = Executors.newCachedThreadPool()
        server = socket
        workers = pool
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                pool.execute { client.use { serve(it) } }
            }
        }, "mnt-file-server").apply { isDaemon = true }.start()
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        workers?.shutdownNow()
        server = null
        workers = null
        shared.clear()
        tokens.clear()
    }

    private fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val request = line(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val header = line(input) ?: return
            if (header.isEmpty()) break
            headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':', "").trim()
        }
        val (method, target) = request.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val path = shared[target.removePrefix("/").substringBefore('/')]
        if (path == null || method !in setOf("GET", "HEAD")) return answer(out, "404 Not Found", mapOf("Content-Length" to "0"))
        val size = runCatching { runBlocking { files.stat(path) }?.size }.getOrNull() ?: return answer(out, "404 Not Found", mapOf("Content-Length" to "0"))
        var from = 0L
        var to = size - 1
        val range = headers["range"]?.let { RANGE.matchEntire(it.trim()) }
        if (range != null) {
            val (a, b) = range.destructured
            if (a.isEmpty()) {
                from = (size - (b.toLongOrNull() ?: 0)).coerceAtLeast(0)
            } else {
                from = a.toLong()
                to = b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            }
            if (from >= size || from > to) return answer(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
        }
        val length = to - from + 1
        val head = linkedMapOf(
            "Content-Type" to Mimes.of(path),
            "Content-Length" to length.toString(),
            "Accept-Ranges" to "bytes",
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to Mimes.FEATURES,
        )
        if (range != null) head["Content-Range"] = "bytes $from-$to/$size"
        answer(out, if (range != null) "206 Partial Content" else "200 OK", head)
        if (method == "HEAD") return
        try {
            files.read(path, from).use { copy(it, out, length) }
        } catch (_: IOException) {
            // The renderer went away or jumped elsewhere: it asks again.
        }
    }

    private fun answer(out: OutputStream, status: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Server: Android UPnP/1.0 Mnt\r\nConnection: close\r\n\r\n")
        }
        out.write(text.toByteArray())
        out.flush()
    }

    private fun copy(input: InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(BLOCK)
        var left = length
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
        out.flush()
    }

    // One line of the request, ended by CRLF; null when the client is gone.
    private fun line(input: InputStream): String? {
        val bytes = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return bytes.toString().trimEnd('\r')
            if (bytes.length > MAX_LINE) return null
            bytes.append(b.toChar())
        }
    }

    private companion object {
        val RANGE = Regex("""bytes=(\d*)-(\d*)""")
        const val BLOCK = 64 * 1024
        const val MAX_LINE = 8192
        const val READ_TIMEOUT_MS = 30_000
    }
}

/** The types renderers are told files are, and what DLNA asks to be said of them. */
object Mimes {
    fun of(path: String): String = when (Paths.extension(Paths.name(path))) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "m4b" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "amr" -> "audio/amr"
        "mka" -> "audio/x-matroska"
        "weba" -> "audio/webm"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    // Seeking by bytes, streamed, no conversion.
    const val FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
}
