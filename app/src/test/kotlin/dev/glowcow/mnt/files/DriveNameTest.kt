package dev.glowcow.mnt.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveNameTest {

    @Test
    fun theMakerStandsInForAProductThatNamesNothing() {
        assertEquals("JetFlash", DriveName.of("JetFlash", "Mass Storage Device"))
        assertEquals("Kingston", DriveName.of("Kingston", "USB Flash Drive 3.0"))
    }

    @Test
    fun makerAndProductAreSaidOnce() {
        assertEquals("SanDisk Ultra", DriveName.of("SanDisk", " Ultra "))
        assertEquals("Samsung Portable SSD T7", DriveName.of("Samsung", "Samsung Portable SSD T7"))
        assertEquals("DataTraveler 3.0", DriveName.of("Generic", "DataTraveler 3.0"))
    }

    @Test
    fun aDriveThatSaysNothingHasNoName() {
        assertNull(DriveName.of("Generic", "Flash Disk"))
        assertNull(DriveName.of(null, "USB Storage"))
        assertNull(DriveName.of("", null))
    }
}
