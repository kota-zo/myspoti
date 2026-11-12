/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import okhttp3.OkHttpClient

/** The providers that need no account, in the order they are asked unless the user changes it. */
val DEFAULT_ORDER = listOf("deezer", "ytmusic", "tidal", "itunes", "musicbrainz")

/**
 * The providers that need no account, by id, each made once (with [scriptSet], the verified downloaded scripts over the
 * bundled ones). A chain picks from them in the user's order, so changing the order keeps what they learned.
 */
fun accountlessProviders(
    http: OkHttpClient,
    userAgent: String,
    countryCode: String,
    scriptSet: ScriptSet? = null,
): Map<String, MetadataProvider> {
    val scripts = OkHttpScripts(http)
    fun web(name: String, paceMs: Long) = ReliableFetch(HttpFetch(http, userAgent, paceMs), name)
    val settings = mapOf("countryCode" to countryCode)
    // A verified downloaded script replaces the bundled source only: its hosts and limits stay those of the ScriptSpec,
    // and the bundled script takes over when it fails by itself.
    fun script(spec: ScriptSpec, settings: Map<String, String>): ScriptProvider {
        val downloaded = scriptSet?.get(spec.id)
        return if (downloaded != null) ScriptProvider(spec, scripts, userAgent, downloaded, settings, fallback = bundledScript(spec.id))
        else ScriptProvider(spec, scripts, userAgent, settings = settings)
    }
    return linkedMapOf<String, MetadataProvider>(
        "deezer" to script(ScriptSpec.DEEZER, settings),
        "ytmusic" to script(ScriptSpec.YOUTUBE_MUSIC, mapOf("homeCountry" to countryCode)),
        "tidal" to script(ScriptSpec.TIDAL, settings),
        // About 20 calls a minute.
        "itunes" to ITunes(web("itunes", 3_100), countryCode),
        // One request a second, for each address.
        "musicbrainz" to MusicBrainz(web("musicbrainz", 1_100), web("listenbrainz", 500)),
    )
}

/**
 * [order] as providers: [signedIn] (the user's signed-in platform) where [order] names it, first when it does not,
 * then the others by id. What [order] leaves out is not asked at all, so a user can turn a provider off; a signed-in
 * platform turned off is not passed.
 */
fun chainOf(providers: Map<String, MetadataProvider>, order: List<String>, signedIn: MetadataProvider? = null): List<MetadataProvider> {
    val listed = order.distinct().mapNotNull { id -> if (signedIn != null && id == signedIn.id) signedIn else providers[id] }
    return if (signedIn == null || signedIn in listed) listed else listOf(signedIn) + listed
}

/**
 * The chain for this user (with [scriptSet], the verified downloaded scripts, over the bundled ones): their
 * signed-in platform first unless [order] places it, then [order].
 */
fun providerChain(
    http: OkHttpClient,
    userAgent: String,
    countryCode: String,
    signedIn: MetadataProvider? = null,
    order: List<String> = DEFAULT_ORDER,
    scriptSet: ScriptSet? = null,
): ProviderChain = ProviderChain(chainOf(accountlessProviders(http, userAgent, countryCode, scriptSet), order, signedIn))
