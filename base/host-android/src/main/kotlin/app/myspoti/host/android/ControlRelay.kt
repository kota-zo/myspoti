package app.myspoti.host.android

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import app.myspoti.spotify.bridge.IpFamily
import app.myspoti.spotify.bridge.RelayRoute
import app.myspoti.spotify.bridge.RelayRoutes
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.brotli.Brotli
import okhttp3.zstd.Zstd
import okio.BufferedSource
import okio.InflaterSource
import okio.Source
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends the Web Player's playback control requests (Spotify Connect commands, transfers,
 * device and track-playback state) from the app instead of the WebView.
 *
 * Every WebView request carries "X-Requested-With: <package>", and an app can no longer turn
 * that off (androidx.webkit's allow-list API is a no-op on current WebViews). Spotify
 * acknowledges Connect commands that carry it but never delivers them to the player, so
 * nothing plays. Tested on desktop Chrome: adding only that header to the command request
 * reproduces the silence, and removing it restores playback.
 *
 * The page's `fetch` is wrapped at document start. Matching requests go through
 * `MyspotiNet.postMessage` ([exposeToPlayer]), which repeats them with OkHttp using the page's own headers (its token,
 * client token, connection id), then hands the response back to the page.
 */
internal class ControlRelay(private val view: WebView, private val fingerprint: Fingerprint) {
    /** The page's player registered its playback device (it started, and takes commands). */
    @Volatile var onDeviceRegistered: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    /** The same client ([http]), pinned to one address family (see RelayRoutes); same pool. */
    private val v4Client by lazy { http.newBuilder().dns(FamilyDns(IpFamily.V4)).build() }
    private val v6Client by lazy { http.newBuilder().dns(FamilyDns(IpFamily.V6)).build() }
    private val routes get() = ROUTES

    fun install() {
        NetworkReport.logOnce(view.context.applicationContext)
        // Before Android 14: Cronet from Play services, ready before any refusal needs it.
        CronetStack.prepare(view.context)
        view.exposeToPlayer("MyspotiNet", ::onMessage)
        val documentStart = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStart) androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("https://open.spotify.com"))
        Logbook.log("control relay installed (document start: $documentStart)")
    }

    /** Fallback for WebViews without document-start scripts; harmless when already installed. */
    fun injectNow() = view.evaluateJavascript(SCRIPT, null)

    /**
     * The identity the page presents, as its own requests carry it (user agent, client hints,
     * accepted languages). The relay echoes these instead of hand-written constants, so a relayed
     * request cannot contradict the page it comes from. Sent from the page at document start; the
     * [WebPlayerHost] constants are only the fallback for the moment before it arrives.
     */
    @Volatile private var identity: JSONObject? = null

    /**
     * Until the page has said which identity it presents, the relay presents the one the WebView
     * was given ([Fingerprint]) and the phone's languages, so even the first requests agree with it.
     */
    private val fallbackLanguage: String by lazy {
        Fingerprint.acceptLanguage(android.os.LocaleList.getDefault().toLanguageTags().split(','))
    }

    /** The page's identity, for the audio proxy: the user agent and language it presents itself. */
    internal fun identityPair(): Pair<String, String>? {
        val page = identity ?: return null
        val userAgent = page.optString("userAgent").takeIf { it.isNotEmpty() } ?: return null
        return userAgent to page.optString("acceptLanguage")
    }

    /**
     * The name this device shows in Spotify's device lists: the one its owner gave the phone
     * (Paramètres → À propos → Nom de l'appareil), the same string Bluetooth and Quick Share show.
     * A phone appearing as "Web Player (Chrome)" on every other device is a phone that stands out;
     * under its own name it looks like what it is. Falls back to the model, then to a neutral name.
     */
    private val deviceName: String by lazy {
        val resolver = view.context.contentResolver
        fun setting(key: String): String? = runCatching {
            android.provider.Settings.Global.getString(resolver, key)
                ?: android.provider.Settings.Secure.getString(resolver, key)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        setting("device_name") ?: setting("bluetooth_name") ?: android.os.Build.MODEL.ifBlank { "Spotify" }
    }

    /** `{id, url, method, headers, body}` from the page's wrapped `fetch`. */
    private fun onMessage(json: String) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (m.optString("type") == "identity") {
            identity = m.optJSONObject("value")
            Logbook.log("page identity: ${identity.toString().take(320)}")
            return
        }
        if (m.optString("type") == "direct") {
            if (m.has("kept")) {
                Logbook.log("commands from the page: ${if (m.optBoolean("kept")) "kept, the music plays" else "dropped, nothing played in 10 s"}")
                app.myspoti.spotify.report.Refusal402.pageKept(m.optBoolean("kept"))
                // A page fetch answered 200 can still be dropped (X-Requested-With): the matrix says which.
                matrixPageLine?.let { line ->
                    matrixPageLine = null
                    Logbook.fact("402 matrix", "$line (page: ${if (m.optBoolean("kept")) "played" else "nothing played"})")
                }
            } else {
                Logbook.log("refused command sent again from the page -> ${m.optInt("status")}")
                app.myspoti.spotify.report.Refusal402.pageFetch(m.optInt("status"))
                // The matrix's last row: the same command through the page's own fetch.
                if (matrixPage) {
                    matrixPage = false
                    val line = "$matrixRows; page fetch=${m.optInt("status")}"
                    Logbook.log("402 matrix: $line")
                    Logbook.fact("402 matrix", line)
                    if (m.optInt("status") in 200..299) matrixPageLine = line
                }
            }
            return
        }
        val id = m.optInt("id", -1).takeIf { it >= 0 } ?: return
        val body = if (m.isNull("body")) null else m.optString("body")
        send(id, m.optString("url"), m.optString("method", "GET"), m.optJSONObject("headers") ?: JSONObject(), body)
    }

    private fun send(id: Int, url: String, method: String, given: JSONObject, body: String?) {
        if (!isRelayed(url)) {
            reply(id, 0, "{}", "")
            return
        }
        val built = relayHeaders(given, identity, fingerprint.userAgent, fallbackLanguage, fingerprint.secChUa)
        // The registration goes out as the real Web Player sends it, except for its name: the phone's
        // own (see [deviceName]) replaces "Web Player (Chrome)".
        val registration = body != null && app.myspoti.spotify.bridge.AdBlock.isDeviceRegistration(method, url)
        if (debuggable && body != null && url.contains("/connect-state/"))
            Logbook.log("connect-state body: ${body.take(500)}")
        if (registration) runCatching { JSONObject(body!!).optJSONObject("device")?.optString("device_id") }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { self = it }
        // The device the previous page left behind (app swiped away or killed before its unload
        // handler ran) is taken off the account alongside this registration, not after it: the
        // cluster the page joins next then lists this player alone.
        if (registration) self?.let { mine ->
            if (debuggable) Logbook.log("player device #${shortHash(mine)}")
            devices.registering(mine)?.let { (old, seq) ->
                staleOwn = old
                sayGoodbye(url, built, old, seq)
            }
        } else if (method != "DELETE") devices.sent(url, body)
        // 402 on some free accounts: which headers the page gave its commands (names only), so a
        // refused session can be set against a working one. Logged when the set changes.
        if (COMMAND.containsMatchIn(url)) {
            val names = headerNames(given)
            if (names != commandHeaders) {
                commandHeaders = names
                Logbook.log("command headers: $names")
            }
        }
        val sent = if (registration) {
            val rewritten = app.myspoti.spotify.bridge.AdBlock.withDeviceName(body!!, deviceName)
            if (debuggable && rewritten != body) Logbook.log("device registered as \"$deviceName\"")
            rewritten
        } else body
        val asked = android.os.SystemClock.uptimeMillis()
        // A refused command (or a refused registration) is sent again another way before the page
        // hears of it (see RelayRoutes): a Chromium network stack, then the other address family.
        val retriable = COMMAND.containsMatchIn(url) || registration
        val tried = ArrayList<RelayRoute>()
        // What each route answered, for the 402 matrix (the first refused command of the session).
        val rows = ArrayList<String>()
        var refusal: Answer? = null
        fun matrixDone(page: Boolean) {
            if (refusal == null || !COMMAND.containsMatchIn(url) || !MATRIX_RUN.compareAndSet(false, true)) return
            val chromium = routes.chromiumRoute()
            if (chromium == null) rows += if (android.os.Build.VERSION.SDK_INT >= 34) "HTTP_ENGINE=unavailable" else "CRONET=${CronetStack.status}"
            matrixRows = rows.joinToString("; ")
            if (page) matrixPage = true
            val line = matrixRows + if (page) "; page fetch=pending" else "; page fetch=not needed"
            Logbook.log("402 matrix (first refused command): $line")
            Logbook.fact("402 matrix", line)
        }
        val command = COMMAND.containsMatchIn(url)
        val kind = if (command) commandKind(url, body) else ""
        fun finish(answer: Answer) {
            matrixDone(page = answer.code == 402)
            if (command) app.myspoti.spotify.report.Refusal402.command(kind, answer.code, retried = refusal != null)
            handle(id, url, method, body, registration, asked, answer)
        }
        fun attempt(route: RelayRoute) {
            tried += route
            val began = android.os.SystemClock.uptimeMillis()
            fetch(route, url, method, built, sent) { answer, error ->
                val via = if (route == RelayRoute.DEFAULT) "" else " via $route"
                if (command) app.myspoti.spotify.report.Refusal402.attempt(kind, shortRoute(route), answer?.code, android.os.SystemClock.uptimeMillis() - began,
                    answer?.headers.orEmpty(), answer?.text.orEmpty())
                rows += "${routeLabel(route)}=${answer?.code ?: "failed (${error?.take(60)})"}"
                if (answer == null) {
                    Logbook.log("relay $method ${shortPath(url)} failed$via: $error")
                    val first = refusal
                    when {
                        first != null -> routes.next(tried, null)?.let { attempt(it) } ?: finish(first)
                        // A kept route the network no longer offers (IPv6 gone with the Wi-Fi): this
                        // request goes the default way; the route stays kept for the next ones.
                        route != RelayRoute.DEFAULT && RelayRoute.DEFAULT !in tried -> attempt(RelayRoute.DEFAULT)
                        else -> reply(id, 0, "{}", "")
                    }
                    return@fetch
                }
                val refused = answer.code == 402 || (registration && answer.code == 403)
                if (refused && retriable) {
                    if (refusal == null) {
                        refusal = answer
                        logRefusal(answer, body)
                        Logbook.fact("ads", adsMode())
                    }
                    val next = routes.next(tried, answer.family)
                    if (next != null) {
                        Logbook.log("relay $method ${shortPath(url)} -> ${answer.code}$via (${answer.family ?: "family unknown"}): sent again via $next")
                        attempt(next)
                        return@fetch
                    }
                }
                if (refusal != null) {
                    if (answer.code in 200..299) {
                        routes.won(route)
                        Logbook.log("relay: $route answered ${answer.code} where ${tried.first()} was refused: kept for this session")
                        Logbook.fact("relay route", "$route (taken after a refusal on ${tried.first()})")
                        if (command) checkAudio(shortRoute(route))
                    } else Logbook.log("relay: refused on every route (${tried.joinToString()})")
                }
                finish(answer)
            }
        }
        attempt(routes.kept)
    }

    /** A relayed request's answer, whichever stack carried it. */
    private class Answer(
        val code: Int,
        val headers: List<Pair<String, String>>,
        val text: String,
        val protocol: String,
        val tls: String?,
        val family: IpFamily?,
    ) {
        fun header(name: String) = headers.lastOrNull { it.first.equals(name, true) }?.second
    }

    private fun clientFor(route: RelayRoute): OkHttpClient = when (route) {
        RelayRoute.IPV4 -> v4Client
        RelayRoute.IPV6 -> v6Client
        else -> http
    }

    /** Sends one relayed request over [route]; [done] gets the answer, or null and why it failed. */
    private fun fetch(route: RelayRoute, url: String, method: String, headers: Headers, sent: String?, done: (Answer?, String?) -> Unit) {
        if (route == RelayRoute.HTTP_ENGINE && android.os.Build.VERSION.SDK_INT >= 34) {
            PlatformStack.fetch(view.context.applicationContext, url, method, headers, sent) { code, heads, text, protocol, error ->
                done(if (error == null) Answer(code, heads, text, protocol, null, null) else null, error)
            }
            return
        }
        if (route == RelayRoute.CRONET) {
            CronetStack.fetch(url, method, headers, sent) { code, heads, text, protocol, error ->
                done(if (error == null) Answer(code, heads, text, protocol, null, null) else null, error)
            }
            return
        }
        // Bytes, not a String body: OkHttp would append "; charset=utf-8" to the content type.
        val payload = if (method == "GET" || method == "HEAD") null else (sent ?: "").toByteArray().toRequestBody(null)
        val tag = RelayTag()
        val request = Request.Builder().url(url).headers(headers).method(method, payload).tag(RelayTag::class.java, tag).build()
        clientFor(route).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done(null, e.message)

            override fun onResponse(call: Call, response: Response) {
                val answer = runCatching {
                    response.use {
                        Answer(it.code, it.headers.map { h -> h.first to h.second }, it.body.string(), it.protocol.toString(),
                            it.handshake?.tlsVersion?.javaName, tag.family)
                    }
                }
                done(answer.getOrNull(), answer.exceptionOrNull()?.message)
            }
        })
    }

    // B3 (402 on some free accounts): what a tester's report must carry to tell the causes apart —
    // the refusal's own headers, and what the refused command asked (the license the page put in
    // it, a picked song or not). No ids, no cookies.
    private fun logRefusal(answer: Answer, body: String?) {
        val heads = answer.headers.map { it.first }.distinct()
            .filterNot { n -> n.equals("set-cookie", true) || n.startsWith("authorization", true) }
            .joinToString(", ") { n -> "$n=${answer.header(n)?.take(80)}" }
        Logbook.log("refusal headers (${answer.protocol}, ${answer.tls ?: "no tls"}, ${answer.family ?: "family unknown"}): $heads")
        if (body != null) Logbook.log("refused command: ${commandSummary(body)}")
    }

    /** What the page gets back, and what the logs keep of it. */
    private fun handle(id: Int, url: String, method: String, body: String?, registration: Boolean, asked: Long, it: Answer) {
        val out = JSONObject()
        it.headers.forEach { (name, value) -> out.put(name, value) }
        val text = it.text
        val net = android.os.SystemClock.uptimeMillis() - asked
        val path = shortPath(url)
        // Steady streams answered 2xx (play reports, playback state and volume PUTs: hundreds per
        // report, never read in an analysis) are logged once a minute; failures always.
        val steady = STEADY.find(path)?.value
        if (steady != null && it.code in 200..299) Logbook.logEvery("relay $method $steady", 60_000, "relay $method $path -> ${it.code} in $net ms")
        else Logbook.log("relay $method $path -> ${it.code} in $net ms")
        if (it.code >= 400) Logbook.log("relay error body: ${text.take(400)}")
        if (it.code in 200..299 && method == "POST" && url.substringBefore('?').endsWith("/track-playback/v1/devices")) onDeviceRegistered?.invoke()
        if (it.code == 402 && !(COMMAND.containsMatchIn(url) || registration)) logRefusal(it, body)
        if (registration) Regex("\"effective_license\"\\s*:\\s*\"([^\"]*)\"").find(text)
            ?.let { m -> Logbook.log("registration: effective_license=${m.groupValues[1]}") }
        if (registration) app.myspoti.spotify.report.Refusal402.registered(it.code)
        if (registration && it.code in 200..299) Logbook.log("registration answer: ${registrationSummary(text)}")
        // The page's connect-state PUT (joining the cluster): its status and its answer's header names.
        if (method == "PUT" && url.contains("/connect-state/v1/devices/"))
            app.myspoti.spotify.report.Refusal402.connectStatePut(it.code, it.headers.map { h -> h.first.lowercase() }.distinct().sorted().joinToString(","))
        if (registration && it.code in 200..299) runCatching { JSONObject(text).optLong("initial_seq_num", 0) }.getOrNull()?.let(devices::answered)
        if (method == "DELETE" && it.code in 200..299 && url.contains("/track-playback/v1/devices/")) devices.deregistered(url)
        if (registration) Logbook.fact("registration", "${it.code}" + (Regex("\"effective_license\"\\s*:\\s*\"([^\"]*)\"").find(text)?.let { m -> " effective_license=${m.groupValues[1]}" } ?: ""))
        // What Spotify holds for this account's devices (this player's license, whether
        // it may play, another device active): the cluster comes back on joining and
        // on every cluster read, the one the page makes after a refused command.
        if (it.code in 200..299 && (url.contains("/connect-state/v1/cluster") || (method == "PUT" && url.contains("/connect-state/v1/devices/"))))
            clusterSummary(text, self, staleOwn)?.let { s ->
                // The same cluster again (every state PUT and cluster read returns it) is not logged twice.
                if (s != lastCluster) { lastCluster = s; Logbook.log("cluster: $s") }
                clusterBrief(text, self, staleOwn)?.let(app.myspoti.spotify.report.Refusal402::cluster)
                Logbook.fact("devices", s)
                // Debug builds: which devices these are, as short hashes (a ghost left by a
                // previous start shows under the hash its own start logged).
                if (debuggable) runCatching {
                    val c = JSONObject(text).let { j -> j.optJSONObject("cluster") ?: j }
                    Logbook.log("cluster devices: " + c.getJSONObject("devices").keys().asSequence().joinToString(" ") { k -> "#${shortHash(k)}" })
                }
            }
        if (debuggable && registration) Logbook.log("registration answered: ${text.take(500)}")
        if (debuggable && AD_MARK.containsMatchIn(text)) keepAdSample(method, url, body, text)
        // An ad in the queue Spotify sends: the song before it goes on to the next song
        // instead, before the page sees the queue (see AdBlock.withoutAds).
        val given = if (url.contains("/track-playback/")) app.myspoti.spotify.bridge.AdBlock.withoutAds(text) else null
        given?.second?.forEach { note -> Logbook.log("ad left out: $note"); ADS_LEFT_OUT.incrementAndGet(); app.myspoti.spotify.report.Refusal402.adStateLeftOut() }
        // Marked as holding an ad, yet left as it was: why (which marker, what kept it), once per reason.
        if (given == null && url.contains("/track-playback/") && AD_MARK.containsMatchIn(text))
            app.myspoti.spotify.bridge.AdBlock.whyKept(text)?.takeIf { it != lastAdKept }?.let { why -> lastAdKept = why; Logbook.log("ad kept in the queue: $why") }
        val sent = given?.first ?: text
        // The songs' media manifests, for extensions to read (spotify.media).
        if (it.code in 200..299 && url.contains("/track-playback/")) app.myspoti.spotify.bridge.MediaManifests.record(sent)
        // Both ways on from this song lead to the ad: the player plays the next song
        // just before this one ends (AdBlock.adAhead).
        if (url.contains("/track-playback/")) app.myspoti.spotify.bridge.AdBlock.adAhead(sent)?.let { a ->
            if (app.myspoti.spotify.bridge.AdBlock.ahead.value != a) Logbook.log("ad ahead: ${a.nextUri} is played before the end")
            app.myspoti.spotify.bridge.AdBlock.ahead.value = a
        }
        reply(id, it.code, out.toString(), sent)
    }

    private fun reply(id: Int, status: Int, headersJson: String, body: String) {
        val posted = android.os.SystemClock.uptimeMillis()
        // Quoted here, on the network thread: a large reply (a track-playback state machine) was
        // quoted on the main thread, which also draws the app and runs every tap behind it.
        val script = "window.__mspNetDone && window.__mspNetDone($id, $status, ${JSONObject.quote(headersJson)}, ${JSONObject.quote(body)})"
        main.post { replyNow(id, script, posted) }
    }

    private fun replyNow(id: Int, script: String, posted: Long) {
        val waited = android.os.SystemClock.uptimeMillis() - posted
        if (waited > 30) Logbook.log("relay reply #$id waited $waited ms for the main thread")
        view.evaluateJavascript(script, null)
    }

    /** The last page's playback device, kept across starts (see [PlayerDeviceRecord]). */
    private val devices = PlayerDeviceRecord(java.io.File(view.context.filesDir, "spotify/player-device"))

    /** The page's own goodbye for a device it left: the same DELETE, with that page's headers' twin. */
    private fun sayGoodbye(registrationUrl: String, headers: Headers, old: String, seq: Long) {
        val request = Request.Builder()
            .url(PlayerDeviceRecord.deviceUrl(registrationUrl, old))
            .headers(headers)
            .delete(PlayerDeviceRecord.goodbye(seq).toByteArray().toRequestBody(null))
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Logbook.log("previous player device: goodbye failed: ${e.message}")
            override fun onResponse(call: Call, response: Response) = response.use {
                Logbook.log("previous player device${if (debuggable) " #${shortHash(old)}" else ""} taken off -> ${it.code}")
                if (it.code >= 400) Logbook.log("goodbye error body: ${it.body.string().take(200)}")
            }
        })
    }

    private fun shortHash(id: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).take(3).joinToString("") { "%02x".format(it) }

    /** This page's playback device id, read from its registration: never logged, only compared. */
    @Volatile private var self: String? = null

    /** The device id the previous start left registered (see [PlayerDeviceRecord]): only compared. */
    @Volatile private var staleOwn: String? = null

    @Volatile private var lastCluster: String? = null
    /** The last reason an ad-marked reply was left as it was (logged once per reason). */
    @Volatile private var lastAdKept: String? = null

    /**
     * A refused command another route answered 2xx: whether the page's media really plays 10 s
     * later (a 2xx alone proved nothing: the page path's 200s were all dropped, 402-ANALYSIS fact 2).
     */
    private fun checkAudio(route: String) = main.postDelayed({
        view.evaluateJavascript(PLAYING) { r ->
            val started = r == "true"
            Logbook.log("audio after the $route retry: ${if (started) "playing" else "nothing played"} within 10 s")
            app.myspoti.spotify.report.Refusal402.audioAfterRetry(route, started)
        }
    }, 10_000)

    /** The 402 matrix's rows once its relay part is done, while the page's own fetch is awaited. */
    @Volatile private var matrixRows = ""
    @Volatile private var matrixPage = false
    @Volatile private var matrixPageLine: String? = null

    /** The ads mode, for the 402 report: what this app does to ads (it has no setting for it). */
    private fun adsMode() = "blocked (page ad managers off, ${app.myspoti.spotify.bridge.AdBlock.blockedHosts.size} ad hosts blocked, " +
        "ad states left out this session=${ADS_LEFT_OUT.get()}, ad ahead=${app.myspoti.spotify.bridge.AdBlock.ahead.value != null})"
    @Volatile private var commandHeaders: String? = null

    private val debuggable = view.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Debug builds: a playback response that carries an ad, kept in cache/ad-samples for study. */
    private fun keepAdSample(method: String, url: String, sent: String?, received: String) = runCatching {
        val dir = java.io.File(view.context.cacheDir, "ad-samples").apply { mkdirs() }
        java.io.File(dir, "${System.currentTimeMillis()}.json").writeText(
            JSONObject().put("method", method).put("url", url.substringBefore('?')).put("sent", sent ?: "").put("received", received).toString())
        Logbook.log("relay: ad in $method ${shortPath(url)} (sample kept)")
    }

    /** A Connect command's shape, without ids: endpoint, license, picked song, origin. */
    private fun commandSummary(body: String): String = runCatching {
        val c = JSONObject(body).optJSONObject("command") ?: JSONObject(body)
        val opts = c.optJSONObject("options")
        listOf(
            "endpoint=${c.optString("endpoint", "?")}",
            "license=${opts?.optString("license", "<none>") ?: "<no options>"}",
            "skip_to=${opts?.has("skip_to") == true}",
            "origin=${c.optJSONObject("play_origin")?.optString("feature_identifier", "?") ?: "?"}",
            "transfer=${c.has("transfer_options") || JSONObject(body).has("transfer_options")}",
        ).joinToString(" ")
    }.getOrDefault("unreadable")

    /**
     * The path with device ids hidden. A command's `from/<id>/to/<id>` keeps one fact: whether it
     * targets this player itself (`to/self`) or another device (`to/other`) — a refusal of a
     * command sent to the official app on the same account reads very differently from one sent
     * to this player.
     */
    private fun shortPath(url: String): String {
        val path = url.substringBefore('?').substringAfter(".com")
        val ids = Regex("/from/([0-9a-f]{20,})/to/([0-9a-f]{20,})").find(path)
        if (ids != null) {
            val target = if (ids.groupValues[1] == ids.groupValues[2]) "self" else "other"
            return path.replaceRange(ids.range, "/from/…/to/$target").replace(Regex("[0-9a-f]{20,}"), "…")
        }
        return path.replace(Regex("[0-9a-f]{20,}"), "…")
    }

    companion object {
        /**
         * No cookie jar: the real Web Player's requests to spclient carry no cookie (its `fetch` is
         * cross-origin with the default `credentials: same-origin`; 02-playback capture). The WebView's
         * cookies the relay sent from 2.0.2-beta.9 on (sp_dc, sp_t, sp_key...) were a shape no
         * browser's command has.
         *
         * Chrome's Accept-Encoding ([ACCEPT_ENCODING]) comes from [DECODING], which also decodes the
         * answer (Spotify answers track-playback in zstd, connect-state in gzip or br).
         *
         * One client for every relay (a page rebuilt after a renderer crash gets a new relay, not a
         * new pool), so that [resetConnections] reaches the one in use. A dead connection fails in
         * seconds: with a 30 s read timeout, a command sent on one (the phone moving between Wi-Fi
         * and mobile data, a connection the mobile network dropped while idle) waited that long
         * before anything tried again. Spotify answers these requests in well under a second, so
         * 10 s without a byte is a dead connection; the ping finds one before a command does.
         */
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .addInterceptor(DECODING)
                .eventListener(RelayEvents)
                .build()
        }

        /**
         * The phone moved to another network: the idle connections belong to the old one, and the
         * next request opens a fresh one instead of waiting on them. Not from the main thread
         * (closing a socket can write to it).
         */
        fun resetConnections() = http.connectionPool.evictAll()

        /**
         * The page's own API calls. Every request the WebView makes carries
         * "X-Requested-With: <our package>" — no browser does that, and it was measured on the wire
         * riding on the telemetry and on the audio files themselves. Relaying through OkHttp is what
         * removes it; the playback commands were only the first ones (that header alone was enough to
         * silence them). One rule on Spotify's side is all it takes to flag every install.
         *
         * The playback commands and states are on the list, and the play reports ([REPORTS]).
         * `gabo-receiver-service` (product analytics, it counts no listen) leaves from the WebView,
         * as in 2.0.1. Widening this to everything else broke the page (protobuf and binary
         * bodies, see the notes).
         *
         * Login and consent hosts stay out: their flows post forms and redirect, and a relay would
         * have to reproduce every step exactly to sign in.
         */
        private val RELAYED = Regex("^https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.spotify\\.com/(connect-state|track-playback)/v1/")
        private val NOT_RELAYED = Regex("^https://(accounts|challenge|www)\\.spotify\\.com/")

        /**
         * The play reports: `melody/v1/msg/batch`, base64 JSON text that tells Spotify what was
         * played and for how long (`jssdk_playback_start`, `track_stream_verification` with the
         * milliseconds really heard). With the playback states, they are what puts a song in the
         * account's history and Wrapped; they left the WebView with the package-name header on
         * them, and plays streamed here were not counted. Only the batches: `melody/v1/time` and
         * `melody/v1/license_url` (the Widevine licence's address) stay with the WebView.
         *
         * The page relays them only once its playback device is registered (see [SCRIPT]): in
         * 2.0.2-beta.1, which relayed them from the start along with a Widevine probe at boot, the
         * page never registered its device on a free account. Until the registration is answered
         * the page boots exactly as 2.0.1 did.
         */
        private val REPORTS = Regex("^https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.spotify\\.com/melody/v1/msg/")

        fun isRelayed(url: String) = (RELAYED.containsMatchIn(url) || REPORTS.containsMatchIn(url)) && !NOT_RELAYED.containsMatchIn(url)

        /**
         * The route kept for this session, shared by every page and WebView of the process. The
         * Chromium stack a refusal goes to first: the platform's on Android 14+, else Cronet once
         * Play services has installed it.
         */
        private val ROUTES = RelayRoutes {
            when {
                android.os.Build.VERSION.SDK_INT >= 34 -> RelayRoute.HTTP_ENGINE
                CronetStack.available -> RelayRoute.CRONET
                else -> null
            }
        }

        /** The 402 matrix runs once per process, on the first refused command. */
        private val MATRIX_RUN = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Ad states [app.myspoti.spotify.bridge.AdBlock.withoutAds] left out this session. */
        private val ADS_LEFT_OUT = java.util.concurrent.atomic.AtomicInteger(0)

        /** A route's name in the 402 summary. */
        internal fun shortRoute(route: RelayRoute) = when (route) {
            RelayRoute.DEFAULT -> "okhttp"
            RelayRoute.IPV4 -> "okhttp-v4"
            RelayRoute.IPV6 -> "okhttp-v6"
            else -> route.name
        }

        /** Whether one of the page's media elements plays (the same test as the page script's `plays`). */
        private const val PLAYING = "(() => { const m = window.__mspMedia; return !!m && [...m].some(e => !e.paused && !e.ended && e.readyState > 2); })()"

        /** Relayed requests that run all along (play reports, playback state, volume): their 2xx answers are rate-limited. */
        private val STEADY = Regex("^/melody/v1/msg/batch|^/track-playback/v1/devices/…/(state|volume)$")

        /** A command's kind for the 402 summary: "transfer", or its endpoint. */
        internal fun commandKind(url: String, body: String?): String =
            if (url.contains("/connect/transfer/")) "transfer"
            else runCatching { (JSONObject(body ?: "").optJSONObject("command") ?: JSONObject(body ?: "")).optString("endpoint") }
                .getOrNull()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: "command"

        /**
         * The cluster for the 402 summary: how many devices, their types, what each is to this
         * player (self, stale-own, foreign-webplayer, foreign) and whether a player state exists.
         */
        internal fun clusterBrief(text: String, self: String?, staleOwn: String?): String? {
            val full = clusterSummary(text, self, staleOwn) ?: return null
            val c = runCatching { JSONObject(text).let { j -> j.optJSONObject("cluster") ?: j } }.getOrNull() ?: return null
            val devices = c.optJSONObject("devices") ?: return null
            val ownSoftware = self?.let { devices.optJSONObject(it)?.optString("device_software_version") }?.takeIf { it.isNotEmpty() }
            val types = sortedMapOf<String, Int>()
            val kinds = sortedMapOf<String, Int>()
            devices.keys().forEach { id ->
                val d = devices.optJSONObject(id) ?: JSONObject()
                types.merge(d.optString("device_type").ifEmpty { "?" }, 1, Int::plus)
                val kind = when {
                    id == self -> "self"
                    staleOwn != null && id == staleOwn -> "stale-own"
                    ownSoftware != null && d.optString("device_software_version") == ownSoftware -> "foreign-webplayer"
                    else -> "foreign"
                }
                kinds.merge(kind, 1, Int::plus)
            }
            val state = Regex("player_state=(absent|present\\([^)]*\\))").find(full)?.groupValues?.get(1) ?: "?"
            val active = Regex("active=(\\w+)").find(full)?.groupValues?.get(1) ?: "?"
            return "devices=${devices.length()} types=${types.entries.joinToString(",") { (k, v) -> "$k×$v" }} " +
                "kinds=${kinds.entries.joinToString(",") { (k, v) -> "$k×$v" }} active=$active player_state=$state"
        }

        internal fun routeLabel(route: RelayRoute) = when (route) {
            RelayRoute.DEFAULT -> "okhttp(no cookies, chrome headers)"
            RelayRoute.IPV4 -> "okhttp-v4"
            RelayRoute.IPV6 -> "okhttp-v6"
            else -> route.name
        }

        /**
         * Chrome's own Accept-Encoding on spclient (02-playback capture), in its order. OkHttp's
         * transparent gzip covers gzip alone: [DECODING] sends this list and decodes each of them
         * (Spotify answers track-playback in zstd, connect-state in gzip or br).
         */
        internal const val ACCEPT_ENCODING = "gzip, deflate, br, zstd"

        /** HTTP's `deflate`: zlib-wrapped, as java.util.zip reads it by default. */
        private object Deflate : CompressionInterceptor.DecompressionAlgorithm {
            override val encoding = "deflate"
            override fun decompress(compressedSource: BufferedSource): Source = InflaterSource(compressedSource, java.util.zip.Inflater())
        }

        /** Chrome's encodings, each with its decoder, in [ACCEPT_ENCODING]'s order. */
        internal val DECODERS: List<CompressionInterceptor.DecompressionAlgorithm> = listOf(Gzip, Deflate, Brotli, Zstd)

        /** A zstd frame of "ok": whether zstd's native decoder loads on this phone. */
        private val ZSTD_PROBE = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 0x04, 0x58, 0x11, 0x00, 0x00, 0x6f, 0x6b, 0xf9.toByte(), 0x5c, 0x14, 0x16)

        /**
         * The decoding interceptor: Chrome's list when zstd's native decoder loads, else the same
         * list without zstd (an answer that could not be read is worse than a shorter list).
         */
        private val DECODING: CompressionInterceptor by lazy {
            val zstd = runCatching {
                okio.Buffer().write(ZSTD_PROBE).let { Zstd.decompress(it) }.let { okio.Buffer().apply { writeAll(it) } }.readUtf8() == "ok"
            }.getOrElse { e -> Logbook.log("relay: zstd decoder unavailable (${e.javaClass.simpleName}): Accept-Encoding without zstd"); false }
            CompressionInterceptor(*DECODERS.filter { zstd || it !== Zstd }.toTypedArray())
        }

        /** Header names OkHttp or the page's `fetch` must not carry over (transport, cookies, the WebView's mark). */
        private val NOT_CARRIED = setOf("host", "content-length", "connection", "accept-encoding", "x-requested-with", "cookie")

        /**
         * The headers a relayed request carries: the page's own (its token, client token, app
         * version...), then what Chrome adds by itself, which the page's `fetch` cannot set
         * (forbidden names in JS) and OkHttp does not add either. [page] is the identity the page
         * presents (user agent, languages, client hints); the fallbacks only stand in for it until
         * it arrives. Matched against desktop Chrome's command request in ControlRelayTest.
         */
        internal fun relayHeaders(given: JSONObject, page: JSONObject?, userAgent: String, language: String, secChUa: String): Headers {
            val headers = Headers.Builder()
            given.keys().forEach { key ->
                if (key.lowercase() !in NOT_CARRIED) runCatching { headers.add(key, given.getString(key)) }
            }
            fun fromPage(name: String, fallback: String) = page?.optString(name)?.takeIf { it.isNotEmpty() } ?: fallback
            headers["User-Agent"] = fromPage("userAgent", userAgent)
            headers["Origin"] = "https://open.spotify.com"
            headers["Referer"] = "https://open.spotify.com/"
            if (headers["Accept"] == null) headers["Accept"] = "*/*"
            // RFC 9218: what Chromium sends with a fetch() from a page.
            if (headers["Priority"] == null) headers["Priority"] = "u=1, i"
            headers["Sec-Fetch-Site"] = "same-site"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Dest"] = "empty"
            // Values taken from the page, not written by hand: whichever language and client hints it
            // presents, the relayed request presents the same.
            headers["Accept-Language"] = fromPage("acceptLanguage", language)
            headers["sec-ch-ua"] = fromPage("brands", secChUa)
            headers["sec-ch-ua-mobile"] = fromPage("mobile", "?0")
            headers["sec-ch-ua-platform"] = "\"${fromPage("platform", "Linux")}\""
            return headers.build()
        }

        private val COMMAND = Regex("/connect-state/v1/(player/command|connect/transfer)/")

        /** The header names a request carries, lowercased and sorted: never their values. */
        internal fun headerNames(given: JSONObject): String =
            given.keys().asSequence().map { it.lowercase() }.sorted().joinToString(",")

        /** Keys that can hold an id, a token or an address: named, never shown. */
        private val PRIVATE_KEY = Regex("id|token|ref|ip|url|name|seq", RegexOption.IGNORE_CASE)

        /**
         * The registration's answer without ids: every key, with the value of the short plain ones
         * (`effective_license`, `supports_observing`, the format filter...). A refused account and a
         * working one can then be told apart by what Spotify granted their player.
         */
        internal fun registrationSummary(text: String): String = runCatching {
            val j = JSONObject(text)
            j.keys().asSequence().sorted().joinToString(" ") { k ->
                val v = j.opt(k)
                when {
                    PRIVATE_KEY.containsMatchIn(k) -> k
                    v is Boolean || v is Number -> "$k=$v"
                    v is String && v.length <= 24 -> "$k=$v"
                    v is org.json.JSONArray && v.length() <= 8 && (0 until v.length()).all { i -> v.opt(i) is String && v.optString(i).length <= 24 } ->
                        "$k=[${(0 until v.length()).joinToString("|") { i -> v.optString(i) }}]"
                    else -> k
                }
            }.take(500)
        }.getOrDefault("unreadable")

        /**
         * The cluster without ids or names: each device's type, license, whether it may play and
         * take commands, which one is this player (`self`) and which one is active; then the
         * player state's restrictions that hold a reason. Null when the body is not a cluster.
         *
         * 402 diagnostics: whether the cluster holds a player state at all (the failing accounts'
         * clusters had none, 402-ANALYSIS fact 4), and what each other device is: `stale-own` when
         * it is the device the previous start left ([staleOwn], from PlayerDeviceRecord),
         * `foreign-webplayer` for another device running this player's software (an older ghost of
         * this app, or the user's own Web Player in a browser), else `foreign`.
         */
        internal fun clusterSummary(text: String, self: String?, staleOwn: String? = null): String? = runCatching {
            val j = JSONObject(text)
            val c = j.optJSONObject("cluster") ?: j
            val devices = c.optJSONObject("devices") ?: return null
            val active = c.optString("active_device_id")
            val ownSoftware = self?.let { devices.optJSONObject(it)?.optString("device_software_version") }?.takeIf { it.isNotEmpty() }
            val list = devices.keys().asSequence().sortedBy { if (it == self) "" else it }.map { id ->
                val d = devices.optJSONObject(id) ?: JSONObject()
                val caps = d.optJSONObject("capabilities")
                val kind = when {
                    id == self -> "self"
                    staleOwn != null && id == staleOwn -> "stale-own"
                    ownSoftware != null && d.optString("device_software_version") == ownSoftware -> "foreign-webplayer"
                    else -> "foreign"
                }
                val marks = listOfNotNull(kind.takeIf { self != null || it != "foreign" }, "active".takeIf { id == active && active.isNotEmpty() })
                val facts = listOfNotNull(
                    d.optString("device_type").ifEmpty { "?" },
                    "license=${d.optString("license").ifEmpty { "-" }}",
                    "can_play=${d.opt("can_play") ?: "-"}",
                    caps?.opt("is_controllable")?.let { v -> "controllable=$v" },
                    caps?.opt("can_be_player")?.let { v -> "player=$v" },
                    caps?.opt("hidden")?.takeIf { v -> v == true }?.let { "hidden" },
                    (d.opt("device_software_version") as? String)?.takeIf { v -> v.isNotEmpty() }?.take(24),
                )
                (marks + facts).joinToString(" ")
            }.toList()
            val activeWhere = when {
                active.isEmpty() -> "none"
                active == self -> "self"
                devices.has(active) -> "other"
                else -> "unlisted"
            }
            val restrictions = c.optJSONObject("player_state")?.optJSONObject("restrictions")?.let { r ->
                r.keys().asSequence().sorted().mapNotNull { k ->
                    val reasons = r.optJSONArray(k) ?: return@mapNotNull null
                    if (reasons.length() == 0) null
                    else "${k.removePrefix("disallow_").removeSuffix("_reasons")}=${(0 until reasons.length()).joinToString("|") { i -> reasons.optString(i) }}"
                }.joinToString(",")
            }.orEmpty()
            val state = c.optJSONObject("player_state")
            val playerState = when {
                state == null || state.length() == 0 -> "absent"
                else -> {
                    val context = state.optString("context_uri").split(':').getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "none"
                    val track = state.optJSONObject("track")?.optString("uri")?.isNotEmpty() == true
                    "present(context=$context track=${if (track) "yes" else "no"} restrictions=${if (restrictions.isEmpty()) "none" else "some"})"
                }
            }
            ("devices=${list.size} active=$activeWhere self_listed=${self != null && devices.has(self)} player_state=$playerState " +
                list.joinToString("; ", "[", "]") +
                (if (restrictions.isNotEmpty()) " restrictions: $restrictions" else "")).take(700)
        }.getOrNull()

        private val AD_MARK = Regex("\"is_advertisement\"\\s*:\\s*\"?true|spotify:ad:|\"ad_id\"")

        /** Wraps window.fetch; the same pattern as [RELAYED]. */
        private val SCRIPT = """
            (() => {
              if (window.__mspNetInstalled || !window.MyspotiNet) return;
              window.__mspNetInstalled = true;
              // The identity this page really presents, read from the browser itself and handed to
              // the host, which echoes it on every relayed request. Written by hand before, the
              // relayed headers contradicted the page's own (a grease brand that read
              // "Not:A-Brand" against the page's "Not?A_Brand", and "en" against its "fr-CH"):
              // a client whose headers disagree with its page is what a server-side gate can spot.
              try {
                const d = navigator.userAgentData || {};
                const langs = (navigator.languages && navigator.languages.length) ? navigator.languages : [navigator.language];
                window.MyspotiNet.postMessage(JSON.stringify({ type: 'identity', value: {
                  userAgent: navigator.userAgent,
                  brands: (d.brands || []).map(b => '"' + b.brand + '";v="' + b.version + '"').join(', '),
                  mobile: d.mobile === true ? '?1' : '?0',
                  platform: d.platform || 'Linux',
                  // Chromium's own shape: the first language plain, then q=0.9, 0.8, ...
                  acceptLanguage: langs.map((l, i) => i === 0 ? l : l + ';q=0.' + Math.max(1, 10 - i)).join(','),
                }}));
              } catch (e) { /* the host keeps its fallback identity */ }
              // The player's media elements, often never added to the document: the bridge
              // silences them during an ad ("media.mute") or while the song is unheard here
              // ("media.hush"); one started then starts silent.
              // Silent is a tiny volume, never muted: Chromium pauses muted media of a hidden page.
              const media = new Set();
              window.__mspMedia = media;
              const play = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () {
                media.add(this);
                if (window.__mspMuted || window.__mspHushed) { this.muted = false; this.volume = 0.001; }
                return play.apply(this, arguments);
              };
              const relayed = /^https:\/\/([a-z0-9-]+\.)*spotify\.com\/(connect-state|track-playback)\/v1\//;
              const notRelayed = /^https:\/\/(accounts|challenge|www)\.spotify\.com\//;
              // The play reports (REPORTS on the host side), relayed only once this page's device
              // is registered: until then the page boots exactly as in 2.0.1.
              const reports = /^https:\/\/([a-z0-9-]+\.)*spotify\.com\/melody\/v1\/msg\//;
              const registration = /^https:\/\/([a-z0-9-]+\.)*spotify\.com\/track-playback\/v1\/devices(\?|$)/;
              let registered = false;
              const pending = new Map();
              let seq = 0;
              const original = window.fetch;
              const command = /player\/command|connect\/transfer/;
              // A refused play or transfer (free accounts in some countries): the app tries another way.
              // late: the command was acknowledged but nothing played (see trust): the host judges it
              // more than its usual few seconds after the play.
              // kind: what was refused (a transfer, or the command's endpoint: play, seek_to...). The
              // app stops trying other ways once two kinds were refused: the account is refused everything.
              const kindOf = (url, body) => {
                if (/connect\/transfer/.test(url)) return 'transfer';
                try { const c = JSON.parse(body).command; return (c && c.endpoint) || 'command'; } catch (e) { return 'command'; }
              };
              const tell = (status, url, late, body) => {
                if ((status === 402 || status === 403) && command.test(url) && window.MyspotiHost) {
                  try { window.MyspotiHost.postMessage(JSON.stringify({ type: 'refused', status, late: !!late, kind: kindOf(url, body), path: url.split('?')[0].replace(/^.*\/v1\//, '').replace(/\/from\/.*$/, '') })); } catch (e) { /* host gone */ }
                }
              };
              const note = (value) => { try { window.MyspotiNet.postMessage(JSON.stringify(Object.assign({ type: 'direct' }, value))); } catch (e) { /* host gone */ } };
              // Some free accounts get every relayed command refused (402 COMMAND_FAILED) while the
              // same account plays in apps that send them from the page itself (Spotui): a refused
              // command is sent once more that way, and the page's commands keep going that way if
              // it took. Kept only if something plays within 10 s: a command sent from the page can
              // be acknowledged and never delivered (X-Requested-With, see above).
              const plays = () => [...media].some(m => !m.paused && !m.ended && m.readyState > 2);
              const trust = (url, body) => {
                if (window.__mspDirect) return;
                window.__mspDirect = true;
                setTimeout(() => {
                  const kept = plays();
                  if (!kept) window.__mspDirect = false;
                  note({ kept });
                  // Acknowledged and never delivered: the page showed a song at 0:00 that never
                  // started (reported after 2.0.1: "I can press play, the clock moves, nothing
                  // plays"). Told as a refusal, so the app's fallbacks run and, failing all of
                  // them, the user is told instead of left on a silent player.
                  if (!kept) tell(402, url, true, body);
                }, 10000);
              };
              const direct = (url, init) => original.call(window, url, init).then(r => { tell(r.status, url, false, init && init.body); return r; });
              window.__mspNetDone = (id, status, headers, body) => {
                const p = pending.get(id);
                if (!p) return;
                pending.delete(id);
                if (!status) return p.reject(new TypeError('Failed to fetch'));
                if (status >= 200 && status < 300 && p.method === 'POST' && registration.test(p.url)) registered = true;
                const relayedAnswer = () => {
                  const noBody = status === 204 || status === 205 || status === 304;
                  return new Response(noBody ? null : body, { status, headers: JSON.parse(headers) });
                };
                if (status === 402 && command.test(p.url) && !window.__mspDirect) {
                  original.call(window, p.url, p.init).then(r => {
                    note({ status: r.status });
                    const sentBody = p.init && p.init.body;
                    if (r.ok) trust(p.url, sentBody); else tell(r.status, p.url, false, sentBody);
                    p.resolve(r);
                  }, () => { tell(status, p.url, false, p.init && p.init.body); p.resolve(relayedAnswer()); });
                  return;
                }
                tell(status, p.url, false, p.init && p.init.body);
                p.resolve(relayedAnswer());
              };
              window.fetch = function (input, init) {
                const url = typeof input === 'string' ? input : (input instanceof URL ? input.href : null);
                const body = init && init.body;
                const report = registered && !!url && reports.test(url) && typeof body === 'string' &&
                  ((init && init.method) || 'GET').toUpperCase() === 'POST';
                if (!url || !(relayed.test(url) || report) || notRelayed.test(url) || (body != null && typeof body !== 'string')) return original.apply(this, arguments);
                if (window.__mspDirect && command.test(url)) return direct(url, init);
                const headers = {};
                new Headers((init && init.headers) || {}).forEach((v, k) => { headers[k] = v; });
                const method = ((init && init.method) || 'GET').toUpperCase();
                return new Promise((resolve, reject) => {
                  const id = ++seq;
                  pending.set(id, { resolve, reject, url, init, method });
                  window.MyspotiNet.postMessage(JSON.stringify({ id, url, method, headers, body: body == null ? null : body }));
                });
              };
            })();
        """.trimIndent()
    }
}
