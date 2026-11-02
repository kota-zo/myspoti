package app.myspoti.extensions

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class BasicHttpTest {
    @Test fun responsesAreDecodedAsUtf8AcrossReadBoundaries() {
        // The bridge reads in 16 KiB chunks: split a multibyte character across that boundary.
        val bodies = listOf("", """{"artist":"Björk — 東京 🎵"}""", "x".repeat(16 * 1024 - 1) + "é🎵")
        for (body in bodies) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .body(body.toByteArray(Charsets.UTF_8).toResponseBody())
                    .build()
            }.build()

            // The interceptor answers in memory; no DNS or network access is used.
            val reply = BasicHttp.send(HttpRequest("https://example.com/bootstrap"), "test", client)
            assertEquals(200, reply.status)
            assertEquals("application/json; charset=utf-8", reply.headers["content-type"])
            assertEquals(body, reply.body)
        }
    }
}
