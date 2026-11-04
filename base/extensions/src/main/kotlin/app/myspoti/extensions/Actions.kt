package app.myspoti.extensions

import kotlinx.serialization.Serializable

/**
 * How much an action can cost the user. A critical one is shown in the warning colour, the user
 * ticks "I accept the risk" before it is granted, and the app frames it at run time (destructive
 * Spotify writes are confirmed one by one, the app keeps the last word).
 */
enum class Level { NORMAL, CRITICAL }

/**
 * Where an action acts in the app: the Installed tab is organised by area, each one opening with
 * what Myspoti does there by itself ("Built-in"), then the extensions acting there, in the order
 * the user set ([Registry.priority] is kept per area).
 */
enum class Area(val key: String) {
    PLAYBACK("playback"),
    LYRICS("lyrics"),
    AUDIO("audio"),
    HOME("home"),
    MENUS("menus"),
    LIBRARY("library"),
    /** Free pages, and the extensions that only support others (network, storage, messages). */
    OTHER("other");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/**
 * What an extension can do, as the user sees it: a closed catalogue defined by the app. Each one
 * has a fixed Material icon ([icon], the name of `Icons.Rounded.<icon>`), a short [label], a
 * [level] and the API it unlocks ([api]). An extension lists the ones it needs in its manifest,
 * each with a `why` of 80 characters at most, read by the user before installing.
 *
 * [takeover]: the extension exists for it (it provides something the app draws or asks). The
 * others are reach. An installed extension is granted every action it lists, either kind.
 * [area]: null for the support actions, which never place an extension on the Installed tab.
 */
enum class Action(
    val key: String,
    val level: Level,
    val area: Area?,
    val icon: String,
    val label: String,
    val api: String,
    val takeover: Boolean = false,
) {
    PLAYBACK_CONTROL("playback.control", Level.NORMAL, Area.PLAYBACK, "PlayCircle", "Control playback",
        "myspoti.player.play/pause/next/previous/seek; spotify.player play, pause, resume, seek, next, previous, shuffle, repeat, volume, speed, skipTo; spotify.devices transfer, pullHere"),
    PLAYBACK_QUEUE("playback.queue", Level.NORMAL, Area.PLAYBACK, "QueueMusic", "Edit the queue",
        "spotify.player addToQueue, playNext, moveInQueue, removeFromQueue, clearQueue (removals confirmed)"),
    PLAYBACK_MIRROR("playback.mirror", Level.CRITICAL, Area.PLAYBACK, "VolumeOff", "Mirror Spotify silently",
        "myspoti.spotify.player.setLocalMute(on): keeps Spotify playing unheard on this phone while Myspoti's own player plays, which counts listens on your Spotify account (one extension at a time, cleared on track change, stop or when the extension goes)"),
    PLAYBACK_EVENTS("playback.events", Level.NORMAL, Area.PLAYBACK, "GraphicEq", "Know what you play",
        "myspoti.player.current(); myspoti.on('track' | 'playback'); spotify.player state, queue, volume"),
    LYRICS_PROVIDE("lyrics.provide", Level.NORMAL, Area.LYRICS, "Lyrics", "Provide lyrics",
        "myspoti.provide('lyrics', fn(track) → lyrics)", takeover = true),
    TRACKINFO_PROVIDE("trackinfo.provide", Level.NORMAL, Area.LYRICS, "Info", "Add song details",
        "myspoti.provide('trackinfo', fn(track) → blocks), shown in the song's credits sheet", takeover = true),
    AUDIO_SOURCE("audio.source", Level.CRITICAL, Area.AUDIO, "Headphones", "Play audio from elsewhere",
        "myspoti.provide('audio-source', fn(track) → { url })", takeover = true),
    DOWNLOAD_SOURCE("download.source", Level.CRITICAL, Area.AUDIO, "Download", "Provide downloads",
        "myspoti.provide('download-source', fn(query) → files)", takeover = true),
    HOME_SECTIONS("home.sections", Level.NORMAL, Area.HOME, "Dashboard", "Add home sections",
        "myspoti.provide('home', fn() → sections of blocks)", takeover = true),
    SEARCH_PROVIDE("search.provide", Level.NORMAL, Area.HOME, "ManageSearch", "Add search results",
        "myspoti.provide('search', fn({ query }) → blocks)", takeover = true),
    MENU_ITEMS("menu.items", Level.NORMAL, Area.MENUS, "MoreHoriz", "Add menu entries",
        "myspoti.provide('menu', fn({ kind, uri, title }) → menu blocks) in the ⋯ menus of songs, albums, playlists, artists", takeover = true),
    PLAYER_BUTTONS("player.buttons", Level.NORMAL, Area.MENUS, "SmartButton", "Add player buttons",
        "myspoti.provide('player', fn(track) → button blocks), under the player's own controls", takeover = true),
    SETTINGS_PAGE("settings.page", Level.NORMAL, null, "Tune", "Its own settings",
        "manifest \"settings\"; myspoti.settings.get(key)", takeover = true),
    NOTIFY("notify", Level.NORMAL, null, "Notifications", "Show short messages",
        "myspoti.notify(text)"),
    LIBRARY_READ("library.read", Level.NORMAL, Area.LIBRARY, "LibraryMusic", "Read your library",
        "spotify.library, playlists (yours), users, recents, devices.list, podcasts.savedEpisodes, blends (reads), search.recent, home.whatsNew"),
    LIBRARY_WRITE("library.write", Level.CRITICAL, Area.LIBRARY, "LibraryAdd", "Change your library",
        "spotify.library add/remove/pin/folders; users follow/unfollow/private session; extras taste and bans; episodes saved; search.clearRecent (removals confirmed)"),
    PLAYLISTS_WRITE("playlists.write", Level.CRITICAL, Area.LIBRARY, "PlaylistAdd", "Edit your playlists",
        "spotify.playlists create/rename/describe/cover/access/collaborate/follow/add/move/remove/delete; spotify.blends invite/join/rename/leave (removals confirmed)"),
    SPOTIFY_CATALOG("spotify.catalog", Level.NORMAL, Area.LIBRARY, "Album", "Browse Spotify",
        "spotify.search, catalog, playlists (public), home, podcasts, extras (lyrics, canvas, credits, radio)"),
    SPOTIFY_RAW("spotify.raw", Level.CRITICAL, Area.LIBRARY, "Code", "Full Spotify access",
        "spotify.raw gql/service/get/request: anything the account can do (changes confirmed)"),
    NETWORK("network", Level.NORMAL, null, "Public", "Use the internet",
        "myspoti.http to the hosts listed (\"*\": any host, critical)"),
    WEBVIEW_PAGE("webview.page", Level.CRITICAL, Area.OTHER, "Web", "Its own web page",
        "myspoti.provide('page', fn() → { title, html }) in a sandboxed WebView; myspoti.provide('message', fn(data) → reply)", takeover = true),
    WEBVIEW_AUTH("webview.auth", Level.CRITICAL, Area.OTHER, "Key", "Sign in on a website",
        "myspoti.auth.open({ url, callback }) → myspoti.on('auth'): a full web view with its own cookies, apart from the app's; only the callback address comes back"),
    STORAGE("storage", Level.NORMAL, null, "Save", "Keep its own data",
        "myspoti.storage get/set/remove (512 KB)"),
    UI_STYLE("ui.style", Level.NORMAL, Area.OTHER, "Palette", "Change the look",
        "manifest \"ui\": colours, hidden parts, tab order, text size and Home blocks, applied by the app (never on protected surfaces)", takeover = true),
    ATTEST("attest", Level.NORMAL, null, "Fingerprint", "Prove this is Myspoti",
        "myspoti.attest enroll/publicKey/sign/reset: a signing key of its own in this phone's hardware keystore, so its server can tell its requests come from a genuine Myspoti install");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }

        /** The key the user grants for "network to any host": critical, apart from listed hosts. */
        const val NETWORK_ANY = "network.any"

        /** The actions whose answer is drawn by the app as native blocks. */
        val BLOCKS: Set<Action> = setOf(TRACKINFO_PROVIDE, HOME_SECTIONS, SEARCH_PROVIDE, MENU_ITEMS, PLAYER_BUTTONS)

        /** At most this many characters in a `why`. */
        const val WHY_MAX = 80

        // ── The old manifests (provides + permissions, before actions) ──

        /** An old hook key or permission key → the actions it stands for now. */
        val LEGACY: Map<String, List<Action>> = mapOf(
            "lyrics" to listOf(LYRICS_PROVIDE),
            "audio-source" to listOf(AUDIO_SOURCE),
            "download-source" to listOf(DOWNLOAD_SOURCE),
            "network" to listOf(NETWORK),
            "notify" to listOf(NOTIFY),
            "player.read" to listOf(PLAYBACK_EVENTS),
            "player.control" to listOf(PLAYBACK_CONTROL),
            "spotify.catalog" to listOf(SPOTIFY_CATALOG),
            "spotify.library.read" to listOf(LIBRARY_READ, PLAYBACK_EVENTS),
            "spotify.write" to listOf(LIBRARY_WRITE, PLAYLISTS_WRITE, PLAYBACK_CONTROL, PLAYBACK_QUEUE),
            "spotify.raw" to listOf(SPOTIFY_RAW),
        )

        /**
         * The keys an extension installed before actions was granted, as action keys: each old
         * key becomes its actions; `network.any` and action keys stay; storage and the settings
         * page, which every old extension had, are added.
         */
        fun migrateGranted(granted: Set<String>): Set<String> {
            if (granted.none { it in LEGACY }) return granted
            return buildSet {
                granted.forEach { k -> LEGACY[k]?.forEach { add(it.key) } ?: add(k) }
                add(STORAGE.key)
                add(SETTINGS_PAGE.key)
            }
        }
    }
}

/** One action as a manifest asks it: `{ "name": "lyrics.provide", "why": "…" }`. */
@Serializable
data class ActionRequest(
    /** An [Action] key. */
    val name: String,
    /** Why the extension needs it, 1 to 80 characters, shown next to its icon. */
    val why: String = "",
    /** network only: the hosts it may call ("api.example.com", "*.example.com", or "*"). */
    val hosts: List<String> = emptyList(),
    /**
     * spotify.raw only: the `raw.request` writes it needs ("POST https://host/path"), shown before
     * install and allowed with it, so they never ask while music plays. 8 at most.
     */
    val writes: List<String> = emptyList(),
)

/**
 * The parts of the app no extension can hide, delay, cover, restyle or observe. No action targets
 * them, extension blocks and pages are drawn beneath them and suspended while one is on screen,
 * and no event about them is ever sent to an extension (see [SurfaceGuard]).
 */
enum class ProtectedSurface(val key: String) {
    /** The owner's donation reminder and the Support sheets (Ko-fi, GitHub Sponsors). */
    SUPPORT("support"),
    /** "New version" card. */
    UPDATE("update"),
    /** The install prompt, its warnings and the third-party notice. */
    INSTALL("install"),
    /** A critical action's confirmation (a destructive Spotify change, granting a critical action). */
    CONFIRM("confirm"),
    /** The Report button (beta logs) and what it opens. */
    REPORT("report"),
    /** About: version, credits, source code, legal. */
    ABOUT("about"),
}

/**
 * The places an extension's blocks or page are drawn. None of them is a [ProtectedSurface]
 * (a test checks the two never meet): an action can only reach these.
 */
enum class Surface(val key: String, val action: Action) {
    HOME("home", Action.HOME_SECTIONS),
    SEARCH("search", Action.SEARCH_PROVIDE),
    TRACK_MENU("menu.track", Action.MENU_ITEMS),
    ALBUM_MENU("menu.album", Action.MENU_ITEMS),
    PLAYLIST_MENU("menu.playlist", Action.MENU_ITEMS),
    ARTIST_MENU("menu.artist", Action.MENU_ITEMS),
    PLAYER("player", Action.PLAYER_BUTTONS),
    TRACK_INFO("trackinfo", Action.TRACKINFO_PROVIDE),
    PAGE("page", Action.WEBVIEW_PAGE),
}

/**
 * Which protected surfaces are on screen now. While any is, extension blocks and pages are not
 * asked or drawn ([ExtensionRuntime.call] answers nothing for a block hook, taps and page messages
 * are dropped) and the app hides their views. The app reports each protected surface as it shows
 * and goes; nothing about them is forwarded to an extension.
 */
class SurfaceGuard {
    private val showing = java.util.concurrent.ConcurrentHashMap<ProtectedSurface, Int>()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<Set<ProtectedSurface>>(emptySet())
    val state: kotlinx.coroutines.flow.StateFlow<Set<ProtectedSurface>> get() = _state

    fun shown(s: ProtectedSurface) { showing.merge(s, 1) { a, b -> a + b }; _state.value = showing.keys.toSet() }

    fun hidden(s: ProtectedSurface) {
        showing.computeIfPresent(s) { _, n -> if (n <= 1) null else n - 1 }
        _state.value = showing.keys.toSet()
    }

    /** True while a protected surface is on screen: extension UI waits. */
    val suspended: Boolean get() = showing.isNotEmpty()

    companion object {
        /** The app's own guard. */
        val app = SurfaceGuard()
    }
}
