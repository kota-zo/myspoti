package com.music.spotui.di

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Headphones unplugged or a Bluetooth headset gone: the music pauses instead of
 * moving to the phone's speaker, like every music app. Music playing on another
 * Connect device is left alone (this phone is only the remote).
 *
 * A TV's HDMI output is not headphones: its link goes away and comes back on its own
 * (see [AudioRouteRules]), and pausing there made the music stop mid-song.
 */
object NoisyAudio {
    /** The outputs the system last said went away, and when: read by the broadcast that follows. */
    @Volatile private var removedAt = 0L
    @Volatile private var removed: Set<AudioRouteRules.Output> = emptySet()

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val gone = removedDevices.orEmpty().map { kindOf(it.type) }.toSet()
            if (gone.isEmpty()) return
            removed = gone
            removedAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    /** A TV: said once at startup (see [register]), not read again on every broadcast. */
    @Volatile private var tv = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (SongPlayer.activeDevice.value != null || !SongPlayer.isPlaying()) return
            val went = gone()
            if (!AudioRouteRules.pauseWhenNoisy(went, linkedNow = stillLinked(context), tv = tv)) {
                app.myspoti.host.android.Logbook.log(
                    "noisy audio: ${went.joinToString("/").ifEmpty { "an output" }} went, playing on (TV)")
                return
            }
            SongPlayer.pause(by = "headphones unplugged")
        }
    }

    /**
     * The outputs the system said just went away, when it said so a moment ago: the callback's
     * report is the documented source for it, and the broadcast itself names nothing.
     */
    private fun gone(): Set<AudioRouteRules.Output> {
        val recent = removed
        if (recent.isNotEmpty() && android.os.SystemClock.elapsedRealtime() - removedAt < REMOVAL_MEMORY_MS) return recent
        return emptySet()
    }

    /** HDMI, optical, line out: outputs whose going away is a link change, not a speaker's. */
    private fun stillLinked(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { kindOf(it.type) == AudioRouteRules.Output.LINK }
    }.getOrDefault(false)

    private fun kindOf(type: Int): AudioRouteRules.Output = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        -> AudioRouteRules.Output.HEADSET
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        -> AudioRouteRules.Output.LINK
        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> AudioRouteRules.Output.HEADSET
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_HDMI_EARC -> AudioRouteRules.Output.LINK
            else -> AudioRouteRules.Output.OTHER
        }
    }

    /**
     * @param tv whether this device is a TV (see `Tv.isTv`): its HDMI links come and go, a
     * phone's headset does not.
     */
    fun register(context: Context, tv: Boolean) {
        this.tv = tv
        // A system broadcast: no export flag needed, but API 33+ asks for one anyway.
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(devices, null)
        }
    }

    /** A removal stays the broadcast's subject for this long (it is sent within a frame or two). */
    private const val REMOVAL_MEMORY_MS = 2_000L
}
