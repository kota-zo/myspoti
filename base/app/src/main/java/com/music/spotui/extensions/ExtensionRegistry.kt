package com.music.spotui.extensions

import app.myspoti.extensions.Action
import app.myspoti.extensions.Area
import app.myspoti.extensions.Ask
import app.myspoti.extensions.Change
import app.myspoti.extensions.Clash
import app.myspoti.extensions.Hook
import app.myspoti.extensions.Level
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Origin
import app.myspoti.extensions.Snapshot
import app.myspoti.extensions.Storage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

// The parts of the extension manager that do not need Android: what is installed and granted,
// what an update asks again, the extensions' stores and logs. Unit-tested on the JVM.

internal val RegistryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** One installed extension, as the registry keeps it. */
@Serializable
data class InstalledExtension(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    /** Of the package kept on the phone: a package changed behind the app's back does not run. */
    val sha256: String,
    /** The [Ask.key]s the user accepted: what the sandbox lets this extension do. */
    val granted: Set<String>,
    val enabled: Boolean = true,
    /** Why the app turned it off (it crashed, a repository blocked it), shown on its page. */
    val disabledReason: String? = null,
    val installedAt: Long = 0,
    /** Where it came from: "file", the https address of the package, or a repository's index. */
    val source: String = "file",
    /** Installed from GitHub: the repository, tag or branch and commit. Updates come from there. */
    val origin: Origin? = null,
    /**
     * What the user accepted (at install, or with Accept on an update card): every update is
     * weighed against it ([UpdateGate]). Null for an extension installed before snapshots: its
     * running manifest is taken as accepted the first time an update is checked.
     */
    val accepted: Snapshot? = null,
    /** The version before the running one, kept on disk (`previous.zip`): one tap reverts to it. */
    val previous: KeptVersion? = null,
    /** A newer version waiting for the user's OK (`staging.zip`): it asks [PendingUpdate.changes]. */
    val pendingUpdate: PendingUpdate? = null,
    /** The version the user answered "Keep current" to: not offered again, a newer one is. */
    val declinedVersionCode: Int = 0,
    /** The package ("Not now") declined, for a code-only update that keeps the same version code. */
    val declinedSha: String? = null,
    /** The package of a harmless update first seen at [seenAt]: auto-applied once a day has passed ([UpdateGate.DELAY_MS]). */
    val seenSha: String? = null,
    val seenAt: Long = 0,
    /** Why updates from its repository are refused (the host's ids changed: re-registered or transferred), shown as a warning. */
    val updateBlocked: String? = null,
    /** When its source was last checked for an update (at most once a day, apart from the button). */
    val lastCheck: Long = 0,
    /** Its repository answered 404: it keeps running from the copy on the phone, marked "source gone". */
    val sourceGone: Boolean = false,
    /** The `raw.request` writes the user allowed for good, listed with its rights and revocable there. */
    val rawGrants: List<app.myspoti.extensions.RawGrant> = emptyList(),
)

/** A version kept on disk to revert to, or waiting in staging. */
@Serializable
data class KeptVersion(val version: String, val versionCode: Int, val sha256: String, val origin: Origin? = null)

/** Spotify's place in the audio sources' order: the extensions after it are not asked. */
const val SPOTIFY_SOURCE = "spotify"

/** A newer version that asks something the user has not accepted; the old one keeps running. */
@Serializable
data class PendingUpdate(val kept: KeptVersion, val changes: List<Change>, val changelog: String = "")

@Serializable
data class Registry(
    /** In installation order (the order hooks ask them in until the user sets a [priority]). */
    val extensions: List<InstalledExtension> = emptyList(),
    /** Repositories (index.json addresses) the user added, at their own risk. */
    val repositories: List<String> = emptyList(),
    /**
     * Per [Area] key, the ids in the order the user set on the Installed tab: asked first, first
     * answer wins (lyrics: the best answer, ties to the higher one; blocks: shown first; playback
     * commands: the higher wins a contradiction). An extension not listed (installed since) comes
     * after, in installation order. Before areas, keys were hook keys: [migrated] maps them.
     */
    val priority: Map<String, List<String>> = emptyMap(),
    /** Per exclusive action key (audio.source, download.source), the extension the user made active. */
    val active: Map<String, String> = emptyMap(),
    /** The extensions whose third-party notice the user accepted (once per extension, kept after uninstall). */
    val noticeAccepted: Set<String> = emptySet(),
) {
    fun find(id: String) = extensions.firstOrNull { it.id == id }

    /** [items] (in installation order) in the order [area] asks them: see [priority]. */
    fun <T> byPriority(area: String, items: List<T>, id: (T) -> String): List<T> {
        val order = priority[area].orEmpty()
        return items.sortedBy { order.indexOf(id(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /**
     * [items] in the order the source [action] (an [app.myspoti.extensions.Mode.ORDERED] action key)
     * asks them: the user's order for it, else the one made active when sources were exclusive
     * first, then [area]'s order.
     */
    fun <T> bySourceOrder(action: String, area: String, items: List<T>, id: (T) -> String): List<T> {
        val first = active[action]
        val seeded = byPriority(area, items, id).sortedBy { if (id(it) == first) 0 else 1 }
        return byPriority(action, seeded, id)
    }

    /**
     * [id] moved by [delta] places (negative: higher) among [providers], the ids of the
     * extensions providing [hook] in installation order; the whole order is then kept.
     */
    fun move(area: String, providers: List<String>, id: String, delta: Int): Registry {
        val now = byPriority(area, providers) { it }.toMutableList()
        val from = now.indexOf(id)
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, now.lastIndex)
        if (to == from) return this
        now.add(to, now.removeAt(from))
        return copy(priority = priority + (area to now))
    }

    /**
     * A registry written before actions, as one now: granted hook and permission keys become
     * action keys ([Action.migrateGranted]), priorities per hook become priorities per area.
     */
    fun migrated(): Registry {
        val byArea = LinkedHashMap<String, List<String>>()
        priority.forEach { (key, ids) ->
            val area = Hook.ofLegacy(key)?.action?.area?.key ?: key
            byArea[area] = (byArea[area].orEmpty() + ids).distinct()
        }
        return copy(extensions = extensions.map { it.copy(granted = Action.migrateGranted(it.granted)) }, priority = byArea)
    }

    /** [e] in place of the extension with its id (kept at its position), or added at the end. */
    fun put(e: InstalledExtension): Registry {
        val i = extensions.indexOfFirst { it.id == e.id }
        return copy(extensions = if (i < 0) extensions + e else extensions.toMutableList().also { it[i] = e })
    }

    fun remove(id: String) = copy(
        extensions = extensions.filterNot { it.id == id },
        priority = priority.mapValues { (_, ids) -> ids - id }.filterValues { it.isNotEmpty() },
        active = active.filterValues { it != id },
    )

    companion object {
        fun read(file: File): Registry = runCatching { RegistryJson.decodeFromString(serializer(), file.readText()).migrated() }.getOrDefault(Registry())

        fun write(file: File, r: Registry) = writeAtomically(file, RegistryJson.encodeToString(serializer(), r))
    }
}

/** Where an extension stands on the Installed tab: the areas its actions act in (support actions alone: [Area.OTHER]). */
object Areas {
    fun of(m: Manifest): Set<Area> = m.actionSet().mapNotNull { it.area }.toSet().ifEmpty { setOf(Area.OTHER) }

    /**
     * Per area (in [Area] order), the installed extensions acting there in the user's order;
     * an area no extension acts in is still there (its Built-in row).
     */
    fun group(r: Registry, manifestOf: (String) -> Manifest?): Map<Area, List<InstalledExtension>> =
        Area.entries.associateWith { area -> r.byPriority(area.key, r.extensions.filter { e -> manifestOf(e.id)?.let { area in of(it) } == true }) { it.id } }
}

/** How the user settled a [Clash] when installing or turning on an extension. */
object Clashes {
    /**
     * The registry after the new extension [id] is installed, each clash resolved by [keepNew]
     * (true: the new one acts, the other stays installed: idle for an exclusive action, turned off
     * for a conflict or a replacement). Returns it and whether the new one starts turned off.
     */
    fun apply(r: Registry, id: String, clashes: List<Clash>, keepNew: (Clash) -> Boolean): Pair<Registry, Boolean> {
        var out = r
        var newOff = false
        clashes.forEach { c ->
            val keep = keepNew(c)
            when (c.kind) {
                Clash.Kind.EXCLUSIVE -> out = out.copy(active = out.active + (c.action!!.key to if (keep) id else c.otherId))
                Clash.Kind.CONFLICT, Clash.Kind.REPLACES ->
                    if (keep) out.find(c.otherId)?.let { o -> out = out.put(o.copy(enabled = false, disabledReason = "replaced by $id")) }
                    else newOff = true
            }
        }
        return out to newOff
    }
}

/**
 * What installing grants: every action the extension lists. There is nothing to untick: to refuse
 * an action, the user does not install the extension (or uninstalls it).
 */
object Grants {
    fun keys(m: Manifest): Set<String> = m.asks().map { it.key }.toSet()

    /**
     * The actions [m] lists that [e] was not granted: left from the time the install prompt let
     * the user untick some. While there are any, it does not run; the user accepts them once on
     * its page.
     */
    fun refused(e: InstalledExtension, m: Manifest): Set<String> = keys(m) - e.granted

    /** What [m] is granted when it replaces a version: all it lists but the [refused] ones still waiting for the user. */
    fun replacing(m: Manifest, refused: Set<String>): Set<String> = keys(m) - refused
}

/** What happens when a package is offered for an extension that may already be installed. */
sealed interface InstallPlan {
    val manifest: Manifest

    /** Not installed: every ask is shown. */
    data class Fresh(override val manifest: Manifest) : InstallPlan

    /**
     * A newer version. [newAsks]: the rights it asks that the installed one did not (or asks
     * wider: more hosts, any host). [refused]: the ones it lists that the user had unticked
     * before installs granted everything. The prompt lists the actions again when there is
     * either; installing grants all it lists.
     */
    data class Update(
        override val manifest: Manifest,
        val from: InstalledExtension,
        val newAsks: List<Ask>,
        val refused: Set<String>,
    ) : InstallPlan {
        val reAsk: Boolean get() = newAsks.isNotEmpty() || refused.isNotEmpty()
    }

    /** The same version, or an older one, is installed. */
    data class NotNewer(override val manifest: Manifest, val installed: InstalledExtension) : InstallPlan

    companion object {
        /**
         * [installed] and [installedManifest]: the extension with [manifest]'s id on the phone,
         * if any, and the manifest of its package.
         */
        fun of(manifest: Manifest, installed: InstalledExtension?, installedManifest: Manifest?): InstallPlan {
            if (installed == null) return Fresh(manifest)
            if (manifest.versionCode <= installed.versionCode) return NotNewer(manifest, installed)
            val old = installedManifest?.asks().orEmpty()
            val newAsks = manifest.asks().filter { a -> widens(a, old.firstOrNull { it.key == a.key }) }
            val refused = (old.map { it.key }.toSet() - installed.granted) intersect Grants.keys(manifest)
            return Update(manifest, installed, newAsks, refused)
        }

        /** Whether [now] asks more than [before] did: new, now critical, more hosts, or another reason. */
        internal fun widens(now: Ask, before: Ask?): Boolean = before == null ||
            (now.level == Level.CRITICAL && before.level != Level.CRITICAL) ||
            !before.hosts.containsAll(now.hosts) || now.why.trim() != before.why.trim()
    }
}

/**
 * A small JSON map in a file (key → JSON text): an extension's `myspoti.storage`, and its
 * settings. Kept in memory, written through on every change.
 */
class FileStore(private val file: File) : Storage {
    private val values: MutableMap<String, String> by lazy {
        runCatching { RegistryJson.decodeFromString(MAP, file.readText()).toMutableMap() }.getOrElse { LinkedHashMap() }
    }

    @Synchronized override fun get(key: String): String? = values[key]
    @Synchronized override fun set(key: String, json: String) { values[key] = json; save() }
    @Synchronized override fun remove(key: String) { if (values.remove(key) != null) save() }
    @Synchronized override fun size(): Int = values.entries.sumOf { it.key.length + it.value.length }
    @Synchronized fun all(): Map<String, String> = values.toMap()

    private fun save() = writeAtomically(file, RegistryJson.encodeToString(MAP, values))

    companion object {
        private val MAP = MapSerializer(String.serializer(), String.serializer())
    }
}

/** An extension's log: the last lines in memory, and on disk (cut to [maxChars]) for its page. */
class ExtensionLog(private val file: File, private val maxLines: Int = 300, private val maxChars: Int = 64 * 1024) {
    private val lines: ArrayDeque<String> by lazy {
        ArrayDeque(runCatching { file.readLines() }.getOrDefault(emptyList()).takeLast(maxLines))
    }

    @Synchronized fun add(line: String) {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > maxChars) writeAtomically(file, lines.joinToString("\n", postfix = "\n"))
            else file.appendText(line + "\n")
        }
    }

    @Synchronized fun lines(): List<String> = lines.toList()

    @Synchronized fun clear() { lines.clear(); file.delete() }
}

/** Counts each extension's failures in a row; at [limit] the app turns it off. */
class FailureCounter(private val limit: Int = 3) {
    private val inARow = HashMap<String, Int>()

    /** A failure of [id]: true when it is one too many and the extension is to be turned off. */
    @Synchronized fun failed(id: String): Boolean {
        val n = (inARow[id] ?: 0) + 1
        inARow[id] = n
        return n >= limit
    }

    @Synchronized fun succeeded(id: String) { inARow.remove(id) }

    @Synchronized fun reset(id: String) { inARow.remove(id) }
}

/**
 * Replaces [file] whole (see [com.music.spotui.data.writeTextSafely]); the old file is never deleted to
 * make room. A write that fails throws, as before; a move that fails keeps the old file and is only logged.
 */
internal fun writeAtomically(file: File, text: String) {
    try {
        com.music.spotui.data.writeTextSafely(file, text, sync = false)
    } catch (e: com.music.spotui.data.NotReplacedException) {
        runCatching { app.myspoti.host.android.Logbook.log("ext: ${e.message}, the old copy kept") }
    }
}
