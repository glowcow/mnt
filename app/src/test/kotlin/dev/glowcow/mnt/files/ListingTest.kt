package dev.glowcow.mnt.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingTest {
    private fun file(name: String, size: Long = 0, modified: Long = 0) = Node("/$name", name, false, size, modified)
    private fun folder(name: String, modified: Long = 0) = Node("/$name", name, true, 0, modified)
    private fun names(nodes: List<Node>) = nodes.map { it.name }

    @Test
    fun foldersComeFirstInAnyOrder() {
        val nodes = listOf(file("a.txt", size = 9), folder("z"), file("b.txt", size = 1), folder("m"))
        assertEquals(listOf("m", "z", "a.txt", "b.txt"), names(Listing.arrange(nodes, SortBy.NAME, descending = false, hidden = true)))
        assertEquals(listOf("z", "m", "b.txt", "a.txt"), names(Listing.arrange(nodes, SortBy.NAME, descending = true, hidden = true)))
        assertEquals(listOf("m", "z", "a.txt", "b.txt"), names(Listing.arrange(nodes, SortBy.SIZE, descending = true, hidden = true)))
    }

    @Test
    fun namesAreReadAsAPersonReadsThem() {
        val nodes = listOf(file("file10.txt"), file("File2.txt"), file("file1.txt"), file("apple"), file("Banana"))
        assertEquals(
            listOf("apple", "Banana", "file1.txt", "File2.txt", "file10.txt"),
            names(Listing.arrange(nodes, SortBy.NAME, descending = false, hidden = true)),
        )
        assertTrue(Listing.natural("a007", "a7b") < 0)
        assertTrue(Listing.natural("a", "ab") < 0)
        assertEquals(0, Listing.natural("same", "same"))
    }

    @Test
    fun sortsByDateSizeAndType() {
        val nodes = listOf(file("b.png", size = 5, modified = 30), file("a.txt", size = 7, modified = 10), file("c.png", size = 1, modified = 20))
        assertEquals(listOf("a.txt", "c.png", "b.png"), names(Listing.arrange(nodes, SortBy.DATE, descending = false, hidden = true)))
        assertEquals(listOf("a.txt", "b.png", "c.png"), names(Listing.arrange(nodes, SortBy.SIZE, descending = true, hidden = true)))
        assertEquals(listOf("b.png", "c.png", "a.txt"), names(Listing.arrange(nodes, SortBy.TYPE, descending = false, hidden = true)))
    }

    @Test
    fun hiddenNamesAreLeftOutUnlessAskedFor() {
        val nodes = listOf(file(".nomedia"), folder(".thumbnails"), file("a.txt"))
        assertEquals(listOf("a.txt"), names(Listing.arrange(nodes, SortBy.NAME, descending = false, hidden = false)))
        assertEquals(3, Listing.arrange(nodes, SortBy.NAME, descending = false, hidden = true).size)
    }

    @Test
    fun aKindIsToldByTheEndOfTheName() {
        assertEquals(Kind.IMAGE, file("IMG_0001.HEIC").kind)
        assertEquals(Kind.ARCHIVE, file("backup.tar.gz").kind)
        assertEquals(Kind.PACKAGE, file("mnt-0.1.0-arm64-v8a-release.apk").kind)
        assertEquals(Kind.TEXT, file("Makefile").kind)
        assertEquals(Kind.OTHER, file("firmware.bin").kind)
        assertEquals(Kind.FOLDER, folder("photos.jpg").kind)
    }
}
