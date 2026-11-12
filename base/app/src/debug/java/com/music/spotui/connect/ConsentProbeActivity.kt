package com.music.spotui.connect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Debug builds only: the device picker's consent page on its own, to see it on any Android
 * version without a Sonos or a signed-in account.
 * adb shell am start -n <package>/com.music.spotui.connect.ConsentProbeActivity --es url <https address>
 */
class ConsentProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: "https://accounts.spotify.com/login"
        setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF2B4A6F))) { DesktopConsentHost() }
            LaunchedEffect(url) {
                val ended = DesktopConsent.ask(url, "Probe")
                ConnectCode.log("consent probe: ${if (ended == null) "closed" else "ended"}")
                finish()
            }
        }
    }
}
