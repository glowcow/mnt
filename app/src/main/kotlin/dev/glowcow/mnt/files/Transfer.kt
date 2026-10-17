package dev.glowcow.mnt.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What happens to an item whose name is already taken where it goes. */
enum class Conflict { REPLACE, KEEP_BOTH, SKIP }

/**
 * One archive to unpack: what lies in the folder [root] goes into the new folder [target]. A lone folder called
 * [stem] is gone into rather than kept, and a lone file of that name becomes [target] itself, so nothing is doubled.
 */
class Unpacking(val root: String, val stem: String, val target: String)

/** There is less room where the files go than they take. */
class NoRoomException : IOException("No room")

/**
 * Copies, moves, deletes and packs trees of files in a [FileStore]. [onProgress] gets the name being
 * worked on and how much of the whole is done: bytes for a copy, a move or an archive, items for a delete.
 */
class Transfer(private val files: FileStore, private val onProgress: (name: String, done: Long, total: Long) -> Unit = { _, _, _ -> }) {
    /** Items that could not be copied, moved or deleted. */
    var failed = 0
        private set

    private var done = 0L
    private var total = 0L

    // Files of a place that is only read — an archive — and where each goes: copied together, in one pass through it.
    private val waiting = ArrayList<Pair<Node, String>>()

    /** Puts [sources] into the folder [dest]; with [move] they leave where they were. */
    suspend fun copy(sources: List<String>, dest: String, move: Boolean, conflict: Conflict) {
        total = sources.sumOf { bytes(it) }
        for (source in sources) place(source, dest, move, conflict)
        flush()
    }

    /** Unpacks each of [archives] next to itself; a folder is filled under a temporary name and takes its own once whole. */
    suspend fun unpack(archives: List<Unpacking>) {
        // What each holds is learnt first: for an archive packed as one stream that is a pass through all of it.
        val inside = archives.map { archive ->
            val top = files.list(archive.root)
            val lone = top?.singleOrNull()?.takeIf { it.directory && it.name == archive.stem }
            if (lone != null) files.list(lone.path) else top
        }
        total = inside.sumOf { nodes -> nodes.orEmpty().sumOf { bytes(it.path) } }
        archives.zip(inside).forEach { (archive, nodes) ->
            if (nodes == null) return@forEach fail()
            val need = nodes.sumOf { bytes(it.path) }
            files.free(Paths.parent(archive.target))?.let { if (need > it) throw NoRoomException() }
            val file = nodes.singleOrNull()?.takeIf { !it.directory && it.name == archive.stem && Paths.parent(it.path) == archive.root }
            if (file != null) {
                waiting += file to archive.target
                return@forEach flush()
            }
            val part = if (files.renames(archive.target)) Paths.child(Paths.parent(archive.target), ".${Paths.name(archive.target)}.part") else archive.target
            try {
                if (!files.mkdir(part)) return@forEach fail()
                for (node in nodes) place(node.path, part, move = false, Conflict.REPLACE)
                flush()
                if (part != archive.target && !files.rename(part, archive.target)) {
                    clear(part)
                    fail()
                }
            } catch (e: Exception) {
                withContext(NonCancellable) { clear(part) }
                throw e
            }
        }
    }

    suspend fun delete(paths: List<String>) {
        total = paths.sumOf { count(it) }
        for (path in paths) erase(path)
    }

    /** Puts [paths] into [trash], from where they can be taken back. */
    suspend fun discard(paths: List<String>, trash: Trash) {
        total = paths.size.toLong()
        for (path in paths) {
            currentCoroutineContext().ensureActive()
            if (!trash.put(path, bytes(path))) failed++
            done++
            onProgress(Paths.name(path), done, total)
        }
    }

    /** Packs [sources] into a zip archive at [target], which it takes once whole. */
    suspend fun zip(sources: List<String>, target: String) {
        total = sources.sumOf { bytes(it) }
        val part = if (files.renames(target)) Paths.child(Paths.parent(target), ".${Paths.name(target)}.part") else target
        try {
            withContext(Dispatchers.IO) {
                ZipOutputStream(files.write(part).buffered(BUFFER)).use { zip -> for (source in sources) pack(source, "", zip) }
            }
            if (part != target && !files.rename(part, target)) {
                files.remove(part)
                fail()
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { files.remove(part) }
            throw e
        }
    }

    /** Bytes of the files in the tree at [path]. */
    suspend fun bytes(path: String): Long {
        currentCoroutineContext().ensureActive()
        val node = files.stat(path) ?: return 0
        return if (node.directory) files.list(path).orEmpty().sumOf { if (it.directory) bytes(it.path) else it.size } else node.size
    }

    /** Files and folders in the tree at [path], itself included. */
    suspend fun count(path: String): Long {
        currentCoroutineContext().ensureActive()
        val node = files.stat(path) ?: return 0
        return 1 + if (node.directory) files.list(path).orEmpty().sumOf { if (it.directory) count(it.path) else 1L } else 0
    }

    private suspend fun place(source: String, dest: String, move: Boolean, conflict: Conflict) {
        currentCoroutineContext().ensureActive()
        val node = files.stat(source) ?: return fail()
        // A folder cannot go into itself: the copy would never end.
        if (node.directory && Paths.within(dest, source)) return fail()
        var target = Paths.child(dest, node.name)
        val taken = files.stat(target)
        var merge = false
        if (taken != null) {
            when {
                // Onto itself: a move changes nothing, a copy makes a second one.
                target == source -> if (move) return step(source, node) else target = spare(dest, node.name)
                conflict == Conflict.SKIP -> return step(source, node)
                conflict == Conflict.KEEP_BOTH -> target = spare(dest, node.name)
                node.directory && taken.directory -> merge = true
                // A file gives way to a folder and a folder to a file; a file over a file is replaced by the rename.
                node.directory != taken.directory -> if (!clear(target)) return fail()
            }
        }
        if (move && !merge && files.rename(source, target)) return step(target, node)
        if (node.directory) {
            if (!merge && !files.mkdir(target)) return fail()
            val children = files.list(source) ?: return fail()
            for (child in children) place(child.path, target, move, conflict)
            // Still holds what could not be moved; then it stays.
            if (move) files.remove(source)
        } else if (!files.writes(source)) {
            waiting += node to target
        } else if (duplicate(node, target)) {
            if (move && !files.remove(source)) fail()
        } else {
            fail()
        }
    }

    /** Copies the files [waiting] in one pass through the place they lie in; what is not handed over counts as failed. */
    private suspend fun flush() {
        if (waiting.isEmpty()) return
        val targets = waiting.associateBy { it.first.path }
        val given = HashSet<String>()
        files.readEach(waiting.map { it.first.path }) { path, input ->
            val (node, target) = targets.getValue(path)
            given += path
            if (!duplicate(node, target) { input }) fail()
        }
        repeat(waiting.count { it.first.path !in given }) { fail() }
        waiting.clear()
    }

    /** Copies one file, read from [open], under a temporary name next to [target], which it takes once whole. */
    private suspend fun duplicate(node: Node, target: String, open: () -> InputStream = { files.read(node.path) }): Boolean {
        // Where nothing can be renamed the file is written under its own name, over what was there.
        val part = if (files.renames(target)) Paths.child(Paths.parent(target), ".${Paths.name(target)}.part") else target
        try {
            withContext(Dispatchers.IO) {
                open().use { input ->
                    files.write(part, node.size).use { output ->
                        pump(input) { buffer, n ->
                            output.write(buffer, 0, n)
                            done += n
                            onProgress(node.name, done, total)
                        }
                    }
                }
            }
            files.setModified(part, node.modified)
            if (part == target || files.rename(part, target)) return true
            files.remove(part)
            return false
        } catch (e: CancellationException) {
            withContext(NonCancellable) { files.remove(part) }
            throw e
        } catch (_: Exception) {
            files.remove(part)
            return false
        }
    }

    /** Reads [input] to its end on one thread and hands what it read to [take] on another, so that neither waits for the other. */
    private suspend fun pump(input: InputStream, take: (ByteArray, Int) -> Unit) = coroutineScope {
        val filled = Channel<Pair<ByteArray, Int>>(QUEUE)
        val spare = Channel<ByteArray>(Channel.UNLIMITED)
        launch {
            try {
                while (true) {
                    ensureActive()
                    val buffer = spare.tryReceive().getOrNull() ?: ByteArray(BUFFER)
                    var n = 0
                    while (n < buffer.size) {
                        // Interruptible: Stop lets go of a read that goes a long way through an archive.
                        val got = runInterruptible { input.read(buffer, n, buffer.size - n) }
                        if (got < 0) break
                        n += got
                    }
                    if (n > 0) filled.send(buffer to n)
                    if (n < buffer.size) break
                }
            } finally {
                filled.close()
            }
        }
        for ((buffer, n) in filled) {
            take(buffer, n)
            spare.trySend(buffer)
        }
    }

    /** Writes the tree at [path] into [zip] under [prefix]; a file that cannot be opened is left out. */
    private suspend fun pack(path: String, prefix: String, zip: ZipOutputStream) {
        currentCoroutineContext().ensureActive()
        val node = files.stat(path) ?: return fail()
        val name = prefix + node.name
        if (node.directory) {
            val children = files.list(path) ?: return fail()
            // An entry of its own keeps a folder that holds nothing.
            zip.putNextEntry(ZipEntry("$name/").apply { time = node.modified })
            zip.closeEntry()
            for (child in children) pack(child.path, "$name/", zip)
            return
        }
        val input = try {
            files.read(path)
        } catch (_: IOException) {
            return fail()
        }
        input.use {
            zip.putNextEntry(ZipEntry(name).apply { time = node.modified })
            val buffer = ByteArray(BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = it.read(buffer)
                if (n < 0) break
                zip.write(buffer, 0, n)
                done += n
                onProgress(node.name, done, total)
            }
            zip.closeEntry()
        }
    }

    private suspend fun erase(path: String) {
        currentCoroutineContext().ensureActive()
        val node = files.stat(path) ?: return
        if (node.directory) files.list(path)?.forEach { erase(it.path) }
        if (!files.remove(path)) failed++
        done++
        onProgress(node.name, done, total)
    }

    /** Deletes the tree at [path] without counting it; true when nothing is left. */
    private suspend fun clear(path: String): Boolean {
        val node = files.stat(path) ?: return true
        if (node.directory) files.list(path)?.forEach { clear(it.path) }
        return files.remove(path)
    }

    private suspend fun spare(folder: String, name: String): String {
        val taken = files.list(folder).orEmpty().mapTo(HashSet()) { it.name }
        return Paths.child(folder, Paths.free(name) { it in taken })
    }

    /** Counts a tree that needed no copying as done. */
    private suspend fun step(path: String, node: Node) {
        done += if (node.directory) bytes(path) else node.size
        onProgress(node.name, done, total)
    }

    private fun fail() {
        failed++
    }

    private companion object {
        const val BUFFER = 1024 * 1024

        // Buffers read and not yet written.
        const val QUEUE = 4
    }
}
