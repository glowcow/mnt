package dev.glowcow.mnt.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.share.DiskShare
import dev.glowcow.mnt.files.Node
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** A share of an SMB 2 or 3 server — Windows, Samba, a NAS. */
class SmbStore(private val c: Connection) : RemoteStore() {
    private var client: SMBClient? = null
    private var share: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        close()
        // NTLM alone: the Kerberos way in leans on classes Android does not have.
        val config = SmbConfig.builder().withTimeout(TIMEOUT_S, TimeUnit.SECONDS).withSoTimeout(TIMEOUT_S, TimeUnit.SECONDS).withAuthenticators(NtlmAuthenticator.Factory()).build()
        val opened = SMBClient(config)
        client = opened
        // A name written as DOMAIN\user tells the domain too; no name at all comes in as a guest.
        val auth = if (c.user.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(c.user.substringAfter('\\'), c.password.toCharArray(), c.user.substringBefore('\\', "").ifEmpty { null })
        }
        val session = opened.connect(c.host.trim(), c.port.takeIf { it > 0 } ?: c.protocol.port).authenticate(auth)
        val disk = session.connectShare(c.share.trim('/', '\\')) as? DiskShare ?: throw IOException("Not a share of files")
        return disk.also { share = it }
    }

    @Synchronized
    override fun close() {
        runCatching { client?.close() }
        client = null
        share = null
    }

    override fun listing(path: String): List<Node> = share().list(inner(path)).filter { it.fileName != "." && it.fileName != ".." }.map {
        val folder = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
        Node(child(path, it.fileName), it.fileName, folder, if (folder) 0 else it.endOfFile, it.lastWriteTime.toEpochMillis())
    }

    override fun node(path: String): Node? = try {
        val known = share().getFileInformation(inner(path))
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
        val file = share().openFile(inner(path), setOf(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        return object : InputStream() {
            private var at = offset

            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xFF }

            override fun read(b: ByteArray, off: Int, len: Int): Int = file.read(b, at, off, len).also { if (it > 0) at += it }

            override fun close() = file.close()
        }
    }

    override fun output(path: String, size: Long): OutputStream {
        val file = share().openFile(inner(path), setOf(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
        val out = file.outputStream
        return object : OutputStream() {
            override fun write(b: Int) = out.write(b)

            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

            override fun close() {
                try {
                    out.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    // The server names a path with backslashes and from the share's root, which is the empty one.
    private fun inner(path: String): String = path.trim('/').replace('/', '\\')

    private companion object {
        const val TIMEOUT_S = 20L
    }
}
