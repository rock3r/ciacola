package dev.bnorm.storyboard.easel.overlay

import androidx.compose.runtime.Composable

@Composable
internal actual fun OverlayNavigationTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    content()
}
