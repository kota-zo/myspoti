/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.web

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Where an account provider's hidden page runs: an Android WebView (`:host-android`) or a tab of a
 * desktop Chrome over the DevTools protocol (`:host-cdp`), so the same page script is tested on a PC.
 * The host loads the provider's official site with the user's own session (the cookies of its
 * sign-in), relays the page's messages and says when a page finished loading. It blocks nothing.
 */
interface PageHost {
    /** JSON strings the page script posts (`window.MyspotiHost.postMessage` on Android, `window.__mspHostPost` over CDP). */
    val messages: Flow<String>

    /** A page finished loading (its url): the bridge script is injected again. */
    val loads: Flow<String>

    /**
     * The page died under the host (its renderer was killed or crashed): what it had is lost, and the
     * host is loading a new one already. A host whose pages cannot die this way says nothing.
     */
    val gone: Flow<Unit> get() = emptyFlow()

    /** Runs [script] in the page's main frame. Fire and forget: answers come back through [messages]. */
    suspend fun evaluate(script: String)

    /** Loads [url] in the page (a new document). */
    suspend fun navigate(url: String)
}

/**
 * The cookies of the user's sign-in on the provider's site, as the host's browser keeps them. They are
 * the session itself (full access to the account): never logged, never written anywhere else.
 */
interface SessionCookies {
    /** The `Cookie` header the browser would send to [url], or null when it holds none. */
    suspend fun header(url: String): String?

    /** Signs out: every cookie of the provider's sites is deleted from the host's browser. */
    suspend fun clear()
}

/** Splits a `Cookie` header into its names and values (the first value of a name wins). */
fun parseCookieHeader(header: String?): Map<String, String> {
    if (header.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in header.split(';')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val name = part.substring(0, eq).trim()
        if (name.isNotEmpty() && name !in out) out[name] = part.substring(eq + 1).trim()
    }
    return out
}
