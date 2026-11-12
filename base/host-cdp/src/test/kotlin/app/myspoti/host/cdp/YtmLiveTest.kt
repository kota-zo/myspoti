package app.myspoti.host.cdp

import app.myspoti.api.NowPlaying
import app.myspoti.api.sync.ListenSyncHost
import app.myspoti.api.sync.LocalNow
import app.myspoti.api.sync.SyncTrack
import app.myspoti.ytmusic.Ytm
import app.myspoti.ytmusic.YtmAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * YouTube Music on a real account, in the user's own Chrome (signed in there by the user, in the tab
 * of music.youtube.com). Prints counts and states only, never titles or the account's data.
 *
 *     google-chrome --remote-debugging-port=9222 --user-data-dir=$HOME/.cache/msp-ytm &   # sign in there once
 *     ./gradlew :host-cdp:test -Pcdp=http://localhost:9222 --tests '*YtmLiveTest*'
 *     ./gradlew :host-cdp:test -Pcdp=http://localhost:9222 -PytmVideo=<11-char id> --tests '*YtmLiveTest*'
 *
 * With `ytmVideo`, the listen sync plays that song unheard for 45 s as a local player at 1× would
 * have it: check it in YouTube Music's History afterwards.
 */
@EnabledIfSystemProperty(named = "myspoti.cdp", matches = ".+")
class YtmLiveTest {
    @Test
    fun `signed in, library read, and the sync plays a song unheard`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = CdpPageHost.connect(Ytm.ORIGIN, Ytm.HOME_URL, Ytm.SESSION_URLS, System.getProperty("myspoti.cdp"))
        val account = YtmAccount(host, host, OkHttpClient(), scope)
        account.bridge.logger = { println("page: $it") }
        account.open()
        withTimeout(30_000) { account.bridge.ready.first { it } }
        println("LIVE signed in (cookies): ${account.refreshSignIn()}, page says: ${account.page.signedIn}")
        assertTrue(account.signedIn.value, "sign in to YouTube Music in that Chrome first")
        println("LIVE liked songs (first page): ${account.library.likedTracks(0, 25).items.size}")
        println("LIVE playlists: ${account.library.playlists().size}")
        println("LIVE history rows: ${account.library.history(50).size}")
        println("LIVE search answers: ${account.metadata.all("piano", 5).tracks.items.size} songs")

        val video = System.getProperty("myspoti.ytmVideo").orEmpty()
        if (!Ytm.isVideoId(video)) return@runBlocking
        val started = System.currentTimeMillis()
        val syncHost = object : ListenSyncHost {
            fun now() = LocalNow(SyncTrack(ids = mapOf(Ytm.PROVIDER to video)), live = true, playing = true,
                positionMs = System.currentTimeMillis() - started, durationMs = 600_000)
            override fun local() = now()
            override fun queued() = emptyList<SyncTrack>()
            override val localActive = true
            override val routing = false
            override val enabled = true
            override val dataSaver = false
            override val engineIntended = false
            override fun pause() = println("LIVE local pause asked")
            override fun resume() = println("LIVE local resume asked")
            override fun seekTo(ms: Long) = println("LIVE local seek asked")
            override fun next() {}
            override fun previous() {}
            override fun stop() {}
            override fun playRemotePick(item: NowPlaying, contextUri: String?, positionMs: Long) {}
            override fun playHereAgain() {}
            override fun syncRunning(on: Boolean) = println("LIVE sync running: $on")
            override fun log(line: String) = println("LIVE $line")
        }
        val main = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val sync = account.listenSync(syncHost, main, clock = System::currentTimeMillis)
        withContext(main.coroutineContext) { sync.attach(); sync.onLocal(syncHost.now()) }
        scope.launch { account.page.state.collect { s -> println("LIVE page: paused=${s?.paused} buffering=${s?.buffering} ad=${s?.ad} at=${s?.positionMs} asked=${s?.trackId == video}") } }
        repeat(9) { delay(5_000); withContext(main.coroutineContext) { sync.onLocal(syncHost.now()) } }
        println("LIVE status: ${sync.status()}")
        withContext(main.coroutineContext) { sync.stop("live test over") }
        sync.awaitEnded()
        host.close()
    }
}
