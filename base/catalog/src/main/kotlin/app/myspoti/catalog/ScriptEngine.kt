/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import java.io.IOException
import java.net.URI
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** One request from a script, on its own thread. */
fun interface ScriptHttp {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): Response
}

/** The network for scripts, paced, a failure tried again as long as the service asks to wait. */
class OkHttpScripts(
    private val client: OkHttpClient,
    private val paceMs: Long = 150,
    private val attempts: Int = 3,
    private val backoffMs: Long = 400,
) : ScriptHttp {
    private var nextAt = 0L

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): Response {
        var attempt = 0
        var failure: IOException? = null
        while (attempt < attempts) {
            if (attempt > 0) Thread.sleep(backoffMs shl (attempt - 1))
            attempt++
            synchronized(this) {
                val wait = nextAt - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                nextAt = System.currentTimeMillis() + paceMs
            }
            try {
                val builder = Request.Builder().url(url)
                headers.forEach { (k, v) -> builder.header(k, v) }
                if (body != null) builder.method(method, body.toRequestBody((headers["Content-Type"] ?: "application/json").toMediaType()))
                else builder.method(method, null)
                val r = client.newCall(builder.build()).execute().use { Response(it.code, it.body.string()) }
                if (r.status != 429 && r.status < 500) return r
                if (attempt == attempts) return r
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("no answer")
    }
}

/**
 * A script in a sandbox: no Java, no files, and the network only towards [hosts], over HTTPS. A run that lasts too
 * long is stopped. Runs on one thread at a time, the caller's business.
 */
internal class ScriptEngine(
    private val name: String,
    private val source: String,
    private val hosts: Set<String>,
    private val http: ScriptHttp,
    private val userAgent: String,
    private val runMs: Long = 25_000,
) {
    private var scope: Scriptable? = null
    private var call: Function? = null

    /** The last run could not reach the service: its empty answer says nothing about the music. */
    var networkFailed = false
        private set

    private fun allowed(url: String): Boolean = runCatching {
        val u = URI(url)
        val host = u.host?.lowercase() ?: return false
        u.scheme == "https" && hosts.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    private fun answer(status: Int, body: String, error: String?): String = Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "statusCode" to JsonPrimitive(status),
                "status" to JsonPrimitive(status),
                "body" to JsonPrimitive(body),
                "error" to if (error == null) JsonNull else JsonPrimitive(error),
            ),
        ),
    )

    private inner class Host : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
            val method = Context.toString(args[0])
            val url = Context.toString(args[1])
            if (!allowed(url)) return answer(0, "", "host not allowed")
            val headers = runCatching {
                (Json.parseToJsonElement(Context.toString(args[2])) as JsonObject).mapValues { it.value.jsonPrimitive.content }
            }.getOrDefault(emptyMap())
            val body = args.getOrNull(3)?.takeUnless { it == null || Undefined(it) }?.let { Context.toString(it) }
            return try {
                val r = http.request(method, url, mapOf("User-Agent" to userAgent) + headers, body)
                if (r.status == 429 || r.status >= 500) networkFailed = true
                answer(r.status, r.body, null)
            } catch (e: IOException) {
                networkFailed = true
                answer(0, "", e.message ?: "network error")
            }
        }

        private fun Undefined(v: Any) = v === org.mozilla.javascript.Undefined.instance
    }

    private fun <T> entered(run: (Context) -> T): T {
        val cx = FACTORY.enterContext()
        DEADLINE.set(System.currentTimeMillis() + runMs)
        try {
            return run(cx)
        } finally {
            DEADLINE.remove()
            Context.exit()
        }
    }

    private fun start(cx: Context): Function {
        call?.let { return it }
        val s = cx.initSafeStandardObjects()
        ScriptableObject.putProperty(s, "__host", Host())
        ScriptableObject.putProperty(s, "__userAgent", userAgent)
        cx.evaluateString(s, PRELUDE, "prelude.js", 1, null)
        cx.evaluateString(s, source, "$name.js", 1, null)
        val f = ScriptableObject.getProperty(s, "__call") as Function
        scope = s
        call = f
        return f
    }

    /** The answer of the script's [function], or null when it had none. Its own errors and a run too long are [ScriptFailed]. */
    fun call(function: String, vararg args: JsonElement): JsonElement? {
        networkFailed = false
        return entered { cx ->
            try {
                val f = start(cx)
                val out = f.call(cx, scope, scope, arrayOf<Any>(function, Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), kotlinx.serialization.json.JsonArray(args.toList()))))
                val parsed = Json.parseToJsonElement(Context.toString(out))
                if (parsed is JsonNull) null else parsed
            } catch (e: RhinoException) {
                throw ScriptFailed(name, e.message ?: "script error", e)
            } catch (e: TooLong) {
                throw ScriptFailed(name, "ran too long", e, definite = false)
            } catch (e: RuntimeException) {
                // An answer that is not JSON, a script without its entry point: the script, not the service.
                throw ScriptFailed(name, e.message ?: e.toString(), e)
            }
        }
    }

    private class TooLong : Error()

    private companion object {
        val PRELUDE: String = ScriptEngine::class.java.getResource("metadata/prelude.js")!!.readText()
        val DEADLINE = ThreadLocal<Long>()

        val FACTORY = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                isInterpretedMode = true
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 10_000
                setClassShutter { false }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                val until = DEADLINE.get() ?: return
                if (System.currentTimeMillis() > until) throw TooLong()
            }
        }
    }
}
