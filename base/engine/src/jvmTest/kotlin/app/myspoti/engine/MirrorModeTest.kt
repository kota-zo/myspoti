// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a listen mirror reads from the engine when it plays an unheard copy of an extension's song
 * (EngineSyncPage): the smallest file, the device's state as another Connect client sees it, and
 * another device taking playback.
 */
class MirrorModeTest {
    private fun file(id: Char, format: Int, bitrate: Int) =
        ManifestFile(bitrate = bitrate, fileId = id.toString().repeat(40), format = format.toString())

    private val track = SmTrack(
        TrackMetadata("spotify:track:x"),
        manifest = mapOf("file_ids_mp4" to listOf(file('a', 10, 128_000), file('b', 12, 64_000), file('c', 11, 256_000))),
    )

    @Test
    fun lowest_takes_the_smallest_file_the_account_may_play() {
        val filter = (1L shl 10) or (1L shl 12)
        assertEquals(10, Resolver.chooseFile(track, filter)!!.format)
        assertEquals(12, Resolver.chooseFile(track, filter, lowest = true)!!.format)
        // Format 11 is the smallest of all, but not allowed by the account's filter.
        val all = SmTrack(track.metadata, mapOf("file_ids_mp4" to track.manifest["file_ids_mp4"]!! + file('d', 11, 32_000)))
        assertEquals(12, Resolver.chooseFile(all, filter, lowest = true)!!.format)
    }

    @Test
    fun now_follows_load_play_pause_and_seek() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        assertNull(rig.engine.now.value)
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        val loading = rig.engine.now.value!!
        assertEquals(rig.trackUriOf(2), loading.uri)
        assertTrue(loading.buffering)
        assertFalse(loading.playing)

        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.engine.now.value!!.let { assertTrue(it.playing); assertFalse(it.buffering) }

        rig.env.now += 4_000
        rig.engine.handlePlayerEvent(PlayerEvent.Paused(4_000))
        rig.engine.now.value!!.let { assertFalse(it.playing); assertFalse(it.buffering); assertEquals(4_000, it.positionMs); assertEquals(rig.env.now, it.atMs) }
        rig.engine.handlePlayerEvent(PlayerEvent.Seeked(60_000))
        assertEquals(60_000, rig.engine.now.value!!.positionMs)
        rig.engine.handlePlayerEvent(PlayerEvent.Buffering(60_000))
        assertTrue(rig.engine.now.value!!.buffering)
    }

    @Test
    fun another_device_taking_playback_is_seen_at_once() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        assertFalse(rig.engine.playsElsewhere.value)
        rig.engine.handleDealerFrame(rig.clearState())
        assertTrue(rig.engine.playsElsewhere.value)
        assertNull(rig.engine.now.value)
        // Playback back here: a state again.
        rig.engine.handleDealerFrame(rig.replaceState(3, paused = false, seekTo = 0))
        assertFalse(rig.engine.playsElsewhere.value)
        assertEquals(rig.trackUriOf(3), rig.engine.now.value!!.uri)
    }

    @Test
    fun the_cluster_names_the_devices_and_what_the_active_one_plays() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        val frame = """{"type":"message","uri":"hm://connect-state/v1/cluster","headers":{"content-type":"application/json"},"payloads":[{"cluster":{
            "timestamp":"1700000000500","active_device_id":"phone1",
            "player_state":{"timestamp":"1700000000000","context_uri":"spotify:playlist:p","track":{"uri":"spotify:track:t","uid":"u"},
              "position_as_of_timestamp":"61234","duration":"200000","is_playing":true,"is_paused":true},
            "devices":{"phone1":{"name":"Phone of Sam","device_type":"SMARTPHONE","volume":65535},
                       "web1":{"name":"Web Player (Chrome)","device_type":"COMPUTER"}}},
            "update_reason":"DEVICE_STATE_CHANGED","devices_that_changed":["phone1"]}]}"""
        rig.engine.handleDealerFrame(frame)
        val c = rig.engine.cluster.value!!
        assertEquals("phone1", c.activeDeviceId)
        assertEquals(listOf(ClusterDevice("phone1", "Phone of Sam", "SMARTPHONE", volume = 1.0), ClusterDevice("web1", "Web Player (Chrome)", "COMPUTER")), c.devices)
        assertEquals("spotify:track:t", c.trackUri)
        assertEquals("spotify:playlist:p", c.contextUri)
        // is_playing stays true while paused.
        assertTrue(c.paused)
        assertEquals(61_234, c.positionMs)
        assertEquals(1_700_000_000_000, c.timestampMs)
        assertEquals(200_000, c.durationMs)
        // A compressed payload (a string) leaves the cluster as it was.
        rig.engine.handleDealerFrame("""{"type":"message","uri":"hm://connect-state/v1/cluster","headers":{"Transfer-Encoding":"gzip"},"payloads":["H4sI"]}""")
        assertEquals(c, rig.engine.cluster.value)
    }

    /**
     * Log seen: the mirror played its song, sent its seek 100 ms later, before the engine's
     * `before_track_load` of that song was answered, and the server put the account's previous
     * song back at the seek's position. The seek now waits for the load's report.
     */
    @Test
    fun a_seek_right_after_a_play_waits_for_the_load_to_be_reported() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 30_000)
        // The mirror's play lands: the server is slow to answer the new song's report.
        val gate = CompletableDeferred<Unit>()
        rig.http.gates["before_track_load"] = gate
        val since = rig.engine.clockMs()
        launch { rig.engine.handleDealerFrame(rig.replaceState(3, paused = false, seekTo = 0)) }
        var loaded = false
        val seek = launch { loaded = rig.engine.awaitLoaded(rig.trackUriOf(3), since, 6_000); if (loaded) rig.engine.seekTo(12_000) }
        runCurrent()
        assertEquals(rig.trackUriOf(3), rig.engine.now.value!!.uri)
        assertTrue(rig.http.requests.none { it.method == "POST" && "/player/command/" in it.url }, "no seek before the report is answered")
        gate.complete(Unit)
        seek.join()
        assertTrue(loaded)
        val report = rig.http.answered.indexOfLast { it.method == "PUT" && it.url.endsWith("/state") && rig.http.json(it)["debug_source"]!!.jsonPrimitive.content == "before_track_load" }
        val sent = rig.http.requests.indexOfFirst { it.method == "POST" && "/player/command/" in it.url && "seek_to" in it.body!!.decodeToString() }
        assertTrue(sent > rig.http.requests.indexOf(rig.http.answered[report]), "the seek goes after the new song's report")
        // A play that never loads: nothing waits for good, no seek.
        assertFalse(rig.engine.awaitLoaded("spotify:track:none", rig.engine.clockMs(), 1_000))
    }

    @Test
    fun mute_reaches_the_player() = runTest {
        val rig = Rig(backgroundScope)
        rig.engine.setMuted(true)
        assertEquals("muted true", rig.player.calls.last())
    }
}
