package app.myspoti.host.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewClient
import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream

/**
 * [BridgeHost] on an Android WebView that loads the official Web Player.
 *
 * - The WebView is built on the application context, so playback outlives activities.
 * - Desktop user agent: on a phone UA the Web Player only offers to open the app.
 * - Protected media (Widevine) is allowed; ad audio requests ([AdBlock]) are refused.
 * - [loginRequired] turns true on Spotify's login pages: the UI then shows [view] so the
 *   user signs in on Spotify's own page.
 */
class WebPlayerHost(private val context: Context) : BridgeHost {
    private val main = Handler(Looper.getMainLooper())

    // DROP_OLDEST: tryEmit under a main-thread stall must not drop the newest
    // state/result (the default SUSPEND overflow drops exactly the new value).
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val messages: SharedFlow<String> = _messages

    private val _url = MutableStateFlow("")
    /** The page's current URL. */
    val url: StateFlow<String> = _url

    /*
     * Spotify limiting this client: measured on the test phone after an evening of test runs,
     * the page asked for an audio licence ~4 times a second, each answered 429, and every repair
     * (reload, replay) asked again. A storm of them ends playback here for a while (growing on
     * repeats) instead of feeding it; [licenseLimitedUntil] tells the app and the player.
     */
    @Volatile private var heldLicenses = 0
    @Volatile private var licenseAnswersLogged = 0
    private val license429 = ArrayDeque<Long>()
    private var limitStreak = 0
    private var lastLimitAt = 0L
    private val _licenseLimitedUntil = MutableStateFlow(0L)
    /** Elapsed-realtime end of the current hold, 0 or past when none. */
    val licenseLimitedUntil: StateFlow<Long> = _licenseLimitedUntil
    fun licenseLimited(): Boolean = android.os.SystemClock.elapsedRealtime() < _licenseLimitedUntil.value

    private fun onLicenseLimited() = synchronized(license429) {
        val now = android.os.SystemClock.elapsedRealtime()
        license429.addLast(now)
        while (license429.isNotEmpty() && now - license429.first() > LIMIT_WINDOW_MS) license429.removeFirst()
        if (license429.size < LIMIT_COUNT || licenseLimited()) return@synchronized
        if (now - lastLimitAt > LIMIT_FORGET_MS) limitStreak = 0
        lastLimitAt = now
        val hold = (LIMIT_HOLD_MS shl limitStreak.coerceAtMost(4)).coerceAtMost(LIMIT_HOLD_MAX_MS)
        limitStreak++
        license429.clear()
        Logbook.log("Spotify is limiting audio licences (429 x$LIMIT_COUNT in ${LIMIT_WINDOW_MS / 1000} s): playback held ${hold / 1000} s")
        heldLicenses = 0
        _licenseLimitedUntil.value = now + hold
    }

    private val _loginRequired = MutableStateFlow(false)
    val loginRequired: StateFlow<Boolean> = _loginRequired

    /** Fires after each Web Player page load; the API injects the bridge then. */
    var onPageLoaded: (() -> Unit)? = null

    /** Fires when a Web Player page load starts: the old page and its bridge are gone. */
    var onPageStarting: (() -> Unit)? = null

    /** Debug builds: extra logging (a proxy that fails, a device name rewritten…) — and CDP. */
    private val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * This install's browser identity, drawn once (see [Fingerprint]) and handed to the API as
     * well: the page and the app's own calls must present the *same* browser, or the difference
     * between them is itself the tell.
     */
    internal val fingerprint = Fingerprint.of(context)
    val userAgent: String get() = fingerprint.userAgent

    init {
        // Debug builds only: lets desktop tools (chrome://inspect, CDP) look at the page.
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)
        watchNetwork()
    }

    /**
     * The phone moves to another network (Wi-Fi to mobile data, or back): the relay's pooled
     * connections still go through the old one, and a command sent on one of them only waited for
     * its timeout. The idle ones are dropped, so the next command opens a connection on the new
     * network; one already waiting fails within the relay's read timeout. The callback runs on the
     * system's network thread, which is where the sockets may be closed.
     */
    private fun watchNetwork() {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                private var current: android.net.Network? = null

                override fun onAvailable(network: android.net.Network) {
                    val was = current
                    current = network
                    // The first call names the network the app started on: nothing to drop.
                    if (was == null || was == network) return
                    Logbook.log("network changed: the relay's idle connections dropped")
                    ControlRelay.resetConnections()
                }
            })
        }.onFailure { Logbook.log("network changes not watched: ${it.message}") }
    }

    private lateinit var relay: ControlRelay

    /** Fetches the audio files itself: the last request the WebView would stamp with its package. */
    private val media = MediaProxy(MediaProxy.client()) { relay.identityPair() }

    @SuppressLint("SetJavaScriptEnabled")
    var view: WebView = createView()

    /**
     * Builds the page view. Called once at startup, and again after a renderer crash: a WebView
     * whose renderer died can never be reused, and Android kills the process if one is used after
     * [onRenderProcessGone] returns false (see [rebuild]).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(): WebView = AwakeWebView(context.applicationContext).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = fingerprint.userAgent
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        // The WebView stamps every request the page makes with "X-Requested-With: <our package>".
        // No browser does that, and it is measured on the wire ([tools/cdp.py] --trace --headers):
        // it rode on the audio files themselves and on Spotify's telemetry, so a single rule on
        // Spotify's side would flag every Myspoti install. The androidx API was made to allow that
        // header for a few origins; an empty allowlist is what suppresses it everywhere.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            Logbook.log("x-requested-with: off for every origin")
        } else {
            Logbook.log("x-requested-with: cannot be turned off on this WebView")
        }
        // Client hints must agree with the desktop user agent: by default the WebView announces
        // "Android WebView" / mobile in Sec-CH-UA headers and navigator.userAgentData.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) {
            val brands = fingerprint.brands.map {
                androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand(it.name).setMajorVersion(it.major).setFullVersion(it.fullVersion).build()
            }
            androidx.webkit.WebSettingsCompat.setUserAgentMetadata(settings, androidx.webkit.UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(fingerprint.fullVersion)
                .setPlatform("Linux")
                .setPlatformVersion(fingerprint.kernel)
                .setArchitecture("x86")
                .setBitness(64)
                .setMobile(false)
                .setModel("")
                .setWow64(false)
                .build())
        }
        // The page plays the audio: its renderer keeps its priority when the screen is off.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        // Playback control requests leave from the app, without the WebView's X-Requested-With.
        relay = ControlRelay(this, fingerprint).also {
            it.onDeviceRegistered = { drmAsked = true; main.removeCallbacks(drmWatch) }
            it.install()
        }
        val webView = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        exposeToPlayer("MyspotiHost") { json -> _messages.tryEmit(json) }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                    message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                    // The page's steady console noise (font preloads, storage access refused on
                    // every load) never helped a report: left out.
                    if (CONSOLE_NOISE.none { it.containsMatchIn(message.message()) })
                        Logbook.log("console ${message.messageLevel()}: ${message.message().take(300)}")
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                Logbook.log("permission request: ${request.resources.joinToString()}")
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) { drmAsked = true; main.removeCallbacks(drmWatch) }
                val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (AdBlock.isBlocked(url)) {
                    Logbook.log("blocked ad request ${Logbook.noQuery(url).take(120)}")
                    // Refused like a blocked request: the player reports a failed ad and moves on.
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                // Spotify is limiting audio licences (429) and the app holds playback: the page still
                // skips on by itself through every song it gets no licence for (measured in the 25/10
                // logs: 20 to 25 more licence requests in the 4 s after the hold began, ~4 a second,
                // every one answered 429). Those are not sent: the page gets the same 429 from here
                // and Spotify sees no more of them while the hold lasts.
                if (request.method == "POST" && licenseLimited() && url.contains(LICENSE_PATH)) {
                    if (heldLicenses++ == 0) Logbook.log("audio licence requests answered here while Spotify is limiting")
                    app.myspoti.spotify.report.Refusal402.licenceHeld()
                    return WebResourceResponse("application/octet-stream", "utf-8", 429, "Too Many Requests", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                // The sign-in flow lives on accounts.spotify.com: logging its steps is what turns
                // "the login is stuck" into the exact request that failed (with the status from
                // onReceivedHttpError below). Reported as B37 and unprovable without this trail.
                if (url.startsWith("https://accounts.spotify.com/")) {
                    loginSteps++
                    Logbook.log("login step #$loginSteps ${request.method} ${url.substringAfter("accounts.spotify.com").substringBefore('?').take(90)}")
                }
                // The music itself: fetched here, so the WebView's own package-name header never
                // travels with it (see MediaProxy).
                if (media.handles(url)) {
                    val proxied = media.fetch(request)
                    if (proxied != null) {
                        if (debuggable) Logbook.log("audio proxied ${url.substringAfterLast('/').take(12)}…")
                        return proxied
                    }
                    Logbook.log("audio proxy failed, the WebView fetches it: ${Logbook.noQuery(url).take(110)}")
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                Logbook.log("page start ${Logbook.noQuery(url).take(120)}")
                mainFrameFailed = false
                // A load that never ends (measured: a reload's document request answered nothing
                // for 9 minutes, the page stayed "loading", empty, without the bridge).
                main.removeCallbacks(loadTimeout)
                if (android.net.Uri.parse(url).host == PLAYER_HOST) main.postDelayed(loadTimeout, PAGE_LOAD_TIMEOUT_MS)
                pageStarts++
                _url.value = url
                _loginRequired.value = isLoginPage(url)
                if (url.startsWith(PLAYER_URL)) { drmAsked = false; licenseAnswersLogged = 0; onPageStarting?.invoke(); relay.injectNow() }
                if (PLAYER_ROOT.matches(url) && url != roots.getString(ROOT_KEY, null)) roots.edit().putString(ROOT_KEY, url).apply()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.url.host?.contains("spotify") == true)
                    Logbook.log("load error ${error.errorCode} ${error.description} ${Logbook.noQuery(request.url.toString()).take(120)}")
                // ERR_ABORTED: a load this app stopped itself (a timeout, a newer load) — not a failure.
                if (request.isForMainFrame && request.url.host == PLAYER_HOST && "ERR_ABORTED" !in error.description.toString())
                    playerPageFailed("load error ${error.errorCode}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                // Licence answers are counted in the 402 summary and their first ones logged below
                // with their headers; the same 429 three times per song was the bulk of some reports.
                if (request.url.host?.contains("spotify") == true && request.url.path?.contains("widevine-license") != true)
                    Logbook.logEvery("http ${response.statusCode} ${request.url.host}${request.url.path}", 60_000,
                        "http ${response.statusCode} ${request.method} ${request.url.toString().substringBefore('?').take(120)}")
                if (request.isForMainFrame && request.url.host == PLAYER_HOST && (response.statusCode >= 500 || response.statusCode == 429))
                    playerPageFailed("http ${response.statusCode}")
                if (request.url.path?.contains("widevine-license") == true) {
                    if (response.statusCode == 429) onLicenseLimited()
                    response.responseHeaders.entries.firstOrNull { it.key.equals("retry-after", true) }
                        ?.let { app.myspoti.spotify.report.Refusal402.licenceRetryAfter(it.value) }
                    // A refused licence (403 seen on a new sign-in, 429 on others) says why in its
                    // headers at best; nothing else of the answer reaches the app. A few per page.
                    if (response.statusCode >= 400 && licenseAnswersLogged++ < LICENSE_ANSWERS_LOGGED) {
                        val h = response.responseHeaders.entries
                            .filter { (k, _) -> k.lowercase() in LICENSE_HEADERS }
                            .joinToString(", ") { (k, v) -> "${k.lowercase()}=${v.take(60)}" }
                        Logbook.log("licence answer ${response.statusCode}: $h")
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                Logbook.log("page finished ${Logbook.noQuery(url).take(120)}")
                main.removeCallbacks(loadTimeout)
                if (url.startsWith(PLAYER_URL) && !mainFrameFailed) { pageFailures = 0; main.removeCallbacks(retryPlayerPage) }
                _url.value = url
                _loginRequired.value = isLoginPage(url)
                if (url.startsWith(PLAYER_URL)) {
                    onPageLoaded?.invoke()
                    if (!drmAsked) { main.removeCallbacks(drmWatch); main.postDelayed(drmWatch, DRM_WATCH_MS) }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                // Stay on Spotify's own pages (player, login, consent); open nothing else.
                // Exact or subdomain match: "evilspotify.com" must not count as Spotify.
                val allowed = listOf("spotify.com", "spotify.net", "scdn.co", "google.com", "facebook.com", "apple.com")
                    .any { host == it || host.endsWith(".$it") }
                return !allowed
            }

            /**
             * The renderer died (the reported "the app crashed", "playback stops until the player
             * is restarted"). A WebView whose renderer is gone can never be reused, and Android
             * kills the whole process if one is still used after this returns false: the page is
             * rebuilt on a new WebView, and true says "handled, do not kill the app".
             */
            override fun onRenderProcessGone(gone: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Killed (memory pressure) or crashed: both end here. A late callback from a view
                // that was already replaced must not tear down the healthy new one.
                if (gone !== this@WebPlayerHost.view) return true
                Logbook.log("renderer gone (crashed=${detail.didCrash()}): rebuilding the page")
                rebuild()
                return true
            }
        }
    }

    /** The container the page is attached to, so a rebuilt page goes back to the same place. */
    private var container: ViewGroup? = null

    /** Whether the page was shown full screen at the last [attach] (login). */
    private var lastVisible = false

    /**
     * Replaces the page after its renderer died: the dead WebView is destroyed rather than reused,
     * the new one takes its place in the same container, and the page loads again. Without this
     * the app kept a dead page — a blank screen with the sound gone — until it was restarted.
     */
    private fun rebuild(): Boolean = main.post {
        main.removeCallbacks(drmWatch)
        val wasLoaded = loadAsked
        val box = container
        box?.let { runCatching { it.removeView(view) } }
        (view as? AwakeWebView)?.releaseFromWindow()
        runCatching { view.stopLoading() }
        runCatching { view.destroy() }
        view = createView()
        pageStarts = 0
        _url.value = ""
        // attach() loads the page itself when a load is pending; the pending load is asked again
        // just after, once, so a rebuilt page is loaded exactly once.
        loadAsked = false
        box?.let { attach(it, lastVisible) }
        // The new view's first navigation can be lost while the browser side still settles after
        // the kill (seen: no page start for 4 s): the watchdog looks again after 1.5 s, not 4 s.
        if (wasLoaded) load(firstWatchMs = REBUILD_WATCH_MS) else view.loadUrl("about:blank")
    }

    private fun isLoginPage(url: String?) =
        url != null && (url.contains("accounts.spotify.com") || url.contains("/login") || url.contains("challenge.spotify.com") ||
            // "Continue with Google / Facebook / Apple": still mid sign-in on their pages.
            url.contains("accounts.google.com") || url.contains("facebook.com") || url.contains("appleid.apple.com"))

    fun load() = load(firstWatchMs = LOAD_WATCH_MS)

    private fun load(firstWatchMs: Long) = main.post {
        pageStarts = 0
        loadAsked = true
        view.loadUrl(playerRoot())
        watchLoad(playerRoot(), attempt = 1, firstWatchMs)
    }

    /** Page starts since the last [load]; the load watchdog looks at it. */
    private var pageStarts = 0

    /** Sign-in requests seen (accounts.spotify.com): a trail for a login that goes nowhere. */
    private var loginSteps = 0
    /** [load] was called and no [unload] since (data saver keeps the page off). */
    private var loadAsked = false

    /**
     * An exec-spawned process (GrapheneOS "secure app spawning") can lose the first
     * navigation of a WebView made in Application.onCreate: no page start, ever. When
     * nothing started after a while, the page is asked for again (a few times, further apart).
     */
    private fun watchLoad(url: String, attempt: Int, firstWatchMs: Long = LOAD_WATCH_MS) {
        main.postDelayed({
            if (pageStarts > 0 || !loadAsked || attempt > LOAD_RETRIES) return@postDelayed
            Logbook.log("page never started: loading again (attempt $attempt)")
            if (attempt > 1) view.stopLoading()
            view.loadUrl(url)
            watchLoad(url, attempt + 1)
        }, if (attempt == 1) firstWatchMs else LOAD_WATCH_MS * attempt)
    }

    private val roots by lazy { context.getSharedPreferences("web_player", Context.MODE_PRIVATE) }

    /**
     * Where Spotify sends "/" for this account's language (`…/intl-fr/`), loaded directly: the
     * redirect cost a whole navigation, about 0.5 s, on every start and reload (measured on the
     * test phone). Learnt from the last load; a language where "/" is final keeps [PLAYER_URL].
     */
    private fun playerRoot(): String = roots.getString(ROOT_KEY, null)?.takeIf { PLAYER_ROOT.matches(it) } ?: PLAYER_URL

    /** The player's own URL, not the page's current one: the bridge parks the page on an empty route. */
    fun reload() = main.post {
        // A page whose player never started is not helped by loading it again in the same renderer.
        if (pageStarts > 0 && !drmAsked && _url.value.startsWith(PLAYER_URL) && newViewAllowed()) {
            Logbook.log("reload: the player never started on this page (no DRM request): new WebView instead")
            rebuild()
        } else view.loadUrl(playerRoot())
    }

    /**
     * The page's player started since the page last started: it asked for DRM (Widevine), as it
     * does on every load, or its playback device was registered. A page that never asks never registers its device, and every command
     * then fails with "No such device" (seen on a Xiaomi just after signing in: six minutes and
     * five reloads, until the renderer was replaced, after which it asked at once).
     */
    @Volatile private var drmAsked = false

    /** Times the page was put on a new WebView because its player never started (at most 2 in 10 min). */
    private val drmRebuilds = ArrayDeque<Long>()

    private fun newViewAllowed(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        while (drmRebuilds.isNotEmpty() && now - drmRebuilds.first() > DRM_REBUILD_WINDOW_MS) drmRebuilds.removeFirst()
        if (drmRebuilds.size >= DRM_REBUILDS_MAX) return false
        drmRebuilds.addLast(now)
        return true
    }

    private val drmWatch: Runnable = Runnable {
        if (drmAsked || !loadAsked || !_url.value.startsWith(PLAYER_URL) || isLoginPage(_url.value)) return@Runnable
        if (!newViewAllowed()) { Logbook.log("player never started on this page (no DRM request); new WebViews used up for now"); return@Runnable }
        Logbook.log("player never started on this page (no DRM request ${DRM_WATCH_MS / 1000} s after load): new WebView")
        rebuild()
    }

    /**
     * Hides the page without hiding it from Chromium (it keeps running as on screen): after the
     * sign-in, while the session is being confirmed, the app's own waiting screen shows instead of
     * Spotify's desktop player (reported: it flashed before the first screen). [attach] and
     * [openLogin] show it again.
     */
    fun veil(on: Boolean) = main.post { view.alpha = if (on) 0f else 1f }

    /*
     * A player page that did not load (Spotify answering 504 to a reload, the network down at that
     * moment) used to stay what it was: an error page, "not ready", every command refused, until
     * the app was restarted (seen on the test phone after an ad recovery's reload). It is loaded
     * again, sooner first, then once a minute, until a page loads.
     */
    private var mainFrameFailed = false
    private var pageFailures = 0
    private val retryPlayerPage = Runnable {
        if (!loadAsked || _loginRequired.value) return@Runnable
        Logbook.log("player page: load again (attempt ${pageFailures + 1})")
        view.stopLoading()
        view.loadUrl(playerRoot())
    }
    private val loadTimeout = Runnable {
        if (!loadAsked || _loginRequired.value) return@Runnable
        view.stopLoading()
        playerPageFailed("still loading after ${PAGE_LOAD_TIMEOUT_MS / 1000} s")
    }

    private fun playerPageFailed(what: String) {
        mainFrameFailed = true
        val wait = RETRY_PAGE_MS[pageFailures.coerceAtMost(RETRY_PAGE_MS.lastIndex)]
        pageFailures++
        Logbook.log("player page failed to load ($what): again in ${wait / 1000} s")
        main.removeCallbacks(retryPlayerPage)
        main.postDelayed(retryPlayerPage, wait)
    }

    fun openLogin() = main.post { view.alpha = 1f; view.loadUrl(LOGIN_URL) }

    /** Signs out of Spotify in this WebView, then shows the login page. */
    fun logout() = main.post {
        CookieManager.getInstance().removeAllCookies {
            main.post {
                // Web storage too: localStorage/IndexedDB hold the player's session
                // state, and clearCache(true) also drops the disk cache (false kept it).
                android.webkit.WebStorage.getInstance().deleteAllData()
                view.clearCache(true)
                view.loadUrl(LOGIN_URL)
            }
        }
    }

    override suspend fun evaluate(script: String) {
        val done = CompletableDeferred<Unit>()
        main.post {
            runCatching { view.evaluateJavascript(script, null) }
            done.complete(Unit)
        }
        done.await()
    }

    /**
     * Keeps the page attached to a window, a tiny non-interactive view in [container] while the
     * player is hidden.
     *
     * Hidden means INVISIBLE, not a 1 px view at 1 % alpha: a drawn WebView is replayed by the
     * app's RenderThread on every frame the app draws (measured with Perfetto on the player
     * screen: `WebViewFunctor::drawVk` 3.7 ms plus a 1.5 ms `postAndWait` on the UI thread, per
     * frame, for a pixel nobody sees), and the page kept animating its own UI underneath. The
     * page then runs as it already does whenever the app is in the background or the screen is
     * off, which is how most listening happens.
     */
    fun attach(container: ViewGroup, visible: Boolean) = main.post {
        this.container = container
        lastVisible = visible
        // Re-added each time: addView converts the params to the container's own type
        // (assigning plain ViewGroup.LayoutParams to a FrameLayout child crashes on measure).
        (view.parent as? ViewGroup)?.removeView(view)
        val size = if (visible) ViewGroup.LayoutParams.MATCH_PARENT else 1
        container.addView(view, ViewGroup.LayoutParams(size, size))
        keepClearOfSystemBars(visible)
        view.alpha = 1f
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        view.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Typing in the login form needs touch focus, or the keyboard never opens.
        view.isFocusable = visible
        view.isFocusableInTouchMode = visible
        view.descendantFocusability = if (visible) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (visible) view.requestFocus()
        view.requestLayout()
        // Now in a window: a first navigation that never started gets another go.
        if (loadAsked && _url.value.isEmpty() && pageStarts == 0) view.loadUrl(playerRoot())
    }

    /**
     * The app draws edge to edge: the login page shown full screen went under the status bar
     * (reported: Spotify's logo under the clock) and its form under the keyboard. Shown, the page
     * keeps clear of the system bars, the cutout and the keyboard; hidden, it takes no room.
     */
    private fun keepClearOfSystemBars(visible: Boolean) {
        val v = view
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(v) { _, insets ->
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                val bars = if (lastVisible) insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                        androidx.core.view.WindowInsetsCompat.Type.ime()
                ) else androidx.core.graphics.Insets.NONE
                if (lp.topMargin != bars.top || lp.bottomMargin != bars.bottom || lp.leftMargin != bars.left || lp.rightMargin != bars.right) {
                    lp.setMargins(bars.left, bars.top, bars.right, bars.bottom)
                    v.layoutParams = lp
                }
            }
            insets
        }
        if (visible) androidx.core.view.ViewCompat.requestApplyInsets(v)
        else (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.setMargins(0, 0, 0, 0); v.layoutParams = it }
    }

    fun detach() = main.post { (view.parent as? ViewGroup)?.removeView(view) }

    /** Frees the page (data saver, low memory). [load] brings it back. */
    fun unload() = main.post { loadAsked = false; view.loadUrl("about:blank") }

    companion object {
        /** Logcat tag of the engine: `adb logcat -s Myspoti`. */
        const val LOG = "Myspoti"
        const val PLAYER_URL = "https://open.spotify.com/"
        private val PLAYER_ROOT = Regex("""https://open\.spotify\.com/intl-[a-z]{2,3}(?:-[a-zA-Z]{2,4})?/""")
        private const val ROOT_KEY = "player_root"
        private const val PLAYER_HOST = "open.spotify.com"
        private const val PAGE_LOAD_TIMEOUT_MS = 25_000L
        private const val DRM_WATCH_MS = 10_000L
        private const val DRM_REBUILDS_MAX = 2
        private const val DRM_REBUILD_WINDOW_MS = 600_000L
        private const val LICENSE_PATH = "/widevine-license/v1/audio/license"
        private const val LICENSE_ANSWERS_LOGGED = 6
        private val CONSOLE_NOISE = listOf(Regex("^The resource .* was preloaded"), Regex("^requestStorageAccess: Permission denied"))
        private val LICENSE_HEADERS = setOf("retry-after", "content-type", "server", "via", "x-ratelimit-remaining", "x-ratelimit-reset", "cf-ray", "x-envoy-upstream-service-time")
        private const val LIMIT_COUNT = 6
        private const val LIMIT_WINDOW_MS = 10_000L
        private const val LIMIT_HOLD_MS = 60_000L
        private const val LIMIT_HOLD_MAX_MS = 600_000L
        private const val LIMIT_FORGET_MS = 1_800_000L
        private val RETRY_PAGE_MS = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)
        private const val LOAD_WATCH_MS = 4_000L
        private const val REBUILD_WATCH_MS = 1_500L
        private const val LOAD_RETRIES = 4
        const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    }
}

/**
 * The engine's WebView, never hidden as far as Chromium knows. A page Chromium sees hidden has
 * its timers held to one wake-up a second, then to one a minute once it has been hidden five
 * minutes without sound (measured on the test phone, app in the background: eight 38 ms steps
 * took 7.8 s). Everything the page does on a timer waited on that: the fades (a song resumed
 * after a pause stayed nearly silent for seconds), Spotify's own hand-over at the end of a song,
 * and the work behind a command sent after a long pause.
 *
 * Only Chromium is told: the framework still sees the view's real visibility, so a hidden page
 * is never drawn (see [WebPlayerHost.attach]). Chromium reads the view's visibility through
 * [getVisibility], and the window's through [getWindowVisibility] when the view is set up and
 * [onWindowVisibilityChanged] afterwards (the callback alone left the page hidden).
 */
private class AwakeWebView(context: Context) : WebView(context) {
    override fun getVisibility(): Int = View.VISIBLE
    override fun getWindowVisibility(): Int = View.VISIBLE
    override fun onWindowVisibilityChanged(visibility: Int) = super.onWindowVisibilityChanged(View.VISIBLE)

    /**
     * Closing the app (swiped from recents, back) destroys the activity and its window, and the
     * view leaves the window with it. Chromium then marks the page's view and window hidden and
     * tears down its compositor: the music stopped for a moment until the player's repair
     * restarted it (reported on build 2022), while the foreground service was still there. The
     * departure is kept from Chromium, as the visibility above is: the page keeps playing as in
     * a window that is on screen, and Chromium is told for real when the view is attached
     * again (the new window needs a fresh attach) or destroyed.
     */
    private var detachHeld = false

    override fun onDetachedFromWindow() {
        detachHeld = true
    }

    override fun onAttachedToWindow() {
        releaseFromWindow()
        super.onAttachedToWindow()
    }

    /** Tells Chromium about a detach that was held back; no-op when none is pending. */
    fun releaseFromWindow() {
        if (!detachHeld) return
        detachHeld = false
        super.onDetachedFromWindow()
    }
}
