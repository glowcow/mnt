package dev.glowcow.mnt

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import java.lang.ref.WeakReference

/** What the phone gives the hand when something the user waited for is done or has failed. */
class Haptics(private val context: Context) {
    // The window in front, while the app has one; without it the vibrator plays.
    private var shown = WeakReference<View>(null)

    fun show(view: View?) {
        shown = WeakReference(view)
    }

    fun done(ok: Boolean) {
        val view = shown.get()
        if (view != null && view.isAttachedToWindow) {
            view.performHapticFeedback(if (ok) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
            return
        }
        // Feedback to the user's own tap, even when it comes later: the touch feedback setting applies, silent mode does not.
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }?.vibrate(
            VibrationEffect.createPredefined(if (ok) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_DOUBLE_CLICK),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
        )
    }

    companion object {
        /** Something taking less than this was not waited for. */
        const val WAIT_MS = 2_000L
    }
}
