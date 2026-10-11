package dev.glowcow.mnt.files

import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException

class TransferTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store = LocalStore()

    private fun write(path: String, text: String): File = File(temp.root, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun dir(path: String): File = File(temp.root, path).apply { mkdirs() }

    private fun text(path: String) = File(temp.root, path).readText()

    /** Every file under the temporary root, as paths from it. */
    private fun tree() = temp.root.walkTopDown().filter { it.isFile }.map { it.relativeTo(temp.root).path }.sorted().toList()

    @Test
    fun copiesATreeAndKeepsTheTimes() = runBlocking {
        val source = write("src/a/one.txt", "one")
        write("src/a/deep/two.txt", "two")
        source.setLastModified(1_600_000_000_000)
        val dest = dir("dst")
        val transfer = Transfer(store)

        transfer.copy(listOf(File(temp.root, "src/a").path), dest.path, move = false, conflict = Conflict.SKIP)

        assertEquals(0, transfer.failed)
        assertEquals(listOf("dst/a/deep/two.txt", "dst/a/one.txt", "src/a/deep/two.txt", "src/a/one.txt"), tree())
        assertEquals("two", text("dst/a/deep/two.txt"))
        assertEquals(1_600_000_000_000, File(temp.root, "dst/a/one.txt").lastModified())
    }

    @Test
    fun reportsProgressUpToTheWhole() = runBlocking {
        write("src/one.txt", "12345")
        write("src/two.txt", "678")
        val seen = ArrayList<Pair<Long, Long>>()
        val transfer = Transfer(store) { _, done, total -> seen += done to total }

        transfer.copy(listOf(File(temp.root, "src").path), dir("dst").path, move = false, conflict = Conflict.SKIP)

        assertEquals(8L to 8L, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test
    fun aMoveLeavesNothingBehind() = runBlocking {
        write("src/a/one.txt", "one")
        write("src/b.txt", "b")
        val dest = dir("dst")

        Transfer(store).copy(listOf(File(temp.root, "src/a").path, File(temp.root, "src/b.txt").path), dest.path, move = true, conflict = Conflict.SKIP)

        assertEquals(listOf("dst/a/one.txt", "dst/b.txt"), tree())
        assertFalse(File(temp.root, "src/a").exists())
    }

    @Test
    fun aTakenNameIsSkippedReplacedOrKeptBeside() = runBlocking {
        fun stage() {
            temp.root.listFiles()!!.forEach { it.deleteRecursively() }
            write("src/a.txt", "new")
            write("dst/a.txt", "old")
        }
        val source = listOf(File(temp.root, "src/a.txt").path)
        val dest = File(temp.root, "dst").path

        stage()
        Transfer(store).copy(source, dest, move = false, conflict = Conflict.SKIP)
        assertEquals("old", text("dst/a.txt"))

        stage()
        Transfer(store).copy(source, dest, move = false, conflict = Conflict.REPLACE)
        assertEquals("new", text("dst/a.txt"))
        assertEquals(listOf("dst/a.txt", "src/a.txt"), tree())

        stage()
        Transfer(store).copy(source, dest, move = false, conflict = Conflict.KEEP_BOTH)
        assertEquals("old", text("dst/a.txt"))
        assertEquals("new", text("dst/a (2).txt"))

        stage()
        Transfer(store).copy(source, dest, move = true, conflict = Conflict.SKIP)
        assertEquals("the skipped one stays where it was", "new", text("src/a.txt"))
    }

    @Test
    fun replacingAFolderMergesIntoIt() = runBlocking {
        write("src/a/one.txt", "new one")
        write("src/a/three.txt", "three")
        write("dst/a/one.txt", "old one")
        write("dst/a/two.txt", "two")

        Transfer(store).copy(listOf(File(temp.root, "src/a").path), File(temp.root, "dst").path, move = true, conflict = Conflict.REPLACE)

        assertEquals(listOf("dst/a/one.txt", "dst/a/three.txt", "dst/a/two.txt"), tree())
        assertEquals("new one", text("dst/a/one.txt"))
    }

    @Test
    fun aCopyIntoTheSameFolderMakesASecondOne() = runBlocking {
        write("src/a.txt", "a")
        val folder = File(temp.root, "src").path

        Transfer(store).copy(listOf(File(temp.root, "src/a.txt").path), folder, move = false, conflict = Conflict.REPLACE)
        assertEquals(listOf("src/a (2).txt", "src/a.txt"), tree())

        Transfer(store).copy(listOf(File(temp.root, "src/a.txt").path), folder, move = true, conflict = Conflict.REPLACE)
        assertEquals("a move onto itself changes nothing", listOf("src/a (2).txt", "src/a.txt"), tree())
    }

    @Test
    fun aFolderDoesNotGoIntoItself() = runBlocking {
        write("src/a/one.txt", "one")
        val folder = File(temp.root, "src/a").path
        val transfer = Transfer(store)

        transfer.copy(listOf(folder), File(temp.root, "src/a/deep").apply { mkdirs() }.path, move = false, conflict = Conflict.SKIP)

        assertEquals(1, transfer.failed)
        assertEquals(listOf("src/a/one.txt"), tree())
    }

    @Test
    fun deletesTreesAndCountsWhatItDid() = runBlocking {
        write("src/a/one.txt", "one")
        write("src/a/deep/two.txt", "two")
        write("src/keep.txt", "keep")
        var last = 0L to 0L
        val transfer = Transfer(store) { _, done, total -> last = done to total }

        transfer.delete(listOf(File(temp.root, "src/a").path))

        assertEquals(0, transfer.failed)
        assertEquals(listOf("src/keep.txt"), tree())
        assertEquals(4L to 4L, last)
    }

    @Test
    fun aStoppedCopyLeavesNoHalfWrittenFile() {
        write("src/big.bin", "x".repeat(1024 * 1024))
        val dest = dir("dst")
        var stopped = false
        try {
            runBlocking {
                Transfer(store) { _, _, _ -> cancel() }.copy(listOf(File(temp.root, "src/big.bin").path), dest.path, move = false, conflict = Conflict.SKIP)
            }
        } catch (_: CancellationException) {
            stopped = true
        }
        assertTrue(stopped)
        assertEquals(listOf("src/big.bin"), tree())
        assertEquals(emptyList<String>(), dest.list()!!.toList())
    }

    @Test
    fun findsNamesAnywhereBelowTheRoots() = runBlocking {
        write("root/Report.pdf", "")
        write("root/deep/annual report.txt", "")
        write("root/.cache/report.tmp", "")
        write("root/other.txt", "")
        val found = ArrayList<String>()

        Search(store).find(listOf(File(temp.root, "root").path), "report", hidden = false).collect { found += it.name }
        assertEquals(setOf("Report.pdf", "annual report.txt"), found.toSet())

        found.clear()
        Search(store).find(listOf(File(temp.root, "root").path), "report", hidden = true).collect { found += it.name }
        assertEquals(3, found.size)
    }

    /** Names in the archive with the text of each file, a folder's entry with none. */
    private fun unzip(path: String): Map<String, String> = ZipFile(File(temp.root, path)).use { zip ->
        zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes().decodeToString() }
    }

    @Test
    fun packsFilesAndFoldersIntoAnArchive() = runBlocking {
        val one = write("src/a/one.txt", "one")
        write("src/a/deep/two.txt", "two")
        dir("src/a/empty")
        write("src/solo.txt", "solo")
        one.setLastModified(1_600_000_000_000)
        dir("dst")
        val seen = ArrayList<Pair<Long, Long>>()
        val transfer = Transfer(store) { _, done, total -> seen += done to total }

        transfer.zip(listOf(File(temp.root, "src/a").path, File(temp.root, "src/solo.txt").path), File(temp.root, "dst/out.zip").path)

        assertEquals(0, transfer.failed)
        assertEquals(mapOf("a/" to "", "a/one.txt" to "one", "a/deep/" to "", "a/deep/two.txt" to "two", "a/empty/" to "", "solo.txt" to "solo"), unzip("dst/out.zip"))
        assertEquals(listOf("out.zip"), File(temp.root, "dst").list()!!.toList())
        assertEquals(10L to 10L, seen.last())
        // A zip keeps times to two seconds.
        ZipFile(File(temp.root, "dst/out.zip")).use { assertEquals(1_600_000_000_000, it.getEntry("a/one.txt").time) }
    }

    @Test
    fun leavesNoArchiveBehindWhenStopped() {
        write("src/big.bin", "x".repeat(1_000_000))
        val dest = dir("dst")
        var stopped = false
        try {
            runBlocking {
                Transfer(store) { _, _, _ -> cancel() }.zip(listOf(File(temp.root, "src/big.bin").path), File(dest, "out.zip").path)
            }
        } catch (_: CancellationException) {
            stopped = true
        }
        assertTrue(stopped)
        assertEquals(emptyList<String>(), dest.list()!!.toList())
    }

    @Test
    fun tellsPermissionsOwnerAndTimes() = runBlocking {
        val file = write("facts.txt", "1")
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r-----"))

        val facts = store.facts(file.path)!!

        assertEquals("640", facts.mode!!.toString(8))
        assertEquals(Files.getOwner(file.toPath()).name, facts.owner)
        assertTrue(facts.accessed!! > 0)
        assertEquals(5L, LocalStore { 5L }.facts(file.path)!!.created)
        assertEquals(null, store.facts(File(temp.root, "none").path))
    }
}
