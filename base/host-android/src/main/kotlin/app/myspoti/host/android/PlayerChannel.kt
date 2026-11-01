package app.myspoti.host.android

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Gives the Web Player page a `window.<name>.postMessage(string)` channel to the app.
 *
 * An `addJavascriptInterface` object is injected into every frame of the WebView, whatever its
 * origin: the player page embeds a google.com reCAPTCHA iframe, which could then post to the
 * app. A web message listener is only injected into frames of [PLAYER_ORIGIN], and only the
 * main frame's messages are taken. WebViews without the feature fall back to the interface,
 * guarded by a secret (see [PlayerChannel]); the page calls the same `postMessage` either way.
 * Returns the fallback channel to arm at each page start, or null when the listener is used.
 */
@SuppressLint("JavascriptInterface")
internal fun WebView.exposeToPlayer(name: String, onMessage: (String) -> Unit): PlayerChannel? {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(this, name, setOf(PLAYER_ORIGIN)) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) message.data?.let(onMessage)
        }
        return null
    }
    return PlayerChannel(name, onMessage).also { addJavascriptInterface(it, name) }
}

private const val PLAYER_ORIGIN = "https://open.spotify.com"

/**
 * Fallback channel for WebViews without web message listeners, where the interface object is
 * reachable from every frame. A message is only taken when it starts with the page's secret:
 * a random token, new at each page start ([newPage]), that [shim] hands to the main frame's
 * script only. The shim replaces `window.<name>` in the top document by a wrapper that keeps the
 * raw interface and the token in its closure, so the scripts of the app go on calling
 * `postMessage(json)` unchanged; a frame of another origin cannot read the top document's
 * closure and its messages carry no valid token. Refused messages are dropped and counted in the log.
 */
internal class PlayerChannel(private val name: String, private val onMessage: (String) -> Unit) {
    @Volatile private var token = ""
    private val refused = java.util.concurrent.atomic.AtomicInteger()

    /** A fresh secret for the page that starts now; the previous one stops working. */
    fun newPage() {
        token = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }

    /** The script that installs the wrapper in the top document with the current secret; harmless twice. */
    fun shim(): String = """
        (() => {
          if (window !== window.top) return;
          const raw = window.$name;
          if (!raw || raw.__msp) return;
          const wrapper = { __msp: true, postMessage: (m) => raw.postMessage('$token.' + m) };
          try { Object.defineProperty(window, '$name', { value: wrapper }); } catch (e) { window.$name = wrapper; }
        })();
    """.trimIndent()

    @JavascriptInterface
    fun postMessage(message: String) {
        val t = token
        if (t.isEmpty() || message.length <= t.length || !message.startsWith(t) || message[t.length] != '.') {
            val n = refused.incrementAndGet()
            Logbook.logEvery("channel-refused-$name", 60_000, "$name message without the page's secret ignored ($n so far)")
            return
        }
        onMessage(message.substring(t.length + 1))
    }
}
