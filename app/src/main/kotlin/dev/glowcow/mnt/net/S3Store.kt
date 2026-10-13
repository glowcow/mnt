package dev.glowcow.mnt.net

import dev.glowcow.mnt.files.Node
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A bucket of an S3 server — Amazon's, MinIO, Garage and their like — asked by path with requests
 * signed the version 4 way. A folder is what the keys under `name/` make, kept by an empty object
 * of that very key when it holds nothing.
 */
class S3Store(private val c: Connection) : RemoteStore() {
    private val region = c.region.trim().ifEmpty { "us-east-1" }
    private val bucket = c.share.trim('/')

    // With no server named it is Amazon's own, in the region.
    private val base: String = c.host.trim().let { host ->
        if (host.isEmpty()) return@let "https://s3.$region.amazonaws.com"
        val standard = if (c.secure) 443 else 80
        (if (c.secure) "https" else "http") + "://" + host + (c.port.takeIf { it > 0 && it != standard }?.let { ":$it" } ?: "")
    }

    private class Answer(val code: Int, val headers: (String) -> String?, val body: ByteArray)

    override fun listing(path: String): List<Node> {
        val prefix = key(path).let { if (it.isEmpty()) "" else "$it/" }
        val nodes = ArrayList<Node>()
        pages(prefix, "/") { page ->
            page.all("CommonPrefixes").forEach { found ->
                val name = found.text("Prefix").removePrefix(prefix).trimEnd('/')
                if (name.isNotEmpty()) nodes += Node(child(path, name), name, true, 0, 0)
            }
            page.all("Contents").forEach { found ->
                val name = found.text("Key").removePrefix(prefix)
                // The object that keeps an empty folder is not a file of it.
                if (name.isNotEmpty() && '/' !in name) nodes += Node(child(path, name), name, false, found.text("Size").toLongOrNull() ?: 0, time(found.text("LastModified")))
            }
        }
        return nodes.distinctBy { it.name }
    }

    override fun node(path: String): Node? {
        val head = send("HEAD", key(path))
        if (head.code == 200) {
            val modified = head.headers("Last-Modified")?.let { runCatching { Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(it)).toEpochMilli() }.getOrNull() } ?: 0
            return Node(path, name(path), false, head.headers("Content-Length")?.toLongOrNull() ?: 0, modified)
        }
        if (head.code != 404) fail(head)
        var folder = false
        pages(key(path) + "/", null, limit = 1) { page -> folder = folder || page.all("Contents").isNotEmpty() }
        return if (folder) Node(path, name(path), true, 0, 0) else null
    }

    override fun makeFolder(path: String) {
        check(send("PUT", key(path) + "/", body = ByteArray(0)))
    }

    override fun move(from: String, to: String) {
        val source = node(from) ?: throw IOException("No such file")
        if (!source.directory) return carry(key(from), key(to))
        val keys = ArrayList<String>()
        pages(key(from) + "/", null) { page -> page.all("Contents").forEach { keys += it.text("Key") } }
        for (key in keys) carry(key, key(to) + key.removePrefix(key(from)))
    }

    private fun carry(from: String, to: String) {
        // The object that keeps a folder is made anew: not every server copies one.
        if (from.endsWith('/')) {
            check(send("PUT", to, body = ByteArray(0)))
            check(send("DELETE", from))
            return
        }
        check(send("PUT", to, headers = mapOf("x-amz-copy-source" to "/$bucket/${encode(from, slash = false)}"), body = ByteArray(0)))
        check(send("DELETE", from))
    }

    override fun delete(path: String) {
        check(send("DELETE", if (node(path)?.directory == true) key(path) + "/" else key(path)))
    }

    override fun input(path: String, offset: Long): InputStream {
        val http = open("GET", key(path), emptyMap(), if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap(), null)
        if (http.responseCode !in 200..299) fail(answer(http))
        return object : FilterInputStream(http.inputStream) {
            override fun close() {
                // The rest of a file left half way is not read to its end to keep the connection.
                http.disconnect()
            }
        }
    }

    override fun output(path: String, size: Long): OutputStream = Upload(key(path))

    /** Sends a file whole when it fits one part, and in parts when it does not: its length need not be known. */
    private inner class Upload(private val key: String) : OutputStream() {
        private val part = ByteArray(PART)
        private var filled = 0
        private var id: String? = null
        private val tags = ArrayList<String>()
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var from = off
            var left = len
            while (left > 0) {
                if (filled == PART) flushPart()
                val n = minOf(left, PART - filled)
                System.arraycopy(b, from, part, filled, n)
                filled += n
                from += n
                left -= n
            }
        }

        private fun flushPart() {
            val upload = id ?: check(send("POST", key, query = mapOf("uploads" to ""), body = ByteArray(0))).xml().text("UploadId").also { id = it }
            val sent = check(send("PUT", key, query = mapOf("partNumber" to (tags.size + 1).toString(), "uploadId" to upload), body = part.copyOf(filled)))
            tags += sent.headers("ETag") ?: throw IOException("No ETag")
            filled = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            val upload = id
            if (upload == null) {
                check(send("PUT", key, body = part.copyOf(filled)))
                return
            }
            try {
                if (filled > 0) flushPart()
                val parts = tags.mapIndexed { i, tag -> "<Part><PartNumber>${i + 1}</PartNumber><ETag>$tag</ETag></Part>" }.joinToString("")
                val done = check(send("POST", key, query = mapOf("uploadId" to upload), body = "<CompleteMultipartUpload>$parts</CompleteMultipartUpload>".toByteArray()))
                // A failure here may come with the code of a success.
                if (done.xml().tagName == "Error") fail(done)
            } catch (e: IOException) {
                runCatching { send("DELETE", key, query = mapOf("uploadId" to upload)) }
                throw e
            }
        }
    }

    /** Every page of the keys under [prefix], cut at [delimiter] when there is one. */
    private fun pages(prefix: String, delimiter: String?, limit: Int = 1000, each: (Element) -> Unit) {
        var token: String? = null
        do {
            val query = buildMap {
                put("list-type", "2")
                put("prefix", prefix)
                put("max-keys", limit.toString())
                delimiter?.let { put("delimiter", it) }
                token?.let { put("continuation-token", it) }
            }
            val page = check(send("GET", "", query)).xml()
            each(page)
            token = page.text("NextContinuationToken").takeIf { page.text("IsTruncated") == "true" && it.isNotEmpty() && limit > 1 }
        } while (token != null)
    }

    private fun send(method: String, key: String, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Answer =
        answer(open(method, key, query, headers, body))

    private fun answer(http: HttpURLConnection): Answer = try {
        val code = http.responseCode
        val body = (if (code in 200..299) http.inputStream else http.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        Answer(code, http::getHeaderField, body)
    } finally {
        http.disconnect()
    }

    private fun check(answer: Answer): Answer = if (answer.code in 200..299) answer else fail(answer)

    private fun fail(answer: Answer): Nothing {
        val said = runCatching { answer.xml().let { "${it.text("Code")}: ${it.text("Message")}" } }.getOrNull()?.takeIf { it.length > 2 }
        throw IOException(said ?: "HTTP ${answer.code}")
    }

    /** Opens a request signed with the secret, the body sent with it. */
    private fun open(method: String, key: String, query: Map<String, String>, headers: Map<String, String>, body: ByteArray?): HttpURLConnection {
        val uri = "/" + bucket + if (key.isEmpty()) "" else "/" + encode(key, slash = true)
        val asked = TreeMap(query).entries.joinToString("&") { "${encode(it.key, false)}=${encode(it.value, false)}" }
        val url = URI.create(base + uri + if (asked.isEmpty()) "" else "?$asked").toURL()
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        val stamp = STAMP.format(now)
        val day = stamp.substring(0, 8)
        val hash = hex(sha256(body ?: ByteArray(0)))
        val signed = TreeMap<String, String>()
        signed["host"] = url.host + if (url.port > 0) ":${url.port}" else ""
        signed["x-amz-content-sha256"] = hash
        signed["x-amz-date"] = stamp
        headers.filterKeys { it.startsWith("x-amz-") }.forEach { (name, value) -> signed[name] = value }
        val names = signed.keys.joinToString(";")
        val request = listOf(method, uri, asked, signed.entries.joinToString("") { "${it.key}:${it.value}\n" }, names, hash).joinToString("\n")
        val scope = "$day/$region/s3/aws4_request"
        val toSign = listOf("AWS4-HMAC-SHA256", stamp, scope, hex(sha256(request.toByteArray()))).joinToString("\n")
        val signing = listOf(day, region, "s3", "aws4_request").fold(("AWS4" + c.password).toByteArray()) { secret, text -> hmac(secret, text) }
        val http = url.openConnection() as HttpURLConnection
        http.requestMethod = method
        http.connectTimeout = TIMEOUT_MS
        http.readTimeout = TIMEOUT_MS
        signed.filterKeys { it != "host" }.forEach { (name, value) -> http.setRequestProperty(name, value) }
        headers.filterKeys { !it.startsWith("x-amz-") }.forEach { (name, value) -> http.setRequestProperty(name, value) }
        http.setRequestProperty("Authorization", "AWS4-HMAC-SHA256 Credential=${c.user.trim()}/$scope, SignedHeaders=$names, Signature=${hex(hmac(signing, toSign))}")
        if (body != null) {
            http.doOutput = true
            http.setFixedLengthStreamingMode(body.size)
            http.outputStream.use { it.write(body) }
        }
        return http
    }

    private fun Answer.xml(): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(body)).documentElement

    private fun Element.all(tag: String): List<Element> = childNodes.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag } }

    private fun Element.text(tag: String): String = all(tag).firstOrNull()?.textContent.orEmpty()

    private fun time(text: String): Long = runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0)

    private fun key(path: String): String = path.trim('/')

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val PART = 16 * 1024 * 1024
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        const val PLAIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

        /** Percent-encodes as the signature wants it: every byte but letters, digits and `-_.~`, and a slash where it parts a path. */
        fun encode(text: String, slash: Boolean): String = buildString {
            for (byte in text.toByteArray()) {
                val char = (byte.toInt() and 0xFF).toChar()
                if (char in PLAIN || slash && char == '/') append(char) else append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        fun hmac(secret: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(text.toByteArray())

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
