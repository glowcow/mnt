package dev.glowcow.mnt.video

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import dev.glowcow.mnt.data.Decoding

/**
 * The video player's renderers: the phone's decoders and FFmpeg's, in the order [decoding] asks. FFmpeg plays what
 * the phone's cannot — 10-bit H.264, DTS, TrueHD — whichever comes first.
 */
@OptIn(UnstableApi::class)
class VideoRenderers(context: Context, decoding: Decoding) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(if (decoding == Decoding.FFMPEG) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
    }
}
