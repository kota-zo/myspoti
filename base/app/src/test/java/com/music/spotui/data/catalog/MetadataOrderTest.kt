package com.music.spotui.data.catalog

import com.music.spotui.cli.CliCommand
import com.music.spotui.cli.CliError
import com.music.spotui.cli.CliParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataOrderTest {
    @Test fun theDefaultOrderPutsTheSignedInAccountFirst() {
        assertEquals(listOf("spotify", "deezer", "ytmusic", "tidal", "itunes", "musicbrainz"), MetadataOrder.normalized(emptyList()))
    }

    @Test fun aStoredOrderKeepsItsPlacesAndGainsNewProvidersAtTheEnd() {
        val stored = listOf("itunes", "nonsense", "deezer", "itunes")
        assertEquals(listOf("itunes", "deezer", "spotify", "ytmusic", "tidal", "musicbrainz"), MetadataOrder.normalized(stored))
    }

    @Test fun movesStayInTheList() {
        val o = MetadataOrder.ALL
        assertEquals(listOf("deezer", "spotify") + o.drop(2), MetadataOrder.moved(o, "spotify", 1))
        assertEquals(o, MetadataOrder.moved(o, "spotify", -1))
        assertEquals(o, MetadataOrder.moved(o, "musicbrainz", 1))
        assertEquals(o, MetadataOrder.moved(o, "unknown", 1))
    }

    @Test fun oneProviderStaysOn() {
        val o = listOf("deezer", "itunes")
        assertTrue(MetadataOrder.canTurnOff(o, emptySet(), "deezer"))
        assertFalse(MetadataOrder.canTurnOff(o, setOf("itunes"), "deezer"))
        // Turning one back on is always possible.
        assertTrue(MetadataOrder.canTurnOff(o, setOf("itunes"), "itunes"))
    }

    @Test fun theCatalogCommandsParse() {
        assertEquals(CliCommand.CatalogStatus, CliParser.parse("catalog.status", emptyList()))
        assertEquals(CliCommand.CatalogUpdate, CliParser.parse("catalog.update", emptyList()))
        assertEquals(CliCommand.CatalogSource(null), CliParser.parse("catalog.source", listOf("default")))
        assertEquals(CliCommand.CatalogSource("http://localhost:8000/"), CliParser.parse("catalog.source", listOf("http://localhost:8000/")))
        assertEquals(CliCommand.CatalogSource("https://example.org/sets/"), CliParser.parse("catalog.source", listOf("https://example.org/sets/")))
        for (bad in listOf("http://192.168.1.5:8000/", "ftp://localhost/", "localhost:8000")) {
            assertTrue(bad, runCatching { CliParser.parse("catalog.source", listOf(bad)) }.exceptionOrNull() is CliError)
        }
        assertTrue(runCatching { CliParser.parse("catalog.source", emptyList()) }.exceptionOrNull() is CliError)
    }
}
