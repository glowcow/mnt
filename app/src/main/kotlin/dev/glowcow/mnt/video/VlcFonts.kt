package dev.glowcow.mnt.video

import android.content.Context
import android.os.Build
import org.videolan.libvlc.LibVLC
import java.io.File

/**
 * The cache of the phone's fonts that VLC finds the faces of subtitles in. Built from the phone's own fonts, it cannot
 * come with the app; built on the first video, that video waited some 20 seconds for its picture.
 */
object VlcFonts {
    /** Builds the cache unless it is there for this system; slow, so off the main thread. */
    fun warm(context: Context) {
        val dir = File(context.getDir("vlc", Context.MODE_PRIVATE), ".cache/fontconfig")
        // An update of the system brings other fonts: the old cache is stale, and the next video would scan them all.
        val stamp = File(dir, "built-for")
        if (dir.listFiles()?.any { ".cache-" in it.name } == true && runCatching { stamp.readText() }.getOrNull() == Build.FINGERPRINT) return
        val vlc = LibVLC(context, arrayListOf("--no-audio"))
        try {
            vlc.buildFontCache()
        } finally {
            vlc.release()
        }
        dir.mkdirs()
        stamp.writeText(Build.FINGERPRINT)
    }
}
