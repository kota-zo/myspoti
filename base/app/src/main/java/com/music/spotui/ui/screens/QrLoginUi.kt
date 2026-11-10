// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
package com.music.spotui.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.connect.QrLoginProtocol
import com.music.spotui.connect.QrLoginServer
import com.music.spotui.connect.QrPairHandoff
import com.music.spotui.connect.QrPairSender
import com.music.spotui.connect.QrPairTarget
import com.music.spotui.connect.QrServerState
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppSans
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.tv.Tv
import com.music.spotui.ui.tv.TvQr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * Full-screen QR / phone-handoff / `sp_dc`-paste sign-in screen. Designed for both Android TV
 * (landscape side-by-side layout with crisp D-pad focus rings) and Android 6+ phones.
 */
@Composable
fun QrLoginScreen(
    serverState: QrServerState,
    onCookies: (Map<String, String>) -> Unit,
    onResetError: () -> Unit,
    onFallbackWebView: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val tv = remember { Tv.isTv(ctx) }
    val wide = tv || LocalConfiguration.current.screenWidthDp >= 640
    var refreshKey by remember { mutableIntStateOf(0) }
    var endpoint by remember { mutableStateOf<QrLoginServer.Endpoint?>(null) }
    var server by remember { mutableStateOf<QrLoginServer?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    DisposableEffect(refreshKey) {
        val srv = QrLoginServer(ctx.applicationContext, isTv = tv) { cookies ->
            localError = null
            onCookies(cookies)
        }
        val ep = srv.start()
        server = srv
        endpoint = ep
        onDispose {
            srv.stop()
            if (server === srv) server = null
        }
    }

    LaunchedEffect(server, serverState) {
        server?.setState(serverState)
    }

    val statusText: String?
    val statusIsError: Boolean
    when {
        localError != null -> { statusText = localError; statusIsError = true }
        serverState is QrServerState.Checking -> { statusText = stringResource(R.string.qr_login_checking); statusIsError = false }
        serverState is QrServerState.SignedIn -> { statusText = stringResource(R.string.qr_login_signed_in); statusIsError = false }
        serverState is QrServerState.Failed -> { statusText = serverState.message; statusIsError = true }
        endpoint != null -> { statusText = stringResource(R.string.qr_login_waiting); statusIsError = false }
        else -> { statusText = stringResource(R.string.qr_login_no_network); statusIsError = true }
    }

    val backFocus = remember { FocusRequester() }
    LaunchedEffect(tv) {
        if (tv) {
            delay(200)
            runCatching { backFocus.requestFocus() }
        }
    }

    fun submitPasted(raw: String = cookieInput) {
        val parsed = QrLoginProtocol.parseCookieInput(raw)
        if (parsed == null) {
            localError = ctx.getString(R.string.qr_login_bad_cookie)
            return
        }
        localError = null
        showPaste = false
        onResetError()
        onCookies(parsed)
    }

    if (showPaste) {
        CookiePasteDialog(
            value = cookieInput,
            onChange = { cookieInput = it; localError = null },
            onPasteClipboard = {
                val clip = readClipboardText(ctx)
                if (clip != null) {
                    cookieInput = clip
                    localError = null
                    true
                } else {
                    Toast.makeText(ctx, ctx.getString(R.string.qr_login_paste_clipboard_empty), Toast.LENGTH_SHORT).show()
                    false
                }
            },
            onSubmit = { submitPasted(cookieInput) },
            onDismiss = { showPaste = false },
            busy = serverState is QrServerState.Checking,
            error = localError,
        )
    }

    Box(Modifier.fillMaxSize().background(AppBackground)) {
        Glow(Modifier.fillMaxSize(), alpha = 0.22f)
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = if (wide) 40.dp else 22.dp, vertical = 20.dp)) {
            if (wide) {
                Row(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(0.95f),
                    ) {
                        val ep = endpoint
                        if (ep != null) {
                            QrCodeCard(ep.url, size = 232.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                stringResource(R.string.qr_login_pin_label, QrLoginProtocol.formatPin(ep.pin)),
                                color = AppText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                            )
                            Text(
                                ep.shortUrl,
                                color = AppText.copy(alpha = 0.65f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            if (ep.vpnBypassed) {
                                Text(
                                    stringResource(R.string.qr_login_vpn_hint, ep.host),
                                    color = AppAccent.copy(alpha = 0.9f),
                                    fontFamily = AppSans,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x18FFFFFF)),
                            ) {
                                Text(
                                    stringResource(R.string.qr_login_no_network),
                                    color = AppText.copy(alpha = 0.8f),
                                    fontFamily = AppSans,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(18.dp),
                                )
                            }
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1.15f),
                    ) {
                        Text(
                            stringResource(R.string.qr_login_title),
                            color = AppText,
                            fontFamily = AppSans,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 30.sp,
                        )
                        Text(
                            stringResource(R.string.qr_login_steps),
                            color = AppText.copy(alpha = 0.78f),
                            fontFamily = AppSans,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        )
                        StatusBanner(statusText, statusIsError, checking = serverState is QrServerState.Checking)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            QrActionButton(
                                label = stringResource(R.string.qr_login_back),
                                primary = true,
                                focusRequester = backFocus,
                                onClick = onBack,
                                modifier = Modifier.weight(1f),
                            )
                            QrActionButton(
                                label = stringResource(R.string.qr_login_retry_network),
                                primary = false,
                                onClick = { localError = null; onResetError(); refreshKey++ },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            QrActionButton(
                                label = stringResource(R.string.qr_login_use_webview),
                                primary = false,
                                onClick = onFallbackWebView,
                                modifier = Modifier.weight(1f),
                            )
                            QrActionButton(
                                label = stringResource(R.string.qr_login_paste_toggle),
                                primary = false,
                                onClick = { localError = null; showPaste = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.qr_login_title),
                        color = AppText,
                        fontFamily = AppSans,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 26.sp,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.qr_login_steps),
                        color = AppText.copy(alpha = 0.78f),
                        fontFamily = AppSans,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    val ep = endpoint
                    if (ep != null) {
                        QrCodeCard(ep.url, size = 196.dp)
                        Text(
                            "${ep.shortUrl}   ·   ${stringResource(R.string.qr_login_pin_label, QrLoginProtocol.formatPin(ep.pin))}",
                            color = AppText.copy(alpha = 0.75f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                        if (ep.vpnBypassed) {
                            Text(
                                stringResource(R.string.qr_login_vpn_hint, ep.host),
                                color = AppAccent.copy(alpha = 0.9f),
                                fontFamily = AppSans,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    StatusBanner(statusText, statusIsError, checking = serverState is QrServerState.Checking)

                    QrActionButton(
                        label = stringResource(R.string.qr_login_paste_toggle),
                        primary = endpoint == null,
                        onClick = { localError = null; showPaste = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        QrActionButton(
                            label = stringResource(R.string.qr_login_use_webview),
                            primary = false,
                            onClick = onFallbackWebView,
                            modifier = Modifier.weight(1f),
                        )
                        QrActionButton(
                            label = stringResource(R.string.qr_login_retry_network),
                            primary = false,
                            onClick = { localError = null; onResetError(); refreshKey++ },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    QrActionButton(
                        label = stringResource(R.string.qr_login_back),
                        primary = false,
                        focusRequester = backFocus,
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun QrCodeCard(url: String, size: Dp) {
    val matrix = remember(url) { TvQr.modules(url) }
    Box(Modifier.size(size).background(Color.White, RoundedCornerShape(14.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val quiet = 3
            val cell = floor(this.size.minDimension / (matrix.width + 2 * quiet))
            val origin = (this.size.minDimension - cell * matrix.width) / 2f
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
            }
        }
    }
}

@Composable
private fun StatusBanner(message: String?, isError: Boolean, checking: Boolean) {
    if (message.isNullOrBlank()) return
    val bg = if (isError) Color(0x33FF6B6B) else Color(0x221DB954)
    val border = if (isError) Color(0x99FF6B6B) else Color(0x771DB954)
    val fg = if (isError) Color(0xFFFFD6D6) else AppText
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, border, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        if (checking) CircularProgressIndicator(color = AppAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Text(message, color = fg, fontFamily = AppSans, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun CookiePasteDialog(
    value: String,
    onChange: (String) -> Unit,
    onPasteClipboard: () -> Boolean,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean,
    error: String?,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = AppText,
        unfocusedTextColor = AppText,
        cursorColor = AppAccent,
        focusedBorderColor = AppAccent,
        unfocusedBorderColor = Color(0xFF555555),
        focusedLabelColor = AppAccent,
        unfocusedLabelColor = AppMuted,
    )
    val pasteFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (value.isBlank()) {
            val clip = readClipboardText(ctx)
            if (clip != null && QrLoginProtocol.parseCookieInput(clip) != null) {
                onChange(clip)
            }
        }
        delay(120)
        val hasToken = value.isNotBlank() || readClipboardText(ctx)?.let { QrLoginProtocol.parseCookieInput(it) } != null
        runCatching { (if (hasToken) submitFocus else pasteFocus).requestFocus() }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color(0xFF242424),
        title = { Text(stringResource(R.string.qr_login_paste_title), color = AppText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.qr_login_paste_desc), color = AppText.copy(alpha = 0.78f), fontSize = 13.sp, lineHeight = 18.sp)
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(stringResource(R.string.qr_login_paste_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Color(0xFFFF8080), fontSize = 13.sp) }
                QrActionButton(
                    label = stringResource(R.string.qr_login_paste_clipboard),
                    primary = false,
                    enabled = !busy,
                    focusRequester = pasteFocus,
                    onClick = {
                        if (onPasteClipboard()) {
                            scope.launch {
                                delay(60)
                                runCatching { submitFocus.requestFocus() }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    QrActionButton(
                        label = stringResource(R.string.cancel),
                        primary = false,
                        enabled = !busy,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    QrActionButton(
                        label = stringResource(R.string.qr_login_paste_submit),
                        primary = true,
                        enabled = !busy && value.isNotBlank(),
                        focusRequester = submitFocus,
                        onClick = onSubmit,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun QrActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val bg = when {
        !enabled -> Color(0x33FFFFFF)
        primary -> AppAccent
        else -> Color(0x22FFFFFF)
    }
    val fg = when {
        !enabled -> AppText.copy(alpha = 0.45f)
        primary -> Color.Black
        else -> AppText
    }
    // Note: clickable() already provides focusability + DPAD_CENTER/ENTER handling on TV;
    // adding a separate .focusable() before .clickable() intercepts focus and swallows OK clicks.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(48.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(bg)
            .border(
                width = if (focused) 3.dp else if (primary) 0.dp else 1.dp,
                color = if (focused) Color.White else if (primary) Color.Transparent else Color(0x44FFFFFF),
                shape = shape,
            )
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Text(label, color = fg, fontFamily = AppSans, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

/**
 * Confirmation dialog shown on the phone when pairing with another device (via camera deep link,
 * ML Kit QR scan, or manual address + code entry).
 */
@Composable
fun PairDeviceDialog(
    target: QrPairTarget,
    signedIn: Boolean,
    onOpenLoginFirst: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var deviceName by remember(target) { mutableStateOf(target.name.ifBlank { target.host }) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(target) {
        if (target.name.isBlank()) {
            QrPairSender.fetchDeviceName(target).onSuccess { deviceName = it }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = Color(0xFF242424),
        title = { Text(stringResource(R.string.pair_confirm_title, deviceName), color = AppText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (signedIn) stringResource(R.string.pair_confirm_body, deviceName, target.host)
                    else stringResource(R.string.pair_confirm_sign_in_first, deviceName),
                    color = AppText.copy(alpha = 0.78f),
                )
                if (sending) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = AppAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.pair_sending), color = AppText, fontSize = 13.sp)
                    }
                }
                error?.let { Text(it, color = Color(0xFFFF8080), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            if (signedIn) {
                TextButton(
                    enabled = !sending,
                    onClick = {
                        sending = true
                        error = null
                        scope.launch {
                            val cookies = MyspotiRuntime.host.exportSpotifySessionCookies()
                            val res = QrPairSender.sendCookiesAndWait(target, cookies)
                            sending = false
                            res.onSuccess {
                                Toast.makeText(ctx.applicationContext, ctx.getString(R.string.pair_success, deviceName), Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }.onFailure { e ->
                                error = e.message ?: "Pairing failed."
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.pair_confirm_send), color = AppAccent, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = onOpenLoginFirst) {
                    Text(stringResource(R.string.welcome_login), color = AppAccent, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = AppText)
            }
        },
    )
}

/** Manual fallback when Play Services barcode scanning is missing or unavailable on older Android 6 devices. */
@Composable
fun ManualPairDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var address by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = AppText,
        unfocusedTextColor = AppText,
        cursorColor = AppAccent,
        focusedBorderColor = AppAccent,
        unfocusedBorderColor = Color(0xFF555555),
        focusedLabelColor = AppAccent,
        unfocusedLabelColor = AppMuted,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF242424),
        title = { Text(stringResource(R.string.pair_manual_title), color = AppText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pair_manual_body), color = AppText.copy(alpha = 0.78f), fontSize = 14.sp)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; error = null },
                    label = { Text(stringResource(R.string.pair_manual_address_hint)) },
                    singleLine = true,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6); error = null },
                    label = { Text(stringResource(R.string.pair_manual_pin_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Color(0xFFFF8080), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = QrLoginProtocol.parseManualPairTarget(address, pin)
                if (parsed == null) {
                    error = ctx.getString(R.string.pair_manual_invalid)
                    return@TextButton
                }
                QrPairHandoff.offer(parsed)
                onDismiss()
            }) {
                Text(stringResource(R.string.pair_manual_connect), color = AppAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = AppText)
            }
        },
    )
}

/** Opens Play Services QR scanner to scan a TV's Myspoti QR code, or invokes [onNeedManual] on failure. */
fun launchQrPairScanner(context: Context, onNeedManual: () -> Unit) {
    runCatching {
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context).startScan()
            .addOnSuccessListener { code ->
                val target = code.rawValue?.let { QrLoginProtocol.parsePairUri(it) }
                if (target != null) QrPairHandoff.offer(target) else onNeedManual()
            }
            .addOnFailureListener { onNeedManual() }
    }.onFailure { onNeedManual() }
}

private fun readClipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching null
    val clip = cm.primaryClip ?: return@runCatching null
    for (i in 0 until clip.itemCount) {
        val text = clip.getItemAt(i)?.coerceToText(context)?.toString()?.trim()
        if (!text.isNullOrEmpty()) return@runCatching text
    }
    null
}.getOrNull()
