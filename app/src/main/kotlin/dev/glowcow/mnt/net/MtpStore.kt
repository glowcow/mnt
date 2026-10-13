package dev.glowcow.mnt.net

import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.ParcelFileDescriptor
import dev.glowcow.mnt.files.Node
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * A camera, a phone or a player on the USB port, spoken to over MTP. Its storages are the folders of
 * its root. Nothing there can be renamed, and a file has to say how large it is before it is sent —
 * one whose size is not known is first gathered in [spool].
 */
class MtpStore(private val device: MtpDevice, private val spool: File) : RemoteStore() {
    // The device knows things by number; the numbers met on the way are kept by path.
    private val handles = ConcurrentHashMap<String, Int>()

    private class Spot(val storage: Int, val handle: Int)

    private fun storages(): List<Pair<String, Int>> {
        val ids = device.storageIds ?: throw IOException("The device does not answer")
        val taken = HashSet<String>()
        return ids.map { id ->
            val said = device.getStorageInfo(id)?.description?.trim()?.replace('/', '-').orEmpty().ifEmpty { "Storage" }
            generateSequence(1) { it + 1 }.map { if (it == 1) said else "$said ($it)" }.first { taken.add(it) } to id
        }
    }

    private fun children(spot: Spot): List<MtpObjectInfo> =
        (device.getObjectHandles(spot.storage, 0, spot.handle) ?: throw IOException("The device does not answer")).toList().mapNotNull { device.getObjectInfo(it) }

    private fun spot(path: String): Spot? {
        val steps = path.trim('/').split('/')
        val storage = storages().firstOrNull { it.first == steps.first() }?.second ?: return null
        var at = "/" + steps.first()
        var spot = Spot(storage, TOP)
        for (step in steps.drop(1)) {
            at = "$at/$step"
            val handle = handles[at] ?: children(spot).firstOrNull { it.name == step }?.objectHandle?.also { handles[at] = it } ?: return null
            spot = Spot(storage, handle)
        }
        return spot
    }

    private fun MtpObjectInfo.node(path: String): Node {
        val folder = format == MtpConstants.FORMAT_ASSOCIATION
        return Node(path, name, folder, if (folder) 0 else compressedSizeLong, dateModified)
    }

    override fun renames(path: String): Boolean = false

    override fun listing(path: String): List<Node> {
        if (path == ROOT) return storages().map { (name, _) -> Node(child(ROOT, name), name, true, 0, 0) }
        val spot = spot(path) ?: throw IOException("No such folder")
        return children(spot).filter { !it.name.isNullOrEmpty() }.map { info ->
            val at = child(path, info.name)
            handles[at] = info.objectHandle
            info.node(at)
        }
    }

    override fun node(path: String): Node? {
        val spot = spot(path) ?: return null
        if (spot.handle == TOP) return Node(path, name(path), true, 0, 0)
        return device.getObjectInfo(spot.handle)?.node(path) ?: null.also { handles.remove(path) }
    }

    override fun makeFolder(path: String) {
        val into = spot(parent(path)) ?: throw IOException("No such folder")
        val made = MtpObjectInfo.Builder().setStorageId(into.storage).setParent(into.under()).setName(name(path))
            .setFormat(MtpConstants.FORMAT_ASSOCIATION).setAssociationType(MtpConstants.ASSOCIATION_TYPE_GENERIC_FOLDER).build()
        device.sendObjectInfo(made) ?: throw IOException("The device refused the folder")
    }

    override fun move(from: String, to: String): Unit = throw IOException("Nothing can be renamed on this device")

    override fun delete(path: String) {
        val spot = spot(path)?.takeIf { it.handle != TOP } ?: throw IOException("No such file")
        if (!device.deleteObject(spot.handle)) throw IOException("The device refused to delete")
        handles.keys.removeAll { it == path || it.startsWith("$path/") }
    }

    override fun input(path: String, offset: Long): InputStream {
        val handle = spot(path)?.handle?.takeIf { it != TOP } ?: throw IOException("No such file")
        if (offset == 0L) {
            val (read, write) = ParcelFileDescriptor.createPipe()
            thread(name = "mtp-read") { write.use { device.importFile(handle, it) } }
            return ParcelFileDescriptor.AutoCloseInputStream(read)
        }
        val size = device.getObjectInfo(handle)?.compressedSizeLong ?: throw IOException("No such file")
        // From the middle the file is asked for piece by piece.
        return object : InputStream() {
            private var at = offset
            private val piece = ByteArray(PIECE)

            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xFF }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= size) return -1
                val want = minOf(len.toLong(), PIECE.toLong(), size - at)
                val got = device.getPartialObject64(handle, at, want, piece).toInt()
                if (got <= 0) throw IOException("The device does not answer")
                System.arraycopy(piece, 0, b, off, got)
                at += got
                return got
            }
        }
    }

    override fun output(path: String, size: Long): OutputStream {
        if (size >= 0) return send(path, size)
        spool.mkdirs()
        val kept = File.createTempFile("mtp", null, spool)
        return object : java.io.FileOutputStream(kept) {
            private var closed = false

            override fun close() {
                super.close()
                if (closed) return
                closed = true
                try {
                    send(path, kept.length()).use { out -> kept.inputStream().use { it.copyTo(out, PIECE) } }
                } finally {
                    kept.delete()
                }
            }
        }
    }

    /** Announces a file of [size] bytes at [path], over the one that is there, and takes its bytes. */
    private fun send(path: String, size: Long): OutputStream {
        val into = spot(parent(path)) ?: throw IOException("No such folder")
        spot(path)?.takeIf { it.handle != TOP }?.let { device.deleteObject(it.handle) }
        handles.remove(path)
        val told = MtpObjectInfo.Builder().setStorageId(into.storage).setParent(into.under()).setName(name(path))
            .setFormat(MtpConstants.FORMAT_UNDEFINED).setCompressedSize(size).setDateModified(System.currentTimeMillis()).build()
        val handle = device.sendObjectInfo(told)?.objectHandle ?: throw IOException("The device refused the file")
        val (read, write) = ParcelFileDescriptor.createPipe()
        var sent = false
        val sending = thread(name = "mtp-write") { read.use { sent = device.sendObject(handle, size, it) } }
        return object : ParcelFileDescriptor.AutoCloseOutputStream(write) {
            private var closed = false

            override fun close() {
                super.close()
                if (closed) return
                closed = true
                sending.join()
                if (!sent) throw IOException("The device did not take the file")
            }
        }
    }

    // A new object's parent is told as 0 at the top of a storage, a listing's as all ones.
    private fun Spot.under(): Int = if (handle == TOP) 0 else handle

    private companion object {
        const val TOP = -1
        const val PIECE = 1024 * 1024
    }
}
