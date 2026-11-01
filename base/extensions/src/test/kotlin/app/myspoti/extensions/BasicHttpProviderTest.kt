package app.myspoti.extensions
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
class BasicHttpProviderTest {
 @Test fun providerUserAgentAndUtf8ArePreserved() {
  val client=OkHttpClient.Builder().addInterceptor { chain ->
   assertEquals("SpotiFLAC-Mobile/test",chain.request().header("User-Agent"))
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
    .body("Björk 東京".toResponseBody()).build()
  }.build()
  val reply=BasicHttp.send(HttpRequest("https://example.org/",headers=mapOf("User-Agent" to "SpotiFLAC-Mobile/test")),"host-default",client)
  assertEquals("Björk 東京",reply.body)
 }
}
