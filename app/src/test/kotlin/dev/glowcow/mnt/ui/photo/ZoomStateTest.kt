package dev.glowcow.mnt.ui.photo

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomStateTest {
    private fun state(w: Float, h: Float) = ZoomState().apply {
        viewport = IntSize(1080, 2400)
        width = w
        height = h
    }

    @Test fun doubleTapFillsThenShowsPixelsThenFits() {
        // A 4:3 photo on a tall phone: fit by the width, fill by the height, then one pixel to one.
        val s = state(4000f, 3000f)
        val fill = s.nextStep()
        assertEquals(2400f / 3000f / s.fit, fill, 1e-4f)
        s.zoom = fill
        assertEquals(1f / s.fit, s.nextStep(), 1e-4f)
        s.zoom = 1f / s.fit
        assertEquals(1f, s.nextStep(), 1e-6f)
    }

    @Test fun aSmallPictureSkipsPixelsBelowTheFit() {
        // 800×600 is already grown to fit, so one pixel to one would be smaller: only the fill is left.
        val s = state(800f, 600f)
        val fill = s.nextStep()
        assertEquals(4f / s.fit, fill, 1e-4f)
        s.zoom = fill
        assertEquals(1f, s.nextStep(), 1e-6f)
    }
}
