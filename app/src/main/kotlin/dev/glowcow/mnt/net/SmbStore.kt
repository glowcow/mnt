package dev.glowcow.mnt.net

import android.os.SystemClock
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.SMB2Echo
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.security.AEADBlockCipher
import com.hierynomus.security.Mac
import com.hierynomus.security.SecurityException
import com.hierynomus.security.SecurityProvider
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.security.jce.JceSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection as SmbConnection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import dev.glowcow.mnt.files.Node
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** A share of an SMB 2 or 3 server — Windows, Samba, a NAS. */
class SmbStore(private val c: Connection) : RemoteStore() {
    private var client: SMBClient? = null
    private var connection: SmbConnection? = null
    private var share: DiskShare? = null

    // When the server last answered, and how many files are being read: a connection busy with them is never asked.
    @Volatile
    private var usedAt = 0L
    private val reading = AtomicInteger()

    // A connection left alone a while is asked whether it is still there before it is trusted: a phone that slept, or
    // a server that let go, leaves one that takes questions and never answers them.
    @Synchronized
    private fun share(): DiskShare {
        val now = SystemClock.elapsedRealtime()
        share?.takeIf { it.isConnected && connection?.isConnected == true && (reading.get() > 0 || now - usedAt < IDLE_MS || answers()) }?.let {
            usedAt = now
            return it
        }
        close()
        // NTLM alone: the Kerberos way in leans on classes Android does not have. No timeout on the socket: one there
        // drops a connection idle that long, and smbj's reader then waits on itself to say goodbye.
        val config = SmbConfig.builder()
            .withTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .withAuthenticators(NtlmAuthenticator.Factory())
            .withSecurityProvider(Crypto())
            // Says the phone can seal what it sends; a server that asks for that lets nobody else in.
            .withEncryptData(true)
            .build()
        val opened = SMBClient(config)
        client = opened
        // A name written as DOMAIN\user tells the domain too; no name at all comes in as a guest.
        val auth = if (c.user.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(c.user.substringAfter('\\'), c.password.toCharArray(), c.user.substringBefore('\\', "").ifEmpty { null })
        }
        val link = opened.connect(c.host.trim(), c.port.takeIf { it > 0 } ?: c.protocol.port)
        connection = link
        val disk = link.authenticate(auth).connectShare(c.share.trim('/', '\\')) as? DiskShare ?: throw IOException("Not a share of files")
        usedAt = now
        return disk.also { share = it }
    }

    private fun answers(): Boolean = runCatching {
        val link = connection ?: return false
        link.send<SMB2Echo>(SMB2Echo(link.negotiatedProtocol.dialect)).get(ECHO_MS, TimeUnit.MILLISECONDS)
        true
    }.getOrDefault(false)

    @Synchronized
    override fun close() {
        runCatching { client?.close() }
        client = null
        connection = null
        share = null
    }

    // Asked once more over a new connection when the one there broke under the question, or could not be made:
    // the first after a long sleep often finds the network not yet awake. What the server refused is not asked again.
    private fun <T> again(work: (DiskShare) -> T): T = try {
        work(share())
    } catch (e: Exception) {
        if (e is SMBApiException || !broken(e)) throw e
        close()
        work(share())
    }

    private fun broken(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any {
        it is TransportException || it is SocketException || it is SocketTimeoutException || it is TimeoutException
    }

    override fun listing(path: String): List<Node> = again { it.list(inner(path)) }.filter { it.fileName != "." && it.fileName != ".." }.map {
        val folder = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
        Node(child(path, it.fileName), it.fileName, folder, if (folder) 0 else it.endOfFile, it.lastWriteTime.toEpochMillis())
    }

    override fun node(path: String): Node? = try {
        val known = again { it.getFileInformation(inner(path)) }
        val folder = known.standardInformation.isDirectory
        Node(path, name(path), folder, if (folder) 0 else known.standardInformation.endOfFile, known.basicInformation.lastWriteTime.toEpochMillis())
    } catch (e: SMBApiException) {
        if (e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) null else throw e
    }

    override fun makeFolder(path: String) = share().mkdir(inner(path))

    override fun move(from: String, to: String) {
        val folder = node(from)?.directory ?: throw IOException("No such file")
        share().open(inner(from), setOf(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            // A file takes the place of one that is there; a folder does not.
            .use { it.rename(inner(to), !folder) }
    }

    override fun delete(path: String) {
        if (node(path)?.directory == true) share().rmdir(inner(path), false) else share().rm(inner(path))
    }

    override fun touch(path: String, time: Long) {
        val keep = FileBasicInformation.DONT_SET
        share().setFileInformation(inner(path), FileBasicInformation(keep, keep, FileTime.ofEpochMillis(time), keep, 0))
    }

    override fun input(path: String, offset: Long): InputStream {
        val file = again { it.openFile(inner(path), setOf(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null) }
        return Ahead(file, offset, file.getFileInformation(FileStandardInformation::class.java).endOfFile)
    }

    override fun output(path: String, size: Long): OutputStream {
        return Behind(share().openFile(inner(path), setOf(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null))
    }

    // The server names a path with backslashes and from the share's root, which is the empty one.
    private fun inner(path: String): String = path.trim('/').replace('/', '\\')

    /**
     * Reads a file by several pieces asked for at once: the wire is never idle while one of them is on its way. The
     * pieces start small and grow while the file is read on: a reader after a few bytes here and there costs few.
     */
    private inner class Ahead(private val file: File, private var next: Long, private val end: Long) : InputStream() {
        private val asked = ArrayDeque<Future<Int>>()
        private val pieces = ArrayDeque<ByteArray>()
        private val spare = ArrayDeque<ByteArray>()
        private var piece: ByteArray? = null
        private var size = 0
        private var at = 0
        private var over = false
        private var stride = FIRST
        private var ahead = 1
        private var closed = false

        init {
            reading.incrementAndGet()
        }

        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xFF }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (at == size && !turn()) return -1
            val n = min(len, size - at)
            System.arraycopy(piece!!, at, b, off, n)
            at += n
            return n
        }

        // Takes the next piece in the file's order and asks for those after it; false at the end of the file.
        private fun turn(): Boolean {
            piece?.let { spare += it }
            piece = null
            if (over) return false
            while (asked.size < ahead && next < end) ask()
            ahead = min(ahead + 1, AHEAD)
            val first = asked.removeFirstOrNull() ?: return false
            val bytes = pieces.removeFirst()
            size = try {
                first.get()
            } catch (e: ExecutionException) {
                throw IOException(e.cause?.message, e.cause)
            }
            // A piece that came short is where the file ends now, whatever was asked past it.
            if (size < bytes.size || size == 0) over = true
            piece = bytes
            at = 0
            return size > 0
        }

        private fun ask() {
            val from = next
            val want = min(stride.toLong(), end - from).toInt()
            stride = min(stride * 2, PIECE)
            val bytes = spare.removeFirstOrNull()?.takeIf { it.size == want } ?: ByteArray(want)
            next += want
            pieces += bytes
            asked += POOL.submit(
                Callable {
                    var got = 0
                    // The server may answer with less than was asked when it is short of credits.
                    while (got < want) {
                        val n = file.read(bytes, from + got, got, want - got)
                        if (n <= 0) break
                        got += n
                    }
                    usedAt = SystemClock.elapsedRealtime()
                    got
                },
            )
        }

        override fun close() {
            if (closed) return
            closed = true
            reading.decrementAndGet()
            asked.forEach { it.cancel(false) }
            file.close()
        }
    }

    /** Writes a file by pieces sent one after another, without waiting for the answer to each. */
    private class Behind(private val file: File) : OutputStream() {
        private val sent = ArrayDeque<Pair<Future<Long>, Int>>()
        private val bytes = ByteArray(PIECE)
        private var held = 0
        private var at = 0L

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var from = off
            while (from < off + len) {
                val n = min(off + len - from, PIECE - held)
                System.arraycopy(b, from, bytes, held, n)
                held += n
                from += n
                if (held == PIECE) send()
            }
        }

        // The piece is on the wire once this returns, so the buffer is free for the next one.
        private fun send() {
            if (held == 0) return
            if (sent.size >= AHEAD) settle()
            sent += file.writeAsync(bytes, at, 0, held) to held
            at += held
            held = 0
        }

        private fun settle() {
            val (answer, size) = sent.removeFirst()
            val written = try {
                answer.get()
            } catch (e: ExecutionException) {
                throw IOException(e.cause?.message, e.cause)
            }
            if (written != size.toLong()) throw IOException("The server took $written bytes of $size")
        }

        override fun close() {
            try {
                send()
                while (sent.isNotEmpty()) settle()
            } finally {
                file.close()
            }
        }
    }

    /** What every packet is signed and sealed with comes from the system, which the processor speeds up; the rest is BouncyCastle's. */
    private class Crypto : SecurityProvider {
        private val system = JceSecurityProvider()
        private val own = BCSecurityProvider()

        override fun getDigest(name: String) = own.getDigest(name)

        override fun getMac(name: String): Mac = try {
            system.getMac(name)
        } catch (_: SecurityException) {
            own.getMac(name)
        }

        override fun getCipher(name: String) = own.getCipher(name)

        override fun getAEADBlockCipher(name: String): AEADBlockCipher = try {
            system.getAEADBlockCipher(name)
        } catch (_: SecurityException) {
            own.getAEADBlockCipher(name)
        }

        override fun getDerivationFunction(name: String) = own.getDerivationFunction(name)
    }

    private companion object {
        const val TIMEOUT_S = 20L

        // How long a connection may be left alone and still be trusted, and how long it has to answer when asked.
        const val IDLE_MS = 30_000L
        const val ECHO_MS = 3_000L

        // How much the first question asks for and the most one asks for, and how many are on their way at once.
        const val FIRST = 64 * 1024
        const val PIECE = 1024 * 1024
        const val AHEAD = 4

        val POOL = Executors.newCachedThreadPool { Thread(it, "smb").apply { isDaemon = true } }
    }
}
