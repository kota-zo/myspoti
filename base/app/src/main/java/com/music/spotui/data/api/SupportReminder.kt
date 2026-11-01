package com.music.spotui.data.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.music.spotui.data.preferences.Look
import com.music.spotui.data.preferences.LookPref

/**
 * Donations and their thank-yous. Reminders: a day after first use, then every 8 to 12 days
 * (drawn per person and per reminder, never weekly);
 * for someone who said they gave once, every 3 months with a thank-you; never for a
 * monthly supporter. "I've supported" is taken on trust (nothing is checked, nothing is
 * sold): it unlocks cosmetic thank-yous only (accent colour, app icon, badge).
 */
object SupportReminder {
    const val URL = "https://ko-fi.com/myspoti"
    /** GitHub Sponsors, the other way to give (monthly or once). */
    const val SPONSORS_URL = "https://github.com/sponsors/kota-zo"
    const val FIRST_AFTER_MS = 24L * 3_600_000
    /**
     * Supporter perks ("I've supported", accent, icon, badge) are off until supports can
     * be checked: everyone is treated as a non-supporter and their entry points are hidden.
     */
    const val PERKS_ENABLED = false
    const val EVERY_MS = 8L * 24 * 3_600_000
    /** Up to this much more after [EVERY_MS], so reminders do not come on the same day for everyone. */
    const val SPREAD_MS = 4L * 24 * 3_600_000
    const val SUPPORTER_EVERY_MS = 90L * 24 * 3_600_000
    private const val PREFS = "support_reminder"
    /** The single accent of earlier versions, copied to both looks once ([init]). */
    private const val KEY_ACCENT = "accent"
    private const val KEY_ACCENT_CLASSIC = "accent_classic"
    private const val KEY_ACCENT_IMMERSIVE = "accent_immersive"

    enum class Level { NONE, ONCE, MONTHLY }

    /** A thank-you colour: the app's accent, and the app icon's bars. */
    enum class Accent(val key: String, val color: Color) {
        GREEN("green", Color(0xFF1ED760)),
        BLUE("blue", Color(0xFF509BF5)),
        PURPLE("purple", Color(0xFFAF7AF6)),
        PINK("pink", Color(0xFFF573A0)),
        ORANGE("orange", Color(0xFFFF9E3D)),
    }

    /** What the user said ("I've supported"), kept while perks are off. */
    private var storedLevel by mutableStateOf(Level.NONE)
    /** The supporter level in effect (NONE while perks are off). */
    val level: Level get() = if (PERKS_ENABLED) storedLevel else Level.NONE
    /** The accent chosen for the Classic look. */
    var classicAccent by mutableStateOf(Accent.GREEN)
        private set
    /** The accent chosen for the Immersive look. */
    var immersiveAccent by mutableStateOf(Accent.GREEN)
        private set
    /** A colour was picked for Immersive (green is also the default): the look has its own default accent until then. */
    var immersiveAccentChosen by mutableStateOf(false)
        private set
    var icon by mutableStateOf(Accent.GREEN)
        private set

    /** The accent chosen for a look. */
    fun accentOf(look: Look): Accent = if (look == Look.IMMERSIVE) immersiveAccent else classicAccent

    /** The colour the active look uses for its accent (Spotify green unless another was chosen in Settings). */
    val accentColor: Color get() = accentOf(LookPref.look).color

    /** When the next reminder is due. */
    fun nextAt(firstSeen: Long, lastShown: Long, level: Level, supportedAt: Long): Long = when (level) {
        Level.MONTHLY -> Long.MAX_VALUE
        Level.ONCE -> maxOf(lastShown, supportedAt) + SUPPORTER_EVERY_MS
        Level.NONE -> if (lastShown == 0L) firstSeen + FIRST_AFTER_MS else lastShown + EVERY_MS + spread(firstSeen, lastShown)
    }

    /**
     * The extra wait after [EVERY_MS], in [0, [SPREAD_MS]): drawn from the first use and the last
     * reminder, so it differs between people and between reminders but stays the same each time
     * the app asks (nothing more to keep).
     */
    internal fun spread(firstSeen: Long, lastShown: Long): Long =
        java.util.Random(firstSeen * 31 + lastShown).nextLong().mod(SPREAD_MS)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        storedLevel = runCatching { Level.valueOf(p.getString("level", "NONE")!!) }.getOrDefault(Level.NONE)
        // One accent used to serve both looks: whoever picked one keeps it for each look.
        if (p.contains(KEY_ACCENT) && !p.contains(KEY_ACCENT_CLASSIC) && !p.contains(KEY_ACCENT_IMMERSIVE)) {
            val old = p.getString(KEY_ACCENT, "green")
            p.edit().putString(KEY_ACCENT_CLASSIC, old).putString(KEY_ACCENT_IMMERSIVE, old).apply()
        }
        classicAccent = Accent.entries.firstOrNull { it.key == p.getString(KEY_ACCENT_CLASSIC, "green") } ?: Accent.GREEN
        immersiveAccent = Accent.entries.firstOrNull { it.key == p.getString(KEY_ACCENT_IMMERSIVE, "green") } ?: Accent.GREEN
        immersiveAccentChosen = p.contains(KEY_ACCENT_IMMERSIVE)
        icon = Accent.entries.firstOrNull { it.key == p.getString("icon", "green") } ?: Accent.GREEN
    }

    fun isDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        val firstSeen = p.getLong("first_seen", 0L).takeIf { it > 0 } ?: now.also { p.edit().putLong("first_seen", it).apply() }
        return now >= nextAt(firstSeen, p.getLong("last_shown", 0L), level, p.getLong("supported_at", 0L))
    }

    /** The reminder was answered (Ko-fi opened or "no thanks"). */
    fun shown(context: Context) = prefs(context).edit().putLong("last_shown", System.currentTimeMillis()).apply()

    /** "I've supported", on trust. */
    fun setLevel(context: Context, value: Level) {
        storedLevel = value
        prefs(context).edit().putString("level", value.name).putLong("supported_at", System.currentTimeMillis()).apply()
    }

    fun setAccent(context: Context, look: Look, value: Accent) {
        if (look == Look.IMMERSIVE) {
            immersiveAccent = value
            immersiveAccentChosen = true
            prefs(context).edit().putString(KEY_ACCENT_IMMERSIVE, value.key).apply()
        } else {
            classicAccent = value
            prefs(context).edit().putString(KEY_ACCENT_CLASSIC, value.key).apply()
        }
    }

    /**
     * Switches the launcher icon: one launcher alias per colour, only the chosen one
     * enabled (Android has no other way to change an app's icon).
     */
    fun setIcon(context: Context, value: Accent) {
        // The launcher aliases are commented out in the manifest while perks are off.
        if (!PERKS_ENABLED || value == icon) return
        val pm = context.packageManager
        for (a in Accent.entries) {
            val state = if (a == value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(ComponentName(context, aliasOf(a)), state, PackageManager.DONT_KILL_APP)
        }
        icon = value
        prefs(context).edit().putString("icon", value.key).apply()
    }

    private fun aliasOf(a: Accent) = "app.myspoti.engine.ui.Launcher" + a.key.replaceFirstChar { it.uppercase() }

    fun open(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openSponsors(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SPONSORS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
