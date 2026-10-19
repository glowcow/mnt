package dev.glowcow.mnt.audio

import dev.glowcow.mnt.files.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundsTest {
    @Test fun playsAndLists() {
        assertTrue(Sounds.plays("01 Intro.FLAC"))
        assertTrue(Sounds.plays("talk.m4b"))
        assertFalse(Sounds.plays("song.wma"))
        assertTrue(Sounds.lists("mix.m3u8"))
        assertFalse(Sounds.lists("mix.txt"))
    }

    @Test fun m3uRelativeAndAbsolute() {
        val text = "#EXTM3U\n#EXTINF:45,Someone - Song\nAlbum/01 Song.mp3\r\n\n../Other/02.flac\n/storage/emulated/0/Music/03.ogg\nnotes.txt\nhttp://radio.example/stream\n"
        assertEquals(
            listOf("/storage/emulated/0/Music/Lists/Album/01 Song.mp3", "/storage/emulated/0/Music/Other/02.flac", "/storage/emulated/0/Music/03.ogg", "http://radio.example/stream"),
            Sounds.parse("mix.m3u", text.toByteArray(), "/storage/emulated/0/Music/Lists"),
        )
    }

    @Test fun windowsPathsOnAServer() {
        val text = "Album\\01 Song.mp3\nC:\\Music\\02.mp3\n..\\..\\..\\escape.mp3\n"
        assertEquals(listOf("//nas/music/Album/01 Song.mp3"), Sounds.parse("a.m3u", text.toByteArray(), "//nas/music"))
    }

    @Test fun plsInItsOwnOrder() {
        val text = "[playlist]\nFile2=b.mp3\nTitle2=B\nFile1=a.mp3\nNumberOfEntries=2\n"
        assertEquals(listOf("/m/a.mp3", "/m/b.mp3"), Sounds.parse("x.pls", text.toByteArray(), "/m"))
    }

    @Test fun cyrillicWindowsCodePage() {
        val bytes = "Песня.mp3\n".toByteArray(charset("windows-1251"))
        assertEquals(listOf("/m/Песня.mp3"), Sounds.parse("x.m3u", bytes, "/m"))
    }

    @Test fun folderCover() {
        fun node(name: String) = Node("/m/$name", name, false, 1, 0)
        assertEquals("/m/Folder.JPG", Sounds.cover(listOf(node("01.mp3"), node("back.jpg"), node("Folder.JPG"))))
        assertEquals("/m/cover.png", Sounds.cover(listOf(node("folder.jpg"), node("cover.png"))))
        assertNull(Sounds.cover(listOf(node("photo.jpg"))))
    }

    @Test fun stems() {
        assertEquals("01 Song", Sounds.stem("/m/01 Song.mp3"))
        assertEquals(".mp3", Sounds.stem("/m/.mp3"))
    }
}
