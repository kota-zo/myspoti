package com.music.spotui.radio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RadioFavoritesStoreTest {
    private fun station(id: String, url: String = "https://radio.example.com/$id.mp3") = RadioStation(
        stationUuid = id, name = "Station $id", streamUrl = url, homepage = "https://radio.example.com",
        favicon = "", tags = listOf("jazz", "news"), country = "France", countryCode = "FR", language = "french",
        codec = "MP3", bitrateKbps = 128, votes = 3, clickCount = 4, lastCheckOk = true,
    )

    private fun file() = File(Files.createTempDirectory("radio").toFile(), "radio_favorites.json")

    private fun ids() = RadioFavoritesStore.list().map { it.stationUuid }

    @Test fun favoritesAreReadBackNewestFirst() {
        val f = file()
        RadioFavoritesStore.load(f)
        RadioFavoritesStore.add(station("a"), now = 1_000)
        RadioFavoritesStore.add(station("c"), now = 3_000)
        RadioFavoritesStore.add(station("b"), now = 2_000)
        RadioFavoritesStore.load(f)
        assertEquals(listOf("c", "b", "a"), ids())
        assertEquals(station("a"), RadioFavoritesStore.favorites["a"]?.station)
    }

    @Test fun aRemovedFavoriteStaysRemoved() {
        val f = file()
        RadioFavoritesStore.load(f)
        RadioFavoritesStore.add(station("a"), now = 1_000)
        RadioFavoritesStore.add(station("b"), now = 2_000)
        RadioFavoritesStore.toggle(station("a"))
        RadioFavoritesStore.load(f)
        assertEquals(listOf("b"), ids())
        assertFalse(RadioFavoritesStore.isFavorite("a"))
    }

    @Test fun aStationThatCannotPlayIsNeverKept() {
        RadioFavoritesStore.load(file())
        RadioFavoritesStore.add(station("cleartext", "http://radio.example.com/live"))
        assertTrue(ids().isEmpty())
        // One in the file all the same (written before the check): left out, the others kept.
        val f = file()
        val saved = JSONArray()
            .put(JSONObject().put("uuid", "x").put("url", "http://radio.example.com/x").put("added", 9))
            .put(JSONObject().put("uuid", "y").put("url", "https://radio.example.com/y").put("added", 5))
            .put(JSONObject().put("uuid", "z").put("url", "https://radio.example.com/z.m3u8").put("added", 7))
            .put(JSONObject().put("name", "no uuid").put("url", "https://radio.example.com/n"))
        f.writeText(JSONObject().put("version", 1).put("favorites", saved).toString())
        RadioFavoritesStore.load(f)
        assertEquals(listOf("y"), ids())
    }

    @Test fun aDamagedFileOrAnotherVersionReadsAsNoFavorite() {
        val f = file()
        RadioFavoritesStore.load(f)
        RadioFavoritesStore.add(station("a"))
        f.writeText("{oops")
        RadioFavoritesStore.load(f)
        assertTrue(ids().isEmpty())
        f.writeText(JSONObject().put("version", 2).put("favorites", JSONArray()).toString())
        RadioFavoritesStore.load(f)
        assertTrue(ids().isEmpty())
    }
}
