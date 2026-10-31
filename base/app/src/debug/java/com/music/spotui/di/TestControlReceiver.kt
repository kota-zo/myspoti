package com.music.spotui.di

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject

/**
 * Debug builds only: the playback test (tools/playback_test.py) sends the app's own
 * commands, as a tap would, with
 * `adb shell am broadcast -a app.myspoti.TEST -p app.myspoti.dev --es cmd <cmd> ...`.
 * cmd: play (--es track <uri> --es context <uri>), resume, pause, next, previous, seek (--el ms <n>).
 * cmd: download (--es id <spotify id> --es title T --es artist A --ei ms <duration>), playdownloaded (--es id).
 * cmd: screenon (--ez on false to release) holds the screen on for a long run: One UI does not
 * honour the huge `screen_off_timeout` set over adb, the window flag does.
 * cmd: uitrace (--ei secs <n>, default 30) logs, 5 times a second under the tag MspTrace, what the
 * app shows (position, duration, playing, title, cover) next to what the page plays, so a run
 * can tell a jumping bar or a stale title/cover from the engine's own state (tools/qa.py --ui).
 * cmd: crossfade (--ei secs <0..12>) sets the crossfade between songs, as the Settings slider does.
 * cmd: gql (--es op <operation> --es vars <json>) runs a persisted operation through the app's own
 * Gql layer and logs the raw response: learning a server shape from the phone's real account beats
 * guessing one from a bundle.
 * cmd: extlist logs the installed extensions; extuninstall (--es id <extension id>) removes one
 * through the app's own uninstall path, as its page's Uninstall button does.
 */
class TestControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd").orEmpty()
        Log.i("MspPlayer", "test command $cmd")
        when (cmd) {
            "gql" -> {
                val op = intent.getStringExtra("op").orEmpty()
                val variables = intent.getStringExtra("vars").orEmpty().ifBlank { "{}" }
                app.myspoti.host.android.MyspotiRuntime.scope.launch {
                    runCatching {
                        app.myspoti.host.android.MyspotiRuntime.spotify.raw.gql(
                            op,
                            app.myspoti.spotify.bridge.Bridge.json.parseToJsonElement(variables).jsonObject,
                        )
                    }.onSuccess { Log.i("MspPlayer", "gql $op vars=$variables -> ${it.toString().take(4000)}") }
                        .onFailure { Log.i("MspPlayer", "gql $op vars=$variables failed: $it") }
                }
            }
            "play" -> SongPlayer.playSong(intent.getStringExtra("track").orEmpty(), contextUri = intent.getStringExtra("context"))
            "resume" -> SongPlayer.play()
            "pause" -> SongPlayer.pause()
            "next" -> SongPlayer.next()
            // The player's own "next" button and the mini player's swipe.
            "swipenext" -> SongPlayer.swipeNext()
            "previous" -> SongPlayer.previous()
            "crossfade" -> com.music.spotui.data.preferences.CrossfadePref.set(context, intent.getIntExtra("secs", 0))
            "uitrace" -> {
                val secs = intent.getIntExtra("secs", 30).coerceIn(1, 600)
                app.myspoti.host.android.MyspotiRuntime.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    val song = Graph.currentSongState
                    val end = android.os.SystemClock.elapsedRealtime() + secs * 1000L
                    while (android.os.SystemClock.elapsedRealtime() < end) {
                        val now = System.currentTimeMillis()
                        val e = app.myspoti.host.android.MyspotiRuntime.spotify.player.state.value
                        Log.i("MspTrace", "t=${android.os.SystemClock.elapsedRealtime()} ui_pos=${SongPlayer.getCurrentPosition()} " +
                            "ui_dur=${SongPlayer.getDuration()} ui_play=${SongPlayer.isPlaying()} ui_uri=${SongPlayer.nowPlaying.value.uri} " +
                            "ui_cover=${song.coverUri.value.substringAfterLast('/')} eng_uri=${e?.item?.uri} " +
                            "eng_pos=${e?.positionAt(now)} eng_paused=${e?.isPaused} eng_buf=${e?.isBuffering} " +
                            "ui_title=${song.title.value} | eng_title=${e?.item?.name}")
                        kotlinx.coroutines.delay(200)
                    }
                    Log.i("MspTrace", "end")
                }
            }
            "seek" -> SongPlayer.seekTo(intent.getLongExtra("ms", 0L))
            "screenon" -> context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("keep_screen_on", intent.getBooleanExtra("on", true)).apply()
            // Debug builds: the same report the Settings row writes, so an unattended run can check
            // that the export really produces a readable file (and where it lands).
            "diagnose" -> runCatching {
                val file = app.myspoti.host.android.Logbook.export(context)
                Log.i("MspPlayer", "diagnostic report: ${file.absolutePath} (${file.length()} bytes)")
            }.onFailure { Log.i("MspPlayer", "diagnostic report failed: $it") }
            // Debug: the friend feed on each host and the typed result, as shapes and counts only
            // (no friend's name or track leaves the phone's log).
            "friends" -> app.myspoti.host.android.MyspotiRuntime.scope.launch {
                val spotify = app.myspoti.host.android.MyspotiRuntime.spotify
                for (host in listOf("guc-spclient.spotify.com", "spclient.wg.spotify.com")) {
                    runCatching { spotify.raw.get("https://$host/presence-view/v1/buddylist") }
                        .onSuccess { Log.i("MspPlayer", "buddylist $host -> ${shape(it)}") }
                        .onFailure { Log.i("MspPlayer", "buddylist $host failed: $it") }
                }
                runCatching { app.myspoti.host.android.MyspotiRuntime.api.users.friendActivity() }
                    .onSuccess { l -> Log.i("MspPlayer", "friendActivity -> ${l.size} friend(s), now=${l.count { it.isNowPlaying() }}, " +
                        "withTrack=${l.count { it.track != null }}, withContext=${l.count { it.contextName != null }}, withCover=${l.count { it.trackImageUrl != null }}") }
                    .onFailure { Log.i("MspPlayer", "friendActivity failed: $it") }
            }
            // Debug: one protobuf call on a spclient path (--es path <path> --es hex <body> --es method POST),
            // logs the status and the answer in hex, to learn the mobile services' shapes.
            "proto" -> app.myspoti.host.android.MyspotiRuntime.scope.launch {
                val path = intent.getStringExtra("path").orEmpty()
                val hex = intent.getStringExtra("hex").orEmpty()
                val body = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                runCatching { app.myspoti.host.android.MyspotiRuntime.spotify.raw.proto(intent.getStringExtra("method") ?: "POST", "https://spclient.wg.spotify.com/$path", body) }
                    .onSuccess { (code, bytes) -> Log.i("MspProbe", "$path -> $code ${bytes.size}B"); bytes.toList().chunked(1500).forEachIndexed { i, part ->
                        Log.i("MspProbe", "$path -> $code ${bytes.size}B [$i] ${part.joinToString("") { "%02x".format(it) }}") } }
                    .onFailure { Log.i("MspProbe", "$path failed: $it") }
            }
            // Debug: one Messages call through the app's own API (--es op <method> --es a <arg> --es b <arg>),
            // logs its answer, to check each action against Spotify without the UI.
            "msg" -> app.myspoti.host.android.MyspotiRuntime.scope.launch {
                val m = app.myspoti.host.android.MyspotiRuntime.api.messages
                val a = intent.getStringExtra("a").orEmpty()
                val b = intent.getStringExtra("b").orEmpty()
                val op = intent.getStringExtra("op").orEmpty()
                runCatching<Any?> {
                    when (op) {
                        "chats" -> m.chats().joinToString("\n") { "${it.id} ${it.name ?: it.members.joinToString { u -> u.name }} group=${it.group} admin=${it.admin} request=${it.request} unread=${it.unread}" }
                        "messages" -> m.messages(a, 8).joinToString("\n") { "${it.id} ${it.author.name}: ${it.text ?: it.shared?.title ?: it.event ?: it.gifUrl ?: "media"} reactions=${it.reactions.map { r -> r.id + r.emoji + r.user.name }}" }
                        "send" -> m.send(a, b)
                        "gifs" -> m.gifs(a).take(5).joinToString("\n") { "${it.id} ${it.width}x${it.height} ${it.url}" }
                        "sendgif" -> m.sendGif(a, m.gifs(b).first())
                        "share" -> m.share(a, b)
                        "seen" -> m.markSeen(a)
                        "react" -> m.react(a, b)
                        "unreact" -> m.unreact(app.myspoti.api.ChatReaction(a, b, app.myspoti.api.UserRef("", "", null), true))
                        "delete" -> m.delete(a, b == "all")
                        "muted" -> m.isMuted(a)
                        "mute" -> m.setMuted(a, b != "off")
                        "rename" -> m.rename(a, b)
                        "addmembers" -> m.addMembers(a, b.split(','))
                        "removemember" -> m.removeMember(a, b)
                        "creategroup" -> m.createGroup(a.split(','), b.ifBlank { null })
                        "leave" -> m.leave(a)
                        "deletechat" -> m.deleteChat(a)
                        "answer" -> m.answerRequest(a, b == "yes")
                        else -> "unknown op $op"
                    }
                }.onSuccess { Log.i("MspMsg", "$op -> ${it ?: "ok"}") }.onFailure { Log.i("MspMsg", "$op failed: $it") }
            }
            // Debug: the account's Spotify top lists per window (counts and totals only).
            "extlist" -> com.music.spotui.extensions.Extensions.registry.value.extensions
                .forEach { Log.i("MspPlayer", "extension ${it.id} ${it.version} enabled=${it.enabled} source=${it.source}") }
            "extuninstall" -> app.myspoti.host.android.MyspotiRuntime.scope.launch {
                val id = intent.getStringExtra("id").orEmpty()
                if (com.music.spotui.extensions.Extensions.registry.value.find(id) == null) Log.i("MspPlayer", "extension $id: not installed")
                else { com.music.spotui.extensions.Extensions.uninstall(id); Log.i("MspPlayer", "extension $id: uninstalled") }
            }
            "top" -> app.myspoti.host.android.MyspotiRuntime.scope.launch {
                app.myspoti.api.TimeRange.entries.forEach { r ->
                    runCatching { app.myspoti.host.android.MyspotiRuntime.api.users.top(r, 50) }
                        .onSuccess { Log.i("MspPlayer", "top $r -> artists=${it.artists.size}/${it.artistTotal} tracks=${it.tracks.size}/${it.trackTotal}") }
                        .onFailure { Log.i("MspPlayer", "top $r failed: $it") }
                }
            }
            // Debug: downloads one song (--es id <spotify id> --es title T --es artist A --ei ms <duration>)
            // through the download-source extensions, as the download button does.
            "download" -> com.music.spotui.data.offline.OfflineDownloader.enqueue(listOf(com.music.spotui.data.entity.SongsModel(
                0, intent.getStringExtra("title").orEmpty(), "", intent.getStringExtra("artist").orEmpty(), "", "",
                spotifyTrackId = intent.getStringExtra("id").orEmpty(), durationMs = intent.getIntExtra("ms", 0),
            )))
            // Debug: plays the downloaded songs in the local player (--es id <spotify id> to start there).
            "playdownloaded" -> {
                val all = com.music.spotui.data.offline.OfflineStore.tracks.values.toList()
                val at = all.indexOfFirst { it.trackId == intent.getStringExtra("id") }.coerceAtLeast(0)
                Log.i("MspPlayer", "downloaded: ${all.size} song(s)")
                if (all.isNotEmpty()) LocalPlayer.play(all.map(com.music.spotui.data.offline.OfflineStore::toLocalTrack), at, context = "test")
            }
            // Debug: how many local audio files the app would list (Local Files check without the UI).
            "localfiles" -> Thread {
                val n = runCatching { kotlinx.coroutines.runBlocking { com.music.spotui.data.local.LocalMusic.tracks(context, refresh = true).size } }
                Log.i("MspPlayer", "local files: ${n.getOrElse { "failed: $it" }} track(s)")
            }.start()
        }
    }
}

/** A JSON value's structure without its values: object keys, array sizes and the first element's shape. */
private fun shape(e: kotlinx.serialization.json.JsonElement?, depth: Int = 0): String = when {
    e == null -> "null"
    depth > 4 -> "..."
    e is kotlinx.serialization.json.JsonObject -> e.entries.joinToString(",", "{", "}") { (k, v) -> "$k:${shape(v, depth + 1)}" }
    e is kotlinx.serialization.json.JsonArray -> "[${e.size}]" + (e.firstOrNull()?.let { shape(it, depth + 1) } ?: "")
    e is kotlinx.serialization.json.JsonNull -> "null"
    else -> (e as kotlinx.serialization.json.JsonPrimitive).let { if (it.isString) "s" else "n" }
}
