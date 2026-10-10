package dev.glowcow.mnt.files

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrashTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store = LocalStore()
    private var clock = 1_000_000L
    private val trash by lazy { Trash(store, { listOf(temp.root.path) }, { clock }) }

    private fun write(path: String, text: String): File = File(temp.root, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun path(name: String) = File(temp.root, name).path

    @Test
    fun keepsWhatIsPutInAndWhereItCameFrom() = runBlocking {
        write("docs/a/one.txt", "one")
        write("docs/a/deep/two.txt", "three")

        assertTrue(trash.put(path("docs/a"), 8))

        assertFalse(File(temp.root, "docs/a").exists())
        val item = trash.entries().single()
        assertEquals("a", item.node.name)
        assertTrue(item.node.directory)
        assertEquals(path("docs"), item.from)
        assertEquals(8L, item.bytes)
        assertEquals(1_000_000L, item.deleted)
    }

    @Test
    fun putsBackUnderASpareNameWhenItsOwnIsTaken() = runBlocking {
        write("docs/one.txt", "old")
        trash.put(path("docs/one.txt"), 3)
        write("docs/one.txt", "new")

        assertTrue(trash.restore(trash.entries().single()))

        assertEquals("new", File(temp.root, "docs/one.txt").readText())
        assertEquals("old", File(temp.root, "docs/one (2).txt").readText())
        assertEquals(emptyList<Trashed>(), trash.entries())
        assertEquals(emptyList<String>(), File(temp.root, Trash.FOLDER).list()!!.toList())
    }

    @Test
    fun makesTheFolderAgainThatWentMeanwhile() = runBlocking {
        write("docs/a/one.txt", "one")
        trash.put(path("docs/a/one.txt"), 3)
        File(temp.root, "docs").deleteRecursively()

        assertTrue(trash.restore(trash.entries().single()))

        assertEquals("one", File(temp.root, "docs/a/one.txt").readText())
    }

    @Test
    fun keepsTwoOfOneNameApartTheLatestFirst() = runBlocking {
        write("one.txt", "first")
        trash.put(path("one.txt"), 5)
        write("one.txt", "second")
        trash.put(path("one.txt"), 6)
        clock += 5
        write("two.txt", "third")
        trash.put(path("two.txt"), 5)

        assertEquals(listOf("two.txt", "one.txt", "one.txt"), trash.entries().map { it.node.name })
        assertEquals(setOf(5L, 6L), trash.entries().filter { it.node.name == "one.txt" }.mapTo(HashSet()) { it.bytes })
    }

    @Test
    fun removesOnlyWhatHasBeenThereForTheWholeTerm() = runBlocking {
        write("old.txt", "old")
        trash.put(path("old.txt"), 3)
        clock += 29 * DAY
        write("fresh/new.txt", "new")
        trash.put(path("fresh"), 3)

        assertFalse(trash.purge())
        clock += DAY
        assertTrue(trash.purge())

        assertEquals(listOf("fresh"), trash.entries().map { it.node.name })
        assertEquals(1, File(temp.root, Trash.FOLDER).listFiles()!!.count { it.isDirectory })
        assertEquals(1, File(temp.root, Trash.FOLDER).listFiles()!!.count { it.isFile })
    }

    @Test
    fun clearsAwayWhatWasEmptiedByHand() = runBlocking {
        clock = System.currentTimeMillis()
        write("one.txt", "one")
        trash.put(path("one.txt"), 3)
        write("two.txt", "two")
        trash.put(path("two.txt"), 3)
        File(trash.entries().first { it.node.name == "one.txt" }.node.path).delete()
        clock = System.currentTimeMillis() + 2 * 60_000

        trash.purge()

        assertEquals(listOf("two.txt"), trash.entries().map { it.node.name })
        assertEquals(2, File(temp.root, Trash.FOLDER).list()!!.size)
    }

    @Test
    fun refusesAVolumeItselfAndWhatLiesOutsideEveryVolume() = runBlocking {
        assertFalse(trash.put(temp.root.path, 0))
        assertFalse(trash.put("/nowhere/at/all.txt", 0))
    }

    @Test
    fun countsTheLastDayFromItsStart() {
        assertEquals(30, Trash.daysLeft(0, 0))
        assertEquals(30, Trash.daysLeft(0, DAY - 1))
        assertEquals(29, Trash.daysLeft(0, DAY))
        assertEquals(1, Trash.daysLeft(0, 30 * DAY - 1))
        assertEquals(0, Trash.daysLeft(0, 30 * DAY))
    }

    @Test
    fun aDiscardReportsWhatItCouldNotPutIn() = runBlocking {
        write("docs/one.txt", "one")
        val transfer = Transfer(store)

        transfer.discard(listOf(path("docs/one.txt"), path("docs/none.txt")), trash)

        assertEquals(1, transfer.failed)
        assertEquals(3L, trash.entries().single().bytes)
    }

    @Test
    fun theBinIsNeverListedOrFound() = runBlocking {
        write("one.txt", "one")
        trash.put(path("one.txt"), 3)
        write("two.txt", "two")

        val listed = Listing.arrange(store.list(temp.root.path).orEmpty(), SortBy.NAME, descending = false, hidden = true)
        assertEquals(listOf("two.txt"), listed.map { it.name })
        val found = ArrayList<String>()
        Search(store).find(listOf(temp.root.path), "txt", hidden = true).collect { found += it.name }
        assertEquals(listOf("two.txt"), found)
    }

    private companion object {
        const val DAY = 24 * 3_600_000L
    }
}
