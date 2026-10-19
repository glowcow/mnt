package dev.glowcow.mnt.audio.upnp

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderersTest {
    @Test fun longNamesCleaned() {
        assertEquals("era - Sonos Era 300", Renderers.clean("era - Sonos Era 300 Media Renderer - RINCON_74CA60B1912801400"))
        assertEquals("Sonos Era 300", Renderers.clean("10.1.1.6 - Sonos Era 300"))
        assertEquals("Kitchen", Renderers.clean("Kitchen"))
    }

    @Test fun makersShortened() {
        assertEquals("Sonos", Renderers.maker("Sonos, Inc."))
        assertEquals("Denon", Renderers.maker("Denon Inc"))
        assertEquals("Yamaha Corporation x", Renderers.maker("Yamaha Corporation x"))
        assertEquals(null, Renderers.maker(" , Inc."))
    }

    @Test fun titles() {
        val era = Renderer("u", "era", "h", "t", "tt", null, null, model = "Era 300", maker = "Sonos")
        assertEquals("era • Era 300", era.title)
        assertEquals("Kitchen", era.copy(name = "Kitchen", model = "kitchen").title)
        assertEquals("Kitchen", era.copy(name = "Kitchen", model = null).title)
    }

    @Test fun times() {
        assertEquals("0:03:20", Soap.time(200_000))
        assertEquals(200_500L, Soap.millis("0:03:20.500"))
        assertEquals(null, Soap.millis("NOT_IMPLEMENTED"))
    }
}
