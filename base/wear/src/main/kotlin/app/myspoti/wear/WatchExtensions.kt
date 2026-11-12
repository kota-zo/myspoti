package app.myspoti.wear

import android.content.Context
import android.util.Log
import app.myspoti.extensions.AudioQuery
import app.myspoti.extensions.AudioStream
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.ExtensionRuntime
import app.myspoti.extensions.Hook
import app.myspoti.extensions.HookBudgets
import app.myspoti.extensions.Host
import app.myspoti.extensions.Storage
import app.myspoti.extensions.TrackInfo
import app.myspoti.pass.PassExtension
import app.myspoti.pass.PassPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's extensions, run on the watch itself (the same sandbox as on the phone): an
 * audio-source one finds a song's audio and the watch plays it straight from the Wi-Fi or its
 * own network, with no phone and no music account. Copied from the phone when asked ([sync]),
 * with what was granted there, on or off, their store and their settings; nothing else.
 */
object WatchExtensions : Host {
    private const val TAG = "Orpheon"
    class Installed(val id: String, val pkg: ExtensionPackage, val granted: Set<String>)

    private lateinit var dir: File
    private val _installed = MutableStateFlow<List<Installed>>(emptyList())
    val installed: StateFlow<List<Installed>> = _installed
    private val runtimes = ConcurrentHashMap<String, ExtensionRuntime>()
    private val stores = ConcurrentHashMap<String, FileMap>()
    private val settings = ConcurrentHashMap<String, FileMap>()
    private val MAP = MapSerializer(String.serializer(), String.serializer())

    /** A key → JSON text map kept in one file (an extension's store or its settings). */
    private class FileMap(private val file: File) : Storage {
        private val values: MutableMap<String, String> by lazy {
            runCatching { PassPayload.JSON.decodeFromString(MAP, file.readText()).toMutableMap() }.getOrElse { LinkedHashMap() }
        }
        @Synchronized override fun get(key: String) = values[key]
        @Synchronized override fun set(key: String, json: String) { values[key] = json; save() }
        @Synchronized override fun remove(key: String) { if (values.remove(key) != null) save() }
        @Synchronized override fun size() = values.entries.sumOf { it.key.length + it.value.length }
        private fun save() = runCatching { file.writeText(PassPayload.JSON.encodeToString(MAP, values)) }
    }

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "extensions").apply { mkdirs() }
        load()
    }

    private fun load() {
        _installed.value = dir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { d ->
            val enabled = File(d, "off").exists().not()
            if (!enabled) return@mapNotNull null
            runCatching {
                Installed(d.name, ExtensionPackage.read(File(d, "package.zip").readBytes()),
                    File(d, "granted").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.toSet().orEmpty())
            }.onFailure { Log.w(TAG, "extension ${d.name} left out: ${it.message}") }.getOrNull()
        }
    }

    /** Takes the phone's extensions in place of those here; how many are on. */
    suspend fun sync(context: Context): Int = withContext(Dispatchers.IO) {
        init(context)
        val list = PassPayload.JSON.decodeFromString(ListSerializer(PassExtension.serializer()), PhoneLink(context).extensions())
        close()
        dir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        val d64 = Base64.getDecoder()
        for (x in list) runCatching {
            if (!x.id.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return@runCatching
            val d = File(dir, x.id).apply { mkdirs() }
            File(d, "package.zip").writeBytes(d64.decode(x.packageZip))
            File(d, "granted").writeText(x.granted.joinToString("\n"))
            if (!x.enabled) File(d, "off").writeText("")
            x.storage?.let { File(d, "storage.json").writeText(it) }
            x.settings?.let { File(d, "settings.json").writeText(it) }
        }.onFailure { Log.w(TAG, "extension ${x.id} not copied: ${it.message}") }
        stores.clear(); settings.clear()
        load()
        _installed.value.size
    }

    private fun runtime(x: Installed): ExtensionRuntime? {
        runtimes[x.id]?.takeIf { !it.closed }?.let { return it }
        return synchronized(this) {
            runtimes[x.id]?.takeIf { !it.closed } ?: runCatching {
                ExtensionRuntime(x.pkg.manifest, x.pkg.script, this, x.granted).also { it.start(); runtimes[x.id] = it }
            }.onFailure { Log.w(TAG, "extension ${x.id} did not load: ${it.message}") }.getOrNull()
        }
    }

    /** Whether an extension here can give audio. */
    fun hasAudio(): Boolean = _installed.value.any { x -> runCatching { runtime(x)?.provides(Hook.AUDIO_SOURCE) == true }.getOrDefault(false) }

    /**
     * The audio of [t] from the first extension that has it, with that extension's name; null when
     * none has. A stream the app would have to decrypt itself is not taken (the watch does not).
     */
    suspend fun audio(t: WatchTrack, ahead: Boolean = false): Pair<AudioStream, String>? = withContext(Dispatchers.IO) {
        val info = TrackInfo(
            id = t.uri.substringAfterLast(':'), uri = t.uri, title = t.name, artist = t.artists.firstOrNull().orEmpty(),
            artists = t.artists, album = t.album, durationMs = t.durationMs, cover = t.cover,
        )
        val input = AudioQuery.toJson(info, ahead)
        for (x in _installed.value) {
            val rt = runtime(x)?.takeIf { it.provides(Hook.AUDIO_SOURCE) } ?: continue
            val stream = runCatching { rt.call(Hook.AUDIO_SOURCE, input, HookBudgets.of(Hook.AUDIO_SOURCE))?.let(AudioStream::parse) }
                .onFailure { Log.d(TAG, "extension ${x.id}: ${it.message}") }.getOrNull() ?: continue
            if (stream.transform != null) continue
            return@withContext stream to x.pkg.manifest.name
        }
        null
    }

    fun close() { runtimes.values.forEach { runCatching { it.close() } }; runtimes.clear() }

    // --- Host ---------------------------------------------------------------------------------
    override fun log(extensionId: String, message: String) { Log.d(TAG, "[$extensionId] $message") }
    override fun storage(extensionId: String): Storage = stores.getOrPut(extensionId) { FileMap(File(File(dir, extensionId), "storage.json")) }
    override fun setting(extensionId: String, key: String): String? =
        settings.getOrPut(extensionId) { FileMap(File(File(dir, extensionId), "settings.json")) }.get(key)
}
