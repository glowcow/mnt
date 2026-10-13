package dev.glowcow.mnt.net

import com.emc.ecs.nfsclient.nfs.NfsDirectoryPlusEntry
import com.emc.ecs.nfsclient.nfs.NfsSetAttributes
import com.emc.ecs.nfsclient.nfs.NfsTime
import com.emc.ecs.nfsclient.nfs.NfsType
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.io.NfsFileOutputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import dev.glowcow.mnt.files.Node
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * An export of an NFS 3 server. The app cannot call from a port under 1024, so the export has to
 * allow others (`insecure`); the server takes the user and the group at their word.
 */
class NfsStore(private val c: Connection) : RemoteStore() {
    private var nfs: Nfs3? = null

    @Synchronized
    private fun nfs(): Nfs3 = nfs ?: run {
        val ids = c.user.split(':', ' ', ',').mapNotNull { it.trim().toIntOrNull() }
        val who = CredentialUnix(ids.getOrElse(0) { DEFAULT_ID }, ids.getOrElse(1) { ids.getOrElse(0) { DEFAULT_ID } }, null)
        Nfs3(c.host.trim(), "/" + c.share.trim('/'), who, RETRIES).also { nfs = it }
    }

    @Synchronized
    override fun close() {
        nfs = null
    }

    private fun file(path: String): Nfs3File = Nfs3File(nfs(), path)

    override fun listing(path: String): List<Node> {
        val folder = file(path)
        val entries = ArrayList<NfsDirectoryPlusEntry>()
        var cookie = 0L
        var verifier = 0L
        // The names come with what is known of each, a page at a time.
        while (true) {
            val page = folder.readdirplus(cookie, verifier, PAGE, PAGE, entries)
            if (page.isEof) break
            cookie = page.cookie
            verifier = page.cookieverf
        }
        return entries.filter { it.fileName != "." && it.fileName != ".." }.map { entry ->
            val known = entry.attributes?.takeIf { it.isLoaded } ?: return@map node(child(path, entry.fileName)) ?: Node(child(path, entry.fileName), entry.fileName, false, 0, 0)
            val isFolder = known.type == NfsType.NFS_DIR
            Node(child(path, entry.fileName), entry.fileName, isFolder, if (isFolder) 0 else known.size, known.mtime.timeInMillis)
        }
    }

    override fun node(path: String): Node? {
        val file = file(path).followLinks()
        if (!file.exists()) return null
        val folder = file.isDirectory
        return Node(path, name(path), folder, if (folder) 0 else file.lengthEx(), file.lastModified())
    }

    override fun makeFolder(path: String) = file(path).mkdir()

    override fun move(from: String, to: String) {
        if (!file(from).renameTo(file(to))) throw IOException("Could not be renamed")
    }

    override fun delete(path: String) = file(path).delete()

    override fun touch(path: String, time: Long) = file(path).setAttributes(NfsSetAttributes(null, null, null, null, NfsTime.DO_NOT_CHANGE, NfsTime(time)))

    override fun input(path: String, offset: Long): InputStream = NfsFileInputStream(file(path), offset, BUFFER)

    override fun output(path: String, size: Long): OutputStream = NfsFileOutputStream(file(path))

    private companion object {
        const val DEFAULT_ID = 1000
        const val RETRIES = 3
        const val PAGE = 32 * 1024
        const val BUFFER = 256 * 1024
    }
}
