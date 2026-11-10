package com.music.spotui.ui.screens

import com.music.spotui.data.preferences.TransitionPref.Mode as TransitionMode
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
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
    // A page inside Settings (Transitions and AutoMix's options): its own screen, back returns to the tab.
    var sub by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = sub != null) { sub = null }
    // One tab per group (see below); kept up here so a search hit can open one.
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    // Settings' search: null while closed.
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = query != null) { query = null }
    Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = { if (sub != null) sub = null else navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = AppText)
            }
            Text(stringResource(if (sub == SUB_TRANSITIONS) R.string.settings_transitions_title else R.string.settings_title),
                color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (sub == null) IconButton(onClick = { query = if (query == null) "" else null }) {
                Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = stringResource(R.string.settings_search), tint = AppText)
            }
        }
        // The search button: a field over the tabs, the rows of every tab it finds; a tap opens its tab.
        query?.let { q ->
            val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            androidx.compose.material3.OutlinedTextField(
                value = q, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_search_hint), color = AppMuted) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Search, null, tint = AppMuted) },
                shape = RoundedCornerShape(24.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppText, unfocusedTextColor = AppText, focusedBorderColor = com.music.spotui.ui.theme.AppAccent,
                    unfocusedBorderColor = tone(0xFF3E3E3E), cursorColor = com.music.spotui.ui.theme.AppAccent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(Modifier.focusRequester(focus)),
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            if (q.isNotBlank()) {
                val hits = SettingsSearch.search(SettingsSearch.classic, q) { ctx.getString(it) }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                    if (hits.isEmpty()) Text(stringResource(R.string.settings_search_empty), color = AppMuted, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                    hits.forEach { h ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                query = null
                                if (h.place == SUB_TRANSITIONS) sub = SUB_TRANSITIONS else tab = h.place.toInt()
                            }.padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text(stringResource(h.title), color = AppText, fontSize = 16.sp)
                            if (h.detail != 0) Text(stringResource(h.detail), color = AppMuted, fontSize = 12.sp)
                        }
                    }
                }
                return@Column
            }
        }
        if (sub == SUB_TRANSITIONS) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                TransitionsRow()
                Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace()))
            }
            return@Column
        }
        // One tab per group, so the page is no longer one long list. What is changed most comes
        // first: playback, then the sources (which extension plays and downloads first).
        val tabs = listOf(R.string.settings_section_playback, R.string.settings_sources_tab, R.string.settings_section_lyrics, R.string.settings_section_downloads,
            R.string.settings_tab_appearance, R.string.settings_tab_about, R.string.accounts_title)
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
            // The first source of each order, when it is an extension announcing settings in that tab.
            val registry by com.music.spotui.extensions.Extensions.registry.collectAsStateWithLifecycle()
            // A source picked in the player for this session leads too ([com.music.spotui.extensions.SessionSource]).
            val session by com.music.spotui.extensions.Extensions.sessionSource.collectAsStateWithLifecycle()
            if (tab == 0) {
            Section(R.string.settings_section_playback)
            // An extension above Spotify: its settings, and Spotify's quality (kept, still used for what Spotify plays) leaves.
            val ext = remember(registry, session) { com.music.spotui.extensions.Extensions.sectionPlacement(app.myspoti.extensions.SettingSection.PLAYBACK) }
            ext?.let { ExtensionSectionSettings(it) }
            if (ext?.hidesAppQuality != true) {
                StreamingQualityPicker(title = R.string.quality_streaming_wifi_title)
                StreamingQualityPicker(cellular = true, title = R.string.quality_streaming_cell_title)
            }
            OptionRow(
                label = stringResource(R.string.settings_autoplay_title),
                detail = stringResource(R.string.settings_autoplay_detail),
                checked = com.music.spotui.data.preferences.AutoplayPref.on,
                onChecked = { com.music.spotui.data.preferences.AutoplayPref.set(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.settings_release_notify),
                detail = stringResource(R.string.settings_release_notify_detail),
                checked = com.music.spotui.data.NewReleaseNotifier.on,
                onChecked = { com.music.spotui.data.NewReleaseNotifier.setOn(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.myspoti_shuffle_title),
                detail = stringResource(R.string.myspoti_shuffle_detail),
                checked = com.music.spotui.data.preferences.AppShufflePref.on,
                onChecked = { com.music.spotui.data.preferences.AppShufflePref.set(ctx, it) },
            )
            TransitionsLink(onOpen = { sub = SUB_TRANSITIONS })
            // The local player's equalizer, speed and silence skipping (never Spotify's own songs).
            Column(
                Modifier.fillMaxWidth().clickable { navController.navigate(com.music.spotui.ui.navigation.Routes.Equalizer.route) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.equalizer_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(R.string.equalizer_settings_detail), color = AppMuted, fontSize = 12.sp)
            }
            // What keeps the music going and what the headset buttons do.
            Section(R.string.settings_section_controls)
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
            // What other devices see of this phone, and a Jam joined here.
            Section(R.string.settings_section_connect)
            // Songs an extension streams, played by Spotify too (unheard): counted there, seen by Connect.
            if (com.music.spotui.extensions.Extensions.sources(app.myspoti.extensions.Action.AUDIO_SOURCE).isNotEmpty())
                OptionRow(
                    label = stringResource(R.string.settings_connect_mirror_title),
                    detail = stringResource(R.string.settings_connect_mirror_detail),
                    checked = com.music.spotui.data.preferences.ConnectMirrorPref.on,
                    onChecked = { com.music.spotui.data.preferences.ConnectMirrorPref.set(ctx, it) },
                )
            // Downloaded songs, played from their files online, played by Spotify too (unheard), as an extension's.
            OptionRow(
                label = stringResource(R.string.settings_download_sync_title),
                detail = stringResource(R.string.settings_download_sync_detail),
                checked = com.music.spotui.data.preferences.DownloadSyncPref.on,
                onChecked = { com.music.spotui.data.preferences.DownloadSyncPref.set(ctx, it) },
            )
            Section(R.string.settings_section_library)
            // Live Radio: adds the pinned Library row under Local Files (see com.music.spotui.radio).
            OptionRow(
                label = stringResource(R.string.live_radio_settings_title),
                detail = stringResource(R.string.live_radio_settings_detail),
                checked = com.music.spotui.radio.RadioFeaturePref.on,
                onChecked = { com.music.spotui.radio.RadioFeaturePref.set(ctx, it) },
            )
            // Songs marked "Not interested": skipped by lists, listed here to be shown again.
            Column(
                Modifier.fillMaxWidth().clickable { navController.navigate(com.music.spotui.ui.navigation.Routes.HiddenSongs.route) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.hidden_songs_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(R.string.hidden_songs_detail), color = AppMuted, fontSize = 12.sp)
            }
            // Podcasts: SponsorBlock (SponsorBlockSettings.kt).
            SponsorBlockSettings()

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
            // Samsung's "Sleeping apps" is a second list: the app has to be in Never sleeping apps too.
            if (app.myspoti.power.BatteryExemption.isSamsung()) Column(
                Modifier.fillMaxWidth().clickable { app.myspoti.power.BatteryExemption.openAppSettings(ctx) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.background_playback_open), color = AppText, fontSize = 16.sp)
                Text(stringResource(R.string.background_playback_samsung), color = AppMuted, fontSize = 12.sp)
            }
            }
            if (tab == TAB_LYRICS) {
            // Lyrics: word by word (karaoke) first, or line by line with Spotify's first.
            Section(R.string.settings_section_lyrics)
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
            // NetEase and Genius after Spotify and LRCLIB (also in the lyrics' own menu).
            var lyricsExtra by remember { mutableStateOf(com.music.spotui.data.preferences.isLyricsExtraSources(ctx)) }
            OptionRow(
                label = stringResource(R.string.lyrics_extra_title),
                detail = stringResource(R.string.lyrics_extra_summary),
                checked = lyricsExtra,
                onChecked = {
                    lyricsExtra = it
                    com.music.spotui.data.preferences.setLyricsExtraSources(ctx, it)
                    com.music.spotui.data.api.LyricsApi.extraSources = it
                    com.music.spotui.data.api.LyricsApi.extraSourcesOff = !it
                    com.music.spotui.data.api.LyricsApi.forgetAll()
                },
            )
            OptionRow(
                label = stringResource(R.string.settings_lyrics_keep_awake_title),
                detail = stringResource(R.string.settings_lyrics_keep_awake_detail),
                checked = com.music.spotui.data.preferences.LyricsKeepAwakePref.on,
                onChecked = { com.music.spotui.data.preferences.LyricsKeepAwakePref.set(ctx, it) },
            )
            // The song in Latin letters under its lines, when the lyrics carry one (as in the lyrics' menu).
            OptionRow(
                label = stringResource(R.string.lyrics_romanization),
                detail = stringResource(R.string.settings_lyrics_romanization_detail),
                checked = com.music.spotui.data.preferences.LyricsRomanizationPref.on,
                onChecked = { com.music.spotui.data.preferences.LyricsRomanizationPref.set(ctx, it) },
            )
            LyricsTranslationRow()
            }
            if (tab == TAB_DOWNLOADS) {
            Section(R.string.settings_section_downloads)
            // Its role quality takes the place of the app's (kept, and still passed to every extension as `quality`).
            val ext = remember(registry) { com.music.spotui.extensions.Extensions.sectionPlacement(app.myspoti.extensions.SettingSection.DOWNLOADS) }
            if (ext?.hidesAppQuality != true) DownloadQualityPicker()
            ext?.let { ExtensionSectionSettings(it) }
            // Where downloads come from: the download-source extensions, in the order they are asked.
            if (com.music.spotui.extensions.Extensions.sources(app.myspoti.extensions.Action.DOWNLOAD_SOURCE).isNotEmpty()) Column(
                Modifier.fillMaxWidth().clickable { tab = TAB_SOURCES }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.sources_download_title), color = AppText, fontSize = 16.sp)
                Text(sourceLineSummary(app.myspoti.extensions.Action.DOWNLOAD_SOURCE).orEmpty(), color = AppMuted, fontSize = 12.sp)
            }
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
            // A downloaded song's lyrics kept with it, shown without a connection.
            OptionRow(
                label = stringResource(R.string.settings_download_lyrics_title),
                detail = stringResource(R.string.settings_download_lyrics_detail),
                checked = com.music.spotui.data.preferences.LyricsOfflinePref.on,
                onChecked = { com.music.spotui.data.preferences.LyricsOfflinePref.set(ctx, it) },
            )
            // What the app keeps on the phone: the caches (emptied here) and the downloads.
            Section(R.string.settings_section_storage)
            StorageRows()
            if (com.music.spotui.data.offline.OfflineStore.tracks.isNotEmpty()) RemoveDownloadsRow()
            }
            if (tab == TAB_LOOK) {

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
            var canvasWifiOnly by remember { mutableStateOf(com.music.spotui.data.preferences.isCanvasWifiOnly(ctx)) }
            OptionRow(
                label = stringResource(R.string.canvas_wifi_only),
                detail = stringResource(R.string.canvas_wifi_only_detail),
                checked = canvasWifiOnly,
                onChecked = { canvasWifiOnly = it; com.music.spotui.data.preferences.setCanvasWifiOnly(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.settings_music_video_title),
                detail = stringResource(R.string.settings_music_video_detail),
                checked = com.music.spotui.data.preferences.MusicVideoPref.on,
                onChecked = { com.music.spotui.data.preferences.MusicVideoPref.set(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.settings_player_lyrics_card_title),
                detail = stringResource(R.string.settings_player_lyrics_card_detail),
                checked = com.music.spotui.data.preferences.PlayerLyricsCardPref.on,
                onChecked = { com.music.spotui.data.preferences.PlayerLyricsCardPref.set(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.settings_player_source_title),
                detail = stringResource(R.string.settings_player_source_detail),
                checked = com.music.spotui.data.preferences.PlayerSourceLinePref.on,
                onChecked = { com.music.spotui.data.preferences.PlayerSourceLinePref.set(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.settings_solid_nav_title),
                detail = stringResource(R.string.settings_solid_nav_detail),
                checked = com.music.spotui.data.preferences.SolidNavBar.on,
                onChecked = { com.music.spotui.data.preferences.SolidNavBar.set(ctx, it) },
            )
            if (com.music.spotui.data.preferences.SongPipPref.available) OptionRow(
                label = stringResource(R.string.settings_song_pip_title),
                detail = stringResource(R.string.settings_song_pip_detail),
                checked = com.music.spotui.data.preferences.SongPipPref.on,
                onChecked = { com.music.spotui.data.preferences.SongPipPref.set(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.settings_hide_dj_title),
                detail = stringResource(R.string.settings_hide_dj_detail),
                checked = com.music.spotui.data.preferences.HideDjPref.on,
                onChecked = { com.music.spotui.data.preferences.HideDjPref.set(ctx, it) },
            )
            // Material You: the wallpaper's accent (Android 12+), in place of the one picked below.
            if (com.music.spotui.data.preferences.DynamicColorPref.available) OptionRow(
                label = stringResource(R.string.settings_dynamic_color_title),
                detail = stringResource(R.string.settings_dynamic_color_detail),
                checked = com.music.spotui.data.preferences.DynamicColorPref.on,
                onChecked = { com.music.spotui.data.preferences.DynamicColorPref.set(ctx, it) },
            )
            if (!com.music.spotui.data.preferences.DynamicColorPref.active) AccentRow(com.music.spotui.data.preferences.Look.CLASSIC)
            OptionRow(
                label = stringResource(R.string.settings_system_font_title),
                detail = stringResource(R.string.settings_system_font_detail),
                checked = com.music.spotui.data.preferences.AppFontPref.system,
                onChecked = { com.music.spotui.data.preferences.AppFontPref.set(ctx, it) },
            )
            LookRow()
            OrientationRow()
            LanguageRow()
            }
            if (tab == TAB_SOURCES) {
            val anySource = com.music.spotui.extensions.Extensions.sources(app.myspoti.extensions.Action.AUDIO_SOURCE).isNotEmpty() ||
                com.music.spotui.extensions.Extensions.sources(app.myspoti.extensions.Action.DOWNLOAD_SOURCE).isNotEmpty()
            if (!anySource) Text(stringResource(R.string.settings_sources_none), color = AppMuted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            if (com.music.spotui.extensions.Extensions.sources(app.myspoti.extensions.Action.AUDIO_SOURCE).isNotEmpty())
                SourcesBlock(app.myspoti.extensions.Action.AUDIO_SOURCE, navController)
            if (com.music.spotui.extensions.Extensions.sources(app.myspoti.extensions.Action.DOWNLOAD_SOURCE).isNotEmpty())
                SourcesBlock(app.myspoti.extensions.Action.DOWNLOAD_SOURCE, navController)
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
            if (tab == 5) {
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
            // adb logcat, which the person reporting the bug cannot run.
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
            if (tab == 6) {
            // The Spotify accounts kept on the phone: switch, add, remove; and signing this one out.
            Section(R.string.accounts_title)
            com.music.spotui.ui.components.AccountsList()
            Divider()
            Text(
                stringResource(R.string.connect_device_menu_logout), color = AppText, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clickable { com.music.spotui.di.SessionActions.logout(com.music.spotui.di.Graph.app) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
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
internal fun Section(title: Int) {
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
private const val SUB_TRANSITIONS = "transitions"
private const val TAB_SOURCES = 1
private const val TAB_LYRICS = 2
private const val TAB_DOWNLOADS = 3
private const val TAB_LOOK = 4

/** Transitions in the Playback tab: what is on, one line; its choices and AutoMix's options on their own page. */
@Composable
private fun TransitionsLink(onOpen: () -> Unit) {
    val seconds = com.music.spotui.data.preferences.CrossfadePref.seconds
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_transitions_title), color = AppText, fontSize = 16.sp)
            Text(stringResource(R.string.settings_transitions_detail), color = AppMuted, fontSize = 12.sp)
        }
        Text(
            when (com.music.spotui.data.preferences.TransitionPref.mode) {
                TransitionMode.AUTOMIX -> stringResource(R.string.settings_automix_title)
                TransitionMode.CROSSFADE -> stringResource(R.string.settings_transition_crossfade_value, seconds)
                TransitionMode.OFF -> stringResource(R.string.settings_crossfade_off)
            },
            color = com.music.spotui.ui.theme.AppAccent, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AppMuted)
    }
}

/**
 * Transitions: off, a crossfade (its length under it) or AutoMix (its options under it), with
 * what the choice does in one line.
 */
@Composable
private fun TransitionsRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val transition = com.music.spotui.data.preferences.TransitionPref
    val automix = com.music.spotui.automix.AutoMix
    val mode = transition.mode
    var intro by remember { mutableStateOf(false) }
    @Composable
    fun Chip(label: String, selected: Boolean, onClick: () -> Unit) = Text(
        label,
        color = if (selected) Color.Black else AppText, fontSize = 14.sp,
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(if (selected) com.music.spotui.ui.theme.AppAccent else tone(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_transitions_title), color = AppText, fontSize = 16.sp)
        Text(
            stringResource(R.string.settings_transitions_detail),
            color = AppMuted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Chip(stringResource(R.string.settings_crossfade_off), mode == TransitionMode.OFF) { transition.set(ctx, TransitionMode.OFF) }
            Chip(stringResource(R.string.settings_crossfade_title), mode == TransitionMode.CROSSFADE) { transition.set(ctx, TransitionMode.CROSSFADE) }
            Chip(stringResource(R.string.settings_automix_title), mode == TransitionMode.AUTOMIX) {
                transition.set(ctx, TransitionMode.AUTOMIX)
                if (!com.music.spotui.ui.components.AutoMixIntro.seen) intro = true
            }
        }
        Text(
            stringResource(when (mode) {
                TransitionMode.OFF -> R.string.settings_transition_off_detail
                TransitionMode.CROSSFADE -> R.string.settings_transition_crossfade_detail
                TransitionMode.AUTOMIX -> R.string.settings_transition_automix_detail
            }),
            color = AppMuted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        // The long explanation of AutoMix, as the Immersive page gives it under the choice.
        if (mode == TransitionMode.AUTOMIX) Text(
            stringResource(R.string.settings_automix_detail),
            color = AppMuted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        // Why the song playing will not mix into the next one, while that is so.
        if (mode == TransitionMode.AUTOMIX) com.music.spotui.ui.components.autoMixReasonText(automix.whyNot())?.let {
            Text(stringResource(it), color = com.music.spotui.ui.theme.AppAccent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        // Any length from 1 to 12 s on a slider (it was four fixed choices).
        if (mode == TransitionMode.CROSSFADE) com.music.spotui.ui.components.CrossfadeSlider(
            com.music.spotui.ui.theme.AppAccent, AppText, AppMuted, Modifier.padding(top = 12.dp))
    }
    if (mode == TransitionMode.AUTOMIX) {
        OptionRow(stringResource(R.string.settings_automix_session), stringResource(R.string.settings_automix_session_detail),
            checked = automix.sessionDj, onChecked = automix::changeSessionDj)
        OptionRow(stringResource(R.string.settings_automix_segue), stringResource(R.string.settings_automix_segue_detail),
            checked = automix.segue, onChecked = automix::changeSegue)
        OptionRow(stringResource(R.string.settings_automix_album_order), stringResource(R.string.settings_automix_album_order_detail),
            checked = automix.albumOrder, onChecked = automix::changeAlbumOrder)
        OptionRow(stringResource(R.string.settings_automix_podcasts), stringResource(R.string.settings_automix_podcasts_detail),
            checked = automix.mixPodcasts, onChecked = automix::changeMixPodcasts)
        OptionRow(stringResource(R.string.settings_automix_harmonic), stringResource(R.string.settings_automix_harmonic_detail),
            checked = automix.harmonic, onChecked = automix::changeHarmonic)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            OptionRow(stringResource(R.string.settings_automix_eq), stringResource(R.string.settings_automix_eq_detail),
                checked = automix.djEq, onChecked = automix::changeDjEq)
        }
        OptionRow(stringResource(R.string.settings_automix_show), stringResource(R.string.settings_automix_show_detail),
            checked = automix.showInPlayer, onChecked = automix::changeShowInPlayer)
        OptionRow(stringResource(R.string.settings_automix_mobile_data), stringResource(R.string.settings_automix_mobile_data_detail),
            checked = automix.analyzeOnMobileData, onChecked = automix::changeAnalyzeOnMobileData)
        // Chips wrap: the style's labels do not fit one line in every language.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        @Composable
        fun Choices(title: Int, detail: Int?, labels: List<Int>, selected: Int, pick: (Int) -> Unit) =
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(stringResource(title), color = AppText, fontSize = 16.sp)
                detail?.let { Text(stringResource(it), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    labels.forEachIndexed { i, l -> Chip(stringResource(l), selected == i) { pick(i) } }
                }
            }
        Choices(R.string.settings_automix_on_next, R.string.settings_automix_on_next_detail,
            listOf(R.string.settings_automix_next_cut, R.string.settings_automix_next_quick, R.string.settings_automix_next_full),
            automix.nextStyle, automix::changeNextStyle)
        Choices(R.string.settings_automix_style, R.string.settings_automix_style_detail,
            listOf(R.string.settings_automix_style_blend, R.string.settings_automix_style_cut, R.string.settings_automix_style_fade),
            automix.style, automix::changeStyle)
        Choices(R.string.settings_automix_length, R.string.settings_automix_length_detail,
            listOf(R.string.settings_automix_length_short, R.string.settings_automix_length_auto, R.string.settings_automix_length_long),
            automix.mixLength, automix::changeMixLength)
        Choices(R.string.settings_automix_tempo, R.string.settings_automix_tempo_detail,
            listOf(R.string.settings_automix_tempo_strict, R.string.settings_automix_tempo_normal, R.string.settings_automix_tempo_wide),
            automix.tempoRange, automix::changeTempoRange)
        if (automix.showInPlayer) Choices(R.string.settings_automix_pulse, null,
            listOf(R.string.settings_crossfade_off, R.string.settings_automix_pulse_subtle, R.string.settings_automix_pulse_strong),
            automix.pulseLevel, automix::changePulseLevel)
        AutoMixCacheRow()
    }
    if (intro) androidx.compose.material3.AlertDialog(
        onDismissRequest = { intro = false; com.music.spotui.ui.components.AutoMixIntro.markSeen() },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.settings_automix_intro_title), color = AppText) },
        text = { Text(stringResource(R.string.settings_automix_intro_text), color = AppMuted) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { intro = false; com.music.spotui.ui.components.AutoMixIntro.markSeen() }) {
                Text(stringResource(R.string.settings_automix_intro_ok), color = com.music.spotui.ui.theme.AppAccent)
            }
        },
    )
}

/** The AutoMix analyses kept on the phone: their space, and a button that drops them (analysed again when played). */
@Composable
private fun AutoMixCacheRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val bytes by androidx.compose.runtime.produceState<Long?>(null, refresh) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.music.spotui.automix.AutoMix.cacheBytes() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_automix_cache), color = AppText, fontSize = 16.sp)
            Text(
                bytes?.let { stringResource(R.string.settings_automix_cache_detail, android.text.format.Formatter.formatShortFileSize(ctx, it)) }.orEmpty(),
                color = AppMuted, fontSize = 12.sp,
            )
        }
        androidx.compose.material3.TextButton(
            enabled = (bytes ?: 0L) > 0L,
            onClick = {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.music.spotui.automix.AutoMix.clearCache() }
                    refresh++
                }
            },
        ) { Text(stringResource(R.string.settings_automix_cache_clear), color = com.music.spotui.ui.theme.AppAccent) }
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

/** "App language": follow the phone, or any language the app has strings for. */
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

/**
 * "Translation language": the language lyrics are translated into, picked from the app's languages
 * (the same list and names as "App language"); Automatic is the phone's language.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LyricsTranslationRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var current by remember { mutableStateOf(com.music.spotui.data.preferences.getLyricTranslateLang(ctx)) }
    var picking by remember { androidx.compose.runtime.mutableStateOf(false) }
    val auto = stringResource(R.string.language_auto)
    fun label(tag: String) = if (tag == com.music.spotui.data.AppLanguage.SYSTEM) auto else com.music.spotui.data.AppLanguage.nativeName(tag)
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.lyrics_translation_title), color = AppText, fontSize = 16.sp)
        Text(label(current), color = AppMuted, fontSize = 12.sp)
    }
    if (picking) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { picking = false },
        containerColor = tone(0xFF282828),
    ) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            item {
                Text(stringResource(R.string.lyrics_translation_title), color = AppText, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Text(stringResource(R.string.lyrics_translation_detail), color = AppMuted, fontSize = 12.sp,
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
                            current = tag
                            com.music.spotui.data.preferences.setLyricTranslateLang(ctx, tag)
                        }
                    }.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(label(tag), color = if (selected) com.music.spotui.ui.theme.AppAccent else AppText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Check, null, tint = com.music.spotui.ui.theme.AppAccent)
                }
            }
        }
    }
}

/** A size in MB as the storage rows show it ("256 MB", "1 GB"). */
@Composable
internal fun cacheSizeLabel(mb: Int): String =
    if (mb >= 1024) stringResource(R.string.settings_storage_gb, mb / 1024) else stringResource(R.string.settings_storage_mb, mb)

/** Storage: the media cache's size (applied when the app starts again) and the caches' space, with a button that empties them. */
@Composable
private fun StorageRows() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val storage = com.music.spotui.data.AppStorage
    var refresh by remember { mutableStateOf(0) }
    var clearing by remember { mutableStateOf(false) }
    val usage by androidx.compose.runtime.produceState<com.music.spotui.data.AppStorage.Usage?>(null, refresh) { value = storage.usage(ctx) }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_storage_media_cache), color = AppText, fontSize = 16.sp)
        val open = storage.mediaCacheMbOpen
        Text(
            stringResource(if (open != null && open != storage.mediaCacheMb) R.string.settings_storage_media_cache_restart else R.string.settings_storage_media_cache_detail),
            color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            for (mb in storage.MEDIA_CACHE_CHOICES) {
                val selected = storage.mediaCacheMb == mb
                Text(
                    cacheSizeLabel(mb),
                    color = if (selected) Color.Black else AppText, fontSize = 14.sp,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(if (selected) com.music.spotui.ui.theme.AppAccent else tone(0xFF2A2A2A))
                        .clickable { storage.chooseMediaCacheMb(mb) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable(enabled = !clearing) {
            clearing = true
            scope.launch {
                storage.clearCaches(ctx)
                clearing = false
                refresh++
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.settings_storage_cleared), android.widget.Toast.LENGTH_SHORT).show()
            }
        }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.settings_storage_clear_cache), color = AppText, fontSize = 16.sp)
        Text(
            usage?.let { stringResource(R.string.settings_storage_clear_cache_detail, android.text.format.Formatter.formatShortFileSize(ctx, it.caches)) }.orEmpty(),
            color = AppMuted, fontSize = 12.sp,
        )
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
internal fun OptionRow(label: String, detail: String, checked: Boolean,
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
