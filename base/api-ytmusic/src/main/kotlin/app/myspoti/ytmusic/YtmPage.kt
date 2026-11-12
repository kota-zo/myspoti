/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.ytmusic

import app.myspoti.web.PageBridge
import app.myspoti.web.PageException
import app.myspoti.web.PageHost
import app.myspoti.web.PageState
import app.myspoti.web.SyncPagePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The official web app in the hidden page ([host]), driven through its page script (`ytm-page.js`):
 * the [SyncPagePlayer] of the YouTube Music listen sync. A song is opened inside the web app (its own
 * navigation, as a tap on it: its queue, its history), or by loading its watch page when that does
 * not take; the page is unheard and plays only the asked song (see the script). [loadMs]: how long a song's page is given to show it.
 */
class YtmPage(
    private val host: PageHost,
    val bridge: PageBridge,
    scope: CoroutineScope,
    private val loadMs: Long = 25_000,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : SyncPagePlayer {
    private val _state = MutableStateFlow<PageState?>(null)
    override val state: StateFlow<PageState?> = _state
    private val _ad = MutableStateFlow(false)
    override val adPlaying: StateFlow<Boolean> = _ad
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready

    /** The page says the account is signed in (its own configuration), null before the page is up. */
    val signedIn: Boolean? get() = bridge.info.value?.let { (it["signedIn"] as? JsonPrimitive)?.booleanOrNull }

    /** The web app's version and language as the page says them (for the API's requests). */
    fun pageInfo(key: String): String? = (bridge.info.value?.get(key) as? JsonPrimitive)?.contentOrNull

    init {
        scope.launch { combine(bridge.ready, bridge.info) { r, _ -> r && signedIn == true }.collect { _ready.value = it } }
        scope.launch {
            bridge.events.collect { (type, value) ->
                if (type != "state") return@collect
                val s = parseState(value)
                _state.value = s
                _ad.value = s?.ad == true
            }
        }
        // A page gone: no state of it is current any more.
        scope.launch { bridge.ready.collect { if (!it) { _state.value = null; _ad.value = false } } }
    }

    override suspend fun play(trackId: String, positionMs: () -> Long) {
        require(Ytm.isVideoId(trackId)) { "not a video id: $trackId" }
        if (bridge.ready.value && _state.value?.trackId == trackId) {
            call("want", trackId)
            seekIfOff(positionMs())
            call("resume")
            return
        }
        val gen = bridge.generation
        // Through the page script when it is there: inside the web app ("inapp", no page load, the
        // script loads the watch page itself if that did not take), or by a load that keeps the
        // asked song; else a plain load.
        val opened = if (bridge.ready.value) runCatching { bridge.call("open", args(trackId), timeoutMs = 3_000) }.getOrNull() else null
        val viaScript = opened != null
        val inApp = (opened as? JsonPrimitive)?.contentOrNull == "inapp"
        if (!viaScript) host.navigate(Ytm.watchUrl(trackId))
        val shown = withTimeoutOrNull(loadMs) {
            if (!inApp) bridge.ready.first { it && bridge.generation != gen }
            if (!viaScript) call("want", trackId)
            _state.first { it?.trackId == trackId }
        } ?: throw PageException("the page did not show $trackId in ${loadMs / 1000} s")
        seekIfOff(positionMs(), shown)
        call("resume")
    }

    private suspend fun seekIfOff(at: Long, s: PageState? = _state.value) {
        val web = s?.positionAt(wallClock()) ?: 0L
        if (kotlin.math.abs(at - web) > SEEK_SLACK_MS) call("seek", buildJsonObject { put("ms", at) })
    }

    override suspend fun pause() { call("pause") }
    override suspend fun resume() { call("resume") }
    override suspend fun seek(positionMs: Long) { call("seek", buildJsonObject { put("ms", positionMs) }) }

    override suspend fun release() {
        if (!bridge.ready.value) return
        call("want", null)
        runCatching { call("pause") }
    }

    /** The page loaded again on the home page, with no song asked (the sync asks its song again once it is ready). */
    override suspend fun reload() {
        if (bridge.ready.value) runCatching { call("want", null) }
        host.navigate(Ytm.ORIGIN + "/")
    }

    private fun args(videoId: String?) = buildJsonObject { if (videoId != null) put("videoId", videoId) else put("videoId", JsonNull) }

    private suspend fun call(method: String, videoId: String?) = bridge.call(method, args(videoId))
    private suspend fun call(method: String, a: JsonObject = JsonObject(emptyMap())) = bridge.call(method, a)

    companion object {
        /** Closer than this to the asked place, a song just loaded is not sought (a seek costs a rebuffer). */
        const val SEEK_SLACK_MS = 1_500L

        /** The script's state message as a [PageState]; null when there is no player. */
        fun parseState(e: JsonElement?): PageState? {
            val o = e as? JsonObject ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)
            return PageState(
                trackId = s("trackId")?.contentOrNull,
                paused = s("paused")?.booleanOrNull ?: true,
                buffering = s("buffering")?.booleanOrNull ?: false,
                positionMs = s("positionMs")?.longOrNull ?: 0L,
                timestampMs = s("at")?.longOrNull ?: System.currentTimeMillis(),
                durationMs = s("durationMs")?.longOrNull ?: 0L,
                ad = s("ad")?.booleanOrNull ?: false,
            )
        }

        /** The page script, bundled as a resource of this module. */
        val SCRIPT: String by lazy {
            YtmPage::class.java.getResourceAsStream("/ytmusic/ytm-page.js")!!.bufferedReader().use { it.readText() }
        }
    }
}
