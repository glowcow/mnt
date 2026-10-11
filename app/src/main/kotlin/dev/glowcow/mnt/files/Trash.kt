package dev.glowcow.mnt.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Something in the bin: the [node] where it lies now, the folder it was taken [from], its size and when it went. */
data class Trashed(val id: String, val node: Node, val from: String, val bytes: Long, val deleted: Long)

/**
 * The bin: a hidden folder at the root of each volume, so that putting something in is a rename.
 * An item lies alone in a folder named by the time it went, with a note of where it came from beside it.
 */
class Trash(private val files: FileStore, private val roots: () -> List<String>, private val now: () -> Long = System::currentTimeMillis) {
    @Serializable
    private class Note(val from: String, val bytes: Long, val deleted: Long)

    /** Moves the file or folder at [path], [bytes] large, into the bin of its volume. */
    suspend fun put(path: String, bytes: Long): Boolean {
        val root = roots().filter { Paths.within(path, it) && path != it }.maxByOrNull { it.length } ?: return false
        val node = files.stat(path) ?: return false
        val bin = Paths.child(root, FOLDER)
        if (Paths.within(path, bin)) return false
        if (files.stat(bin) == null && !files.mkdir(bin)) return false
        val taken = files.list(bin).orEmpty().mapTo(HashSet()) { it.name }
        val time = now()
        val entry = Paths.child(bin, generateSequence(0) { it + 1 }.map { "$time-$it" }.first { it !in taken })
        if (!files.mkdir(entry)) return false
        val noted = write(entry + NOTE, Json.encodeToString(Note(Paths.parent(path), bytes, time)))
        if (noted && files.rename(path, Paths.child(entry, node.name))) return true
        files.remove(entry + NOTE)
        files.remove(entry)
        return false
    }

    /** What the bins of the mounted volumes hold, the latest first. */
    suspend fun entries(): List<Trashed> = roots().flatMap { root ->
        files.list(Paths.child(root, FOLDER)).orEmpty().filter { it.directory }.mapNotNull { entry ->
            val node = files.list(entry.path)?.singleOrNull() ?: return@mapNotNull null
            val note = read(entry.path + NOTE)?.let { runCatching { Json.decodeFromString<Note>(it) }.getOrNull() }
            // Without its note an item goes back to the root of its volume.
            Trashed(entry.path, node, note?.from ?: root, note?.bytes ?: node.size, note?.deleted ?: entry.name.substringBefore('-').toLongOrNull() ?: entry.modified)
        }
    }.sortedByDescending { it.deleted }

    /** Puts [item] back where it was, under a spare name when its own was taken meanwhile. */
    suspend fun restore(item: Trashed): Boolean {
        if (!folder(item.from)) return false
        val taken = files.list(item.from).orEmpty().mapTo(HashSet()) { it.name }
        if (!files.rename(item.node.path, Paths.child(item.from, Paths.free(item.node.name) { it in taken }))) return false
        files.remove(item.id + NOTE)
        files.remove(item.id)
        return true
    }

    /** Removes for good what has been in the bin for [KEEP_DAYS]; true when anything was. */
    suspend fun purge(): Boolean {
        val old = entries().filter { daysLeft(it.deleted, now()) <= 0 }
        if (old.isNotEmpty()) Transfer(files).delete(old.flatMap { paths(it) })
        sweep()
        return old.isNotEmpty()
    }

    // What somebody emptied by hand leaves a folder with nothing in it and a note of nothing.
    private suspend fun sweep() {
        for (root in roots()) {
            val held = files.list(Paths.child(root, FOLDER)).orEmpty()
            // A folder just made may still be waiting for its item.
            val bare = held.filter { it.directory && now() - it.modified > SETTLE_MS && files.list(it.path)?.isEmpty() == true }
            bare.forEach { files.remove(it.path) }
            val kept = held.filter { it.directory && it !in bare }.mapTo(HashSet()) { it.name + NOTE }
            held.filter { !it.directory && it.name.endsWith(NOTE) && it.name !in kept && now() - it.modified > SETTLE_MS }.forEach { files.remove(it.path) }
        }
    }

    // The folder may have gone with what it held: it is made again, with the ones above it.
    private suspend fun folder(path: String): Boolean {
        if (files.stat(path)?.directory == true) return true
        val parent = Paths.parent(path)
        return parent != path && folder(parent) && files.mkdir(path)
    }

    private suspend fun write(path: String, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { files.write(path).use { it.write(text.toByteArray()) } }.isSuccess
    }

    private suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        runCatching { files.read(path).use { it.readBytes().decodeToString() } }.getOrNull()
    }

    companion object {
        const val FOLDER = ".mnt-trash"
        const val KEEP_DAYS = 30
        private const val NOTE = ".json"
        private const val SETTLE_MS = 60_000L
        private const val DAY_MS = 24 * 3_600_000L

        /** What deleting [item] for good has to remove. */
        fun paths(item: Trashed): List<String> = listOf(item.id, item.id + NOTE)

        /** When what went into the bin at [deleted] is removed for good. */
        fun until(deleted: Long): Long = deleted + KEEP_DAYS * DAY_MS

        /** Whole days until what went into the bin at [deleted] is removed, the last one counted from its start; 0 when it is due. */
        fun daysLeft(deleted: Long, now: Long): Int {
            val left = until(deleted) - now
            return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt()
        }
    }
}
