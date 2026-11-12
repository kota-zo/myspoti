package app.myspoti.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every response the engine reads, parsed from the anonymised captures. */
class ParsingTest {
    private inline fun <reified T> parse(name: String, s: kotlinx.serialization.KSerializer<T>): T =
        EngineJson.decodeFromString(s, Fixtures.text(name))

    @Test
    fun register_answer_gives_seq_and_format_filter() {
        val r = parse("register-response.json", RegisterResponse.serializer())
        assertEquals(1, r.initialSeqNum)
        assertEquals(5160L, r.fileFormatFilter)
        assertEquals("tft", r.effectiveLicense)
    }

    @Test
    fun apresolve_lists_dealer_and_spclient() {
        val r = parse("apresolve.json", ApResolveResponse.serializer())
        assertTrue(r.dealer.first().endsWith("-dealer.g2.spotify.com:443"))
        assertTrue(r.spclient.first().endsWith("-spclient.spotify.com:443"))
    }

    @Test
    fun dealer_replace_state_is_a_state_machine() {
        val f = EngineJson.decodeFromString(DealerFrame.serializer(), Fixtures.text("dealer-replace-state.json"))
        assertEquals("hm://track-playback/v1/command", f.uri)
        val cmd = EngineJson.decodeFromJsonElement(TrackPlaybackCommand.serializer(), f.payloads!!.single())
        assertEquals("replace_state", cmd.type)
        assertEquals(2, cmd.stateRef!!.stateIndex)
        assertTrue(cmd.stateRef.paused)
        assertEquals(2651L, cmd.seekTo!!.toLong())
        val sm = cmd.stateMachine!!
        assertEquals(5, sm.tracks.size)
        assertEquals(12, sm.states.size)
        val current = sm.states[2]
        assertEquals(3, current.transitions.advance!!.stateIndex)
        assertEquals(6, current.transitions.skipNext!!.stateIndex)
        assertEquals(30_000L, sm.tracks[current.track].msPlayedUntilUpdate)
    }

    @Test
    fun state_put_answer_carries_the_next_machine() {
        val r = parse("state-put-response-1.json", PutStateResponse.serializer())
        assertEquals(3, r.stateMachine!!.tracks.size)
        assertEquals(8, r.stateMachine.states.size)
        assertEquals(2, r.updatedStateRef!!.stateIndex)
        assertTrue(r.updatedStateRef.paused)
    }

    @Test
    fun file_choice_follows_the_format_filter() {
        val media = parse("media-response.json", MediaResponse.serializer())
        val track = media.media.values.first().item
        // The manifest offers 256 kbit/s (format 11) too; the free filter 5160 only allows 10 and 12.
        val chosen = Resolver.chooseFile(track, 5160)!!
        assertEquals(10, chosen.format)
        assertEquals(128_000, chosen.bitrate)
        // Without a filter the best file of the first list wins.
        assertEquals(11, Resolver.chooseFile(track, 0)!!.format)
        assertNotNull(media.media.values.first().videoVersionUri)
    }

    @Test
    fun storage_resolve_and_seektable_make_the_playable_media() {
        val sr = parse("storage-resolve.json", StorageResolveResponse.serializer())
        val st = parse("seektable.json", SeektableResponse.serializer())
        val file = ChosenFile(sr.fileId!!, 10, 128_000, "HIGH", -5.1)
        val track = parse("media-response.json", MediaResponse.serializer()).media.values.first().item
        val m = Resolver.toMedia(track, file, sr, st, EngineConfig(), 0)
        assertEquals(6, m.cdnUrls.size)
        assertTrue(m.cdnUrls.first().startsWith("https://audio-fa.scdn.co/audio/${sr.fileId}?"))
        assertEquals(1794354467000L, m.expiresAtMs)
        assertEquals(16, m.segments.size)
        assertEquals(0L..1068L, m.initRange)
        assertEquals(1292L, m.firstSegmentOffset)
        assertEquals(1292L, m.segments.first().byteRange.first)
        // Fragments are contiguous: each range starts where the previous ended (as the Web Player fetched them).
        m.segments.zipWithNext().forEach { (a, b) -> assertEquals(a.byteRange.last + 1, b.byteRange.first) }
        assertEquals(44_100, m.timescale)
        assertEquals(150_227L, m.durationMs) // 150.2266 s, rounded as the Web Player reported it
        // Widevine PSSH v0: system id edef8ba9-..., data names the file (provider "spotify").
        val pssh = m.widevinePssh!!
        assertEquals("pssh", pssh.copyOfRange(4, 8).decodeToString())
        assertTrue(pssh.decodeToString(throwOnInvalidSequence = false).contains("spotify"))
        assertEquals(sr.fileId.take(16), pssh.copyOfRange(34, 50).decodeToString())
        assertEquals("cross-site", m.cdnHeaders["sec-fetch-site"])
        assertNull(m.cdnHeaders["authorization"])
    }

    @Test
    fun licence_url_is_relative_to_spclient() {
        val l = parse("license-url.json", LicenseUrlResponse.serializer())
        assertEquals("widevine-license/v1/audio/license", l.uri)
        assertEquals(1794354467L, l.expires)
    }

    @Test
    fun cdn_expiry_is_read_from_both_token_forms() {
        assertEquals(1794354467000L, Resolver.cdnExpiry("https://audio-fa.scdn.co/audio/ab?1794354467_xyz="))
        assertEquals(1794354467000L, Resolver.cdnExpiry("https://audio-ak.spotifycdn.com/audio/ab?__token__=exp=1794354467~hmac=00"))
        assertNull(Resolver.cdnExpiry("https://audio-fa.scdn.co/audio/ab"))
    }
}
