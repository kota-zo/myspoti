package com.music.spotui.cli

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.di.SongPlayer
import com.music.spotui.extensions.Extensions
import com.music.spotui.extensions.Grants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The command line's door (`adb shell am broadcast -a <package>.CLI -p <package> --es cmd … --es args64 …
 * --es req <id>`), see docs/cli.md and tools/myspoti-cli. Only the adb shell may send it (DUMP), the
 * manifest turns it on in debug and beta builds only, and it answers nothing elsewhere. The reply goes
 * to `<external files>/cli/<req>.json` and, cut, to logcat under [TAG].
 */
class CliReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!enabled) return
        val cmd = intent.getStringExtra("cmd").orEmpty()
        val req = CliReply.safeRequestId(intent.getStringExtra("req")) ?: "last"
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            val reply = try {
                val command = CliParser.parse(cmd, CliParser.args(intent.getStringExtra("args"), intent.getStringExtra("args64")))
                CliReply.ok(cmd, run(app, command))
            } catch (e: CliError) {
                CliReply.error(cmd, e.message.orEmpty())
            } catch (t: Throwable) {
                CliReply.error(cmd, CliMask.text(t.message ?: t.toString()))
            }
            runCatching {
                val dir = File(app.getExternalFilesDir(null), "cli").apply { mkdirs() }
                val tmp = File(dir, "$req.json.tmp")
                tmp.writeText(reply)
                tmp.renameTo(File(dir, "$req.json"))
            }.onFailure { Log.w(TAG, "$req: reply not written: $it") }
            Log.i(TAG, "$req $cmd ${reply.take(3500)}")
            // 1: the receiver answered (an absent or disabled one leaves 0), how tools/myspoti-cli tells them apart.
            pending.resultCode = 1
            pending.resultData = if (reply.length < 1000) reply else null
            pending.finish()
        }
    }

    private suspend fun run(ctx: Context, c: CliCommand): JsonElement = when (c) {
        CliCommand.Status -> {
          // What the page streams now (bitrate, codec): one bridge call, for the mirror's quality check.
          val audio = withContext(Dispatchers.Main) { if (MyspotiRuntime.isStarted) MyspotiRuntime.spotify.player.audioInfo() else 0 to "" }
          onMain {
            val np = SongPlayer.nowPlaying.value
            buildJsonObject {
                put("package", ctx.packageName)
                put("version", app.myspoti.engine.BuildConfig.VERSION_NAME)
                put("versionCode", app.myspoti.engine.BuildConfig.VERSION_CODE)
                put("debug", app.myspoti.engine.BuildConfig.DEBUG)
                put("beta", com.music.spotui.data.api.UpdateChecker.isBeta)
                put("signedIn", ctx.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE).getBoolean("LoggedIn", false))
                put("playing", SongPlayer.isPlaying())
                put("uri", np.uri)
                put("context", np.contextUri)
                put("positionMs", SongPlayer.getCurrentPosition())
                put("durationMs", SongPlayer.getDuration())
                // Who plays: the app's own player (an extension's stream, a download, a file) or Spotify's engine.
                val local = com.music.spotui.di.LocalPlayer.active.value && !SongPlayer.routing
                put("player", if (local) "local" else "engine")
                put("routing", SongPlayer.routing)
                // The local player's own view: asked to play, and idle (stopped by an error), buffering, ready or ended.
                if (com.music.spotui.di.LocalPlayer.active.value) {
                    put("playWhenReady", com.music.spotui.di.LocalPlayer.playWhenReady())
                    put("playbackState", com.music.spotui.di.LocalPlayer.playbackState())
                }
                if (local) com.music.spotui.di.LocalPlayer.current()?.let { t ->
                    val streamed = t.uri.scheme == com.music.spotui.extensions.ExtensionAudio.SCHEME
                    put("source", if (streamed) "audio-source" else if (com.music.spotui.data.offline.OfflineStore.byLocalId(t.id) != null) "download" else "file")
                    if (streamed) com.music.spotui.extensions.ExtensionAudio.providerOf(t.uri.lastPathSegment.orEmpty())?.let { put("extension", it) }
                }
                put("extensions", if (Extensions.isReady) Extensions.registry.value.extensions.size else 0)
                put("ui", nav.subscriptionCount.value > 0)
                // The Connect mirror ("Count extension plays on Spotify") and the page under it.
                put("mirror", buildJsonObject {
                    val m = com.music.spotui.di.ConnectMirror.status()
                    put("on", m.on); put("running", m.running); put("handedAway", m.handedAway); put("landed", m.landed)
                    m.uri?.let { put("uri", it) }; m.paused?.let { put("paused", it) }
                    m.webPositionMs?.let { put("webPositionMs", it) }; m.failedUri?.let { put("failedUri", it) }
                    if (MyspotiRuntime.isStarted) {
                        val page = MyspotiRuntime.spotify.player
                        val s = page.state.value
                        put("pageUri", s?.item?.uri ?: "")
                        put("pagePaused", s?.isPaused ?: true)
                        put("pagePositionMs", s?.positionAt(System.currentTimeMillis()) ?: 0L)
                        put("hushed", page.localMute)
                        put("quality", page.streamingLevel)
                        put("bitrate", audio.first); put("codec", audio.second)
                        put("playsElsewhere", page.playsElsewhere.value)
                    }
                    SongPlayer.activeDevice.value?.let { put("activeDevice", it.name) }
                })
            }
          }
        }
        CliCommand.ExtList -> JsonArray(ready().registry.value.extensions.map { e ->
            val m = Extensions.manifest(e.id)
            buildJsonObject {
                put("id", e.id); put("name", e.name); put("version", e.version)
                put("enabled", e.enabled); put("running", Extensions.isRunning(e.id)); put("source", e.source)
                e.disabledReason?.let { put("disabledReason", it) }
                Extensions.errors.value[e.id]?.let { put("error", CliMask.text(it, secrets(e.id))) }
                // Its last status probe: live, inactive (skipped for audio and downloads) or unknown (no answer, still used).
                val st = Extensions.statusOf(e.id)
                put("inactive", st?.state == "inactive")
                put("status", st?.state ?: "not probed")
                st?.detail?.let { put("statusDetail", CliMask.text(it, secrets(e.id))) }
                put("actions", JsonArray(m?.actionSet()?.map { JsonPrimitive(it.key) }.orEmpty()))
                put("settings", CliMask.settings(m?.settings.orEmpty(), Extensions.settingValues(e.id)))
            }
        })
        is CliCommand.ExtInstall -> {
            val f = File(c.path)
            if (!f.isFile) throw CliError("ext.install: no file at ${c.path} (push it under the app's external files folder)")
            val zip = withContext(Dispatchers.IO) { f.readBytes() }
            val p = runCatching { ExtensionPackage.read(zip) }.getOrElse { throw CliError("ext.install: not an extension package: ${it.message}") }
            val m = p.manifest
            val plan = ready().plan(p)
            Extensions.install(zip, p, Grants.keys(m), "file", Extensions.clashes(m), { true }, noticeAccepted = true)
            buildJsonObject {
                put("id", m.id); put("version", m.version); put("plan", plan::class.simpleName)
                put("running", Extensions.isRunning(m.id))
                Extensions.errors.value[m.id]?.let { put("error", CliMask.text(it, secrets(m.id))) }
            }
        }
        is CliCommand.ExtUninstall -> { installed(c.id); Extensions.uninstall(c.id); JsonNull }
        is CliCommand.ExtEnable -> {
            installed(c.id)
            val m = Extensions.manifest(c.id)
            Extensions.setEnabled(c.id, c.on, if (c.on && m != null) Extensions.clashes(m) else emptyList()).join()
            buildJsonObject { put("enabled", Extensions.registry.value.find(c.id)?.enabled); put("running", Extensions.isRunning(c.id)) }
        }
        is CliCommand.ExtSetting -> {
            val s = setting(c.id, c.key)
            if (s.type == "button") throw CliError("ext.setting: ${c.key} is a button, use ext.press")
            Extensions.setSetting(c.id, c.key, c.json)
            JsonPrimitive(if (c.json == null) "reset" else "set")
        }
        is CliCommand.ExtPress -> {
            if (setting(c.id, c.key).type != "button") throw CliError("ext.press: ${c.key} is not a button")
            Extensions.pressSetting(c.id, c.key)
            JsonPrimitive("pressed")
        }
        is CliCommand.ExtCall -> {
            installed(c.id)
            val answer = runCatching { Extensions.callHook(c.id, c.hook, c.input) }.getOrElse { throw CliError("ext.call: ${it.message ?: it}") }
            answer?.let { CliMask.json(runCatching { Json.parseToJsonElement(it) }.getOrElse { _ -> JsonPrimitive(it) }, secrets(c.id)) } ?: JsonNull
        }
        is CliCommand.ExtLog -> {
            installed(c.id)
            val known = secrets(c.id)
            JsonArray(Extensions.logLines(c.id).takeLast(c.lines).map { JsonPrimitive(CliMask.text(it, known)) })
        }
        is CliCommand.ExtWhy -> {
            // Debug builds only: a beta's receiver answers that it is off here (the CLI's exit code 5).
            if (!app.myspoti.engine.BuildConfig.DEBUG) throw CliError(DISABLED)
            // The song playing now: what the decoder reports, read on the player's thread.
            onMain {
                val t = com.music.spotui.di.LocalPlayer.current()?.uri?.takeIf { it.scheme == com.music.spotui.extensions.ExtensionAudio.SCHEME }
                val detected = com.music.spotui.ui.components.detectedLabel(com.music.spotui.di.LocalPlayer.audioFormat())
                if (t != null && detected != null) Extensions.noteAttempt(t.lastPathSegment) { it.copy(detected = detected) }
            }
            val known = Extensions.registry.value.extensions.flatMap { secrets(it.id) }
            CliMask.json(Extensions.attempts.toJson(c.n), known)
        }
        is CliCommand.ExtStorage -> {
            installed(c.id)
            val values = Extensions.storageValues(c.id)
            buildJsonObject {
                put("keys", JsonArray(values.keys.sorted().map(::JsonPrimitive)))
                put("bytes", values.entries.sumOf { it.key.length + it.value.length })
            }
        }
        is CliCommand.Play -> onMain { SongPlayer.playSong(c.uri, contextUri = c.context); JsonNull }
        CliCommand.Pause -> onMain { SongPlayer.pause(by = "cli"); JsonNull }
        CliCommand.Resume -> onMain { SongPlayer.play(by = "cli"); JsonNull }
        CliCommand.Next -> onMain { SongPlayer.next(); JsonNull }
        CliCommand.Previous -> onMain { SongPlayer.previous(); JsonNull }
        is CliCommand.Nav -> {
            if (nav.subscriptionCount.value == 0) throw CliError("nav: the app's screen is not open (adb shell monkey -p ${ctx.packageName} 1)")
            nav.emit(c.route)
            JsonPrimitive(c.route)
        }
    }

    private fun ready(): Extensions = Extensions.also { if (!it.isReady) throw CliError("extensions are not loaded yet") }

    private fun installed(id: String) { if (ready().registry.value.find(id) == null) throw CliError("$id is not installed") }

    private fun setting(id: String, key: String) = installed(id).let {
        Extensions.manifest(id)?.settings?.firstOrNull { it.key == key } ?: throw CliError("$id has no setting '$key'")
    }

    private fun secrets(id: String): List<String> {
        val m = Extensions.manifest(id)
        return CliMask.knownSecrets(m?.settings.orEmpty(), Extensions.settingValues(id), Extensions.storageValues(id))
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    companion object {
        const val TAG = "MyspotiCli"

        /** The error of a command this build does not have (tools/myspoti-cli exits with 5 on it). */
        const val DISABLED = "disabled in this build"

        /** Debug and beta builds; the manifest leaves the receiver off in the others too. */
        val enabled: Boolean get() = app.myspoti.engine.BuildConfig.DEBUG || com.music.spotui.data.api.UpdateChecker.isBeta

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** `nav <route>`: the app's screen navigates there (App collects it). */
        val nav = MutableSharedFlow<String>(extraBufferCapacity = 1)
    }
}
