package dev.glowcow.mnt.ui.photo

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import dev.glowcow.mnt.view.image.Gain
import kotlin.math.ln

/**
 * Draws a picture lifted by its gain map with the app's own arithmetic, the same on every phone, instead of the
 * platform's, which each maker's display pipeline treats its own way. The gain is applied in linear light, as Ultra
 * HDR and ISO 21496-1 define it, weighted by the headroom the screen has right now.
 */
internal class GainShader {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@GainShader.shader }

    /**
     * The paint for [base], placed by [baseMatrix], with [gain] laid over it by [gainMatrix]; [headroom] is how many
     * times SDR white the screen can show at the moment.
     */
    fun paint(base: Bitmap, baseMatrix: Matrix, gain: Gain, gainMatrix: Matrix, headroom: Float): Paint {
        shader.setInputShader("base", bitmapShader(base, baseMatrix))
        val channels = gain.channels.map { bitmapShader(it, gainMatrix) }
        shader.setInputShader("gainR", channels[0])
        shader.setInputShader("gainG", channels.getOrElse(1) { channels[0] })
        shader.setInputShader("gainB", channels.getOrElse(2) { channels[0] })
        shader.setFloatUniform("logMin", ln(gain.ratioMin[0]), ln(gain.ratioMin[1]), ln(gain.ratioMin[2]))
        shader.setFloatUniform("logMax", ln(gain.ratioMax[0]), ln(gain.ratioMax[1]), ln(gain.ratioMax[2]))
        shader.setFloatUniform("invGamma", 1f / gain.gamma[0], 1f / gain.gamma[1], 1f / gain.gamma[2])
        shader.setFloatUniform("epsSdr", gain.epsilonSdr[0], gain.epsilonSdr[1], gain.epsilonSdr[2])
        shader.setFloatUniform("epsHdr", gain.epsilonHdr[0], gain.epsilonHdr[1], gain.epsilonHdr[2])
        shader.setFloatUniform("weight", weight(gain, headroom))
        return paint
    }

    private fun bitmapShader(bitmap: Bitmap, matrix: Matrix) = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        filterMode = BitmapShader.FILTER_MODE_LINEAR
        setLocalMatrix(matrix)
    }

    companion object {
        /** How much of the gain shows at [headroom]: none below the map's start, all from where it is whole. */
        fun weight(gain: Gain, headroom: Float): Float {
            if (headroom <= 1f) return 0f
            val low = ln(gain.minHdr.coerceAtLeast(1f))
            val high = ln(gain.fullHdr.coerceAtLeast(1f))
            if (high <= low) return if (ln(headroom) >= high) 1f else 0f
            return ((ln(headroom) - low) / (high - low)).coerceIn(0f, 1f)
        }

        private const val SOURCE = """
            uniform shader base;
            uniform shader gainR;
            uniform shader gainG;
            uniform shader gainB;
            uniform float3 logMin;
            uniform float3 logMax;
            uniform float3 invGamma;
            uniform float3 epsSdr;
            uniform float3 epsHdr;
            uniform float weight;

            half4 main(float2 p) {
                half4 c = base.eval(p);
                if (c.a <= 0.0) return c;
                float3 sdr = toLinearSrgb(c.rgb / c.a);
                float3 g = float3(gainR.eval(p).a, gainG.eval(p).a, gainB.eval(p).a);
                float3 boost = exp(mix(logMin, logMax, pow(g, invGamma)) * weight);
                float3 hdr = (sdr + epsSdr) * boost - epsHdr;
                return half4(fromLinearSrgb(max(hdr, float3(0.0))) * c.a, c.a);
            }
        """
    }
}
