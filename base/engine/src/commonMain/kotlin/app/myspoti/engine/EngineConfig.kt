// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

/**
 * Who the engine says it is: the Web Player in a desktop browser, with the values that browser
 * sends. Defaults are the ones measured in the play-bodies capture (Firefox 159 on Linux,
 * 2026-11-10, Web Player build 1.3.10.962, player SDK harmony 4.81.1); the page bridge should
 * pass the live `clientVersion` of `appServerConfig` and its own browser identity.
 */
data class EngineConfig(
    /** `spotify-app-version` header and the clienttoken `client_version` (appServerConfig `clientVersion`). */
    val appVersion: String = "1.3.10.962.g89de28870671",
    /** appServerConfig `buildVersion`, the `play_origin.feature_version` of play commands. */
    val buildVersion: String = "web-player_2026-11-09_1794251286219_89de28870671",
    /** Player SDK name and version (`harmony:4.81.1-1f3d48b7f` in register and some melody batches). */
    val sdkVersion: String = "4.81.1",
    val sdkBuild: String = "1f3d48b7f",
    /** Browser name as the Web Player prints it in the device name: "Firefox", "Chrome". */
    val browserName: String = "Firefox",
    /** Browser version as in `platform_identifier`: "159.0" (Firefox), "155.0.0.0" (Chrome). */
    val browserVersion: String = "159.0",
    /** `navigator.platform`-like word of `platform_identifier`: "linux", "windows". */
    val os: String = "linux",
    val userAgent: String = "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:159.0) Gecko/20100101 Firefox/159.0",
    val acceptLanguage: String = "en-US,en;q=0.9",
    /** Sec-Fetch-* headers the browser adds to every fetch (measured on every spclient call). */
    val sendFetchMetadata: Boolean = true,
    /**
     * Headers a Chromium browser adds to every secure request on its own: the low-entropy client
     * hints (`sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`). Empty for Firefox (the
     * default identity), which sends none; a platform presenting Chrome (Android, whose HTTP
     * stack is Chromium's) passes the ones its user agent implies, as the relay does.
     */
    val clientHints: Map<String, String> = emptyMap(),
    /** Volume the device registers with, 0..65535 (65535 measured). */
    val initialVolume: Int = 65535,
    /** `strategy` of playback_stats: "MSE" in the browser; a native player may say what it is. */
    val playbackStrategy: String = "MSE",
    /** Preload the next track's media and licence this long before the end; null = as soon as playing. */
    val preloadBeforeEndMs: Long? = null,
    /** Retries after a 429 before the error reaches the caller. */
    val maxRateLimitRetries: Int = 3,
) {
    val deviceName get() = "Web Player ($browserName)"
    val platformIdentifier get() = "web_player $os undefined;${browserName.lowercase()} $browserVersion;desktop"
    val harmonyVersion get() = "harmony:$sdkVersion-$sdkBuild"

    companion object {
        const val ORIGIN = "https://open.spotify.com"
        const val REFERER = "https://open.spotify.com/"
        const val SPCLIENT_WG = "https://spclient.wg.spotify.com"
        const val APRESOLVE = "https://apresolve.spotify.com/?type=dealer-g2&type=spclient"
        const val SEEKTABLES = "https://seektables.spotifycdn.com/v1/seektable/"

        /** Formats the device accepts, in the register body (as measured, duplicate included). */
        val MANIFEST_FORMATS = listOf(
            "file_ids_mp3", "file_urls_mp3", "manifest_urls_audio_ad", "manifest_ids_video",
            "file_urls_external", "file_ids_mp4", "file_ids_mp4_dual", "manifest_urls_audio_ad",
        )
    }
}
