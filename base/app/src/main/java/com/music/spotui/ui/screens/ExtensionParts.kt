package com.music.spotui.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import app.myspoti.extensions.Ask
import app.myspoti.extensions.Catalog
import app.myspoti.extensions.Category
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.Clash
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.ProtectedSurface
import com.music.spotui.extensions.ProtectedSurfaceEffect
import com.music.spotui.extensions.actionIcon
import com.music.spotui.extensions.actionLabel
import com.music.spotui.extensions.askLabel
import app.myspoti.extensions.StoreAction
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.extensions.ExtensionStore
import com.music.spotui.extensions.Extensions
import com.music.spotui.extensions.Grants
import com.music.spotui.extensions.InstallPlan
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The pieces the extension screens share: the install prompt (the trust model: what it asks, the
// clashes, the third-party notice and the one tick that the user installs at their own risk), the
// store row's icon, pill, action icons and button.

/** The colour of a critical action: the user reads it before ticking that they accept the risk. */
internal val Warning = Color(0xFFFFA42B)

/** This Myspoti's version name, compared with an extension's `minAppVersion`. */
internal val appVersion: String get() = app.myspoti.engine.BuildConfig.VERSION_NAME

/** A package read and waiting for the user's answer in the install prompt; [origin]: its GitHub source. */
internal class PendingInstall(val zip: ByteArray, val pkg: ExtensionPackage, val source: String, val plan: InstallPlan, val origin: app.myspoti.extensions.Origin? = null)

internal val pending = MutableStateFlow<PendingInstall?>(null)

/** Runs installs the user confirmed, past the prompt that started them. */
private val installScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

/** A failure said in a toast; a cancelled job (its screen closed) is not one. */
internal fun toastError(ctx: Context, t: Throwable) {
    if (t is kotlinx.coroutines.CancellationException) return
    // The whole chain in the log: a cancellation wrapped in another exception still shows here.
    app.myspoti.host.android.Logbook.log("ext: not installed: " + generateSequence(t) { it.cause }.take(4).joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" })
    Toast.makeText(ctx.applicationContext, ctx.getString(R.string.extensions_error, t.message ?: t.toString()), Toast.LENGTH_LONG).show()
}

/** Reads [zip] and opens the install prompt; a refused package says why. */
internal fun offer(ctx: Context, zip: ByteArray, source: String, origin: app.myspoti.extensions.Origin? = null) {
    runCatching { ExtensionPackage.read(zip) }
        .onSuccess { p -> pending.value = PendingInstall(zip, p, source, Extensions.plan(p), origin) }
        .onFailure { toastError(ctx, it) }
}

/** Downloads a repository's package for [entry] (a spinner on its button meanwhile), then the prompt. */
internal suspend fun installFromStore(ctx: Context, repo: String, entry: Catalog.Entry) {
    ExtensionStore.busy.value = ExtensionStore.busy.value + entry.id
    runCatching { Extensions.fetch(repo, entry) }
        .onSuccess { f -> offer(ctx, f.zip, f.origin?.let { "https://github.com/${it.repo}" } ?: repo, f.origin) }
        .onFailure { toastError(ctx, it) }
    ExtensionStore.busy.value = ExtensionStore.busy.value - entry.id
}

/** The system file picker, for a package (.zip). */
@Composable
internal fun rememberPackagePicker(): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { s ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (out.size() <= ExtensionPackage.MAX_ZIP) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            if (bytes == null) Toast.makeText(ctx, ctx.getString(R.string.extensions_unreadable_file), Toast.LENGTH_LONG).show()
            else offer(ctx, bytes, "file")
        }
    }
    return { launcher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
}

// ── The install prompt ──

/**
 * What the user accepts before an extension is installed or updated (docs/extensions/API.md §4):
 * its name, author and source, every action it lists with the author's reason (critical ones
 * marked; an update lists only what changes), what it would collide with, the third-party notice
 * and one tick: all or nothing, there is no action to untick. An update that asks nothing new of
 * an extension already accepted is applied with the same tap.
 * A protected surface: no extension draws or acts over it.
 */
@Composable
internal fun InstallPrompt() {
    val p = pending.collectAsState().value ?: return
    ProtectedSurfaceEffect(ProtectedSurface.INSTALL)
    val ctx = LocalContext.current
    val registry by Extensions.registry.collectAsState()
    val m = p.pkg.manifest
    val plan = p.plan
    var risk by remember(p) { mutableStateOf(false) }
    val clashes = remember(p) { Extensions.clashes(m) }
    val keepNew = remember(p) { mutableStateMapOf<Clash, Boolean>().apply { clashes.forEach { put(it, true) } } }
    val newKeys = (plan as? InstallPlan.Update)?.newAsks?.map { it.key }?.toSet().orEmpty()
    val silentUpdate = plan is InstallPlan.Update && !plan.reAsk
    // An update asking nothing new of an extension whose notice was accepted needs no tick.
    val askRisk = plan !is InstallPlan.NotNewer && (!silentUpdate || m.id !in registry.noticeAccepted)
    val dismiss = { pending.value = null }

    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = tone(0xFF282828),
        icon = { ExtIcon(m.id, m.name, null, p.pkg.icon, 48.dp) },
        title = {
            Text(stringResource(if (plan is InstallPlan.Fresh) R.string.extensions_prompt_title_install else R.string.extensions_prompt_title_update, m.name), color = AppText)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.extensions_prompt_by, authorLine(m.author, p.origin?.repo), m.version, m.id), color = AppMuted, fontSize = 12.sp)
                Text(stringResource(R.string.extensions_prompt_source, if (p.source == "file") stringResource(R.string.extensions_prompt_source_file) else p.source),
                    color = AppMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (m.description.isNotBlank()) Text(m.description, color = AppText, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                if (plan is InstallPlan.Update && m.changelog.isNotBlank()) {
                    PromptHeader(R.string.extension_changelog)
                    Text(m.changelog, color = AppText, fontSize = 13.sp)
                }
                Text(stringResource(R.string.extensions_prompt_sha, p.pkg.sha256.take(16) + "…"), color = AppMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                when {
                    plan is InstallPlan.NotNewer -> Text(stringResource(R.string.extensions_prompt_not_newer, plan.installed.version), color = Warning, modifier = Modifier.padding(top = 10.dp))
                    silentUpdate -> Text(stringResource(R.string.extensions_prompt_update_same_rights, (plan as InstallPlan.Update).from.version, m.version), color = AppText, modifier = Modifier.padding(top = 10.dp))
                    else -> {
                        PromptHeader(R.string.actions_title)
                        // An update lists only what changes: what it asks anew, and what was unticked before.
                        val asks = m.asks().filter { plan !is InstallPlan.Update || it.key in newKeys || it.key in plan.refused }.sortedByDescending { it.critical }
                        asks.forEach { a -> ActionRow(a, new = a.key in newKeys) }
                        if (asks.any { it.critical }) Text(stringResource(R.string.actions_critical_warning), color = Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                if (plan !is InstallPlan.NotNewer) ClashChoices(m.name, clashes, keepNew)
                if (askRisk) RiskBox(risk) { risk = it }
            }
        },
        confirmButton = {
            if (plan !is InstallPlan.NotNewer) {
                TextButton(
                    enabled = !askRisk || risk,
                    onClick = {
                        pending.value = null
                        val choice = keepNew.toMap()
                        // Not the prompt's scope: closing the prompt cancelled it, and the cancelled
                        // (yet finished) install showed as an error.
                        installScope.launch {
                            runCatching { Extensions.install(p.zip, p.pkg, Grants.keys(m), p.source, clashes, { choice[it] != false }, noticeAccepted = true, origin = p.origin) }.onFailure { toastError(ctx, it) }
                        }
                    },
                ) { Text(stringResource(if (plan is InstallPlan.Fresh) R.string.extensions_install else R.string.extensions_update), color = AppAccent) }
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.extensions_cancel), color = AppText) } },
    )
}

/**
 * "Third-party extension, not reviewed by Myspoti, under its author's responsibility", and the one
 * tick: "I understand this extension is made by a third party and I install it at my own risk".
 * Also on the cards that wait for the user's OK on an installed extension.
 */
@Composable
internal fun RiskBox(accepted: Boolean, onAccepted: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(Warning.copy(alpha = 0.10f)).padding(10.dp)) {
        Text(stringResource(R.string.ext_third_party_notice), color = AppText, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).toggleable(accepted, role = Role.Checkbox, onValueChange = onAccepted)) {
            Checkbox(checked = accepted, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Warning))
            Text(stringResource(R.string.ext_risk_accept), color = AppText, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** For each clash: what it is, and the user's pick (the new one acts, or the other is kept). */
@Composable
internal fun ClashChoices(newName: String, clashes: List<Clash>, keepNew: MutableMap<Clash, Boolean>) {
    if (clashes.isEmpty()) return
    val ctx = LocalContext.current
    PromptHeader(R.string.ext_clash_title)
    clashes.forEach { c ->
        val other = Extensions.registry.value.find(c.otherId)?.name ?: c.otherId
        val what = when (c.kind) {
            Clash.Kind.EXCLUSIVE -> stringResource(R.string.ext_clash_exclusive, ctx.getString(actionLabel(c.action)).lowercase(), other)
            Clash.Kind.CONFLICT -> stringResource(R.string.ext_clash_conflict, other)
            Clash.Kind.REPLACES -> stringResource(R.string.ext_clash_replaces, other)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Icon(actionIcon(c.action), null, tint = Warning, modifier = Modifier.size(18.dp))
            Text(what, color = Warning, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
        val state = if (c.kind == Clash.Kind.EXCLUSIVE) R.string.ext_clash_idle else R.string.ext_clash_off
        listOf(true, false).forEach { mine ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().toggleable(keepNew[c] == mine, role = Role.RadioButton) { keepNew[c] = mine }) {
                androidx.compose.material3.RadioButton(selected = keepNew[c] == mine, onClick = null)
                Text(
                    if (mine) stringResource(R.string.ext_clash_keep_new, newName, stringResource(state)) else stringResource(R.string.ext_clash_keep_old, other),
                    color = AppText, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** Turning on [m] would collide with what is on: the user picks first. */
@Composable
internal fun ClashDialog(m: Manifest, clashes: List<Clash>, onDismiss: () -> Unit, onDone: (Map<Clash, Boolean>) -> Unit) {
    val keepNew = remember(clashes) { mutableStateMapOf<Clash, Boolean>().apply { clashes.forEach { put(it, true) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tone(0xFF282828),
        title = { Text(m.name, color = AppText, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { ClashChoices(m.name, clashes, keepNew) } },
        confirmButton = { TextButton(onClick = { onDone(keepNew.toMap()) }) { Text(stringResource(R.string.ext_clash_ok), color = AppAccent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.extensions_cancel), color = AppText) } },
    )
}

/** Turns [id] on, asking first which to keep when it collides with an extension that is on. */
@Composable
internal fun rememberEnabler(): (String, Boolean) -> Unit {
    var asking by remember { mutableStateOf<Pair<String, List<Clash>>?>(null) }
    asking?.let { (id, clashes) ->
        val m = Extensions.manifest(id)
        if (m == null) asking = null
        else ClashDialog(m, clashes, onDismiss = { asking = null }) { choice ->
            asking = null
            Extensions.setEnabled(id, true, clashes) { choice[it] != false }
        }
    }
    return { id, on ->
        val clashes = if (on) Extensions.manifest(id)?.let { Extensions.clashes(it) }.orEmpty() else emptyList()
        if (clashes.isEmpty()) Extensions.setEnabled(id, on) else asking = id to clashes
    }
}

@Composable
internal fun PromptHeader(text: Int) =
    Text(stringResource(text).uppercase(), color = AppAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))

/** An action's icon on a small tile, in the warning colour when it is critical. */
@Composable
internal fun ActionTile(a: Ask, size: Dp = 36.dp) {
    val c = if (a.critical) Warning else AppAccent
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(c.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(actionIcon(a.action), contentDescription = stringResource(askLabel(a)), tint = c, modifier = Modifier.size(size * 0.58f))
    }
}

/** One action: icon, label, the author's reason (≤ 80 characters), hosts, "Critical", and "new in this version" when [new]. */
@Composable
internal fun ActionRow(a: Ask, new: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        ActionTile(a)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(askLabel(a)), color = if (a.critical) Warning else AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (a.critical) { androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp)); ExtPill(stringResource(R.string.action_critical), Color.Black, Warning) }
                if (new) { androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp)); ExtPill(stringResource(R.string.extensions_prompt_new), AppAccent, AppAccent.copy(alpha = 0.15f)) }
            }
            Text(a.why.ifBlank { stringResource(R.string.action_no_why) }, color = AppMuted, fontSize = 12.sp)
            if (a.hosts.isNotEmpty() && a.hosts != listOf("*")) Text(a.hosts.joinToString(), color = AppMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

internal fun categoryLabel(c: Category?): Int = when (c) {
    null -> R.string.extensions_cat_all
    Category.LYRICS -> R.string.extensions_cat_lyrics
    Category.PLAYBACK -> R.string.extensions_cat_playback
    Category.LIBRARY -> R.string.extensions_cat_library
    Category.DISCOVERY -> R.string.extensions_cat_discovery
    Category.STATS -> R.string.extensions_cat_stats
    Category.LOOK -> R.string.extensions_cat_look
    Category.SOCIAL -> R.string.extensions_cat_social
    Category.TOOLS -> R.string.extensions_cat_tools
    Category.OTHER -> R.string.extensions_cat_other
}

// ── Store widgets ──

/**
 * An extension's icon: its PNG (from the package, or the index's `icon_url` before download)
 * over a tile of a colour taken from its id, with its first letter (seen when there is no icon,
 * or it does not load).
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun ExtIcon(id: String, name: String, iconUrl: String?, bytes: ByteArray?, size: Dp = 44.dp) {
    val tint = remember(id) { Color.hsv(((id.hashCode() and 0x7fffffff) % 360).toFloat(), 0.45f, 0.58f) }
    // Decoded off the main thread, at the size shown (an icon file can be a large picture).
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, bytes, targetPx) {
        value = bytes?.let { b -> withContext(Dispatchers.Default) { runCatching { decodeSampled(b, targetPx) }.getOrNull() } }
    }
    val icon = bitmap
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(tint), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        when {
            icon != null -> Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            iconUrl != null && iconUrl.startsWith("https://") && !com.music.spotui.data.DataSaver.active ->
                GlideImage(model = iconUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** [bytes] as a bitmap no larger than needed for [targetPx] on both sides (a power-of-two sample). */
private fun decodeSampled(bytes: ByteArray, targetPx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** An entry's icon address, resolved against its index's. */
internal fun iconOf(repo: String, e: Catalog.Entry): String? =
    e.iconAddress()?.let { runCatching { Catalog.resolve(repo, it) }.getOrNull() }

/** `v1.2.0` in a small grey pill. */
/** "Inactive: <what its status said>": its source does not answer now, the app skips it for songs' audio and downloads. */
@Composable
internal fun inactiveLine(s: app.myspoti.extensions.ExtensionStatus): String =
    s.detail?.let { stringResource(R.string.extension_inactive_detail, it) } ?: stringResource(R.string.extension_inactive)

/**
 * An installed extension's one state message, with its colour: why it was turned off, else (on)
 * that it is inactive, else its last error; null when there is nothing to say.
 */
@Composable
internal fun stateLine(e: com.music.spotui.extensions.InstalledExtension, error: String?, status: app.myspoti.extensions.ExtensionStatus?): Pair<String, Color>? = when {
    e.disabledReason != null -> stringResource(R.string.extension_disabled_reason, e.disabledReason) to Problem
    !e.enabled -> null
    status != null -> inactiveLine(status) to Warning
    error != null -> stringResource(R.string.extension_last_error, error) to Problem
    else -> null
}

/** A failure: turned off, or the last error. */
internal val Problem = Color(0xFFE5534B)

@Composable
internal fun VersionPill(version: String) = ExtPill(if (version.startsWith("v")) version else "v$version", AppMuted, tone(0xFF2A2A2A))

@Composable
internal fun ExtPill(text: String, color: Color, background: Color) =
    Text(text, color = color, fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 7.dp, vertical = 1.dp))

/** The actions as small icons (the critical ones first, in the warning colour), [max] then "+n". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionIcons(asks: List<Ask>, max: Int = 6) {
    if (asks.isEmpty()) return
    val sorted = asks.sortedByDescending { it.critical }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
        sorted.take(max).forEach { a -> ActionTile(a, 22.dp) }
        if (sorted.size > max) Text("+${sorted.size - max}", color = AppMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp))
    }
}

/** The one state button of a store row: Install, Update (tonal), Installed (outlined), or a spinner. */
@Composable
internal fun StateButton(action: StoreAction, busy: Boolean, large: Boolean = false, onInstall: () -> Unit, onOpen: () -> Unit) {
    val h = if (large) 44.dp else 32.dp
    val pad = PaddingValues(horizontal = if (large) 24.dp else 14.dp)
    val mod = Modifier.height(h).let { if (large) it.fillMaxWidth() else it.widthIn(min = 92.dp) }
    val text = @Composable { id: Int -> Text(stringResource(id), fontSize = if (large) 15.sp else 13.sp, maxLines = 1) }
    when {
        busy -> Box(mod, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = AppAccent, strokeWidth = 2.dp) }
        action == StoreAction.INSTALL -> Button(onInstall, mod, contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = AppAccent, contentColor = Color.Black)) { text(R.string.extensions_install) }
        action == StoreAction.UPDATE -> FilledTonalButton(onInstall, mod, contentPadding = pad,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = AppAccent.copy(alpha = 0.22f), contentColor = AppAccent)) { text(R.string.extensions_update) }
        action == StoreAction.INSTALLED -> OutlinedButton(onOpen, mod, contentPadding = pad,
            border = BorderStroke(1.dp, AppMuted.copy(alpha = 0.6f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText)) { text(R.string.extensions_up_to_date) }
        else -> OutlinedButton({}, mod, enabled = false, contentPadding = pad,
            border = BorderStroke(1.dp, AppMuted.copy(alpha = 0.3f))) { text(R.string.extensions_install) }
    }
}

/** A small dot, for "failed to load" on an icon or "update" on a tab. */
@Composable
internal fun Dot(color: Color, size: Dp = 10.dp) =
    Box(Modifier.size(size).clip(CircleShape).background(color).border(1.5.dp, AppBackground, CircleShape))

// ── Small pieces, as on the settings page ──

@Composable
internal fun Page(titleRes: Int?, navController: NavController, title: String? = null, top: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    // Immersive look: the shared page scaffold (large title into the glass bar) around the same content.
    if (com.music.spotui.ui.navigation.isImmersive()) {
        com.music.spotui.ui.immersive.pages.ImmersivePage(title = title ?: stringResource(titleRes!!), onBack = { navController.popBackStack() }) {
            item(key = "ext:top") { top() }
            item(key = "ext:content") { Column(Modifier.fillMaxWidth()) { content() } }
        }
        return
    }
    Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = AppText)
            }
            Text(title ?: stringResource(titleRes!!), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        top()
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) { content() }
    }
}

@Composable
internal fun ExtSection(title: Int) = ExtSection(stringResource(title))

@Composable
internal fun ExtSection(title: String) {
    if (com.music.spotui.ui.navigation.isImmersive()) {
        Text(title.uppercase(), style = com.music.spotui.ui.immersive.Immersive.Caption,
            modifier = Modifier.padding(start = 36.dp, end = 20.dp, top = 18.dp, bottom = 6.dp))
        return
    }
    Text(title.uppercase(), color = AppAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
internal fun ExtDivider() {
    if (com.music.spotui.ui.navigation.isImmersive()) Spacer(Modifier.height(8.dp))
    else HorizontalDivider(color = tone(0xFF2A2A2A), modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun SwitchRow(label: String, detail: String, checked: Boolean, detailColor: Color = AppMuted, onChecked: (Boolean) -> Unit) {
    if (com.music.spotui.ui.navigation.isImmersive()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(com.music.spotui.ui.immersive.Immersive.Card)
            .background(com.music.spotui.ui.immersive.Immersive.Raised)) {
            com.music.spotui.ui.immersive.pages.ImmersiveSwitchRow(label, checked, onChecked, detail = detail, detailColor = detailColor)
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChecked)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = AppText, fontSize = 16.sp)
            if (detail.isNotBlank()) Text(detail, color = detailColor, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** An https address typed by the user. */
@Composable
internal fun UrlDialog(title: Int, hint: Int, warning: Int? = null, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ok = text.trim().startsWith("https://") && text.trim().length > 10
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(title), color = AppText) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text(stringResource(hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                warning?.let { Text(stringResource(it), color = Warning, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onOk(text.trim()) }) { Text(stringResource(android.R.string.ok), color = AppAccent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.extensions_cancel), color = AppText) } },
    )
}
