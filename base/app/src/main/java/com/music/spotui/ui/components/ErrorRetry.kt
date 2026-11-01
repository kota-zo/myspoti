package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R

/** A page or list that could not load: what happened, and a Try again button. */
@Composable
fun ErrorRetry(message: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(24.dp, 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = AppText, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.retry_button), color = com.music.spotui.ui.theme.AppBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(AppText)
                .pressable(0.96f) { onRetry() }.padding(horizontal = 22.dp, vertical = 10.dp))
    }
}
