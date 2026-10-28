package com.music.spotui

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import com.music.spotui.ui.components.pressable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Scaffold
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.music.spotui.ui.navigation.MainBottomNavigation
import com.music.spotui.ui.navigation.MyNavHost
import com.music.spotui.ui.navigation.navigateSingleTop


@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App() {
    val bottomBarState = rememberSaveable { (mutableStateOf(true)) }
    val bottomBarPlayerState = rememberSaveable { (mutableStateOf(true)) }
    val navController = rememberNavController()

    // Debug and beta builds: `myspoti-cli nav <route>` (see CliReceiver).
    LaunchedEffect(Unit) { com.music.spotui.cli.CliReceiver.nav.collect { r -> runCatching { navController.navigate(r) } } }
    // A page the onboarding asked for ("See the extensions").
    LaunchedEffect(Unit) {
        com.music.spotui.ui.screens.Setup.openAfter?.let { r -> com.music.spotui.ui.screens.Setup.openAfter = null; runCatching { navController.navigate(r) } }
    }

    // "See the extensions" in the dialog a download shows when no download extension is installed.
    LaunchedEffect(Unit) { com.music.spotui.ui.components.DownloadAsk.openRoute.collect { r -> runCatching { navController.navigateSingleTop(r) } } }

    UpdatePrompt()
    SupportPrompt()
    // The playlists at the top of the library, fetched ahead on Wi-Fi (see PagePrefetch).
    LaunchedEffect(Unit) { com.music.spotui.data.api.PagePrefetch.start() }

    // A collaborative-playlist invitation was opened (see InviteLinks): its playlist page offers "Join".
    val invite by com.music.spotui.data.api.InviteLinks.pending.collectAsState()
    LaunchedEffect(invite) {
        val i = invite?.takeIf { !it.opened } ?: return@LaunchedEffect
        app.myspoti.host.android.MyspotiRuntime.api.ready.first { it }
        i.opened = true
        navController.navigateSingleTop(com.music.spotui.ui.navigation.playlistRoute(i.playlistId))
    }

    // A Blend invitation was opened (see InviteLinks): who invites, and "Join", over the app.
    val blendLink by com.music.spotui.data.api.InviteLinks.blend.collectAsState()
    blendLink?.let { link ->
        com.music.spotui.ui.screens.BlendInviteSheet(link, navController) {
            if (com.music.spotui.data.api.InviteLinks.blend.value === link) com.music.spotui.data.api.InviteLinks.blend.value = null
        }
    }

    // Genius's explanations of the song playing, read while it starts: ready when the lyrics open.
    val geniusLang = com.music.spotui.ui.components.readerLanguage()
    val playingUri = com.music.spotui.di.SongPlayer.nowPlaying.value.uri
    LaunchedEffect(playingUri) {
        if (!playingUri.startsWith("spotify:track:")) return@LaunchedEffect
        kotlinx.coroutines.delay(2_000) // the song's own loads first
        val st = com.music.spotui.di.Graph.currentSongState
        com.music.spotui.data.api.GeniusMeaning.prefetch(playingUri.substringAfterLast(':'), st.title.value, st.singer.value, geniusLang)
    }
    // A shared link to a list / artist / podcast: its page, over whatever was open.
    val pageLink by com.music.spotui.data.api.InviteLinks.page.collectAsState()
    LaunchedEffect(pageLink) {
        val uri = pageLink ?: return@LaunchedEffect
        com.music.spotui.data.api.InviteLinks.page.value = null
        val id = uri.substringAfterLast(':')
        com.music.spotui.ui.navigation.PlayerSheet.close()
        runCatching {
            navController.navigateSingleTop(when (uri.split(':')[1]) {
                "playlist" -> com.music.spotui.ui.navigation.playlistRoute(id)
                "album" -> com.music.spotui.ui.navigation.albumRoute("", "", id)
                "artist" -> com.music.spotui.ui.navigation.artistRoute("artist", id)
                else -> com.music.spotui.ui.navigation.showRoute(id)
            })
        }
    }
    val shareTarget by com.music.spotui.ui.components.SpotifyCode.request.collectAsState()
    shareTarget?.let { t -> com.music.spotui.ui.components.SpotifyCodeSheet(t) { com.music.spotui.ui.components.SpotifyCode.request.value = null } }

    // The look (Settings > Display) switches the shell live. The nav host keeps one place in the
    // tree in both looks, so a switch keeps the back stack, the pages' state and the playback.
    val immersive = com.music.spotui.ui.navigation.isImmersive()
    val haze = com.music.spotui.ui.immersive.shell.rememberImmersiveHazeState()
    // Liquid Glass (Settings > Display): the pages record themselves for the glass to refract.
    // Not provided while it is off or unsupported, so nothing is recorded for no reader.
    val glassPage = com.music.spotui.ui.immersive.shell.rememberImmersiveBackdrop()
    val liquidGlass = immersive && com.music.spotui.ui.immersive.shell.rememberLiquidGlassActive()
    // Without it (Android 12+), the frost blurs the same recording, which follows page and tab
    // transitions frame by frame where Haze lagged behind.
    val frostPage = immersive && !liquidGlass && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    // The floating chrome's fold (iOS 26): pages scroll it through immersiveChromeScroll().
    val chrome = com.music.spotui.ui.immersive.shell.rememberImmersiveChromeState()
    androidx.compose.runtime.CompositionLocalProvider(
        com.music.spotui.ui.look.LocalReduceMotion provides com.music.spotui.ui.look.rememberReduceMotion(),
        com.music.spotui.ui.immersive.shell.LocalImmersiveHaze provides if (immersive) haze else null,
        com.music.spotui.ui.immersive.shell.LocalImmersiveBackdrop provides if (liquidGlass) glassPage else null,
        com.music.spotui.ui.immersive.shell.LocalImmersiveFrostPage provides if (frostPage) glassPage else null,
        com.music.spotui.ui.immersive.shell.LocalImmersiveChrome provides if (immersive) chrome else null,
    ) {
    // Large windows (tablets, foldables, DeX): Classic's tabs in a rail at the side, the
    // Immersive tab bar on the left edge (ImmersiveBottomChrome); phones keep the bottom bars.
    val large = com.music.spotui.ui.components.windowSize().large
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
      androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
        // Before the Scaffold, in a group of its own: the nav host keeps its place when the
        // window grows or shrinks past the threshold (back stack and pages kept).
        if (large && !immersive) com.music.spotui.ui.navigation.MainNavigationRail(navController)
        Scaffold(
            modifier = Modifier.weight(1f),
            bottomBar = {
                if (!immersive) MainBottomNavigation(navController = navController, bottomBarState = bottomBarState, bottomBarPlayerState)
            }
        ) {
            MyNavHost(
                navHostController = navController, bottomBarState = bottomBarState, bottomBarPlayerState,
                // Immersive: each page marks its own list as what the glass bars blur (a source on
                // the whole host would hold the pages' own top bars too).
                modifier = if (immersive) Modifier.background(com.music.spotui.ui.immersive.Immersive.Page) else Modifier,
            )
        }
      }
        if (immersive) ImmersiveShell(navController)
        // Over the pages and the bottom bar: pulling it down shows them, already in place.
        else com.music.spotui.ui.navigation.PlayerOverlay(navController)
    }
    }
}



/**
 * The Immersive look's floating chrome: the mini player capsule over the tab bar (both hidden
 * on the full-screen routes; folded into one row while a page scrolls down), and its player over
 * everything.
 */
@Composable
private fun ImmersiveShell(navController: androidx.navigation.NavHostController) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        com.music.spotui.ui.immersive.shell.ImmersiveBottomChrome(
            navController,
            miniPlayer = { m -> com.music.spotui.ui.immersive.player.ImmersiveMiniPlayer(navController, m) },
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
        com.music.spotui.ui.immersive.player.ImmersivePlayerOverlay(navController)
    }
}

/** The update card is on screen: the donation reminder waits for another launch. */
private val updateCardOpen = mutableStateOf(false)

/**
 * The release the update card shows. The launch check fills it; so does the manual check in
 * Settings, which is why it is not private to [UpdatePrompt].
 */
internal val updateRelease = mutableStateOf<com.music.spotui.data.api.UpdateChecker.Release?>(null)

/**
 * The donation reminder (see SupportReminder): a card a few seconds after launch, only
 * when due, signed in, and no update card is open. "No thanks" closes it for 2 weeks.
 */
@Composable
private fun SupportPrompt() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var show by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(12_000)
        val signedIn = context.getSharedPreferences("myspoti_prefs", android.content.Context.MODE_PRIVATE).getBoolean("LoggedIn", false)
        show = signedIn && !updateCardOpen.value && com.music.spotui.data.api.SupportReminder.isDue(context)
    }
    if (!show) return
    val close = { com.music.spotui.data.api.SupportReminder.shown(context); show = false }
    SupportDialog(
        supporter = com.music.spotui.data.api.SupportReminder.level != com.music.spotui.data.api.SupportReminder.Level.NONE,
        onSupport = { close(); com.music.spotui.data.api.SupportReminder.open(context) },
        onSponsor = { close(); com.music.spotui.data.api.SupportReminder.openSponsors(context) },
        onDecline = close,
        onSupported = { level -> com.music.spotui.data.api.SupportReminder.setLevel(context, level); close() },
    )
}

/**
 * The reminder card: a request, or a thank-you for someone who already gave. "I've
 * supported" (on trust) asks once or monthly, and unlocks the supporter thank-yous.
 */
@Composable
private fun SupportDialog(supporter: Boolean, onSupport: () -> Unit, onSponsor: () -> Unit, onDecline: () -> Unit, onSupported: (com.music.spotui.data.api.SupportReminder.Level) -> Unit) {
    // The owner's reminder: no extension draws, listens or acts over it.
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.myspoti.extensions.ProtectedSurface.SUPPORT)
    var choosing by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDecline) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(tone(0xFF242424))
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(app.myspoti.engine.R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(tone(0xFF181818)),
            )
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(if (supporter) app.myspoti.engine.R.string.supporter_thanks_title else app.myspoti.engine.R.string.support_title),
                color = AppText, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(if (supporter) app.myspoti.engine.R.string.supporter_thanks_body else app.myspoti.engine.R.string.support_body),
                color = AppText.copy(alpha = 0.8f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 14.sp, lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            androidx.compose.foundation.layout.Box(
                contentAlignment = androidx.compose.ui.Alignment.Center,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .pressable(0.96f, onClick = onSupport)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(com.music.spotui.ui.theme.AppAccent),
            ) {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(if (supporter) app.myspoti.engine.R.string.support_again else app.myspoti.engine.R.string.support_action),
                    color = androidx.compose.ui.graphics.Color.Black, fontFamily = com.music.spotui.ui.theme.AppSans,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp,
                )
            }
            com.music.spotui.ui.components.GitHubSponsorButton(Modifier.padding(top = 10.dp), onClick = onSponsor)
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(app.myspoti.engine.R.string.support_decline),
                color = AppText, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp).pressable(0.96f, onClick = onDecline).padding(horizontal = 24.dp, vertical = 12.dp),
            )
            if (!supporter && com.music.spotui.data.api.SupportReminder.PERKS_ENABLED) {
                if (choosing) androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp),
                ) { com.music.spotui.ui.components.SupportChoice(onChosen = onSupported) }
                else androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(app.myspoti.engine.R.string.supporter_i_supported),
                    color = AppText.copy(alpha = 0.6f), fontFamily = com.music.spotui.ui.theme.AppSans,
                    fontSize = 13.sp,
                    modifier = Modifier.pressable(0.96f) { choosing = true }.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * "New version" dialog: checked at launch and on every return to the app (off the UI thread), shown as soon as the answer is
 * known. "Later" hides it; Settings > About offers the update again.
 */
@Composable
private fun UpdatePrompt() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var release by updateRelease
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(owner) {
        // Each time the app comes to the foreground (the check itself skips when the last one is
        // under an hour old), then every 6 h while it stays open. Turned off in Settings: no
        // automatic check; the manual one there still asks.
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                if (com.music.spotui.data.preferences.AutoUpdateCheckPref.on) {
                    // The request runs on the IO dispatcher, nothing waits for it.
                    com.music.spotui.data.api.UpdateChecker.check(context)?.let { release = it }
                }
                kotlinx.coroutines.delay(com.music.spotui.data.api.UpdateChecker.PERIODIC_MS)
            }
        }
    }
    updateCardOpen.value = release != null
    val r = release ?: return
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.myspoti.extensions.ProtectedSurface.UPDATE)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val updater = com.music.spotui.data.api.AppUpdater
    val state by updater.state.collectAsState()
    val start = { scope.launch { updater.update(context, r) }; Unit }
    // Back from the "Install unknown apps" page with the switch on: carry on by itself.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME &&
                updater.state.value == com.music.spotui.data.api.AppUpdater.State.NeedsPermission &&
                updater.canInstall(context)) start()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val dismiss = { updater.reset(); com.music.spotui.data.api.UpdateChecker.dismiss(context, r); release = null }
    val res = context.resources
    val status = when (val s = state) {
        is com.music.spotui.data.api.AppUpdater.State.Downloading ->
            res.getString(app.myspoti.engine.R.string.update_downloading, (s.progress.coerceAtLeast(0f) * 100).toInt())
        com.music.spotui.data.api.AppUpdater.State.Installing -> res.getString(app.myspoti.engine.R.string.update_installing)
        com.music.spotui.data.api.AppUpdater.State.NeedsPermission -> res.getString(app.myspoti.engine.R.string.update_needs_permission)
        is com.music.spotui.data.api.AppUpdater.State.Failed -> res.getString(app.myspoti.engine.R.string.update_failed, s.message)
        com.music.spotui.data.api.AppUpdater.State.Idle -> null
    }
    val busy = state is com.music.spotui.data.api.AppUpdater.State.Downloading || state == com.music.spotui.data.api.AppUpdater.State.Installing
    UpdateDialog(
        version = r.version,
        notes = r.notes,
        status = status,
        progress = (state as? com.music.spotui.data.api.AppUpdater.State.Downloading)?.progress,
        button = res.getString(when (state) {
            com.music.spotui.data.api.AppUpdater.State.NeedsPermission -> app.myspoti.engine.R.string.update_allow
            is com.music.spotui.data.api.AppUpdater.State.Failed -> app.myspoti.engine.R.string.update_retry
            else -> app.myspoti.engine.R.string.version_sunsetting_dialog_cta_update
        }),
        onUpdate = {
            when {
                busy -> Unit
                // A release without an APK: its page, as before.
                r.apkUrl.isBlank() -> {
                    release = null
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(r.pageUrl))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                state == com.music.spotui.data.api.AppUpdater.State.NeedsPermission && !updater.canInstall(context) ->
                    updater.openInstallSettings(context)
                else -> start()
            }
        },
        onLater = { if (!busy) dismiss() },
    )
}

/** The update card, in the app's own style (dark sheet, green pill), not a system dialog. */
@Composable
private fun UpdateDialog(
    version: String, notes: String, status: String?, progress: Float?, button: String,
    onUpdate: () -> Unit, onLater: () -> Unit,
) {
    // Only the buttons close it: a touch outside must not (the back gesture still means "Later").
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onLater,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(tone(0xFF242424))
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(app.myspoti.engine.R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(tone(0xFF181818)),
            )
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(app.myspoti.engine.R.string.main_update_available),
                color = AppText, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            androidx.compose.material3.Text(
                "Myspoti $version", color = AppText.copy(alpha = 0.7f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp),
            )
            // What's new (the release's CHANGELOG section), scrollable if long.
            if (notes.isNotBlank()) androidx.compose.material3.Text(
                notes, color = AppText.copy(alpha = 0.8f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(max = 240.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            )
            // The download and install, under the notes.
            if (status != null) androidx.compose.material3.Text(
                status, color = AppText.copy(alpha = 0.8f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            )
            if (progress != null) {
                val bar = Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                if (progress < 0f) androidx.compose.material3.LinearProgressIndicator(
                    modifier = bar, color = com.music.spotui.ui.theme.AppAccent, trackColor = AppText.copy(alpha = 0.15f))
                else androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress }, modifier = bar, color = com.music.spotui.ui.theme.AppAccent,
                    trackColor = AppText.copy(alpha = 0.15f), drawStopIndicator = {})
            }
            androidx.compose.foundation.layout.Box(
                contentAlignment = androidx.compose.ui.Alignment.Center,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .pressable(0.96f, onClick = onUpdate)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(com.music.spotui.ui.theme.AppAccent),
            ) {
                androidx.compose.material3.Text(
                    button,
                    color = androidx.compose.ui.graphics.Color.Black, fontFamily = com.music.spotui.ui.theme.AppSans,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp,
                )
            }
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(app.myspoti.engine.R.string.age_assurance_intro_cta_not_now),
                color = AppText, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp).pressable(0.96f, onClick = onLater).padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
