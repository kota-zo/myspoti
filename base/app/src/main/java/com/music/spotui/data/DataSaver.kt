package com.music.spotui.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Data saver (a switch in the profile menu): on mobile data or another metered network
 * (never on Wi-Fi) the app uses no data at all, as if offline. Every connection is
 * refused ([check], [interceptor], the WebView and Glide guards), the hidden Spotify
 * player is shut down, and only downloaded songs and Local Files play. Back on Wi-Fi,
 * the player is rebuilt.
 */
object DataSaver {
    private const val PREFS = "myspoti_prefs"
    private const val KEY = "DataSaver"

    /** The user's switch. */
    var enabled by mutableStateOf(false)
        private set
    /** The current network costs data (mobile data, a hotspot). */
    var metered by mutableStateOf(false)
        private set
    private var context: Context? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var wasActive = false

    /** The saver is on and applies right now. */
    val active: Boolean get() = enabled && metered

    /** A connection refused by the data saver. */
    class Blocked : java.io.IOException("Data saver: no mobile data")

    /** Throws [Blocked] while the saver applies: call before opening any connection. */
    fun check() {
        if (active) throw Blocked()
    }

    /** For OkHttp clients (and Ktor's OkHttp engine). */
    val interceptor = okhttp3.Interceptor { chain -> check(); chain.proceed(chain.request()) }

    /** Shuts the hidden Spotify player down when the saver starts applying, rebuilds it when it stops. */
    private fun changed() {
        val now = active
        if (now == wasActive) return
        wasActive = now
        main.post {
            if (now) app.myspoti.host.android.MyspotiRuntime.suspendPlayer()
            else app.myspoti.host.android.MyspotiRuntime.resume()
        }
    }

    fun init(c: Context) {
        if (context != null) return
        val app = c.applicationContext
        context = app
        enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        metered = cm.isActiveNetworkMetered
        wasActive = active
        runCatching {
            val callback = object : ConnectivityManager.NetworkCallback() {
                private fun updateMetered(nowMetered: Boolean) {
                    val was = metered
                    metered = nowMetered
                    if (was && !metered) com.music.spotui.data.offline.OfflineCollections.onUnmetered(app)
                    // Wi-Fi and mobile data each have their own streaming level.
                    if (was != metered) { com.music.spotui.data.preferences.applyStreamingQuality(app); com.music.spotui.extensions.Extensions.networkChanged() }
                    changed()
                }
                override fun onAvailable(network: Network) {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) updateMetered(cm.isActiveNetworkMetered)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val now = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    else cm.isActiveNetworkMetered
                    updateMetered(now)
                }
                override fun onLost(network: Network) = updateMetered(cm.isActiveNetworkMetered)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                cm.registerNetworkCallback(
                    android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build(),
                    callback,
                )
            }
        }
    }

    fun set(on: Boolean) {
        enabled = on
        changed()
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY, on)?.apply()
    }
}
