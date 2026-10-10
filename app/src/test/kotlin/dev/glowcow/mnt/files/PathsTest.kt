package dev.glowcow.mnt.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathsTest {

    @Test
    fun splitsAPathIntoFolderAndName() {
        assertEquals("b.txt", Paths.name("/a/b.txt"))
        assertEquals("a", Paths.name("/a/"))
        assertEquals("/a", Paths.parent("/a/b.txt"))
        assertEquals("/", Paths.parent("/a"))
        assertEquals("/", Paths.parent("/"))
        assertEquals("/a/b", Paths.child("/a", "b"))
        assertEquals("/b", Paths.child("/", "b"))
    }

    @Test
    fun aFolderHoldsItselfAndWhatIsBelowItOnly() {
        assertTrue(Paths.within("/a/b", "/a/b"))
        assertTrue(Paths.within("/a/b/c", "/a/b"))
        assertFalse(Paths.within("/a/bc", "/a/b"))
        assertFalse(Paths.within("/a", "/a/b"))
    }

    @Test
    fun anExtensionIsWhatFollowsTheLastDot() {
        assertEquals("gz", Paths.extension("backup.tar.gz"))
        assertEquals("jpg", Paths.extension("PHOTO.JPG"))
        assertEquals("", Paths.extension("Makefile"))
        assertEquals("", Paths.extension(".nomedia"))
    }

    @Test
    fun aNameMustBeSomethingAFileSystemTakes() {
        assertTrue(Paths.valid("notes.txt"))
        assertTrue(Paths.valid(".hidden"))
        assertFalse(Paths.valid(""))
        assertFalse(Paths.valid("  "))
        assertFalse(Paths.valid(".."))
        assertFalse(Paths.valid("a/b"))
    }

    @Test
    fun aTakenNameGetsANumberBeforeItsExtension() {
        val taken = setOf("a.txt", "a (2).txt", "folder", ".env")
        assertEquals("b.txt", Paths.free("b.txt") { it in taken })
        assertEquals("a (3).txt", Paths.free("a.txt") { it in taken })
        assertEquals("folder (2)", Paths.free("folder") { it in taken })
        assertEquals(".env (2)", Paths.free(".env") { it in taken })
    }
}
