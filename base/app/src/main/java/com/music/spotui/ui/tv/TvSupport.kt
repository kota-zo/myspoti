/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.floor

/** A QR code's modules, one bit each: the screen scales them and adds the quiet zone. */
internal object TvQr {
    fun modules(text: String): BitMatrix =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
}

/**
 * The ways to support Myspoti, at the end of Home. A TV has no browser to send anyone to, so the links
 * are codes for the phone. The block can take the focus, or the remote could never scroll down to it.
 */
@Composable
internal fun TvSupport(modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 48.dp)
            .onFocusChanged { focused = it.isFocused }.focusable()
            .background(Color(0x14FFFFFF), shape)
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.support_title), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.support_body), color = Color(0xB3FFFFFF), fontSize = 16.sp)
            Text(stringResource(R.string.support_scan), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        TvQrCode(com.music.spotui.data.api.SupportReminder.URL, "Ko-fi")
        TvQrCode(com.music.spotui.data.api.SupportReminder.SPONSORS_URL, "GitHub Sponsors")
    }
}

/** Black on white whatever the theme, with its quiet zone: what a phone's camera reads best. */
@Composable
private fun TvQrCode(url: String, label: String) {
    val matrix = remember(url) { TvQr.modules(url) }
    Column(Modifier.width(190.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(190.dp).background(Color.White, RoundedCornerShape(10.dp))) {
            Canvas(Modifier.size(190.dp)) {
                val quiet = 3
                // A whole number of pixels per module: no seam between two of them.
                val cell = floor(size.minDimension / (matrix.width + 2 * quiet))
                val origin = (size.minDimension - cell * matrix.width) / 2
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (matrix[x, y]) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
                }
            }
        }
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
