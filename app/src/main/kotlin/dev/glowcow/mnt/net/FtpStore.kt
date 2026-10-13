package dev.glowcow.mnt.net

import dev.glowcow.mnt.files.Node
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * An FTP server, plain or with TLS asked for after connecting, from the folder named or the one it puts the user in. One connection does one thing at a
 * time and a file being read or written holds it, so a few are kept and more are opened when needed.
 */
class FtpStore(private val c: Connection) : RemoteStore() {
    private val idle = ArrayDeque<FTPClient>()

    // The folder everything is counted from: the one named, or the one the server puts the user in.
    @Volatile
    private var base: String? = c.share.trim('/').takeIf { it.isNotEmpty() }?.let { "/$it" }

    // How far the times of the old listing are from the real ones, in milliseconds; null until a file has told.
    @Volatile
    private var shift: Long? = null

    private fun connect(): FTPClient {
        val ftp = if (c.secure) FTPSClient(false) else FTPClient()
        ftp.connectTimeout = TIMEOUT_MS
        ftp.defaultTimeout = TIMEOUT_MS
        ftp.autodetectUTF8 = true
        ftp.connect(c.host.trim(), c.port.takeIf { it > 0 } ?: c.protocol.port)
        try {
            ftp.soTimeout = TIMEOUT_MS
            if (!ftp.login(c.user.ifBlank { "anonymous" }, c.password)) fail(ftp)
            if (ftp is FTPSClient) {
                ftp.execPBSZ(0)
                ftp.execPROT("P")
            }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftp.setDataTimeout(java.time.Duration.ofMillis(TIMEOUT_MS.toLong()))
            if (base == null) base = ftp.printWorkingDirectory()?.trimEnd('/').orEmpty()
        } catch (e: IOException) {
            drop(ftp)
            throw e
        }
        return ftp
    }

    private fun take(): FTPClient {
        while (true) {
            val kept = synchronized(idle) { idle.removeFirstOrNull() } ?: break
            if (runCatching { kept.sendNoOp() }.getOrDefault(false)) return kept
            drop(kept)
        }
        return connect()
    }

    private fun give(ftp: FTPClient) {
        if (synchronized(idle) { if (idle.size < KEPT) idle.addLast(ftp).let { true } else false }) return
        drop(ftp)
    }

    private fun drop(ftp: FTPClient) {
        runCatching { ftp.disconnect() }
    }

    private fun <T> with(work: (FTPClient) -> T): T {
        val ftp = take()
        try {
            return work(ftp).also { give(ftp) }
        } catch (e: Exception) {
            drop(ftp)
            throw e
        }
    }

    private fun fail(ftp: FTPClient): Nothing = throw IOException(ftp.replyString?.trim().orEmpty().ifEmpty { "FTP ${ftp.replyCode}" })

    override fun close() {
        synchronized(idle) { idle.toList().also { idle.clear() } }.forEach(::drop)
    }

    override fun listing(path: String): List<Node> = with { ftp ->
        var found = ftp.mlistDir(inner(path))
        // A server that knows no MLSD is asked the old way.
        val old = unknown(ftp)
        if (old) found = ftp.listFiles(inner(path))
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) fail(ftp)
        val by = if (old) shift(ftp, path, found) else 0
        found.filter { it != null && it.name != "." && it.name != ".." }.map { it.node(child(path, name(it.name)), by) }
    }

    override fun node(path: String): Node? = with { ftp ->
        val found = ftp.mlistFile(inner(path))
        if (found != null || !unknown(ftp)) return@with found?.node(path)
        val around = ftp.listFiles(inner(parent(path)))
        around.firstOrNull { it != null && name(it.name) == name(path) }?.node(path, shift(ftp, parent(path), around))
    }

    /**
     * The old listing gives a time with no zone, in whatever the server's clock shows, and it is read as the phone's own.
     * One file of [found] in [folder] is asked for its real time, and the difference is what all the others are moved by.
     */
    private fun shift(ftp: FTPClient, folder: String, found: Array<FTPFile?>): Long {
        shift?.let { return it }
        // Only a recent file is listed with its hour; an old one has but a date.
        val recent = System.currentTimeMillis() - RECENT_MS
        val probe = found.filter { it != null && it.isFile && (it.timestamp?.timeInMillis ?: 0) > recent }.maxByOrNull { it!!.timestamp.timeInMillis } ?: return 0
        val real = ftp.mdtmFile(inner(child(folder, name(probe!!.name))))?.timestamp?.timeInMillis
        // A server that tells no real time leaves the listing as it is, for good.
        if (real == null) {
            if (unknown(ftp)) shift = 0
            return 0
        }
        // The listing drops the seconds, and a zone is a whole number of quarters of an hour.
        return (Math.round((real - probe.timestamp.timeInMillis).toDouble() / QUARTER_MS) * QUARTER_MS).also { shift = it }
    }

    override fun makeFolder(path: String) = with { ftp -> if (!ftp.makeDirectory(inner(path))) fail(ftp) }

    override fun move(from: String, to: String) = with { ftp -> if (!ftp.rename(inner(from), inner(to))) fail(ftp) }

    override fun delete(path: String) = with { ftp -> if (!ftp.deleteFile(inner(path)) && !ftp.removeDirectory(inner(path))) fail(ftp) }

    override fun touch(path: String, time: Long) = with { ftp ->
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
        // Not every server lets the time be set; the file is whole without it.
        ftp.setModificationTime(inner(path), stamp)
        Unit
    }

    override fun input(path: String, offset: Long): InputStream {
        val ftp = take()
        val data = try {
            ftp.restartOffset = offset
            ftp.retrieveFileStream(inner(path)) ?: fail(ftp)
        } catch (e: IOException) {
            drop(ftp)
            throw e
        }
        return object : InputStream() {
            private var ended = false
            private var closed = false

            override fun read(): Int = data.read().also { if (it < 0) ended = true }

            override fun read(b: ByteArray, off: Int, len: Int): Int = data.read(b, off, len).also { if (it < 0) ended = true }

            override fun close() {
                if (closed) return
                closed = true
                runCatching { data.close() }
                // Left before its end, a file leaves the connection in the middle of an answer.
                if (ended && runCatching { ftp.completePendingCommand() }.getOrDefault(false)) give(ftp) else drop(ftp)
            }
        }
    }

    override fun output(path: String, size: Long): OutputStream {
        val ftp = take()
        val data = try {
            ftp.storeFileStream(inner(path)) ?: fail(ftp)
        } catch (e: IOException) {
            drop(ftp)
            throw e
        }
        return object : OutputStream() {
            private var closed = false

            override fun write(b: Int) = data.write(b)

            override fun write(b: ByteArray, off: Int, len: Int) = data.write(b, off, len)

            override fun close() {
                if (closed) return
                closed = true
                try {
                    data.close()
                    if (!ftp.completePendingCommand()) fail(ftp)
                    give(ftp)
                } catch (e: IOException) {
                    drop(ftp)
                    throw e
                }
            }
        }
    }

    private fun unknown(ftp: FTPClient): Boolean = ftp.replyCode.let { it == FTPReply.UNRECOGNIZED_COMMAND || it == FTPReply.COMMAND_NOT_IMPLEMENTED || it == FTPReply.SYNTAX_ERROR_IN_ARGUMENTS }

    private fun FTPFile.node(path: String, by: Long = 0): Node =
        Node(path, name(path), isDirectory, if (isDirectory) 0 else size.coerceAtLeast(0), timestamp?.timeInMillis?.plus(by) ?: 0)

    private fun inner(path: String): String = (base.orEmpty() + path.trimEnd('/')).ifEmpty { "/" }

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val KEPT = 3
        const val QUARTER_MS = 15 * 60 * 1000L
        const val RECENT_MS = 150L * 24 * 60 * 60 * 1000
    }
}
