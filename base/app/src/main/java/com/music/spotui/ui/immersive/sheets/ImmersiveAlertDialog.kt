package com.music.spotui.ui.immersive.sheets

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.shell.immersiveGlassSurface

/** The corners of an Immersive dialog. */
val ImmersiveDialogShape = RoundedCornerShape(28.dp)

/**
 * A Material alert on glass, the dialog of the Immersive look: the same title, text and buttons
 * as [AlertDialog], the container drawn with the shared `immersiveGlassSurface` (as the sheets)
 * instead of an opaque fill.
 */
@Composable
fun ImmersiveAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = Modifier.immersiveGlassSurface(ImmersiveDialogShape),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = ImmersiveDialogShape,
        containerColor = Color.Transparent,
        titleContentColor = Immersive.Text,
        textContentColor = Immersive.TextSecondary,
        tonalElevation = 0.dp,
    )
}
