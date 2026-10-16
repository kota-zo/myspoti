package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SponsorBlock: the hash-prefix query, the answer's parsing and the segment to skip. */
class SponsorBlockTest {
    private val episode = "5oKWMVAGiNNUdOLJ4erWci"

    // A real answer's shape: the prefix lists other episodes too, and segments of every kind.
    private val body = """
        [
          {"videoID":"someOtherEpisode0000000","segments":[
            {"category":"sponsor","actionType":"skip","segment":[10,60],"UUID":"other"}]},
          {"videoID":"$episode","segments":[
            {"category":"sponsor","actionType":"skip","segment":[972.267,1094.658],"UUID":"b"},
            {"category":"sponsor","actionType":"skip","segment":[0.304,132.094],"UUID":"a"},
            {"category":"sponsor","actionType":"full","segment":[0,0],"UUID":"label"},
            {"category":"poi_highlight","actionType":"poi","segment":[500,500],"UUID":"poi"},
            {"category":"intro","actionType":"skip","segment":[200,200.5],"UUID":"tiny"}]}
        ]
    """.trimIndent()

    @Test fun theQueryOnlyCarriesAFourCharacterHashPrefix() {
        assertEquals("1ca7", SponsorBlock.hashPrefix(episode))
    }

    @Test fun onlyThisEpisodesSkipSegmentsAreKeptInOrder() {
        val segments = SponsorBlock.parse(episode, body)
        assertEquals(listOf("a", "b"), segments.map { it.uuid })
        assertEquals(304L, segments[0].startMs)
        assertEquals(132_094L, segments[0].endMs)
    }

    @Test fun aBrokenAnswerGivesNoSegments() {
        assertEquals(emptyList<SponsorBlock.Segment>(), SponsorBlock.parse(episode, "<html>"))
    }

    @Test fun theSegmentToSkipFollowsTheChosenCategoriesAndTheListener() {
        val segments = SponsorBlock.parse(episode, body)
        val all = setOf("sponsor")
        assertEquals("a", SponsorBlock.segmentAt(segments, 5_000, all, emptySet())?.uuid)
        assertNull(SponsorBlock.segmentAt(segments, 5_000, setOf("intro"), emptySet()))
        // The listener seeked back into it: it plays.
        assertNull(SponsorBlock.segmentAt(segments, 5_000, all, setOf("a")))
        // Between two segments, and right before one ends: nothing to do.
        assertNull(SponsorBlock.segmentAt(segments, 500_000, all, emptySet()))
        assertNull(SponsorBlock.segmentAt(segments, 131_950, all, emptySet()))
    }
}
