package com.music.spotui.ui.immersive.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import app.myspoti.extensions.Category
import app.myspoti.extensions.Setting
import app.myspoti.extensions.Store
import app.myspoti.extensions.StoreAction
import com.music.spotui.extensions.ExtensionStore
import com.music.spotui.extensions.ExtensionUpdates
import com.music.spotui.extensions.Extensions
import com.music.spotui.extensions.Grants
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.screens.ActionRow
import com.music.spotui.ui.screens.ExtIcon
import com.music.spotui.ui.screens.ExtPill
import com.music.spotui.ui.screens.InstallPrompt
import com.music.spotui.ui.screens.RateButtons
import com.music.spotui.ui.screens.RatingLine
import com.music.spotui.ui.screens.RefusedNeedsOk
import com.music.spotui.ui.screens.RepoChangedWarning
import com.music.spotui.ui.screens.Tags
import com.music.spotui.ui.screens.UpdateNeedsOk
import com.music.spotui.ui.screens.VersionPill
import com.music.spotui.ui.screens.Warning
import com.music.spotui.ui.screens.appVersion
import com.music.spotui.ui.screens.authorLine
import com.music.spotui.ui.screens.categoryLabel
import com.music.spotui.ui.screens.checkResult
import com.music.spotui.ui.screens.extensionPageRoute
import com.music.spotui.ui.screens.iconOf
import com.music.spotui.ui.screens.incompatibleLine
import com.music.spotui.ui.screens.incompatibleWith
import com.music.spotui.ui.screens.installFromStore
import com.music.spotui.ui.screens.installedRoute
import com.music.spotui.ui.screens.rememberEnabler
import com.music.spotui.ui.screens.rememberPackagePicker
import com.music.spotui.ui.screens.saveSetting
import com.music.spotui.ui.screens.settingInitial
import com.music.spotui.ui.screens.stateLine
import com.music.spotui.ui.screens.storeRoute
import com.music.spotui.ui.screens.toastError
import kotlinx.coroutines.launch

/** The sides of a Classic card (Warning or accent tint) placed on this page: its own 16 dp plus 4 dp make the page gutter. */
private val CardInset = Modifier.padding(horizontal = 4.dp)

/** Icon, author, version and category: the top of both detail pages (the name is the page's title). */
@Composable
private fun ExtHeader(id: String, name: String, author: String, repo: String?, version: String, category: Category, iconUrl: String?, iconBytes: ByteArray?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter, vertical = 8.dp)) {
        ExtIcon(id, name, iconUrl, iconBytes, 72.dp)
        Column(Modifier.padding(start = 16.dp)) {
            Text(authorLine(author, repo), style = Immersive.Subhead, maxLines = 2)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VersionPill(version)
                ExtPill(stringResource(categoryLabel(category)), Immersive.Accent, Immersive.Accent.copy(alpha = 0.15f))
            }
        }
    }
}

/** A label and its value, the value in monospace for ids and hashes. */
@Composable
private fun DetailRow(label: Int, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(stringResource(label), style = Immersive.Caption, modifier = Modifier.width(100.dp))
        Text(
            value,
            style = Immersive.Caption.copy(color = Immersive.Text, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A note in a tinted rounded card (a warning, an update on offer), on the page's gutters. */
@Composable
private fun TintedCard(tint: Color, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter, vertical = 6.dp)
            .clip(Immersive.Card).background(tint.copy(alpha = 0.10f)).padding(14.dp),
    ) { content() }
}

private fun openLink(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** A repository's extension before it is installed, in the Immersive look; the Classic `ExtensionEntryScreen`'s data and actions. */
@Composable
fun ImmersiveExtensionEntryScreen(navController: NavController, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry by Extensions.registry.collectAsState()
    val repos by ExtensionStore.repos.collectAsState()
    val busy by ExtensionStore.busy.collectAsState()
    val ratings by ExtensionStore.ratings.collectAsState()
    val downloads by ExtensionStore.downloads.collectAsState()
    LaunchedEffect(registry.repositories) { ExtensionStore.refresh(registry.repositories) }
    val found = Store.newest(ExtensionStore.read())[id]

    ImmersivePage(title = found?.second?.name ?: id, onBack = { navController.popBackStack() }) {
        if (found == null) {
            immersiveBlock("missing") { Text(stringResource(R.string.extension_not_listed), style = Immersive.Subhead) }
            return@ImmersivePage
        }
        val (repo, e) = found
        val installed = registry.find(id)
        val action = Store.action(e, installed?.versionCode, appVersion)
        val m = e.preview()
        item(key = "ext:header") {
            ExtHeader(e.id, e.name, e.author, e.repo, e.version, e.category(), iconOf(repo, e), installed?.let { Extensions.pkg(id)?.icon })
        }
        if (e.repo != null) item(key = "ext:rating") {
            LaunchedEffect(e.repo) { ExtensionStore.loadDownloads(e.repo!!) }
            Column(Modifier.padding(horizontal = Immersive.Gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RatingLine(e.repo?.let { ratings[it] })
                    e.repo?.let { downloads[it] }?.let {
                        Text("  ·  " + stringResource(R.string.extension_downloads, it.toString()), style = Immersive.Caption.copy(fontSize = 11.sp))
                    }
                }
            }
        }
        // The buttons keep their own 12 dp, 8 dp more make the gutter.
        if (e.repo != null) item(key = "ext:rate") { Box(Modifier.padding(horizontal = 8.dp)) { RateButtons(e.repo!!) } }
        item(key = "ext:state") {
            Column(Modifier.padding(horizontal = Immersive.Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (action == StoreAction.INCOMPATIBLE) Text(incompatibleLine(e), style = Immersive.Subhead.copy(color = Warning))
                if (action == StoreAction.UPDATE && installed != null) Text(stringResource(R.string.extension_update_from, installed.version, e.version), style = Immersive.Caption)
                ImmersiveStateButton(action, id in busy, large = true,
                    onInstall = { scope.launch { installFromStore(ctx, repo, e) } },
                    onOpen = { navController.navigate(installedRoute(id)) })
            }
        }
        if (e.description.isNotBlank()) immersiveBlock("description") { Text(e.description, style = Immersive.Body) }
        item(key = "ext:tags") { Tags(e.tags) }
        if (e.changelog.isNotBlank()) immersiveGroup("changelog", headerRes = R.string.extension_changelog) {
            row { Text(e.changelog, style = Immersive.Subhead.copy(color = Immersive.Text), modifier = Modifier.padding(16.dp)) }
        }
        item(key = "ext:clash") {
            incompatibleWith(e, registry)?.let { Text(it, style = Immersive.Subhead.copy(color = Warning), modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 6.dp)) }
        }
        val asks = m.asks().sortedByDescending { it.critical }
        immersiveGroup("actions", headerRes = R.string.actions_title) {
            asks.forEach { a -> row { Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { ActionRow(a) } } }
        }
        if (asks.any { it.critical }) immersiveBlock("critical") {
            Text(stringResource(R.string.actions_critical_warning), style = Immersive.Caption.copy(color = Warning),
                modifier = Modifier.fillMaxWidth().clip(Immersive.Card).background(Warning.copy(alpha = 0.10f)).padding(12.dp))
        }
        immersiveGroup("details", headerRes = R.string.extension_details) {
            e.updatedAt?.let { row { DetailRow(R.string.extension_detail_updated, it.take(10)) } }
            e.minAppVersion?.let { row { DetailRow(R.string.extension_detail_min_app, it) } }
            if (e.size > 0) row { DetailRow(R.string.extension_detail_size, "${(e.size + 1023) / 1024} KB") }
            // What the package will be checked against when it is downloaded (the rest is on the store's list and, once installed, here).
            if (e.sha256.isNotBlank()) row { DetailRow(R.string.extension_detail_sha, e.sha256.take(16) + "…", mono = true) }
        }
        immersiveGroup("links") {
            e.homepage?.takeIf { it.startsWith("https://") }?.let { url ->
                row { ImmersiveGroupRow(stringResource(R.string.extension_homepage), chevron = true, onClick = { openLink(ctx, url) }) }
            }
            row {
                ImmersiveGroupRow(stringResource(R.string.extension_report), chevron = true, onClick = {
                    openLink(ctx, app.myspoti.extensions.GitHub.reportUrl(e.id, e.version, e.repo, e.sha256.ifBlank { null }))
                })
            }
        }
    }
    InstallPrompt()
}

/** One installed extension's page in the Immersive look; the Classic `ExtensionScreen`'s data and actions. */
@Composable
fun ImmersiveExtensionScreen(navController: NavController, id: String) {
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
    val lines = remember(id, logTick) { Extensions.logLines(id) }

    ImmersivePage(title = e?.name ?: id, onBack = { navController.popBackStack() }) {
        if (e == null || m == null) {
            immersiveBlock("none") { Text(stringResource(R.string.extensions_none), style = Immersive.Subhead) }
            return@ImmersivePage
        }
        item(key = "ext:header") { ExtHeader(e.id, e.name, e.author, e.origin?.repo, e.version, m.category(), null, pkg.icon) }
        if (e.updateBlocked != null || ExtensionStore.identityChanged(e)) item(key = "ext:repo-changed") { Box(CardInset) { RepoChangedWarning() } }
        if (e.sourceGone) item(key = "ext:source-gone") {
            TintedCard(Warning) { Text(stringResource(R.string.extension_source_gone), style = Immersive.Caption.copy(color = Warning)) }
        }
        // A GitHub update that asks more than the user accepted: what it changes, Accept / Keep current.
        // Unticked actions left from before installs granted everything: paused until one OK.
        val refused = Grants.refused(e, m)
        if (refused.isNotEmpty()) item(key = "ext:refused") {
            Box(CardInset) {
                RefusedNeedsOk(m.asks().filter { it.key in refused }) {
                    scope.launch { Extensions.acceptAll(id); enable(id, true) }
                }
            }
        }
        e.pendingUpdate?.let { p ->
            item(key = "ext:pending") {
                Box(CardInset) {
                    UpdateNeedsOk(e.name, e.version, p, onAccept = { scope.launch { runCatching { ExtensionUpdates.accept(id) }.onFailure { toastError(ctx, it) } } },
                        onKeep = { ExtensionUpdates.keepCurrent(id) })
                }
            }
        }

        // An update in a repository: its button right here.
        val listed = Store.newest(ExtensionStore.read())[id]
        if (listed != null && listed.second.versionCode > e.versionCode && e.pendingUpdate == null && e.origin == null) item(key = "ext:update") {
            val (repo, entry) = listed
            val action = Store.action(entry, e.versionCode, appVersion)
            TintedCard(Immersive.Accent) {
                Text(stringResource(R.string.extension_update_from, e.version, entry.version), style = Immersive.BodyStrong)
                if (entry.changelog.isNotBlank()) Text(entry.changelog, style = Immersive.Caption, maxLines = 4, modifier = Modifier.padding(top = 2.dp))
                if (action == StoreAction.INCOMPATIBLE) Text(incompatibleLine(entry), style = Immersive.Caption.copy(color = Warning), modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                ImmersiveStateButton(action, id in busy, onInstall = { scope.launch { installFromStore(ctx, repo, entry) } }, onOpen = {})
            }
        }

        // One state: why it is off, inactive, its last error, else whether it runs.
        immersiveGroup("enabled") {
            row {
                val state = stateLine(e, errors[id], statuses[id])
                ImmersiveSwitchRow(
                    stringResource(R.string.extension_enabled), e.enabled, { enable(id, it) },
                    detail = state?.first ?: stringResource(if (Extensions.isRunning(id)) R.string.extension_running else R.string.extension_not_running),
                    detailColor = state?.second ?: Immersive.TextSecondary,
                )
            }
            if (Extensions.hasPage(id)) row {
                ImmersiveGroupRow(stringResource(R.string.ext_open_page), chevron = true, onClick = { navController.navigate(extensionPageRoute(id)) })
            }
        }
        if (m.description.isNotBlank()) immersiveBlock("description") { Text(m.description, style = Immersive.Body) }
        item(key = "ext:tags") { Tags(m.tags) }
        if (m.changelog.isNotBlank()) immersiveGroup("changelog", headerRes = R.string.extension_changelog) {
            row { Text(m.changelog, style = Immersive.Subhead.copy(color = Immersive.Text), modifier = Modifier.padding(16.dp)) }
        }

        immersiveGroup("actions", headerRes = R.string.actions_title) {
            m.asks().sortedByDescending { it.critical }.forEach { a -> row { Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { ActionRow(a) } } }
        }
        // Writes allowed for good from a confirmation ("Always"): each one revocable here.
        if (e.rawGrants.isNotEmpty()) immersiveGroup("grants", headerRes = R.string.ext_grants_title, footerRes = R.string.ext_grants_note) {
            e.rawGrants.forEach { g ->
                row {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g.toString(), style = Immersive.Caption.copy(color = Immersive.Text, fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                        TextButton(onClick = { Extensions.revokeRawGrant(id, g) }) {
                            Text(stringResource(R.string.ext_grant_revoke), style = Immersive.Subhead.copy(color = Warning))
                        }
                    }
                }
            }
        }
        if (m.settings.isNotEmpty()) immersiveGroup("settings", headerRes = R.string.extension_settings) {
            m.settings.forEach { s -> row { Column { com.music.spotui.ui.screens.networkEditors(id, s).forEach { SettingRow(id, it) } } } }
        }

        immersiveGroup("details", headerRes = R.string.extension_details) {
            row { DetailRow(R.string.extension_detail_id, e.id, mono = true) }
            row { DetailRow(R.string.extension_detail_source, e.source) }
            e.origin?.let { o ->
                row { DetailRow(R.string.extension_detail_github, o.repo, mono = true) }
                row { DetailRow(R.string.extension_detail_ref, o.label(), mono = true) }
            }
            row { DetailRow(R.string.extension_detail_sha, e.sha256.take(16) + "…", mono = true) }
        }
        immersiveGroup("manage") {
            if (e.origin != null) row {
                ImmersiveGroupRow(stringResource(R.string.extension_check_updates), enabled = id !in checking, chevron = true, onClick = {
                    scope.launch {
                        val r = ExtensionUpdates.check(id, manual = true)
                        android.widget.Toast.makeText(ctx, ctx.getString(checkResult(r)), android.widget.Toast.LENGTH_SHORT).show()
                    }
                })
            }
            e.previous?.let { prev ->
                row {
                    ImmersiveGroupRow(stringResource(R.string.extension_revert, prev.version), chevron = true, onClick = {
                        scope.launch {
                            runCatching { ExtensionUpdates.revert(id) }
                                .onSuccess { android.widget.Toast.makeText(ctx, ctx.getString(R.string.extension_reverted, prev.version), android.widget.Toast.LENGTH_SHORT).show() }
                                .onFailure { toastError(ctx, it) }
                        }
                    })
                }
            }
            m.homepage?.takeIf { it.startsWith("https://") }?.let { url ->
                row { ImmersiveGroupRow(stringResource(R.string.extension_homepage), chevron = true, onClick = { openLink(ctx, url) }) }
            }
            if (listed != null) row {
                ImmersiveGroupRow(stringResource(R.string.extension_see_in_store), chevron = true, onClick = { navController.navigate(storeRoute(id)) })
            }
            row { ImmersiveGroupRow(stringResource(R.string.extension_update_file), chevron = true, onClick = pickFile) }
            row {
                ImmersiveGroupRow(stringResource(R.string.extension_report), chevron = true, onClick = {
                    openLink(ctx, app.myspoti.extensions.GitHub.reportUrl(e.id, e.version, e.origin?.repo, e.sha256))
                })
            }
            row { ImmersiveGroupRow(stringResource(R.string.extension_uninstall), destructive = true, onClick = { uninstall = true }) }
        }

        immersiveGroup("log", headerRes = R.string.extension_log) {
            row {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (lines.isEmpty()) Text(stringResource(R.string.extension_log_empty), style = Immersive.Caption)
                    lines.takeLast(150).asReversed().forEach {
                        Text(it, style = Immersive.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
            if (lines.isNotEmpty()) row {
                ImmersiveGroupRow(stringResource(R.string.extension_log_clear), onClick = { Extensions.clearLog(id) })
            }
        }
    }

    if (uninstall && e != null) AlertDialog(
        onDismissRequest = { uninstall = false },
        containerColor = Immersive.Raised,
        text = { Text(stringResource(R.string.extension_uninstall_confirm, e.name), style = Immersive.Body) },
        confirmButton = {
            TextButton(onClick = {
                uninstall = false
                // Back out only if the page is still the one on top (the uninstall takes a moment).
                val page = navController.currentBackStackEntry
                scope.launch {
                    Extensions.uninstall(id)
                    if (navController.currentBackStackEntry == page) navController.popBackStack()
                }
            }) { Text(stringResource(R.string.extension_uninstall), style = Immersive.BodyStrong.copy(color = Immersive.Destructive)) }
        },
        dismissButton = {
            TextButton(onClick = { uninstall = false }) {
                Text(stringResource(R.string.extensions_cancel), style = Immersive.BodyStrong.copy(color = Immersive.Accent))
            }
        },
    )
    InstallPrompt()
}

/**
 * An extension's settings announced in Settings → Playback or Downloads (manifest `section`), as a
 * card under a discreet [header] ("<name> settings"), drawn as on its page.
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.extensionSectionGroup(p: com.music.spotui.extensions.SectionPlacement, header: String) =
    immersiveGroup("ext-section:${p.extensionId}", header = header) {
        p.settings.forEach { s -> row { Column { com.music.spotui.ui.screens.networkEditors(p.extensionId, s).forEach { SettingRow(p.extensionId, it) } } } }
    }

/** One of the extension's options (manifest `settings`), saved as JSON as it changes (see `saveSetting`). */
@Composable
private fun SettingRow(id: String, s: Setting) {
    var value by remember(id, s.key) { mutableStateOf(settingInitial(id, s)) }
    fun save(v: String) {
        value = v
        saveSetting(id, s, v)
    }
    when (s.type) {
        "toggle" -> ImmersiveSwitchRow(s.label, value == "true", { save(it.toString()) }, detail = s.description)
        "button" -> ImmersiveGroupRow(s.label, detail = s.description.ifBlank { null }, chevron = true, onClick = { Extensions.pressSetting(id, s.key) })
        "choice" -> Column {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(s.label, style = Immersive.Body)
                if (s.description.isNotBlank()) Text(s.description, style = Immersive.Caption)
            }
            s.options.forEach { o -> ImmersiveChoiceRow(o, selected = value == o, onClick = { save(o) }) }
        }
        else -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(s.label, style = Immersive.Caption)
            Box(
                Modifier.padding(top = 6.dp).fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), Immersive.Button)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = ::save,
                    singleLine = true,
                    textStyle = Immersive.Body,
                    cursorBrush = SolidColor(Immersive.Accent),
                    visualTransformation = if (s.type == "secret") PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = when (s.type) { "number" -> KeyboardType.Number; "secret" -> KeyboardType.Password; else -> KeyboardType.Text }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (s.description.isNotBlank()) Text(s.description, style = Immersive.Caption, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
