/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.tv

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.music.spotui.data.api.SupportReminder
import org.junit.Assert.assertEquals
import org.junit.Test

class TvQrTest {
    /** Read back as a camera would: scaled up, with a quiet zone. */
    private fun read(url: String): String {
        val m = TvQr.modules(url)
        val scale = 6; val quiet = 3
        val side = (m.width + 2 * quiet) * scale
        val pixels = IntArray(side * side) { 0xFFFFFF }
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) {
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))).text
    }

    @Test fun theKoFiCodeReadsBackAsItsLink() = assertEquals(SupportReminder.URL, read(SupportReminder.URL))
    @Test fun theSponsorsCodeReadsBackAsItsLink() = assertEquals(SupportReminder.SPONSORS_URL, read(SupportReminder.SPONSORS_URL))
}
