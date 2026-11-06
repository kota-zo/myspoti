/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.spotify.gql

import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The client token is minted once and reused by the next launch (a new SpotifyHttp) from the host's store. */
class ClientTokenStoreTest {
    /** Answers the page's token.get as the bridge would. */
    private class TokenHost(val clientId: String) : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""").find(script) ?: return
            val value = """{"accessToken":"web","expiresAtMs":${System.currentTimeMillis() + 3_600_000},"clientId":"$clientId","appVersion":"1.0","isAnonymous":false}"""
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
    }

    private class MemoryStore : ClientTokenStore {
        val saved = HashMap<String, String>()
        override fun load(deviceId: String) = saved[deviceId]
        override fun save(deviceId: String, text: String) { saved[deviceId] = text }
    }

    private var mints = 0
    private val sentTokens = mutableListOf<String>()
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val r = chain.request()
        val body = if (r.url.host == "clienttoken.spotify.com") {
            mints++
            """{"granted_token":{"token":"ct$mints","expires_after_seconds":1209600}}"""
        } else { sentTokens += r.header("client-token").orEmpty(); "{}" }
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
    }).build()

    private fun launch(store: ClientTokenStore, scope: CoroutineScope, device: String = "dev1", clientId: String = "c1") =
        SpotifyHttp(http, Bridge(TokenHost(clientId), scope), deviceId = device, tokenStore = store)

    @Test fun aTokenKeptOnDiskIsNotMintedAgain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = MemoryStore()
            launch(store, scope).send("GET", "https://api-partner.spotify.com/x")
            launch(store, scope).send("GET", "https://api-partner.spotify.com/x")
            assertEquals(1, mints, "the second launch reuses the stored token")
            assertEquals(listOf("ct1", "ct1"), sentTokens)
            // Another device id, or a token minted for another client id, is minted afresh.
            launch(store, scope, device = "dev2").send("GET", "https://api-partner.spotify.com/x")
            assertEquals(2, mints)
            launch(store, scope, clientId = "c2").send("GET", "https://api-partner.spotify.com/x")
            assertEquals(3, mints)
        } finally { scope.cancel() }
    }
}
