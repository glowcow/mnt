package dev.glowcow.mnt.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MountsTest {
    private val table = """
        8612 49 8:49 / /mnt/media_rw/6C7025F07025C226 rw,nosuid,nodev,noexec,noatime shared:60 - fuseblk /dev/block/vold/public:8,49 rw,dirsync
        9010 49 8:50 / /mnt/media_rw/FA4D-00ED rw,nosuid,nodev,noexec,noatime shared:61 - vfat /dev/block/vold/public:8,50 rw,dirsync
        9050 49 8:65 / /mnt/media_rw/AAAA-0001 rw,nosuid,nodev,noexec,noatime shared:63 - vfat /dev/block/vold/public:8,65 rw,dirsync
        9100 49 179:9 / /mnt/media_rw/1234-ABCD rw,nosuid,nodev,noexec,noatime shared:62 - vfat /dev/block/vold/public:179,9 rw,dirsync
    """.trimIndent()

    @Test
    fun partitionsOfOneDriveShareItsDisk() {
        assertEquals("8:48", Mounts.disk(table, "/mnt/media_rw/6C7025F07025C226"))
        assertEquals("8:48", Mounts.disk(table, "/mnt/media_rw/FA4D-00ED"))
        assertEquals("a second drive is another disk", "8:64", Mounts.disk(table, "/mnt/media_rw/AAAA-0001"))
    }

    @Test
    fun aMemoryCardIsToldByItsDriver() {
        val disk = Mounts.disk(table, "/mnt/media_rw/1234-ABCD")
        assertEquals("179:8", disk)
        assertTrue(disk!!.startsWith("${Mounts.MMC}:"))
    }

    @Test
    fun namesAFileSystemAsPeopleDo() {
        assertEquals("FAT", Mounts.format(table, "/mnt/media_rw/FA4D-00ED"))
        assertNull("a driver's name tells nothing", Mounts.format(table, "/mnt/media_rw/6C7025F07025C226"))
        assertNull(Mounts.format(table, "/nowhere"))
    }

    @Test
    fun knowsNothingOfAPathThatIsNoMountPoint() {
        assertNull(Mounts.disk(table, "/mnt/media_rw"))
        assertNull(Mounts.disk("", "/storage/emulated/0"))
    }
}
