package com.music.spotui.ui.immersive.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import app.myspoti.extensions.Action
import app.myspoti.extensions.Area
import app.myspoti.extensions.Catalog
import app.myspoti.extensions.Category
import app.myspoti.extensions.Store
import app.myspoti.extensions.StoreAction
import app.myspoti.extensions.mode
import com.music.spotui.extensions.ExtensionStore
import com.music.spotui.extensions.Extensions
import com.music.spotui.extensions.InstalledExtension
import com.music.spotui.extensions.Registry
import com.music.spotui.extensions.actionIcon
import com.music.spotui.extensions.actionLabel
import com.music.spotui.extensions.areaLabel
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveButton
import com.music.spotui.ui.immersive.components.ImmersiveSearchField
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.screens.ImmersiveChip
import com.music.spotui.ui.immersive.screens.ImmersiveChipRow
import com.music.spotui.ui.screens.ActionIcons
import com.music.spotui.ui.screens.Dot
import com.music.spotui.ui.screens.ExtIcon
import com.music.spotui.ui.screens.ExtPill
import com.music.spotui.ui.screens.InstallPrompt
import com.music.spotui.ui.screens.RatingLine
import com.music.spotui.ui.screens.UrlDialog
import com.music.spotui.ui.screens.VersionPill
import com.music.spotui.ui.screens.Warning
import com.music.spotui.ui.screens.appVersion
import com.music.spotui.ui.screens.authorLine
import com.music.spotui.ui.screens.categoryLabel
import com.music.spotui.ui.screens.iconOf
import com.music.spotui.ui.screens.incompatibleLine
import com.music.spotui.ui.screens.incompatibleWith
import com.music.spotui.ui.screens.installFromStore
import com.music.spotui.ui.screens.installedRoute
import com.music.spotui.ui.screens.offer
import com.music.spotui.ui.screens.rememberEnabler
import com.music.spotui.ui.screens.rememberPackagePicker
import com.music.spotui.ui.screens.stateLine
import com.music.spotui.ui.screens.storeRoute
import com.music.spotui.ui.screens.toastError
import kotlinx.coroutines.launch

/**
 * The extensions page of the Immersive look: Store and Installed behind a segmented control,
 * search and category pills, then the repositories' extensions or the installed ones by area, in
 * grouped rows. Everything the Classic `ExtensionsScreen` does is here, with its state and
 * helpers: install from a repository, a file or an address, turn on and off, uninstall, the order
 * of the sources of an area, repositories and GitHub discovery, refresh.
 */
@Composable
fun ImmersiveExtensionsScreen(navController: NavController) {
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
    val categories = Category.entries.filter { it in present || it == category }
    val github = repos[app.myspoti.extensions.Discovery.SOURCE]
    val githubShown = discovered.filter { Store.matches(it.item, query, category) }
    val hiddenCount = remember(repos, ratings, blocks) { ExtensionStore.hiddenCount() }
    val shown = storeSources.sumOf { url -> repos[url]?.catalog?.extensions?.count { Store.matches(it, query, category) } ?: 0 } +
        (if (github != null) githubShown.size else 0)
    val installedLabel = stringResource(R.string.extensions_installed)

    ImmersivePage(title = stringResource(R.string.extensions_title), onBack = { navController.popBackStack() }) {
        item(key = "ext:tabs") {
            ImmersiveSegmentedControl(
                options = listOf(stringResource(R.string.extensions_tab_store), if (updates.isNotEmpty()) "$installedLabel (${updates.size})" else installedLabel),
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 4.dp),
            )
        }
        item(key = "ext:search") {
            ImmersiveSearchField(
                query = query, onQueryChange = { query = it }, onSubmit = {},
                placeholder = stringResource(R.string.extensions_search),
                modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 8.dp),
            )
        }
        if (categories.isNotEmpty()) item(key = "ext:categories") {
            ImmersiveChipRow {
                (listOf<Category?>(null) + categories).forEach { c ->
                    ImmersiveChip(stringResource(categoryLabel(c)), selected = c == category) { categoryKey = if (c == category) null else c?.key }
                }
            }
        }
        if (downloading) immersiveBlock("downloading") {
            Text(stringResource(R.string.extensions_loading), style = Immersive.Caption)
        }

        if (tab == 0) {
            if (registry.repositories.none { !ExtensionStore.isOfficial(it) } && !officialShown) immersiveBlock("empty") { EmptyState() }
            storeSources.forEach { url ->
                val repo = repos[url] ?: ExtensionStore.Repo(url, loading = true)
                item(key = "repo:$url") {
                    RepoHeader(repo, onRefresh = { ExtensionStore.refresh(registry.repositories, force = true) }, onRemove = { Extensions.removeRepository(url) })
                }
                val entries = repo.catalog?.extensions?.filter { Store.matches(it, query, category) }.orEmpty()
                immersiveGroup("store:$url") {
                    entries.forEach { entry ->
                        row {
                            StoreRow(url, entry, Store.action(entry, installedCodes[entry.id], appVersion), entry.id in busy, registry,
                                onOpen = { navController.navigate(storeRoute(entry.id)) },
                                onInstall = { scope.launch { installFromStore(ctx, url, entry) } },
                                onInstalled = { navController.navigate(installedRoute(entry.id)) })
                        }
                    }
                }
            }
            // Discovered on GitHub: by score, then the most recently updated; the hidden ones behind "Show all".
            if (github != null) {
                item(key = "repo:github") { GitHubHeader(github, onRefresh = { ExtensionStore.discover(force = true) }) }
                immersiveGroup("store:github") {
                    githubShown.forEach { r ->
                        row {
                            val entry = r.item
                            StoreRow(app.myspoti.extensions.Discovery.SOURCE, entry, Store.action(entry, installedCodes[entry.id], appVersion), entry.id in busy, registry,
                                onOpen = { navController.navigate(storeRoute(entry.id)) },
                                onInstall = { scope.launch { installFromStore(ctx, app.myspoti.extensions.Discovery.SOURCE, entry) } },
                                onInstalled = { navController.navigate(installedRoute(entry.id)) },
                                rating = r.rating, hidden = app.myspoti.extensions.Ranking.hidden(r, blocks, System.currentTimeMillis()) != null)
                        }
                    }
                    if (hiddenCount > 0 || showAll) row {
                        ImmersiveGroupRow(
                            title = if (showAll) stringResource(R.string.extensions_show_less) else stringResource(R.string.extensions_show_all, hiddenCount),
                            onClick = { showAll = !showAll },
                        )
                    }
                }
            }
            if (shown == 0 && catalogs.any { it.second.extensions.isNotEmpty() }) immersiveBlock("no-match") {
                Text(stringResource(R.string.extensions_no_match), style = Immersive.Subhead)
            }
        } else {
            val matches = { e: InstalledExtension ->
                val m = Extensions.manifest(e.id)
                (category == null || m?.category() == category) &&
                    query.lowercase().split(' ').filter { it.isNotBlank() }.all { w -> listOf(e.name, e.author, m?.description.orEmpty(), e.id, m?.tags.orEmpty().joinToString(" ")).any { it.lowercase().contains(w) } }
            }
            if (registry.extensions.isEmpty()) immersiveBlock("none") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.extensions_none), style = Immersive.Subhead)
                    ImmersiveButton(stringResource(R.string.extensions_browse_store), onClick = { tab = 0 })
                }
            }
            // A source that said its account needs a sign-in: its page, where the extension offers it.
            signIns.forEach { (id, detail) ->
                immersiveBlock("sign-in-$id") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.extension_sign_in_needed, registry.find(id)?.name ?: id), style = Immersive.Subhead)
                        if (detail.isNotBlank()) Text(detail, style = Immersive.Caption)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ImmersiveButton(stringResource(R.string.extension_sign_in), onClick = { navController.navigate(installedRoute(id)) })
                            ImmersiveButton(stringResource(R.string.extension_sign_in_later), onClick = { Extensions.dismissSignIn(id) })
                        }
                    }
                }
            }
            // By area: the extensions acting there, in order; an area without any is not listed.
            Extensions.byArea().forEach { (area, list) ->
                val inArea = list.filter(matches)
                if (inArea.isNotEmpty()) areaSection(area, inArea, errors, statuses, updates, enable, onOpen = { navController.navigate(installedRoute(it)) })
            }
        }

        immersiveGroup(
            "other-ways",
            headerRes = R.string.extensions_other_ways,
            footerRes = if (tab == 0) R.string.extensions_repository_warning else null,
        ) {
            if (tab == 0) row {
                ImmersiveGroupRow(stringResource(R.string.extensions_repository_add), icon = Icons.Rounded.Add, chevron = true, onClick = { askRepo = true })
            }
            row { ImmersiveGroupRow(stringResource(R.string.extensions_install_file), icon = Icons.Rounded.Folder, chevron = true, onClick = pickFile) }
            row { ImmersiveGroupRow(stringResource(R.string.extensions_install_url), icon = Icons.Rounded.Link, chevron = true, onClick = { askUrl = true }) }
        }
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

/** No repository yet: what extensions are, before anything is added. */
@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.extensions_empty_title), style = Immersive.BodyStrong)
        Text(stringResource(R.string.extensions_empty_body), style = Immersive.Subhead, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A repository: its name, address and how much it is trusted; refresh and remove. */
@Composable
private fun RepoHeader(repo: ExtensionStore.Repo, onRefresh: () -> Unit, onRemove: () -> Unit) {
    val official = ExtensionStore.isOfficial(repo.url)
    Column(Modifier.fillMaxWidth().padding(start = Immersive.Gutter + 16.dp, end = Immersive.Gutter - 8.dp, top = 18.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(repo.catalog?.name ?: runCatching { java.net.URI(repo.url).host }.getOrNull() ?: repo.url, style = Immersive.BodyStrong,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    if (official) ExtPill(stringResource(R.string.extensions_official_badge), androidx.compose.ui.graphics.Color.White, Immersive.Accent)
                    else ExtPill(stringResource(R.string.extensions_repo_yours), Warning, Warning.copy(alpha = 0.15f))
                }
                Text(repo.url, style = Immersive.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (official) R.string.extensions_repo_official_note else R.string.extensions_repo_yours_note),
                    style = Immersive.Caption.copy(color = if (official) Immersive.TextSecondary else Warning))
            }
            if (repo.loading) CircularProgressIndicator(Modifier.padding(10.dp).size(20.dp), color = Immersive.Accent, strokeWidth = 2.dp)
            else ImmersiveRowIcon(Icons.Rounded.Refresh, stringResource(R.string.extensions_repository_refresh), onClick = onRefresh)
            if (!official) ImmersiveRowIcon(Icons.Rounded.DeleteOutline, stringResource(R.string.extensions_repository_remove), onClick = onRemove)
        }
        repo.error?.let { Text(stringResource(R.string.extensions_repo_error, it), style = Immersive.Caption.copy(color = Warning)) }
        if (repo.catalog?.extensions?.isEmpty() == true) Text(stringResource(R.string.extensions_repo_empty), style = Immersive.Caption)
    }
}

/** The GitHub discovery's header: what it is (not reviewed), refresh. */
@Composable
private fun GitHubHeader(repo: ExtensionStore.Repo, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = Immersive.Gutter + 16.dp, end = Immersive.Gutter - 8.dp, top = 18.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.extensions_github_title), style = Immersive.BodyStrong)
                    Spacer(Modifier.width(8.dp))
                    ExtPill(stringResource(R.string.extensions_repo_community), Warning, Warning.copy(alpha = 0.15f))
                }
                Text(stringResource(R.string.extensions_github_note), style = Immersive.Caption.copy(color = Warning))
            }
            if (repo.loading) CircularProgressIndicator(Modifier.padding(10.dp).size(20.dp), color = Immersive.Accent, strokeWidth = 2.dp)
            else ImmersiveRowIcon(Icons.Rounded.Refresh, stringResource(R.string.extensions_repository_refresh), onClick = onRefresh)
        }
        repo.error?.let { Text(stringResource(R.string.extensions_repo_error, it), style = Immersive.Caption.copy(color = Warning)) }
    }
}

/** A store row: icon, name, version, one line, the rights, and the one state button. */
@Composable
private fun StoreRow(
    repo: String, e: Catalog.Entry, action: StoreAction, busy: Boolean, registry: Registry,
    onOpen: () -> Unit, onInstall: () -> Unit, onInstalled: () -> Unit, rating: app.myspoti.extensions.Rating? = null, hidden: Boolean = false,
) {
    ImmersiveExtRow(
        onClick = onOpen,
        leading = { ExtIcon(e.id, e.name, iconOf(repo, e), null) },
        trailing = { ImmersiveStateButton(action, busy, onInstall = onInstall, onOpen = onInstalled) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, style = Immersive.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            VersionPill(e.version)
        }
        if (action == StoreAction.INCOMPATIBLE) Text(incompatibleLine(e), style = Immersive.Caption.copy(color = Warning), maxLines = 1, overflow = TextOverflow.Ellipsis)
        else Text(e.description.ifBlank { authorLine(e.author, e.repo) }, style = Immersive.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        incompatibleWith(e, registry)?.let { Text(it, style = Immersive.Caption.copy(color = Warning), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (e.repo != null) RatingLine(rating, hidden)
        ActionIcons(e.preview().asks())
    }
}

/**
 * One area of the Installed tab: its extensions in the user's order (arrows to reorder, "Active" /
 * "Idle" for an exclusive action), and who did what lately, with one-tap turn off.
 */
private fun LazyListScope.areaSection(
    area: Area, list: List<InstalledExtension>, errors: Map<String, String>, statuses: Map<String, app.myspoti.extensions.ExtensionStatus>,
    updates: Set<String>, enable: (String, Boolean) -> Unit, onOpen: (String) -> Unit,
) {
    // The exclusive actions of this area: who acts now.
    val exclusive = Action.entries.filter { it.area == area && it.mode == app.myspoti.extensions.Mode.EXCLUSIVE }
    immersiveGroup(
        "area:$area",
        headerRes = areaLabel(area),
        footerRes = if (area == Area.AUDIO && list.size >= 2) R.string.sources_order_hint else null,
    ) {
        list.forEachIndexed { i, e ->
            row {
                val m = remember(e.id, e.sha256) { Extensions.manifest(e.id) }
                InstalledRow(e, errors[e.id], statuses[e.id], e.id in updates, enable, onOpen = { onOpen(e.id) }) {
                    exclusive.filter { m?.actionSet()?.contains(it) == true }.forEach { a ->
                        val active = Extensions.activeFor(a) == e.id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                                .then(if (!active && e.enabled) Modifier.immersiveClickable(onClick = { Extensions.setActive(a, e.id) }, pressedScale = 0.98f) else Modifier),
                        ) {
                            Icon(actionIcon(a), null, tint = if (active) Immersive.Accent else Immersive.TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (active) stringResource(R.string.area_active) else stringResource(R.string.area_idle) + " · " + stringResource(R.string.area_make_active, stringResource(actionLabel(a)).lowercase()),
                                style = Immersive.Caption.copy(color = if (active) Immersive.Accent else Immersive.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Sources are ordered per action, in Settings (see the hint under them).
                    if (list.size >= 2 && area != Area.AUDIO) Row {
                        ImmersiveRowIcon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.extensions_move_up), enabled = i > 0, tint = Immersive.Text,
                            onClick = { Extensions.movePriority(area, e.id, -1) })
                        ImmersiveRowIcon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.extensions_move_down), enabled = i < list.lastIndex, tint = Immersive.Text,
                            onClick = { Extensions.movePriority(area, e.id, 1) })
                    }
                }
            }
        }
    }
    item(key = "area-activity:$area") { AreaActivity(area, enable) }
}

/** Who did what in an area lately, each with a one-tap turn off while the extension is on. */
@Composable
private fun AreaActivity(area: Area, enable: (String, Boolean) -> Unit) {
    val activity by Extensions.activity.state.collectAsState()
    val recent = remember(activity, area) { activity.filter { it.area == area }.takeLast(3).asReversed() }
    if (recent.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(start = Immersive.Gutter + 16.dp, end = Immersive.Gutter - 8.dp, top = 8.dp)) {
        Text(stringResource(R.string.area_activity).uppercase(), style = Immersive.Caption)
        recent.forEach { a ->
            val on = Extensions.registry.value.find(a.extensionId)?.enabled == true
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(a.text, style = Immersive.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    android.text.format.DateUtils.getRelativeTimeSpanString(a.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = Immersive.Caption.copy(color = Immersive.TextTertiary), modifier = Modifier.padding(horizontal = 6.dp),
                )
                if (on) TextButton(onClick = { enable(a.extensionId, false) }) {
                    Text(stringResource(R.string.area_turn_off), style = Immersive.Caption.copy(color = Warning))
                }
            }
        }
    }
}

/** An installed row: icon (a dot when it failed), name, version, its state or description, its action icons, the switch; [extra] under it. */
@Composable
private fun InstalledRow(
    e: InstalledExtension, error: String?, status: app.myspoti.extensions.ExtensionStatus?, update: Boolean,
    enable: (String, Boolean) -> Unit, onOpen: () -> Unit, extra: @Composable () -> Unit = {},
) {
    val pkg = remember(e.id, e.sha256) { Extensions.pkg(e.id) }
    val m = pkg?.manifest
    val state = stateLine(e, error, status)
    var askUninstall by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ImmersiveExtRow(
        onClick = onOpen,
        leading = {
            Box {
                ExtIcon(e.id, e.name, null, pkg?.icon)
                if (state != null) Box(Modifier.align(Alignment.TopEnd)) { Dot(state.second) }
            }
        },
        trailing = {
            ImmersiveRowIcon(Icons.Rounded.DeleteOutline, stringResource(R.string.extension_uninstall), onClick = { askUninstall = true })
            ImmersiveSwitch(e.enabled) { enable(e.id, it) }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, style = Immersive.BodyStrong.copy(color = if (e.enabled) Immersive.Text else Immersive.TextSecondary),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            VersionPill(e.version)
            if (update) {
                Spacer(Modifier.width(4.dp))
                ExtPill(stringResource(R.string.extensions_update_badge), Immersive.Accent, Immersive.Accent.copy(alpha = 0.18f))
            }
        }
        when {
            state != null -> Text(state.first, style = Immersive.Caption.copy(color = state.second), maxLines = 2, overflow = TextOverflow.Ellipsis)
            !e.enabled -> Text(stringResource(R.string.extension_off), style = Immersive.Caption)
            else -> Text(m?.description?.ifBlank { null } ?: authorLine(e.author, e.origin?.repo), style = Immersive.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        m?.let { ActionIcons(it.asks().filter { a -> a.key in e.granted }) }
        extra()
    }
    if (askUninstall) AlertDialog(
        onDismissRequest = { askUninstall = false },
        containerColor = Immersive.Raised,
        text = { Text(stringResource(R.string.extension_uninstall_confirm, e.name), style = Immersive.Body) },
        confirmButton = {
            TextButton(onClick = {
                askUninstall = false
                scope.launch { Extensions.uninstall(e.id) }
            }) { Text(stringResource(R.string.extension_uninstall), style = Immersive.BodyStrong.copy(color = Immersive.Destructive)) }
        },
        dismissButton = {
            TextButton(onClick = { askUninstall = false }) {
                Text(stringResource(R.string.extensions_cancel), style = Immersive.BodyStrong.copy(color = Immersive.Accent))
            }
        },
    )
}
