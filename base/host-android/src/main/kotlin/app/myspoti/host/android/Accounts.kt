package app.myspoti.host.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * One Spotify account kept on this phone: the WebView profile that holds its sign-in (cookies,
 * Web storage), and what the app shows of it. No e-mail and no user id: [key] is the opaque folder
 * key of the downloads (OfflineAccounts), the name and picture are the public profile's.
 */
data class SavedAccount(
    val profile: String,
    /** Opaque key of the Spotify user (a hash of its id); null until the account was read signed in. */
    val key: String? = null,
    val name: String? = null,
    val imageUrl: String? = null,
    /** The plan (AccountPlan): true Premium, false Free, null not read yet. */
    val premium: Boolean? = null,
    /** When it was last the active account (ms): the account to go back to is the one used last. */
    val usedAt: Long = 0L,
) {
    /** Signed in on its profile at least once: something to switch to. */
    val known: Boolean get() = key != null
}

/**
 * The accounts kept on this phone, each in a WebView profile of its own, and the active one (the
 * profile the page is built with). Pure: every change returns a new list, [AccountStore] keeps it.
 *
 * The account a WebView without profiles holds is in [DEFAULT_PROFILE], so an install from before
 * keeps its sign-in as its first account.
 */
data class AccountList(
    val active: String = DEFAULT_PROFILE,
    val accounts: List<SavedAccount> = emptyList(),
    /** Number of the next profile [adding] makes. Never reused: a profile still being deleted keeps its name. */
    val next: Int = 1,
    /** Profiles removed whose data could not be deleted yet (still in use by a WebView): deleted later. */
    val doomed: List<String> = emptyList(),
) {
    val current: SavedAccount? get() = accounts.firstOrNull { it.profile == active }

    /** The accounts signed in at least once other than the active one, the one used last first. */
    val others: List<SavedAccount> get() = accounts.filter { it.profile != active && it.known }.sortedByDescending { it.usedAt }

    /** A new, empty profile for another account, made the active one; its name is returned with the list. */
    fun adding(now: Long): Pair<AccountList, String> {
        var n = next
        var name = "$PROFILE_PREFIX$n"
        while (accounts.any { it.profile == name } || name in doomed) { n++; name = "$PROFILE_PREFIX$n" }
        val list = copy(
            active = name,
            accounts = touched(now) + SavedAccount(profile = name, usedAt = now),
            next = n + 1,
        )
        return list to name
    }

    /** [profile] becomes the active one (unchanged when it is not kept here). */
    fun switchTo(profile: String, now: Long): AccountList {
        if (accounts.none { it.profile == profile }) return this
        return copy(active = profile, accounts = touched(now).map { if (it.profile == profile) it.copy(usedAt = now) else it })
    }

    /**
     * [profile] is signed in as the account [key], named [name] with the picture [imageUrl]. The same
     * account kept in another profile too (signed in twice through "Add account") is one account: the
     * profile just signed in stays, the others are dropped and returned, for their data to be deleted.
     */
    fun signedIn(profile: String, key: String, name: String?, imageUrl: String?, now: Long): Pair<AccountList, List<String>> {
        val duplicates = accounts.filter { it.profile != profile && it.key == key }.map { it.profile }
        val mine = accounts.firstOrNull { it.profile == profile }
        val updated = (mine ?: SavedAccount(profile = profile)).copy(
            key = key,
            name = name?.takeIf { it.isNotBlank() } ?: mine?.name?.takeIf { mine.key == key },
            imageUrl = imageUrl?.takeIf { it.isNotBlank() } ?: mine?.imageUrl?.takeIf { mine.key == key },
            // Another account signed in on that profile (after a sign-out) has its own plan, read later.
            premium = mine?.premium?.takeIf { mine.key == key },
            usedAt = if (profile == active) now else mine?.usedAt ?: 0L,
        )
        val kept = (if (mine == null) accounts + updated else accounts.map { if (it.profile == profile) updated else it })
            .filter { it.profile !in duplicates }
        val list = copy(accounts = kept, doomed = doomed + duplicates.filter { it !in doomed })
        return list to duplicates
    }

    /** The plan of [profile]'s account read (or forgotten: null keeps the one known). */
    fun plan(profile: String, premium: Boolean?): AccountList {
        if (premium == null) return this
        return copy(accounts = accounts.map { if (it.profile == profile && it.known) it.copy(premium = premium) else it })
    }

    /** [profile] signed out: it stays the profile it is, with no account in it any more. */
    fun signedOut(profile: String): AccountList =
        copy(accounts = accounts.map { if (it.profile == profile) SavedAccount(profile = profile, usedAt = it.usedAt) else it })

    /**
     * [profile] is no longer kept: its entry goes, and its data is to be deleted ([doomed]); the
     * default profile cannot be deleted and is cleared instead (by the host). The active profile is
     * never removed here: the page is first moved to another one ([switchTo]).
     */
    fun removed(profile: String): AccountList {
        if (profile == active || accounts.none { it.profile == profile }) return this
        return copy(
            accounts = accounts.filter { it.profile != profile },
            doomed = if (profile in doomed) doomed else doomed + profile,
        )
    }

    /** [profile]'s data was deleted (or never existed): nothing left to do for it. */
    fun deleted(profile: String): AccountList = copy(doomed = doomed - profile)

    private fun touched(now: Long) = accounts.map { if (it.profile == active) it.copy(usedAt = now) else it }

    fun toJson(): String = buildJsonObject {
        put("active", active)
        put("next", next)
        put("accounts", buildJsonArray {
            accounts.forEach { a ->
                add(buildJsonObject {
                    put("profile", a.profile)
                    a.key?.let { put("key", it) }
                    a.name?.let { put("name", it) }
                    a.imageUrl?.let { put("image", it) }
                    a.premium?.let { put("premium", it) }
                    put("used", a.usedAt)
                })
            }
        })
        put("doomed", buildJsonArray { doomed.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    companion object {
        /** androidx.webkit.Profile.DEFAULT_PROFILE_NAME: the profile every WebView used before. */
        const val DEFAULT_PROFILE = "Default"
        const val PROFILE_PREFIX = "account-"

        /** The list saved by [toJson]; an empty one (the default profile active) when there is none or it cannot be read. */
        fun fromJson(text: String?): AccountList {
            val o = text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return AccountList()
            fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
            val accounts = (o["accounts"] as? JsonArray).orEmpty().mapNotNull { e ->
                val a = e as? JsonObject ?: return@mapNotNull null
                val profile = a.str("profile")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SavedAccount(
                    profile = profile,
                    key = a.str("key"),
                    name = a.str("name"),
                    imageUrl = a.str("image"),
                    premium = (a["premium"] as? JsonPrimitive)?.booleanOrNull,
                    usedAt = (a["used"] as? JsonPrimitive)?.longOrNull ?: 0L,
                )
            }.distinctBy { it.profile }
            val doomed = (o["doomed"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val active = o.str("active")?.takeIf { a -> accounts.any { it.profile == a } }
                ?: accounts.maxByOrNull { it.usedAt }?.profile ?: DEFAULT_PROFILE
            return AccountList(active, accounts, ((o["next"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1), doomed)
        }
    }
}

/**
 * The [AccountList] of this install, saved in `filesDir/spotify/accounts.json`. A WebView without
 * profiles (MULTI_PROFILE unsupported) keeps one account at a time, in [AccountList.DEFAULT_PROFILE].
 */
class AccountStore(private val file: File) {
    private val _list = MutableStateFlow(AccountList.fromJson(runCatching { file.readText() }.getOrNull()))
    val list: StateFlow<AccountList> = _list

    /** Applies [change] and saves the result; returns it. */
    fun update(change: (AccountList) -> AccountList): AccountList = synchronized(this) {
        val next = change(_list.value)
        if (next != _list.value) {
            _list.value = next
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(next.toJson())
                if (!tmp.renameTo(file)) { file.writeText(next.toJson()); tmp.delete() }
            }.onFailure { Logbook.log("accounts: could not be saved (${it.javaClass.simpleName})") }
        }
        next
    }
}
