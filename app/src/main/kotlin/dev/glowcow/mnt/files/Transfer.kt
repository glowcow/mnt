package dev.glowcow.mnt.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What happens to an item whose name is already taken where it goes. */
enum class Conflict { REPLACE, KEEP_BOTH, SKIP }

/**
 * Copies, moves and deletes trees of files in a [FileStore]. [onProgress] gets the name being
 * worked on and how much of the whole is done: bytes for a copy or a move, items for a delete.
 */
class Transfer(private val files: FileStore, private val onProgress: (name: String, done: Long, total: Long) -> Unit = { _, _, _ -> }) {
    /** Items that could not be copied, moved or deleted. */
    var failed = 0
        private set

    private var done = 0L
    private var total = 0L

    /** Puts [sources] into the folder [dest]; with [move] they leave where they were. */
    suspend fun copy(sources: List<String>, dest: String, move: Boolean, conflict: Conflict) {
        total = sources.sumOf { bytes(it) }
        for (source in sources) place(source, dest, move, conflict)
    }

    suspend fun delete(paths: List<String>) {
        total = paths.sumOf { count(it) }
        for (path in paths) erase(path)
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
        } else if (duplicate(node, target)) {
            if (move && !files.remove(source)) fail()
        } else {
            fail()
        }
    }

    /** Copies one file under a temporary name next to [target], which it takes once whole. */
    private suspend fun duplicate(node: Node, target: String): Boolean {
        val part = Paths.child(Paths.parent(target), ".${Paths.name(target)}.part")
        try {
            withContext(Dispatchers.IO) {
                files.read(node.path).use { input ->
                    files.write(part).use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            onProgress(node.name, done, total)
                        }
                    }
                }
            }
            files.setModified(part, node.modified)
            if (files.rename(part, target)) return true
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
        const val BUFFER = 256 * 1024
    }
}
