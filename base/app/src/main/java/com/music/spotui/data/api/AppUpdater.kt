package com.music.spotui.data.api

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads a release's APK and installs it over this app with Android's package
 * installer: the system asks the user to confirm, nothing opens in a browser.
 * The APK is signed with the same key as the installed app, or Android refuses it.
 */
object AppUpdater {
    sealed interface State {
        data object Idle : State
        /** [progress] 0..1, or -1 while the size is unknown. */
        data class Downloading(val progress: Float) : State
        /** Handed to the system installer, waiting for the user's confirmation. */
        data object Installing : State
        /** Android needs "Install unknown apps" allowed for Myspoti first. */
        data object NeedsPermission : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private const val ACTION = "app.myspoti.UPDATE_STATUS"
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Android lets this app install packages (the "Install unknown apps" switch). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page with Myspoti's "Install unknown apps" switch. */
    fun openInstallSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Downloads [release]'s APK, then starts the install. Updates [state] as it goes. */
    suspend fun update(context: Context, release: UpdateChecker.Release) {
        val app = context.applicationContext
        if (!canInstall(app)) { _state.value = State.NeedsPermission; return }
        _state.value = State.Downloading(-1f)
        val apk = File(File(app.cacheDir, "updates"), "myspoti-${release.version}.apk")
        val result = runCatching {
            download(release, apk)
            install(app, apk)
        }
        // The installer session holds its own copy once committed; a failed download left a partial one.
        runCatching { apk.delete() }
        result.exceptionOrNull()?.let {
            android.util.Log.w("AppUpdater", "update failed", it)
            _state.value = State.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    fun reset() { _state.value = State.Idle }

    /** No release of this app comes near it; a bigger answer is not an APK of ours. */
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    private suspend fun download(release: UpdateChecker.Release, file: File): Unit = withContext(Dispatchers.IO) {
        val dir = file.parentFile!!.apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val total = r.body.contentLength()
            if (total > MAX_APK_BYTES) throw java.io.IOException("the download is too big ($total bytes)")
            var done = 0L
            var shown = -1
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) throw java.io.IOException("the download is too big (over $MAX_APK_BYTES bytes)")
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) { shown = pct; _state.value = State.Downloading(pct / 100f) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw java.io.IOException("download cut at $done / $total bytes")
        }
    }

    /** Streams the APK into an installer session; [StatusReceiver] gets the outcome. */
    private suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        _state.value = State.Installing
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // User action stays required (the default): without the
            // UPDATE_PACKAGES_WITHOUT_USER_ACTION permission a NOT_REQUIRED flag
            // would be ignored anyway; the confirmation is the user's last check.
        }
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                session.openWrite("myspoti.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    context, id,
                    Intent(ACTION).setPackage(context.packageName).setClass(context, StatusReceiver::class.java),
                    // Mutable: the installer adds the status extras.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
                committed = true
            }
        } finally {
            // A session that never reached the commit would stay open, holding a copy of the APK.
            if (!committed) runCatching { installer.abandonSession(id) }
        }
    }

    /** The installer's answer: ask the user to confirm, or report the failure. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                // On success this process is replaced by the new version.
                PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle
                PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = State.Idle
                else -> _state.value = State.Failed(
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install status $status",
                )
            }
        }
    }
}
