package com.music.spotui.radio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationFilterTest {
    @Test fun onlyHttpsStreamsPlayHlsOrNot() {
        assertTrue(RadioBrowserApi.playable("https://icecast.example.com/live.mp3"))
        assertTrue(RadioBrowserApi.playable("HTTPS://icecast.example.com:8443/stream"))
        assertFalse(RadioBrowserApi.playable("http://icecast.example.com/live.mp3"))
        assertTrue(RadioBrowserApi.playable("https://cdn.example.com/live/index.m3u8"))
        assertTrue(RadioBrowserApi.playable("https://cdn.example.com/live/master.M3U8?token=1"))
        assertFalse(RadioBrowserApi.playable("http://cdn.example.com/live/index.m3u8"))
        assertTrue(RadioBrowserApi.looksHls("https://cdn.example.com/live/master.M3U8?token=1"))
        assertFalse(RadioBrowserApi.looksHls("https://cdn.example.com/live.mp3?f=.m3u8"))
        assertFalse(RadioBrowserApi.playable("rtsp://cdn.example.com/live"))
        assertFalse(RadioBrowserApi.playable("https:///no-host"))
        assertFalse(RadioBrowserApi.playable(""))
    }

    @Test fun anHttpStationIsAskedOverHttps() {
        assertEquals("https://stream.srg-ssr.ch/m/couleur3/mp3_128", RadioBrowserApi.secured("http://stream.srg-ssr.ch/m/couleur3/mp3_128"))
        assertEquals("https://a.example.com:8000/live", RadioBrowserApi.secured("HTTP://a.example.com:8000/live"))
        assertEquals(null, RadioBrowserApi.secured("https://a.example.com/live"))
        assertEquals(null, RadioBrowserApi.secured("rtsp://a.example.com/live"))
        assertEquals(null, RadioBrowserApi.secured("http:///no-host"))
    }

    @Test fun aRedirectToHttpIsFollowedOverHttps() {
        // Couleur 3 (#234): its https address sends to an http one, whose server answers https too.
        val from = "https://stream.srg-ssr.ch/m/couleur3/mp3_128"
        assertEquals("https://livestreaming-node-2.srg-ssr.ch/srgssr/couleur3/mp3/128",
            RadioBrowserApi.securedHop(from, "http://livestreaming-node-2.srg-ssr.ch/srgssr/couleur3/mp3/128"))
        assertEquals("https://stream.srg-ssr.ch/live/aac", RadioBrowserApi.securedHop(from, "/live/aac"))
        assertEquals("https://cdn.example.com/x.mp3", RadioBrowserApi.securedHop(from, "https://cdn.example.com/x.mp3"))
        assertEquals(null, RadioBrowserApi.securedHop(from, "rtsp://cdn.example.com/live"))
    }

    @Test fun theDirectoryListKeepsOnlyHttpsStations() {
        val list = Json.parseToJsonElement(
            """
            [
              {"stationuuid": "a", "name": "A", "url": "http://a.example.com/raw", "url_resolved": "https://a.example.com/live", "hls": 0},
              {"stationuuid": "b", "name": "B", "url": "https://b.example.com/raw", "url_resolved": "http://b.example.com/live"},
              {"stationuuid": "c", "name": "C", "url": "https://c.example.com/live", "url_resolved": ""},
              {"stationuuid": "f", "name": "F", "url_resolved": "rtsp://f.example.com/live"},
              {"stationuuid": "d", "name": "D", "url_resolved": "https://d.example.com/live", "hls": 1},
              {"stationuuid": "e", "name": "E", "url_resolved": "https://e.example.com/playlist.m3u8"},
              "not a station"
            ]
            """,
        ).jsonArray
        val kept = RadioBrowserApi.stations(list)
        // "b" (http) is asked over https; "f" (rtsp) stays out; the HLS ones, "d" by the
        // directory's flag and "e" by its address, play.
        assertEquals(listOf("a", "b", "c", "d", "e"), kept.map { it.stationUuid })
        assertEquals(listOf(false, false, false, true, true), kept.map { it.hls })
        assertEquals("https://a.example.com/live", kept[0].streamUrl)
        assertEquals("https://b.example.com/live", kept[1].streamUrl)
        // The ones left out are counted, so a search says they exist instead of finding nothing.
        val firstOne = RadioBrowserApi.results(list, limit = 1)
        assertEquals(listOf("a"), firstOne.stations.map { it.stationUuid })
        assertEquals(1, firstOne.unplayable)
    }

    @Test fun theCuratedStationsAllPlay() {
        assertTrue(RadioBrowserApi.curatedPopular.all { RadioBrowserApi.playable(it.streamUrl) })
    }

    @Test fun onlyAUuidIsTakenForAStationId() {
        val id = "cf383460-c764-477e-a17b-33d1bff7a9aa"
        assertEquals(id, RadioBrowserApi.asStationUuid(id))
        assertEquals(id, RadioBrowserApi.asStationUuid("  $id  "))
        assertEquals(id.uppercase(), RadioBrowserApi.asStationUuid(id.uppercase()))
        assertEquals(null, RadioBrowserApi.asStationUuid("RDS"))
        assertEquals(null, RadioBrowserApi.asStationUuid("rds grandi successi"))
        assertEquals(null, RadioBrowserApi.asStationUuid(id.dropLast(1)))
        assertEquals(null, RadioBrowserApi.asStationUuid(""))
    }
}
