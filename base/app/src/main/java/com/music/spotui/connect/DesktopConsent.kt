package com.music.spotui.connect

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spotify's consent page for its desktop client ([CodeExchange.authorizeUrl]), the last way to a
 * Sonos code: shown full screen over the device picker (both looks share it), in a web view of the
 * default profile, so accounts.spotify.com sees the session the Web Player signed in and the user
 * only allows. Nothing in the page is touched or read but its address: when it goes to the desktop
 * redirect, it closes on that address. Closed, refused, a page that cannot load, or the picker gone:
 * no code.
 */
object DesktopConsent {
    class Open(val url: String, val deviceName: String) {
        val result = CompletableDeferred<String?>()
    }

    val open = MutableStateFlow<Open?>(null)
    /** Pickers on screen to show the page in (changed on the main thread). */
    @Volatile private var hosts = 0
    private const val TIMEOUT_MS = 5 * 60_000L

    /** The address the page ended on once the user allowed, or null. */
    suspend fun ask(url: String, deviceName: String): String? {
        if (hosts == 0) { ConnectCode.log("consent: no device picker on screen"); return null }
        val o = Open(url, deviceName)
        open.value = o
        ConnectCode.log("consent: shown")
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { o.result.await() }.also { if (it == null) ConnectCode.log("consent: cancelled") }
        } finally {
            open.compareAndSet(o, null)
        }
    }

    fun finish(o: Open, url: String?) {
        o.result.complete(url)
        open.compareAndSet(o, null)
    }

    internal fun hosted(on: Boolean) {
        hosts += if (on) 1 else -1
        if (hosts == 0) open.value?.let { finish(it, null) }
    }
}

/** In the device picker: the consent page while one is asked. */
@Composable
fun DesktopConsentHost() {
    DisposableEffect(Unit) {
        DesktopConsent.hosted(true)
        onDispose { DesktopConsent.hosted(false) }
    }
    val o by DesktopConsent.open.collectAsState()
    val current = o ?: return
    key(current) {
        Dialog(
            onDismissRequest = { DesktopConsent.finish(current, null) },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) { ConsentPage(current) }
    }
}

@Composable
private fun ConsentPage(o: DesktopConsent.Open) {
    var host by remember { mutableStateOf(runCatching { java.net.URI(o.url).host }.getOrNull().orEmpty()) }
    Column(Modifier.fillMaxSize().background(tone(0xFF121212)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { DesktopConsent.finish(o, null) }) {
                Icon(Icons.Rounded.Close, stringResource(R.string.ext_auth_close), tint = AppText)
            }
            Column(Modifier.weight(1f)) {
                Text(o.deviceName, color = AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = AppMuted, modifier = Modifier.padding(end = 4.dp))
                    Text(host, color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(stringResource(R.string.lan_sonos_consent, o.deviceName), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx -> consentView(ctx, o) { h -> host = h } },
            onRelease = { it.stopLoading(); it.destroy() },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun consentView(ctx: android.content.Context, o: DesktopConsent.Open, onHost: (String) -> Unit): WebView {
    // The default profile, on purpose: its cookies are the Web Player's, so the account is signed in already.
    val view = WebView(ctx)
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setGeolocationEnabled(false)
        // The same browser as the Web Player's page, for accounts.spotify.com.
        userAgentString = MyspotiRuntime.host.userAgent
    }
    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
        androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())

    /** The desktop redirect, reached by a redirect or a link: the page closes on it (nothing listens there). */
    fun reached(url: String): Boolean {
        if (CodeExchange.redirected(url) == null) return false
        ConnectCode.log("consent: redirect reached")
        DesktopConsent.finish(o, url)
        return true
    }

    view.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            reached(request.url.toString()) || request.url.scheme != "https"

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (reached(url)) { view.stopLoading(); return }
            runCatching { java.net.URI(url).host }.getOrNull()?.let(onHost)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || reached(request.url.toString())) return
            ConnectCode.log("consent: page error ${request.url.host} ${error.errorCode}")
            DesktopConsent.finish(o, null)
        }
    }
    view.loadUrl(o.url)
    return view
}
