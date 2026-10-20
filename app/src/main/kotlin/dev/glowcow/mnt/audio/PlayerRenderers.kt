package dev.glowcow.mnt.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * The player's renderers, with FFmpeg's decoders behind the phone's own. ALAC goes to the phone only when it has
 * Google's decoder: Qualcomm's refuse the first frame, and every ALAC track was skipped.
 */
@OptIn(UnstableApi::class)
class PlayerRenderers(context: Context) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setMediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { info ->
                mime != MimeTypes.AUDIO_ALAC || GOOGLE.any { info.name.startsWith(it) }
            }
        }
    }

    private companion object {
        val GOOGLE = listOf("c2.android.", "OMX.google.")
    }
}
