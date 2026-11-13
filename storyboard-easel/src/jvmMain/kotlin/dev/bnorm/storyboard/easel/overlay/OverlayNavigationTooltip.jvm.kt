package dev.bnorm.storyboard.easel.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun OverlayNavigationTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colors
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = colors.surface,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.24f)),
                elevation = 2.dp,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        content = content,
    )
}
