package app.myspoti.host.cdp

import app.myspoti.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * A Jam joined from the Web Player as a member, the host being another device (the Spotify desktop
 * app on another account): the member's commands must change the host's playback.
 *
 *     ./gradlew :host-cdp:test --tests '*JamLiveTest*' -Pcdp=http://localhost:9333 -Pjam=<join token> \
 *         -PjamHost='python3 hostctl.py cluster'
 *
 * jamHost prints the host's playback as "... | track <uri> | ctx <uri> | paused <bool> ...".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@EnabledIfSystemProperty(named = "myspoti.jam", matches = ".+")
class JamLiveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var host: CdpHost
    private lateinit var api: SpotifyApi

    private fun live(block: suspend () -> Any?) { runBlocking { withTimeout(90_000) { block() } } }

    /** The host's playback, read with the jamHost command. */
    private fun hostState(): Map<String, String> {
        val cmd = System.getProperty("myspoti.jamHost")
        val out = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
        println("host: $out")
        return Regex("""(track|ctx|paused) (\S+)""").findAll(out).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private suspend fun <T> eventually(timeoutMs: Long = 12_000, check: suspend () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { check()?.let { return it }; delay(700) }
        error("condition not reached in $timeoutMs ms")
    }

    @BeforeAll
    fun connect() = live {
        host = CdpHost.connect(System.getProperty("myspoti.cdp"))
        api = SpotifyApi(host, scope, language = "fr")
        host.reload()
        host.awaitLoad()
        api.onPageLoaded()
        api.awaitReady()
    }

    @AfterAll
    fun close() {
        runBlocking { runCatching { api.jam.leave() } }
        host.close()
        scope.cancel()
    }

    @Test @Order(1)
    fun `joins the host's Jam`() = live {
        val s = api.jam.join(System.getProperty("myspoti.jam"))
        println("joined ${s.id} members=${s.members.map { it.name }} host=${s.isHost}")
        api.jam.refresh()
        assertNotNull(api.jam.session.value)
        delay(3_000)
        println("member sees: ${api.player.state.value?.item?.uri} state=${api.player.state.value?.let { it::class.simpleName }}")
    }

    @Test @Order(2)
    fun `next moves the host on`() = live {
        val before = hostState()["track"]
        api.player.next()
        val after = eventually { hostState()["track"]?.takeIf { it != before } }
        assertNotEquals(before, after)
    }

    @Test @Order(3)
    fun `a list played by the member plays on the host`() = live {
        // In a Jam the host plays it as the Jam's own list (spotify:list:jam-list:<session>): its first song is checked.
        val album = "spotify:album:2noRn2Aes5aoNVsU6iWThc"
        val first = api.catalog.albumTracks(album, 0, 1).items.first().uri
        api.player.play(album)
        eventually { hostState()["track"]?.takeIf { it == first } }
    }

    @Test @Order(4)
    fun `pause and resume reach the host`() = live {
        api.player.pause()
        eventually { hostState()["paused"]?.takeIf { it == "True" } }
        api.player.resume()
        eventually { hostState()["paused"]?.takeIf { it == "False" } }
    }

    @Test @Order(5)
    fun `the member is still in the Jam after its player page reloads`() = live {
        host.reload(); host.awaitLoad(); api.onPageLoaded(); api.awaitReady()
        delay(4_000)
        api.jam.refresh()
        println("after a reload: ${api.jam.session.value?.id} members=${api.jam.session.value?.members?.map { it.name }}")
        val before = hostState()["track"]
        api.player.next()
        eventually { hostState()["track"]?.takeIf { it != before } }
    }

    @Test @Order(6)
    fun `the member is still in the Jam`() = live {
        api.jam.refresh()
        val s = api.jam.session.value
        println("still in: ${s?.id} members=${s?.members?.map { it.name }}")
        assertTrue(s != null)
        assertEquals(false, s!!.isHost)
    }
}
