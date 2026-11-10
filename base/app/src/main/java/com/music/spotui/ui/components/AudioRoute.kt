package com.music.spotui.ui.components

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Where this phone's sound goes: its speaker, a Bluetooth device or wired headphones. */
data class AudioRoute(val kind: Kind, val name: String) {
    enum class Kind { SPEAKER, BLUETOOTH, WIRED }

    companion object {
        /**
         * The output media plays on now. Each name is read on its own: without
         * BLUETOOTH_CONNECT the product name throws, and the route must still be known
         * (the mini player then says "Bluetooth" instead of showing nothing).
         */
        fun current(context: Context): AudioRoute = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            fun nameOf(device: AudioDeviceInfo) = runCatching { device.productName?.toString().orEmpty() }.getOrDefault("")
            outs.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                        (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
            }
                ?.let { return@runCatching AudioRoute(Kind.BLUETOOTH, nameOf(it)) }
            outs.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && device.type == AudioDeviceInfo.TYPE_USB_HEADSET)
            }
                ?.let { return@runCatching AudioRoute(Kind.WIRED, nameOf(it)) }
            AudioRoute(Kind.SPEAKER, "")
        }.getOrDefault(AudioRoute(Kind.SPEAKER, ""))

        /**
         * The system's own output picker (speaker, headphones, Bluetooth), as the
         * official app opens it; Bluetooth settings where the picker does not exist.
         */
        fun pickOutput(context: Context) {
            val shown = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 34)
                    android.media.MediaRouter2.getInstance(context).showSystemOutputSwitcher()
                else {
                    context.sendBroadcast(android.content.Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                        .setPackage("com.android.systemui")
                        .putExtra("com.android.systemui.extra.package_name", context.packageName))
                    true
                }
            }.getOrDefault(false)
            if (!shown) openBluetooth(context)
        }

        fun openBluetooth(context: Context) {
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** [AudioRoute.current], kept up to date as headphones come and go. */
@Composable
fun rememberAudioRoute(): AudioRoute {
    val context = LocalContext.current
    var route by remember { mutableStateOf(AudioRoute.current(context)) }
    DisposableEffect(Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { route = AudioRoute.current(context) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { route = AudioRoute.current(context) }
        }
        am.registerAudioDeviceCallback(cb, null)
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
    return route
}
