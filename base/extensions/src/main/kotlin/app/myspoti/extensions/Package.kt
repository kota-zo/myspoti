package app.myspoti.extensions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An extension as it travels: a zip with `manifest.json`, its script and, if it likes, an
 * icon (`icon.png`, or the PNG the manifest's `icon` names: square, 256 px or less) and a
 * `LICENSE`. Nothing else is read.
 */
class ExtensionPackage(val manifest: Manifest, val script: String, val icon: ByteArray?, val sha256: String) {
    /** The same package with its manifest's texts in [tag] ([Manifest.localized]). */
    fun localized(tag: String): ExtensionPackage = manifest.localized(tag).let { if (it === manifest) this else ExtensionPackage(it, script, icon, sha256) }

    companion object {
        const val MAX_ZIP = 2 * 1024 * 1024
        const val MAX_SCRIPT = 1024 * 1024
        const val MAX_ICON = 256 * 1024
        private val KEPT = setOf("manifest.json", "icon.png", "LICENSE", "LICENSE.txt", "README.md")

        /** Reads and checks a package. Throws [ExtensionException] with the reason it is refused. */
        fun read(zip: ByteArray): ExtensionPackage {
            if (zip.size > MAX_ZIP) throw ExtensionException("package larger than ${MAX_ZIP / 1024 / 1024} MB")
            val files = HashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                var count = 0
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++count > 30) throw ExtensionException("package has more than 30 files")
                    if (e.isDirectory) continue
                    val name = e.name
                    if (name.contains("..") || name.startsWith("/") || name.contains('\\')) throw ExtensionException("package path not allowed: $name")
                    files[name] = z.readLimited(MAX_SCRIPT + 1)
                }
            }
            val manifestBytes = files["manifest.json"] ?: throw ExtensionException("no manifest.json at the root of the package")
            val manifest = runCatching { Manifest.parse(manifestBytes.decodeToString()) }
                .getOrElse { throw ExtensionException("manifest.json: ${it.message?.lineSequence()?.firstOrNull()}") }
            manifest.problems().takeIf { it.isNotEmpty() }?.let { throw ExtensionException("manifest.json: " + it.joinToString("; ")) }
            val script = files[manifest.main] ?: throw ExtensionException("${manifest.main} (main) is not in the package")
            if (script.size > MAX_SCRIPT) throw ExtensionException("${manifest.main} larger than 1 MB")
            val iconName = manifest.icon ?: "icon.png"
            if (manifest.icon != null && iconName !in files) throw ExtensionException("$iconName (icon) is not in the package")
            val icon = files[iconName]?.also { if (it.size > MAX_ICON) throw ExtensionException("$iconName larger than 256 KB") }
            (files.keys - KEPT - manifest.main - iconName).takeIf { it.isNotEmpty() }?.let { throw ExtensionException("files not used by Myspoti in the package: ${it.joinToString()}") }
            return ExtensionPackage(manifest, script.decodeToString(), icon, sha256(zip))
        }

        /**
         * Builds the package from an extension's folder, the same bytes every time for the same
         * files (fixed order and dates), so its sha256 can be checked by anyone who builds it.
         */
        fun pack(dir: File): ByteArray {
            val manifest = Manifest.parse(File(dir, "manifest.json").readText())
            val names = (listOf("manifest.json", manifest.main) + listOfNotNull(manifest.icon) + KEPT.filter { File(dir, it).isFile }).distinct()
            return zip(names.associateWith { name -> File(dir, name).takeIf { it.isFile }?.readBytes() ?: throw ExtensionException("$name is missing in ${dir.path}") })
        }

        /**
         * The package made of the files at the root of an extension's folder, given as [files]
         * (name → bytes): the manifest, its script, its icon and the [KEPT] files; anything else
         * (a repository's README images, workflows, sources) is left out. Same bytes as [pack].
         */
        fun packFiles(files: Map<String, ByteArray>): ByteArray {
            val manifestBytes = files["manifest.json"] ?: throw ExtensionException("no manifest.json at the root of the repository")
            val manifest = runCatching { Manifest.parse(manifestBytes.decodeToString()) }
                .getOrElse { throw ExtensionException("manifest.json: ${it.message?.lineSequence()?.firstOrNull()}") }
            val names = (listOf("manifest.json", manifest.main) + listOfNotNull(manifest.icon) + KEPT.filter { it in files }).distinct()
            return zip(names.associateWith { files[it] ?: throw ExtensionException("$it is missing in the repository") })
        }

        private fun zip(files: Map<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                for ((name, bytes) in files) {
                    z.putNextEntry(ZipEntry(name).apply { time = 315532800000L })
                    z.write(bytes)
                    z.closeEntry()
                }
            }
            return out.toByteArray()
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun ZipInputStream.readLimited(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) break
            }
            return out.toByteArray()
        }
    }
}
