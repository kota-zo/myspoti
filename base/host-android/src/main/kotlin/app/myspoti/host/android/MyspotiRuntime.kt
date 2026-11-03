package app.myspoti.host.android

import android.content.Context
import app.myspoti.api.MyspotiApi
import app.myspoti.spotify.AccountPlan
import app.myspoti.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The app's single Spotify session: one WebView host and one [SpotifyApi] for the process.
 * Created on the main thread (WebView requirement), usually from Application.onCreate.
 */
object MyspotiRuntime {
    lateinit var host: WebPlayerHost
        private set
    lateinit var spotify: SpotifyApi
        private set
    val api: MyspotiApi get() = spotify
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** A plan not known yet is asked again after this (a failed or unclear answer). */
    private const val PLAN_ASK_GAP_MS = 60_000L

    val isStarted: Boolean get() = ::host.isInitialized
    val loginRequired: StateFlow<Boolean> get() = host.loginRequired

    /** [loadPage] false (data saver on a metered network): nothing goes out until [resume]. */
    fun start(context: Context, loadPage: Boolean = true) {
        if (isStarted) return
        val updater = app.myspoti.spotify.gql.GqlOpsUpdater(okhttp3.OkHttpClient(), java.io.File(context.filesDir, "spotify/gql-ops.json"))
        host = WebPlayerHost(context)
        AudioModeWatch.install(context.applicationContext)
        spotify = SpotifyApi(host, scope, language = Locale.getDefault().language, ops = updater.load(), deviceId = clientDeviceId(context), userAgent = host.userAgent)
        spotify.bridge.logger = { line -> Logbook.log(line) }
        host.onPageLoaded = { scope.launch { spotify.onPageLoaded() } }
        host.onPageStarting = { spotify.onPageStarting() }
        spotify.player.reloadHost = { host.reload() }
        // Repairs wait while Spotify limits playback, and while a Spotify sign-in page is open:
        // the player is not there to answer, and the "page not answering" reload threw the user
        // off the e-mail code step while they were away reading the code (reported, log seen).
        // And while the phone is offline (airplane mode): a stalled song got a seek, a skip, then
        // a reload whose page could not load, so the music on screen was thrown away for nothing.
        // Downloads and extension audio play outside the page and never ask this.
        spotify.player.recoveryHeld = { host.licenseLimited() || host.loginRequired.value || !host.online.value }
        spotify.player.playbackHeld = { host.licenseLimited() }
        // Spotify limiting this client: stop asking (a paused player requests no licence).
        scope.launch {
            host.licenseLimitedUntil.collect { until ->
                if (until > android.os.SystemClock.elapsedRealtime()) runCatching { spotify.player.pause() }
            }
        }
        // The bridge, the page and the player in Logcat (diagnostics of a bug report): what
        // changes is logged when it changes, not once every 10 s whether or not anything happened.
        scope.launch {
            var last = ""
            // When the account's plan was last asked for (AccountPlan), so a failed or unknown answer is asked again later.
            var planAskedAt = 0L
            // A login page or an anonymous session seen in this run: the sign-in that follows is new.
            var sawSignedOut = false
            // A page that loaded but whose player never became ready (no bridge, a script that
            // failed): loaded again, after 60 s, then twice as long each time, up to 10 min.
            var notReadySince = 0L
            var notReadyWait = 60_000L
            while (true) {
                kotlinx.coroutines.delay(10_000)
                val clock = android.os.SystemClock.elapsedRealtime()
                val waiting = !spotify.ready.value && !host.loginRequired.value && !host.licenseLimited() &&
                    host.online.value && host.url.value.startsWith(WebPlayerHost.PLAYER_URL)
                if (!waiting) { notReadySince = 0L; if (spotify.ready.value) notReadyWait = 60_000L }
                else if (notReadySince == 0L) notReadySince = clock
                else if (clock - notReadySince >= notReadyWait) {
                    Logbook.log("player not ready for ${(clock - notReadySince) / 1000} s: loading it again")
                    notReadySince = 0L
                    notReadyWait = (notReadyWait * 2).coerceAtMost(600_000L)
                    host.reload()
                }
                // The account's plan (AccountPlan: Premium turns the ad machinery off), asked while it is not
                // known, once the page is signed in; forgotten at a sign-out (below). Its log line is the first
                // thing a refused play (402) needs. No name, no id, only what decides what the account may play.
                if (spotify.ready.value && runCatching { isSignedIn() }.getOrDefault(false)) {
                    host.noteSignedIn(fresh = sawSignedOut)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (AccountPlan.premium.value == null && now - planAskedAt >= PLAN_ASK_GAP_MS) {
                        planAskedAt = now
                        runCatching {
                            val answer = spotify.raw.gql("accountAttributes", kotlinx.serialization.json.buildJsonObject { })
                            val a = ((answer as kotlinx.serialization.json.JsonObject)["data"] as kotlinx.serialization.json.JsonObject)
                                .let { (it["me"] as kotlinx.serialization.json.JsonObject)["account"] as kotlinx.serialization.json.JsonObject }
                            val attrs = a["attributes"] as? kotlinx.serialization.json.JsonObject
                            fun v(o: kotlinx.serialization.json.JsonObject?, k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
                            AccountPlan.set(AccountPlan.fromAnswer(answer))
                            Logbook.log("account: product=${v(a, "product")} country=${v(a, "country")} catalogue=${v(attrs, "catalogue")} onDemand=${v(attrs, "onDemand")} ads=${v(attrs, "ads")}")
                            Logbook.fact("account", "product=${v(a, "product")} country=${v(a, "country")} catalogue=${v(attrs, "catalogue")} onDemand=${v(attrs, "onDemand")} ads=${v(attrs, "ads")}")
                        }
                    }
                }
                val s = spotify.player.state.value
                // "login" alone said nothing (it only meant "the page is on a login URL"): a bug
                // report needs the session itself — a token that is anonymous means no account.
                val session = when {
                    host.loginRequired.value -> "login-page"
                    // The token in hand: a fresh one every 10 s held every request behind the page's answer.
                    else -> when (spotify.signedInHint()) { true -> "signed-in"; false -> "anonymous"; null -> "unknown" }
                }
                if (session == "login-page" || session == "anonymous") {
                    sawSignedOut = true
                    // Signed out: the plan of the account that left is forgotten (a new sign-in reads its own).
                    AccountPlan.set(null)
                }
                val line = "status ready=${spotify.ready.value} session=$session " +
                    "track=${s?.item?.name} paused=${s?.isPaused} buffering=${s?.isBuffering} url=${host.url.value.take(60)}"
                if (line != last) {
                    last = line
                    Logbook.log(line)
                }
            }
        }
        if (!loadPage) return
        host.load()
        // New Spotify build: pick up its operation hashes (public bundles, no account).
        scope.launch {
            runCatching { updater.refresh(spotify.opsInUse) }.getOrNull()?.let(spotify::updateOps)
        }
    }

    /**
     * True when the Web Player holds a signed-in user's token (not an anonymous one), and never
     * while a Spotify sign-in page is open: whatever a token says, the user is mid sign-in.
     */
    suspend fun isSignedIn(): Boolean = !host.loginRequired.value && spotify.isSignedIn()

    /**
     * Waits for a signed-in session (a non-anonymous token) up to [timeoutMs]. The login flow is
     * bounded by this: the page returning to open.spotify.com is not a session, and waiting on
     * it for ever is what left the spinner running (see MainActivity.watchLoginReturn).
     */
    suspend fun awaitSignedIn(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { isSignedIn() }.getOrDefault(false)) return true
            kotlinx.coroutines.delay(500)
        }
        return runCatching { isSignedIn() }.getOrDefault(false)
    }

    /** Data saver: frees the page; [resume] loads it again. */
    fun suspendPlayer() = host.unload()
    fun resume() = host.load()

    /**
     * The client's device id, kept across launches: a client token is issued per (client, device)
     * pair, and a fresh id at every start made the app look like a brand-new client each time.
     */
    private fun clientDeviceId(context: Context): String {
        val file = java.io.File(context.filesDir, "spotify/device-id")
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        runCatching { file.parentFile?.mkdirs(); file.writeText(id) }
        return id
    }
}
