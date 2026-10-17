package dev.glowcow.mnt.archive

import dev.glowcow.mnt.files.Conflict
import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.LocalStore
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.Stores
import dev.glowcow.mnt.files.Transfer
import dev.glowcow.mnt.files.Unpacking
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchivesTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** An entry of a made-up archive: a name as stored, and data for a file. */
    private class Entry(val name: ByteArray, val kind: EntryKind, val data: ByteArray = ByteArray(0), val locked: Boolean = false, val link: String? = null, val sized: Boolean = true)

    private fun file(name: String, text: String, locked: Boolean = false) = Entry(name.toByteArray(), EntryKind.FILE, text.toByteArray(), locked)

    private fun folder(name: String) = Entry(name.toByteArray(), EntryKind.FOLDER)

    /** A walk through [entries] as libarchive gives them; [opened] counts the walks begun. */
    private inner class ListWalk(private val entries: List<Entry>, private val raw: Boolean = false) : Walk {
        private var at = -1
        private var read = 0

        override fun next(): Header? {
            at++
            read = 0
            val e = entries.getOrNull(at) ?: return null
            return Header(e.name, e.kind, if (e.sized) e.data.size.toLong() else -1, 1_600_000_000_000, e.locked, zip = true, raw = raw, link = e.link?.toByteArray())
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val data = entries[at].data
            if (read >= data.size) return -1
            val n = minOf(length, data.size - read)
            data.copyInto(buffer, offset, read, read + n)
            read += n
            return n
        }

        override val done: Float get() = (at + 1f) / entries.size

        override fun close() = Unit
    }

    private var opened = 0

    /** The phone's storage with the archive [name] in it, holding [entries]; the archive is a place of [files]. */
    private fun archive(name: String, entries: List<Entry>, raw: Boolean = false): Pair<Stores, String> {
        val path = File(temp.root, name).apply { writeText("archive") }.path
        lateinit var files: Stores
        val store by lazy { ArchiveStore(path, files) { _, _, _, _ -> opened++; ListWalk(entries, raw) } }
        files = Stores(LocalStore()) { id -> if (Archives.archive(id) == path) store else null }
        return files to path
    }

    private fun tree(root: File) = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()

    @Test
    fun namesAnArchiveByItsWholePath() {
        val root = Archives.root("/storage/emulated/0/Download/a b.zip")
        assertTrue(root.startsWith("//@"))
        assertFalse(root.drop(2).contains('/'))
        assertEquals("/storage/emulated/0/Download/a b.zip", Archives.of("$root/x/y.txt"))
        assertNull(Archives.of("/storage/emulated/0/a.zip"))
        assertNull(Archives.archive("nas"))
    }

    @Test
    fun takesTheStemOffAnArchivesName() {
        assertEquals("a", Archives.stem("a.zip"))
        assertEquals("a", Archives.stem("a.tar.gz"))
        assertEquals("a", Archives.stem("a.tgz"))
        assertEquals("notes.txt", Archives.stem("notes.txt.gz"))
        assertEquals("a.part1", Archives.stem("a.part1.rar"))
        assertTrue(Archives.opens("x.7z") && Archives.opens("y.TAR.XZ") && !Archives.opens("z.docx"))
        assertTrue(Archives.raw("notes.txt.gz") && !Archives.raw("a.zip"))
    }

    @Test
    fun makesNamesSafeToUnpack() {
        assertEquals("/a/b.txt", ArchiveIndex.clean("a\\b.txt", zip = true))
        assertEquals("/a\\b.txt".replace('\\', '_'), ArchiveIndex.clean("a\\b.txt", zip = false))
        assertEquals("/a/b.txt", ArchiveIndex.clean("/./a//b.txt", zip = false))
        assertNull(ArchiveIndex.clean("../etc/passwd", zip = false))
        assertNull(ArchiveIndex.clean("a/../../b", zip = false))
        assertNull(ArchiveIndex.clean("__MACOSX/a/._b", zip = false))
        assertEquals("/12_30 _why_.log", ArchiveIndex.clean("12:30 <why>.log", zip = false))
    }

    @Test
    fun readsNamesOfWindowsInItsDosCodePage() {
        assertEquals("Отчёт.txt", ArchiveIndex.decode("Отчёт.txt".toByteArray()))
        assertEquals("Отчёт.txt", ArchiveIndex.decode("Отчёт.txt".toByteArray(charset("IBM866"))))
    }

    @Test
    fun listsWhatAnArchiveHoldsWithTheFoldersItDoesNotName() = runBlocking {
        val (files, path) = archive("a.zip", listOf(file("docs/deep/one.txt", "one"), folder("empty/"), file("two.txt", "22"), file("../evil", "x")))
        val root = Archives.root(path)

        assertEquals(listOf("docs", "empty", "two.txt"), files.list(root)!!.map { it.name }.sorted())
        assertEquals(listOf("deep"), files.list("$root/docs")!!.map { it.name })
        assertEquals("$root/docs/deep/one.txt", files.list("$root/docs/deep")!!.single().path)
        assertEquals(emptyList<String>(), files.list("$root/empty")!!.map { it.name })
        assertEquals(2L, files.stat("$root/two.txt")!!.size)
        assertNull(files.list("$root/two.txt"))
        assertFalse(files.writes(root))
        assertFalse(files.mkdir("$root/new"))
        assertEquals(1, opened)
    }

    @Test
    fun readsAFileFromWhereverItIs() = runBlocking {
        val (files, path) = archive("a.zip", listOf(file("one.txt", "one"), file("two.txt", "second")))
        val root = Archives.root(path)

        assertEquals("second", files.read("$root/two.txt").use { String(it.readBytes()) })
        assertEquals("ond", files.read("$root/two.txt", 3).use { String(it.readBytes()) })
    }

    @Test
    fun countsAStreamThatDoesNotSayHowLongItIs() = runBlocking {
        val (files, path) = archive("notes.txt.gz", listOf(Entry("data".toByteArray(), EntryKind.FILE, "hello".toByteArray(), sized = false)), raw = true)
        val only = files.list(Archives.root(path))!!.single()

        assertEquals("notes.txt", only.name)
        assertEquals(5L, only.size)
    }

    @Test
    fun aHardLinkReadsTheFileItStandsFor() = runBlocking {
        val (files, path) = archive("a.tar", listOf(file("a.txt", "same"), Entry("b.txt".toByteArray(), EntryKind.HARDLINK, link = "a.txt"), Entry("c".toByteArray(), EntryKind.SYMLINK)))
        val root = Archives.root(path)

        assertEquals(listOf("a.txt", "b.txt"), files.list(root)!!.map { it.name }.sorted())
        assertEquals("same", files.read("$root/b.txt").use { String(it.readBytes()) })
    }

    @Test
    fun aLockedFileIsRefused() = runBlocking {
        val (files, path) = archive("a.zip", listOf(file("secret.txt", "x", locked = true)))
        try {
            files.read("${Archives.root(path)}/secret.txt")
            fail()
        } catch (e: ArchiveException) {
            assertEquals(Reason.LOCKED, e.reason)
        }
    }

    @Test
    fun extractsChosenFilesInOnePass() = runBlocking {
        val (files, path) = archive("a.zip", listOf(file("x/one.txt", "1"), file("x/two.txt", "2"), file("three.txt", "3")))
        val root = Archives.root(path)
        val dest = temp.newFolder("dst")
        opened = 0
        val transfer = Transfer(files)

        transfer.copy(listOf("$root/x", "$root/three.txt"), dest.path, move = false, conflict = Conflict.SKIP)

        assertEquals(0, transfer.failed)
        assertEquals(listOf("three.txt", "x/one.txt", "x/two.txt"), tree(dest))
        assertEquals("2", File(dest, "x/two.txt").readText())
        // One pass to learn what it holds, one to copy.
        assertEquals(2, opened)
    }

    @Test
    fun unpacksIntoAFolderNamedAfterTheArchive() = runBlocking {
        val (files, path) = archive("a.zip", listOf(file("x/one.txt", "1"), file("two.txt", "2")))
        val transfer = Transfer(files)

        transfer.unpack(listOf(Unpacking(Archives.root(path), "a", Paths.child(temp.root.path, "a"))))

        assertEquals(0, transfer.failed)
        assertEquals(listOf("a.zip", "a/two.txt", "a/x/one.txt"), tree(temp.root))
    }

    @Test
    fun aLoneFolderOfTheArchivesNameIsNotDoubled() = runBlocking {
        val (files, path) = archive("a.zip", listOf(folder("a/"), file("a/one.txt", "1"), file("a/a", "inner")))
        val transfer = Transfer(files)

        transfer.unpack(listOf(Unpacking(Archives.root(path), "a", Paths.child(temp.root.path, "a"))))

        assertEquals(listOf("a.zip", "a/a", "a/one.txt"), tree(temp.root))
    }

    @Test
    fun aFileOnlyCompressedComesOutNextToIt() = runBlocking {
        val (files, path) = archive("notes.txt.gz", listOf(Entry("data".toByteArray(), EntryKind.FILE, "hello".toByteArray())), raw = true)
        val transfer = Transfer(files)

        transfer.unpack(listOf(Unpacking(Archives.root(path), "notes.txt", Paths.child(temp.root.path, "notes.txt"))))

        assertEquals(listOf("notes.txt", "notes.txt.gz"), tree(temp.root))
        assertEquals("hello", File(temp.root, "notes.txt").readText())
    }

    @Test
    fun anArchiveThatCannotBeReadLeavesNothing() = runBlocking {
        val path = File(temp.root, "bad.zip").apply { writeText("x") }.path
        lateinit var files: Stores
        val store by lazy { ArchiveStore(path, files) { _, _, _, _ -> throw ArchiveException(Reason.UNKNOWN) } }
        files = Stores(LocalStore()) { store }
        val transfer = Transfer(files)

        transfer.unpack(listOf(Unpacking(Archives.root(path), "bad", Paths.child(temp.root.path, "bad"))))

        assertEquals(1, transfer.failed)
        assertEquals(listOf("bad.zip"), temp.root.list()!!.toList())
        assertEquals(Reason.UNKNOWN, store.error?.reason)
    }

    /** A store of [entries] in the archive at [path], made the way [make] says. */
    private fun store(path: String, entries: List<Entry>, make: (FileStore, (String, Long, Boolean, ((Float) -> Unit)?) -> Walk) -> ArchiveStore): ArchiveStore {
        lateinit var store: ArchiveStore
        val files = Stores(LocalStore()) { store }
        store = make(files) { _, _, _, _ -> opened++; ListWalk(entries) }
        return store.also { File(path).takeIf { !it.exists() }?.writeText("archive") }
    }

    @Test
    fun keepsWhatItHoldsOnDiskForTheNextTime() = runBlocking {
        val path = File(temp.root, "a.tar.gz").path
        val cache = temp.newFolder("cache")
        val entries = listOf(file("x/one.txt", "1"), file("two.txt", "22"))
        store(path, entries) { files, open -> ArchiveStore(path, files, cache, saveAfter = 0, open = open) }.index()
        opened = 0

        val again = store(path, entries) { files, open -> ArchiveStore(path, files, cache, saveAfter = 0, open = open) }

        assertEquals(listOf("two.txt", "x"), again.list("/")!!.map { it.name }.sorted())
        assertEquals(2L, again.stat("/two.txt")!!.size)
        assertEquals(0, opened)
        // A changed archive is read again.
        File(path).appendText("more")
        assertEquals(2, again.list("/")!!.size)
        assertEquals(1, opened)
    }

    @Test
    fun refusesMoreEntriesThanItCanHold() = runBlocking {
        val path = File(temp.root, "many.zip").path
        val store = store(path, (1..5).map { file("f$it.txt", "x") }) { files, open -> ArchiveStore(path, files, limit = 3, open = open) }

        assertNull(store.list("/"))
        assertEquals(Reason.TOO_MANY, store.error?.reason)
    }

    @Test
    fun asksBeforeReadingABigStreamThrough() = runBlocking {
        val tar = File(temp.root, "big.tar.gz").path
        val zip = File(temp.root, "big.zip").path
        val streamed = store(tar, listOf(file("a.txt", "a"))) { files, open -> ArchiveStore(tar, files, slowBytes = 1, open = open) }
        val indexed = store(zip, listOf(file("a.txt", "a"))) { files, open -> ArchiveStore(zip, files, slowBytes = 1, open = open) }

        assertTrue(streamed.slow()!! >= 1)
        assertNull(indexed.slow())
        streamed.agree()
        assertNull(streamed.slow())
    }

    @Test
    fun findsItsWayInAListOfManyThousands() = runBlocking {
        val path = File(temp.root, "big.zip").path
        val entries = (0 until 20_000).map { file("d${it % 100}/s${it % 7}/f$it.txt", "$it") }
        val store = store(path, entries) { files, open -> ArchiveStore(path, files, open = open) }

        assertEquals(100, store.list("/")!!.size)
        assertEquals(7, store.list("/d42")!!.size)
        assertEquals("/d42/s6/f19942.txt", store.stat("/d42/s6/f19942.txt")!!.path)
        assertEquals(5L, store.stat("/d42/s6/f19942.txt")!!.size)
        assertNull(store.stat("/d42/s3/f19943.txt"))
    }
}
