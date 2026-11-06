package com.music.spotui.radio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationFilterTest {
    @Test fun onlyHttpsStreamsThatAreNotHlsPlay() {
        assertTrue(RadioBrowserApi.playable("https://icecast.example.com/live.mp3"))
        assertTrue(RadioBrowserApi.playable("HTTPS://icecast.example.com:8443/stream"))
        assertFalse(RadioBrowserApi.playable("http://icecast.example.com/live.mp3"))
        assertFalse(RadioBrowserApi.playable("https://cdn.example.com/live/index.m3u8"))
        assertFalse(RadioBrowserApi.playable("https://cdn.example.com/live/master.M3U8?token=1"))
        assertFalse(RadioBrowserApi.playable("https://cdn.example.com/live", hls = true))
        assertFalse(RadioBrowserApi.playable("rtsp://cdn.example.com/live"))
        assertFalse(RadioBrowserApi.playable("https:///no-host"))
        assertFalse(RadioBrowserApi.playable(""))
    }

    @Test fun theDirectoryListKeepsOnlyPlayableStations() {
        val list = Json.parseToJsonElement(
            """
            [
              {"stationuuid": "a", "name": "A", "url": "http://a.example.com/raw", "url_resolved": "https://a.example.com/live", "hls": 0},
              {"stationuuid": "b", "name": "B", "url": "https://b.example.com/raw", "url_resolved": "http://b.example.com/live"},
              {"stationuuid": "c", "name": "C", "url": "https://c.example.com/live", "url_resolved": ""},
              {"stationuuid": "d", "name": "D", "url_resolved": "https://d.example.com/live", "hls": 1},
              {"stationuuid": "e", "name": "E", "url_resolved": "https://e.example.com/playlist.m3u8"},
              "not a station"
            ]
            """,
        ).jsonArray
        val kept = RadioBrowserApi.stations(list)
        assertEquals(listOf("a", "c"), kept.map { it.stationUuid })
        assertEquals("https://a.example.com/live", kept[0].streamUrl)
    }

    @Test fun theCuratedStationsAllPlay() {
        assertTrue(RadioBrowserApi.curatedPopular.all { RadioBrowserApi.playable(it.streamUrl) })
    }
}
