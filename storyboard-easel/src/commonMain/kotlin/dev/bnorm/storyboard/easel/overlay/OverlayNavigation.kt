package dev.bnorm.storyboard.easel.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.Animatic
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal expect fun OverlayNavigationTooltip(
    text: String,
    content: @Composable () -> Unit,
)

@Composable
fun EaselOverlayScope.OverlayNavigation(
    animatic: Animatic,
    alignment: Alignment = Alignment.BottomEnd,
) {
    val coroutineScope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val indices = animatic.storyboard.indices
    val colors = MaterialTheme.colors
    val isLight = colors.isLight || colors.surface.luminance() > 0.5f
    val pillShape = RoundedCornerShape(999.dp)
    val pillBackground = colors.surface
    val pillForeground = if (colors.onSurface.luminance() != colors.surface.luminance()) {
        colors.onSurface
    } else if (isLight) {
        Color(0xFF1E1F22)
    } else {
        Color(0xFFF4F1EA)
    }
    val pillBorderColor = pillForeground.copy(alpha = if (isLight) 0.24f else 0.28f)

    Surface(
        shape = pillShape,
        color = pillBackground,
        contentColor = pillForeground,
        border = BorderStroke(1.dp, pillBorderColor),
        elevation = 0.dp,
        modifier = Modifier
            .align(alignment)
            .padding(16.dp)
            .clip(pillShape)
            .overlayElement()
            .testTag("easel-overlay-navigation"),
    ) {
        Row {
            NavigationIconButton(
                text = "Previous",
                tooltip = "Previous beat (←)",
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                tint = pillForeground,
                enabled = animatic.targetIndex > indices.first(),
                onClick = {
                    job?.cancel()
                    job = coroutineScope.launch {
                        animatic.advance(AdvanceDirection.Backward)
                        job = null
                    }
                }
            )
            NavigationIconButton(
                text = "Next",
                tooltip = "Next beat (→)",
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                tint = pillForeground,
                enabled = animatic.targetIndex < indices.last(),
                onClick = {
                    job?.cancel()
                    job = coroutineScope.launch {
                        animatic.advance(AdvanceDirection.Forward)
                        job = null
                    }
                }
            )
        }
    }
}

@Composable
private fun NavigationIconButton(
    text: String,
    tooltip: String,
    icon: ImageVector,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OverlayNavigationTooltip(text = tooltip) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.testTag("easel-overlay-nav-${text.lowercase()}"),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = if (enabled) tint else tint.copy(alpha = 0.42f),
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand),
            )
        }
    }
}
