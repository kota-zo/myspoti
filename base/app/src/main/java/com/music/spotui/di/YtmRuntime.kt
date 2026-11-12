package com.music.spotui.di

import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.myspoti.api.account.AccountProviders
import app.myspoti.catalog.ApiProvider
import app.myspoti.host.android.HiddenPageHost
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.ytmusic.Ytm
import app.myspoti.ytmusic.YtmAccount
import com.music.spotui.data.preferences.AccountProviderPref
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * YouTube Music as the account provider ([AccountProviderPref]): its hidden page (the official web
 * app, in a WebView profile of its own), the session the user signs in to in a WebView of Google's
 * own sign-in ([HiddenPageHost.signInView]), and what reads it: the listen sync
 * ([AccountSync]), the library and the metadata provider first in the catalog while signed in. Nothing
 * is made (no WebView) until YouTube Music is chosen. Main thread.
 */
object YtmRuntime {
    private const val PROFILE = "ytmusic"
    @Volatile private var made = false

    val host: HiddenPageHost by lazy {
        made = true
        HiddenPageHost(Graph.app, PROFILE, Ytm.ORIGIN, Ytm.SESSION_URLS, if (MyspotiRuntime.isStarted) MyspotiRuntime.host.userAgent else null)
    }

    val account: YtmAccount by lazy {
        YtmAccount(
            host, host, MyspotiRuntime.apiHttp, MyspotiRuntime.scope,
            language = { Locale.getDefault().language.ifBlank { "en" } },
            country = { Locale.getDefault().country.takeIf { it.length == 2 } ?: "US" },
        ).also { a -> a.bridge.logger = { line -> Logbook.log("ytmusic $line") } }
    }

    /** The catalog's provider on the user's session (first in the chain while signed in). */
    val metadata: ApiProvider by lazy { ApiProvider(Ytm.PROVIDER, account.metadata, account.metadata) }

    /** Signed in, as the session's cookies said last (for the screens). */
    var signedIn by mutableStateOf(false)
        private set

    val chosen: Boolean get() = AccountProviderPref.id == AccountProviders.YTMUSIC

    /**
     * Chosen: the session is read and, signed in with the listen sync on, the page opened. Not
     * chosen, or the sync off: the page is freed. The library and the metadata read YouTube Music's
     * API on the session's cookies, without the page; only the sync plays in it (it loaded the
     * whole web app at every start, the sync on or off).
     */
    fun startIfChosen() {
        watchSyncSetting()
        if (!chosen) { if (made) host.unload(); return }
        window?.get()?.let(host::attach)
        MyspotiRuntime.scope.launch {
            val ok = runCatching { account.refreshSignIn() }.getOrDefault(false)
            signedIn = ok
            val sync = com.music.spotui.data.preferences.ConnectMirrorPref.on
            Logbook.log("ytmusic: ${if (ok) "signed in" else "not signed in"}, listen sync ${if (sync) "on" else "off"}")
            if (ok && sync && !com.music.spotui.data.DataSaver.active) runCatching { account.open() }
            else if (!sync && made) host.unload()
            AccountSync.listen.refresh()
        }
    }

    private var watchingSync = false

    /** The listen sync turned on or off in the settings: the page is opened or freed with it. */
    private fun watchSyncSetting() {
        if (watchingSync) return
        watchingSync = true
        MyspotiRuntime.scope.launch {
            androidx.compose.runtime.snapshotFlow { com.music.spotui.data.preferences.ConnectMirrorPref.on }
                .drop(1).collect { if (chosen) startIfChosen() }
        }
    }

    /** The sign-in WebView came back to YouTube Music ([ok]: an account is signed in). */
    fun signInFinished(ok: Boolean) {
        Logbook.log("ytmusic: sign-in ${if (ok) "done" else "left signed out"}")
        host.flush()
        startIfChosen()
    }

    /** Signs out of YouTube Music on this phone: the sync stops, the session's cookies are deleted. */
    fun signOut() {
        AccountSync.listen.stop("signed out of YouTube Music")
        MyspotiRuntime.scope.launch {
            runCatching { account.signOut() }
            signedIn = false
            host.unload()
            AccountSync.listen.refresh()
        }
    }

    /** The page kept in the activity's window, hidden (see [HiddenPageHost.attach]); only once it exists. */
    fun attach(container: ViewGroup) {
        window = java.lang.ref.WeakReference(container)
        if (chosen) host.attach(container)
    }

    fun detach(container: ViewGroup) {
        if (window?.get() === container) window = null
        if (made) host.detach(container)
    }

    /** The activity's root the page is kept in, while there is one. */
    private var window: java.lang.ref.WeakReference<ViewGroup>? = null
}
