package dev.glowcow.mnt.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideosTest {
    @Test fun episodeAfterTheMarkerNamesIt() {
        assertEquals(Videos.Named("Dio the Destroyer", 1, 1), Videos.named("JoJo's Bizarre Adventure - S01E01 - Dio the Destroyer.mkv"))
        assertEquals(Videos.Named("Im Leslie Knope", 4, 1), Videos.named("Parks.And.Recreation.S04E01.Im.Leslie.Knope.1080p.AMZN.WEB-DL.Rus.Eng_TeamHD.mkv"))
    }

    @Test fun showWhenNothingNamesTheEpisode() {
        assertEquals(Videos.Named("Dark Matter", 2, 1), Videos.named("Dark.Matter.S02E01.1080p.WEB-DL.RGzsRutracker.mkv"))
    }

    @Test fun releaseTagsAndYearAreCut() {
        assertEquals("Blade Runner 2049", Videos.named("Blade.Runner.2049.2017.2160p.UHD.BDRip.HDR.DoVi.x265.DD+7.1.by.DVT.mkv").title)
        assertEquals("Dossier 137", Videos.named("Dossier.137.2025.REMUX.mkv").title)
        assertEquals("No Other Choice", Videos.named("No.Other.Choice.2025.1080p.WEB-DL.H.264-EniaHD.mkv").title)
        assertEquals("El Camino A Breaking Bad Movie", Videos.named("El Camino A Breaking Bad Movie (2019).mkv").title)
        assertEquals("Charlotte's Web", Videos.named("Charlotte's.Web.2006.mkv").title)
    }

    @Test fun qualityByTheSize() {
        assertEquals("4K", Videos.quality(3840, 1600))
        assertEquals("HD", Videos.quality(1920, 800))
        assertEquals("SD", Videos.quality(720, 404))
    }
}
