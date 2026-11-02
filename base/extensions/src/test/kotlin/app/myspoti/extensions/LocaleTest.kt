package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleTest {
    private val track = TrackInfo("abc", "spotify:track:abc", "Song", "Artist", listOf("Artist"), "Album", 180_000)

    private class LocaleHost(@Volatile var tag: String) : Host {
        override fun log(extensionId: String, message: String) {}
        override fun storage(extensionId: String): Storage = MemoryStorage()
        override fun setting(extensionId: String, key: String): String? = null
        override fun locale(): String = tag
    }

    private val manifest = Manifest("io.github.test.ext", "Test", "1.0", author = "me", provides = listOf("lyrics"))

    private fun runtime(host: Host, script: String = "myspoti.provide('lyrics', function () { return { v: myspoti.locale }; });") =
        ExtensionRuntime(manifest, script, host, manifest.asks().map { it.key }.toSet()).also { it.start() }

    @Test fun localeFollowsTheAppLanguageWithoutAReload() {
        val host = LocaleHost("it")
        runtime(host).use {
            assertEquals("""{"v":"it"}""", it.call(Hook.LYRICS, track.toJson(), 3_000))
            host.tag = "pt-BR"
            assertEquals("""{"v":"pt-BR"}""", it.call(Hook.LYRICS, track.toJson(), 3_000))
        }
    }

    @Test fun localeIsNeverEmpty() {
        val fallback = java.util.Locale.getDefault().toLanguageTag()
        runtime(LocaleHost("")).use { assertEquals("""{"v":"$fallback"}""", it.call(Hook.LYRICS, track.toJson(), 3_000)) }
    }

    @Test fun localeCannotBeReplacedAndNeedsNoAction() {
        val js = "try { myspoti.locale = 'xx'; } catch (e) {} myspoti.provide('lyrics', function () { return { v: myspoti.locale + ' ' + typeof myspoti.apiVersion }; });"
        runtime(LocaleHost("fr"), js).use { assertEquals("""{"v":"fr number"}""", it.call(Hook.LYRICS, track.toJson(), 3_000)) }
    }
}
