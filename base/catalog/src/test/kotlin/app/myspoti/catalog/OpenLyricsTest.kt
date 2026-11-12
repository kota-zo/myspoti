/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenLyricsTest {
    @Test fun `a TTML file gives its lines word by word, spaces kept and roles left out`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
            <p begin="00:03.351" end="00:05.000"><span begin="00:03.351" end="00:03.563">I</span> <span begin="00:03.563" end="00:03.822">was</span><span ttm:role="x-translation">trad</span></p>
            <p begin="1:02.5" end="1:03"><span begin="1:02.5" end="1:03">Next</span></p></div></body></tt>"""
        val l = OpenLyrics.parseTtml(ttml)!!
        assertEquals(2, l.size)
        assertEquals("I was", l[0].text)
        assertEquals(3351L, l[0].startMs)
        assertEquals(listOf(3351L, 3563L), l[0].words!!.map { it.startMs })
        assertEquals(62_500L, l[1].startMs)
    }

    @Test fun `NetEase YRC rows become word-timed lines, credits left out`() {
        val l = OpenLyrics.parseYrc("""{"t":0,"c":[{"tx":"x"}]}
            |[1000,2000](1000,500,0)One (1500,500,0)more
            |[3000,500](3000,500,0)作词 : Someone""".trimMargin())
        assertEquals(1, l.size)
        assertEquals("One more", l[0].text)
        assertEquals(2000L, l[0].words!![1].endMs)
    }

    @Test fun `word-timed lyrics win over line-timed ones`() = runBlocking {
        val amll = """<tt><body><div><p begin="0:01" end="0:02"><span begin="0:01" end="0:02">Hey</span></p></div></body></tt>"""
        val fetch = Fetch { url ->
            when {
                "amll" in url -> Response(200, amll)
                "lrclib" in url && "/api/get" in url -> Response(200, """{"syncedLyrics":"[00:01.00]Hey","plainLyrics":"Hey"}""")
                else -> Response(404, "")
            }
        }
        val found = OpenLyrics(fetch).find("abc123", "Song", "Band", null, 120_000)!!
        assertEquals("AMLL TTML DB", found.provider)
        assertTrue(found.wordSynced)
        val noId = OpenLyrics(fetch).find(null, "Song", "Band", null, 120_000)!!
        assertEquals("LRCLIB", noId.provider)
        assertNull(OpenLyrics(Fetch { Response(404, "") }).find(null, "Song", "Band", null, 120_000))
    }
}
