package com.music.spotui.ui.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.myspoti.host.android.Logbook

/**
 * What a TV can carry, measured once at start: its memory, its cores and whether it runs 64-bit
 * apps. Many TVs run 32-bit apps with 1.5 to 2 GB on four slow cores, and the TV screens show
 * neither clips nor Canvas nor AutoMix's settings: those stay off there, and the energy saver goes on
 * when the TV is short of means. Nothing changes on a phone.
 */
object TvCaps {
    data class Caps(val ramMb: Long, val lowRam: Boolean, val abi64: Boolean, val cores: Int, val sdk: Int) {
        /** Too little for the extras: under 2.5 GB, a "low RAM" device, 32-bit only, or under 4 cores. */
        val weak: Boolean get() = lowRam || ramMb < 2_500 || !abi64 || cores < 4
    }

    fun probe(context: Context): Caps {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return Caps(mem.totalMem / (1024 * 1024), am.isLowRamDevice, Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
            Runtime.getRuntime().availableProcessors(), Build.VERSION.SDK_INT)
    }

    /** On a TV: what has no screen there is turned off, and the energy saver on for a weak one. */
    fun apply(context: Context) {
        if (!Tv.isTv(context)) return
        val c = runCatching { probe(context) }.getOrNull() ?: return
        // Analysing every song (Beat This on ONNX) for a mix whose settings the TV does not show.
        if (com.music.spotui.automix.AutoMix.on) com.music.spotui.automix.AutoMix.set(false)
        // Looked up for each song, shown by no TV screen.
        if (com.music.spotui.data.preferences.MusicVideoPref.on) com.music.spotui.data.preferences.MusicVideoPref.set(context, false)
        if (app.myspoti.power.EnergySettings.state.value.canvasEnabled) app.myspoti.power.EnergySettings.setCanvasEnabled(false)
        // Notifications are not read on a TV.
        if (com.music.spotui.data.NewReleaseNotifier.on) com.music.spotui.data.NewReleaseNotifier.setOn(context, false)
        if (c.weak && !app.myspoti.power.EnergySettings.state.value.manualSaver) app.myspoti.power.EnergySettings.setSaver(true)
        Logbook.log("tv: ${c.ramMb} MB${if (c.lowRam) " (low RAM)" else ""}, ${c.cores} cores, ${if (c.abi64) "64" else "32"}-bit, Android ${c.sdk}: " +
            "AutoMix, clips, Canvas and release notices off${if (c.weak) ", energy saver on (weak)" else ""}")
    }
}
