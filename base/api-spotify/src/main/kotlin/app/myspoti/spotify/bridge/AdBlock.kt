package app.myspoti.spotify.bridge

import app.myspoti.spotify.AccountPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ad blocker, for Spotify Connect devices only. It acts while the app sends the music to
 * another device of the account through Spotify Connect (a speaker, a TV, a receiver picked in
 * the app's device list), where Spotify plays the songs and their ads on that device itself:
 * the app mutes that device for the break (its RemoteAdMute, the Connect volume command), and the
 * Web Player page that controls it is refused the ad hosts of the bundled list ([isBlocked]).
 *
 * Everywhere else it does nothing: the phone's own page, as the account's player or as the
 * unheard listen-sync mirror, loads exactly what Spotify sends, its ads and the third-party hosts
 * around them included (an ad whose verification or tracking host is refused can stall, and the
 * listen-sync mirror with it). Never on a Premium account, which has none ([AccountPlan]).
 */
object AdBlock {
    /**
     * Whether the blocker acts: another device of the account plays ([elsewhere]), and it is the
     * one the user sent the music to from the app ([activeId] == [chosenId]); not a Jam's device
     * (`social-connect`) nor a Jam joined as a member ([jamMember], the host's device plays), and
     * never on a Premium account.
     */
    fun activeFor(
        elsewhere: Boolean, activeId: String?, chosenId: String?, jamMember: Boolean,
        premium: Boolean = AccountPlan.isPremium,
    ): Boolean =
        !premium && elsewhere && activeId != null && activeId == chosenId &&
            !activeId.startsWith("social-connect") && !jamMember

    private val _active = MutableStateFlow(false)

    /** The blocker acts now (see [activeFor]); set by the app as the device that plays changes. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Sets [active]: [on] is [activeFor] of the device that plays now. */
    fun setActive(on: Boolean) { _active.value = on && !AccountPlan.isPremium }

    /** One rule of the list: [host] and its subdomains, and only the paths starting with [path] when set. */
    internal data class Rule(val host: String, val path: String?) {
        fun matches(urlHost: String, urlPath: String): Boolean =
            (urlHost == host || urlHost.endsWith(".$host")) && (path == null || urlPath.startsWith(path))
    }

    /** The bundled list, read once: its ad rules and its tracker rules, both refused only while the blocker acts. */
    internal class Rules(val ads: List<Rule>, val trackers: List<Rule>)

    internal val rules: Rules by lazy {
        parse(AdBlock::class.java.getResourceAsStream(LIST)!!.bufferedReader().use { it.readText() })
    }

    /**
     * The list's text: `[ads]` and `[trackers]` sections, one rule a line (`host` or `host/path`),
     * `#` starting a comment. A line before any section, or a section of another name, is ignored.
     */
    internal fun parse(text: String): Rules {
        val ads = ArrayList<Rule>()
        val trackers = ArrayList<Rule>()
        var into: MutableList<Rule>? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith('[') && line.endsWith(']')) {
                into = when (line) { "[ads]" -> ads; "[trackers]" -> trackers; else -> null }
                continue
            }
            val slash = line.indexOf('/')
            val rule = if (slash < 0) Rule(line.lowercase(), null) else Rule(line.substring(0, slash).lowercase(), line.substring(slash))
            into?.add(rule)
        }
        return Rules(ads, trackers)
    }

    /** Every host a rule names, for a host that filters by URL pattern before asking [isBlocked] (the desktop one). */
    val hosts: List<String> get() = (rules.ads + rules.trackers).map { it.host }.distinct()

    /** Whether the page is refused [url]: an ad or tracker host of the list, only while the blocker is [active]. */
    fun isBlocked(url: String): Boolean = isBlocked(url, _active.value)

    internal fun isBlocked(url: String, blocking: Boolean): Boolean {
        if (!blocking) return false
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        val path = rest.drop(authority.length).substringBefore('?').substringBefore('#').ifEmpty { "/" }
        return rules.ads.any { it.matches(host, path) } || rules.trackers.any { it.matches(host, path) }
    }

    private const val LIST = "/bridge/page-filter.txt"
}
