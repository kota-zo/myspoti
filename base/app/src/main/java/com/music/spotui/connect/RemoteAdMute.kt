package com.music.spotui.connect

import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.spotify.AccountPlan
import app.myspoti.spotify.bridge.AdBlock
import androidx.compose.runtime.snapshotFlow
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's side of the ad blocker for Spotify Connect devices ([AdBlock]: the blocker acts only
 * while the music is sent from the app to another device of the account, see [AdBlock.activeFor];
 * this keeps [AdBlock.active] to the device that plays and mutes that device's ads).
 *
 * Ads on a device the music was sent to (#153). Moved there with Spotify Connect (a Cast
 * speaker woken by [com.music.spotui.cast.CastSpotify], a Denon/HEOS receiver, any Connect
 * device), the songs are played by Spotify on that device itself: its ads too, which no host
 * on the phone can refuse. The page here only follows that device, and it does see the ad (the
 * player's ad flag, which the screens show as "Advertisement").
 *
 * What an ad muter does with a desktop client is done here with the device's volume, through
 * the same Connect volume command as the player's volume bar: set to 0 when an ad comes on the
 * device picked in Myspoti, and the level it had set back once the break is over. Nothing is
 * skipped and no event is sent: the ad plays to its end, unheard.
 *
 * Guards: the user's own volume change during the break ends the mute (their level stands); a
 * break that has not ended after [MAX_MUTE_MS] (some players are said to hold a silenced ad)
 * gets its sound back and that device is no longer muted until the app restarts; a device that
 * left before the break ended gets its level back the next time it plays. Never a device
 * with a fixed volume, nor a Jam host's device.
 */
object RemoteAdMute {
    /** Longer than any break seen (two 30 s ads); past it the sound comes back. */
    const val MAX_MUTE_MS = 90_000L
    /** A restore kept over an app restart is dropped after this. */
    private const val OWED_MAX_AGE_MS = 24 * 3600_000L

    /** The decisions, without Android or the page (tested). */
    internal object Rules {
        /**
         * Whether the ad now on is muted: an [ad] on the device that plays ([elsewhere], [activeId]),
         * which is the one picked in Myspoti ([chosenId]), with a [level] above 0 (a device with a
         * fixed volume reports none, or a negative one), outside a Jam joined as a member, and not
         * a device whose muted ad never ended ([setAside]).
         */
        fun mutes(
            ad: Boolean, elsewhere: Boolean, activeId: String?, chosenId: String?, level: Double?,
            jamMember: Boolean, setAside: Set<String>,
        ): Boolean =
            ad && AdBlock.activeFor(elsewhere, activeId, chosenId, jamMember, premium = false) &&
                level != null && level > 0.0 && activeId !in setAside

        /**
         * The level a device is reported at (0..1), else the one the player's volume bar shows
         * (0..100) when that is this device's ([shown] null: the bar still has its first 100 % or
         * another device's level, and restoring it would set the device to that, #159).
         */
        fun levelOf(reported: Double?, shown: Int?): Double? = when {
            reported == null -> shown?.takeIf { it > 0 }?.let { it / 100.0 }
            reported <= 0.0 -> null
            else -> reported.coerceAtMost(1.0)
        }

        /** Whether a restore owed to [owedId] since [owedAt] is done now, [activeId] playing at [now]. */
        fun restoresOwed(owedId: String?, owedAt: Long, activeId: String?, ad: Boolean, now: Long): Boolean =
            owedId != null && owedId == activeId && !ad && now - owedAt < OWED_MAX_AGE_MS
    }

    private class Muted(val deviceId: String, val level: Double, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var started = false
    /** The device muted for the break on now. */
    @Volatile private var muted: Muted? = null
    /** A device muted for a break it left before the end: its level waits for it. */
    @Volatile private var owed: Muted? = null
    private val setAside = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** A device is muted for an ad: the volume it reports is not the user's (see SongPlayer.refreshDevice). */
    val muting: Boolean get() = muted != null

    private val prefs get() = Graph.app.getSharedPreferences("remote_ad_mute", android.content.Context.MODE_PRIVATE)

    /** Follows the player's ad flag (once, when the player is attached). */
    fun start() {
        if (started) return
        started = true
        // The app went away during a muted break: the level is still owed.
        runCatching {
            val p = prefs
            val id = p.getString("device", null)
            if (id != null) owed = Muted(id, p.getFloat("level", 1f).toDouble(), p.getLong("at", 0L))
        }
        scope.launch {
            // The engine may start after the player is attached.
            while (!MyspotiRuntime.isStarted) delay(1_000)
            val player = MyspotiRuntime.spotify.player
            // The blocker follows the device that plays (the page's Connect status, the device the
            // app knows as playing, the one picked here, a Jam joined), whatever the ads do.
            launch {
                combine(player.playsElsewhere, snapshotFlow { SongPlayer.activeDevice.value?.id }, MyspotiRuntime.api.jam.session) { elsewhere, activeId, jam ->
                    AdBlock.activeFor(elsewhere, activeId, SongPlayer.chosenRemoteId, jamMember = jam?.isHost == false)
                }.distinctUntilChanged().collect { on ->
                    AdBlock.setActive(on)
                    log(if (on) "on (the music plays on a Spotify Connect device sent from the app)" else "off")
                }
            }
            combine(player.adPlaying, player.playsElsewhere) { ad, elsewhere -> ad to elsewhere }
                .collectLatest { (ad, elsewhere) -> runCatching { onChange(ad, elsewhere) } }
        }
    }

    /**
     * The user set the volume of the device that plays: their level stands, nothing is restored
     * over it. True when that device was muted here (the level must then be sent even if unchanged).
     */
    fun userSetVolume(): Boolean {
        val m = muted ?: owed?.takeIf { it.deviceId == SongPlayer.activeDevice.value?.id } ?: return false
        if (muted === m) muted = null
        if (owed === m) owed = null
        forget()
        log("volume set by the user during the ad: left to them")
        return true
    }

    private suspend fun onChange(ad: Boolean, elsewhere: Boolean) {
        // Premium: Spotify sends no ads to this account, so nothing is muted here (AccountPlan).
        if (AccountPlan.isPremium) return
        val api = MyspotiRuntime.api
        muted?.let { m ->
            if (ad) { holdUntilTooLong(m); return }
            restore(m, "break over")
            return
        }
        owed?.let { o ->
            if (!elsewhere || ad) return@let
            runCatching { api.devices.refresh() }
            val activeId = api.devices.devices.value.firstOrNull { it.active }?.id
            if (Rules.restoresOwed(o.deviceId, o.at, activeId, ad, System.currentTimeMillis())) {
                runCatching { api.player.setVolume(o.level) }
                    .onSuccess { if (owed === o) owed = null; forget(); log("level put back on a device muted for an earlier ad") }
            } else if (System.currentTimeMillis() - o.at >= OWED_MAX_AGE_MS) { owed = null; forget() }
        }
        if (!ad || !elsewhere) return
        runCatching { api.devices.refresh() }
        val active = api.devices.devices.value.firstOrNull { it.active && !it.local } ?: return
        val level = Rules.levelOf(active.volume, SongPlayer.knownRemoteVolume(active.id))
        val jamMember = api.jam.session.value?.isHost == false
        if (!Rules.mutes(ad, elsewhere, active.id, SongPlayer.chosenRemoteId, level, jamMember, setAside)) return
        val m = Muted(active.id, level!!, System.currentTimeMillis())
        muted = m
        remember(m)
        val sent = runCatching { api.player.setVolume(0.0) }
        if (sent.isFailure) {
            muted = null
            forget()
            log("ad on a ${active.type.lowercase()}: volume not set (${sent.exceptionOrNull()?.message?.take(80)})")
            return
        }
        log("ad on a ${active.type.lowercase()}: muted for the break")
        holdUntilTooLong(m)
    }

    /** Returns only when the break is cut short by the next change; past [MAX_MUTE_MS] the sound comes back. */
    private suspend fun holdUntilTooLong(m: Muted) {
        val left = MAX_MUTE_MS - (System.currentTimeMillis() - m.at)
        if (left > 0) delay(left)
        if (muted !== m) return
        setAside.add(m.deviceId)
        restore(m, "the ad still on after ${MAX_MUTE_MS / 1000} s, this device is no longer muted")
    }

    /** The device's level back, when it still plays; otherwise owed to it. Not cancelled midway. */
    private suspend fun restore(m: Muted, why: String) = withContext(NonCancellable) {
        if (muted !== m) return@withContext
        muted = null
        val api = MyspotiRuntime.api
        runCatching { api.devices.refresh() }
        val activeId = api.devices.devices.value.firstOrNull { it.active }?.id
        if (activeId != m.deviceId) {
            owed = m
            log("$why: the device no longer plays, its level is put back when it does")
            return@withContext
        }
        val sent = runCatching { api.player.setVolume(m.level) }
        if (sent.isSuccess) { forget(); log("$why: level put back") }
        else { owed = m; log("$why: level not put back (${sent.exceptionOrNull()?.message?.take(80)}), tried again later") }
    }

    private fun remember(m: Muted) = runCatching {
        prefs.edit().putString("device", m.deviceId).putFloat("level", m.level.toFloat()).putLong("at", m.at).apply()
    }

    private fun forget() = runCatching { prefs.edit().clear().apply() }

    private fun log(line: String) = Logbook.log("connect: ad blocker: $line")
}
