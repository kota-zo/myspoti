package app.myspoti.extensions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * How a source hook call (`audio-source`, `download-source`, `lyrics`) ended. An extension tells
 * why it has nothing with `{ outcome, retryAfterMs?, untilMs?, detail?, available? }` (feature
 * `results` in `myspoti.features`); the app classifies what it did not say ([of]). Only [Bug]
 * counts toward turning an extension off: a slow, rate-limited or down server never does.
 */
sealed class Outcome {
    /** The wire name, for logs, diagnostics and `{ outcome }`. */
    abstract val key: String
    /** What the extension or the app said about it, or null. */
    open val detail: String? get() = null

    /** It answered with something to use. */
    object Found : Outcome() { override val key = "found" }

    /** It has nothing for this (null, or `{ outcome: "not-found" }`). */
    object NotFound : Outcome() { override val key = "not-found" }

    /** Its account needs the user to sign in ([detail]: what to tell them). */
    data class SignIn(override val detail: String? = null) : Outcome() { override val key = "sign-in" }

    /** Its server asks to wait [retryAfterMs] (bounded by the app, [SourceCooldown]). */
    data class RateLimited(val retryAfterMs: Long? = null, override val detail: String? = null) : Outcome() { override val key = "rate-limited" }

    /** It has the song, not in the quality asked; [available]: the ones it has. */
    data class QualityUnavailable(val available: List<String> = emptyList(), override val detail: String? = null) : Outcome() { override val key = "quality-unavailable" }

    /** Its server is down until [untilMs] (wall-clock ms, bounded by the app), or for a while. */
    data class ServerDown(val untilMs: Long? = null, override val detail: String? = null) : Outcome() { override val key = "server-down" }

    /** A timeout or a network error of its requests: not counted against it. */
    data class Network(override val detail: String? = null) : Outcome() { override val key = "network" }

    /** Not asked ([detail]: why: busy, cooling down, inactive). */
    data class Skipped(override val detail: String? = null) : Outcome() { override val key = "skipped" }

    /** Its script threw, or answered something unusable: counted toward turning it off. */
    data class Bug(override val detail: String? = null) : Outcome() { override val key = "bug" }

    /** Whether it counts as a failure of the extension ([Bug] only). */
    val counts: Boolean get() = this is Bug

    /** `key` and its detail, for one log line. */
    fun describe(): String = key + (detail?.let { ": $it" } ?: "") + when (this) {
        is RateLimited -> retryAfterMs?.let { " (retry after $it ms)" } ?: ""
        is ServerDown -> untilMs?.let { " (until $it)" } ?: ""
        is QualityUnavailable -> if (available.isEmpty()) "" else " (has ${available.joinToString()})"
        else -> ""
    }

    companion object {
        const val MAX_DETAIL = 120
        private const val MAX_AVAILABLE = 10

        /** The hooks whose answer may be an outcome. */
        val HOOKS = setOf(Hook.AUDIO_SOURCE, Hook.DOWNLOAD_SOURCE, Hook.LYRICS)

        /**
         * The outcome [json] states, or null when it is an ordinary answer (no string `outcome`
         * at its root, or [hook] not one of [HOOKS]). [now]: the clock, turning a `retryAfterMs`
         * of `server-down` into its end. An unknown outcome is a [Bug].
         */
        fun parse(hook: Hook, json: String, now: Long = System.currentTimeMillis()): Outcome? {
            if (hook !in HOOKS) return null
            val o = runCatching { ExtJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val name = (o["outcome"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase() ?: return null
            fun long(k: String) = (o[k] as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong() }?.takeIf { it >= 0 }
            val detail = (o["detail"] as? JsonPrimitive)?.contentOrNull?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_DETAIL)?.ifBlank { null }
            return when (name) {
                "not-found", "notfound", "none" -> NotFound
                "sign-in", "signin" -> SignIn(detail)
                "rate-limited", "ratelimited" -> RateLimited(long("retryAfterMs"), detail)
                "quality-unavailable" -> QualityUnavailable(
                    (o["available"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(40)?.ifBlank { null } }.take(MAX_AVAILABLE),
                    detail,
                )
                "server-down" -> ServerDown(long("untilMs") ?: long("retryAfterMs")?.let { now + it }, detail)
                "network" -> Network(detail)
                "error", "bug" -> Bug(detail ?: "the extension said it failed")
                else -> Bug("${hook.key}: unknown outcome \"${name.take(40)}\"")
            }
        }

        /**
         * What a call that threw [e] means: still busy ([Skipped]), a timeout or an error of its
         * `http` requests ([Network]), else its own failure ([Bug]). A request the sandbox refused
         * (a host not declared, a local address, plain http) is the extension's bug.
         */
        fun of(e: ExtensionException): Outcome {
            val m = e.message ?: e.javaClass.simpleName
            return when {
                e is ExtensionBusy -> Skipped("busy: $m")
                e is ExtensionFault -> Bug(m)
                e is ExtensionTimeout -> Network("timeout: $m")
                networkError(m) -> Network(m)
                else -> Bug(m)
            }
        }

        /** The sandbox's own refusals of a request: written by the extension, so its bug. */
        private val REFUSALS = listOf(
            "is a local address", "not in this extension's network hosts", "https only", "not a URL", "no host in",
            "unreadable request", "is not allowed",
        )

        /** [message] is an `http:` error that is not a refusal (DNS, connection, timeout, too large). */
        internal fun networkError(message: String): Boolean {
            val at = message.indexOf("http: ")
            if (at < 0) return false
            val rest = message.substring(at + 6)
            return REFUSALS.none { rest.contains(it, ignoreCase = true) }
        }
    }
}
