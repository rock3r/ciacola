package dev.bnorm.storyboard.easel

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBarScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.overview.OverviewVisibility
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

private val SCALE_PRESETS = listOf(
    "200%" to 2.0f,
    "150%" to 1.5f,
    "100%" to 1.0f,
    "75%" to 0.75f,
    "50%" to 0.5f,
    "25%" to 0.25f,
)

@Composable
internal fun MenuBarScope.DesktopMenu(
    storyboard: Storyboard,
    windowState: WindowState?,
    window: ComposeWindow?,
    overview: OverviewVisibility? = null,
) {
    Menu("Easel") {
        if (overview != null) {
            // No accelerator: Esc is handled by the stage itself, and a menu accelerator would
            // fire on key press in addition to the stage's key-release toggle.
            CheckboxItem("Overview (Esc)", checked = overview.isVisible) { overview.isVisible = it }
        }
        if (windowState != null && window != null) {
            Item("Fullscreen", shortcut = KeyShortcut(Key.P, alt = true, meta = true)) {
                // TODO keynote seems to create a new window which fades in over the entire screen
                //  - is this a better experience then converting the window to full screen?
                //  - would *just* the overview be shown when not in full screen?
                windowState.placement = when (windowState.placement) {
                    WindowPlacement.Floating -> WindowPlacement.Fullscreen
                    WindowPlacement.Maximized -> WindowPlacement.Fullscreen
                    WindowPlacement.Fullscreen -> WindowPlacement.Floating // TODO go back to float or max?
                }
            }

            Menu("Scale") {
                val insets = window.insets
                    ?.let { DpSize((it.left + it.right).dp, (it.top + it.bottom).dp) }
                    ?: DpSize.Zero

                val size = DpSize(storyboard.format.size.width.dp, storyboard.format.size.height.dp)

                for ((label, factor) in SCALE_PRESETS) {
                    Item(label) {
                        val maxBounds = usableWindowBoundsFor(window)
                        val clampedSize = clampScaledWindowSize(
                            stageSize = size,
                            scale = factor,
                            insets = insets,
                            maxWindowBounds = maxBounds,
                        )
                        windowState.placement = WindowPlacement.Floating
                        windowState.size = clampedSize
                        windowState.position = clampWindowPositionToBounds(
                            position = windowState.position,
                            size = clampedSize,
                            maxBounds = maxBounds,
                        )
                    }
                }
            }
        }
    }
}

internal fun clampScaledWindowSize(
    stageSize: DpSize,
    scale: Float,
    insets: DpSize,
    maxWindowBounds: Rectangle?,
): DpSize {
    val desired = stageSize * scale + insets
    if (maxWindowBounds == null || maxWindowBounds.width <= 0 || maxWindowBounds.height <= 0) {
        return desired
    }
    val maxW = maxWindowBounds.width.toFloat()
    val maxH = maxWindowBounds.height.toFloat()
    if (desired.width.value <= maxW && desired.height.value <= maxH) {
        return desired
    }
    val availStageW = (maxW - insets.width.value).coerceAtLeast(1f)
    val availStageH = (maxH - insets.height.value).coerceAtLeast(1f)
    val stageW = stageSize.width.value.coerceAtLeast(1f)
    val stageH = stageSize.height.value.coerceAtLeast(1f)
    val fitScale = minOf(scale, availStageW / stageW, availStageH / stageH).coerceAtLeast(0f)
    val clampedW = (stageW * fitScale + insets.width.value).coerceAtMost(maxW)
    val clampedH = (stageH * fitScale + insets.height.value).coerceAtMost(maxH)
    return DpSize(clampedW.dp, clampedH.dp)
}

internal fun clampWindowPositionToBounds(
    position: WindowPosition,
    size: DpSize,
    maxBounds: Rectangle?,
): WindowPosition {
    if (position !is WindowPosition.Absolute || maxBounds == null || maxBounds.width <= 0 || maxBounds.height <= 0) {
        return position
    }
    val minX = maxBounds.x.toFloat()
    val minY = maxBounds.y.toFloat()
    val maxX = (maxBounds.x + maxBounds.width - size.width.value).coerceAtLeast(minX)
    val maxY = (maxBounds.y + maxBounds.height - size.height.value).coerceAtLeast(minY)
    return WindowPosition(
        x = position.x.value.coerceIn(minX, maxX).dp,
        y = position.y.value.coerceIn(minY, maxY).dp,
    )
}

private fun usableWindowBoundsFor(window: ComposeWindow): Rectangle? {
    if (GraphicsEnvironment.isHeadless()) return null
    return runCatching {
        val maxBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val gc = window.graphicsConfiguration
        if (gc != null) {
            val screenBounds = gc.bounds
            val screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
            val usableW = screenBounds.width - screenInsets.left - screenInsets.right
            val usableH = screenBounds.height - screenInsets.top - screenInsets.bottom
            if (usableW > 0 && usableH > 0) {
                return@runCatching Rectangle(
                    screenBounds.x + screenInsets.left,
                    screenBounds.y + screenInsets.top,
                    usableW,
                    usableH,
                )
            }
        }
        maxBounds.takeIf { it.width > 0 && it.height > 0 }
    }.getOrNull()
}
