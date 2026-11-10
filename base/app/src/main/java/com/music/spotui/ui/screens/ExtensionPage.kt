package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import app.myspoti.engine.R
import app.myspoti.extensions.Action
import app.myspoti.extensions.PageContent
import app.myspoti.extensions.PageNetwork
import app.myspoti.extensions.SurfaceGuard
import com.music.spotui.extensions.Extensions
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import java.io.ByteArrayInputStream

internal fun extensionPageRoute(id: String) = "extension-page/${android.net.Uri.encode(id)}"

/**
 * An extension's own page (webview.page, critical): its HTML in a WebView the app locks down.
 * No file or content access, no navigation, no new windows, no storage, no cookies: what the page
 * loads is fetched by the app, GET over https from the hosts its network action lists, never a
 * local or Spotify address ([PageNetwork]), with a content policy on top. The only bridge is
 * `MyspotiPage.postMessage(text)`, to its own extension; answers come back as `message` events.
 * It is drawn in the app's page (dialogs, the protected ones included, are above it) and hidden
 * while a protected surface is on screen.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ExtensionPageScreen(navController: NavController, id: String) {
    val registry by Extensions.registry.collectAsState()
    val e = registry.find(id)
    val m = remember(id, e?.sha256) { Extensions.manifest(id) }
    val loaded by produceState<Result<PageContent?>?>(null, id) { value = runCatching { Extensions.page(id) } }
    val shield by SurfaceGuard.app.state.collectAsState()
    val granted = e?.granted.orEmpty()
    val hosts = m?.hosts().orEmpty()
    val network = Action.NETWORK.key in granted
    val any = Action.NETWORK_ANY in granted

    if (com.music.spotui.ui.navigation.isImmersive()) {
        com.music.spotui.ui.immersive.pages.ImmersiveExtensionPageFrame(
            title = loaded?.getOrNull()?.title ?: e?.name ?: id,
            subtitle = stringResource(R.string.ext_from, e?.name ?: id),
            onBack = { navController.popBackStack() },
        ) { ExtensionPageBody(id, loaded, hosts, network, any, shield.isNotEmpty()) }
        return
    }
    Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = AppText)
            }
            Column {
                Text(loaded?.getOrNull()?.title ?: e?.name ?: id, color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.ext_from, e?.name ?: id), color = AppMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding()) {
            ExtensionPageBody(id, loaded, hosts, network, any, shield.isNotEmpty())
        }
    }
}

/**
 * What an extension's page shows in the space under its header: a spinner while it loads, the
 * failure, or the sandboxed WebView (hidden and paused while [shielded], a protected surface is on
 * screen). The same in both looks, which only differ in the frame around it.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun ExtensionPageBody(id: String, loaded: Result<PageContent?>?, hosts: List<String>, network: Boolean, any: Boolean, shielded: Boolean) {
    Box(Modifier.fillMaxSize()) {
        val page = loaded?.getOrNull()
        when {
            loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = AppAccent)
            page == null -> Text(stringResource(R.string.ext_page_failed), color = AppMuted, modifier = Modifier.padding(20.dp))
            else -> {
                val html = remember(page) { PageNetwork.wrap(page.html, PageNetwork.csp(hosts, network, any)) }
                val web = remember { arrayOfNulls<WebView>(1) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            web[0] = this
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.domStorageEnabled = false
                            settings.setGeolocationEnabled(false)
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            setBackgroundColor(android.graphics.Color.BLACK)
                            webViewClient = SandboxClient(hosts, network, any)
                            addJavascriptInterface(PageBridge(id), "MyspotiPage")
                            Extensions.attachPage(id) { json -> evaluateJavascript("window.dispatchEvent(new MessageEvent('message', { data: $json }));", null) }
                            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        }
                    },
                    update = { wv ->
                        if (shielded) { wv.onPause(); wv.visibility = View.INVISIBLE } else { wv.onResume(); wv.visibility = View.VISIBLE }
                    },
                )
                DisposableEffect(id) {
                    onDispose {
                        Extensions.attachPage(id, null)
                        web[0]?.destroy()
                    }
                }
            }
        }
    }
}

/** The page's one door: a message to its own extension. */
private class PageBridge(private val id: String) {
    @JavascriptInterface
    fun postMessage(data: String) = Extensions.pageMessage(id, data)
}

/** No navigation; every load checked by [PageNetwork] and fetched here, without cookies. */
private class SandboxClient(private val hosts: List<String>, private val network: Boolean, private val any: Boolean) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (url.startsWith("data:") || url.startsWith("about:")) return null
        if (com.music.spotui.data.DataSaver.active || !PageNetwork.allows(url, request.method, hosts, network, any)) return refused()
        // OkHttp with the sandbox's Dns: a name that resolves to a local address is never connected to,
        // and no redirect is followed (a redirect would escape the allowed hosts).
        return runCatching {
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Myspoti-Extension-Page/1").get().build()
            val client = app.myspoti.extensions.SafeNet.client.newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(req).execute().use { r ->
                val status = r.code
                if (status !in 200..299) return refused()
                val body = r.body
                val ct = body.contentType()
                val type = ct?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
                val charset = ct?.charset()?.name()
                if (body.contentLength() > 8L * 1024 * 1024) return refused()
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                body.byteStream().use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > 8 * 1024 * 1024) return refused()
                    }
                }
                WebResourceResponse(type, charset, status, "OK", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(out.toByteArray()))
            }
        }.getOrElse { refused() }
    }

    private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Refused", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
