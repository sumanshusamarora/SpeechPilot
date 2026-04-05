package com.speechpilot.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Delivers feedback events as subtle device vibrations.
 *
 * - [FeedbackEvent.SlowDown] produces an uneven multi-pulse waveform to stand out as
 *   an over-target alert.
 * - [FeedbackEvent.SpeedUp] produces a short, gentle vibration pulse.
 * - [FeedbackEvent.OnTarget] produces no vibration (positive reinforcement is silent).
 * - Devices that do not have a vibrator are handled gracefully (no-op).
 *
 * Uses [VibratorManager] on API 31+ and falls back to the deprecated [Vibrator] service
 * on API 26–30 (minSdk 26).
 *
 * @param context Application or Activity context used to obtain the vibrator service.
 */
class VibrationFeedbackDispatcher(context: Context) : FeedbackDispatcher {

    internal sealed interface HapticPattern {
        data class OneShot(val durationMs: Long, val amplitude: Int) : HapticPattern
        data class Waveform(
            val timings: LongArray,
            val amplitudes: IntArray,
            val repeat: Int = -1,
        ) : HapticPattern
    }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override fun dispatch(event: FeedbackEvent) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        when (val pattern = patternFor(event)) {
            null -> { /* no vibration — on-target is a positive state */ }
            is HapticPattern.OneShot -> vibrator.vibrate(
                VibrationEffect.createOneShot(pattern.durationMs, pattern.amplitude)
            )
            is HapticPattern.Waveform -> vibrator.vibrate(
                VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, pattern.repeat)
            )
        }
    }

    companion object {
        /** Duration of a single alert vibration pulse in milliseconds. Kept short and subtle. */
        const val ALERT_DURATION_MS = 80L

        /**
         * Amplitude of the vibration pulse in the range [1, 255].
         * Lower values are gentler. Uses ~31% of maximum intensity.
         */
        const val ALERT_AMPLITUDE = 80

        /**
         * Deliberately uneven pulse train used only for over-target pace alerts.
         * Starts immediately, lands a stronger middle hit, then trails off.
         */
        internal val SLOW_DOWN_TIMINGS_MS = longArrayOf(0L, 35L, 45L, 115L, 35L, 70L, 55L, 140L)

        internal val SLOW_DOWN_AMPLITUDES = intArrayOf(0, 180, 0, 70, 0, 220, 0, 110)

        internal fun patternFor(event: FeedbackEvent): HapticPattern? = when (event) {
            FeedbackEvent.SlowDown -> HapticPattern.Waveform(
                timings = SLOW_DOWN_TIMINGS_MS,
                amplitudes = SLOW_DOWN_AMPLITUDES,
            )
            FeedbackEvent.SpeedUp -> HapticPattern.OneShot(
                durationMs = ALERT_DURATION_MS,
                amplitude = ALERT_AMPLITUDE,
            )
            FeedbackEvent.OnTarget -> null
        }
    }
}
