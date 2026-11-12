/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.host.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.myspoti.web.PageHost
import app.myspoti.web.SessionCookies
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The hidden page of an account provider other than Spotify (YouTube Music): a WebView of the
 * provider's official site ([origin]) with the session the user signed in to in [signInView], kept in
 * a WebView profile of its own ([profile]: its own cookies and storage, next to Spotify's), or, on a
 * WebView without profiles, in the shared cookie store, where sign-out deletes only the cookies of
 * [sessionUrls]. It relays the page script's messages (`window.MyspotiHost.postMessage`, a web message
 * listener taken only from [origin]'s main frame) and says when a page finished loading.
 *
 * Unlike Spotify's page it blocks, rewrites and hides nothing: no request is refused, and the page
 * sees its real visibility (a hidden view is hidden to it), so what the provider does with a hidden
 * page on the user's plan is what happens. Its media is unheard from the first play of each document
 * (a volume of 0.001, never "muted": Chromium pauses muted media of a hidden page), before the page
 * script is in. A page whose renderer dies is made again at once and loads [origin] ([gone]): the
 * sync then plays the song it is on there again. [userAgent]: the desktop browser's, as the desktop
 * site is the one whose player is used. Everything runs on the main thread.
 */
class HiddenPageHost(
    context: Context,
    val profile: String,
    private val origin: String,
    private val sessionUrls: List<String>,
    private val userAgent: String?,
) : PageHost, SessionCookies {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 256)
    override val messages: SharedFlow<String> = _messages
    private val _loads = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val loads: SharedFlow<String> = _loads
    private val _gone = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val gone: SharedFlow<Unit> = _gone

    private val multiProfile: Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    private var channel: PlayerChannel? = null
    private var _view: WebView? = null
    private var container: ViewGroup? = null
    /** A page of [origin] was loaded in the view (not only about:blank): a new view loads one again. */
    private var loaded = false

    /** The page's view, made on first use. Main thread. */
    val view: WebView get() = _view ?: create().also { _view = it }

    private fun useProfile(v: WebView) {
        if (!multiProfile) return
        ProfileStore.getInstance().getOrCreateProfile(profile)
        WebViewCompat.setProfile(v, profile)
    }

    /** The cookies of [profile] (the shared ones without profiles). Main thread. */
    fun cookies(): CookieManager =
        if (multiProfile) ProfileStore.getInstance().getOrCreateProfile(profile).cookieManager else CookieManager.getInstance()

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView = WebView(android.content.MutableContextWrapper(appContext)).apply {
        useProfile(this)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgent?.let { userAgentString = it }
        }
        val web = this
        cookies().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        channel = exposeChannel(this)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(this, QUIET_MEDIA, setOf(origin))
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                channel?.let { it.newPage(); view.evaluateJavascript(it.shim(), null) }
            }

            override fun onPageFinished(view: WebView, url: String) {
                channel?.let { view.evaluateJavascript(it.shim(), null) }
                if (url.startsWith(origin)) { loaded = true; _loads.tryEmit(url) }
            }

            // Links out of the provider's site are not followed in the hidden page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith(origin) && !request.url.toString().startsWith("https://accounts.google.com") && request.isForMainFrame

            // A WebView whose renderer is gone can never be reused, and Android kills the app if one
            // is still used after this returns false: true, and the page is made again at once.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (_view !== view) { runCatching { view.destroy() }; return true }
                Logbook.log("$profile page: renderer gone (crashed=${detail.didCrash()}): made again")
                remake(view)
                return true
            }
        }
    }

    /**
     * Replaces [dead] by a new view in the same place, loading [origin] if a page was loaded (the
     * sync plays its song there again once the page is ready), and says the old page is [gone].
     */
    private fun remake(dead: WebView) {
        val box = container
        val reload = loaded
        loaded = false
        (dead.parent as? ViewGroup)?.removeView(dead)
        _view = null
        runCatching { dead.destroy() }
        _gone.tryEmit(Unit)
        val fresh = view
        box?.let { attach(it) }
        if (reload) fresh.loadUrl(origin)
    }

    /** `window.MyspotiHost.postMessage` for [origin]'s main frame only (see [exposeToPlayer] for Spotify's). */
    @SuppressLint("JavascriptInterface")
    private fun exposeChannel(v: WebView): PlayerChannel? {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(v, CHANNEL, setOf(origin)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) message.data?.let { _messages.tryEmit(it) }
            }
            return null
        }
        return PlayerChannel(CHANNEL) { _messages.tryEmit(it) }.also { v.addJavascriptInterface(it, CHANNEL) }
    }

    private suspend fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CompletableDeferred<T>()
        main.post { runCatching(block).fold(done::complete, done::completeExceptionally) }
        return done.await()
    }

    override suspend fun evaluate(script: String) = onMain { view.evaluateJavascript(script, null) }

    override suspend fun navigate(url: String) = onMain { view.loadUrl(url) }

    override suspend fun header(url: String): String? = onMain { cookies().getCookie(url) }

    override suspend fun clear() {
        val done = CompletableDeferred<Unit>()
        main.post {
            if (multiProfile) {
                val p = ProfileStore.getInstance().getOrCreateProfile(profile)
                p.webStorage.deleteAllData()
                p.cookieManager.removeAllCookies { p.cookieManager.flush(); done.complete(Unit) }
            } else {
                // The shared store also holds Spotify's sign-in: only the provider's sites are cleared.
                val c = CookieManager.getInstance()
                for (url in sessionUrls) {
                    val host = android.net.Uri.parse(url).host ?: continue
                    val names = app.myspoti.web.parseCookieHeader(c.getCookie(url)).keys
                    val parent = host.split('.').takeLast(2).joinToString(".")
                    for (name in names) for (domain in listOf(null, ".$parent", host)) {
                        c.setCookie(url, "$name=; Max-Age=0; Path=/" + (domain?.let { "; Domain=$it" } ?: ""))
                    }
                }
                c.flush()
                done.complete(Unit)
            }
        }
        done.await()
    }

    /** Writes the session's cookies to disk now (a sign-in just done must survive the app being swiped away). */
    fun flush() = main.post { cookies().flush() }

    /**
     * Keeps the page in [container] as a 1 px INVISIBLE view: attached to a window (a WebView that
     * never was runs no media), hidden for real.
     */
    fun attach(container: ViewGroup) = main.post {
        this.container = container
        val v = view
        (v.parent as? ViewGroup)?.removeView(v)
        container.addView(v, ViewGroup.LayoutParams(1, 1))
        v.visibility = View.INVISIBLE
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        v.isFocusable = false
    }

    fun detach(from: ViewGroup) = main.post {
        if (container !== from) return@post
        container = null
        _view?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    /** Frees the page (provider changed, signed out, data saver). [navigate] loads it again. */
    fun unload() = main.post { loaded = false; _view?.loadUrl("about:blank") }

    /**
     * A WebView of the provider's official sign-in ([signInUrl]) on the same profile, for the user to
     * sign in themself. [done] is told once the flow comes back to the provider's site ([finished])
     * with whether the session holds an account then ([signedIn], from the cookies of [origin]).
     * The view uses the WebView's own user agent, as the sign-in apps of this kind do.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun signInView(activity: Context, signInUrl: String, finished: (String) -> Boolean, signedIn: (String?) -> Boolean, done: (Boolean) -> Unit): WebView =
        WebView(activity).apply {
            useProfile(this)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            val web = this
            cookies().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
            var reported = false
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    if (reported || !finished(url)) return
                    reported = true
                    val c = cookies()
                    c.flush()
                    // Never logged: the cookies are the account's session.
                    done(signedIn(c.getCookie(origin)))
                }
            }
            loadUrl(signInUrl)
        }

    companion object {
        private const val CHANNEL = "MyspotiHost"

        /**
         * At the start of each document, before the page's own scripts: every media element is held at
         * 0.001 from its first play and whenever the page sets its volume again (the page script does
         * the same once it is in, see ytm-page.js).
         */
        private val QUIET_MEDIA = """
            (() => {
              if (window.__mspQuietHook) return;
              window.__mspQuietHook = true;
              const hold = el => {
                if (el.__mspQuiet) return;
                el.__mspQuiet = true;
                const quiet = () => { if (el.volume > 0.001) el.volume = 0.001; };
                el.addEventListener('volumechange', quiet);
                el.addEventListener('playing', quiet);
                quiet();
              };
              const play = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () { hold(this); return play.apply(this, arguments); };
            })();
        """.trimIndent()
    }
}
