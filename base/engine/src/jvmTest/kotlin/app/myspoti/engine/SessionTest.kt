package app.myspoti.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The playback session state machine against the sequences measured in the captures: which
 * state PUTs (debug_source) and melody messages go out, in which order, and only when the
 * player reported real playback.
 */
class SessionTest {
    private fun kotlinx.serialization.json.JsonObject.str(vararg path: String): String {
        var e: kotlinx.serialization.json.JsonElement = this
        path.forEach { e = e.jsonObject[it]!! }
        return e.jsonPrimitive.content
    }

    @Test
    fun play_to_the_end_then_advance() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        assertEquals(listOf("apresolve", "devices", "hobs"), rig.http.requests.map {
            when { "apresolve" in it.url -> "apresolve"; it.url.endsWith("/devices") -> "devices"; else -> "hobs" }
        })

        // Server push after our play command: state 2, playing, from 0.
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        assertEquals(listOf("before_track_load"), rig.http.debugSources())
        assertEquals(listOf("load ${rig.trackUriOf(2)} @0 play=true"), rig.player.calls)
        val btl = rig.http.statePuts()[0]
        assertEquals("2", btl.str("seq_num"))
        assertEquals("ChTdealer2false0", btl.str("state_ref", "state_machine_id"))
        assertEquals(rig.stateId(2), btl.str("state_ref", "state_id"))
        assertEquals("0", btl.str("sub_state", "playback_speed"))
        assertEquals(listOf(listOf("jssdk_playback_start")), rig.http.melodyTypes())

        // Nothing more is reported until the player says audio plays.
        rig.env.now += 5_000
        assertEquals(1, rig.http.statePuts().size)

        rig.player.licence!!.licence(Fixtures.bytes("licence-challenge.bin"))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(153_889))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 1_000)
        assertEquals(listOf("before_track_load", "started_playing"), rig.http.debugSources())
        val started = rig.http.statePuts()[1]
        assertEquals("1", started.str("sub_state", "playback_speed"))
        assertEquals("1000", started.str("previous_position"))
        assertEquals("153889", started.str("sub_state", "duration"))
        // The answer of the first PUT is the machine the next one refers to.
        assertEquals("ChTresponse1", started.str("state_ref", "state_machine_id"))
        // Next track preloaded once playing.
        assertEquals("preload ${rig.trackUriOf(rig.advanceOf(2))}", rig.player.calls[1])

        rig.playFor(1_000, 29_500)
        assertEquals(2, rig.http.statePuts().size)
        rig.playFor(29_500, 30_000)
        assertEquals(listOf("before_track_load", "started_playing", "played_threshold_reached"), rig.http.debugSources())

        rig.playFor(30_000, 153_500)
        val resolvesBefore = rig.http.count("/storage-resolve/")
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(153_889))
        assertEquals(
            listOf("before_track_load", "started_playing", "played_threshold_reached", "track_data_finalized", "before_track_load"),
            rig.http.debugSources(),
        )
        val puts = rig.http.statePuts()
        assertEquals((2..6).map { it.toString() }, puts.map { it.str("seq_num") })
        val fin = puts[3]
        assertEquals(rig.stateId(2), fin.str("state_ref", "state_id"))
        assertEquals("153889", fin.str("playback_stats", "ms_played_per_audio_format", "mp4a.40.2;128000"))
        val next = puts[4]
        assertEquals(rig.stateId(rig.advanceOf(2)), next.str("state_ref", "state_id"))
        assertEquals("false", next.str("state_ref", "paused"))
        assertEquals("0", next.str("sub_state", "position"))
        // previous_position is the last position the player reported, not the end (real1: 153699 of 153889).
        assertEquals("153500", next.str("previous_position"))
        assertEquals(fin.str("state_ref", "state_machine_id"), next.str("state_ref", "state_machine_id"))
        // Melody at the end: verification + stats in one batch (real1 order), then the next start.
        val types = rig.http.melodyTypes()
        assertTrue(types.contains(listOf("track_stream_verification", "jssdk_playback_stats")))
        assertEquals(listOf("jssdk_playback_start"), types.last())
        // 120 s of play flushed one media_playback_event_v2 timeline.
        assertTrue(types.contains(listOf("media_playback_event_v2")))
        // The preloaded file is loaded without resolving it again.
        assertEquals(resolvesBefore, rig.http.count("/storage-resolve/"))
        assertSame(rig.player.preloaded.single(), rig.player.loaded)
    }

    @Test
    fun natural_end_matches_the_firefox_end_of_track_capture() = runTest {
        // end-of-track (Firefox 159, 2026-11-10): paused session restored at 120752, resumed, played
        // to the natural end of a 150.2266 s file (the fixture seektable), then the next state.
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.transfer()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true, seekTo = 120_752))
        rig.engine.resume()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(150_274))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(120_752))
        rig.playFor(120_752, 150_062)
        val before = rig.http.requests.size
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(150_274))

        val fxFin = Fixtures.json("state-track-data-finalized-firefox.json")
        val fxNext = Fixtures.json("state-before-track-load-after-end.json")
        val fxMelody = Fixtures.json("melody-end-of-track-firefox.json")["messages"]!!.jsonArray.map { it.jsonObject }

        // Order as captured: melody verification + stats, finalized PUT, next before_track_load.
        val after = rig.http.requests.drop(before)
        assertTrue("/melody/v1/msg/batch" in after[0].url)
        assertEquals(listOf("track_stream_verification", "jssdk_playback_stats"),
            rig.http.json(after[0])["messages"]!!.jsonArray.map { it.jsonObject.str("type") })
        val fin = rig.http.json(after[1])
        val next = rig.http.json(after[2])
        assertEquals("track_data_finalized", fin.str("debug_source"))
        assertEquals("before_track_load", next.str("debug_source"))
        assertEquals(keyPaths(fxFin), keyPaths(fin))
        assertEquals(keyPaths(fxNext), keyPaths(next))

        // The capture's numbers: last reported position in the PUT, the file's duration, played time to the end.
        for (path in listOf(arrayOf("sub_state", "position"), arrayOf("previous_position"), arrayOf("sub_state", "duration"),
            arrayOf("sub_state", "playback_speed"), arrayOf("state_ref", "paused"),
            arrayOf("playback_stats", "ms_total_est"), arrayOf("playback_stats", "start_offset_ms"),
            arrayOf("playback_stats", "ms_played_per_audio_format", "mp4a.40.2;128000"))) {
            assertEquals(fxFin.str(*path), fin.str(*path), path.joinToString("."))
        }
        // Sent together: same machine as the finalized PUT, not the machine of its answer.
        assertEquals(fin.str("state_ref", "state_machine_id"), next.str("state_ref", "state_machine_id"))
        assertEquals(rig.stateId(rig.advanceOf(2)), next.str("state_ref", "state_id"))
        for (path in listOf(arrayOf("sub_state", "position"), arrayOf("sub_state", "playback_speed"), arrayOf("previous_position"), arrayOf("state_ref", "paused"))) {
            assertEquals(fxNext.str(*path), next.str(*path), path.joinToString("."))
        }

        val melody = rig.http.json(after[0])["messages"]!!.jsonArray.map { it.jsonObject }
        for ((i, keys) in listOf(
            0 to listOf("ms_played", "ms_nominal_played", "sequence_id", "playback_service"),
            1 to listOf("ms_played", "ms_file_duration", "ms_actual_duration", "ms_start_position", "ms_end_position",
                "reason_start", "reason_end", "initially_paused", "audio_format", "key_system"),
        )) {
            assertEquals(keyPaths(fxMelody[i]), keyPaths(melody[i]))
            keys.forEach { k -> assertEquals(fxMelody[i].str("message", k), melody[i].str("message", k), k) }
        }
        assertEquals(rig.stateId(rig.advanceOf(2)), melody[0].str("message", "next_playback_id"))

        // The next track's first audio flushes the timeline (+0.45 s in the capture) ...
        assertFalse(rig.http.melodyTypes().contains(listOf("media_playback_event_v2")))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        assertEquals(listOf("media_playback_event_v2"), rig.http.melodyTypes().last())
        // ... and the next report refers to the answer of before_track_load.
        rig.playFor(0, 1_000)
        val started = rig.http.statePuts().last()
        assertEquals("started_playing", started.str("debug_source"))
        assertTrue(started.str("state_ref", "state_machine_id") != next.str("state_ref", "state_machine_id"))
    }

    @Test
    fun loaded_paused_then_resumed_reports_started_playing_not_resume() = runTest {
        // The play-bodies sequence: transfer -> paused load at 60271 -> resume -> started_playing.
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.transfer()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true, seekTo = 60_271))
        val btl = rig.http.statePuts().single()
        assertEquals("true", btl.str("state_ref", "paused"))
        assertEquals("60271", btl.str("sub_state", "position"))
        assertTrue(rig.http.melodyTypes().contains(listOf("jssdk_connect_command")))
        assertEquals("load ${rig.trackUriOf(2)} @60271 play=false", rig.player.calls.single())

        rig.engine.resume()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false))
        assertEquals("resume", rig.player.calls.last())
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(60_271))
        rig.playFor(60_271, 61_337)
        assertEquals(listOf("before_track_load", "started_playing"), rig.http.debugSources())
        rig.playFor(61_337, 90_270)
        assertEquals(listOf("before_track_load", "started_playing"), rig.http.debugSources())
        rig.playFor(90_270, 90_271)
        // 30 s of audio from the start position (90514 in the capture, reported on a 200 ms tick).
        assertEquals(listOf("before_track_load", "started_playing", "played_threshold_reached"), rig.http.debugSources())
        assertEquals("90271", rig.http.statePuts().last().str("sub_state", "position"))
    }

    @Test
    fun pause_and_resume_are_reported_when_the_player_did_them() = runTest {
        val rig = playing(this)
        rig.engine.pause()
        assertTrue(rig.http.requests.last().url.contains("/player/command/from/${rig.engine.deviceId}/to/${rig.engine.deviceId}"))
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true))
        assertEquals("pause", rig.player.calls.last())
        assertFalse(rig.http.debugSources().contains("pause")) // not before the player confirms
        rig.engine.handlePlayerEvent(PlayerEvent.Paused(10_000))
        val pause = rig.http.statePuts().last()
        assertEquals("pause", pause.str("debug_source"))
        assertEquals("true", pause.str("state_ref", "paused"))
        assertEquals("0", pause.str("sub_state", "playback_speed"))
        assertEquals("10000", pause.str("sub_state", "position"))

        // Time paused is not played time.
        rig.env.now += 60_000
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false))
        assertEquals("resume", rig.player.calls.last())
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(10_000))
        val resume = rig.http.statePuts().last()
        assertEquals("resume", resume.str("debug_source"))
        assertEquals("1", resume.str("sub_state", "playback_speed"))
        rig.playFor(10_000, 29_500)
        assertFalse(rig.http.debugSources().contains("played_threshold_reached"))
        rig.playFor(29_500, 30_000)
        assertTrue(rig.http.debugSources().contains("played_threshold_reached"))
    }

    @Test
    fun seek_reports_old_and_new_position() = runTest {
        val rig = playing(this)
        rig.engine.seekTo(121_190)
        val cmd = rig.http.json(rig.http.requests.last())["command"]!!.jsonObject
        assertEquals("seek_to", cmd.str("endpoint"))
        assertEquals("121190.0", cmd.str("value"))
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 121_190))
        assertEquals("seek 121190", rig.player.calls.last())
        rig.engine.handlePlayerEvent(PlayerEvent.Seeked(121_190))
        val seek = rig.http.statePuts().last()
        assertEquals("seek", seek.str("debug_source"))
        assertEquals("121190", seek.str("sub_state", "position"))
        assertEquals("10000", seek.str("previous_position"))
        // A jump is not listening time: still under the 30 s threshold after the seek.
        rig.playFor(121_190, 125_000)
        assertFalse(rig.http.debugSources().contains("played_threshold_reached"))
    }

    @Test
    fun previous_seeks_to_zero_past_three_seconds() = runTest {
        val rig = playing(this)
        rig.engine.previous()
        val cmd = rig.http.json(rig.http.requests.last())["command"]!!.jsonObject
        assertEquals("seek_to", cmd.str("endpoint"))
        assertEquals("0.0", cmd.str("value"))
    }

    @Test
    fun skip_to_the_preloaded_next_track() = runTest {
        val rig = playing(this)
        val resolves = rig.http.count("/storage-resolve/")
        rig.engine.skipNext()
        val skipTo = rig.states[2].jsonObject["transitions"]!!.jsonObject["skip_next"]!!.jsonObject["state_index"]!!.jsonPrimitive.content.toInt()
        rig.engine.handleDealerFrame(rig.replaceState(skipTo, paused = false))
        assertEquals(listOf("before_track_load", "started_playing", "track_data_finalized", "before_track_load"), rig.http.debugSources())
        val fin = rig.http.statePuts()[2]
        assertEquals(rig.stateId(2), fin.str("state_ref", "state_id"))
        val stats = rig.http.requests.filter { "/melody/v1/msg/batch" in it.url }.map { rig.http.json(it) }
            .flatMap { it["messages"]!!.let { m -> (m as kotlinx.serialization.json.JsonArray).map { x -> x.jsonObject } } }
            .first { it.str("type") == "jssdk_playback_stats" }["message"]!!.jsonObject
        assertEquals("remote", stats.str("reason_end"))
        assertEquals("10000", stats.str("ms_played"))
        val verification = rig.http.requests.filter { "/melody/v1/msg/batch" in it.url }.map { rig.http.json(it) }
            .flatMap { (it["messages"] as kotlinx.serialization.json.JsonArray).map { x -> x.jsonObject } }
            .first { it.str("type") == "track_stream_verification" }["message"]!!.jsonObject
        assertEquals(rig.stateId(skipTo), verification.str("next_playback_id"))
        assertEquals(rig.stateId(skipTo), rig.http.statePuts()[3].str("state_ref", "state_id"))
        // No network round trip for the new track's media: it was preloaded.
        assertEquals(resolves, rig.http.count("/storage-resolve/"))
        assertSame(rig.player.preloaded.single(), rig.player.loaded)
        assertTrue(rig.player.calls.last().startsWith("load ${rig.trackUriOf(skipTo)} @0"))
    }

    @Test
    fun a_401_refreshes_the_token_once_then_surfaces() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.http.failNext("/storage-resolve/", 401)
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        assertEquals(1, rig.tokens.refreshes)
        val resolves = rig.http.requests.filter { "/storage-resolve/" in it.url }
        assertEquals(listOf("Bearer test-token-1", "Bearer test-token-2"), resolves.map { it.headers["authorization"] })
        assertEquals(listOf("before_track_load"), rig.http.debugSources())

        // Twice in a row: the session is gone, the caller is told.
        rig.http.failNext("/state", 401, times = 2)
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        val e = runCatching { rig.playFor(0, 1_000) }.exceptionOrNull()
        assertTrue(e is EngineException.Unauthorized, "$e")
        assertEquals(2, rig.tokens.refreshes) // refreshed once more before giving up
    }

    @Test
    fun a_429_waits_retry_after_then_surfaces() = runTest {
        val rig = Rig(backgroundScope)
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }
        rig.start()
        rig.http.failNext("/storage-resolve/", 429, mapOf("retry-after" to "2"))
        val job = launch { rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0)) }
        runCurrent()
        assertEquals(1, rig.http.count("/storage-resolve/"))
        assertTrue(rig.http.debugSources().isEmpty())
        advanceTimeBy(1_999); runCurrent()
        assertEquals(1, rig.http.count("/storage-resolve/"))
        advanceTimeBy(2); runCurrent()
        job.join()
        assertEquals(2, rig.http.count("/storage-resolve/"))
        assertEquals(listOf("before_track_load"), rig.http.debugSources())
        assertTrue(events.any { it is EngineEvent.RateLimited && it.retryAfterMs == 2_000L && it.attempt == 1 })

        // Without Retry-After: 1 s, 2 s, 4 s, then the error reaches the caller.
        rig.http.failNext("/melody/v1/msg/batch", 429, times = 4)
        var failure: Throwable? = null
        val failing = launch { failure = runCatching { rig.engine.handlePlayerEvent(PlayerEvent.Ended(0)) }.exceptionOrNull() }
        advanceTimeBy(7_001); runCurrent()
        failing.join()
        assertTrue(failure is EngineException.RateLimited, "$failure")
        assertEquals(listOf(1_000L, 2_000L, 4_000L), events.filterIsInstance<EngineEvent.RateLimited>().drop(1).map { it.retryAfterMs })
    }

    @Test
    fun an_ad_is_neither_fetched_nor_reported() = runTest {
        val rig = Rig(backgroundScope)
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }
        rig.start()
        val before = rig.http.requests.size
        val frame = rig.replaceState(2, paused = false, seekTo = 0).replace("\"content_type\":\"TRACK\"", "\"content_type\":\"AD\"")
        rig.engine.handleDealerFrame(frame)
        runCurrent()
        // Nothing is asked for the ad and nothing reported of it: the one request is the command that passes it.
        assertEquals(before + 1, rig.http.requests.size)
        val sent = rig.http.requests.last()
        assertTrue("/connect-state/v1/player/command/" in sent.url)
        assertTrue("skip_next" in sent.body!!.decodeToString())
        assertEquals(listOf("stop"), rig.player.calls)
        assertTrue(events.any { it is EngineEvent.AdBreak })
    }

    @Test
    fun clear_state_finalizes_and_reports_state_clear() = runTest {
        val rig = playing(this)
        rig.engine.handleDealerFrame(rig.clearState())
        assertEquals(listOf("before_track_load", "started_playing", "track_data_finalized", "state_clear"), rig.http.debugSources())
        assertEquals("stop", rig.player.calls.last())
    }

    /** A rig playing state 2 from 0, 10 s in (started_playing sent, next track preloaded). */
    private suspend fun playing(scope: kotlinx.coroutines.test.TestScope): Rig {
        val rig = Rig(scope.backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 10_000)
        return rig
    }
}
