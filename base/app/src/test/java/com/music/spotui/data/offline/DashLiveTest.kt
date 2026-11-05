package com.music.spotui.data.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class DashLiveTest {
    private val mpd = """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet><Representation codecs="flac">
        <SegmentTemplate timescale="44100" initialization="https://cdn.example.com/x/0.mp4?a=1&amp;b=2"
          media="https://cdn.example.com/x/${'$'}Number${'$'}.mp4?a=1&amp;b=2" startNumber="1">
          <SegmentTimeline><S d="176128" r="2"/><S d="1024"/></SegmentTimeline></SegmentTemplate>
        </Representation></AdaptationSet></Period></MPD>
    """.trimIndent()

    @Test fun aSegmentTemplateBecomesItsAddressesAndDurations() {
        val p = DashLive.parse(mpd)!!
        assertEquals("https://cdn.example.com/x/0.mp4?a=1&b=2", p.initUrl)
        assertEquals((1..4).map { "https://cdn.example.com/x/$it.mp4?a=1&b=2" }, p.segmentUrls)
        assertEquals(44100L, p.timescale)
        assertArrayEquals(longArrayOf(176128, 176128, 176128, 1024), p.durations)
        assertNull(DashLive.parse("<MPD><BaseURL>https://x/a.flac</BaseURL></MPD>"))
        assertNull(DashLive.parse(mpd.replace("https://cdn.example.com/x/0", "x/0")))
    }

    @Test fun theIndexListsEverySegmentForSeeking() {
        val b = ByteBuffer.wrap(DashLive.sidx(44100, longArrayOf(10, 20), longArrayOf(300, 400)))
        assertEquals(32 + 24, b.int)
        assertEquals("sidx", String(ByteArray(4).also { b.get(it) }))
        assertEquals(0, b.int)
        assertEquals(1, b.int); assertEquals(44100, b.int); assertEquals(0, b.int); assertEquals(0, b.int)
        assertEquals(0, b.short.toInt()); assertEquals(2, b.short.toInt())
        assertEquals(300, b.int); assertEquals(10, b.int); assertEquals(0x90000000.toInt(), b.int)
        assertEquals(400, b.int); assertEquals(20, b.int)
    }

    @Test fun twoReadersAtDifferentPlacesDoNotFetchTheSameSegmentsAgain() {
        val fetched = ArrayList<String>()
        val live = DashLive(byteArrayOf(), listOf("s1", "s2", "s3"), longArrayOf(4, 4, 4)) { fetched += it; ByteArray(4) }
        val b = ByteArray(1)
        for (i in 0 until 4) { live.read(i.toLong(), b, 0, 1); live.read(8L + i, b, 0, 1) }
        assertEquals(listOf("s1", "s3"), fetched)
    }

    @Test fun theLiveFileReadsAcrossSegmentsAndFromAnyPosition() {
        val segs = mapOf("s1" to ByteArray(5) { (10 + it).toByte() }, "s2" to ByteArray(3) { (20 + it).toByte() })
        val fetched = mutableListOf<String>()
        val live = DashLive(byteArrayOf(1, 2), listOf("s1", "s2"), longArrayOf(5, 3)) { fetched += it; segs.getValue(it) }
        assertEquals(10, live.length)
        val all = ByteArray(10)
        var at = 0L
        while (true) {
            val n = live.read(at, all, at.toInt(), 4)
            if (n < 0) break
            at += n
        }
        assertArrayEquals(byteArrayOf(1, 2, 10, 11, 12, 13, 14, 20, 21, 22), all)
        // A seek into the second segment fetches only it.
        fetched.clear()
        val buf = ByteArray(2)
        assertEquals(2, live.read(8, buf, 0, 2))
        assertArrayEquals(byteArrayOf(21, 22), buf)
        assertEquals(-1, live.read(10, buf, 0, 2))
        assertEquals(emptyList<String>(), fetched) // s2 was the segment read last: still in memory
    }

    @Test fun aSegmentBeingFetchedHoldsNoOtherReadAndIsFetchedOnce() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val fetched = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val live = DashLive(byteArrayOf(1, 2), listOf("s1", "s2"), longArrayOf(2, 2)) { url ->
            fetched += url
            if (url == "s1") gate.await()
            if (url == "s1") byteArrayOf(10, 11) else byteArrayOf(20, 21)
        }
        val warm = Thread { live.warm() }.apply { start() }
        while (fetched.isEmpty()) Thread.sleep(1)
        val waiting = arrayOfNulls<Int>(1)
        val reader = Thread { waiting[0] = live.read(2, ByteArray(2), 0, 2) }.apply { start() }
        // The second segment is read while the first one is still on its way.
        val buf = ByteArray(2)
        assertEquals(2, live.read(4, buf, 0, 2))
        assertArrayEquals(byteArrayOf(20, 21), buf)
        gate.countDown()
        warm.join(5_000); reader.join(5_000)
        assertEquals(2, waiting[0])
        assertEquals(listOf("s1", "s2"), fetched.toList()) // the reader of s1 waited for the warm-up's fetch
    }

    @Test fun theFirstSegmentFetchedAheadIsReadWithoutAnotherFetch() {
        val segs = mapOf("s1" to ByteArray(5) { (10 + it).toByte() }, "s2" to ByteArray(3))
        val fetched = mutableListOf<String>()
        val live = DashLive(byteArrayOf(1, 2), listOf("s1", "s2"), longArrayOf(5, 3)) { fetched += it; segs.getValue(it) }
        live.warm()
        live.warm() // already there: not fetched twice
        assertEquals(listOf("s1"), fetched)
        val buf = ByteArray(5)
        assertEquals(5, live.read(2, buf, 0, 5))
        assertArrayEquals(segs.getValue("s1"), buf)
        assertEquals(listOf("s1"), fetched)
    }
}
