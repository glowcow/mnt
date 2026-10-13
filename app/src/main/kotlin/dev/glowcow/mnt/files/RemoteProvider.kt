package dev.glowcow.mnt.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import dev.glowcow.mnt.container
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.InputStream

/** Hands a file that is not in the phone's file system to another app, read from its place as that app asks for it. */
class RemoteProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun node(uri: Uri): Node? {
        val path = uri.pathSegments.firstOrNull()?.takeIf { Remote.id(it) != null } ?: return null
        return runBlocking { context!!.container.files.stat(path) }?.takeIf { !it.directory }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val rows = MatrixCursor(columns)
        val node = node(uri) ?: return rows
        rows.addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) node.name else if (it == OpenableColumns.SIZE) node.size else null })
        return rows
    }

    override fun getType(uri: Uri): String? = uri.pathSegments.firstOrNull()?.let { Opener.mime(Paths.name(it)) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val node = node(uri) ?: throw FileNotFoundException()
        val context = context!!
        // A thread of its own: a slow place must not hold up another file being read.
        val thread = HandlerThread("remote-file").apply { start() }
        return context.getSystemService(StorageManager::class.java)
            .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Reader(context.container.files, node, thread), Handler(thread.looper))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /** Answers reads anywhere in the file from one stream, opened again where a read jumps. */
    private class Reader(private val files: FileStore, private val node: Node, private val thread: HandlerThread) : ProxyFileDescriptorCallback() {
        private var stream: InputStream? = null
        private var at = 0L

        override fun onGetSize(): Long = node.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
            var from = stream
            // A short step forward is read through; anything else opens the file anew there.
            if (from != null && offset > at && offset - at <= SKIP) {
                val gap = ByteArray((offset - at).toInt())
                var passed = 0
                while (passed < gap.size) passed += from.read(gap, passed, gap.size - passed).also { if (it < 0) throw java.io.EOFException() }
                at = offset
            }
            if (from == null || offset != at) {
                from?.close()
                from = files.read(node.path, offset).also { stream = it }
                at = offset
            }
            var got = 0
            while (got < size) {
                val n = from.read(data, got, size - got)
                if (n < 0) break
                got += n
            }
            at += got
            got
        } catch (_: Exception) {
            runCatching { stream?.close() }
            stream = null
            throw ErrnoException("read", OsConstants.EIO)
        }

        override fun onRelease() {
            runCatching { stream?.close() }
            thread.quitSafely()
        }

        private companion object {
            const val SKIP = 512 * 1024L
        }
    }

    companion object {
        fun uri(context: Context, path: String): Uri = Uri.Builder().scheme("content").authority("${context.packageName}.remote").appendPath(path).build()
    }
}
