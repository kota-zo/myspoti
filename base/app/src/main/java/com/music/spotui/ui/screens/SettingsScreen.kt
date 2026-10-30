package com.music.spotui.ui.screens

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.myspoti.engine.R
import kotlinx.coroutines.launch
import com.music.spotui.ui.components.DownloadQualityPicker
import com.music.spotui.ui.components.StreamingQualityPicker
import com.music.spotui.ui.theme.AppBackground

/**
 * Settings, on one page: sound quality (streaming and downloads), the download source,
 * data and battery, display, language, and the player's own repair tools.
 */
@Composable
fun SettingsScreen(navController: NavController) {
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = AppText)
            }
            Text(stringResource(R.string.settings_title), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        // One tab per group, so the page is no longer one long list.
        var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
        val tabs = listOf(R.string.settings_section_playback, R.string.settings_section_downloads, R.string.settings_tab_appearance,
            R.string.extensions_title, R.string.settings_tab_about)
        androidx.compose.material3.ScrollableTabRow(
            selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = AppText, edgePadding = 12.dp,
            indicator = { positions ->
                androidx.compose.material3.TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(positions[tab]), color = com.music.spotui.ui.theme.AppAccent)
            },
            divider = { HorizontalDivider(color = tone(0xFF2A2A2A)) },
        ) {
            tabs.forEachIndexed { i, title ->
                androidx.compose.material3.Tab(
                    selected = tab == i, onClick = { tab = i },
                    text = { Text(stringResource(title), fontSize = 14.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) },
                    selectedContentColor = AppText, unselectedContentColor = AppMuted,
                )
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (tab == 0) {
            Section(R.string.settings_section_playback)
            StreamingQualityPicker(title = R.string.quality_streaming_wifi_title)
            StreamingQualityPicker(cellular = true, title = R.string.quality_streaming_cell_title)
            var canvasWifiOnly by remember { mutableStateOf(com.music.spotui.data.preferences.isCanvasWifiOnly(ctx)) }
            OptionRow(
                label = stringResource(R.string.canvas_wifi_only),
                detail = stringResource(R.string.canvas_wifi_only_detail),
                checked = canvasWifiOnly,
                onChecked = { canvasWifiOnly = it; com.music.spotui.data.preferences.setCanvasWifiOnly(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.settings_autoplay_title),
                detail = stringResource(R.string.settings_autoplay_detail),
                checked = com.music.spotui.data.preferences.AutoplayPref.on,
                onChecked = { com.music.spotui.data.preferences.AutoplayPref.set(ctx, it) },
            )
            CrossfadeRow()
            var keepPlaying by remember { mutableStateOf(app.myspoti.host.android.PlaybackService.keepsPlayingOnClose(ctx)) }
            OptionRow(
                label = stringResource(R.string.settings_keep_playing_title),
                detail = stringResource(R.string.settings_keep_playing_detail),
                checked = keepPlaying,
                onChecked = { keepPlaying = it; app.myspoti.host.android.PlaybackService.setKeepsPlayingOnClose(ctx, it) },
            )
            var headsetSkip by remember { mutableStateOf(app.myspoti.host.android.PlaybackService.skipsOnEpisodes(ctx)) }
            OptionRow(
                label = stringResource(R.string.settings_headset_skip_title),
                detail = stringResource(R.string.settings_headset_skip_detail),
                checked = headsetSkip,
                onChecked = { headsetSkip = it; app.myspoti.host.android.PlaybackService.setSkipsOnEpisodes(ctx, it) },
            )
            // Lyrics: word by word (karaoke) first, or line by line with Spotify's first.
            var wordSync by remember { mutableStateOf(com.music.spotui.data.preferences.isLyricsWordSync(ctx)) }
            OptionRow(
                label = stringResource(R.string.lyrics_word_sync_title),
                detail = stringResource(if (wordSync) R.string.lyrics_word_sync_on else R.string.lyrics_word_sync_off),
                checked = wordSync,
                onChecked = {
                    wordSync = it
                    com.music.spotui.data.preferences.setLyricsWordSync(ctx, it)
                    com.music.spotui.data.api.LyricsApi.wordSync = it
                    com.music.spotui.data.api.LyricsApi.forgetAll()
                },
            )
            // Songs an extension streams, played by Spotify too (unheard): counted there, seen by Connect.
            if (com.music.spotui.extensions.Extensions.sources(app.myspoti.extensions.Action.AUDIO_SOURCE).isNotEmpty())
                OptionRow(
                    label = stringResource(R.string.settings_connect_mirror_title),
                    detail = stringResource(R.string.settings_connect_mirror_detail),
                    checked = com.music.spotui.data.preferences.ConnectMirrorPref.on,
                    onChecked = { com.music.spotui.data.preferences.ConnectMirrorPref.set(ctx, it) },
                )
            // Live Radio (radio/ module): adds the pinned row under Local Files and the player.
            OptionRow(
                label = stringResource(R.string.live_radio_settings_title),
                detail = stringResource(R.string.live_radio_settings_detail),
                checked = com.music.spotui.radio.RadioFeaturePref.on,
                onChecked = { com.music.spotui.radio.RadioFeaturePref.set(ctx, it) },
            )
            Divider()
            SourcesBlock(app.myspoti.extensions.Action.AUDIO_SOURCE, navController)

            Section(R.string.settings_section_data)
            OptionRow(
                label = stringResource(R.string.energy_saver_title),
                detail = stringResource(R.string.energy_saver_summary),
                checked = energy.manualSaver,
                onChecked = app.myspoti.power.EnergySettings::setSaver,
            )
            OptionRow(
                label = stringResource(R.string.data_saver_title),
                detail = stringResource(R.string.data_saver_summary),
                checked = com.music.spotui.data.DataSaver.enabled,
                onChecked = com.music.spotui.data.DataSaver::set,
            )
            // Battery optimization off = music and Connect keep going with the screen off.
            var exempt by remember { androidx.compose.runtime.mutableStateOf(app.myspoti.power.BatteryExemption.isExempt(ctx)) }
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                exempt = app.myspoti.power.BatteryExemption.isExempt(ctx)
            }
            OptionRow(
                label = stringResource(R.string.background_playback_title),
                detail = stringResource(if (exempt) R.string.background_playback_on else R.string.background_playback_off),
                checked = exempt,
                onChecked = {
                    if (exempt) ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    else app.myspoti.power.BatteryExemption.request(ctx)
                },
            )
            }
            if (tab == 1) {
            Section(R.string.settings_section_downloads)
            DownloadQualityPicker()
            // Where downloads come from: the download-source extensions, in the order they are asked.
            if (com.music.spotui.extensions.Extensions.sources(app.myspoti.extensions.Action.DOWNLOAD_SOURCE).isNotEmpty())
                SourcesBlock(app.myspoti.extensions.Action.DOWNLOAD_SOURCE, navController)
            else Column(
                Modifier.fillMaxWidth().clickable { navController.navigate(com.music.spotui.ui.navigation.Routes.Extensions.route) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.downloads_source_row_title), color = AppText, fontSize = 16.sp)
                Text(
                    stringResource(
                        if (com.music.spotui.extensions.Extensions.hasDownloadSource()) R.string.downloads_source_row_on
                        else R.string.downloads_source_row_off,
                    ),
                    color = AppMuted, fontSize = 12.sp,
                )
            }
            if (com.music.spotui.data.offline.OfflineStore.tracks.isNotEmpty()) RemoveDownloadsRow()
            }
            if (tab == 2) {

            Section(R.string.settings_section_display)
            OptionRow(
                label = stringResource(R.string.settings_amoled_theme),
                detail = stringResource(R.string.settings_amoled_theme_subtitle),
                checked = energy.blackPreference,
                onChecked = app.myspoti.power.EnergySettings::setBlackBackground,
            )
            OptionRow(
                label = stringResource(R.string.settings_item_canvas_title),
                detail = stringResource(if (energy.policy.saverEnabled) R.string.energy_saver_summary else R.string.video_settings_canvas_subtitle),
                checked = energy.canvasEnabled && !energy.policy.saverEnabled,
                enabled = !energy.policy.saverEnabled,
                onChecked = app.myspoti.power.EnergySettings::setCanvasEnabled,
            )
            OptionRow(
                label = stringResource(R.string.settings_music_video_title),
                detail = stringResource(R.string.settings_music_video_detail),
                checked = com.music.spotui.data.preferences.MusicVideoPref.on,
                onChecked = { com.music.spotui.data.preferences.MusicVideoPref.set(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.settings_solid_nav_title),
                detail = stringResource(R.string.settings_solid_nav_detail),
                checked = com.music.spotui.data.preferences.SolidNavBar.on,
                onChecked = { com.music.spotui.data.preferences.SolidNavBar.set(ctx, it) },
            )
            AccentRow(com.music.spotui.data.preferences.Look.CLASSIC)
            LookRow()
            OrientationRow()
            LanguageRow()
            }
            if (tab == 3) {
            ExtensionSettingsRows(navController)
            // Third-party extensions: lyrics, a song's audio, scripts reacting to the music.
            Column(
                Modifier.fillMaxWidth().clickable { navController.navigate(com.music.spotui.ui.navigation.Routes.Extensions.route) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.extensions_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(R.string.extensions_row_detail), color = AppMuted, fontSize = 12.sp)
            }
            }
            if (tab == 4) {
            Section(R.string.settings_section_app)
            AboutRow()
            // A18: the launch check can be turned off; the manual check below keeps working.
            OptionRow(
                label = stringResource(R.string.settings_auto_update_title),
                detail = stringResource(R.string.settings_auto_update_detail),
                checked = com.music.spotui.data.preferences.AutoUpdateCheckPref.on,
                onChecked = { com.music.spotui.data.preferences.AutoUpdateCheckPref.set(ctx, it) },
            )
            // The hidden Web Player stuck or crashed: rebuild it without leaving the app.
            Column(
                Modifier.fillMaxWidth().clickable {
                    app.myspoti.host.android.MyspotiRuntime.host.reload()
                    // Reported: nothing said the restart happened.
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.restart_player_done), android.widget.Toast.LENGTH_SHORT).show()
                }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.restart_player_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(R.string.restart_player_detail), color = AppMuted, fontSize = 12.sp)
            }
            // The report a tester hands us. The engine's log is otherwise reachable only through
            // adb logcat, which the person reporting the bug cannot run: the label is a literal
            // rather than a string resource so this change stays out of the shared string files.
            Column(
                Modifier.fillMaxWidth().clickable {
                    val file = runCatching { app.myspoti.host.android.Logbook.export(ctx) }.getOrNull()
                    if (file != null) shareDiagnosticReport(ctx, file)
                    else android.widget.Toast.makeText(ctx, ctx.getString(R.string.diagnostic_export_failed), android.widget.Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.diagnostic_export_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(R.string.diagnostic_export_detail), color = AppMuted, fontSize = 12.sp)
            }
            }
            // Clear of the mini player and the navigation bar, as every other page (96 dp left the
            // last rows, "Restart the player" among them, under the mini player).
            Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace()))
        }
    }
}

/**
 * The installed extensions that declare settings (manifest `settings`), each one opening its page,
 * where they are edited. Nothing when none has any.
 */
@Composable
private fun ExtensionSettingsRows(navController: NavController) {
    val registry by com.music.spotui.extensions.Extensions.registry.collectAsStateWithLifecycle()
    val withSettings = registry.extensions.mapNotNull { e ->
        com.music.spotui.extensions.Extensions.manifest(e.id)?.settings?.takeIf { it.isNotEmpty() }?.let { e to it }
    }
    if (withSettings.isEmpty()) return
    Section(R.string.extensions_title)
    withSettings.forEach { (e, settings) ->
        Column(
            Modifier.fillMaxWidth().clickable { navController.navigate(installedRoute(e.id)) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(e.name, color = AppText, fontSize = 16.sp)
            Text(settings.joinToString { it.label }, color = AppMuted, fontSize = 12.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
    Divider()
}

/** The order a source action asks extensions in ([SourceOrderList]); nothing without any such extension. */
@Composable
private fun SourcesBlock(action: app.myspoti.extensions.Action, navController: NavController) {
    val registry by com.music.spotui.extensions.Extensions.registry.collectAsStateWithLifecycle()
    val audio = action == app.myspoti.extensions.Action.AUDIO_SOURCE
    if (remember(registry) { com.music.spotui.extensions.Extensions.sources(action).isEmpty() }) return
    Section(if (audio) R.string.sources_audio_title else R.string.sources_download_title)
    SourceOrderList(action, spotifyLast = audio, look = SourceLook.Classic, onOpen = { navController.navigate(installedRoute(it)) })
    Text(stringResource(if (audio) R.string.sources_audio_footer else R.string.sources_download_footer), color = AppMuted, fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    if (audio) Divider()
}

@Composable
private fun Section(title: Int) {
    Text(stringResource(title).uppercase(), color = com.music.spotui.ui.theme.AppAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Divider() = HorizontalDivider(color = tone(0xFF2A2A2A), modifier = Modifier.padding(top = 8.dp))

/** Classic or Immersive: the choice made in the onboarding, changeable here. */
@Composable
private fun LookRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = com.music.spotui.data.preferences.LookPref.look
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_look_title), color = AppText, fontSize = 16.sp)
        for (l in com.music.spotui.data.preferences.Look.entries) {
            val classic = l == com.music.spotui.data.preferences.Look.CLASSIC
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .semantics { role = Role.RadioButton }
                    .clickable { com.music.spotui.data.preferences.LookPref.set(ctx, l) }
                    .padding(vertical = 8.dp),
            ) {
                androidx.compose.material3.RadioButton(selected = current == l, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(if (classic) R.string.look_classic else R.string.look_immersive), color = AppText, fontSize = 15.sp)
                    Text(stringResource(if (classic) R.string.look_classic_detail else R.string.look_immersive_detail), color = AppMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

/** The accent colour of one look, for everyone (the same palette the supporter sheet uses). */
@Composable
private fun AccentRow(look: com.music.spotui.data.preferences.Look) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.supporter_accent), color = AppText, fontSize = 16.sp)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 10.dp)) {
            for (a in com.music.spotui.data.api.SupportReminder.Accent.entries) {
                val selected = com.music.spotui.data.api.SupportReminder.accentOf(look) == a
                val name = stringResource(when (a) {
                    com.music.spotui.data.api.SupportReminder.Accent.GREEN -> R.string.color_green
                    com.music.spotui.data.api.SupportReminder.Accent.BLUE -> R.string.color_blue
                    com.music.spotui.data.api.SupportReminder.Accent.PURPLE -> R.string.color_purple
                    com.music.spotui.data.api.SupportReminder.Accent.PINK -> R.string.color_pink
                    com.music.spotui.data.api.SupportReminder.Accent.ORANGE -> R.string.color_orange
                })
                Box(
                    Modifier.size(40.dp)
                        .semantics { contentDescription = name; role = Role.RadioButton }
                        .clip(CircleShape).background(a.color)
                        .then(if (selected) Modifier.border(3.dp, AppText, CircleShape) else Modifier)
                        .clickable { com.music.spotui.data.api.SupportReminder.setAccent(ctx, look, a) },
                )
            }
        }
    }
}

/**
 * "Crossfade": the length of the fade at the end of a song. The label says what it is: the two
 * songs never overlap (the page plays one media element), so this is a fade, not a true
 * crossfade. The default is 0: off, as before.
 */
@Composable
private fun CrossfadeRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pref = com.music.spotui.data.preferences.CrossfadePref
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_crossfade_title), color = AppText, fontSize = 16.sp)
        Text(
            stringResource(R.string.settings_crossfade_detail),
            color = AppMuted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            for (v in listOf(0, 3, 6, 9, 12)) {
                val selected = pref.seconds == v
                Text(
                    if (v == 0) stringResource(R.string.settings_crossfade_off)
                    else stringResource(R.string.settings_crossfade_seconds, v),
                    color = if (selected) Color.Black else AppText, fontSize = 14.sp,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(if (selected) com.music.spotui.ui.theme.AppAccent else tone(0xFF2A2A2A))
                        .clickable { pref.set(ctx, v) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Screen rotation: portrait (default), automatic or landscape. */
@Composable
private fun OrientationRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pref = com.music.spotui.data.preferences.OrientationPref
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_rotation_title), color = AppText, fontSize = 16.sp)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            for ((value, label) in listOf(
                pref.PORTRAIT to R.string.settings_rotation_portrait,
                pref.AUTO to R.string.settings_rotation_auto,
                pref.LANDSCAPE to R.string.settings_rotation_landscape,
            )) {
                val selected = pref.mode == value
                Text(
                    stringResource(label),
                    color = if (selected) Color.Black else AppText, fontSize = 14.sp,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(if (selected) com.music.spotui.ui.theme.AppAccent else tone(0xFF2A2A2A))
                        .clickable { pref.set(ctx, value) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** "App language": Automatic (French or English) or any language with Spotify's labels. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LanguageRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = remember { com.music.spotui.data.AppLanguage.current(ctx) }
    var picking by remember { androidx.compose.runtime.mutableStateOf(false) }
    val auto = stringResource(R.string.language_auto)
    fun label(tag: String) = if (tag == com.music.spotui.data.AppLanguage.SYSTEM) auto else com.music.spotui.data.AppLanguage.nativeName(tag)
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.settings_item_app_language_title), color = AppText, fontSize = 16.sp)
        Text(label(current), color = AppMuted, fontSize = 12.sp)
    }
    if (picking) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { picking = false },
        containerColor = tone(0xFF282828),
    ) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            item {
                Text(stringResource(R.string.settings_item_app_language_title), color = AppText, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Text(stringResource(R.string.language_partial_note), color = AppMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            }
            items(com.music.spotui.data.AppLanguage.AVAILABLE.size) { i ->
                val tag = com.music.spotui.data.AppLanguage.AVAILABLE[i]
                val selected = tag == current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                        picking = false
                        if (!selected) {
                            com.music.spotui.data.AppLanguage.apply(ctx, tag)
                            // Screens take the new language when the activity is rebuilt.
                            generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
                                .filterIsInstance<android.app.Activity>().firstOrNull()?.recreate()
                        }
                    }.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(label(tag), color = if (selected) com.music.spotui.ui.theme.AppAccent else AppText, fontSize = 16.sp)
                        if (tag == com.music.spotui.data.AppLanguage.SYSTEM)
                            Text(stringResource(R.string.language_auto_detail), color = AppMuted, fontSize = 12.sp)
                    }
                    if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Check, null, tint = com.music.spotui.ui.theme.AppAccent)
                }
            }
        }
    }
}

/** "Remove all downloads", with the space they take; asks first, like the official app. */
@Composable
private fun RemoveDownloadsRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var ask by remember { androidx.compose.runtime.mutableStateOf(false) }
    val size = android.text.format.Formatter.formatShortFileSize(ctx, com.music.spotui.data.offline.OfflineStore.totalBytes())
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable { ask = true }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.settings_item_remove_downloads_title), color = AppText, fontSize = 16.sp)
        Text(stringResource(R.string.downloads_size, size), color = AppMuted, fontSize = 12.sp)
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.settings_storage_remove_downloads_dialog_title), color = AppText) },
        text = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_description), color = AppMuted) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                ask = false
                com.music.spotui.data.offline.OfflineDownloader.removeAll()
            }) { Text(stringResource(R.string.playlist_entity_remove_download_dialog_positive_button), color = com.music.spotui.ui.theme.AppAccent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { ask = false }) {
                Text(stringResource(R.string.add_to_playlist_button_cancel), color = AppText)
            }
        },
    )
}



@Composable
private fun OptionRow(label: String, detail: String, checked: Boolean,
    enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
            .padding(horizontal = 20.dp, vertical = 10.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label, color = AppText, fontSize = 16.sp)
            Text(detail, color = AppMuted, fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/**
 * The installed version with a refresh button: it asks GitHub right now (no cache), shows the
 * answer and the time of the last check. A newer release opens the update card on tap.
 */
@Composable
private fun AboutRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val check = com.music.spotui.data.api.rememberUpdateCheckState(ctx)
    // Also what "Later" put aside: the card in the app offers it again from here.
    val live = com.music.spotui.updateRelease.value
    val known = live ?: remember { com.music.spotui.data.api.UpdateChecker.known(ctx) }
    Row(
        Modifier.fillMaxWidth()
            .then(if (known != null) Modifier.clickable { com.music.spotui.updateRelease.value = known } else Modifier)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_about_version, app.myspoti.engine.BuildConfig.VERSION_NAME), color = AppText, fontSize = 16.sp)
            if (known != null) {
                Text(stringResource(R.string.settings_update_available, known.version), color = com.music.spotui.ui.theme.AppAccent, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                Text(stringResource(R.string.settings_update_available_detail), color = AppMuted, fontSize = 12.sp)
            }
            check.message?.takeIf { known == null || check.isError }?.let {
                Text(it, color = if (check.isError) androidx.compose.material3.MaterialTheme.colorScheme.error else AppText, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Text(check.lastCheckedText, color = AppMuted, fontSize = 12.sp)
        }
        if (check.checking) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp, color = AppMuted)
        } else {
            androidx.compose.material3.IconButton(onClick = { scope.launch { check.run() } }) {
                androidx.compose.material3.Icon(Icons.Default.Refresh, stringResource(R.string.settings_update_refresh), tint = AppMuted)
            }
        }
    }
}

/**
 * Hands the exported report to the share sheet. The file goes through the app's FileProvider;
 * if the provider refuses the path (external storage absent), the report's own path is shared
 * instead, so the tester still has something to send.
 */
internal fun shareDiagnosticReport(context: android.content.Context, file: java.io.File) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Myspoti diagnostic report")
    }
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
    if (uri != null) {
        send.type = "application/octet-stream"
        send.putExtra(android.content.Intent.EXTRA_STREAM, uri)
        send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        send.putExtra(android.content.Intent.EXTRA_TEXT, "Myspoti diagnostic report: ${file.absolutePath}")
    }
    context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
