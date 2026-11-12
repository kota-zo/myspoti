package app.myspoti.engine

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Bodies, URLs and headers compared with what the Web Player sent (anonymised captures). */
class RequestTest {
    private val firefox = EngineConfig()

    @Test
    fun register_body_is_the_captured_one() {
        val captured = Fixtures.json("register-request.json")
        val device = captured["device"]!!.jsonObject
        val built = Bodies.register(
            firefox,
            device["device_id"]!!.jsonPrimitive.content,
            captured["connection_id"]!!.jsonPrimitive.content,
            device["correlation_id"]!!.jsonPrimitive.content,
        )
        assertEquals(captured, built)
        assertEquals(captured.keys.toList(), built.keys.toList())
    }

    @Test
    fun hobs_body_is_the_captured_one() = assertEquals(Fixtures.json("hobs-request.json"), Bodies.hobs())

    @Test
    fun state_bodies_match_the_capture() {
        val btl = Fixtures.json("state-before-track-load.json")
        val ref = btl["state_ref"]!!.jsonObject
        val built = Bodies.state(
            2, ref["state_machine_id"]!!.jsonPrimitive.content, ref["state_id"]!!.jsonPrimitive.content, true,
            Bodies.SubState(0, 60271, 150226, 128000, 10, "HIGH"), null, "before_track_load",
        )
        assertEquals(btl, built)
        for (name in listOf("state-started-playing.json", "state-played-threshold.json", "state-before-track-load-next.json")) {
            val c = Fixtures.json(name)
            val r = c["state_ref"]!!.jsonObject
            val s = c["sub_state"]!!.jsonObject
            val b = Bodies.state(
                c["seq_num"]!!.jsonPrimitive.content.toInt(), r["state_machine_id"]!!.jsonPrimitive.content,
                r["state_id"]!!.jsonPrimitive.content, r["paused"]!!.jsonPrimitive.content.toBoolean(),
                Bodies.SubState(
                    s["playback_speed"]!!.jsonPrimitive.content.toInt(), s["position"]!!.jsonPrimitive.content.toLong(),
                    s["duration"]!!.jsonPrimitive.content.toLong(), 128000, 10, "HIGH",
                ),
                c["previous_position"]!!.jsonPrimitive.content.toLong(), c["debug_source"]!!.jsonPrimitive.content,
            )
            assertEquals(c, b, name)
        }
    }

    @Test
    fun command_and_transfer_bodies_match_the_capture() {
        val resume = Fixtures.json("command-resume.json")
        val id = resume["command"]!!.jsonObject["logging_params"]!!.jsonObject["command_id"]!!.jsonPrimitive.content
        assertEquals(resume, Bodies.command("resume", id))
        val transfer = Fixtures.json("transfer-request.json")
        assertEquals(transfer, Bodies.transfer("pause", transfer["command_id"]!!.jsonPrimitive.content))
    }

    /** Runs a short real play through the engine and returns the rig (requests recorded). */
    private fun recordedPlay(block: suspend Rig.() -> Unit) = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.player.licence!!.licence(Fixtures.bytes("licence-challenge.bin"))
        rig.env.now += 100
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 31_000)
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(31_000))
        rig.block()
    }

    @Test
    fun melody_messages_have_the_captured_fields() = recordedPlay {
        val batches = http.requests.filter { "/melody/v1/msg/batch" in it.url }.map { http.json(it) }
        fun message(type: String): JsonObject = batches.flatMap { it["messages"]!!.jsonArray }
            .map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == type }["message"]!!.jsonObject
        fun captured(name: String, type: String) = Fixtures.json(name)["messages"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == type }["message"]!!.jsonObject

        assertEquals(keyPaths(captured("melody-playback-start.json", "jssdk_playback_start")), keyPaths(message("jssdk_playback_start")))
        assertEquals(keyPaths(captured("melody-end-of-track.json", "track_stream_verification")), keyPaths(message("track_stream_verification")))
        assertEquals(keyPaths(captured("melody-end-of-track.json", "jssdk_playback_stats")), keyPaths(message("jssdk_playback_stats")))
        // Envelope fields as captured.
        val envelope = Fixtures.json("melody-playback-start.json").keys
        batches.forEach { assertEquals(envelope, it.keys) }
        val start = batches.first { it["messages"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content == "jssdk_playback_start" }
        assertEquals("harmony:4.81.1", start["sdk_id"]!!.jsonPrimitive.content)
        assertEquals(firefox.platformIdentifier, start["platform"]!!.jsonPrimitive.content)
        // ms_played is what the player played: 31 s from position 0.
        assertEquals(31_000, message("track_stream_verification")["ms_played"]!!.jsonPrimitive.content.toInt())
        assertEquals("trackdone", message("jssdk_playback_stats")["reason_end"]!!.jsonPrimitive.content)
    }

    @Test
    fun finalized_state_has_the_captured_playback_stats() = recordedPlay {
        val fin = http.statePuts().first { it["debug_source"]!!.jsonPrimitive.content == "track_data_finalized" }
        assertEquals(keyPaths(Fixtures.json("state-track-data-finalized.json")), keyPaths(fin))
        val perFormat = fin["playback_stats"]!!.jsonObject["ms_played_per_audio_format"]!!.jsonObject
        assertEquals(setOf("mp4a.40.2;128000"), perFormat.keys)
    }

    @Test
    fun urls_and_headers_per_endpoint_are_the_browsers() = recordedPlay {
        fun req(part: String) = http.requests.first { part in it.url }
        val common = setOf("user-agent", "accept", "accept-language", "referer", "origin", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site")
        val creds = setOf("client-token", "authorization")
        val player = setOf("app-platform", "spotify-app-version")

        assertEquals(common + creds + player + "content-type", req("/track-playback/v1/devices").headers.keys)
        assertEquals(common + creds + player + "content-type" + "x-spotify-connection-id", req("/connect-state/v1/devices/hobs_").headers.keys)
        assertEquals("application/json", req("/connect-state/v1/devices/hobs_").headers["accept"])
        assertEquals(common + creds + player + "content-type", req("/state").headers.keys)
        assertEquals(common + creds, req("/storage-resolve/").headers.keys)
        assertEquals(common + creds, req("/melody/v1/license_url").headers.keys)
        assertEquals(common + creds, req("/widevine-license/v1/audio/license").headers.keys) // no content-type, as measured
        assertEquals(common + creds + "content-type", req("/melody/v1/msg/batch").headers.keys)
        assertEquals("text/plain;charset=UTF-8", req("/melody/v1/msg/batch").headers["content-type"])
        assertEquals(common, req("seektables.spotifycdn.com").headers.keys)
        assertEquals(common, req("apresolve.spotify.com").headers.keys)
        assertEquals("cross-site", req("seektables.spotifycdn.com").headers["sec-fetch-site"])
        assertEquals("same-site", req("/state").headers["sec-fetch-site"])
        assertEquals("WebPlayer", req("/state").headers["app-platform"])
        assertEquals("Bearer test-token-1", req("/state").headers["authorization"])
        http.requests.forEach { r -> assertFalse(r.headers.keys.any { it.equals("x-requested-with", true) }, r.url) }

        val sr = req("/storage-resolve/").url
        assertTrue(Regex("""^https://gew4-spclient\.spotify\.com/storage-resolve/v2/files/audio/interactive/10/[0-9a-f]{40}\?version=10000000&product=9&platform=39&alt=json$""").matches(sr), sr)
        assertEquals(
            "https://gew4-spclient.spotify.com/melody/v1/license_url?keysystem=com.widevine.alpha&mediatype=audio&sdk_name=harmony&sdk_version=4.81.1",
            req("/melody/v1/license_url").url,
        )
        assertEquals("https://gew4-spclient.spotify.com/widevine-license/v1/audio/license", req("/audio/license").url)
        assertArrayEquals(Fixtures.bytes("licence-challenge.bin"), req("/audio/license").body)
        assertTrue(sockets.url.startsWith("wss://gew4-dealer.g2.spotify.com/?access_token=test-token-1"))
        assertEquals("hobs_" + engine.deviceId.take(35), req("/connect-state/v1/devices/hobs_").url.substringAfterLast('/'))
        // The register body names the dealer connection.
        val reg = http.json(req("/track-playback/v1/devices"))
        assertEquals(Fixtures.json("dealer-connection.json")["headers"]!!.jsonObject["Spotify-Connection-Id"], reg["connection_id"])
        assertEquals(40, engine.deviceId.length)
        assertNull(req("/state").headers["cookie"])
    }

    @Test
    fun certificate_is_fetched_once_without_authorization() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        val ch = rig.player.licence!!
        assertArrayEquals(Fixtures.bytes("certificate.bin"), ch.serviceCertificate())
        ch.serviceCertificate()
        val certs = rig.http.requests.filter { "application-certificate" in it.url }
        assertEquals(1, certs.size)
        assertEquals("https://spclient.wg.spotify.com/widevine-license/v1/application-certificate", certs[0].url)
        assertEquals("test-client-token", certs[0].headers["client-token"])
        assertNull(certs[0].headers["authorization"])
        // Two licences, one licence URL lookup (valid 24 h).
        ch.licence(byteArrayOf(1)); ch.licence(byteArrayOf(2))
        assertEquals(1, rig.http.count("/melody/v1/license_url"))
        assertEquals(2, rig.http.count("/audio/license"))
    }
}
