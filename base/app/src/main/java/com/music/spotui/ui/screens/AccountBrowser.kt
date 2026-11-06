package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Spotify's account pages (plan, privacy, apps, devices) in a browser that shares the
 * app's sign-in: WebViews on the same profile (the active account's) share cookies, so no second login.
 */
class AccountBrowser : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            // The active account's WebView profile, before anything else: its sign-in is the one shared.
            val host = app.myspoti.host.android.MyspotiRuntime.host
            host.useProfile(this)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            host.cookies().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()
        }
        val url = intent.getStringExtra(EXTRA_URL) ?: ACCOUNT_URL
        val language = com.music.spotui.data.AppLanguage.current(this).ifEmpty { null }
            ?.let { java.util.Locale.forLanguageTag(it).language } ?: resources.configuration.locales[0].language
        lifecycleScope.launch {
            web.loadUrl(withContext(Dispatchers.IO) { runCatching { localized(url, language) }.getOrNull() } ?: url)
        }
        // Android draws apps edge to edge: the page sits in a frame kept out from under the
        // status and navigation bars (a WebView's own padding does not move its content).
        val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK); addView(web) }
        setContentView(frame)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private val http by lazy {
            OkHttpClient.Builder().followRedirects(false).callTimeout(6, TimeUnit.SECONDS).build()
        }
        private val MARKET = Regex("^/([a-z]{2})(?:-[a-z]{2})?/")

        /**
         * www.spotify.com picks the page language from the country it sees ("/ch-de/"), not
         * from the app: the same page in the app's language ("/ch-en/") when Spotify has it.
         */
        private fun localized(url: String, language: String): String? {
            val target = url.toHttpUrlOrNull() ?: return null
            if (target.host != "www.spotify.com") return null
            val path = target.encodedPath
            val market = MARKET.find(path)?.groupValues?.get(1) ?: run {
                val probe = Request.Builder().url("https://www.spotify.com/premium/").head().build()
                val where = http.newCall(probe).execute().use { it.header("Location") } ?: return null
                MARKET.find(where.toHttpUrlOrNull()?.encodedPath ?: return null)?.groupValues?.get(1) ?: return null
            }
            val prefix = "/$market-$language/"
            // Spotify answers 200 for the combinations it has, and redirects the others.
            val check = Request.Builder().url("https://www.spotify.com${prefix}premium/").head().build()
            if (http.newCall(check).execute().use { it.code } != 200) return null
            val rest = MARKET.find(path)?.let { path.substring(it.range.last + 1) } ?: path.removePrefix("/")
            return target.newBuilder().encodedPath(prefix + rest).build().toString()
        }

        const val ACCOUNT_URL = "https://www.spotify.com/account/overview/"
        private const val EXTRA_URL = "url"

        fun open(context: Context, url: String) {
            context.startActivity(Intent(context, AccountBrowser::class.java)
                .putExtra(EXTRA_URL, url)
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }
}
