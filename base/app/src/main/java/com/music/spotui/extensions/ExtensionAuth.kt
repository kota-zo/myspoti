package com.music.spotui.extensions

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.myspoti.engine.R
import app.myspoti.extensions.AuthRequest
import app.myspoti.extensions.AuthResult
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sign-in pages of extensions (`webview.auth`): one at a time, full screen, in a web view whose
 * profile belongs to that extension, so its cookies are never the app's or Spotify's and stay
 * between two sign-ins of the same extension. Nothing is injected in the page and nothing of it is
 * read but the address: when the page goes to the callback, it closes and the extension gets that
 * address (`auth` event). The extension's own page (`webview.page`) stays locked as it is.
 *
 * A hidden sign-in ([AuthRequest.hidden]) runs the same web view out of sight first: a check that
 * passes by itself (a redirect, an invisible captcha) ends without any screen. If it has not reached
 * the callback after [AuthRequest.HIDDEN_MS], the very same page (nothing reloaded) is shown with its
 * Close button. Whatever the mode, it ends after [AuthRequest.TIMEOUT_MS] with "timeout", and a page
 * that cannot be loaded ends with "network: …".
 */
object ExtensionAuth {
    class Open(val extensionId: String, val id: String, val request: AuthRequest) {
        /** Shown to the user: at once, or once a hidden sign-in needed them. */
        val visible = MutableStateFlow(!request.hidden)
    }

    val open = MutableStateFlow<Open?>(null)
    private val counter = AtomicInteger()

    /** Shows [request] for [extensionId]; its id. Throws when it cannot be shown now. */
    fun open(extensionId: String, request: AuthRequest): String {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            "this phone's WebView cannot keep sign-in pages apart (update Android System WebView)"
        }
        val o = Open(extensionId, "auth-${counter.incrementAndGet()}", request)
        synchronized(this) {
            check(open.value == null) { "a sign-in page is already open" }
            open.value = o
        }
        Extensions.log(extensionId, "sign-in ${o.id} opened on ${java.net.URI(request.url).host}" + if (request.hidden) " (hidden)" else "")
        return o.id
    }

    /** Closes [o] and tells its extension: [url] when the callback was reached, else [error]. */
    fun finish(o: Open, url: String?, error: String? = null) {
        synchronized(this) { if (open.value !== o) return; open.value = null }
        Extensions.authDone(o.extensionId, AuthResult(o.id, url = url, error = if (url == null) error ?: "closed" else null))
    }

    /** The extension stopped: its page goes, without an answer. */
    fun cancel(extensionId: String) = synchronized(this) { if (open.value?.extensionId == extensionId) open.value = null }

    /** The web view profile of [extensionId]: its own cookies and storage. */
    /** Deletes the extension's sign-in profile (cookies and storage); best effort, on the main thread. */
    fun forget(extensionId: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { ProfileStore.getInstance().deleteProfile(profile(extensionId)) }
        }
    }

    fun profile(extensionId: String) = "ext-auth-" + extensionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/** Above every screen (MainActivity's root box): the open sign-in, hidden or full screen. */
@Composable
fun ExtensionAuthHost() {
    val o by ExtensionAuth.open.collectAsState()
    val current = o ?: return
    key(current) { AuthPage(current) }
}

@Composable
private fun AuthPage(current: ExtensionAuth.Open) {
    val visible by current.visible.collectAsState()
    val name = Extensions.registry.value.find(current.extensionId)?.name ?: current.extensionId
    var host by remember { mutableStateOf(runCatching { java.net.URI(current.request.url).host }.getOrNull().orEmpty()) }
    LaunchedEffect(Unit) {
        if (current.request.hidden) {
            delay(AuthRequest.HIDDEN_MS)
            if (ExtensionAuth.open.value === current && !current.visible.value) {
                Extensions.log(current.extensionId, "sign-in ${current.id} needs the user: shown")
                current.visible.value = true
            }
            delay(AuthRequest.TIMEOUT_MS - AuthRequest.HIDDEN_MS)
        } else delay(AuthRequest.TIMEOUT_MS)
        ExtensionAuth.finish(current, null, "timeout")
    }
    BackHandler(enabled = visible) { ExtensionAuth.finish(current, null, "closed") }
    // One web view for both modes: showing a hidden page only moves it, its state stays. Hidden, it is
    // laid out full screen (a 1 dp page lays itself out for 1 px and stays zoomed in once shown), so its
    // scripts run as on screen, but placed beside the screen and transparent: never seen, never touched.
    val frame = if (visible) Modifier.fillMaxSize().background(tone(0xFF121212))
        .clickable(remember { MutableInteractionSource() }, indication = null) {}
        .statusBarsPadding().navigationBarsPadding().imePadding()
    else Modifier.fillMaxSize().offset { IntOffset(100_000, 0) }.alpha(0f)
    Column(frame) {
        if (visible) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ExtensionAuth.finish(current, null, "closed") }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.ext_auth_close), tint = AppText)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ext_auth_title, name), color = AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = AppMuted, modifier = Modifier.padding(end = 4.dp))
                        Text(host, color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(stringResource(R.string.ext_auth_notice), color = AppMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        AndroidView(
            modifier = if (visible) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxSize(),
            factory = { ctx -> authView(ctx, current) { h -> host = h } },
            onRelease = { it.stopLoading(); it.destroy() },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun authView(ctx: android.content.Context, o: ExtensionAuth.Open, onHost: (String) -> Unit): WebView {
    val view = WebView(ctx)
    // The sign-in page in the app's language when its site offers it (the WebView's own header
    // follows the phone, not a language picked in Myspoti).
    val language = mapOf("Accept-Language" to Extensions.language().let { if (it.contains('-')) "$it,${it.substringBefore('-')};q=0.9" else it } + ",en;q=0.5")
    // Before anything else touches it: this view's cookies and storage are the extension's profile.
    val profile = ExtensionAuth.profile(o.extensionId)
    ProfileStore.getInstance().getOrCreateProfile(profile)
    WebViewCompat.setProfile(view, profile)
    WebViewCompat.getProfile(view).cookieManager.apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(view, true)
    }
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        // window.open is taken (onCreateWindow) and loaded here: some checks hand the callback over that way.
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        setGeolocationEnabled(false)
    }

    /** The callback, reached by a link, a redirect, a form or a window.open: the page closes on it. */
    fun reached(url: String): Boolean {
        if (!o.request.reached(url)) return false
        ExtensionAuth.finish(o, url)
        return true
    }

    /** Only web pages in this view: no app, intent or file scheme leaves it. */
    fun refused(uri: android.net.Uri) = uri.scheme != "https" && uri.scheme != "http"

    view.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            reached(request.url.toString()) || refused(request.url)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // A form POSTed to an https callback does not go through shouldOverrideUrlLoading.
            if (reached(url)) { view.stopLoading(); return }
            runCatching { java.net.URI(url).host }.getOrNull()?.let(onHost)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // The page itself cannot be loaded (no network, no such host): the sign-in ends. A check's
            // HTTP status (403, 503 of a challenge page) is not an error, the page goes on.
            if (request.isForMainFrame && !o.request.reached(request.url.toString()))
                ExtensionAuth.finish(o, null, "network: ${error.description}")
        }
    }
    view.webChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // A throwaway view only learns where the new window goes, then that address is the
            // callback (done) or loads here; no second window is ever shown.
            val probe = WebView(view.context)
            // The same profile: even the first request of the new window never carries other cookies.
            WebViewCompat.setProfile(probe, ExtensionAuth.profile(o.extensionId))
            probe.webViewClient = object : WebViewClient() {
                private var taken = false
                private fun take(url: String) {
                    if (taken) return
                    taken = true
                    probe.stopLoading()
                    if (!reached(url) && !refused(android.net.Uri.parse(url))) view.loadUrl(url, language)
                    probe.post { probe.destroy() }
                }
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean { take(request.url.toString()); return true }
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { if (url != "about:blank") take(url) }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = probe
            resultMsg.sendToTarget()
            return true
        }
    }
    view.loadUrl(o.request.url, language)
    return view
}
