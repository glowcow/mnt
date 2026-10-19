package dev.glowcow.mnt.audio.upnp

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderersTest {
    @Test fun longNamesCleaned() {
        assertEquals("era - Sonos Era 300", Renderers.clean("era - Sonos Era 300 Media Renderer - RINCON_74CA60B1912801400"))
        assertEquals("Sonos Era 300", Renderers.clean("10.1.1.6 - Sonos Era 300"))
        assertEquals("Kitchen", Renderers.clean("Kitchen"))
    }

    @Test fun times() {
        assertEquals("0:03:20", Soap.time(200_000))
        assertEquals(200_500L, Soap.millis("0:03:20.500"))
        assertEquals(null, Soap.millis("NOT_IMPLEMENTED"))
    }
}
