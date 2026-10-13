package dev.glowcow.mnt.files

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class StoresTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val local = LocalStore()

    /** A place whose root `/` is a folder of the disk, and where nothing is renamed unless [renames]. */
    private inner class Place(private val root: File, private val renames: Boolean = true) : FileStore {
        private fun real(path: String) = File(root, path.trimStart('/')).path
        private fun Node.inner() = copy(path = "/" + File(path).relativeTo(root).path)

        override suspend fun list(path: String) = local.list(real(path))?.map { it.inner() }
        override suspend fun stat(path: String) = local.stat(real(path))?.inner()
        override suspend fun facts(path: String) = local.facts(real(path))
        override suspend fun mkdir(path: String) = local.mkdir(real(path))
        override suspend fun rename(from: String, to: String) = renames && local.rename(real(from), real(to))
        override suspend fun remove(path: String) = local.remove(real(path))
        override suspend fun setModified(path: String, time: Long) = local.setModified(real(path), time)
        override fun renames(path: String) = renames
        override fun read(path: String, offset: Long): InputStream = local.read(real(path), offset)
        override fun write(path: String, size: Long): OutputStream = local.write(real(path), size)
    }

    private fun stores(renames: Boolean = true): Stores {
        val far = Place(temp.newFolder("far"), renames)
        return Stores(local) { id -> far.takeIf { id == "nas" } }
    }

    private fun here(path: String) = File(temp.root, path)

    @Test
    fun aPathTellsItsPlace() {
        assertNull(Remote.id("/storage/emulated/0/a"))
        assertEquals("nas", Remote.id("//nas/a/b"))
        assertEquals("/a/b", Remote.inner("//nas/a/b"))
        assertEquals("/", Remote.inner("//nas"))
        assertEquals("//nas", Remote.outer("nas", "/"))
        assertEquals("//nas/a", Paths.child(Remote.root("nas"), "a"))
        assertEquals("//nas", Paths.parent("//nas/a"))
    }

    @Test
    fun aPlaceIsListedUnderItsOwnPaths() = runBlocking {
        val stores = stores()
        here("far/docs").mkdirs()
        here("far/docs/a.txt").writeText("a")

        assertEquals(listOf("//nas/docs"), stores.list("//nas")!!.map { it.path })
        assertEquals("//nas/docs/a.txt", stores.stat("//nas/docs/a.txt")!!.path)
        assertTrue(stores.stat("//nas")!!.directory)
        assertNull(stores.list("//gone"))
    }

    @Test
    fun nothingIsRenamedFromOnePlaceIntoAnother() = runBlocking {
        val stores = stores()
        val file = here("a.txt").apply { writeText("a") }

        assertFalse(stores.rename(file.path, "//nas/a.txt"))
        assertTrue(file.exists())
    }

    @Test
    fun aMoveToAnotherPlaceCopiesAndRemoves() = runBlocking {
        val stores = stores()
        here("src/in").mkdirs()
        here("src/in/a.txt").writeText("abc")
        val transfer = Transfer(stores)

        transfer.copy(listOf(here("src").path), "//nas", move = true, conflict = Conflict.SKIP)

        assertEquals(0, transfer.failed)
        assertEquals("abc", here("far/src/in/a.txt").readText())
        assertFalse(here("src").exists())
    }

    @Test
    fun whereNothingIsRenamedAFileIsWrittenUnderItsOwnName() = runBlocking {
        val stores = stores(renames = false)
        here("a.txt").writeText("new")
        here("far/a.txt").writeText("old")
        val transfer = Transfer(stores)

        transfer.copy(listOf(here("a.txt").path), "//nas", move = false, conflict = Conflict.REPLACE)

        assertEquals(0, transfer.failed)
        assertEquals("new", here("far/a.txt").readText())
        assertEquals(listOf("a.txt"), here("far").list()!!.toList())
    }

    @Test
    fun aFileIsReadFromTheMiddle() {
        here("a.txt").writeText("0123456789")
        assertEquals("56789", local.read(here("a.txt").path, 5).use { it.readBytes().decodeToString() })
    }

    @Test
    fun aChangeOfCaseAloneRenames() = runBlocking {
        val file = here("Report.TXT").apply { writeText("r") }

        assertTrue(local.rename(file.path, here("report.txt").path))

        assertEquals(listOf("report.txt"), temp.root.list()!!.filter { it.startsWith("r", ignoreCase = true) })
    }
}
