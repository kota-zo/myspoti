package com.music.spotui.di

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import java.lang.ref.WeakReference

/** Light, system-native haptics for user actions (respects the system haptics setting). */
object Haptics {
    private var view: WeakReference<View>? = null

    fun attach(v: View) { view = WeakReference(v) }

    private fun perform(constant: Int, fallback: Int = HapticFeedbackConstants.CONTEXT_CLICK) {
        val v = view?.get() ?: return
        // Only while the app is in front (no buzz for notification / headset commands).
        if (v.windowVisibility != View.VISIBLE || !v.hasWindowFocus()) return
        v.post { if (!v.performHapticFeedback(constant)) v.performHapticFeedback(fallback) }
    }

    /** Buttons: play/pause, next/previous, add to queue. */
    fun click() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    /** Toggles: like, shuffle, repeat. */
    fun toggle(on: Boolean) = perform(
        if (Build.VERSION.SDK_INT >= 34) (if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
        else HapticFeedbackConstants.CLOCK_TICK
    )

    /**
     * One tick of a rising buzz, [level] 0..1 (the debug button while it is held): stronger
     * as the hold goes on, so the finger feels it is getting somewhere.
     */
    fun ramp(level: Float) {
        val vibrator = vibrator() ?: return
        val l = level.coerceIn(0.05f, 1f)
        val ms = (10 + 15 * l).toLong()
        // A plain pulse, not the TICK primitive: some phones (Samsung) play that one too
        // faintly to feel at the start of the hold.
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl() ->
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(ms, (60 + 195 * l).toInt().coerceIn(1, 255)))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                else -> @Suppress("DEPRECATION") vibrator.vibrate(ms)
            }
        }
    }

    /** A strong, unmistakable buzz: the debug button held to the end. */
    fun thud() {
        val vibrator = vibrator() ?: return
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(45L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                else -> @Suppress("DEPRECATION") vibrator.vibrate(45L)
            }
        }
    }

    private fun vibrator(): android.os.Vibrator? {
        val v = view?.get() ?: return null
        return if (Build.VERSION.SDK_INT >= 31)
            v.context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") v.context.getSystemService(android.os.Vibrator::class.java)
    }

    /** A completed action (queued, removed, transferred). */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.CONTEXT_CLICK
    )
}
