package dev.glowcow.mnt.archive

import dev.glowcow.mnt.files.Node
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A file or folder of an archive; [ordinal] counts the entries before the one that holds its data, -1 for a folder no entry names. */
class Item(val path: String, val directory: Boolean, val size: Long, val modified: Long, val ordinal: Int, val locked: Boolean) {
    val name: String get() = path.substringAfterLast('/')

    fun node(): Node = Node(path, name, directory, if (directory) 0 else size, modified)
}

/**
 * What an archive holds, by path from its own root `/`. Kept as arrays — a name and its folder's number for each
 * entry, no path — so an archive of many thousands of files takes a few dozen bytes of memory for each.
 */
class ArchiveIndex private constructor(
    private val names: Array<String>,
    private val parents: IntArray,
    private val sizes: LongArray,
    private val times: LongArray,
    private val ordinals: IntArray,
    private val flags: ByteArray,
) {
    private val count = names.size
    private val table = Table(count).also { table -> for (i in 1 until count) table.put(i, parents[i], names[i], parents, names) }

    // The entries of each folder, one after another: those of folder f are at firsts[f] until firsts[f + 1].
    private val firsts = IntArray(count + 1)
    private val members = IntArray(maxOf(count - 1, 0))

    init {
        for (i in 1 until count) firsts[parents[i] + 1]++
        for (f in 0 until count) firsts[f + 1] += firsts[f]
        val at = firsts.copyOf()
        for (i in 1 until count) members[at[parents[i]]++] = i
    }

    /** How many files and folders it holds. */
    val size: Int get() = count - 1

    fun item(path: String): Item? = find(path).takeIf { it >= 0 }?.let { item(it, path) }

    /** What the folder at [path] holds; null when there is no such folder. */
    fun children(path: String): List<Item>? {
        val folder = find(path)
        if (folder < 0 || flags[folder].toInt() and FOLDER == 0) return null
        val prefix = if (path == ROOT) "" else path
        return (firsts[folder] until firsts[folder + 1]).map { member -> members[member].let { item(it, "$prefix/${names[it]}") } }
    }

    private fun item(i: Int, path: String) = Item(path, flags[i].toInt() and FOLDER != 0, sizes[i], times[i], ordinals[i], flags[i].toInt() and LOCKED != 0)

    private fun find(path: String): Int {
        var at = 0
        for (step in path.split('/')) {
            if (step.isEmpty()) continue
            at = table.find(at, step, parents, names)
            if (at < 0) return -1
        }
        return at
    }

    /** Writes it for [read] to take back. */
    fun write(out: DataOutputStream) {
        out.writeInt(VERSION)
        out.writeInt(count)
        for (i in 0 until count) {
            out.writeUTF(names[i])
            out.writeInt(parents[i])
            out.writeLong(sizes[i])
            out.writeLong(times[i])
            out.writeInt(ordinals[i])
            out.writeByte(flags[i].toInt())
        }
    }

    /** An open table of entries by their folder and name; [put] and [find] take the arrays the numbers stand for. */
    private class Table(entries: Int) {
        var slots = IntArray(capacity(entries)) { -1 }
        private var used = 0

        fun find(parent: Int, name: String, parents: IntArray, names: Array<out String?>): Int {
            val mask = slots.size - 1
            var i = hash(parent, name) and mask
            while (true) {
                val e = slots[i]
                if (e < 0) return -1
                if (parents[e] == parent && names[e] == name) return e
                i = (i + 1) and mask
            }
        }

        fun put(entry: Int, parent: Int, name: String, parents: IntArray, names: Array<out String?>) {
            if ((used + 1) * 2 > slots.size) {
                val old = slots
                slots = IntArray(old.size * 2) { -1 }
                for (e in old) if (e >= 0) place(e, parents[e], names[e]!!)
            }
            place(entry, parent, name)
            used++
        }

        private fun place(entry: Int, parent: Int, name: String) {
            val mask = slots.size - 1
            var i = hash(parent, name) and mask
            while (slots[i] >= 0) i = (i + 1) and mask
            slots[i] = entry
        }

        private fun hash(parent: Int, name: String): Int {
            val h = parent * -0x61c88647 xor name.hashCode()
            return h xor (h ushr 16)
        }

        private companion object {
            fun capacity(entries: Int): Int = Integer.highestOneBit(maxOf(entries, 8) * 2 - 1) shl 1
        }
    }

    /** Gathers entries as the archive gives them; refuses to hold more than [limit]. */
    private class Builder(private val limit: Int, born: Long) {
        var count = 0
        var names = arrayOfNulls<String>(START)
        var parents = IntArray(START)
        var sizes = LongArray(START)
        var times = LongArray(START)
        var ordinals = IntArray(START)
        var flags = ByteArray(START)
        val table = Table(START)

        init {
            add(-1, "", FOLDER, 0, born, -1)
        }

        fun find(parent: Int, name: String): Int = table.find(parent, name, parents, names)

        fun add(parent: Int, name: String, flag: Int, size: Long, time: Long, ordinal: Int): Int {
            if (count > limit) throw ArchiveException(Reason.TOO_MANY)
            if (count == names.size) grow()
            names[count] = name
            parents[count] = parent
            set(count, flag, size, time, ordinal)
            if (parent >= 0) table.put(count, parent, name, parents, names)
            return count++
        }

        fun set(i: Int, flag: Int, size: Long, time: Long, ordinal: Int) {
            flags[i] = flag.toByte()
            sizes[i] = size
            times[i] = time
            ordinals[i] = ordinal
        }

        fun folder(i: Int): Boolean = flags[i].toInt() and FOLDER != 0

        private fun grow() {
            val n = names.size * 2
            names = names.copyOf(n)
            parents = parents.copyOf(n)
            sizes = sizes.copyOf(n)
            times = times.copyOf(n)
            ordinals = ordinals.copyOf(n)
            flags = flags.copyOf(n)
        }

        @Suppress("UNCHECKED_CAST")
        fun build() = ArchiveIndex(
            names.copyOf(count) as Array<String>, parents.copyOf(count), sizes.copyOf(count),
            times.copyOf(count), ordinals.copyOf(count), flags.copyOf(count),
        )
    }

    companion object {
        const val ROOT = "/"

        private const val FOLDER = 1
        private const val LOCKED = 2
        private const val START = 64
        private const val VERSION = 1

        /**
         * Reads [walk] through to its end. Names are made safe to unpack: no step up, no root, none of the signs a
         * card's file system refuses; the Mac's `__MACOSX` copies are left out. A file that is only compressed is
         * called [stem]; folders no entry names are dated [born]. More than [limit] entries is refused.
         * [onProgress] hears how far it has got, 0–1.
         */
        suspend fun read(walk: Walk, stem: String, born: Long, limit: Int = Int.MAX_VALUE, onProgress: (Float) -> Unit = {}): ArchiveIndex {
            val b = Builder(limit, born)
            val links = ArrayList<Pair<String, String>>()
            var ordinal = -1

            // The folder holding [steps], made where no entry made it; a file in the way gives way to it.
            fun folder(steps: List<String>): Int {
                var at = 0
                for (step in steps) {
                    val found = b.find(at, step)
                    at = when {
                        found < 0 -> b.add(at, step, FOLDER, 0, born, -1)
                        !b.folder(found) -> found.also { b.set(it, FOLDER, 0, born, -1) }
                        else -> found
                    }
                }
                return at
            }

            fun put(path: String, directory: Boolean, size: Long, time: Long, ordinal: Int, locked: Boolean) {
                val steps = path.split('/').filter { it.isNotEmpty() }
                val parent = folder(steps.dropLast(1))
                val name = steps.last()
                val found = b.find(parent, name)
                val flag = if (directory) FOLDER else if (locked) LOCKED else 0
                when {
                    found < 0 -> b.add(parent, name, flag, if (directory) 0 else size, time, ordinal)
                    // A folder named by an entry of its own takes that entry's time; a file does not take a folder's place.
                    directory -> if (!b.folder(found) || b.ordinals[found] < 0) b.set(found, FOLDER, 0, time, ordinal)
                    !b.folder(found) -> b.set(found, flag, size, time, ordinal)
                }
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                // Stepping over a large entry of a stream reads all of it: leaving lets go of it at once.
                val header = runInterruptible { walk.next() } ?: break
                ordinal++
                onProgress(walk.done)
                val path = if (header.raw) "/$stem" else clean(decode(header.name), header.zip) ?: continue
                val time = header.modified.takeIf { it > 0 } ?: born
                when (header.kind) {
                    EntryKind.FOLDER -> put(path, true, 0, time, ordinal, false)
                    // A stream that does not say how long it is gets counted.
                    EntryKind.FILE -> put(path, false, if (header.size >= 0) header.size else runInterruptible { count(walk) }, time, ordinal, header.locked)
                    EntryKind.HARDLINK -> header.link?.let { clean(decode(it), header.zip) }?.let { links += path to it }
                    // Links and devices are not unpacked: a link could point anywhere.
                    else -> Unit
                }
            }
            val index = b.build()
            if (links.isEmpty()) return index
            // A hard link reads the data of the file it stands for.
            for ((path, target) in links) {
                val file = index.item(target)?.takeIf { !it.directory } ?: continue
                put(path, false, file.size, file.modified, file.ordinal, file.locked)
            }
            return b.build()
        }

        /** Takes back what [write] wrote; null when it is not that. */
        fun read(input: DataInputStream): ArchiveIndex? = try {
            if (input.readInt() != VERSION) {
                null
            } else {
                val count = input.readInt()
                val names = arrayOfNulls<String>(count)
                val parents = IntArray(count)
                val sizes = LongArray(count)
                val times = LongArray(count)
                val ordinals = IntArray(count)
                val flags = ByteArray(count)
                for (i in 0 until count) {
                    names[i] = input.readUTF()
                    parents[i] = input.readInt()
                    sizes[i] = input.readLong()
                    times[i] = input.readLong()
                    ordinals[i] = input.readInt()
                    flags[i] = input.readByte()
                }
                @Suppress("UNCHECKED_CAST")
                ArchiveIndex(names as Array<String>, parents, sizes, times, ordinals, flags)
            }
        } catch (_: IOException) {
            null
        }

        private fun count(walk: Walk): Long {
            val buffer = ByteArray(BUFFER)
            var size = 0L
            while (true) {
                val n = walk.read(buffer, 0, buffer.size)
                if (n < 0) return size
                size += n
            }
        }

        /** [name] as a path from the archive's root, or null for one that must not be unpacked. */
        fun clean(name: String, zip: Boolean): String? {
            val steps = (if (zip) name.replace('\\', '/') else name).split('/').filter { it.isNotEmpty() && it != "." }
            if (steps.isEmpty() || steps.any { it == ".." } || steps.first() == "__MACOSX") return null
            return steps.joinToString("/", prefix = "/") { step -> step.map { if (it < ' ' || it in REFUSED) '_' else it }.joinToString("") }
        }

        /** A stored name: UTF-8 where it is that, else the DOS code page a zip of Windows writes. */
        fun decode(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, DOS)
        }

        private val DOS: Charset = Charset.forName("IBM866")

        // What the file systems of cards refuse in a name.
        private const val REFUSED = "\\:*?\"<>|"

        private const val BUFFER = 256 * 1024
    }
}
