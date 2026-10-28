package com.music.spotui.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import app.myspoti.extensions.Catalog
import app.myspoti.extensions.Category
import app.myspoti.extensions.Action
import app.myspoti.extensions.Area
import app.myspoti.extensions.mode
import com.music.spotui.extensions.actionIcon
import com.music.spotui.extensions.actionLabel
import com.music.spotui.extensions.areaIcon
import com.music.spotui.extensions.areaLabel
import app.myspoti.extensions.Store
import app.myspoti.extensions.StoreAction
import com.music.spotui.extensions.ExtensionStore
import com.music.spotui.extensions.Extensions
import com.music.spotui.extensions.InstalledExtension
import com.music.spotui.ui.components.SearchClearButton
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.launch

internal fun installedRoute(id: String) = "${Routes.Extension.route}/${Uri.encode(id)}"
internal fun storeRoute(id: String) = "${Routes.ExtensionEntry.route}/${Uri.encode(id)}"

// ── The list: Store and Installed tabs, search, categories ──

@Composable
fun ExtensionsScreen(navController: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry by Extensions.registry.collectAsState()
    val errors by Extensions.errors.collectAsState()
    val statuses by Extensions.statuses.collectAsState()
    val signIns by Extensions.signIns.collectAsState()
    val repos by ExtensionStore.repos.collectAsState()
    val busy by ExtensionStore.busy.collectAsState()
    val pickFile = rememberPackagePicker()
    val enable = rememberEnabler()
    var askUrl by remember { mutableStateOf(false) }
    var askRepo by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(if (registry.extensions.isEmpty()) 0 else 1) }
    var query by rememberSaveable { mutableStateOf("") }
    var categoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val category = categoryKey?.let { Category.of(it) }

    LaunchedEffect(registry.repositories) { ExtensionStore.refresh(registry.repositories) }
    val catalogs = repos.values.mapNotNull { r -> r.catalog?.let { r.url to it } }
    val installedCodes = registry.extensions.associate { it.id to it.versionCode }
    // Updates in a repository, and GitHub updates waiting for the user's OK.
    val updates = Store.updates(catalogs, installedCodes, appVersion) + registry.extensions.filter { it.pendingUpdate != null }.map { it.id }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val ratings by ExtensionStore.ratings.collectAsState()
    val blocks by com.music.spotui.extensions.ExtensionUpdates.blocks.collectAsState()

    // The store's lists as the tab shows them, and the categories that have something in them.
    val officialShown = remember(repos) { ExtensionStore.officialShown() }
    val storeSources = ExtensionStore.sources(registry.repositories).filter { !ExtensionStore.isOfficial(it) || officialShown }
    val discovered = remember(repos, ratings, blocks, showAll) { ExtensionStore.discovered(showAll) }
    val present = if (tab == 0) (storeSources.flatMap { repos[it]?.catalog?.extensions.orEmpty() } + discovered.map { it.item }).map { it.category() }.toSet()
        else registry.extensions.mapNotNull { Extensions.manifest(it.id)?.category() }.toSet()

    Page(R.string.extensions_title, navController, top = {
        Tabs(tab, updates.size) { tab = it }
    }) {
        SearchField(query) { query = it }
        CategoryChips(Category.entries.filter { it in present || it == category }, category) { categoryKey = it?.key }
        if (downloading) Text(stringResource(R.string.extensions_loading), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))

        if (tab == 0) {
            if (registry.repositories.none { !ExtensionStore.isOfficial(it) } && !officialShown) EmptyState()
            var shown = 0
            storeSources.forEach { url ->
                val repo = repos[url] ?: ExtensionStore.Repo(url, loading = true)
                RepoHeader(repo, onRefresh = { ExtensionStore.refresh(registry.repositories, force = true) }, onRemove = { Extensions.removeRepository(url) })
                repo.catalog?.extensions?.filter { Store.matches(it, query, category) }?.forEach { entry ->
                    shown++
                    StoreRow(url, entry, Store.action(entry, installedCodes[entry.id], appVersion), entry.id in busy, registry,
                        onOpen = { navController.navigate(storeRoute(entry.id)) },
                        onInstall = { scope.launch { installFromStore(ctx, url, entry) } },
                        onInstalled = { navController.navigate(installedRoute(entry.id)) })
                }
            }
            // Discovered on GitHub: by score, then the most recently updated; the hidden ones behind "Show all".
            val github = repos[app.myspoti.extensions.Discovery.SOURCE]
            if (github != null) {
                GitHubHeader(github, onRefresh = { ExtensionStore.discover(force = true) })
                discovered.filter { Store.matches(it.item, query, category) }.forEach { r ->
                    val entry = r.item
                    shown++
                    StoreRow(app.myspoti.extensions.Discovery.SOURCE, entry, Store.action(entry, installedCodes[entry.id], appVersion), entry.id in busy, registry,
                        onOpen = { navController.navigate(storeRoute(entry.id)) },
                        onInstall = { scope.launch { installFromStore(ctx, app.myspoti.extensions.Discovery.SOURCE, entry) } },
                        onInstalled = { navController.navigate(installedRoute(entry.id)) },
                        rating = r.rating, hidden = app.myspoti.extensions.Ranking.hidden(r, blocks, System.currentTimeMillis()) != null)
                }
                val hidden = remember(repos, ratings, blocks) { ExtensionStore.hiddenCount() }
                if (hidden > 0 || showAll) TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(if (showAll) stringResource(R.string.extensions_show_less) else stringResource(R.string.extensions_show_all, hidden), color = AppAccent)
                }
            }
            if (shown == 0 && catalogs.any { it.second.extensions.isNotEmpty() }) Text(stringResource(R.string.extensions_no_match), color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
            ExtDivider()
            ExtSection(R.string.extensions_other_ways)
            Text(stringResource(R.string.extensions_repository_warning), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
            InstallButtons(onRepo = { askRepo = true }, onFile = pickFile, onUrl = { askUrl = true })
        } else {
            val matches = { e: InstalledExtension ->
                val m = Extensions.manifest(e.id)
                (category == null || m?.category() == category) &&
                    query.lowercase().split(' ').filter { it.isNotBlank() }.all { w -> listOf(e.name, e.author, m?.description.orEmpty(), e.id, m?.tags.orEmpty().joinToString(" ")).any { it.lowercase().contains(w) } }
            }
            if (registry.extensions.isEmpty()) {
                Text(stringResource(R.string.extensions_none), color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                OutlinedButton(onClick = { tab = 0 }, modifier = Modifier.padding(horizontal = 20.dp), border = BorderStroke(1.dp, AppMuted.copy(alpha = 0.6f))) {
                    Text(stringResource(R.string.extensions_browse_store), color = AppText)
                }
            }
            // A source that said its account needs a sign-in: its page, where the extension offers it.
            signIns.forEach { (id, detail) ->
                val name = registry.find(id)?.name ?: id
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.extension_sign_in_needed, name), color = AppText, fontSize = 14.sp)
                    if (detail.isNotBlank()) Text(detail, color = AppMuted, fontSize = 12.sp)
                    Row {
                        OutlinedButton(onClick = { navController.navigate(installedRoute(id)) }, border = BorderStroke(1.dp, AppMuted.copy(alpha = 0.6f))) {
                            Text(stringResource(R.string.extension_sign_in), color = AppText)
                        }
                        androidx.compose.material3.TextButton(onClick = { Extensions.dismissSignIn(id) }) { Text(stringResource(R.string.extension_sign_in_later), color = AppMuted) }
                    }
                }
            }
            // By area: the extensions acting there, in order; an area without any is not listed.
            val grouped = remember(registry) { Extensions.byArea() }
            grouped.forEach { (area, list) ->
                val shown = list.filter(matches)
                if (shown.isNotEmpty()) AreaSection(area, shown, errors, statuses, updates, enable,
                    onOpen = { navController.navigate(installedRoute(it)) })
            }
            ExtDivider()
            ExtSection(R.string.extensions_other_ways)
            InstallButtons(onRepo = null, onFile = pickFile, onUrl = { askUrl = true })
        }
        Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace()))
    }

    if (askUrl) UrlDialog(R.string.extensions_install_url, R.string.extensions_url_hint, onDismiss = { askUrl = false }) { url ->
        askUrl = false
        downloading = true
        scope.launch {
            // A GitHub repository's address: its newest release (or default branch), kept on the phone and updated from there.
            // (GitHub, GitLab or Codeberg; `gitlab:group/name` and `codeberg:owner/name` work too.)
            val gitHub = app.myspoti.extensions.GitHubRepo.parse(url)?.takeIf { !url.endsWith(".zip") }
            runCatching {
                if (gitHub != null) com.music.spotui.extensions.ExtensionUpdates.fromGitHub(gitHub)
                else Extensions.downloadPackage(url).let { (zip, p) -> com.music.spotui.extensions.ExtensionUpdates.Fetched(zip, p, null) }
            }
                .onSuccess { f -> offer(ctx, f.zip, f.origin?.page() ?: url, f.origin) }
                .onFailure { toastError(ctx, it) }
            downloading = false
        }
    }
    if (askRepo) UrlDialog(R.string.extensions_repository_add, R.string.extensions_repository_hint, warning = R.string.extensions_repository_warning, onDismiss = { askRepo = false }) { url ->
        askRepo = false
        Extensions.addRepository(url)
        tab = 0
    }
    InstallPrompt()
}

/** "Store" and "Installed", the latter with the number of updates waiting. */
@Composable
private fun Tabs(selected: Int, updates: Int, onSelect: (Int) -> Unit) {
    if (com.music.spotui.ui.navigation.isImmersive()) {
        val installed = stringResource(R.string.extensions_installed)
        com.music.spotui.ui.immersive.pages.ImmersiveSegmentedControl(
            options = listOf(stringResource(R.string.extensions_tab_store), if (updates > 0) "$installed ($updates)" else installed),
            selectedIndex = selected, onSelect = onSelect,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        listOf(R.string.extensions_tab_store, R.string.extensions_installed).forEachIndexed { i, label ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }.padding(top = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(label), color = if (i == selected) AppText else AppMuted, fontSize = 15.sp, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal)
                    if (i == 1 && updates > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(updates.toString(), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(AppAccent).padding(horizontal = 6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(2.dp).background(if (i == selected) AppAccent else Color.Transparent))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tone(0xFF2A2A2A)))
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    if (com.music.spotui.ui.navigation.isImmersive()) {
        com.music.spotui.ui.immersive.components.ImmersiveSearchField(
            query = query, onQueryChange = onChange, onSubmit = {},
            placeholder = stringResource(R.string.extensions_search),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        return
    }
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text(stringResource(R.string.extensions_search), color = AppMuted) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = AppMuted) },
        trailingIcon = if (query.isEmpty()) null else { { SearchClearButton(focus, AppMuted) { onChange("") } } },
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = AppText, unfocusedTextColor = AppText, focusedBorderColor = AppAccent, unfocusedBorderColor = tone(0xFF3E3E3E),
            focusedContainerColor = tone(0xFF1E1E1E), unfocusedContainerColor = tone(0xFF1E1E1E), cursorColor = AppAccent,
        ),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).heightIn(min = 48.dp).focusRequester(focus),
    )
}

/** "All" and the [categories] that have an extension in the tab. */
@Composable
private fun CategoryChips(categories: List<Category>, selected: Category?, onSelect: (Category?) -> Unit) {
    if (categories.isEmpty()) return
    val immersive = com.music.spotui.ui.navigation.isImmersive()
    val chipOn = if (immersive) com.music.spotui.ui.immersive.Immersive.Accent else AppAccent
    val chipOff = if (immersive) com.music.spotui.ui.immersive.Immersive.Raised else tone(0xFF2A2A2A)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf<Category?>(null) + categories).forEach { c ->
            val on = c == selected
            Text(stringResource(categoryLabel(c)), color = if (on) (if (immersive) Color.White else Color.Black) else AppText, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) chipOn else chipOff)
                    .clickable { onSelect(if (on) null else c) }.padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}

/** No repository yet: what extensions are, before anything is added. */
@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.extensions_empty_title), color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.extensions_empty_body), color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A repository: its name, address and how much it is trusted; refresh and remove. */
@Composable
private fun RepoHeader(repo: ExtensionStore.Repo, onRefresh: () -> Unit, onRemove: () -> Unit) {
    val official = ExtensionStore.isOfficial(repo.url)
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(repo.catalog?.name ?: runCatching { java.net.URI(repo.url).host }.getOrNull() ?: repo.url, color = AppText, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    if (official) ExtPill(stringResource(R.string.extensions_official_badge), Color.Black, AppAccent)
                    else ExtPill(stringResource(R.string.extensions_repo_yours), Warning, Warning.copy(alpha = 0.15f))
                }
                Text(repo.url, color = AppMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (official) R.string.extensions_repo_official_note else R.string.extensions_repo_yours_note), color = if (official) AppMuted else Warning, fontSize = 11.sp)
            }
            if (repo.loading) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), color = AppAccent, strokeWidth = 2.dp)
            else IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.extensions_repository_refresh), tint = AppMuted) }
            if (!official) IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, stringResource(R.string.extensions_repository_remove), tint = AppMuted) }
        }
        repo.error?.let { Text(stringResource(R.string.extensions_repo_error, it), color = Warning, fontSize = 12.sp) }
        if (repo.catalog?.extensions?.isEmpty() == true) Text(stringResource(R.string.extensions_repo_empty), color = AppMuted, fontSize = 12.sp)
    }
}

/** The GitHub discovery's header: what it is (not reviewed), refresh. */
@Composable
private fun GitHubHeader(repo: ExtensionStore.Repo, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.extensions_github_title), color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    ExtPill(stringResource(R.string.extensions_repo_community), Warning, Warning.copy(alpha = 0.15f))
                }
                Text(stringResource(R.string.extensions_github_note), color = Warning, fontSize = 11.sp)
            }
            if (repo.loading) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), color = AppAccent, strokeWidth = 2.dp)
            else IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.extensions_repository_refresh), tint = AppMuted) }
        }
        repo.error?.let { Text(stringResource(R.string.extensions_repo_error, it), color = Warning, fontSize = 12.sp) }
    }
}

/** "★ 4.2 · 35 votes" (a Bayesian score: see [app.myspoti.extensions.Ranking]); greyed when hidden behind "Show all". */
@Composable
internal fun RatingLine(rating: app.myspoti.extensions.Rating?, hidden: Boolean = false) {
    val text = if (rating == null || rating.votes == 0) stringResource(R.string.extension_rating_none)
        else stringResource(R.string.extension_rating, "%.1f".format(app.myspoti.extensions.Ranking.stars(rating)), rating.votes)
    Text(text, color = if (hidden) Warning else AppMuted, fontSize = 11.sp, maxLines = 1)
}

/** A store row: icon, name, version, one line, the rights, and the one state button. */
@Composable
private fun StoreRow(
    repo: String, e: Catalog.Entry, action: StoreAction, busy: Boolean, registry: com.music.spotui.extensions.Registry,
    onOpen: () -> Unit, onInstall: () -> Unit, onInstalled: () -> Unit, rating: app.myspoti.extensions.Rating? = null, hidden: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        ExtIcon(e.id, e.name, iconOf(repo, e), null)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                VersionPill(e.version)
            }
            if (action == StoreAction.INCOMPATIBLE) Text(incompatibleLine(e), color = Warning, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(e.description.ifBlank { authorLine(e.author, e.repo) }, color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            incompatibleWith(e, registry)?.let { Text(it, color = Warning, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (e.repo != null) RatingLine(rating, hidden)
            ActionIcons(e.preview().asks())
        }
        StateButton(action, busy, onInstall = onInstall, onOpen = onInstalled)
    }
}

@Composable
internal fun incompatibleLine(e: Catalog.Entry): String =
    if (e.apiVersion > app.myspoti.extensions.API_VERSION) stringResource(R.string.extensions_requires_api, e.apiVersion)
    else stringResource(R.string.extensions_requires, e.minAppVersion.orEmpty())

/** "Incompatible with X (installed)": the entry, or an installed extension, lists the other in conflictsWith or replaces. */
@Composable
internal fun incompatibleWith(e: Catalog.Entry, registry: com.music.spotui.extensions.Registry): String? {
    val other = registry.extensions.firstOrNull { i ->
        i.id != e.id && (i.id in e.conflictsWith || i.id in e.replaces || Extensions.manifest(i.id)?.conflictsWith?.contains(e.id) == true)
    } ?: return null
    return stringResource(if (other.id in e.replaces) R.string.ext_clash_replaces else R.string.ext_clash_conflict, other.name)
}

/**
 * One area of the Installed tab: its icon and name, the extensions acting there in the user's
 * order (arrows to reorder, "Active" / "Idle" for an exclusive action), and who did what lately,
 * with one-tap turn off.
 */
@Composable
private fun AreaSection(
    area: Area, list: List<InstalledExtension>, errors: Map<String, String>, statuses: Map<String, app.myspoti.extensions.ExtensionStatus>, updates: Set<String>, enable: (String, Boolean) -> Unit, onOpen: (String) -> Unit) {
    val activity by Extensions.activity.state.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp)) {
        Icon(areaIcon(area), null, tint = AppAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(areaLabel(area)), color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
    // The exclusive actions of this area: who acts now.
    val exclusive = Action.entries.filter { it.area == area && it.mode == app.myspoti.extensions.Mode.EXCLUSIVE }
    list.forEachIndexed { i, e ->
        val m = remember(e.id, e.sha256) { Extensions.manifest(e.id) }
        InstalledRow(e, errors[e.id], statuses[e.id], e.id in updates, enable, onOpen = { onOpen(e.id) }) {
            exclusive.filter { m?.actionSet()?.contains(it) == true }.forEach { a ->
                val active = Extensions.activeFor(a) == e.id
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)
                    .clickable(enabled = !active && e.enabled) { Extensions.setActive(a, e.id) }) {
                    Icon(actionIcon(a), null, tint = if (active) AppAccent else AppMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (active) stringResource(R.string.area_active) else stringResource(R.string.area_idle) + " · " + stringResource(R.string.area_make_active, stringResource(actionLabel(a)).lowercase()),
                        color = if (active) AppAccent else AppMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Sources are ordered per action, in Settings (see the hint under them).
            if (list.size >= 2 && area != Area.AUDIO) Row {
                IconButton(onClick = { Extensions.movePriority(area, e.id, -1) }, enabled = i > 0, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.extensions_move_up), tint = if (i > 0) AppText else AppMuted.copy(alpha = 0.3f))
                }
                IconButton(onClick = { Extensions.movePriority(area, e.id, 1) }, enabled = i < list.lastIndex, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.extensions_move_down), tint = if (i < list.lastIndex) AppText else AppMuted.copy(alpha = 0.3f))
                }
            }
        }
    }
    if (area == Area.AUDIO && list.size >= 2) Text(stringResource(R.string.sources_order_hint), color = AppMuted, fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    // Who did what here lately.
    val recent = remember(activity, area) { activity.filter { it.area == area }.takeLast(3).asReversed() }
    if (recent.isNotEmpty()) {
        Text(stringResource(R.string.area_activity), color = AppMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
        recent.forEach { a ->
            val on = Extensions.registry.value.find(a.extensionId)?.enabled == true
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp)) {
                Text(a.text, color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(android.text.format.DateUtils.getRelativeTimeSpanString(a.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(),
                    color = AppMuted.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
                if (on) androidx.compose.material3.TextButton(onClick = { enable(a.extensionId, false) }) { Text(stringResource(R.string.area_turn_off), color = Warning, fontSize = 12.sp) }
            }
        }
    }
}

/** An installed row: icon (a dot when it failed), name, version, its state or description, its action icons, the switch; [extra] under it. */
@Composable
private fun InstalledRow(e: InstalledExtension, error: String?, status: app.myspoti.extensions.ExtensionStatus?, update: Boolean, enable: (String, Boolean) -> Unit, onOpen: () -> Unit, extra: @Composable () -> Unit = {}) {
    val pkg = remember(e.id, e.sha256) { Extensions.pkg(e.id) }
    val m = pkg?.manifest
    val state = stateLine(e, error, status)
    var askUninstall by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Box {
            ExtIcon(e.id, e.name, null, pkg?.icon)
            if (state != null) Box(Modifier.align(Alignment.TopEnd).padding(0.dp)) { Dot(state.second) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, color = if (e.enabled) AppText else AppMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                VersionPill(e.version)
                if (update) { Spacer(Modifier.width(4.dp)); ExtPill(stringResource(R.string.extensions_update_badge), AppAccent, AppAccent.copy(alpha = 0.18f)) }
            }
            when {
                state != null -> Text(state.first, color = state.second, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                !e.enabled -> Text(stringResource(R.string.extension_off), color = AppMuted, fontSize = 12.sp)
                else -> Text(m?.description?.ifBlank { null } ?: authorLine(e.author, e.origin?.repo), color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            m?.let { ActionIcons(it.asks().filter { a -> a.key in e.granted }) }
            extra()
        }
        IconButton(onClick = { askUninstall = true }) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.extension_uninstall), tint = AppMuted)
        }
        Switch(checked = e.enabled, onCheckedChange = { enable(e.id, it) })
    }
    if (askUninstall) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askUninstall = false },
        containerColor = tone(0xFF282828),
        text = { Text(stringResource(R.string.extension_uninstall_confirm, e.name), color = AppText) },
        confirmButton = {
            TextButton(onClick = {
                askUninstall = false
                scope.launch { Extensions.uninstall(e.id) }
            }) { Text(stringResource(R.string.extension_uninstall), color = Warning) }
        },
        dismissButton = { TextButton(onClick = { askUninstall = false }) { Text(stringResource(R.string.extensions_cancel), color = AppText) } },
    )
}

@Composable
private fun InstallButtons(onRepo: (() -> Unit)?, onFile: () -> Unit, onUrl: () -> Unit) {
    if (com.music.spotui.ui.navigation.isImmersive()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onRepo?.let { com.music.spotui.ui.immersive.components.ImmersiveButton(stringResource(R.string.extensions_repository_add), it) }
            com.music.spotui.ui.immersive.components.ImmersiveButton(stringResource(R.string.extensions_install_file), onFile)
            com.music.spotui.ui.immersive.components.ImmersiveButton(stringResource(R.string.extensions_install_url), onUrl)
        }
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val border = BorderStroke(1.dp, AppMuted.copy(alpha = 0.5f))
        onRepo?.let { OutlinedButton(onClick = it, border = border) { Text(stringResource(R.string.extensions_repository_add), color = AppText, fontSize = 13.sp) } }
        OutlinedButton(onClick = onFile, border = border) { Text(stringResource(R.string.extensions_install_file), color = AppText, fontSize = 13.sp) }
        OutlinedButton(onClick = onUrl, border = border) { Text(stringResource(R.string.extensions_install_url), color = AppText, fontSize = 13.sp) }
    }
}
