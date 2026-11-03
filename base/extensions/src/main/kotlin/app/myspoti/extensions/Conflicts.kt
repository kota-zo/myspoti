package app.myspoti.extensions

/**
 * How extensions doing the same thing live together.
 *
 * [Mode.ADDITIVE]: every extension's answer is used, merged and de-duplicated in the area's
 * priority order (lyrics: the best one; blocks: all, quotas per extension). [Mode.EXCLUSIVE]: one
 * active extension per action (the user picks; the others stay installed and idle).
 * [Mode.ORDERED]: each is asked in the user's order for that action until one answers (audio and
 * download sources: Tidal first, then Qobuz, then Spotify). [Mode.SHARED]: reach (network,
 * storage…), nothing to share out.
 */
enum class Mode { ADDITIVE, EXCLUSIVE, ORDERED, SHARED }

val Action.mode: Mode
    get() = when (this) {
        Action.AUDIO_SOURCE, Action.DOWNLOAD_SOURCE -> Mode.ORDERED
        Action.LYRICS_PROVIDE, Action.LYRICS_ROMANIZE, Action.TRACKINFO_PROVIDE, Action.HOME_SECTIONS, Action.SEARCH_PROVIDE,
        Action.MENU_ITEMS, Action.PLAYER_BUTTONS -> Mode.ADDITIVE
        else -> Mode.SHARED
    }

/** What installing or turning on an extension would collide with, so the user picks. */
data class Clash(val kind: Kind, val otherId: String, val action: Action? = null) {
    enum class Kind {
        /** It provides an exclusive action [otherId] provides now: "replaces X currently provided by Y". */
        EXCLUSIVE,
        /** One of the two lists the other in `conflictsWith`: "incompatible with X (installed)". */
        CONFLICT,
        /** It lists [otherId] in `replaces`. */
        REPLACES,
    }
}

object Conflicts {
    /** The one extension (of [providers], in priority order) acting for an exclusive action: the user's [chosen], else the first. */
    fun activeFor(providers: List<String>, chosen: String?): String? = chosen?.takeIf { it in providers } ?: providers.firstOrNull()

    /**
     * What [m] collides with among [installed] (the enabled extensions, in priority order, with
     * their manifests; [m]'s own id is skipped). [active]: the user's choice per exclusive action key.
     */
    fun clashes(m: Manifest, installed: List<Manifest>, active: Map<String, String>): List<Clash> {
        val others = installed.filter { it.id != m.id }
        val out = LinkedHashMap<String, Clash>()
        m.actionSet().filter { it.mode == Mode.EXCLUSIVE }.forEach { a ->
            activeFor(others.filter { a in it.actionSet() }.map { it.id }, active[a.key])
                ?.let { out["x:${a.key}"] = Clash(Clash.Kind.EXCLUSIVE, it, a) }
        }
        others.forEach { o ->
            when {
                o.id in m.replaces -> out["r:${o.id}"] = Clash(Clash.Kind.REPLACES, o.id)
                o.id in m.conflictsWith || m.id in o.conflictsWith -> out["c:${o.id}"] = Clash(Clash.Kind.CONFLICT, o.id)
            }
        }
        return out.values.toList()
    }

    /**
     * Additive blocks of several extensions (in priority order) for one place: an item, card or
     * entry already given by a higher one is dropped (same uri, or same label), then each
     * extension keeps [Quotas.perExtension] of its blocks on screen, and the place [Quotas.total];
     * what is left goes to [Shown.overflow] (the "Extensions ⋯" entry), except on Home, where it is
     * dropped. Never mixes two extensions' blocks: each block stays with its owner.
     */
    fun <K> merge(perExtension: List<Pair<K, List<Block>>>, surface: Surface): Shown<K> {
        val seenUris = HashSet<String>()
        val seenLabels = HashSet<String>()
        val shown = ArrayList<Pair<K, Block>>()
        val overflow = ArrayList<Pair<K, Block>>()
        val total = Quotas.total(surface)
        val each = Quotas.perExtension(surface)
        perExtension.forEach { (owner, blocks) ->
            var mine = 0
            dedupe(blocks, seenUris, seenLabels).forEach { b ->
                if (mine < each && shown.size < total) { shown += owner to b; mine++ } else if (Quotas.overflows(surface)) overflow += owner to b
            }
        }
        return Shown(shown, overflow)
    }

    data class Shown<K>(val shown: List<Pair<K, Block>>, val overflow: List<Pair<K, Block>>)

    private fun dedupe(blocks: List<Block>, uris: MutableSet<String>, labels: MutableSet<String>): List<Block> = blocks.mapNotNull { b ->
        when (b) {
            is Block.Items -> b.copy(items = b.items.filter { uris.add(it.uri) }).takeIf { it.items.isNotEmpty() }
            is Block.Card -> b.takeIf { c -> c.target?.open?.let(uris::add) ?: true }
            is Block.MenuEntry -> b.takeIf { labels.add("m:" + it.label.lowercase()) }
            is Block.Button -> b.takeIf { labels.add("b:" + it.label.lowercase()) }
            is Block.Section -> b.copy(blocks = dedupe(b.blocks, uris, labels)).takeIf { it.blocks.isNotEmpty() }
            else -> b
        }
    }
}

/** How much of a place one extension, and all of them, may take. */
object Quotas {
    fun perExtension(surface: Surface): Int = when (surface) {
        Surface.TRACK_MENU, Surface.ALBUM_MENU, Surface.PLAYLIST_MENU, Surface.ARTIST_MENU -> 2
        Surface.PLAYER -> 1
        Surface.HOME -> 2
        Surface.SEARCH, Surface.TRACK_INFO -> 4
        Surface.PAGE -> 0
    }

    fun total(surface: Surface): Int = when (surface) {
        Surface.TRACK_MENU, Surface.ALBUM_MENU, Surface.PLAYLIST_MENU, Surface.ARTIST_MENU -> 4
        Surface.PLAYER -> 3
        Surface.HOME -> 6
        Surface.SEARCH, Surface.TRACK_INFO -> 8
        Surface.PAGE -> 0
    }

    /** Menus and the player gather the rest under "Extensions ⋯"; other places drop it. */
    fun overflows(surface: Surface): Boolean = surface == Surface.PLAYER || surface.action == Action.MENU_ITEMS
}

/**
 * Every playback command an extension sends goes through here before the player.
 *
 * - Two extensions' commands of the same group (play/pause, next/previous/skip, seek, shuffle/repeat)
 *   within [windowMs]: the higher priority one wins (a lower one arriving after it is dropped; a
 *   higher one arriving after a lower one runs and overrides it).
 * - The same two extensions contradicting each other [loopCount] times within [loopMs] (A skips,
 *   B goes back, again and again): the lower priority one is suspended ([Verdict.suspend]); the app
 *   says so with a button to turn it off.
 * - At most [perMinute] commands per extension a minute.
 *
 * [priority]: an extension's rank in the Playback area (0 is first).
 */
class PlaybackArbiter(
    private val priority: (String) -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 2_000,
    private val loopMs: Long = 30_000,
    private val loopCount: Int = 3,
    private val perMinute: Int = 20,
) {
    data class Verdict(val allowed: Boolean, val reason: String? = null, val winner: String? = null, val suspend: String? = null)

    private data class Sent(val id: String, val group: String, val at: Long)

    private val recent = ArrayDeque<Sent>()
    private val counts = HashMap<String, ArrayDeque<Long>>()
    private val clashes = HashMap<Pair<String, String>, ArrayDeque<Long>>()
    private val suspended = HashSet<String>()

    fun isSuspended(id: String) = synchronized(this) { id in suspended }

    /** Lets [id] send commands again (turned back on by the user). */
    fun resume(id: String) = synchronized(this) { suspended -= id; clashes.keys.removeAll { id in it.toList() } }

    @Synchronized
    fun submit(id: String, command: String): Verdict {
        val now = clock()
        if (id in suspended) return Verdict(false, "suspended")
        val times = counts.getOrPut(id) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
        if (times.size >= perMinute) return Verdict(false, "rate")
        times.addLast(now)
        while (recent.isNotEmpty() && now - recent.first().at > windowMs) recent.removeFirst()
        val group = groupOf(command)
        val rival = recent.lastOrNull { it.id != id && it.group == group }
        var verdict = Verdict(true)
        if (rival != null) {
            val pair = if (id < rival.id) id to rival.id else rival.id to id
            val log = clashes.getOrPut(pair) { ArrayDeque() }
            while (log.isNotEmpty() && now - log.first() > loopMs) log.removeFirst()
            log.addLast(now)
            val mineFirst = priority(id) < priority(rival.id) || (priority(id) == priority(rival.id) && id < rival.id)
            val lower = if (mineFirst) rival.id else id
            verdict = if (mineFirst) Verdict(true, "overrides", winner = id) else Verdict(false, "overridden", winner = rival.id)
            if (log.size >= loopCount) {
                suspended += lower
                log.clear()
                verdict = verdict.copy(suspend = lower)
            }
        }
        if (verdict.allowed) recent.addLast(Sent(id, group, now))
        return verdict
    }

    companion object {
        /** The commands that are arbitrated, and the group each one competes in. */
        fun groupOf(command: String): String = when (command) {
            "play", "pause", "resume" -> "transport"
            "next", "previous", "skipTo", "playContext" -> "track"
            "seek" -> "position"
            "setShuffle", "setRepeat", "shuffle", "repeat" -> "mode"
            else -> command
        }
    }
}

/** "Who did what", per area, for the Installed tab: the last [max] things extensions did. */
class ActivityLog(private val max: Int = 200, private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val at: Long, val area: Area, val extensionId: String, val text: String)

    private val entries = ArrayDeque<Entry>()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<List<Entry>>(emptyList())
    val state: kotlinx.coroutines.flow.StateFlow<List<Entry>> get() = _state

    @Synchronized fun add(area: Area, extensionId: String, text: String) {
        entries.addLast(Entry(clock(), area, extensionId, text.take(120)))
        while (entries.size > max) entries.removeFirst()
        _state.value = entries.toList()
    }

    /** The newest first. */
    @Synchronized fun forArea(area: Area, limit: Int = 5): List<Entry> = entries.filter { it.area == area }.takeLast(limit).asReversed()
}
