package app.myspoti.extensions

/**
 * Where the store files an extension: a fixed, neutral list defined by the app. An author
 * declares one (`category` in the manifest or the index); the app checks it against the declared
 * actions ([fits]) and files it under [OTHER] when it does not fit or is unknown. An extension
 * that provides downloads is always under [TOOLS]. Free `tags` are shown and searched, never
 * filtered on.
 */
enum class Category(val key: String) {
    LYRICS("lyrics"),
    PLAYBACK("playback"),
    LIBRARY("library"),
    DISCOVERY("discovery"),
    STATS("stats"),
    LOOK("look"),
    SOCIAL("social"),
    TOOLS("tools"),
    OTHER("other");

    /** The actions one of which an extension needs to be filed here (empty: any extension fits). */
    val needs: Set<Action>
        get() = when (this) {
            LYRICS -> setOf(Action.LYRICS_PROVIDE, Action.LYRICS_ROMANIZE, Action.TRACKINFO_PROVIDE)
            PLAYBACK -> setOf(Action.PLAYBACK_CONTROL, Action.PLAYBACK_QUEUE, Action.PLAYBACK_EVENTS, Action.AUDIO_SOURCE, Action.PLAYER_BUTTONS)
            LIBRARY -> setOf(Action.LIBRARY_READ, Action.LIBRARY_WRITE, Action.PLAYLISTS_WRITE)
            DISCOVERY -> setOf(Action.HOME_SECTIONS, Action.SEARCH_PROVIDE, Action.SPOTIFY_CATALOG)
            STATS -> setOf(Action.PLAYBACK_EVENTS, Action.LIBRARY_READ)
            LOOK -> Action.BLOCKS + Action.WEBVIEW_PAGE + Action.UI_STYLE
            SOCIAL -> setOf(Action.NETWORK, Action.LIBRARY_READ, Action.SPOTIFY_CATALOG)
            TOOLS, OTHER -> emptySet()
        }

    /** Whether an extension declaring [actions] may be filed here. */
    fun fits(actions: Set<Action>): Boolean = needs.isEmpty() || needs.any { it in actions }

    companion object {
        /** The keys of the first store (before this list), read as their nearest category. */
        private val OLD = mapOf("audio-source" to PLAYBACK, "player" to PLAYBACK, "spotify" to LIBRARY, "downloads" to TOOLS)

        fun of(key: String): Category? = entries.firstOrNull { it.key == key } ?: OLD[key]

        /**
         * Where an extension declaring [declared] and [actions] is filed: [TOOLS] when it provides
         * downloads, else the declared category when it is known and [fits], else [OTHER].
         */
        fun of(declared: String?, actions: Set<Action>): Category = when {
            Action.DOWNLOAD_SOURCE in actions -> TOOLS
            else -> declared?.let(::of)?.takeIf { it.fits(actions) } ?: OTHER
        }
    }
}

/**
 * Myspoti's version names, compared like semantic versions: `2.0.2` > `2.0.2-beta.4-dev` >
 * `2.0.2-beta.4` > `2.0.1`. Missing parts count as 0 (`2.1` = `2.1.0`).
 */
object AppVersion {
    private val FORMAT = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?$")

    data class Parsed(val core: List<Int>, val pre: List<String>)

    fun parse(s: String): Parsed? {
        val m = FORMAT.matchEntire(s.trim()) ?: return null
        val core = (1..3).map { m.groupValues[it].ifEmpty { "0" }.toIntOrNull() ?: return null }
        val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
        return Parsed(core, pre)
    }

    /** Negative when [a] is older than [b]; an unreadable version counts as the oldest. */
    fun compare(a: String, b: String): Int {
        val x = parse(a) ?: return if (parse(b) == null) 0 else -1
        val y = parse(b) ?: return 1
        for (i in 0..2) x.core[i].compareTo(y.core[i]).let { if (it != 0) return it }
        // A release is newer than its pre-releases.
        if (x.pre.isEmpty() || y.pre.isEmpty()) return y.pre.size.coerceAtMost(1) - x.pre.size.coerceAtMost(1)
        for (i in 0 until minOf(x.pre.size, y.pre.size)) {
            val p = x.pre[i]; val q = y.pre[i]
            val pn = p.toIntOrNull(); val qn = q.toIntOrNull()
            val c = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null -> -1
                qn != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.pre.size.compareTo(y.pre.size)
    }

    /**
     * The last version of Myspoti, which this app continues under the name Orpheon from 1.0.0 on. A
     * minimum from 2.0.0 on names a Myspoti version: Orpheon has everything of [MYSPOTI_LAST], so such
     * a minimum is met when it is [MYSPOTI_LAST] or older (none newer was ever released).
     */
    const val MYSPOTI_LAST = "2.4.0"

    /** Whether [app] is [min] or newer; no minimum, or an app version that cannot be read: yes. */
    fun atLeast(app: String, min: String?): Boolean {
        if (min.isNullOrBlank() || parse(app) == null) return true
        val m = parse(min) ?: return compare(app, min) >= 0
        val orpheon = parse(app)!!.core[0] < 2
        if (orpheon && m.core[0] >= 2) return compare(MYSPOTI_LAST, min) >= 0
        return compare(app, min) >= 0
    }
}

/** The one button of a store row. */
enum class StoreAction { INSTALL, UPDATE, INSTALLED, INCOMPATIBLE }

/** What the store shows, without Android: a row's button, the update count, the search. */
object Store {
    /**
     * The button for [entry] when [installedVersionCode] is on the phone (null: not installed),
     * in a Myspoti named [appVersion]. A version this app cannot run is [StoreAction.INCOMPATIBLE]
     * (as an update too); what is installed and not older stays [StoreAction.INSTALLED].
     */
    fun action(entry: Catalog.Entry, installedVersionCode: Int?, appVersion: String): StoreAction = when {
        installedVersionCode != null && entry.versionCode <= installedVersionCode -> StoreAction.INSTALLED
        !entry.compatible(appVersion) -> StoreAction.INCOMPATIBLE
        installedVersionCode != null -> StoreAction.UPDATE
        else -> StoreAction.INSTALL
    }

    /** The newest entry of each extension across [catalogs] (the first repository wins a tie). */
    fun newest(catalogs: List<Pair<String, Catalog>>): Map<String, Pair<String, Catalog.Entry>> {
        val out = LinkedHashMap<String, Pair<String, Catalog.Entry>>()
        catalogs.forEach { (url, c) -> c.extensions.forEach { e -> if ((out[e.id]?.second?.versionCode ?: Int.MIN_VALUE) < e.versionCode) out[e.id] = url to e } }
        return out
    }

    /** The installed extensions (id → versionCode) a repository has a version of that this app can install. */
    fun updates(catalogs: List<Pair<String, Catalog>>, installed: Map<String, Int>, appVersion: String): Set<String> =
        newest(catalogs).filter { (id, p) -> installed[id]?.let { action(p.second, it, appVersion) == StoreAction.UPDATE } == true }.keys

    /** Whether [entry] is in [category] (null: any) and every word of [query] is in its name, author, description, id or tags. */
    fun matches(entry: Catalog.Entry, query: String, category: Category?): Boolean {
        if (category != null && category != entry.category()) return false
        val hay = listOf(entry.name, entry.author, entry.description, entry.id, entry.tags.joinToString(" ")).joinToString(" ").lowercase()
        return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in hay }
    }
}
