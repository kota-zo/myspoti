package com.music.spotui.ui.immersive.pages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.preferences.DownloadQuality
import com.music.spotui.data.preferences.StreamingQuality
import com.music.spotui.data.preferences.getDownloadQuality
import com.music.spotui.data.preferences.getStreamingQuality
import com.music.spotui.data.preferences.setDownloadQuality
import com.music.spotui.data.preferences.setStreamingQuality
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.rememberReduceMotion
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.screens.installedRoute
import com.music.spotui.ui.screens.shareDiagnosticReport
import kotlinx.coroutines.launch

/** Where the Immersive Settings are: the list of categories, one category, or a choice inside it. */
private enum class SettingsPage { Root, Playback, StreamingWifi, StreamingCell, Crossfade, Downloads, DownloadQuality, Data, Display, Look, Rotation, Language, App }

private val StreamingChoices = listOf(
    Triple(StreamingQuality.AUTO, R.string.quality_streaming_auto, R.string.quality_streaming_auto_detail),
    Triple(StreamingQuality.LOW, R.string.quality_streaming_low, R.string.quality_streaming_low_detail),
    Triple(StreamingQuality.NORMAL, R.string.quality_streaming_normal, R.string.quality_streaming_normal_detail),
    Triple(StreamingQuality.HIGH, R.string.quality_streaming_high, R.string.quality_streaming_high_detail),
)

private val DownloadChoices = listOf(
    Triple(DownloadQuality.OFF, R.string.quality_download_off, R.string.quality_download_off_detail),
    Triple(DownloadQuality.SMALL, R.string.quality_download_small, R.string.quality_download_small_detail),
    Triple(DownloadQuality.CD, R.string.quality_download_cd, R.string.quality_download_cd_detail),
    Triple(DownloadQuality.HI_RES, R.string.quality_download_hires, R.string.quality_download_hires_detail),
    Triple(DownloadQuality.ASK, R.string.quality_download_ask, R.string.quality_download_ask_detail),
)

/**
 * Settings of the Immersive look, replacing the Classic `SettingsScreen`: the same preferences
 * and actions (every switch writes through the same stores) as iOS-style inset groups, one page
 * per category and one for each choice (quality, language, look...). A pushed page slides in on a
 * spring and back leaves it; with "reduce motion" they cross-fade.
 */
@Composable
fun ImmersiveSettingsScreen(navController: NavController) {
    // The pages pushed so far, so back pops one before it leaves Settings.
    // Saved by name, so a recreation (a language change, say) keeps the page the user is on.
    val stack = rememberSaveable(
        saver = listSaver(
            save = { list -> list.map { it.name } },
            restore = { names -> names.map { SettingsPage.valueOf(it) }.toMutableStateList() },
        ),
    ) { mutableStateListOf<SettingsPage>() }
    val page = stack.lastOrNull() ?: SettingsPage.Root
    val reduce = rememberReduceMotion()
    var pushing by remember { mutableStateOf(true) }
    val pop: () -> Unit = { pushing = false; stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isNotEmpty(), onBack = pop)
    val open: (SettingsPage) -> Unit = { pushing = true; stack += it }
    val back: () -> Unit = { if (stack.isNotEmpty()) pop() else navController.popBackStack() }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            when {
                reduce -> fadeIn(tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.FADE_MS)) togetherWith fadeOut(tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.FADE_MS))
                !pushing ->
                    (slideInHorizontally(tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.MEDIUM_MS)) { -it / 3 } + fadeIn(tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.MEDIUM_MS))) togetherWith
                        (slideOutHorizontally(tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.MEDIUM_MS)) { it } + fadeOut(tween(160)))
                else ->
                    (slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))) { it } + fadeIn(tween(160))) togetherWith
                        (slideOutHorizontally(tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.MEDIUM_MS)) { -it / 3 } + fadeOut(tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.MEDIUM_MS)))
            }
        },
        label = "settings pages",
    ) { p ->
        when (p) {
            SettingsPage.Root -> SettingsRoot(navController, open, back)
            SettingsPage.Playback -> PlaybackPage(navController, open, back)
            SettingsPage.StreamingWifi -> StreamingPage(cellular = false, back)
            SettingsPage.StreamingCell -> StreamingPage(cellular = true, back)
            SettingsPage.Crossfade -> CrossfadePage(back)
            SettingsPage.Downloads -> DownloadsPage(navController, open, back)
            SettingsPage.DownloadQuality -> DownloadQualityPage(back)
            SettingsPage.Data -> DataPage(back)
            SettingsPage.Display -> DisplayPage(open, back)
            SettingsPage.Look -> LookPage(back)
            SettingsPage.Rotation -> RotationPage(back)
            SettingsPage.Language -> LanguagePage(back)
            SettingsPage.App -> AppPage(navController, back)
        }
    }
}

@Composable
private fun SettingsRoot(navController: NavController, open: (SettingsPage) -> Unit, back: () -> Unit) {
    val registry by com.music.spotui.extensions.Extensions.registry.collectAsStateWithLifecycle()
    val withSettings = registry.extensions.mapNotNull { e ->
        com.music.spotui.extensions.Extensions.manifest(e.id)?.settings?.takeIf { it.isNotEmpty() }?.let { e to it }
    }
    ImmersivePage(title = stringResource(R.string.settings_title), onBack = back) {
        immersiveGroup("root") {
            row { ImmersiveGroupRow(stringResource(R.string.settings_section_playback), icon = Icons.Rounded.PlayArrow, chevron = true, onClick = { open(SettingsPage.Playback) }) }
            row { ImmersiveGroupRow(stringResource(R.string.settings_section_downloads), icon = Icons.Rounded.Download, chevron = true, onClick = { open(SettingsPage.Downloads) }) }
            row { ImmersiveGroupRow(stringResource(R.string.settings_section_data), icon = Icons.Rounded.SignalCellularAlt, chevron = true, onClick = { open(SettingsPage.Data) }) }
            row { ImmersiveGroupRow(stringResource(R.string.settings_section_display), icon = Icons.Rounded.Palette, chevron = true, onClick = { open(SettingsPage.Display) }) }
            row { ImmersiveGroupRow(stringResource(R.string.settings_section_app), icon = Icons.Rounded.Info, chevron = true, onClick = { open(SettingsPage.App) }) }
        }
        immersiveGroup("extensions") {
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.extensions_title), detail = stringResource(R.string.extensions_row_detail),
                    icon = Icons.Rounded.Extension, chevron = true,
                    onClick = { navController.navigate(Routes.Extensions.route) },
                )
            }
        }
        // The installed extensions that declare settings, each opening its page.
        immersiveGroup("extension-settings", headerRes = if (withSettings.isEmpty()) null else R.string.extensions_title) {
            withSettings.forEach { (e, settings) ->
                row {
                    ImmersiveGroupRow(
                        e.name, detail = settings.joinToString { it.label }, chevron = true,
                        onClick = { navController.navigate(installedRoute(e.id)) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Playback

private val ImmersiveSourceLook = com.music.spotui.ui.screens.SourceLook(
    Immersive.Text, Immersive.TextSecondary, Immersive.Accent, Immersive.BodyStrong, Immersive.Caption)

/** The order a source action asks extensions in, as a card ([com.music.spotui.ui.screens.SourceOrderList]); nothing without any such extension. */
private fun androidx.compose.foundation.lazy.LazyListScope.sourcesGroup(action: app.myspoti.extensions.Action, navController: NavController) {
    if (com.music.spotui.extensions.Extensions.sources(action).isEmpty()) return
    val audio = action == app.myspoti.extensions.Action.AUDIO_SOURCE
    immersiveGroup("sources:${action.key}",
        headerRes = if (audio) R.string.sources_audio_title else R.string.sources_download_title,
        footerRes = if (audio) R.string.sources_audio_footer else R.string.sources_download_footer) {
        row {
            com.music.spotui.ui.screens.SourceOrderList(action, spotifyLast = audio, look = ImmersiveSourceLook,
                onOpen = { navController.navigate(com.music.spotui.ui.screens.installedRoute(it)) },
                rowPadding = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun PlaybackPage(navController: NavController, open: (SettingsPage) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val wifi = getStreamingQuality(ctx, false)
    val cell = getStreamingQuality(ctx, true)
    val label = { q: StreamingQuality -> StreamingChoices.first { it.first == q }.second }
    var headsetSkip by remember { mutableStateOf(app.myspoti.host.android.PlaybackService.skipsOnEpisodes(ctx)) }
    var keepPlaying by remember { mutableStateOf(app.myspoti.host.android.PlaybackService.keepsPlayingOnClose(ctx)) }
    var wordSync by remember { mutableStateOf(com.music.spotui.data.preferences.isLyricsWordSync(ctx)) }
    val crossfade = com.music.spotui.data.preferences.CrossfadePref.seconds
    ImmersivePage(title = stringResource(R.string.settings_section_playback), onBack = back) {
        immersiveGroup("quality", footerRes = R.string.quality_streaming_detail) {
            row { ImmersiveGroupRow(stringResource(R.string.quality_streaming_wifi_title), value = stringResource(label(wifi)), chevron = true, onClick = { open(SettingsPage.StreamingWifi) }) }
            row { ImmersiveGroupRow(stringResource(R.string.quality_streaming_cell_title), value = stringResource(label(cell)), chevron = true, onClick = { open(SettingsPage.StreamingCell) }) }
        }
        immersiveGroup("playback") {
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.settings_autoplay_title),
                    checked = com.music.spotui.data.preferences.AutoplayPref.on,
                    onChecked = { com.music.spotui.data.preferences.AutoplayPref.set(ctx, it) },
                    detail = stringResource(R.string.settings_autoplay_detail),
                )
            }
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.settings_crossfade_title), detail = stringResource(R.string.settings_crossfade_detail),
                    value = if (crossfade == 0) stringResource(R.string.settings_crossfade_off) else stringResource(R.string.settings_crossfade_seconds, crossfade),
                    chevron = true, onClick = { open(SettingsPage.Crossfade) },
                )
            }
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.settings_keep_playing_title), checked = keepPlaying,
                    onChecked = { keepPlaying = it; app.myspoti.host.android.PlaybackService.setKeepsPlayingOnClose(ctx, it) },
                    detail = stringResource(R.string.settings_keep_playing_detail),
                )
            }
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.settings_headset_skip_title), checked = headsetSkip,
                    onChecked = { headsetSkip = it; app.myspoti.host.android.PlaybackService.setSkipsOnEpisodes(ctx, it) },
                    detail = stringResource(R.string.settings_headset_skip_detail),
                )
            }
            // Songs an extension streams, played by Spotify too (unheard): counted there, seen by Connect.
            if (com.music.spotui.extensions.Extensions.sources(app.myspoti.extensions.Action.AUDIO_SOURCE).isNotEmpty()) row {
                ImmersiveSwitchRow(
                    stringResource(R.string.settings_connect_mirror_title),
                    checked = com.music.spotui.data.preferences.ConnectMirrorPref.on,
                    onChecked = { com.music.spotui.data.preferences.ConnectMirrorPref.set(ctx, it) },
                    detail = stringResource(R.string.settings_connect_mirror_detail),
                )
            }
            // Live Radio (radio/ module): adds the pinned row under Local Files and the player.
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.live_radio_settings_title),
                    checked = com.music.spotui.radio.RadioFeaturePref.on,
                    onChecked = { com.music.spotui.radio.RadioFeaturePref.set(ctx, it) },
                    detail = stringResource(R.string.live_radio_settings_detail),
                )
            }
        }
        sourcesGroup(app.myspoti.extensions.Action.AUDIO_SOURCE, navController)
        immersiveGroup("visuals") {
            // Lyrics: word by word (karaoke) first, or line by line with Spotify's first.
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.lyrics_word_sync_title), checked = wordSync,
                    onChecked = {
                        wordSync = it
                        com.music.spotui.data.preferences.setLyricsWordSync(ctx, it)
                        com.music.spotui.data.api.LyricsApi.wordSync = it
                        com.music.spotui.data.api.LyricsApi.forgetAll()
                    },
                    detail = stringResource(if (wordSync) R.string.lyrics_word_sync_on else R.string.lyrics_word_sync_off),
                )
            }
        }
    }
}

@Composable
private fun StreamingPage(cellular: Boolean, back: () -> Unit) {
    val ctx = LocalContext.current
    var current by remember(cellular) { mutableStateOf(getStreamingQuality(ctx, cellular)) }
    ImmersivePage(
        title = stringResource(if (cellular) R.string.quality_streaming_cell_title else R.string.quality_streaming_wifi_title),
        onBack = back,
    ) {
        immersiveGroup("choices", footerRes = R.string.quality_streaming_detail) {
            StreamingChoices.forEach { (value, label, detail) ->
                row {
                    ImmersiveChoiceRow(stringResource(label), selected = current == value, detail = stringResource(detail),
                        onClick = { current = value; setStreamingQuality(ctx, value, cellular) })
                }
            }
        }
    }
}

@Composable
private fun CrossfadePage(back: () -> Unit) {
    val ctx = LocalContext.current
    val pref = com.music.spotui.data.preferences.CrossfadePref
    ImmersivePage(title = stringResource(R.string.settings_crossfade_title), onBack = back) {
        immersiveGroup("choices", footerRes = R.string.settings_crossfade_detail) {
            for (v in listOf(0, 3, 6, 9, 12)) {
                row {
                    ImmersiveChoiceRow(
                        if (v == 0) stringResource(R.string.settings_crossfade_off) else stringResource(R.string.settings_crossfade_seconds, v),
                        selected = pref.seconds == v, onClick = { pref.set(ctx, v) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Downloads

@Composable
private fun DownloadsPage(navController: NavController, open: (SettingsPage) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val quality = getDownloadQuality(ctx)
    var ask by remember { mutableStateOf(false) }
    val size = android.text.format.Formatter.formatShortFileSize(ctx, com.music.spotui.data.offline.OfflineStore.totalBytes())
    ImmersivePage(title = stringResource(R.string.settings_section_downloads), onBack = back) {
        immersiveGroup("quality", footerRes = R.string.quality_download_detail) {
            row {
                ImmersiveGroupRow(stringResource(R.string.quality_download_title),
                    value = stringResource(DownloadChoices.first { it.first == quality }.second), chevron = true,
                    onClick = { open(SettingsPage.DownloadQuality) })
            }
        }
        // Where downloads come from: the download-source extensions, in the order they are asked.
        sourcesGroup(app.myspoti.extensions.Action.DOWNLOAD_SOURCE, navController)
        if (com.music.spotui.extensions.Extensions.sources(app.myspoti.extensions.Action.DOWNLOAD_SOURCE).isEmpty()) immersiveGroup("source") {
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.downloads_source_row_title),
                    detail = stringResource(
                        if (com.music.spotui.extensions.Extensions.hasDownloadSource()) R.string.downloads_source_row_on
                        else R.string.downloads_source_row_off,
                    ),
                    chevron = true, onClick = { navController.navigate(Routes.Extensions.route) },
                )
            }
        }
        if (com.music.spotui.data.offline.OfflineStore.tracks.isNotEmpty()) immersiveGroup("remove") {
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.settings_item_remove_downloads_title), detail = stringResource(R.string.downloads_size, size),
                    destructive = true, onClick = { ask = true },
                )
            }
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        containerColor = Immersive.Raised,
        title = { Text(stringResource(R.string.settings_storage_remove_downloads_dialog_title), style = Immersive.Headline) },
        text = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_description), style = Immersive.Subhead) },
        confirmButton = {
            TextButton(onClick = { ask = false; com.music.spotui.data.offline.OfflineDownloader.removeAll() }) {
                Text(stringResource(R.string.playlist_entity_remove_download_dialog_positive_button), color = Immersive.Destructive)
            }
        },
        dismissButton = {
            TextButton(onClick = { ask = false }) { Text(stringResource(R.string.add_to_playlist_button_cancel), color = Immersive.Accent) }
        },
    )
}

@Composable
private fun DownloadQualityPage(back: () -> Unit) {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(getDownloadQuality(ctx)) }
    ImmersivePage(title = stringResource(R.string.quality_download_title), onBack = back) {
        immersiveGroup("choices", footerRes = R.string.quality_download_detail) {
            DownloadChoices.forEach { (value, label, detail) ->
                row {
                    ImmersiveChoiceRow(stringResource(label), selected = current == value, detail = stringResource(detail),
                        onClick = { current = value; setDownloadQuality(ctx, value) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Data and battery

@Composable
private fun DataPage(back: () -> Unit) {
    val ctx = LocalContext.current
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    // Battery optimization off = music and Connect keep going with the screen off.
    var exempt by remember { mutableStateOf(app.myspoti.power.BatteryExemption.isExempt(ctx)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        exempt = app.myspoti.power.BatteryExemption.isExempt(ctx)
    }
    ImmersivePage(title = stringResource(R.string.settings_section_data), onBack = back) {
        immersiveGroup("data") {
            row {
                ImmersiveSwitchRow(stringResource(R.string.energy_saver_title), checked = energy.manualSaver,
                    onChecked = app.myspoti.power.EnergySettings::setSaver, detail = stringResource(R.string.energy_saver_summary))
            }
            row {
                ImmersiveSwitchRow(stringResource(R.string.data_saver_title), checked = com.music.spotui.data.DataSaver.enabled,
                    onChecked = com.music.spotui.data.DataSaver::set, detail = stringResource(R.string.data_saver_summary))
            }
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.background_playback_title), checked = exempt,
                    onChecked = {
                        if (exempt) ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        else app.myspoti.power.BatteryExemption.request(ctx)
                    },
                    detail = stringResource(if (exempt) R.string.background_playback_on else R.string.background_playback_off),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Display

@Composable
private fun DisplayPage(open: (SettingsPage) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    val look = com.music.spotui.data.preferences.LookPref.look
    val rotation = com.music.spotui.data.preferences.OrientationPref.mode
    val language = remember { com.music.spotui.data.AppLanguage.current(ctx) }
    val auto = stringResource(R.string.language_auto)
    ImmersivePage(title = stringResource(R.string.settings_section_display), onBack = back) {
        immersiveGroup("look") {
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.settings_look_title),
                    value = stringResource(if (look == com.music.spotui.data.preferences.Look.CLASSIC) R.string.look_classic else R.string.look_immersive),
                    chevron = true, onClick = { open(SettingsPage.Look) },
                )
            }
            row { AccentRow(com.music.spotui.data.preferences.Look.IMMERSIVE) }
        }
        immersiveGroup("display") {
            if (look == com.music.spotui.data.preferences.Look.IMMERSIVE) row {
                val glass = com.music.spotui.ui.immersive.shell.liquidGlassAvailability()
                val unsupported = glass == com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.UNSUPPORTED
                ImmersiveSwitchRow(
                    stringResource(R.string.settings_liquid_glass_title),
                    checked = com.music.spotui.data.preferences.LiquidGlassPref.on && !unsupported,
                    enabled = !unsupported,
                    onChecked = { com.music.spotui.data.preferences.LiquidGlassPref.set(ctx, it) },
                    detail = stringResource(
                        when (glass) {
                            com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.UNSUPPORTED -> R.string.settings_liquid_glass_unsupported
                            com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.REDUCED -> R.string.settings_liquid_glass_reduced
                            com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.AVAILABLE -> R.string.settings_liquid_glass_detail
                        },
                    ),
                )
            }
            row {
                ImmersiveSwitchRow(stringResource(R.string.settings_amoled_theme), checked = energy.blackPreference,
                    onChecked = app.myspoti.power.EnergySettings::setBlackBackground, detail = stringResource(R.string.settings_amoled_theme_subtitle))
            }
            row {
                ImmersiveSwitchRow(stringResource(R.string.settings_music_video_title), checked = com.music.spotui.data.preferences.MusicVideoPref.on,
                    onChecked = { com.music.spotui.data.preferences.MusicVideoPref.set(ctx, it) }, detail = stringResource(R.string.settings_music_video_detail))
            }
        }
        immersiveGroup("layout") {
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.settings_rotation_title),
                    value = stringResource(rotationLabel(rotation)), chevron = true, onClick = { open(SettingsPage.Rotation) },
                )
            }
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.settings_item_app_language_title),
                    value = if (language == com.music.spotui.data.AppLanguage.SYSTEM) auto else com.music.spotui.data.AppLanguage.nativeName(language),
                    chevron = true, onClick = { open(SettingsPage.Language) },
                )
            }
        }
    }
}

private fun rotationLabel(mode: String): Int = when (mode) {
    com.music.spotui.data.preferences.OrientationPref.AUTO -> R.string.settings_rotation_auto
    com.music.spotui.data.preferences.OrientationPref.LANDSCAPE -> R.string.settings_rotation_landscape
    else -> R.string.settings_rotation_portrait
}

/** The accent colour of one look, for everyone (the same palette the supporter sheet uses). */
@Composable
private fun AccentRow(look: com.music.spotui.data.preferences.Look) {
    val ctx = LocalContext.current
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.supporter_accent), style = Immersive.Body)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 10.dp)) {
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
                    Modifier.size(38.dp)
                        .semantics { contentDescription = name; role = Role.RadioButton }
                        .clip(CircleShape).background(a.color)
                        .then(if (selected) Modifier.border(3.dp, Immersive.Text, CircleShape) else Modifier)
                        .clickable { com.music.spotui.data.api.SupportReminder.setAccent(ctx, look, a) },
                )
            }
        }
    }
}

@Composable
private fun LookPage(back: () -> Unit) {
    val ctx = LocalContext.current
    val current = com.music.spotui.data.preferences.LookPref.look
    ImmersivePage(title = stringResource(R.string.settings_look_title), onBack = back) {
        immersiveGroup("choices") {
            for (l in com.music.spotui.data.preferences.Look.entries) {
                val classic = l == com.music.spotui.data.preferences.Look.CLASSIC
                row {
                    ImmersiveChoiceRow(
                        stringResource(if (classic) R.string.look_classic else R.string.look_immersive),
                        selected = current == l,
                        detail = stringResource(if (classic) R.string.look_classic_detail else R.string.look_immersive_detail),
                        onClick = { com.music.spotui.data.preferences.LookPref.set(ctx, l) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RotationPage(back: () -> Unit) {
    val ctx = LocalContext.current
    val pref = com.music.spotui.data.preferences.OrientationPref
    ImmersivePage(title = stringResource(R.string.settings_rotation_title), onBack = back) {
        immersiveGroup("choices") {
            for ((value, label) in listOf(
                pref.PORTRAIT to R.string.settings_rotation_portrait,
                pref.AUTO to R.string.settings_rotation_auto,
                pref.LANDSCAPE to R.string.settings_rotation_landscape,
            )) {
                row { ImmersiveChoiceRow(stringResource(label), selected = pref.mode == value, onClick = { pref.set(ctx, value) }) }
            }
        }
    }
}

/** "App language": Automatic (French or English) or any language with Spotify's labels. */
@Composable
private fun LanguagePage(back: () -> Unit) {
    val ctx = LocalContext.current
    val current = remember { com.music.spotui.data.AppLanguage.current(ctx) }
    val auto = stringResource(R.string.language_auto)
    fun label(tag: String) = if (tag == com.music.spotui.data.AppLanguage.SYSTEM) auto else com.music.spotui.data.AppLanguage.nativeName(tag)
    ImmersivePage(title = stringResource(R.string.settings_item_app_language_title), onBack = back) {
        immersiveGroup("choices", footerRes = R.string.language_partial_note) {
            com.music.spotui.data.AppLanguage.AVAILABLE.forEach { tag ->
                row {
                    ImmersiveChoiceRow(
                        label(tag), selected = tag == current,
                        detail = if (tag == com.music.spotui.data.AppLanguage.SYSTEM) stringResource(R.string.language_auto_detail) else null,
                        onClick = {
                            if (tag != current) {
                                com.music.spotui.data.AppLanguage.apply(ctx, tag)
                                // Screens take the new language when the activity is rebuilt.
                                generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
                                    .filterIsInstance<android.app.Activity>().firstOrNull()?.recreate()
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- App

@Composable
private fun AppPage(navController: NavController, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val check = com.music.spotui.data.api.rememberUpdateCheckState(ctx)
    ImmersivePage(title = stringResource(R.string.settings_section_app), onBack = back) {
        immersiveGroup("updates") {
            row {
                ImmersiveSwitchRow(stringResource(R.string.settings_auto_update_title), checked = com.music.spotui.data.preferences.AutoUpdateCheckPref.on,
                    onChecked = { com.music.spotui.data.preferences.AutoUpdateCheckPref.set(ctx, it) }, detail = stringResource(R.string.settings_auto_update_detail))
            }
            row {
                val known = com.music.spotui.updateRelease.value ?: remember { com.music.spotui.data.api.UpdateChecker.known(ctx) }
                val detail = buildString {
                    if (known != null) appendLine(ctx.getString(R.string.settings_update_available, known.version))
                    check.message?.takeIf { known == null || check.isError }?.let { appendLine(it) }
                    append(check.lastCheckedText)
                }
                ImmersiveGroupRow(
                    stringResource(R.string.settings_about_version, app.myspoti.engine.BuildConfig.VERSION_NAME),
                    detail = detail,
                    detailColor = if (check.isError) Immersive.Destructive else Immersive.TextSecondary,
                    onClick = if (known != null) ({ com.music.spotui.updateRelease.value = known }) else null,
                    trailing = {
                        if (check.checking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Immersive.Accent)
                        else androidx.compose.material3.IconButton(onClick = { scope.launch { check.run() } }) {
                            androidx.compose.material3.Icon(Icons.Rounded.Refresh, stringResource(R.string.settings_update_refresh), tint = Immersive.Accent)
                        }
                    },
                )
            }
        }
        immersiveGroup("repair") {
            // The hidden Web Player stuck or crashed: rebuild it without leaving the app.
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.restart_player_title), detail = stringResource(R.string.restart_player_detail),
                    onClick = {
                        app.myspoti.host.android.MyspotiRuntime.host.reload()
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.restart_player_done), android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.diagnostic_export_title), detail = stringResource(R.string.diagnostic_export_detail),
                    onClick = {
                        val file = runCatching { app.myspoti.host.android.Logbook.export(ctx) }.getOrNull()
                        if (file != null) shareDiagnosticReport(ctx, file)
                        else android.widget.Toast.makeText(ctx, ctx.getString(R.string.diagnostic_export_failed), android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}
