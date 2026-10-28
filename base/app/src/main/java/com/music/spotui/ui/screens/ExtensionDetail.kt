package com.music.spotui.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import app.myspoti.extensions.Ask
import app.myspoti.extensions.Setting
import app.myspoti.extensions.Store
import app.myspoti.extensions.StoreAction
import com.music.spotui.extensions.ExtensionStore
import com.music.spotui.extensions.ExtensionUpdates
import com.music.spotui.extensions.Extensions
import com.music.spotui.extensions.Grants
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * "by <author> · repository by <owner>": the manifest's author is the package's own claim, so it
 * is never shown alone as if verified (a package from a file or an address is "not verified").
 */
@Composable
internal fun authorLine(author: String, repo: String?): String {
    val owner = repo?.let { app.myspoti.extensions.GitHubRepo.parse(it)?.owner }
    return if (owner != null) stringResource(R.string.update_author_owner, author, owner) else stringResource(R.string.update_author_unverified, author)
}

/** Updates from the repository are refused: its host ids changed (re-registered or transferred). */
@Composable
internal fun RepoChangedWarning() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Warning.copy(alpha = 0.10f)).padding(12.dp)) {
        Text(stringResource(R.string.update_repo_changed_title), color = Warning, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.update_repo_changed_body), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Icon, name, author, version and category: the top of both detail pages. */
@Composable
private fun Header(id: String, name: String, author: String, repo: String?, version: String, category: app.myspoti.extensions.Category, iconUrl: String?, iconBytes: ByteArray?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        ExtIcon(id, name, iconUrl, iconBytes, 72.dp)
        Column(Modifier.padding(start = 16.dp)) {
            Text(name, color = AppText, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(authorLine(author, repo), color = AppMuted, fontSize = 13.sp, maxLines = 1)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VersionPill(version)
                ExtPill(stringResource(categoryLabel(category)), AppAccent, AppAccent.copy(alpha = 0.15f))
            }
        }
    }
}

/** Its actions as icons with the author's reasons, critical ones first and highlighted; the warning when there is one. */
@Composable
private fun ActionsList(asks: List<Ask>) {
    ExtSection(R.string.actions_title)
    Column(Modifier.padding(horizontal = 20.dp)) { asks.sortedByDescending { it.critical }.forEach { ActionRow(it) } }
    if (asks.any { it.critical }) Text(stringResource(R.string.actions_critical_warning), color = Warning, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(10.dp)).background(Warning.copy(alpha = 0.10f)).padding(12.dp))
}

@Composable
private fun Line(title: String, detail: String, color: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(title, color = color, fontSize = 14.sp)
        if (detail.isNotBlank()) Text(detail, color = AppMuted, fontSize = 12.sp)
    }
}

@Composable
private fun Detail(label: Int, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp)) {
        Text(stringResource(label), color = AppMuted, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(value, color = AppText, fontSize = 12.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Tags(tags: List<String>) {
    if (tags.isEmpty()) return
    FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { ExtPill("#$it", AppMuted, tone(0xFF2A2A2A)) }
    }
}

@Composable
private fun HomepageButton(url: String?) {
    val ctx = LocalContext.current
    url?.takeIf { it.startsWith("https://") }?.let {
        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.extension_homepage), color = AppAccent)
        }
    }
}

// ── A repository's extension, before it is installed ──

@Composable
fun ExtensionEntryScreen(navController: NavController, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry by Extensions.registry.collectAsState()
    val repos by ExtensionStore.repos.collectAsState()
    val busy by ExtensionStore.busy.collectAsState()
    val ratings by ExtensionStore.ratings.collectAsState()
    val downloads by ExtensionStore.downloads.collectAsState()
    LaunchedEffect(registry.repositories) { ExtensionStore.refresh(registry.repositories) }
    val found = Store.newest(ExtensionStore.read())[id]

    Page(null, navController, title = found?.second?.name ?: id) {
        if (found == null) {
            Text(stringResource(R.string.extension_not_listed), color = AppMuted, modifier = Modifier.padding(20.dp))
            return@Page
        }
        val (repo, e) = found
        val installed = registry.find(id)
        val action = Store.action(e, installed?.versionCode, appVersion)
        val m = e.preview()
        Header(e.id, e.name, e.author, e.repo, e.version, e.category(), iconOf(repo, e), installed?.let { Extensions.pkg(id)?.icon })
        if (e.repo != null) {
            LaunchedEffect(e.repo) { ExtensionStore.loadDownloads(e.repo!!) }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                RatingLine(e.repo?.let { ratings[it] })
                e.repo?.let { downloads[it] }?.let { Text("  ·  " + stringResource(R.string.extension_downloads, it.toString()), color = AppMuted, fontSize = 11.sp) }
            }
            RateButtons(e.repo!!)
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            if (action == StoreAction.INCOMPATIBLE) Text(incompatibleLine(e), color = Warning, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            if (action == StoreAction.UPDATE && installed != null) Text(stringResource(R.string.extension_update_from, installed.version, e.version), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            StateButton(action, id in busy, large = true,
                onInstall = { scope.launch { installFromStore(ctx, repo, e) } },
                onOpen = { navController.navigate(installedRoute(id)) })
        }
        if (e.description.isNotBlank()) Text(e.description, color = AppText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Tags(e.tags)
        if (e.changelog.isNotBlank()) {
            ExtSection(R.string.extension_changelog)
            Text(e.changelog, color = AppText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
        incompatibleWith(e, registry)?.let { Text(it, color = Warning, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        ActionsList(m.asks())
        ExtDivider()
        ExtSection(R.string.extension_details)
        e.updatedAt?.let { Detail(R.string.extension_detail_updated, it.take(10)) }
        e.minAppVersion?.let { Detail(R.string.extension_detail_min_app, it) }
        if (e.size > 0) Detail(R.string.extension_detail_size, "${(e.size + 1023) / 1024} KB")
        // What the package will be checked against when it is downloaded (the rest is on the store's list and, once installed, here).
        if (e.sha256.isNotBlank()) Detail(R.string.extension_detail_sha, e.sha256.take(16) + "…", mono = true)
        Row(Modifier.padding(horizontal = 12.dp)) {
            HomepageButton(e.homepage)
            ReportButton(e.id, e.version, e.repo, e.sha256.ifBlank { null })
        }
        Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace()))
    }
    InstallPrompt()
}

// ── One installed extension's page ──

@Composable
fun ExtensionScreen(navController: NavController, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry by Extensions.registry.collectAsState()
    val errors by Extensions.errors.collectAsState()
    val statuses by Extensions.statuses.collectAsState()
    val logTick by Extensions.logTick.collectAsState()
    val repos by ExtensionStore.repos.collectAsState()
    val busy by ExtensionStore.busy.collectAsState()
    val checking by ExtensionUpdates.checking.collectAsState()
    LaunchedEffect(registry.repositories) { ExtensionStore.refresh(registry.repositories) }
    val e = registry.find(id)
    val pkg = remember(id, e?.sha256) { Extensions.pkg(id) }
    val m = pkg?.manifest
    val pickFile = rememberPackagePicker()
    var uninstall by remember { mutableStateOf(false) }
    val enable = rememberEnabler()

    Page(null, navController, title = e?.name ?: id) {
        if (e == null || m == null) {
            Text(stringResource(R.string.extensions_none), color = AppMuted, modifier = Modifier.padding(20.dp))
            return@Page
        }
        Header(e.id, e.name, e.author, e.origin?.repo, e.version, m.category(), null, pkg.icon)
        if (e.updateBlocked != null || ExtensionStore.identityChanged(e)) RepoChangedWarning()

        if (e.sourceGone) Text(stringResource(R.string.extension_source_gone), color = Warning, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Warning.copy(alpha = 0.10f)).padding(12.dp))
        // A GitHub update that asks more than the user accepted: what it changes, Accept / Keep current.
        // Unticked actions left from before installs granted everything: paused until one OK.
        val refused = Grants.refused(e, m)
        if (refused.isNotEmpty()) RefusedNeedsOk(m.asks().filter { it.key in refused }) {
            scope.launch { Extensions.acceptAll(id); enable(id, true) }
        }
        e.pendingUpdate?.let { p -> UpdateNeedsOk(e.name, e.version, p, onAccept = { scope.launch { runCatching { ExtensionUpdates.accept(id) }.onFailure { toastError(ctx, it) } } },
            onKeep = { ExtensionUpdates.keepCurrent(id) }) }

        // An update in a repository: its button right here.
        val listed = Store.newest(ExtensionStore.read())[id]
        if (listed != null && listed.second.versionCode > e.versionCode && e.pendingUpdate == null && e.origin == null) {
            val (repo, entry) = listed
            val action = Store.action(entry, e.versionCode, appVersion)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(AppAccent.copy(alpha = 0.10f)).padding(12.dp),
            ) {
                Text(stringResource(R.string.extension_update_from, e.version, entry.version), color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (entry.changelog.isNotBlank()) Text(entry.changelog, color = AppMuted, fontSize = 12.sp, maxLines = 4, modifier = Modifier.padding(top = 2.dp))
                if (action == StoreAction.INCOMPATIBLE) Text(incompatibleLine(entry), color = Warning, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                StateButton(action, id in busy, onInstall = { scope.launch { installFromStore(ctx, repo, entry) } }, onOpen = {})
            }
        }

        // One state: why it is off, inactive, its last error, else whether it runs.
        val state = stateLine(e, errors[id], statuses[id])
        SwitchRow(
            stringResource(R.string.extension_enabled),
            state?.first ?: stringResource(if (Extensions.isRunning(id)) R.string.extension_running else R.string.extension_not_running),
            e.enabled,
            detailColor = state?.second ?: AppMuted,
        ) { enable(id, it) }
        if (m.description.isNotBlank()) Text(m.description, color = AppText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Tags(m.tags)
        if (m.changelog.isNotBlank()) {
            ExtSection(R.string.extension_changelog)
            Text(m.changelog, color = AppText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
        ExtDivider()

        if (Extensions.hasPage(id)) OutlinedButton(onClick = { navController.navigate(extensionPageRoute(id)) }, border = BorderStroke(1.dp, AppAccent.copy(alpha = 0.6f)),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { Text(stringResource(R.string.ext_open_page), color = AppAccent) }
        ExtSection(R.string.actions_title)
        Column(Modifier.padding(horizontal = 20.dp)) { m.asks().sortedByDescending { it.critical }.forEach { a -> ActionRow(a) } }
        // Writes allowed for good from a confirmation ("Always"): each one revocable here.
        if (e.rawGrants.isNotEmpty()) {
            Text(stringResource(R.string.ext_grants_title), color = AppText, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
            Text(stringResource(R.string.ext_grants_note), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
            e.rawGrants.forEach { g ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.toString(), color = AppText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    TextButton(onClick = { Extensions.revokeRawGrant(id, g) }) { Text(stringResource(R.string.ext_grant_revoke), color = Warning) }
                }
            }
        }
        if (m.settings.isNotEmpty()) {
            ExtDivider()
            ExtSection(R.string.extension_settings)
            m.settings.forEach { s -> SettingEditor(id, s) }
        }
        ExtDivider()
        ExtSection(R.string.extension_details)
        Detail(R.string.extension_detail_id, e.id, mono = true)
        Detail(R.string.extension_detail_source, e.source)
        e.origin?.let { o ->
            Detail(R.string.extension_detail_github, o.repo, mono = true)
            Detail(R.string.extension_detail_ref, o.label(), mono = true)
        }
        Detail(R.string.extension_detail_sha, e.sha256.take(16) + "…", mono = true)
        Row(Modifier.padding(horizontal = 12.dp)) {
            if (e.origin != null) TextButton(enabled = id !in checking, onClick = {
                scope.launch {
                    val r = ExtensionUpdates.check(id, manual = true)
                    android.widget.Toast.makeText(ctx, ctx.getString(checkResult(r)), android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.extension_check_updates), color = AppAccent) }
            e.previous?.let { prev ->
                TextButton(onClick = {
                    scope.launch {
                        runCatching { ExtensionUpdates.revert(id) }
                            .onSuccess { android.widget.Toast.makeText(ctx, ctx.getString(R.string.extension_reverted, prev.version), android.widget.Toast.LENGTH_SHORT).show() }
                            .onFailure { toastError(ctx, it) }
                    }
                }) { Text(stringResource(R.string.extension_revert, prev.version), color = AppAccent) }
            }
            ReportButton(e.id, e.version, e.origin?.repo, e.sha256)
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            HomepageButton(m.homepage)
            if (listed != null) TextButton(onClick = { navController.navigate(storeRoute(id)) }) { Text(stringResource(R.string.extension_see_in_store), color = AppAccent) }
            TextButton(onClick = pickFile) { Text(stringResource(R.string.extension_update_file), color = AppAccent) }
        }
        OutlinedButton(onClick = { uninstall = true }, border = BorderStroke(1.dp, Warning.copy(alpha = 0.6f)), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.extension_uninstall), color = Warning)
        }
        ExtDivider()

        ExtSection(R.string.extension_log)
        val lines = remember(id, logTick) { Extensions.logLines(id) }
        if (lines.isEmpty()) Text(stringResource(R.string.extension_log_empty), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
        lines.takeLast(150).asReversed().forEach {
            Text(it, color = AppMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 20.dp, vertical = 1.dp))
        }
        if (lines.isNotEmpty()) TextButton(onClick = { Extensions.clearLog(id) }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(stringResource(R.string.extension_log_clear), color = AppAccent)
        }
        Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace()))
    }

    if (uninstall && e != null) AlertDialog(
        onDismissRequest = { uninstall = false },
        containerColor = tone(0xFF282828),
        text = { Text(stringResource(R.string.extension_uninstall_confirm, e.name), color = AppText) },
        confirmButton = {
            TextButton(onClick = {
                uninstall = false
                // Back out only if the page is still the one on top (the uninstall takes a moment).
                val page = navController.currentBackStackEntry
                scope.launch {
                    Extensions.uninstall(id)
                    if (navController.currentBackStackEntry == page) navController.popBackStack()
                }
            }) { Text(stringResource(R.string.extension_uninstall), color = Warning) }
        },
        dismissButton = { TextButton(onClick = { uninstall = false }) { Text(stringResource(R.string.extensions_cancel), color = AppText) } },
    )
    InstallPrompt()
}

/** An option of the extension (manifest `settings`) as text: what the user set, else its default. */
internal fun settingInitial(id: String, s: Setting): String {
    val stored = Extensions.settingValues(id)[s.key]
    return (stored?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() } ?: s.default)
        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
}

/** Saves [v] as JSON, typed as the option is; a number that is not one saves nothing. */
internal fun saveSetting(id: String, s: Setting, v: String) {
    val json = when (s.type) {
        "toggle" -> JsonPrimitive(v == "true").toString()
        "number" -> v.toLongOrNull()?.let { JsonPrimitive(it).toString() } ?: v.toDoubleOrNull()?.let { JsonPrimitive(it).toString() }
        else -> JsonPrimitive(v).toString()
    }
    Extensions.setSetting(id, s.key, json)
}

/** One of the extension's options (manifest `settings`), saved as JSON as it changes. */
@Composable
private fun SettingEditor(id: String, s: Setting) {
    var value by remember(id, s.key) { mutableStateOf(settingInitial(id, s)) }
    fun save(v: String) {
        value = v
        saveSetting(id, s, v)
    }
    when (s.type) {
        "toggle" -> SwitchRow(s.label, s.description, value == "true") { save(it.toString()) }
        "button" -> Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            OutlinedButton(onClick = { Extensions.pressSetting(id, s.key) }, border = BorderStroke(1.dp, AppAccent.copy(alpha = 0.6f))) { Text(s.label, color = AppAccent) }
            if (s.description.isNotBlank()) Text(s.description, color = AppMuted, fontSize = 12.sp)
        }
        "choice" -> Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(s.label, color = AppText, fontSize = 16.sp)
            if (s.description.isNotBlank()) Text(s.description, color = AppMuted, fontSize = 12.sp)
            s.options.forEach { o ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().toggleable(value == o, role = Role.RadioButton) { save(o) }) {
                    RadioButton(selected = value == o, onClick = null)
                    Text(o, color = AppText, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        else -> Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = ::save,
                label = { Text(s.label) },
                singleLine = true,
                visualTransformation = if (s.type == "secret") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = when (s.type) { "number" -> KeyboardType.Number; "secret" -> KeyboardType.Password; else -> KeyboardType.Text }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (s.description.isNotBlank()) Text(s.description, color = AppMuted, fontSize = 12.sp)
        }
    }
}

// ── GitHub: the update card, report, rating ──

/** The toast after "Check for updates". */
internal fun checkResult(r: ExtensionUpdates.Result): Int = when (r) {
    ExtensionUpdates.Result.UP_TO_DATE, ExtensionUpdates.Result.NOT_FROM_GITHUB -> R.string.extension_check_up_to_date
    ExtensionUpdates.Result.APPLIED -> R.string.extension_check_applied
    ExtensionUpdates.Result.WAITING -> R.string.update_check_waiting
    ExtensionUpdates.Result.REPO_CHANGED -> R.string.update_check_repo_changed
    ExtensionUpdates.Result.NEEDS_OK -> R.string.extension_check_needs_ok
    ExtensionUpdates.Result.DECLINED -> R.string.extension_check_declined
    ExtensionUpdates.Result.SOURCE_GONE -> R.string.extension_source_gone
    ExtensionUpdates.Result.INCOMPATIBLE -> R.string.extension_check_incompatible
    ExtensionUpdates.Result.BLOCKED -> R.string.extension_check_blocked
    ExtensionUpdates.Result.FAILED -> R.string.extension_check_failed
}

/**
 * "Update needs your OK": a newer version asks what the user has not accepted. Only what changes
 * is listed, with its icon and the author's (new) reason, then the one tick before Accept; the
 * old version runs until then.
 * A protected surface, like the install prompt.
 */
@Composable
internal fun UpdateNeedsOk(name: String, from: String, p: com.music.spotui.extensions.PendingUpdate, onAccept: () -> Unit, onKeep: () -> Unit) {
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.myspoti.extensions.ProtectedSurface.INSTALL)
    val critical = p.changes.any { it.critical }
    val tint = if (critical) Warning else AppAccent
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.10f)).padding(12.dp)) {
        // Only the code changed (in a package holding powerful rights): Update / Not now, no risk tick.
        val codeOnly = p.changes.all { it.kind == app.myspoti.extensions.Change.Kind.CODE }
        val code = p.changes.firstOrNull { it.kind == app.myspoti.extensions.Change.Kind.CODE }
        if (codeOnly && code != null) {
            Text(stringResource(R.string.update_code_title, name, code.added + code.removed, code.added, code.removed), color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.update_code_body), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        } else {
            Text(stringResource(R.string.extension_update_needs_ok, from, p.kept.version), color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.extension_update_needs_ok_body), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        }
        p.changes.forEach { c -> ChangeRow(c) }
        if (p.changelog.isNotBlank()) Text(p.changelog, color = AppMuted, fontSize = 12.sp, maxLines = 4, modifier = Modifier.padding(top = 4.dp))
        if (critical && !codeOnly) Text(stringResource(R.string.actions_critical_warning), color = Warning, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        var risk by remember(p) { mutableStateOf(false) }
        if (!codeOnly) RiskBox(risk) { risk = it }
        AcceptRow(
            accept = stringResource(if (codeOnly) R.string.update_code_update else R.string.extension_update_accept),
            enabled = risk || codeOnly,
            tint = tint,
            onAccept = onAccept,
            keep = stringResource(if (codeOnly) R.string.update_code_later else R.string.extension_update_keep),
            onKeep = onKeep,
        )
    }
}

/**
 * Accept as a filled button, there before the box is ticked (greyed, with what to tick), and the
 * other choice beside it. Reported: a greyed text-only Accept was not seen as a button at all.
 */
@Composable
private fun AcceptRow(accept: String, enabled: Boolean, tint: Color, onAccept: () -> Unit, keep: String? = null, onKeep: () -> Unit = {}) {
    if (!enabled) Text(stringResource(R.string.extension_accept_tick_first), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Button(
            onClick = onAccept,
            enabled = enabled,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.Black, disabledContainerColor = AppMuted.copy(alpha = 0.25f), disabledContentColor = AppText.copy(alpha = 0.6f)),
            modifier = Modifier.weight(1f),
        ) { Text(accept, fontWeight = FontWeight.SemiBold) }
        if (keep != null) androidx.compose.material3.OutlinedButton(onClick = onKeep, modifier = Modifier.weight(1f)) { Text(keep, color = AppText) }
    }
}

/**
 * An extension installed when the prompt let the user untick actions, and some were: it is paused
 * until the user accepts all it lists, once, with the same tick as the install prompt.
 */
@Composable
internal fun RefusedNeedsOk(refused: List<Ask>, onAccept: () -> Unit) {
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.myspoti.extensions.ProtectedSurface.INSTALL)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Warning.copy(alpha = 0.10f)).padding(12.dp)) {
        Text(stringResource(R.string.extension_refused_title), color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.extension_refused_body), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        refused.sortedByDescending { it.critical }.forEach { ActionRow(it) }
        if (refused.any { it.critical }) Text(stringResource(R.string.actions_critical_warning), color = Warning, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        var risk by remember { mutableStateOf(false) }
        RiskBox(risk) { risk = it }
        AcceptRow(stringResource(R.string.extension_update_accept), enabled = risk, tint = Warning, onAccept = onAccept)
    }
}

@Composable
private fun ChangeRow(c: app.myspoti.extensions.Change) {
    val action = c.action
    val ask = app.myspoti.extensions.Ask(if (c.kind == app.myspoti.extensions.Change.Kind.HOST && c.key == "*") app.myspoti.extensions.Action.NETWORK_ANY else action?.key ?: c.key,
        if (c.critical) app.myspoti.extensions.Level.CRITICAL else app.myspoti.extensions.Level.NORMAL, c.why, action = action)
    val label = action?.let { stringResource(com.music.spotui.extensions.askLabel(ask)) } ?: c.key
    val title = when (c.kind) {
        app.myspoti.extensions.Change.Kind.ACTION -> stringResource(R.string.extension_change_action, label)
        app.myspoti.extensions.Change.Kind.HOST -> stringResource(R.string.extension_change_host, if (c.key == "*") stringResource(R.string.extension_change_any_host) else c.key)
        app.myspoti.extensions.Change.Kind.WRITE -> stringResource(R.string.extension_change_write, c.key)
        app.myspoti.extensions.Change.Kind.EXCLUSIVE -> stringResource(R.string.extension_change_exclusive, label)
        app.myspoti.extensions.Change.Kind.WHY -> stringResource(R.string.extension_change_why, label)
        app.myspoti.extensions.Change.Kind.CODE -> stringResource(R.string.update_code_files, c.key.ifBlank { "main" } + " (+${c.added}/−${c.removed})")
        app.myspoti.extensions.Change.Kind.REPO -> stringResource(R.string.extension_change_repo, c.key, c.before)
        app.myspoti.extensions.Change.Kind.AUTHOR -> stringResource(R.string.extension_change_author, c.key, c.before)
        app.myspoti.extensions.Change.Kind.REPLACES -> stringResource(R.string.extension_change_replaces, c.key)
        app.myspoti.extensions.Change.Kind.CONFLICTS -> stringResource(R.string.extension_change_conflicts, c.key)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ActionTile(ask, 32.dp)
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = if (c.critical) Warning else AppText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (c.why.isNotBlank()) Text("“${c.why}”", color = AppText, fontSize = 12.sp)
            if (c.kind == app.myspoti.extensions.Change.Kind.WHY && c.before.isNotBlank()) Text(stringResource(R.string.extension_change_was, c.before), color = AppMuted, fontSize = 11.sp)
        }
    }
}

/** Opens a prefilled report on the official repository in the browser (no account in the app). */
@Composable
private fun ReportButton(id: String, version: String, repo: String?, sha256: String?) {
    val ctx = LocalContext.current
    TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.myspoti.extensions.GitHub.reportUrl(id, version, repo, sha256)))) } }) {
        Text(stringResource(R.string.extension_report), color = Warning)
    }
}

/** 👍 / 👎 on the extension's ratings issue; hidden while the GitHub OAuth app is not set ([app.myspoti.extensions.GitHubAuth.enabled]). */
@Composable
internal fun RateButtons(repo: String) {
    if (!app.myspoti.extensions.GitHubAuth.enabled) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ratings by ExtensionStore.ratings.collectAsState()
    val signIn by ExtensionStore.signIn.collectAsState()
    if ((ratings[repo]?.issue ?: 0) <= 0) return
    fun rate(up: Boolean) = scope.launch {
        runCatching { ExtensionStore.rate(repo, up) }
            .onSuccess { android.widget.Toast.makeText(ctx, ctx.getString(R.string.extension_rated), android.widget.Toast.LENGTH_SHORT).show() }
            .onFailure { toastError(ctx, it) }
    }
    Row(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { rate(true) }) { Text("👍  " + stringResource(R.string.extension_rate_up), color = AppAccent) }
        TextButton(onClick = { rate(false) }) { Text("👎  " + stringResource(R.string.extension_rate_down), color = AppMuted) }
    }
    signIn?.let { code ->
        AlertDialog(
            onDismissRequest = { ExtensionStore.signIn.value = null },
            containerColor = tone(0xFF282828),
            title = { Text(stringResource(R.string.extension_signin_title), color = AppText) },
            text = {
                Column {
                    Text(stringResource(R.string.extension_signin_body, code.verificationUri), color = AppMuted, fontSize = 13.sp)
                    Text(code.userCode, color = AppText, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 10.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri))) } }) {
                    Text(stringResource(R.string.extension_signin_open), color = AppAccent)
                }
            },
            dismissButton = { TextButton(onClick = { ExtensionStore.signIn.value = null }) { Text(stringResource(R.string.extensions_cancel), color = AppText) } },
        )
    }
}
