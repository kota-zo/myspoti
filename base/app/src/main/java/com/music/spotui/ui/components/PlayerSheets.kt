package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import androidx.compose.ui.res.stringResource
import app.myspoti.engine.R

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Delete
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.screens.ExplicitBadge

private val Green: Color get() = com.music.spotui.ui.theme.AppAccent
private val SheetColor: androidx.compose.ui.graphics.Color get() = tone(0xFF282828)

/**
 * Name of the output playing now: the Connect device, else the headphones this phone
 * plays into (their Bluetooth name), else "This phone".
 */
@Composable
fun activeDeviceLabel(): String {
    SongPlayer.activeDevice.value?.let { return it.name }
    val route = rememberAudioRoute()
    return when (route.kind) {
        // "This phone", in the player as in the official app. Spotify Connect still lists it under
        // the device's own name (the engine registers that name); only this label says "this phone".
        AudioRoute.Kind.SPEAKER -> stringResource(R.string.connect_phone_is_self)
        AudioRoute.Kind.BLUETOOTH -> route.name.ifBlank { stringResource(R.string.label_bluetooth) }
        AudioRoute.Kind.WIRED -> stringResource(R.string.headphones)
    }
}

/** The icon of the output playing now, matching [activeDeviceLabel]: a phone when this phone plays. */
@Composable
fun activeDeviceIcon(): ImageVector {
    SongPlayer.activeDevice.value?.let { return deviceIcon(it.type) }
    return when (rememberAudioRoute().kind) {
        AudioRoute.Kind.SPEAKER -> Icons.Default.Smartphone
        AudioRoute.Kind.BLUETOOTH -> Icons.Default.Bluetooth
        AudioRoute.Kind.WIRED -> Icons.Default.Headphones
    }
}

/**
 * Where the sound comes from while Myspoti's own player plays a song (an extension's stream: its
 * name and the quality it announced; a download), shown under the output device; "" when Spotify
 * plays (a song it plays for want of the extensions included) or while a song is being asked for.
 */
@Composable
fun playingSourceLabel(): String {
    val uri = SongPlayer.nowPlaying.value.uri
    val answers = com.music.spotui.extensions.ExtensionAudio.answers.intValue
    val downloaded = stringResource(R.string.player_source_downloaded)
    if (!com.music.spotui.di.LocalPlayer.active.value || SongPlayer.routing) return ""
    return remember(uri, answers, downloaded) {
        com.music.spotui.di.LocalPlayer.current()?.let { com.music.spotui.extensions.ExtensionAudio.sourceLabel(it, downloaded) }.orEmpty()
    }
}

/** The name of the extension streaming the song playing, for the mini player; null otherwise (see [playingSourceLabel]). */
@Composable
fun playingExtensionName(): String? {
    val uri = SongPlayer.nowPlaying.value.uri
    val answers = com.music.spotui.extensions.ExtensionAudio.answers.intValue
    if (!com.music.spotui.di.LocalPlayer.active.value || SongPlayer.routing) return null
    return remember(uri, answers) { com.music.spotui.di.LocalPlayer.current()?.let(com.music.spotui.extensions.ExtensionAudio::streamedBy) }
}

private fun deviceIcon(type: String): ImageVector = when (type.uppercase()) {
    "SMARTPHONE", "TABLET" -> Icons.Default.Smartphone
    "SPEAKER", "AVR", "STB", "AUDIO_DONGLE", "CAST_AUDIO" -> Icons.Default.Speaker
    "TV", "CAST_VIDEO", "GAME_CONSOLE" -> Icons.Default.Tv
    else -> Icons.Default.Computer
}

// ── Connect: where the music plays ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var devices by remember { mutableStateOf<List<SongPlayer.ConnectDevice>?>(null) }
    var quality by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { devices = SongPlayer.devices() }
    val remote = SongPlayer.activeDevice.value
    val route = rememberAudioRoute()
    val headphones = remote == null && route.kind != AudioRoute.Kind.SPEAKER
    if (quality) QualitySheet(onDismiss = { quality = false })
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.listen_on),
                color = AppText, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            // The output playing now, the song, and its quality (tap: what plays, and change it).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp)).background(tone(0xFF333333)).padding(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(activeDeviceLabel(), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (remote == null) Text(
                            stringResource(streamingLabel(com.music.spotui.data.preferences.getStreamingQuality(context, com.music.spotui.data.DataSaver.metered))),
                            color = Color(0xFFDDDDDD), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF4A4A4A))
                                .clickable { quality = true }.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    val title = Graph.currentSongState.title.value
                    if (title.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Default.Equalizer, null, tint = Green, modifier = Modifier.size(16.dp))
                        Text("$title — ${Graph.currentSongState.singer.value}", color = Green, fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Icon(
                    when {
                        remote != null -> deviceIcon(remote.type)
                        headphones -> Icons.Default.Headphones
                        else -> Icons.Default.Smartphone
                    },
                    contentDescription = null, tint = Green, modifier = Modifier.padding(start = 12.dp).size(40.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            val list = devices
            val self = list?.firstOrNull { it.isSelf }
            // This phone's speaker: from headphones (the system's output picker), or back from a Connect device.
            if (remote != null || headphones) DeviceRow(Icons.Default.Smartphone, stringResource(R.string.connect_phone_is_self), null) {
                if (remote != null) self?.let { SongPlayer.transferTo(it) } else AudioRoute.pickOutput(context)
                onDismiss()
            }
            // The headphones this phone plays into, while a Connect device has the music.
            if (remote != null && route.kind != AudioRoute.Kind.SPEAKER || headphones) DeviceRow(
                Icons.Default.Headphones,
                if (route.kind == AudioRoute.Kind.BLUETOOTH) route.name.ifBlank { stringResource(R.string.label_bluetooth) } else stringResource(R.string.headphones),
                if (route.kind == AudioRoute.Kind.BLUETOOTH) stringResource(R.string.label_bluetooth) else null,
                current = headphones,
            ) {
                if (remote != null) self?.let { SongPlayer.transferTo(it) } else AudioRoute.pickOutput(context)
                onDismiss()
            }
            if (list == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Green, modifier = Modifier.size(28.dp))
                }
            } else {
                list.filter { !it.isSelf && !it.isActive }.forEach { d ->
                    DeviceRow(deviceIcon(d.type), d.name, null) { SongPlayer.transferTo(d); onDismiss() }
                }
                if (list.none { !it.isSelf } && !headphones && remote == null)
                    Text(stringResource(R.string.no_devices), color = AppText.copy(alpha = 0.7f), modifier = Modifier.padding(20.dp))
            }
            JamSection(onDismiss)
            // Bluetooth: pair or pick headphones and speakers in the system settings.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp)).background(tone(0xFF333333))
                    .clickable { AudioRoute.openBluetooth(context) }.padding(14.dp),
            ) {
                Icon(Icons.Default.Bluetooth, null, tint = Green, modifier = Modifier.size(26.dp))
                Text(stringResource(R.string.label_bluetooth), color = Green, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, detail: String?, current: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (current) Green else AppText, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(name, color = if (current) Green else AppText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, color = AppMuted, fontSize = 13.sp)
        }
    }
}

private fun streamingLabel(q: com.music.spotui.data.preferences.StreamingQuality): Int = when (q) {
    com.music.spotui.data.preferences.StreamingQuality.AUTO -> R.string.quality_streaming_auto
    com.music.spotui.data.preferences.StreamingQuality.LOW -> R.string.quality_streaming_low
    com.music.spotui.data.preferences.StreamingQuality.NORMAL -> R.string.quality_streaming_normal
    com.music.spotui.data.preferences.StreamingQuality.HIGH -> R.string.quality_streaming_high
}

/** The sound quality page: what plays now (codec and bitrate, the downloaded file, or what an extension announced), and the setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(onDismiss: () -> Unit) {
    val local = com.music.spotui.di.LocalPlayer.active.value
    val now by androidx.compose.runtime.produceState<String?>(null) {
        value = if (local) {
            val t = com.music.spotui.di.LocalPlayer.current()
            val file = t?.let { com.music.spotui.data.offline.OfflineStore.byLocalId(it.id) }
            file?.let { "${it.file.substringAfterLast('.').uppercase()} · ${it.source}" }
                // A stream an extension gives: the quality it announced, as it said it.
                ?: t?.uri?.takeIf { it.scheme == com.music.spotui.extensions.ExtensionAudio.SCHEME }
                    ?.let { com.music.spotui.extensions.ExtensionAudio.qualityOf(it.lastPathSegment.orEmpty()) }
                ?: ""
        } else {
            val (bitrate, mime) = app.myspoti.host.android.MyspotiRuntime.spotify.player.audioInfo()
            if (bitrate <= 0) "" else "${codecName(mime)} · ${bitrate / 1000} kbit/s"
        }
    }
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.quality_sheet_title), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val text = now
            Text(
                when {
                    text == null -> "…"
                    text.isBlank() -> stringResource(R.string.quality_now_unknown)
                    else -> stringResource(R.string.quality_now, text)
                },
                color = Green, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            // The level of the network in use now (Wi-Fi and mobile data each keep their own).
            if (!local) StreamingQualityPicker(Modifier.padding(top = 12.dp), cellular = com.music.spotui.data.DataSaver.metered,
                title = if (com.music.spotui.data.DataSaver.metered) R.string.quality_streaming_cell_title else R.string.quality_streaming_wifi_title)
        }
    }
}

private fun codecName(mime: String): String = when {
    "mp4a" in mime -> "AAC"
    "flac" in mime -> "FLAC"
    "opus" in mime -> "Opus"
    "vorbis" in mime -> "Ogg Vorbis"
    "mpeg" in mime -> "MP3"
    else -> mime.substringBefore(';').substringAfter('/').uppercase()
}

/** Spotify Jam: listen together. Free accounts can join (Spotify keeps starting one to Premium). */
@Composable
private fun JamSection(onDismiss: () -> Unit) {
    val jam = app.myspoti.host.android.MyspotiRuntime.api.jam
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val session by jam.session.collectAsState()
    val availability by androidx.compose.runtime.produceState<app.myspoti.api.JamAvailability?>(null) {
        value = runCatching { jam.refresh(); jam.availability() }.getOrDefault(app.myspoti.api.JamAvailability.UNKNOWN)
    }
    fun failed() = android.widget.Toast.makeText(context, context.getString(R.string.jam_failed), android.widget.Toast.LENGTH_SHORT).show()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.jam_title), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val s = session
        if (s != null) {
            Text(stringResource(R.string.jam_members, s.members.size), color = Green, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Text(stringResource(R.string.jam_invite), color = AppText, fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        val url = runCatching { jam.inviteLink() }.getOrNull() ?: return@launch failed()
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url)
                        context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.padding(vertical = 10.dp))
            Text(stringResource(R.string.jam_leave), color = AppText, fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().clickable {
                    scope.launch { runCatching { if (s.isHost) jam.end() else jam.leave() }; onDismiss() }
                }.padding(vertical = 10.dp))
            return@Column
        }
        if (availability == app.myspoti.api.JamAvailability.AVAILABLE) Text(stringResource(R.string.jam_start), color = Green, fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().clickable {
                scope.launch { if (runCatching { jam.start() }.isFailure) failed() }
            }.padding(vertical = 10.dp))
        // Join: scan the QR code another phone shows in its Jam.
        Text(stringResource(R.string.jam_scan), color = Green, fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().clickable {
                com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context).startScan()
                    .addOnSuccessListener { code ->
                        val raw = code.rawValue.orEmpty()
                        scope.launch {
                            val token = JamLinks.token(raw)
                            if (token == null || runCatching { jam.join(token) }.isFailure) failed() else onDismiss()
                        }
                    }
                    // No scanner on this phone (Play services missing or too old): say so.
                    .addOnFailureListener { failed() }
            }.padding(vertical = 10.dp))
        // Join: an invite link copied from a message.
        Text(stringResource(R.string.jam_paste_link), color = Green, fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().clickable {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                val raw = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                scope.launch {
                    val token = JamLinks.token(raw)
                    if (token == null || runCatching { jam.join(token) }.isFailure) failed() else onDismiss()
                }
            }.padding(vertical = 10.dp))
    }
}

/** Jam invite codes: `open.spotify.com/socialsession/<token>`, or a spotify.link short link to it. */
private object JamLinks {
    private val http = okhttp3.OkHttpClient.Builder().followRedirects(true).build()

    suspend fun token(raw: String): String? {
        val url = if (raw.contains("spotify.link") || raw.contains("spoti.fi")) resolve(raw) ?: raw else raw
        Regex("socialsession/([A-Za-z0-9_-]+)").find(url)?.let { return it.groupValues[1] }
        Regex("spotify:socialsession:([A-Za-z0-9_-]+)").find(url)?.let { return it.groupValues[1] }
        return null
    }

    /** Where a short link leads (its final URL after redirects). */
    private suspend fun resolve(link: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            http.newCall(okhttp3.Request.Builder().url(link).header("user-agent", "Mozilla/5.0").build()).execute().use { r ->
                val final = r.request.url.toString()
                // Some short links answer with a page that holds the target in a meta refresh / link.
                if (final.contains("socialsession")) final
                else Regex("https://open\\.spotify\\.com/socialsession/[A-Za-z0-9_-]+").find(r.body.string())?.value ?: final
            }
        }.getOrNull()
    }
}

// ── Sleep timer ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val active = SongPlayer.sleepEndsAt.value != null
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.context_menu_sleep_timer),
                color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            val endOfTrack = stringResource(R.string.context_menu_sleep_timer_end_of_track)
            val options = buildList {
                for (m in listOf(5, 10, 15, 30, 45)) add(stringResource(R.string.sleep_minutes, m) to m)
                add(stringResource(R.string.mute_chat_duration_one_hour) to 60)
                add(endOfTrack to 0)
            }
            options.forEach { (label, minutes) ->
                Text(
                    label,
                    color = AppText, fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { SongPlayer.setSleepTimer(minutes); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            if (active) {
                Text(
                    stringResource(R.string.context_menu_sleep_timer_turn_off),
                    color = Green, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { SongPlayer.setSleepTimer(null); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

// ── Queue (official layout: sheet over the now-playing screen) ──

private val QueueRowHeight = 64.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun QueueSheet(contextName: String, onDismiss: () -> Unit) {
    DisposableEffect(Unit) {
        SongPlayer.watchQueue(true)
        onDispose { SongPlayer.watchQueue(false) }
    }
    val q by SongPlayer.engineQueue
    val state = Graph.currentSongState
    val playing = state.playingState.value
    val shuffle = state.shuffle.value
    var showTimer by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    if (showTimer) SleepTimerSheet { showTimer = false }

    // The queue rows are all ONE LazyColumn item, so a drag can only scroll the sheet
    // through this state.
    val listState = rememberLazyListState()

    com.music.spotui.ui.components.LookSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_queue_label), color = AppText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (contextName.isNotBlank()) Row(modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.playing_on), color = AppText.copy(alpha = 0.7f), fontSize = 14.sp)
                        Text(contextName, color = AppText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                        Icon(activeDeviceIcon(), contentDescription = null, tint = Green, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(activeDeviceLabel(), color = Green, fontSize = 13.sp)
                    }
                }
                PillButton(if (editing) "OK" else stringResource(R.string.curation_actions_edit_button_title)) { editing = !editing }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                q.current?.let { cur ->
                    item(key = "current") {
                        QueueSheetRow(cur, current = true, trailing = {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AppText)
                                    .clickable { if (playing) SongPlayer.pause() else SongPlayer.play() },
                            ) {
                                Icon(
                                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playing) stringResource(R.string.content_description_pause_button) else stringResource(R.string.content_description_play_button),
                                    tint = com.music.spotui.ui.theme.AppBackground, modifier = Modifier.size(22.dp),
                                )
                            }
                        }, onClick = {})
                    }
                }
                item(key = "queue") { QueueList(q, contextName, editing, listState) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                QueueAction(Icons.Default.Shuffle, stringResource(R.string.play_mode_item_shuffle_title), shuffle, Modifier.weight(1f)) {
                    SongPlayer.setShuffle(!shuffle); state.shuffle.value = !shuffle
                }
                val mode = SongPlayer.repeatMode.value
                QueueAction(if (mode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat, stringResource(R.string.np_content_desc_repeat), mode != 0, Modifier.weight(1f)) {
                    SongPlayer.cycleRepeat()
                }
                QueueAction(Icons.Outlined.Timer, stringResource(R.string.np_content_desc_sleep_timer), SongPlayer.sleepEndsAt.value != null, Modifier.weight(1f)) {
                    showTimer = true
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tone(0xFF3E3E3E))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) { Text(label, color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

private val QueueHeaderHeight = 48.dp

/** Strip at each end of the list where a drag starts scrolling it. */
private val QueueEdgeZone = 72.dp

/** Scroll distance per frame at the very edge; it fades to 0 at the zone's inner border. */
private val QueueEdgeMaxStep = 14.dp

/**
 * Pixels to scroll this frame for a drag whose row centre is at [pointerY] (viewport
 * space): negative near the top edge, positive near the bottom, 0 in the middle. The
 * speed ramps up as the row gets closer to the edge, so a song crosses a long queue fast.
 */
internal fun queueEdgeScrollStep(
    pointerY: Float,
    viewportTop: Float,
    viewportBottom: Float,
    edgePx: Float,
    maxStepPx: Float,
): Float {
    if (viewportBottom <= viewportTop || edgePx <= 0f) return 0f
    val up = ((viewportTop + edgePx) - pointerY) / edgePx
    if (up > 0f) return -maxStepPx * up.coerceIn(0f, 1f)
    val down = (pointerY - (viewportBottom - edgePx)) / edgePx
    return if (down > 0f) maxStepPx * down.coerceIn(0f, 1f) else 0f
}

/**
 * Top of the queue block in viewport pixels (negative once scrolled past the top).
 * The sheet's list holds the current track (optional) and the queue block, so
 * [queueContentTop] is that first item's height; [firstVisibleIndex] / [firstVisibleScrollOffset]
 * are [LazyListState]'s own numbers, which keeps this in step with the scroll.
 */
internal fun queueTopInViewport(
    totalItems: Int,
    firstVisibleIndex: Int,
    firstVisibleScrollOffset: Int,
    queueContentTop: Float,
): Float {
    if (totalItems == 0) return 0f
    val firstItemTop = if (firstVisibleIndex > 0 && totalItems > 1) queueContentTop else 0f
    return queueContentTop - (firstItemTop + firstVisibleScrollOffset)
}

/** The y (in the queue block) of the gap [item] would drop into from [centerY]; mirrors the drop's own placement. */
private fun dropLineY(
    entries: List<QEntry>, item: SongPlayer.QueueItem, fromQueued: Boolean, centerY: Float,
    headerPx: Float, rowPx: Float, topOf: (Int) -> Float,
): Float? {
    val rowsOf = { queuedSection: Boolean -> entries.filterIsInstance<QEntry.Row>().filter { it.queued == queuedSection }.map { it.item } }
    fun rowTop(target: SongPlayer.QueueItem) = topOf(entries.indexOfFirst { it is QEntry.Row && it.item == target })
    val nextHeaderIndex = entries.indexOfFirst { it is QEntry.Header && !it.queuedSection }
    val nextHeaderMid = if (nextHeaderIndex >= 0) topOf(nextHeaderIndex) + headerPx / 2 else Float.MAX_VALUE
    val toQueue = centerY < nextHeaderMid
    val others = rowsOf(toQueue) - item
    if (others.isEmpty()) return if (toQueue && !fromQueued) 0f else null
    val slot = if (toQueue) ((centerY - headerPx) / rowPx).toInt().coerceIn(0, others.size)
        else ((centerY - (topOf(nextHeaderIndex) + headerPx)) / rowPx).toInt().coerceIn(0, others.size)
    return if (slot < others.size) rowTop(others[slot]) else rowTop(others.last()) + rowPx
}

private sealed class QEntry {
    data class Header(val queuedSection: Boolean) : QEntry()
    data class Row(val item: SongPlayer.QueueItem, val queued: Boolean) : QEntry()
}

/**
 * The upcoming tracks as ONE draggable list: "Prochains titres dans la file d'attente"
 * then "Suivants de : …". Dragging a track above the "Suivants" header moves it into
 * the queue (official behaviour). Drag handle on every row; swipe right removes.
 */
@Composable
private fun QueueList(
    q: SongPlayer.EngineQueue,
    contextName: String,
    editing: Boolean,
    listState: LazyListState,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = rememberCoroutineScope()
    val rowPx = with(density) { QueueRowHeight.toPx() }
    val headerPx = with(density) { QueueHeaderHeight.toPx() }
    val edgePx = with(density) { QueueEdgeZone.toPx() }
    val maxStepPx = with(density) { QueueEdgeMaxStep.toPx() }

    var queued by remember(q.queued) { mutableStateOf(q.queued) }
    var nextUp by remember(q.nextUp) { mutableStateOf(q.nextUp) }
    var dragging by remember { mutableStateOf<SongPlayer.QueueItem?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var lastSlot by remember { mutableStateOf(-1) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }

    val entries = buildList {
        if (queued.isNotEmpty()) add(QEntry.Header(true))
        queued.forEach { add(QEntry.Row(it, true)) }
        if (nextUp.isNotEmpty()) add(QEntry.Header(false))
        nextUp.forEach { add(QEntry.Row(it, false)) }
    }
    fun height(e: QEntry) = if (e is QEntry.Header) headerPx else rowPx
    fun topOf(index: Int) = entries.take(index).sumOf { height(it).toDouble() }.toFloat()

    /** Entry under a y position (list coordinates). */
    fun entryAt(y: Float): Int {
        var acc = 0f
        entries.forEachIndexed { i, e -> acc += height(e); if (y < acc) return i }
        return entries.lastIndex
    }

    /**
     * Top of the queue block in viewport coordinates (negative once scrolled past the
     * top). Computing it from the scroll state instead of measuring the composable
     * guarantees it matches the scroll the drag itself is doing.
     */
    fun queueBlockTop(): Float {
        val total = listState.layoutInfo.totalItemsCount
        return queueTopInViewport(
            totalItems = total,
            firstVisibleIndex = listState.firstVisibleItemIndex,
            firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset,
            queueContentTop = if (total > 1) rowPx else 0f,
        )
    }

    /** Pixels to scroll this frame: negative near the top edge, positive near the bottom. */
    fun edgeScrollStep(pointerY: Float): Float {
        val info = listState.layoutInfo
        return queueEdgeScrollStep(
            pointerY,
            info.viewportStartOffset.toFloat(),
            info.viewportEndOffset.toFloat(),
            edgePx,
            maxStepPx,
        )
    }

    /** Recomputes the hovered entry after the list moved; ticks only when it changes. */
    fun syncHoveredSlot(itemIndex: Int) {
        val slot = entryAt(topOf(itemIndex) + dragOffset + rowPx / 2)
        if (slot != lastSlot) {
            lastSlot = slot
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
        }
    }

    /** Scrolls the list once per frame while the dragged row stays in an edge zone. */
    fun startAutoScroll(itemIndex: Int) {
        autoScrollJob?.cancel()
        autoScrollJob = scope.launch {
            while (isActive) {
                val top = queueBlockTop()
                val pointerY = top + topOf(itemIndex) + dragOffset + rowPx / 2
                val step = edgeScrollStep(pointerY)
                if (step != 0f) {
                    val moved = listState.scrollBy(step)
                    if (moved != 0f) {
                        // The rows live in list CONTENT space: the content slid under a
                        // still finger, so the drag has to slide by the same amount or
                        // the row escapes the finger and the hovered slot stops matching.
                        dragOffset += moved
                        syncHoveredSlot(itemIndex)
                    }
                }
                withFrameNanos { }
            }
        }
    }

    fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    fun drop(item: SongPlayer.QueueItem, fromQueued: Boolean, centerY: Float) {
        val nextHeaderIndex = entries.indexOfFirst { it is QEntry.Header && !it.queuedSection }
        val nextHeaderMid = if (nextHeaderIndex >= 0) topOf(nextHeaderIndex) + headerPx / 2 else Float.MAX_VALUE
        // Either way across the line: a song moved up into the queue can be taken back down.
        val toQueue = centerY < nextHeaderMid
        if (toQueue) {
            val others = queued - item
            if (others.isEmpty()) {
                if (!fromQueued) {
                    // Empty queue: becomes the first queued track.
                    nextUp = nextUp - item; queued = listOf(item)
                    SongPlayer.moveToQueueFront(item)
                }
                return
            }
            // Position among the queued rows (rows start after the queue header).
            val slot = ((centerY - headerPx) / rowPx).toInt().coerceIn(0, others.size)
            val newQueued = others.toMutableList().apply { add(slot, item) }
            if (newQueued == queued) return
            if (!fromQueued) nextUp = nextUp - item
            queued = newQueued
            if (slot < others.size) SongPlayer.reorder(item, others[slot], after = false)
            else SongPlayer.reorder(item, others[slot - 1], after = true)
        } else {
            val others = nextUp - item
            if (others.isEmpty()) return
            val startY = topOf(nextHeaderIndex) + headerPx
            val slot = ((centerY - startY) / rowPx).toInt().coerceIn(0, others.size)
            val newNext = others.toMutableList().apply { add(slot, item) }
            if (newNext == nextUp) return
            if (fromQueued) queued = queued - item
            nextUp = newNext
            if (slot < others.size) SongPlayer.reorder(item, others[slot], after = false)
            else SongPlayer.reorder(item, others[slot - 1], after = true)
        }
    }

    // Where the dragged song would land if let go: a thin accent bar between two rows, worked
    // out exactly as [drop] places it.
    val accent = com.music.spotui.ui.theme.AppAccent
    val dropBar = Modifier.drawWithContent {
        drawContent()
        val item = dragging ?: return@drawWithContent
        val from = entries.indexOfFirst { it is QEntry.Row && it.item == item }
        if (from < 0) return@drawWithContent
        val y = dropLineY(entries, item, (entries[from] as QEntry.Row).queued, topOf(from) + dragOffset + rowPx / 2, headerPx, rowPx, ::topOf)
            ?: return@drawWithContent
        val inset = 20.dp.toPx()
        val h = 1.5.dp.toPx()
        drawRoundRect(androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.35f).copy(alpha = 0.8f), topLeft = androidx.compose.ui.geometry.Offset(inset, y - h / 2),
            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
    }
    Column(dropBar) {
        entries.forEachIndexed { index, e ->
            when (e) {
                is QEntry.Header -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(QueueHeaderHeight),
                ) {
                    Box(Modifier.weight(1f)) {
                        SheetHeader(
                            if (e.queuedSection) stringResource(R.string.next_in_queue)
                            else if (contextName.isNotBlank()) stringResource(R.string.next_from, contextName) else stringResource(R.string.next_up)
                        )
                    }
                    if (e.queuedSection) Text(
                        stringResource(R.string.clear_queue),
                        color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(end = 20.dp)
                            .clickable {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                                queued = emptyList(); SongPlayer.clearQueue()
                            },
                    )
                }
                is QEntry.Row -> {
                    val item = e.item
                    val isDragged = dragging == item
                    Box(
                        modifier = Modifier
                            .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                            .zIndex(if (isDragged) 1f else 0f)
                            .background(if (isDragged) tone(0xFF3A3A3A) else SheetColor)
                    ) {
                        SwipeLeftToRemove(enabled = !editing && dragging == null, onRemove = {
                            if (e.queued) queued = queued - item else nextUp = nextUp - item
                            SongPlayer.removeFromQueue(item)
                        }) {
                            QueueSheetRow(item.song, current = false, leading = {
                                if (editing) Icon(
                                    Icons.Default.RemoveCircleOutline,
                                    contentDescription = stringResource(R.string.aligned_curation_first_save_default_row_remove_action_description),
                                    tint = AppText.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .size(24.dp)
                                        .clickable {
                                            if (e.queued) queued = queued - item else nextUp = nextUp - item
                                            SongPlayer.removeFromQueue(item)
                                        },
                                )
                            }, trailing = {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = stringResource(R.string.edit_playlist_items_move_chip),
                                    tint = AppText.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .size(28.dp)
                                        .pointerInput(item.uid + item.uri) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    dragging = item; dragOffset = 0f; lastSlot = index
                                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    startAutoScroll(index)
                                                },
                                                onDragEnd = {
                                                    stopAutoScroll()
                                                    val center = topOf(entries.indexOf(e)) + dragOffset + rowPx / 2
                                                    drop(item, e.queued, center)
                                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
                                                    dragging = null; dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    stopAutoScroll()
                                                    dragging = null; dragOffset = 0f
                                                },
                                            ) { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                syncHoveredSlot(index)
                                            }
                                        },
                                )
                            }, onClick = { if (!editing) SongPlayer.skipTo(item) })
                        }
                    }
                }
            }
        }
    }
}

/** Swipe a queue row from right to left to remove it (red strip, haptic tick at the threshold). */
@Composable
private fun SwipeLeftToRemove(enabled: Boolean, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        if (offset.value < 0f) Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.matchParentSize().background(if (armed) Color(0xFFE22134) else Color(0xFF7A1F1F)),
        ) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.aligned_curation_first_save_default_row_remove_action_description), tint = AppText, modifier = Modifier.padding(end = 24.dp).size(24.dp))
        }
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offset.value }
                .background(SheetColor)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (armed) {
                                scope.launch { offset.animateTo(-size.width.toFloat()) ; onRemove(); offset.snapTo(0f) }
                            } else scope.launch { offset.animateTo(0f) }
                            armed = false
                        },
                        onDragCancel = { armed = false; scope.launch { offset.animateTo(0f) } },
                    ) { change, dx ->
                        change.consume()
                        val next = (offset.value + dx).coerceIn(-size.width.toFloat(), 0f)
                        scope.launch { offset.snapTo(next) }
                        val nowArmed = -next >= threshold
                        if (nowArmed && !armed) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                        armed = nowArmed
                    }
                },
        ) { content() }
    }
}

@Composable
private fun SheetHeader(text: String) {
    Text(
        text, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(item: SongPlayer.QueueItem) {
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v != SwipeToDismissBoxValue.Settled) { SongPlayer.removeFromQueue(item); true } else false
    })
    SwipeToDismissBox(
        state = dismiss,
        backgroundContent = { Box(Modifier.fillMaxWidth().fillMaxHeight().background(Color(0xFF7A1F1F))) },
    ) {
        Box(Modifier.background(SheetColor)) {
            QueueSheetRow(item.song, current = false, trailing = {}, onClick = { SongPlayer.skipTo(item) })
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun QueueSheetRow(
    song: SongsModel,
    current: Boolean,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rec = com.music.spotui.ui.screens.SmartShuffle.isRecommendation(song.spotifyTrackId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        leading()
        GlideImage(
            transition = CrossFade,
            model = song.coverUri, contentDescription = "", contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current) {
                    Icon(Icons.Default.Equalizer, contentDescription = null, tint = Green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    song.title, color = if (current) Green else AppText, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                // Mixed in by smart shuffle: marked, as in the official app, not taken for one of the playlist's songs.
                if (rec) { Icon(Icons.Outlined.AutoAwesome, contentDescription = stringResource(R.string.smart_shuffle_recommended), tint = Green, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                if (song.explicit) { ExplicitBadge(); Spacer(Modifier.width(6.dp)) }
                Text(song.singer, color = AppText.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (rec) Icon(
            Icons.Outlined.AddCircleOutline,
            contentDescription = stringResource(R.string.smart_shuffle_add),
            tint = AppText,
            modifier = Modifier.padding(end = 12.dp).size(26.dp).clip(CircleShape).clickable { com.music.spotui.ui.screens.SmartShuffle.keep(context, song) },
        )
        trailing()
    }
}

@Composable
private fun QueueAction(icon: ImageVector, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tone(0xFF3E3E3E))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Green else AppText, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (active) Green else AppText, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
