package app.myspoti.host.android

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
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

    fun install() {
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
            if (m.has("kept")) Logbook.log("commands from the page: ${if (m.optBoolean("kept")) "kept, the music plays" else "dropped, nothing played in 10 s"}")
            else Logbook.log("refused command sent again from the page -> ${m.optInt("status")}")
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
        val headers = Headers.Builder()
        given.keys().forEach { key ->
            // OkHttp sets the transport headers itself.
            if (key.lowercase() !in setOf("host", "content-length", "connection", "accept-encoding", "x-requested-with"))
                runCatching { headers.add(key, given.getString(key)) }
        }
        val page = identity
        fun fromPage(name: String, fallback: String) = page?.optString(name)?.takeIf { it.isNotEmpty() } ?: fallback
        headers["User-Agent"] = fromPage("userAgent", fingerprint.userAgent)
        headers["Origin"] = "https://open.spotify.com"
        headers["Referer"] = "https://open.spotify.com/"
        // The transport headers Chromium adds by itself, which the page's `fetch` cannot set
        // (forbidden header names in JS) and OkHttp does not add either: without them the
        // relayed request carries a shape the real Web Player's request never has.
        headers["Sec-Fetch-Site"] = "same-site"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Dest"] = "empty"
        // Values taken from the page, not written by hand: whichever language and client hints it
        // presents, the relayed request presents the same.
        headers["Accept-Language"] = fromPage("acceptLanguage", fallbackLanguage)
        headers["sec-ch-ua"] = fromPage("brands", fingerprint.secChUa)
        headers["sec-ch-ua-mobile"] = fromPage("mobile", "?0")
        headers["sec-ch-ua-platform"] = "\"${fromPage("platform", "Linux")}\""
        val built = headers.build()
        // The registration goes out as the real Web Player sends it, except for its name: the phone's
        // own (see [deviceName]) replaces "Web Player (Chrome)".
        val registration = body != null && app.myspoti.spotify.bridge.AdBlock.isDeviceRegistration(method, url)
        if (debuggable && body != null && url.contains("/connect-state/"))
            Logbook.log("connect-state body: ${body.take(500)}")
        if (registration) runCatching { JSONObject(body!!).optJSONObject("device")?.optString("device_id") }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { self = it }
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
        // Bytes, not a String body: OkHttp would append "; charset=utf-8" to the content type.
        val payload = if (method == "GET" || method == "HEAD") null else (sent ?: "").toByteArray().toRequestBody(null)
        val request = Request.Builder().url(url).headers(built).method(method, payload).build()
        val asked = android.os.SystemClock.uptimeMillis()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Logbook.log("relay $method ${shortPath(url)} failed: ${e.message}")
                reply(id, 0, "{}", "")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val out = JSONObject()
                    it.headers.names().forEach { name -> out.put(name, it.header(name)) }
                    val text = it.body.string()
                    val net = android.os.SystemClock.uptimeMillis() - asked
                    Logbook.log("relay $method ${shortPath(url)} -> ${it.code} in $net ms")
                    if (it.code >= 400) Logbook.log("relay error body: ${text.take(400)}")
                    if (it.code in 200..299 && method == "POST" && url.substringBefore('?').endsWith("/track-playback/v1/devices")) onDeviceRegistered?.invoke()
                    // B3 (402 on some free accounts): what a tester's report must carry to tell the
                    // causes apart — the refusal's own headers, and what the refused command asked
                    // (the license the page put in it, a picked song or not). No ids, no cookies.
                    if (it.code == 402) {
                        val heads = it.headers.names().filterNot { n -> n.equals("set-cookie", true) || n.startsWith("authorization", true) }
                            .joinToString(", ") { n -> "$n=${it.header(n)?.take(80)}" }
                        Logbook.log("refusal headers (${it.protocol}, ${it.handshake?.tlsVersion?.javaName ?: "no tls"}): $heads")
                        if (body != null) Logbook.log("refused command: ${commandSummary(body)}")
                    }
                    if (registration) Regex("\"effective_license\"\\s*:\\s*\"([^\"]*)\"").find(text)
                        ?.let { m -> Logbook.log("registration: effective_license=${m.groupValues[1]}") }
                    if (registration && it.code in 200..299) Logbook.log("registration answer: ${registrationSummary(text)}")
                    // What Spotify holds for this account's devices (this player's license, whether
                    // it may play, another device active): the cluster comes back on joining and
                    // on every cluster read, the one the page makes after a refused command.
                    if (it.code in 200..299 && (url.contains("/connect-state/v1/cluster") || (method == "PUT" && url.contains("/connect-state/v1/devices/"))))
                        clusterSummary(text, self)?.let { s -> Logbook.log("cluster: $s") }
                    if (debuggable && registration) Logbook.log("registration answered: ${text.take(500)}")
                    if (debuggable && AD_MARK.containsMatchIn(text)) keepAdSample(method, url, body, text)
                    // An ad in the queue Spotify sends: the song before it goes on to the next song
                    // instead, before the page sees the queue (see AdBlock.withoutAds).
                    val given = if (url.contains("/track-playback/")) app.myspoti.spotify.bridge.AdBlock.withoutAds(text) else null
                    given?.second?.forEach { note -> Logbook.log("ad left out: $note") }
                    val sent = given?.first ?: text
                    // Both ways on from this song lead to the ad: the player plays the next song
                    // just before this one ends (AdBlock.adAhead).
                    if (url.contains("/track-playback/")) app.myspoti.spotify.bridge.AdBlock.adAhead(sent)?.let { a ->
                        if (app.myspoti.spotify.bridge.AdBlock.ahead.value != a) Logbook.log("ad ahead: ${a.nextUri} is played before the end")
                        app.myspoti.spotify.bridge.AdBlock.ahead.value = a
                    }
                    reply(id, it.code, out.toString(), sent)
                }
            }
        })
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

    /** This page's playback device id, read from its registration: never logged, only compared. */
    @Volatile private var self: String? = null
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
         * The WebView's own cookies. The page's `fetch` sends them, this app's OkHttp does not: a
         * relayed request without them is not the shape of the real Web Player's request, which
         * Spotify can tell apart (a `402 COMMAND_FAILED` on a free account is one such refusal).
         */
        private val cookies = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = runCatching {
                val raw = android.webkit.CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
                raw.split(';').mapNotNull { part ->
                    val at = part.indexOf('=')
                    if (at <= 0) null
                    else Cookie.Builder()
                        .name(part.take(at).trim())
                        .value(part.substring(at + 1).trim())
                        .domain(url.host)
                        .path("/")
                        .build()
                }
            }.getOrDefault(emptyList())
        }

        /**
         * One client for every relay (a page rebuilt after a renderer crash gets a new relay, not a
         * new pool), so that [resetConnections] reaches the one in use. A dead connection fails in
         * seconds: with a 30 s read timeout, a command sent on one (the phone moving between Wi-Fi
         * and mobile data, a connection the mobile network dropped while idle) waited that long
         * before anything tried again. Spotify answers these requests in well under a second, so
         * 10 s without a byte is a dead connection; the ping finds one before a command does.
         */
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .cookieJar(cookies)
            .build()

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
         * Only the playback commands and states are on the list. The play telemetry (`melody`,
         * `gabo-receiver-service`) was added in 2.0.2-beta.1 and that beta's page never registered
         * its playback device on a free account ("Device not found", nothing plays): it leaves from
         * the WebView again, as in 2.0.1, until it is proven harmless on a phone. Widening this to
         * everything else broke the page too (protobuf and binary bodies, see the notes).
         *
         * Login and consent hosts stay out: their flows post forms and redirect, and a relay would
         * have to reproduce every step exactly to sign in.
         */
        private val RELAYED = Regex("^https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.spotify\\.com/(connect-state|track-playback)/v1/")
        private val NOT_RELAYED = Regex("^https://(accounts|challenge|www)\\.spotify\\.com/")

        fun isRelayed(url: String) = RELAYED.containsMatchIn(url) && !NOT_RELAYED.containsMatchIn(url)

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
         */
        internal fun clusterSummary(text: String, self: String?): String? = runCatching {
            val j = JSONObject(text)
            val c = j.optJSONObject("cluster") ?: j
            val devices = c.optJSONObject("devices") ?: return null
            val active = c.optString("active_device_id")
            val list = devices.keys().asSequence().sortedBy { if (it == self) "" else it }.map { id ->
                val d = devices.optJSONObject(id) ?: JSONObject()
                val caps = d.optJSONObject("capabilities")
                val marks = listOfNotNull("self".takeIf { id == self }, "active".takeIf { id == active && active.isNotEmpty() })
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
            ("devices=${list.size} active=$activeWhere self_listed=${self != null && devices.has(self)} " +
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
              // silences them during an ad ("media.mute"); one started then starts silent.
              // Silent is a tiny volume, never muted: Chromium pauses muted media of a hidden page.
              const media = new Set();
              window.__mspMedia = media;
              const play = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () {
                media.add(this);
                if (window.__mspMuted) { this.muted = false; this.volume = 0.001; }
                return play.apply(this, arguments);
              };
              const relayed = /^https:\/\/([a-z0-9-]+\.)*spotify\.com\/(connect-state|track-playback)\/v1\//;
              const notRelayed = /^https:\/\/(accounts|challenge|www)\.spotify\.com\//;
              const pending = new Map();
              let seq = 0;
              const original = window.fetch;
              const command = /player\/command|connect\/transfer/;
              // A refused play or transfer (free accounts in some countries): the app tries another way.
              // late: the command was acknowledged but nothing played (see trust): the host judges it
              // more than its usual few seconds after the play.
              const tell = (status, url, late) => {
                if ((status === 402 || status === 403) && command.test(url) && window.MyspotiHost) {
                  try { window.MyspotiHost.postMessage(JSON.stringify({ type: 'refused', status, late: !!late, path: url.split('?')[0].replace(/^.*\/v1\//, '').replace(/\/from\/.*$/, '') })); } catch (e) { /* host gone */ }
                }
              };
              const note = (value) => { try { window.MyspotiNet.postMessage(JSON.stringify(Object.assign({ type: 'direct' }, value))); } catch (e) { /* host gone */ } };
              // Some free accounts get every relayed command refused (402 COMMAND_FAILED) while the
              // same account plays in apps that send them from the page itself (Spotui): a refused
              // command is sent once more that way, and the page's commands keep going that way if
              // it took. Kept only if something plays within 10 s: a command sent from the page can
              // be acknowledged and never delivered (X-Requested-With, see above).
              const plays = () => [...media].some(m => !m.paused && !m.ended && m.readyState > 2);
              const trust = (url) => {
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
                  if (!kept) tell(402, url, true);
                }, 10000);
              };
              const direct = (url, init) => original.call(window, url, init).then(r => { tell(r.status, url); return r; });
              window.__mspNetDone = (id, status, headers, body) => {
                const p = pending.get(id);
                if (!p) return;
                pending.delete(id);
                if (!status) return p.reject(new TypeError('Failed to fetch'));
                const relayedAnswer = () => {
                  const noBody = status === 204 || status === 205 || status === 304;
                  return new Response(noBody ? null : body, { status, headers: JSON.parse(headers) });
                };
                if (status === 402 && command.test(p.url) && !window.__mspDirect) {
                  original.call(window, p.url, p.init).then(r => {
                    note({ status: r.status });
                    if (r.ok) trust(p.url); else tell(r.status, p.url);
                    p.resolve(r);
                  }, () => { tell(status, p.url); p.resolve(relayedAnswer()); });
                  return;
                }
                tell(status, p.url);
                p.resolve(relayedAnswer());
              };
              window.fetch = function (input, init) {
                const url = typeof input === 'string' ? input : (input instanceof URL ? input.href : null);
                const body = init && init.body;
                if (!url || !relayed.test(url) || notRelayed.test(url) || (body != null && typeof body !== 'string')) return original.apply(this, arguments);
                if (window.__mspDirect && command.test(url)) return direct(url, init);
                const headers = {};
                new Headers((init && init.headers) || {}).forEach((v, k) => { headers[k] = v; });
                const method = ((init && init.method) || 'GET').toUpperCase();
                return new Promise((resolve, reject) => {
                  const id = ++seq;
                  pending.set(id, { resolve, reject, url, init });
                  window.MyspotiNet.postMessage(JSON.stringify({ id, url, method, headers, body: body == null ? null : body }));
                });
              };
            })();
        """.trimIndent()
    }
}
