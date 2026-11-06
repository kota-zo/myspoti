package com.music.spotui.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.music.spotui.ui.immersive.sheets.ImmersiveAlertDialog
import com.music.spotui.ui.navigation.isImmersive

/**
 * The alert of the screens shared by both looks: Material's [AlertDialog] on [containerColor] in
 * Classic (exactly as before), a glass dialog in Immersive (as [LookSheet] for the sheets).
 */
@Composable
fun LookAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    containerColor: Color,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    if (isImmersive()) ImmersiveAlertDialog(onDismissRequest, confirmButton, dismissButton, icon, title, text)
    else AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        containerColor = containerColor,
    )
}
