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

    /** A held queue row taking another place: a faint tick. */
    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
        HapticFeedbackConstants.CLOCK_TICK,
    )

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
        // A plain pulse, not the TICK primitive: some phones (Samsung) play that one too
        // faintly to feel at the start of the hold.
        runCatching {
            if (vibrator.hasAmplitudeControl()) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot((10 + 15 * l).toLong(), (60 + 195 * l).toInt().coerceIn(1, 255)))
            } else {
                vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK))
            }
        }
    }

    /** A strong, unmistakable buzz: the debug button held to the end. */
    fun thud() {
        val vibrator = vibrator() ?: return
        runCatching { vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK)) }
    }

    private fun vibrator(): android.os.Vibrator? {
        val v = view?.get() ?: return null
        return if (Build.VERSION.SDK_INT >= 31)
            v.context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") v.context.getSystemService(android.os.Vibrator::class.java)
    }

    /** A completed action (queued, removed, transferred). */
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
}
