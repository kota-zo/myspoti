/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import java.io.File

/** Desktop-only cache. Timing comparisons of planners must not re-run the neural model for every pair. */
internal object BenchCache {
    class Result(val analysis: TrackAnalysis, val cached: Boolean, val elapsedMs: Double)

    fun analysis(dir: File, name: String, fresh: Boolean = false): Result {
        val t0 = System.nanoTime()
        val source = File(dir, "$name.f32")
        require(source.isFile) { "missing audio: ${source.name}" }
        val modelFile = System.getenv("AUTOMIX_MODEL")?.let(::File)
        val stamp = "${TrackAnalysis.VERSION}|${source.length()}|${source.lastModified()}|${modelFile?.length()}|${modelFile?.lastModified()}"
        val cache = File(dir, ".automix-bench").apply { mkdirs() }
        val data = File(cache, "$name.analysis")
        val identity = File(cache, "$name.stamp")
        if (!fresh && identity.isFile && runCatching { identity.readText() == stamp }.getOrDefault(false)) {
            val a = runCatching { data.inputStream().buffered().use { TrackAnalysis.read(it) } }.getOrNull()
            if (a != null) return Result(a, true, (System.nanoTime() - t0) / 1e6)
        }
        val (mid, side) = loadPcm(source)
        val a = Analyzer.analyze(mid, side, model, threads = System.getenv("AUTOMIX_THREADS")?.toInt() ?: 2)
        val tmp = File(cache, "$name.tmp")
        tmp.outputStream().buffered().use(a::write)
        check(tmp.renameTo(data)) { "cannot save bench analysis" }
        identity.writeText(stamp)
        return Result(a, false, (System.nanoTime() - t0) / 1e6)
    }
}
