package com.speechpilot.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Delivers feedback events as subtle device vibrations.
 *
 * - [FeedbackEvent.SlowDown] and [FeedbackEvent.SpeedUp] produce a short, gentle vibration pulse.
 * - [FeedbackEvent.OnTarget] produces no vibration (positive reinforcement is silent).
 * - Devices that do not have a vibrator are handled gracefully (no-op).
 *
 * Uses [VibratorManager] on API 31+ and falls back to the deprecated [Vibrator] service
 * on API 26–30 (minSdk 26).
 *
 * @param context Application or Activity context used to obtain the vibrator service.
 */
class VibrationFeedbackDispatcher(context: Context) : FeedbackDispatcher {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override fun dispatch(event: FeedbackEvent) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        when (event) {
            FeedbackEvent.SlowDown, FeedbackEvent.SpeedUp -> vibrateAlert()
            FeedbackEvent.OnTarget -> { /* no vibration — on-target is a positive state */ }
        }
    }

    private fun vibrateAlert() {
        vibrator?.vibrate(
            VibrationEffect.createOneShot(ALERT_DURATION_MS, ALERT_AMPLITUDE)
        )
    }

    companion object {
        /** Duration of a single alert vibration pulse in milliseconds. Kept short and subtle. */
        const val ALERT_DURATION_MS = 80L

        /**
         * Amplitude of the vibration pulse in the range [1, 255].
         * Lower values are gentler. Uses ~31% of maximum intensity.
         */
        const val ALERT_AMPLITUDE = 80
    }
}
