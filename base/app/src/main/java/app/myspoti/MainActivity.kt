// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.App
import com.music.spotui.di.AutoBrowser
import com.music.spotui.ui.screens.WelcomeScreen
import com.music.spotui.ui.theme.SpotuiTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Logcat tag, the host's own: playback and session land in one stream (`adb logcat -s Myspoti`). */
private const val LOG = "Myspoti"

/**
 * How long the player page is given to prove the session (a non-anonymous token) once it comes back.
 * The sign-in page stays in front while this runs — the address alone is never taken for a session —
 * and only a real token hands the screen to the player (see [MainActivity.watchLoginReturn]).
 */
private const val SESSION_WAIT_MS = 6_000L

/** The most a returning page may take to get its bridge ready before the wait gives up anyway. */
private const val SESSION_LOAD_MAX_MS = 60_000L

/**
 * The app's only screen host: the Compose UI, with the Web Player page attached behind it.
 * The page comes to the front only to sign in, on Spotify's own login pages.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private var signedIn by mutableStateOf(false)
    private var showingLogin by mutableStateOf(false)
    /** Back from Spotify's login, waiting for the session: a native loading screen, not the web page. */
    private var connecting by mutableStateOf(false)
    /** A pending wait for the session to prove itself after the login page came back. */
    private var loginWait: kotlinx.coroutines.Job? = null
    /** The one-time setup screen (permissions, battery) after signing in. */
    private var setupNeeded by mutableStateOf(false)
    /** In the picture-in-picture window: only the music video is drawn. */
    private var inPip by mutableStateOf(false)
    /** Debug builds: `am start ... --ez showLook true` opens the look step alone, signed in or not. */
    private var showLook by mutableStateOf(false)
    /** The "new look" popup was already shown (or the onboarding look step covered it). */
    private var lookOffered by mutableStateOf(true)

    private val prefs get() = getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge on every Android version (15+ enforces it, 14 and older do not): the pages draw
        // under the status bar and handle its inset, otherwise a band of the window's colour sits
        // above their gradient on 14 and older.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // The user's rotation choice (portrait by default); changes are applied from the UI below.
        requestedOrientation = com.music.spotui.data.preferences.OrientationPref.requested()
        signedIn = prefs.getBoolean("LoggedIn", false)
        setupNeeded = com.music.spotui.ui.screens.Setup.needed(this)
        lookOffered = com.music.spotui.data.preferences.LookPref.isOffered(this)
        root = FrameLayout(this)
        val compose = ComposeView(this).apply {
            setContent {
                SpotuiTheme { com.music.spotui.ui.look.ProvideLook {
                    val orientation = com.music.spotui.data.preferences.OrientationPref.mode
                    androidx.compose.runtime.LaunchedEffect(orientation) {
                        requestedOrientation = com.music.spotui.data.preferences.OrientationPref.requested()
                    }
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        when {
                            showLook -> com.music.spotui.ui.look.LookStep(onNext = {
                                com.music.spotui.data.preferences.LookPref.setChosen(this@MainActivity); showLook = false
                                setupNeeded = com.music.spotui.ui.screens.Setup.needed(this@MainActivity)
                            })
                            signedIn && setupNeeded -> com.music.spotui.ui.screens.SetupScreen(
                                onDone = { setupNeeded = false },
                            )
                            signedIn -> App()
                            connecting -> ConnectingScreen()
                            else -> WelcomeScreen(onLogin = { showLogin() })
                        }
                        // Existing users, once, right after the update: the new look, live.
                        if (signedIn && !setupNeeded && !showLook && !showingLogin && !inPip && !lookOffered) {
                            if (com.music.spotui.data.preferences.LookPref.look == com.music.spotui.data.preferences.Look.IMMERSIVE) {
                                androidx.compose.runtime.LaunchedEffect(Unit) {
                                    com.music.spotui.data.preferences.LookPref.setOffered(this@MainActivity); lookOffered = true
                                }
                            } else {
                                val close = { look: com.music.spotui.data.preferences.Look ->
                                    com.music.spotui.data.preferences.LookPref.set(this@MainActivity, look)
                                    com.music.spotui.data.preferences.LookPref.setOffered(this@MainActivity)
                                    lookOffered = true
                                }
                                val keep = { close(com.music.spotui.data.preferences.Look.CLASSIC) }
                                androidx.compose.ui.window.Dialog(
                                    onDismissRequest = keep,
                                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                                ) {
                                    com.music.spotui.ui.look.LookStep(
                                        onNext = { close(com.music.spotui.data.preferences.Look.IMMERSIVE) },
                                        offer = com.music.spotui.ui.look.LookOffer(onKeep = keep),
                                    )
                                }
                            }
                        }
                        // "Ask each time": the download quality question, above every screen.
                        com.music.spotui.ui.components.DownloadAskHost()
                        // An extension about to change the Spotify account waits for the user's yes.
                        com.music.spotui.extensions.ExtensionConfirmHost()
                        // An extension's sign-in page, in a web view of its own.
                        com.music.spotui.extensions.ExtensionAuthHost()
                        // Beta and dev builds only: hold the faint dot to copy the recent logs.
                        if (com.music.spotui.data.api.UpdateChecker.isBeta || app.myspoti.engine.BuildConfig.DEBUG)
                            com.music.spotui.ui.components.BetaLogButton(
                                androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomEnd))
                        // Picture-in-picture: the clip alone, over the app (which stays composed underneath,
                        // so coming back finds the same screen).
                        if (inPip) com.music.spotui.ui.components.PipVideo()
                    }
                } }
            }
        }
        root.addView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // Haptics (buttons, likes, the debug icon) go through this window.
        com.music.spotui.di.Haptics.attach(compose)
        // Debug builds: `am start ... --ez showPlayer true` shows the Web Player page full screen.
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        MyspotiRuntime.host.attach(root, visible = debuggable && intent.getBooleanExtra("showPlayer", false))
        showLook = debuggable && intent.getBooleanExtra("showLook", false)
        // Debug builds: `am start ... --ez resetLookOffer true` shows the new-look popup again.
        if (debuggable && intent.getBooleanExtra("resetLookOffer", false)) {
            com.music.spotui.data.preferences.LookPref.clearOffered(this); lookOffered = false
        }
        watchSession()
        watchLoginReturn()
        watchLogout()
        // Only on a fresh start: on recreation (theme, locale, process restore)
        // the same intent would play its link again. New links arrive in onNewIntent.
        if (savedInstanceState == null) handle(intent)
    }

    // Leaving the app (Home, recents) while a music video plays: it goes on in a small window.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val mv = com.music.spotui.video.MusicVideoController
        if (!com.music.spotui.data.preferences.MusicVideoPref.on || !mv.on.value || mv.player == null) return
        if (!com.music.spotui.di.SongPlayer.isPlaying() || isInPictureInPictureMode) return
        val ratio = mv.aspect.floatValue.coerceIn(0.5f, 2.3f)
        runCatching {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational((ratio * 1000).toInt(), 1000))
                    .build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        com.music.spotui.video.MusicVideoController.inPip.value = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // The clip player follows the app on screen: paused with its side jobs in the background.
    override fun onStart() {
        // Beta and dev builds only, like the log button: a stable release has no freeze reports to read.
        if (com.music.spotui.data.api.UpdateChecker.isBeta || app.myspoti.engine.BuildConfig.DEBUG) app.myspoti.host.android.MainThreadWatch.start()
        super.onStart()
        com.music.spotui.video.MusicVideoController.onForeground()
        // Asked for over adb (TestControlReceiver's "screenon"): One UI ignores the huge
        // `screen_off_timeout`, so a long unattended run holds the screen on from in here.
        if (prefs.getBoolean("keep_screen_on", false)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        app.myspoti.host.android.MainThreadWatch.stop()
        if (!isChangingConfigurations) com.music.spotui.video.MusicVideoController.onBackground()
    }

    override fun onDestroy() {
        // The process-wide WebView lives in this activity's root while attached:
        // detach it, or view.parent -> root keeps the destroyed activity (and its
        // whole view tree) alive for the rest of the playback session.
        MyspotiRuntime.host.detach()
        super.onDestroy()
    }

    /** Spotify's login page, full screen, until the Web Player comes back signed in. */
    private fun showLogin() {
        showingLogin = true
        MyspotiRuntime.host.attach(root, visible = true)
        MyspotiRuntime.host.openLogin()
    }

    private fun hideLogin() {
        showingLogin = false
        MyspotiRuntime.host.attach(root, visible = false)
    }

    /** Logout: leave the app's screens for Spotify's login page. */
    private fun watchLogout() {
        lifecycleScope.launch {
            com.music.spotui.di.SessionActions.signedOut.collect { out ->
                if (out && signedIn) {
                    signedIn = false
                    showingLogin = true
                    MyspotiRuntime.host.attach(root, visible = true)
                }
            }
        }
    }

    /**
     * Spotify sent the user back to the player: the page is hidden now, the session follows.
     *
     * The address is not a session. The page comes back to open.spotify.com while its token is
     * still anonymous when the login never finished (the e-mail step that hangs — the reported
     * "stuck on the e-mail screen"), and hiding the page on that alone left a spinner that never
     * stopped. The return is bounded: no token after [SESSION_WAIT_MS], the login page comes back.
     */
    private fun watchLoginReturn() {
        lifecycleScope.launch {
            MyspotiRuntime.host.url.collect { url ->
                val backOnPlayer = url.startsWith("https://open.spotify.com") && !url.contains("/login")
                if (!(showingLogin && backOnPlayer)) return@collect
                // Spotify's desktop player is not shown while the session is confirmed: the app's
                // waiting screen is, behind the page made transparent (reported: it flashed).
                connecting = true
                MyspotiRuntime.host.veil(true)
                // The address is not a session — and it comes back to open.spotify.com as soon as the
                // user leaves the app to read the sign-in e-mail. Hiding the page here showed an
                // anonymous player: an app that looked signed in with nothing playable (reported).
                // The login page therefore stays in FRONT until a real token exists.
                loginWait?.cancel()
                loginWait = launch {
                    var waited = 0L
                    var elapsed = 0L
                    while (waited < SESSION_WAIT_MS && elapsed < SESSION_LOAD_MAX_MS) {
                        if (runCatching { MyspotiRuntime.isSignedIn() }.getOrDefault(false)) {
                            android.util.Log.d(LOG, "session established: player shown")
                            hideLogin()
                            return@launch
                        }
                        // The countdown only runs while the user is looking at the app: away to read
                        // the e-mail, the sign-in page must not be reset under them.
                        // Nor while the player is still loading: it answers nothing before its bridge is
                        // ready, which takes 10 s and more on a slow device (a DAP, a low-end Xiaomi).
                        // Counted from the address alone, the wait ran out under a page that was still
                        // booting and put the sign-in page back: the reported loop to the login page.
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                            elapsed += 500
                            if (MyspotiRuntime.spotify.ready.value) waited += 500
                        }
                        kotlinx.coroutines.delay(500)
                    }
                    android.util.Log.d(LOG, "no session ${SESSION_WAIT_MS / 1000}s after the player came back: sign-in page restored")
                    // The page may now be the anonymous player: send it back to the e-mail step so the
                    // user is not left where nothing plays.
                    connecting = false
                    if (!showingLogin) showLogin() else MyspotiRuntime.host.openLogin()
                }
            }
        }
    }

    /**
     * Back from the e-mail app: the session may have been established while away, or the page may
     * have reloaded into the anonymous player. Only the token decides — never the address.
     */
    override fun onResume() {
        super.onResume()
        if (!showingLogin) return
        lifecycleScope.launch {
            if (MyspotiRuntime.awaitSignedIn(1_500)) {
                android.util.Log.d(LOG, "back with a session: player shown")
                hideLogin()
            } else if (MyspotiRuntime.spotify.ready.value &&
                MyspotiRuntime.host.url.value.let { it.startsWith("https://open.spotify.com") && !it.contains("/login") }) {
                // Ready: a player still booting has not answered yet, and is left to watchLoginReturn.
                android.util.Log.d(LOG, "back without a session: sign-in page restored")
                MyspotiRuntime.host.openLogin()
            }
        }
    }

    /**
     * Signed in = the Web Player is ready with a user token. A Spotify login page while
     * signed in (expired session) brings the page back to the front.
     */
    private fun watchSession() {
        val api = MyspotiRuntime.spotify
        lifecycleScope.launch {
            combine(api.ready, MyspotiRuntime.loginRequired) { ready, login -> ready to login }.collect { (ready, login) ->
                when {
                    login && !showingLogin && signedIn -> showLogin()
                    ready && !login -> {
                        val user = runCatching { MyspotiRuntime.isSignedIn() }.getOrDefault(false)
                        if (user) {
                            if (com.music.spotui.di.SessionActions.signedOut.value) {
                                // Signed in again after a logout, maybe as someone else: start the
                                // screens over (a new activity also drops their view models).
                                com.music.spotui.di.SessionActions.signedIn()
                                prefs.edit().putBoolean("LoggedIn", true).apply()
                                MyspotiRuntime.host.attach(root, visible = false)
                                startActivity(Intent(this@MainActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                                finish()
                                return@collect
                            }
                            if (!signedIn) {
                                prefs.edit().putBoolean("LoggedIn", true).apply()
                                signedIn = true
                            }
                            connecting = false
                            if (showingLogin) hideLogin()
                        } else if (signedIn) {
                            // The pref said "signed in", the token says anonymous: only the token
                            // counts. This is what left the app on its full UI (or on Spotify's
                            // anonymous page) with nothing playable — reported after leaving the app
                            // to read the sign-in e-mail, where the session never completed.
                            android.util.Log.d(LOG, "anonymous token while the app thought it was signed in: back to the sign-in page")
                            prefs.edit().putBoolean("LoggedIn", false).apply()
                            signedIn = false
                            connecting = false
                            showLogin()
                        }
                    }
                }
            }
        }
    }


    /** Spotify links (open.spotify.com/...) play their item; voice search plays its best match. */
    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val link = intent.data?.toString() ?: return
                // A collaborative-playlist invitation (…?pt=) or a spotify.link short link: kept / resolved there.
                if (com.music.spotui.data.api.InviteLinks.handle(link)) return
                val uri = spotifyUri(link) ?: return
                // A shared list (or artist, podcast) opens on its page, as in the official app
                // (reported: a shared playlist started playing and was never shown). A song or an
                // episode still plays.
                if (uri.split(':').getOrNull(1) in setOf("playlist", "album", "artist", "show")) {
                    com.music.spotui.data.api.InviteLinks.page.value = uri
                    return
                }
                // A song, with an audio-source extension on: asked there first, like any play.
                if (uri.startsWith("spotify:track:") && com.music.spotui.extensions.Extensions.hasAudioSource()) {
                    com.music.spotui.di.SongPlayer.playSong(uri)
                    return
                }
                lifecycleScope.launch {
                    MyspotiRuntime.api.ready.first { it }
                    runCatching { MyspotiRuntime.api.player.play(uri) }
                }
            }
            android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
                if (query.isNotBlank()) lifecycleScope.launch { AutoBrowser.playFromSearch(query) }
            }
        }
    }

    /** `https://open.spotify.com/intl-fr/album/{id}?si=…` -> `spotify:album:{id}`. */
    private fun spotifyUri(link: String): String? {
        val m = Regex("(?:open|www|play)\\.spotify\\.com/(?:intl-[a-z-]+/)?(track|album|playlist|artist|show|episode)/([A-Za-z0-9]{22})").find(link) ?: return null
        return "spotify:${m.groupValues[1]}:${m.groupValues[2]}"
    }
}

/** Shown between Spotify's login page and the first screen. */
@androidx.compose.runtime.Composable
private fun ConnectingScreen() {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize().background(com.music.spotui.ui.theme.AppBackground),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(color = com.music.spotui.ui.theme.AppAccent)
    }
}
