package com.music.spotui.extensions

import app.myspoti.extensions.Archive
import app.myspoti.extensions.BlockList
import app.myspoti.extensions.Catalog
import app.myspoti.extensions.CodeDiff
import app.myspoti.extensions.RepoIds
import app.myspoti.extensions.ExtensionException
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.GitHub
import app.myspoti.extensions.Discovery
import app.myspoti.extensions.GitHubRepo
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Origin
import app.myspoti.extensions.Snapshot
import app.myspoti.extensions.UpdateGate
import app.myspoti.host.android.Logbook
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Extensions installed from GitHub: the download (the release's package, else the archive of
 * the tag or the default branch, cut down to a package), the daily update check and its gate
 * ([UpdateGate]: an update that asks nothing new is applied, anything else waits for the user's
 * OK on an "Update needs your OK" card while the old version runs), revert, and the kill switch
 * (the official repository's `blocked.json`, read once a day).
 *
 * The extension's copy on the phone is complete: a repository deleted or unreachable changes
 * nothing to what runs, the extension is only marked "source gone".
 */
object ExtensionUpdates {
    private const val DAY_MS = 24 * 3600 * 1000L
    private const val MAX_TEXT = 512 * 1024

    /** A package downloaded and read, with where it came from on GitHub (null: not from GitHub). */
    class Fetched(val zip: ByteArray, val pkg: ExtensionPackage, val origin: Origin?)

    /** What a check found, for the "Check for updates" button. */
    enum class Result { UP_TO_DATE, APPLIED, WAITING, REPO_CHANGED, NEEDS_OK, DECLINED, SOURCE_GONE, INCOMPATIBLE, BLOCKED, NOT_FROM_GITHUB, FAILED }

    private val _blocks = MutableStateFlow(BlockList.EMPTY)
    val blocks: StateFlow<BlockList> = _blocks.asStateFlow()

    /** The ids being checked now (a spinner on the button). */
    val checking = MutableStateFlow<Set<String>>(emptySet())

    fun blocked(id: String, sha256: String?): Catalog.Block? = _blocks.value.blocks(id, sha256)

    // ── Downloads ──

    /** One answer: [status], the [body] (at most the limit asked), the redirect [location] when not followed, the [finalUrl]. */
    internal class Answer(val status: Int, val body: ByteArray?, val location: String?, val finalUrl: String)

    /** https only, no cookies. [follow]: follow redirects (else [Answer.location] is the target). */
    internal fun get(url: String, max: Int, follow: Boolean = true, accept: String? = null, method: String = "GET", body: String? = null, token: String? = null): Answer {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) throw ExtensionException("an https address is needed")
        if (com.music.spotui.data.DataSaver.active) throw ExtensionException("the data saver is on")
        val c = uri.toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000; c.useCaches = false
        c.instanceFollowRedirects = follow
        c.requestMethod = method
        c.setRequestProperty("User-Agent", "Myspoti-Extensions/1")
        c.setRequestProperty("Cache-Control", "no-cache"); c.setRequestProperty("Pragma", "no-cache")
        accept?.let { c.setRequestProperty("Accept", it) }
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", if (body.startsWith("{")) "application/json" else "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else null
            val bytes = stream?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw ExtensionException("larger than ${max / 1024} KB")
                }
                out.toByteArray()
            }
            return Answer(status, bytes, c.getHeaderField("Location"), c.url.toString())
        } finally {
            c.disconnect()
        }
    }

    /**
     * The newest release's tag, read from GitHub's redirect (no API call), and the repository the
     * redirect points into (another one when it moved); nulls without a release. A missing
     * repository is told by its archive or manifest answering 404, not by this page.
     */
    private fun latestTag(repo: GitHubRepo): Pair<String?, String?> {
        val a = get(repo.latestRelease, MAX_TEXT, follow = false)
        return GitHub.tagFromLocation(a.location) to GitHub.repoOf(a.location)
    }

    /** [ref], with `HEAD` turned into the default branch's name where the host's file addresses need one (Codeberg). */
    private fun concrete(repo: GitHubRepo, ref: String): String {
        if (ref != GitHubRepo.HEAD) return ref
        val api = repo.defaultBranchApi ?: return ref
        return runCatching { get(api, MAX_TEXT).body?.decodeToString()?.let(Discovery::parseDefaultBranch) }.getOrNull() ?: "main"
    }

    /** The numeric ids the host gives [repo] and its owner (null when the host does not answer: nothing is pinned then). */
    internal fun identity(repo: GitHubRepo): RepoIds? = runCatching {
        val a = get(Discovery.identityUrl(repo), MAX_TEXT, accept = if (repo.host == app.myspoti.extensions.CodeHost.GITHUB) "application/vnd.github+json" else null)
        if (a.status in 200..299) a.body?.decodeToString()?.let { Discovery.parseIdentity(repo.host, it) } else null
    }.getOrNull()

    private class SourceGone : ExtensionException("its GitHub repository is gone")

    /**
     * The complete extension from [repo]: at [ref] when given (a tag when [tag]), else the newest
     * release (its [GitHub.ASSET], else its archive), else the default branch's archive. An
     * archive is cut down to the package; [expectSha], when given, must be the download's or the
     * package's sha256.
     */
    suspend fun fromGitHub(repo: GitHubRepo, ref: String? = null, tag: Boolean = false, expectSha: String? = null): Fetched = withContext(Dispatchers.IO) {
        var at = ref
        var isTag = tag
        var movedTo: String? = null
        if (at == null) latestTag(repo).let { (t, r) -> at = t; isTag = t != null; movedTo = r }
        var asset = false
        var answer: Answer? = null
        if (isTag && at != null) {
            answer = repo.releaseAsset(at!!)?.let { get(it, GitHub.MAX_ARCHIVE) }?.takeIf { it.status in 200..299 }
            asset = answer != null
        }
        if (answer == null) {
            answer = get(repo.archive(at ?: concrete(repo, GitHubRepo.HEAD), isTag), GitHub.MAX_ARCHIVE)
            if (answer.status == 404) throw SourceGone()
            if (answer.status !in 200..299) throw ExtensionException("${repo.host.label} answered ${answer.status}")
        }
        val raw = answer.body ?: throw ExtensionException("${repo.host.label} sent nothing")
        val zip = Archive.toPackage(raw)
        if (expectSha != null && ExtensionPackage.sha256(raw) != expectSha.lowercase() && ExtensionPackage.sha256(zip) != expectSha.lowercase())
            throw ExtensionException("the package is not the one the store lists (sha256 differs)")
        val pkg = ExtensionPackage.read(zip)
        val from = movedTo ?: GitHub.repoOf(answer.finalUrl) ?: repo.slug
        val ids = identity(GitHubRepo.parse(from) ?: repo)
        Fetched(zip, pkg, Origin(from, at ?: GitHubRepo.HEAD, isTag, commit = if (asset) null else Archive.commit(raw), asset = asset, repoId = ids?.repo, ownerId = ids?.owner))
    }

    // ── Updates ──

    /** Once at launch, then every few hours while the app runs: the kill switch and the updates that are due. */
    suspend fun daily() {
        while (true) {
            runCatching { refreshBlocks() }.onFailure { Logbook.log("ext: blocked.json not read: ${it.message}") }
            runCatching { checkAll(force = false) }
            delay(6 * 3600 * 1000L)
        }
    }

    /** Every extension from GitHub not checked for a day ([force]: all of them). */
    suspend fun checkAll(force: Boolean) {
        val now = System.currentTimeMillis()
        Extensions.registry.value.extensions.filter { it.origin != null && (force || now - it.lastCheck > DAY_MS) }.forEach { check(it.id, manual = force) }
    }

    /**
     * Looks for a newer version of [id] on GitHub. Not newer, or the one the user declined
     * (unless [manual]): nothing. Newer: downloaded to `staging.zip` and weighed against what
     * the user accepted; asks nothing new: applied (the running one kept to revert to); else it
     * waits there, listed on the card, while the old one keeps running.
     */
    suspend fun check(id: String, manual: Boolean): Result = withContext(Dispatchers.IO) {
        val e = Extensions.registry.value.find(id) ?: return@withContext Result.FAILED
        val origin = e.origin ?: return@withContext Result.NOT_FROM_GITHUB
        val repo = origin.gitHub() ?: return@withContext Result.NOT_FROM_GITHUB
        checking.value = checking.value + id
        try {
            val result = runCatching { checkOnce(e, origin, repo, manual) }.getOrElse { t ->
                if (t is SourceGone) {
                    Extensions.update { r -> r.find(id)?.let { r.put(it.copy(sourceGone = true)) } ?: r }
                    if (!e.sourceGone) Extensions.log(id, "its GitHub repository ${origin.repo} is gone: it keeps running from the copy on the phone")
                    Result.SOURCE_GONE
                } else {
                    Extensions.log(id, "update check failed: ${t.message ?: t}")
                    Result.FAILED
                }
            }
            if (result != Result.FAILED) Extensions.update { r -> r.find(id)?.let { r.put(it.copy(lastCheck = System.currentTimeMillis())) } ?: r }
            result
        } finally {
            checking.value = checking.value - id
        }
    }

    private suspend fun checkOnce(e: InstalledExtension, origin: Origin, repo: GitHubRepo, manual: Boolean): Result {
        val id = e.id
        // Where to look: the newest release when it came from one (or one appeared), else its branch.
        val (tag, movedTo) = latestTag(repo)
        val ref = tag ?: if (origin.tag) GitHubRepo.HEAD else origin.ref
        val manifestAnswer = get(repo.raw(concrete(repo, ref), "manifest.json"), MAX_TEXT)
        if (manifestAnswer.status == 404 && tag == null) throw SourceGone()
        val listed = manifestAnswer.body?.let { runCatching { Manifest.parse(it.decodeToString()) }.getOrNull() }
        val head = tag == null
        if (e.sourceGone) Extensions.update { r -> r.find(id)?.let { r.put(it.copy(sourceGone = false)) } ?: r }
        // A release without a manifest at its root (its package only in the asset) is read in full.
        // A tag is immutable: the same version code there is the same code. A branch can change under the same version.
        if (listed != null && (listed.versionCode < e.versionCode || listed.versionCode == e.versionCode && !head)) return Result.UP_TO_DATE
        val newer = listed != null && listed.versionCode > e.versionCode
        if (newer && !manual && listed!!.versionCode == e.declinedVersionCode) return Result.DECLINED
        if (newer && e.pendingUpdate?.kept?.versionCode == listed!!.versionCode) return Result.NEEDS_OK
        val f = fromGitHub(repo, tag ?: ref, tag = tag != null).let { f -> movedTo?.let { Fetched(f.zip, f.pkg, f.origin?.copy(repo = it)) } ?: f }
        val m = f.pkg.manifest
        if (m.versionCode < e.versionCode || m.versionCode == e.versionCode && (!head || f.pkg.sha256 == e.sha256)) return Result.UP_TO_DATE
        if (!manual && m.versionCode == e.declinedVersionCode && (e.declinedSha == null || e.declinedSha == f.pkg.sha256)) return Result.DECLINED
        if (blocked(m.id, f.pkg.sha256) != null) return Result.BLOCKED
        if (!Catalog.Entry(m.id, m.name, m.version, m.versionCode, m.apiVersion, m.author, url = "", sha256 = "", minAppVersion = m.minAppVersion)
                .compatible(app.myspoti.engine.BuildConfig.VERSION_NAME)) return Result.INCOMPATIBLE
        val running = Extensions.pkg(id)
        var accepted = e.accepted ?: Extensions.manifest(id)?.let { Snapshot.of(it, origin.repo) } ?: return Result.FAILED
        // Installs from before the code and the repository were pinned: pinned now, from what runs and what the host says, without asking.
        if (accepted.scriptSha == null || accepted.repoId == null) {
            val ids = if (accepted.repoId == null) (origin.repoId?.let { RepoIds(it, origin.ownerId.orEmpty()) }?.takeIf { it.owner.isNotEmpty() } ?: identity(repo)) else null
            accepted = accepted.copy(
                scriptSha = accepted.scriptSha ?: running?.script?.let(Snapshot::scriptHash),
                repoId = accepted.repoId ?: ids?.repo, ownerId = accepted.ownerId ?: ids?.owner,
            )
            Extensions.update { r -> r.find(id)?.let { r.put(it.copy(accepted = accepted)) } ?: r }
        }
        val next = Snapshot.of(m, f.origin?.repo, f.pkg.script, f.origin)
        val code = CodeDiff.of(running?.let { mapOf(it.manifest.main to it.script) }.orEmpty(), mapOf(m.main to f.pkg.script))
        val now = System.currentTimeMillis()
        // The user asked now (the button): no wait. Else the clock runs from when this very package was first seen.
        val firstSeen = if (manual) null else if (e.seenSha == f.pkg.sha256 && e.seenAt > 0) e.seenAt else now
        val kept = KeptVersion(m.version, m.versionCode, f.pkg.sha256, f.origin)
        return when (val d = UpdateGate.decide(accepted, next, code, firstSeen, now)) {
            is UpdateGate.Decision.Block -> {
                Extensions.update { r -> r.find(id)?.let { r.put(it.copy(updateBlocked = d.reason, pendingUpdate = null)) } ?: r }
                File(Extensions.dir, "$id/staging.zip").delete()
                Extensions.log(id, "update from ${origin.repo} refused: repository changed owner (${d.reason})")
                Result.REPO_CHANGED
            }
            is UpdateGate.Decision.Wait -> {
                Extensions.update { r -> r.find(id)?.let { r.put(it.copy(updateBlocked = null, seenSha = f.pkg.sha256, seenAt = firstSeen ?: now)) } ?: r }
                Extensions.log(id, "${m.version} seen: applied by itself after 24 h")
                Result.WAITING
            }
            UpdateGate.Decision.Apply -> {
                val plan = updatePlan(m, e, accepted.toManifest()) ?: return Result.UP_TO_DATE
                Extensions.install(f.zip, f.pkg, Grants.replacing(m, plan.refused), e.source, origin = f.origin, accepted = false)
                val pinned = accepted.copy(scriptSha = next.scriptSha ?: accepted.scriptSha)
                Extensions.update { r -> r.find(id)?.let { r.put(it.copy(accepted = pinned, pendingUpdate = null, updateBlocked = null, seenSha = null, seenAt = 0)) } ?: r }
                Extensions.log(id, "updated by itself to ${m.version}: it asks nothing new (${code.lines} lines changed)")
                Result.APPLIED
            }
            is UpdateGate.Decision.Ask -> {
                Extensions.writeBytesAtomically(File(Extensions.dir, "$id/staging.zip"), f.zip)
                Extensions.update { r -> r.find(id)?.let { r.put(it.copy(updateBlocked = null, pendingUpdate = PendingUpdate(kept, d.changes, m.changelog))) } ?: r }
                Extensions.log(id, "${m.version} waits for your OK: ${d.changes.joinToString { "${it.kind.name.lowercase()} ${it.key}" }}")
                Result.NEEDS_OK
            }
        }
    }

    /**
     * [m] as an update of [e]: a newer version code, or (a branch can change under the same version
     * code) the same one with other code. [InstallPlan.of] calls the latter [InstallPlan.NotNewer],
     * which left Accept doing nothing on a same-version code update.
     */
    private fun updatePlan(m: Manifest, e: InstalledExtension, installed: Manifest?): InstallPlan.Update? =
        when (val plan = InstallPlan.of(m, e, installed)) {
            is InstallPlan.Update -> plan
            is InstallPlan.NotNewer -> if (m.versionCode == e.versionCode)
                (InstallPlan.of(m, e.copy(versionCode = e.versionCode - 1), installed) as? InstallPlan.Update)?.copy(from = e) else null
            is InstallPlan.Fresh -> null
        }

    /** The staged update of [id], read and checked against what was staged. */
    private fun staged(id: String): ExtensionPackage? {
        val e = Extensions.registry.value.find(id) ?: return null
        val p = e.pendingUpdate ?: return null
        val bytes = runCatching { File(Extensions.dir, "$id/staging.zip").readBytes() }.getOrNull() ?: return null
        return runCatching { ExtensionPackage.read(bytes) }.getOrNull()?.takeIf { it.sha256 == p.kept.sha256 }
    }

    /** Ids whose Accept runs: a second tap meanwhile does nothing. */
    private val accepting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The user read the card and accepted: the staged version is installed with all it lists
     * granted (but the actions unticked before, still waiting on their own card), and becomes the
     * accepted snapshot. Each outcome goes to the extension's log and the Logbook.
     */
    suspend fun accept(id: String) = withContext(Dispatchers.IO) {
        if (!accepting.add(id)) return@withContext Logbook.log("ext $id: update Accept ignored: already running")
        try {
            val e = Extensions.registry.value.find(id) ?: return@withContext Logbook.log("ext $id: update Accept: not installed")
            val wanted = e.pendingUpdate?.kept?.version
            e.updateBlocked?.let {
                Extensions.log(id, "update to $wanted not accepted: the repository changed owner ($it)")
                throw ExtensionException("update refused: the repository changed owner")
            }
            val p = staged(id) ?: run {
                Extensions.update { r -> r.find(id)?.let { r.put(it.copy(pendingUpdate = null)) } ?: r }
                Extensions.log(id, "update to $wanted not accepted: the staged package is missing or changed")
                throw ExtensionException("the update is no longer on the phone: check again")
            }
            val accepted = e.accepted?.toManifest() ?: Extensions.manifest(id)
            val plan = updatePlan(p.manifest, e, accepted) ?: run {
                Extensions.update { r -> r.find(id)?.let { r.put(it.copy(pendingUpdate = null)) } ?: r }
                File(Extensions.dir, "$id/staging.zip").delete()
                Extensions.log(id, "update to ${p.manifest.version} (${p.manifest.versionCode}) not accepted: not newer than ${e.version} (${e.versionCode})")
                throw ExtensionException("this version is not newer than the one installed: check again")
            }
            val grant = Grants.replacing(p.manifest, plan.refused)
            val zip = File(Extensions.dir, "$id/staging.zip").readBytes()
            runCatching {
                Extensions.install(zip, p, grant, e.source, clashes = Extensions.clashes(p.manifest), origin = e.pendingUpdate?.kept?.origin, accepted = true)
            }.onFailure { Extensions.log(id, "update to ${p.manifest.version} accepted but failed: ${it.message ?: it}") }.getOrThrow()
            Extensions.update { r -> r.find(id)?.let { r.put(it.copy(pendingUpdate = null)) } ?: r }
            Extensions.log(id, "update to ${p.manifest.version} accepted and installed")
        } finally {
            accepting.remove(id)
        }
    }

    /** "Keep current": the staged version is dropped and not offered again (a newer one will be). */
    fun keepCurrent(id: String) {
        val e = Extensions.registry.value.find(id) ?: return
        File(Extensions.dir, "$id/staging.zip").delete()
        Extensions.update { r -> r.find(id)?.let { r.put(it.copy(pendingUpdate = null, declinedVersionCode = e.pendingUpdate?.kept?.versionCode ?: it.declinedVersionCode, declinedSha = e.pendingUpdate?.kept?.sha256 ?: it.declinedSha)) } ?: r }
        Extensions.log(id, "kept ${e.version}: ${e.pendingUpdate?.kept?.version} declined")
    }

    /**
     * Back to the version kept on disk; the one left becomes the kept one (so a second tap undoes
     * it), and it is not applied again by itself.
     */
    suspend fun revert(id: String) = withContext(Dispatchers.IO) {
        val e = Extensions.registry.value.find(id) ?: return@withContext
        val prev = e.previous ?: throw ExtensionException("no previous version on the phone")
        val folder = File(Extensions.dir, id)
        val bytes = runCatching { File(folder, "previous.zip").readBytes() }.getOrElse { throw ExtensionException("the previous version is missing") }
        val p = ExtensionPackage.read(bytes)
        if (p.sha256 != prev.sha256) throw ExtensionException("the previous version changed on the phone")
        blocked(id, p.sha256)?.let { throw ExtensionException("blocked by Myspoti: ${it.reason.ifBlank { "no reason given" }}") }
        val refused = Extensions.manifest(id)?.let { Grants.refused(e, it) }.orEmpty()
        Extensions.stop(id)
        val current = File(folder, "package.zip").readBytes()
        Extensions.writeBytesAtomically(File(folder, "package.zip"), bytes)
        Extensions.writeBytesAtomically(File(folder, "previous.zip"), current)
        Extensions.packages[id] = p
        val now = e.copy(
            version = prev.version, versionCode = prev.versionCode, sha256 = prev.sha256, origin = prev.origin ?: e.origin,
            granted = Grants.replacing(p.manifest, refused),
            previous = KeptVersion(e.version, e.versionCode, e.sha256, e.origin),
            declinedVersionCode = maxOf(e.declinedVersionCode, e.versionCode),
        )
        Extensions.update { it.put(now) }
        Extensions.log(id, "reverted ${e.version} → ${prev.version}")
        Extensions.start(now)
    }

    // ── The kill switch ──

    private val blocksFile get() = File(Extensions.dir, "cache/blocked.json")

    /** The last list read, at launch, before the network answers. */
    fun readCachedBlocks() {
        runCatching { _blocks.value = BlockList.parse(blocksFile.readText()) }
    }

    /** Reads `blocked.json` (once a day; [force]: now) and turns off what it blocks. */
    suspend fun refreshBlocks(force: Boolean = false) = withContext(Dispatchers.IO) {
        val f = blocksFile
        if (force || !f.isFile || System.currentTimeMillis() - f.lastModified() > DAY_MS) {
            val a = get(GitHub.BLOCKED_URL, MAX_TEXT)
            if (a.status != 404 && a.status !in 200..299) throw ExtensionException("GitHub answered ${a.status}")
            val text = a.body?.decodeToString().orEmpty()
            _blocks.value = BlockList.of(a.status, text)
            writeAtomically(f, if (a.status == 404) "{}" else text)
        }
        Extensions.registry.value.extensions.forEach { e ->
            blocked(e.id, e.sha256)?.let { b -> if (e.enabled) Extensions.disable(e.id, "blocked by Myspoti: ${b.reason.ifBlank { "no reason given" }}") }
        }
    }

    // ── A small cache of GitHub answers (the store's search, manifests, ratings) ──

    /**
     * [url]'s body, from the cache when younger than [maxAgeMs], else fetched and kept; null on 404.
     * [force] (Refresh) skips the cache and reports a failure instead of falling back to the old copy.
     */
    internal fun cached(url: String, maxAgeMs: Long, max: Int = MAX_TEXT, force: Boolean = false): String? {
        val f = File(Extensions.dir, "cache/" + ExtensionPackage.sha256(url.toByteArray()).take(24))
        if (!force && f.isFile && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return f.readText().ifEmpty { null }
        val a = runCatching { get(url, max, accept = if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else null) }
            .getOrElse { if (f.isFile && !force) return f.readText().ifEmpty { null } else throw it }
        if (a.status == 404) { writeAtomically(f, ""); return null }
        if (a.status !in 200..299) {
            // A rate limit (403 / 429) keeps the old copy even on Refresh: hosts allow few searches a minute without an account.
            val limited = a.status == 403 || a.status == 429
            if (f.isFile && (!force || limited)) return f.readText().ifEmpty { null }
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "the host"
            throw ExtensionException("$host answered ${a.status}" + if (limited) " (too many requests: try again in a few minutes)" else "")
        }
        val text = a.body?.decodeToString().orEmpty()
        writeAtomically(f, text)
        return text
    }
}
