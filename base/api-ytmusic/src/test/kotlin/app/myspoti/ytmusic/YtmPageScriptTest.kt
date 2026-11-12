package app.myspoti.ytmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `ytm-page.js` run under Node in a stand-in of the page (`node/ytm-page-harness.js`): the messages it
 * posts and what it does to the player. Skipped where Node is not installed.
 */
class YtmPageScriptTest {
    private fun node(): String? {
        val bin = System.getProperty("myspoti.node")?.takeIf { it.isNotBlank() } ?: "node"
        return runCatching {
            val p = ProcessBuilder(bin, "--version").redirectErrorStream(true).start()
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) bin else null
        }.getOrNull()
    }

    private fun resourceFile(path: String): File {
        val tmp = File.createTempFile("msp", ".js").apply { deleteOnExit() }
        tmp.writeText(YtmPageScriptTest::class.java.getResourceAsStream(path)!!.bufferedReader().readText())
        return tmp
    }

    @Test fun `the page script plays only the asked song, holds the page unheard, leaves ads alone`() {
        val bin = node()
        assumeTrue(bin != null, "Node is not installed")
        val harness = resourceFile("/node/ytm-page-harness.js")
        val script = resourceFile("/ytmusic/ytm-page.js")
        val p = ProcessBuilder(bin, harness.path, script.path).redirectErrorStream(true).start()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.exitValue(), out)
        val r = Json.parseToJsonElement(out.trim().lines().last()).jsonObject
        val posted = r["posted"]!!.jsonArray.map { it.jsonObject }
        fun type(o: JsonObject) = o["type"]!!.jsonPrimitive.content
        val ready = posted.first()
        assertEquals("ready", type(ready))
        assertEquals("3", ready["page"]!!.jsonPrimitive.content)
        assertEquals("true", ready["signedIn"]!!.jsonPrimitive.content)
        assertEquals("1.2.3", ready["clientVersion"]!!.jsonPrimitive.content)
        // A song nobody asked for is paused at once.
        assertEquals("pause", posted[1]["did"]!!.jsonPrimitive.content)
        assertEquals(listOf("/watch?v=vidAAAAAAA1"), r["assigned"]!!.jsonArray.map { it.jsonPrimitive.content })
        val seek = posted.first { it["did"]?.jsonPrimitive?.content == "seek" }
        assertEquals("42", seek["s"]!!.jsonPrimitive.content)
        // The ad is reported and left to play: no pause right after it.
        val adAt = posted.indexOfFirst { type(it) == "state" && it["state"]!!.jsonObject["ad"]!!.jsonPrimitive.content == "true" }
        assertTrue(adAt > 0)
        assertTrue(posted.drop(adAt).takeWhile { type(it) != "result" }.none { it["did"]?.jsonPrimitive?.content == "pause" })
        val unknown = posted.first { type(it) == "result" && it["id"]!!.jsonPrimitive.content == "4" }
        assertEquals("false", unknown["ok"]!!.jsonPrimitive.content)
        // Nothing asked any more: the page's song is paused.
        assertEquals("pause", posted.dropLast(2).last()["did"]!!.jsonPrimitive.content)
        assertEquals("ready", type(posted.last()), "injected again: announced again, not run twice")
        assertEquals(0.001, r["volume"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue(r["stored"]!!.jsonArray.isEmpty())
    }

    @Test fun `a song is opened inside the web app, at the lowest video quality, its state posted on media events`() {
        val bin = node()
        assumeTrue(bin != null, "Node is not installed")
        val harness = resourceFile("/node/ytm-page-harness.js")
        val script = resourceFile("/ytmusic/ytm-page.js")
        val p = ProcessBuilder(bin, harness.path, script.path, "inapp").redirectErrorStream(true).start()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.exitValue(), out)
        val r = Json.parseToJsonElement(out.trim().lines().last()).jsonObject
        fun strings(k: String) = r[k]!!.jsonArray.map { it.jsonPrimitive.content }
        val result = r["posted"]!!.jsonArray.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "result" }
        assertEquals("inapp", result["value"]!!.jsonPrimitive.content)
        assertEquals(listOf("yt-navigate vidBBBBBBB2"), strings("navigated"))
        // The app's navigation took: no page load.
        assertTrue(strings("assigned").isEmpty())
        assertTrue("tiny-tiny vidBBBBBBB2" in strings("quality"), out)
        assertTrue("playing capture" in strings("listened") && "pause capture" in strings("listened"))
    }
}
