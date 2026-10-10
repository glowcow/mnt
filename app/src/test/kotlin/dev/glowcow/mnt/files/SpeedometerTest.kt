package dev.glowcow.mnt.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedometerTest {

    @Test
    fun saysNothingBeforeThereIsEnoughToTellBy() {
        val meter = Speedometer()
        assertNull(meter.at(0, 0))
        assertNull(meter.at(200, 2_000_000))
        assertEquals(10_000_000L, meter.at(500, 5_000_000))
    }

    @Test
    fun followsTheLastSecondsNotTheWholeRun() {
        val meter = Speedometer(windowMs = 3_000)
        // Ten seconds at 1 MB/s, then the drive speeds up to 5 MB/s.
        for (s in 0..10) meter.at(s * 1_000L, s * 1_000_000L)
        var rate = 0L
        for (s in 11..14) rate = meter.at(s * 1_000L, 10_000_000L + (s - 10) * 5_000_000L)!!
        assertEquals(5_000_000L, rate)
    }

    @Test
    fun aStallReadsAsNoSpeed() {
        val meter = Speedometer(windowMs = 3_000)
        for (s in 0..3) meter.at(s * 1_000L, s * 1_000_000L)
        var rate = 1L
        for (s in 4..8) rate = meter.at(s * 1_000L, 3_000_000L)!!
        assertEquals(0L, rate)
    }
}
