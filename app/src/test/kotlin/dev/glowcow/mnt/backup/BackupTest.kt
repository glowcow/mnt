package dev.glowcow.mnt.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class BackupTest {
    private val settings = BackupSettings("DARK", "WARM", true, true, "DATE", true, listOf("/storage/emulated/0/Trip"), listOf("MUSIC"), listOf("report", "IMG"))

    private fun seal(data: ByteArray, password: String) = ByteArrayOutputStream().also { out ->
        BackupCrypto.encrypt(out, password.toCharArray()).use { it.write(data) }
    }.toByteArray()

    private fun open(sealed: ByteArray, password: String) = BackupCrypto.decrypt(ByteArrayInputStream(sealed), password.toCharArray()).readBytes()

    private fun archive(): ByteArray = ByteArrayOutputStream().also {
        BackupArchive.write(it, BackupManifest(createdAt = 99, app = "0.1.1", settings = settings))
    }.toByteArray()

    @Test
    fun archiveKeepsTheSettings() {
        val manifest = BackupArchive.read(ByteArrayInputStream(archive()))
        assertEquals(settings, manifest.settings)
        assertEquals("0.1.1", manifest.app)
        assertEquals(99, manifest.createdAt)
    }

    @Test
    fun whatElseAnArchiveHoldsIsPassedOver() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            add("../outside.txt", "1")
            add("files/secret", "1")
            add(BackupArchive.MANIFEST, """{"createdAt":1,"app":"x","settings":{"theme":"LIGHT","palette":"CLASSIC","later":"unknown"}}""")
        }
        val manifest = BackupArchive.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("LIGHT", manifest.settings.theme)
        assertEquals(emptyList<String>(), manifest.settings.pinned)
    }

    @Test
    fun anArchiveWithoutAManifestIsRefused() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("notes.txt"))
            zip.closeEntry()
        }
        assertThrows(IOException::class.java) { BackupArchive.read(ByteArrayInputStream(out.toByteArray())) }
        assertThrows(Exception::class.java) { BackupArchive.read(ByteArrayInputStream("not a zip".toByteArray())) }
    }

    @Test
    fun passwordRoundTrip() {
        val data = Random(1).nextBytes(200_000)
        val sealed = seal(data, "correct horse")
        assertTrue(BackupCrypto.isEncrypted(sealed))
        assertFalse(BackupCrypto.isEncrypted(archive()))
        assertArrayEquals(data, open(sealed, "correct horse"))
        assertArrayEquals(ByteArray(0), open(seal(ByteArray(0), "p"), "p"))
        assertEquals(settings, BackupArchive.read(ByteArrayInputStream(open(seal(archive(), "p"), "p"))).settings)
    }

    @Test
    fun aWrongPasswordIsToldApartFromDamage() {
        val sealed = seal(Random(2).nextBytes(200_000), "right")
        assertThrows(WrongPasswordException::class.java) { open(sealed, "wrong") }
        // A byte flipped in a later chunk is damage, not a wrong password.
        val damaged = sealed.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
        val error = assertThrows(IOException::class.java) { open(damaged, "right") }
        assertFalse(error is WrongPasswordException)
        // A file cut short does not pass for a whole one.
        assertThrows(IOException::class.java) { open(sealed.copyOf(sealed.size - 70_000), "right") }
    }
}
