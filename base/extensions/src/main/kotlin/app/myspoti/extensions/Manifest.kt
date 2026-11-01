package app.myspoti.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The version of the extension API this build of Myspoti speaks. An extension asks for one. */
const val API_VERSION = 1

internal val ExtJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

/**
 * `manifest.json`, at the root of an extension's package: who made it and what it does in the
 * app ([actions], from the app's closed catalogue). Everything the user is asked before
 * installing comes from here, and the sandbox enforces exactly this, nothing more.
 */
@Serializable
data class Manifest(
    /** Reverse-domain, lower case: `io.github.someone.lyrics`. Never changes between versions. */
    val id: String,
    val name: String,
    /** Shown to people: "1.2.0". */
    val version: String,
    /** Compared between versions: higher is newer. */
    val versionCode: Int = 1,
    val apiVersion: Int = API_VERSION,
    val author: String,
    val description: String = "",
    val homepage: String? = null,
    /** The script that runs: one file, in the package. */
    val main: String = "main.js",
    /**
     * What the extension does, from the app's catalogue ([Action]), each with its reason:
     * `[{ "name": "lyrics.provide", "why": "Synced lyrics from LRCLIB" }, { "name": "network", "hosts": ["lrclib.net"], "why": "…" }]`.
     */
    val actions: List<ActionRequest> = emptyList(),
    /**
     * `media`: the hosts its audio and download addresses (and block images) may point at, apart
     * from its `network` hosts: `{ "hosts": ["*.audio.example.com"] }`. Shown in the install prompt.
     * Absent (an older extension): audio and download addresses may name any public host, as before.
     */
    val media: Media? = null,
    /** Old format (before [actions]): the hooks it takes over. Still read, mapped to actions. */
    val provides: List<String> = emptyList(),
    /** Old format: what it reaches. Still read, mapped to actions. */
    val permissions: List<PermissionRequest> = emptyList(),
    /** Options the user sets on the extension's page, read with `myspoti.settings.get(key)`. */
    val settings: List<Setting> = emptyList(),
    /**
     * A PNG at the root of the package shown as the extension's icon (square, 256 px or less).
     * Absent: `icon.png` when the package has one, else a tinted letter.
     */
    val icon: String? = null,
    /** A [Category] key, to file it in the store; checked against [actions], else "other" ([Category.of]). */
    val category: String? = null,
    /** Words the store's search also finds: 8 at most, lower case. */
    val tags: List<String> = emptyList(),
    /**
     * The oldest Myspoti it works on ("2.0.2", compared as [AppVersion]); an older app shows
     * "Requires Myspoti 2.0.2 or newer" and does not install it.
     */
    val minAppVersion: String? = null,
    /** What changed in this version, in a few lines: shown on the store page and before an update. */
    val changelog: String = "",
    /** What the extension does and what is left to do, shown once right after it is first installed. */
    val welcome: Welcome? = null,
    /** Extensions (ids) this one takes the place of: installing it offers to turn them off. */
    val replaces: List<String> = emptyList(),
    /** Extensions (ids) that must not run with this one: the user is asked which to keep. */
    val conflictsWith: List<String> = emptyList(),
) {
    /** True for a manifest written before actions (`provides` and `permissions`): still loaded, mapped to actions. */
    val isLegacy: Boolean get() = actions.isEmpty() && (provides.isNotEmpty() || permissions.isNotEmpty())

    /**
     * The actions this extension asks, in the manifest's order. An old manifest's hooks and
     * permissions are mapped ([Action.LEGACY]), its reasons kept (cut to 80 characters); storage,
     * which every old extension had, is added, and the settings page when it has settings.
     */
    fun declared(): List<ActionRequest> {
        if (!isLegacy) return actions
        val out = LinkedHashMap<String, ActionRequest>()
        fun put(a: Action, why: String, hosts: List<String> = emptyList()) {
            val before = out[a.key]
            out[a.key] = ActionRequest(a.key, (before?.why?.ifBlank { null } ?: why).take(Action.WHY_MAX), (before?.hosts.orEmpty() + hosts).distinct())
        }
        provides.forEach { h -> Action.LEGACY[h]?.forEach { put(it, why(h)) } }
        permissions.forEach { p -> Action.LEGACY[p.name]?.takeIf { Hook.ofLegacy(p.name) == null }?.forEach { put(it, p.why, if (it == Action.NETWORK) p.hosts else emptyList()) } }
        if (settings.isNotEmpty()) put(Action.SETTINGS_PAGE, "")
        put(Action.STORAGE, "")
        return out.values.toList()
    }

    /** The [Action]s declared that this Myspoti knows. */
    fun actionSet(): Set<Action> = declared().mapNotNull { Action.of(it.name) }.toSet()

    fun declares(a: Action): Boolean = declared().any { it.name == a.key }

    /**
     * The writes `spotify.raw` lists, as the grants they become at install ([RawRequest.offer]):
     * the same kind of request on another song or playlist is covered, nothing else.
     */
    fun declaredWrites(): List<RawGrant> = declared().firstOrNull { it.name == Action.SPOTIFY_RAW.key }?.writes.orEmpty()
        .mapNotNull { w -> runCatching { w.trim().split(Regex("\\s+"), 2).let { (m, u) -> RawRequest.offer(RawRequest.check(m, u), u) } }.getOrNull() }
        .distinct()

    /** The hosts `media` lists, or null when the manifest has no `media` (an older one: see [media]). */
    fun mediaHosts(): List<String>? = media?.hosts

    /** The hosts `network` lists. */
    fun hosts(): List<String> = declared().firstOrNull { it.name == Action.NETWORK.key }?.hosts.orEmpty()

    /** What the user is asked to accept, in the manifest's order; network to any host is `network.any`, critical. */
    fun asks(): List<Ask> = declared().mapNotNull { r ->
        val a = Action.of(r.name) ?: return@mapNotNull null
        if (a == Action.NETWORK && r.hosts.any { it == "*" }) Ask(Action.NETWORK_ANY, Level.CRITICAL, r.why, r.hosts, a)
        else Ask(a.key, a.level, r.why, r.hosts + (if (a == Action.AUDIO_SOURCE || a == Action.DOWNLOAD_SOURCE) media?.hosts.orEmpty() else emptyList()) + if (a == Action.SPOTIFY_RAW) declaredWrites().map { it.toString() } else emptyList(), a)
    }

    /** True when the user is shown the warning and has to tick that they accept the risk. */
    fun isCritical(): Boolean = asks().any { it.level == Level.CRITICAL }

    /** The justification the author gives for an old hook, written as a permission named after it. */
    private fun why(key: String) = permissions.firstOrNull { it.name == key }?.why.orEmpty()

    /** Every reason this manifest cannot be installed, for the author (empty: it can). */
    fun problems(): List<String> = buildList {
        if (!ID.matches(id)) add("id \"$id\": lower case, reverse-domain, at least two parts (io.github.you.name)")
        if (name.isBlank() || name.length > 40) add("name: 1 to 40 characters")
        if (version.isBlank() || version.length > 20) add("version: 1 to 20 characters")
        if (versionCode < 1) add("versionCode: 1 or more")
        if (apiVersion < 1 || apiVersion > API_VERSION) add("apiVersion $apiVersion: this Myspoti speaks $API_VERSION")
        if (author.isBlank() || author.length > 60) add("author: 1 to 60 characters")
        if (description.length > 500) add("description: 500 characters at most")
        if (!main.endsWith(".js") || main.contains("/") || main.contains("\\")) add("main \"$main\": a .js file at the root of the package")
        if (actions.isNotEmpty() && (provides.isNotEmpty() || permissions.isNotEmpty()))
            add("actions: list everything there; provides and permissions are the old format and cannot be mixed with it")
        if (isLegacy) legacyProblems() else actionProblems()
        settings.forEach { s ->
            if (!KEY.matches(s.key)) add("setting \"${s.key}\": letters, digits and _ only")
            if (s.type !in Setting.TYPES) add("setting \"${s.key}\": type ${s.type} unknown (${Setting.TYPES.joinToString()})")
            if (s.type == "choice" && s.options.isEmpty()) add("setting \"${s.key}\": a choice needs options")
            if (s.type == "button" && s.default != null) add("setting \"${s.key}\": a button has no default")
            if (s.label.isBlank()) add("setting \"${s.key}\": a label")
        }
        if (settings.size != settings.map { it.key }.toSet().size) add("settings: a key listed twice")
        icon?.let { if (!it.endsWith(".png") || it.contains("/") || it.contains("\\") || it == main) add("icon \"$it\": a .png file at the root of the package") }
        if (tags.size > 8) add("tags: 8 at most")
        tags.filterNot { TAG.matches(it) }.forEach { add("tag \"$it\": 1 to 24 characters, lower case letters, digits, spaces and -") }
        minAppVersion?.let { if (AppVersion.parse(it) == null) add("minAppVersion \"$it\": a version like 2.0.2 or 2.0.2-beta.4") }
        if (changelog.length > 2000) add("changelog: 2000 characters at most")
        welcome?.let { w ->
            if (w.message.isBlank() && w.steps.isEmpty()) add("welcome: a message or steps")
            if (w.message.length > 600) add("welcome.message: 600 characters at most")
            if (w.steps.size > 5) add("welcome.steps: 5 at most")
            if (w.steps.any { it.isBlank() || it.length > 160 }) add("welcome.steps: each 1 to 160 characters")
        }
        for ((field, ids) in listOf("replaces" to replaces, "conflictsWith" to conflictsWith)) {
            if (ids.size > 10) add("$field: 10 ids at most")
            ids.filterNot { ID.matches(it) }.forEach { add("$field \"$it\": an extension id (io.github.you.name)") }
            if (id in ids) add("$field: not its own id")
        }
    }

    /** The rules of `actions`: known names, once each, a why of 1 to 80 characters each, network's hosts. */
    private fun MutableList<String>.actionProblems() {
        if (actions.isEmpty()) add("actions: at least one (known: ${Action.entries.joinToString { it.key }})")
        actions.forEach { r ->
            val a = Action.of(r.name)
            when {
                a == null -> add("action \"${r.name}\": unknown (known: ${Action.entries.joinToString { it.key }})")
                r.why.isBlank() -> add("action \"${r.name}\": say why the extension needs it in \"why\" (1 to ${Action.WHY_MAX} characters), shown next to its icon before install")
                r.why.length > Action.WHY_MAX -> add("action \"${r.name}\": \"why\" is ${r.why.length} characters, ${Action.WHY_MAX} at most")
            }
            if (a == Action.NETWORK) {
                if (r.hosts.isEmpty()) add("network: list the hosts it calls (\"api.example.com\", \"*.example.com\", or \"*\" for any)")
                r.hosts.filterNot { HOST.matches(it) }.forEach { add("network host \"$it\": a host name, *.domain, or *") }
            } else if (a != null && r.hosts.isNotEmpty()) add("action \"${r.name}\": only network takes hosts")
            if (a != null && a != Action.SPOTIFY_RAW && r.writes.isNotEmpty()) add("action \"${r.name}\": only spotify.raw takes writes")
            if (a == Action.SPOTIFY_RAW) {
                if (r.writes.size > MAX_WRITES) add("spotify.raw: $MAX_WRITES writes at most")
                r.writes.forEach { w ->
                    val parts = w.trim().split(Regex("\\s+"), 2)
                    val why = if (parts.size != 2) "\"METHOD https://host/path\""
                        else runCatching { RawRequest.check(parts[0], parts[1]) }.fold(
                            { m -> if (m in RawRequest.READS) "a write (POST, PUT, PATCH, DELETE): reads never ask" else null },
                            { it.message })
                    why?.let { add("spotify.raw write \"$w\": $it") }
                }
            }
        }
        media?.let { m ->
            if (m.hosts.size > 20) add("media: 20 hosts at most")
            m.hosts.filter { it == "*" || !HOST.matches(it) }.forEach { add("media host \"$it\": a host name or *.domain (not *)") }
        }
        if (actions.size != actions.map { it.name }.toSet().size) add("actions: an action listed twice")
        if (settings.isNotEmpty() && actions.none { it.name == Action.SETTINGS_PAGE.key }) add("settings: add the settings.page action (with its why) to have a settings page")
    }

    /** The rules of an old manifest, as they were: it still installs, mapped to actions. */
    private fun MutableList<String>.legacyProblems() {
        if (provides.isEmpty() && permissions.none { it.name == "player.read" }) add("provides: at least one of ${Hook.LEGACY_KEYS.joinToString()} (or player.read, for an extension that only listens); better: \"actions\"")
        provides.filter { Hook.ofLegacy(it) == null }.forEach { add("provides \"$it\": unknown (known: ${Hook.LEGACY_KEYS.joinToString()})") }
        if (provides.size != provides.toSet().size) add("provides: listed twice")
        permissions.forEach { p ->
            val known = p.name in Action.LEGACY
            val hook = Hook.ofLegacy(p.name)
            when {
                !known -> add("permission \"${p.name}\": unknown (known: ${Action.LEGACY.keys.filter { Hook.ofLegacy(it) == null }.joinToString()})")
                hook != null && hook.key !in provides -> add("permission \"${p.name}\": a justification for a hook the extension does not provide")
                p.name == "network" && p.hosts.isEmpty() -> add("network: list the hosts it calls (\"api.example.com\", \"*.example.com\", or \"*\" for any)")
                p.name == "network" -> p.hosts.filterNot { HOST.matches(it) }.forEach { add("network host \"$it\": a host name, *.domain, or *") }
            }
        }
        if (permissions.size != permissions.map { it.name }.toSet().size) add("permissions: a name listed twice")
        asks().filter { it.level == Level.CRITICAL && it.why.trim().length < 15 }
            .forEach { add("\"${it.key}\" is a critical action: say why the extension needs it in \"why\" (15 to 80 characters), the user reads it before installing") }
    }

    /** Its settings shown in [section] of the app's Settings, in the manifest's order. */
    fun settingsIn(section: SettingSection): List<Setting> = settings.filter { it.sectionOf == section }

    /** Its quality for [section] (`role: quality`), the first if it lists more (the check refuses that). */
    fun qualitySetting(section: SettingSection): Setting? = settingsIn(section).firstOrNull { it.isQuality }

    /**
     * The keys of its per-network settings whose value differs between Wi-Fi and mobile data:
     * the ones a network change changes, for the `setting` event `{ key, network: true }`.
     */
    fun networkKeys(stored: (String) -> String?): List<String> =
        settings.filter { it.splitsByNetwork && it.value(stored, false) != it.value(stored, true) }.map { it.key }

    /**
     * What is wrong with the settings' `section`, `role` and `perNetwork`, for `myspoti-ext check`.
     * Never a reason to refuse the package: the app shows such a setting on its page only, as one value.
     */
    fun settingFieldProblems(): List<String> = buildList {
        settings.forEach { s ->
            if (s.role == Setting.ROLE_QUALITY && s.type != "choice") add("setting \"${s.key}\": role quality is a choice (its options are the qualities)")
            if (s.role == Setting.ROLE_QUALITY && s.section == null) add("setting \"${s.key}\": role quality needs a section (playback or downloads)")
            if (s.perNetwork && s.type !in Setting.NETWORK_TYPES) add("setting \"${s.key}\": perNetwork is for ${Setting.NETWORK_TYPES.joinToString()}, not ${s.type}")
        }
        SettingSection.entries.forEach { sec ->
            if (settingsIn(sec).count { it.role == Setting.ROLE_QUALITY } > 1) add("settings: one role quality per section at most (${sec.key} has more)")
        }
    }

    /** Values of `section` and `role` this Myspoti does not know, and a section the extension can never lead: told, not refused. */
    fun settingFieldWarnings(): List<String> = buildList {
        settings.forEach { s ->
            if (s.section != null && s.sectionOf == null) add("setting \"${s.key}\": section \"${s.section}\" unknown (known: ${SettingSection.entries.joinToString { it.key }}), shown on the extension's page only")
            if (s.role != null && s.role !in Setting.ROLES) add("setting \"${s.key}\": role \"${s.role}\" unknown (known: ${Setting.ROLES.joinToString()}), ignored")
            s.sectionOf?.let { sec -> if (sec.action !in actionSet()) add("setting \"${s.key}\": section ${sec.key} is shown only for an extension with ${sec.action.key}, which this one does not ask") }
        }
    }

    /** Where the store files it: the declared category if its actions fit it, else [Category.OTHER]. */
    fun category(): Category = Category.of(category, actionSet())

    /**
     * Why the declared category is not the one it is filed under, for the author (null: it is).
     * Not a reason to refuse the package: it is filed under "other".
     */
    fun categoryWarning(): String? {
        val c = category ?: return null
        val known = Category.of(c) ?: return "category \"$c\": unknown (known: ${Category.entries.joinToString { it.key }}), filed under other"
        val filed = category()
        return if (filed == known) null else "category \"$c\": filed under ${filed.key} (${if (filed == Category.TOOLS) "it provides downloads" else "it needs one of ${known.needs.joinToString { it.key }}"})"
    }

    companion object {
        private val ID = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")
        private val HOST = Regex("^(\\*|(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+)$")
        private val KEY = Regex("^[A-Za-z0-9_]{1,40}$")
        private val TAG = Regex("^[a-z0-9][a-z0-9 -]{0,23}$")
        private const val MAX_WRITES = 8

        fun parse(text: String): Manifest = ExtJson.decodeFromString(serializer(), text)
    }
}

/**
 * `welcome`: a few words after the first install, for what the extension does ([message]) and the
 * little the user still has to do ([steps], in order: "Pair your account in Settings → Extensions").
 */
@Serializable
data class Welcome(val message: String = "", val steps: List<String> = emptyList())

/** `media`: where audio, downloads and block images may come from, besides the `network` hosts. */
@Serializable
data class Media(val hosts: List<String> = emptyList())

@Serializable
data class PermissionRequest(
    /** A [Permission] key, or a [Hook] key to give the reason for a takeover. */
    val name: String,
    /** network only: the hosts it may call. */
    val hosts: List<String> = emptyList(),
    /** Why the extension needs it, in the author's words: shown to the user for sensitive rights. */
    val why: String = "",
)

@Serializable
data class Setting(
    val key: String,
    /**
     * text, secret (a password field, never logged), toggle, number, choice, or button: no value,
     * a button that sends the `setting` event with `pressed` (pair again, clear a cache…).
     */
    val type: String = "text",
    val label: String,
    val description: String = "",
    val default: JsonElement? = null,
    /** choice only. */
    val options: List<String> = emptyList(),
    /**
     * `playback` or `downloads`: also shown in that section of the app's Settings, under the
     * extension's name, while it is the first source there ([SettingSection]). Absent: its page only.
     * Any other value: its page only too (`myspoti-ext check` warns).
     */
    val section: String? = null,
    /** `quality` (a choice): the extension's quality, shown in the place of the app's own. Any other value is ignored. */
    val role: String? = null,
    /** One value on Wi-Fi and one on mobile data (choice, toggle, number, text); `get` gives the current network's. */
    val perNetwork: Boolean = false,
) {
    /** The section of Settings it is also shown in, or null (none, or one this Myspoti does not know). */
    val sectionOf: SettingSection? get() = SettingSection.of(section)

    /** It is the extension's quality for its section: `role: quality` on a choice. */
    val isQuality: Boolean get() = role == ROLE_QUALITY && type == "choice" && sectionOf != null

    /** It has a Wi-Fi and a mobile data value: `perNetwork` on a type that has a value to split. */
    val splitsByNetwork: Boolean get() = perNetwork && type in NETWORK_TYPES

    /**
     * The value `myspoti.settings.get` reads, as JSON text: on mobile data ([metered]) the mobile
     * value of a [splitsByNetwork] setting, else (and while it has none) the Wi-Fi one, which is
     * the one any setting has; else [default]. As the app's own streaming quality: mobile data
     * follows Wi-Fi until it is set.
     */
    fun value(stored: (String) -> String?, metered: Boolean): String? =
        (if (metered && splitsByNetwork) stored(cellKey(key)) else null) ?: stored(key) ?: default?.toString()

    companion object {
        val TYPES = listOf("text", "secret", "toggle", "number", "choice", "button")
        const val ROLE_QUALITY = "quality"
        val ROLES = listOf(ROLE_QUALITY)
        /** The types `perNetwork` splits: a secret or a button has no value per network. */
        val NETWORK_TYPES = listOf("choice", "toggle", "number", "text")
        /** Suffix of the stored mobile data value; never in a declared key (letters, digits, _). */
        private const val CELL = "@cell"

        /** Where the mobile data value of [key] is kept, next to its Wi-Fi value (kept under [key]). */
        fun cellKey(key: String) = key + CELL

        /** The declared key a stored key belongs to ([cellKey] undone). */
        fun baseKey(stored: String) = stored.removeSuffix(CELL)
    }
}

/** `section` of a setting: the section of the app's Settings it is also shown in, and the source order that decides. */
enum class SettingSection(val key: String, val action: Action) {
    /** Settings → Playback, while the extension is the first source of the audio order (above Spotify). */
    PLAYBACK("playback", Action.AUDIO_SOURCE),
    /** Settings → Downloads, while it is the first extension of the download order. */
    DOWNLOADS("downloads", Action.DOWNLOAD_SOURCE);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * One line of the install prompt and the detail pages: an [Action] (or `network.any`), its level,
 * the author's reason and, for network, the hosts.
 */
data class Ask(val key: String, val level: Level, val why: String = "", val hosts: List<String> = emptyList(), val action: Action? = Action.of(key)) {
    val critical: Boolean get() = level == Level.CRITICAL
}

/**
 * A point where the app calls the script: `myspoti.provide(key, fn)`. Each is unlocked by one
 * [action] (null for `tap`: unlocked by any action whose answer is drawn as blocks).
 */
enum class Hook(val key: String, val action: Action?) {
    /** Lyrics for a song, asked along the app's own sources. */
    LYRICS("lyrics", Action.LYRICS_PROVIDE),
    /** Where a song's audio comes from; it then plays in Myspoti's own player, like a download. */
    AUDIO_SOURCE("audio-source", Action.AUDIO_SOURCE),
    /** The files a song can be downloaded from: the app downloads, checks, encrypts and keeps them. */
    DOWNLOAD_SOURCE("download-source", Action.DOWNLOAD_SOURCE),
    /** Blocks under a song's credits. */
    TRACK_INFO("trackinfo", Action.TRACKINFO_PROVIDE),
    /** Sections at the end of Home. */
    HOME("home", Action.HOME_SECTIONS),
    /** Blocks under the search results. */
    SEARCH("search", Action.SEARCH_PROVIDE),
    /** Entries at the end of the ⋯ menus of songs, albums, playlists and artists. */
    MENU("menu", Action.MENU_ITEMS),
    /** Buttons under the player's own controls. */
    PLAYER("player", Action.PLAYER_BUTTONS),
    /** Its web page: `{ title, html }`. */
    PAGE("page", Action.WEBVIEW_PAGE),
    /** A message its web page posted; the answer is posted back to the page. */
    MESSAGE("message", Action.WEBVIEW_PAGE),
    /** A block with an `action` was tapped: `{ action, surface, context }`. */
    TAP("tap", null),
    /** Whether its source answers now: `{ live, detail? }` ([ExtensionStatus]). Any extension may provide it. */
    STATUS("status", null);

    /** Whether this hook's answer is drawn as blocks (suspended while a protected surface shows). */
    val drawn: Boolean get() = action in Action.BLOCKS || action == Action.WEBVIEW_PAGE || this == TAP

    /** Provided without an action of its own: `status`. */
    val free: Boolean get() = this == STATUS

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }

        /** The three hooks old manifests could list in `provides`. */
        val LEGACY_KEYS = listOf(LYRICS.key, AUDIO_SOURCE.key, DOWNLOAD_SOURCE.key)

        fun ofLegacy(key: String) = if (key in LEGACY_KEYS) of(key) else null
    }
}
