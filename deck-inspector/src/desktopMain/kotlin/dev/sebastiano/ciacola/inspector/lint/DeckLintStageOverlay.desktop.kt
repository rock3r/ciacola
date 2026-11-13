package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.LocalStoryboard
import dev.bnorm.storyboard.easel.SceneMode
import dev.sebastiano.ciacola.timeline.LocalTimelineOffscreen
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.theme.colorPalette

/**
 * Returns `true` when [event] is an unmodified `F8` key press intended to toggle the Lint tab.
 */
fun isDeckLintToggleShortcut(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown &&
        event.key == Key.F8 &&
        !event.isCtrlPressed &&
        !event.isMetaPressed &&
        !event.isAltPressed &&
        !event.isShiftPressed

/**
 * Installs the `F8` keyboard shortcut hook to toggle or focus the Deck Linter.
 */
fun Modifier.onDeckLintShortcut(onToggleLint: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (isDeckLintToggleShortcut(event)) {
        onToggleLint()
        true
    } else {
        false
    }
}

/**
 * Creates a [ContentDecorator] that records a clean [GraphicsLayer] of the active `SceneMode.Story`
 * stage when [isInspectionVisible] is `true` and renders [StageLintHighlightOverlay] above it.
 * Completely bypassed in Show Mode, Fullscreen, Overview, and offscreen preview renders.
 */
@Composable
fun rememberDeckLintStageDecorator(
    controller: DeckLintController,
    isInspectionVisible: () -> Boolean,
    stageFormatSize: IntSize = IntSize(STAGE_WIDTH_PX.toInt(), STAGE_HEIGHT_PX.toInt()),
): ContentDecorator = remember(controller, stageFormatSize) {
    ContentDecorator { content ->
        val visible = isInspectionVisible() &&
            !LocalPresentingFullscreen.current &&
            !LocalTimelineOffscreen.current &&
            !LocalOverviewOpen.current &&
            LocalSceneMode.current == SceneMode.Story

        if (!visible) {
            content()
            return@ContentDecorator
        }

        val storyboard = LocalStoryboard.current
        val effectiveFormatSize = storyboard?.format?.size ?: stageFormatSize
        val cleanLayer = rememberGraphicsLayer()

        DisposableEffect(controller, cleanLayer) {
            controller.cleanStageGraphicsLayer = cleanLayer
            onDispose {
                if (controller.cleanStageGraphicsLayer === cleanLayer) {
                    controller.cleanStageGraphicsLayer = null
                }
                controller.stageRootCoordinates = null
                controller.stageBoundsInRoot = null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("ciacola-lint-stage")
                .onPlaced { coords ->
                    controller.stageRootCoordinates = coords
                    if (coords.isAttached) {
                        controller.stageBoundsInRoot = runCatching { coords.boundsInRoot() }.getOrNull()
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        cleanLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(cleanLayer)
                    },
            ) {
                content()
            }
            StageLintHighlightOverlay(
                controller = controller,
                isInspectionVisible = controller.showStageOverlay,
                stageFormatSize = effectiveFormatSize,
            )
        }
    }
}

/**
 * Draws amber/red dashed diagnostic callout boxes and compact severity badges on the stage for
 * [DeckLintController.activeBeatOverlayIssues]. Strictly hidden when [isInspectionVisible] is
 * `false` or during Fullscreen / Show Mode / Overview / Preview / offscreen renders.
 */
@Composable
fun StageLintHighlightOverlay(
    controller: DeckLintController,
    isInspectionVisible: Boolean,
    stageFormatSize: IntSize = IntSize(STAGE_WIDTH_PX.toInt(), STAGE_HEIGHT_PX.toInt()),
    modifier: Modifier = Modifier,
) {
    if (
        !isInspectionVisible ||
        LocalPresentingFullscreen.current ||
        LocalTimelineOffscreen.current ||
        LocalOverviewOpen.current ||
        LocalSceneMode.current == SceneMode.Preview
    ) {
        return
    }

    val issues = controller.activeBeatOverlayIssues
    if (issues.isEmpty()) return

    val highlightedId = controller.highlightedIssueId
    val palette = rememberLintPalette()
    val textMeasurer = rememberTextMeasurer()
    val badgeStyle = remember {
        TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("ciacola-lint-overlay"),
    ) {
        val formatW = stageFormatSize.width.toFloat().coerceAtLeast(1f)
        val formatH = stageFormatSize.height.toFloat().coerceAtLeast(1f)
        val sx = size.width / formatW
        val sy = size.height / formatH
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()), 0f)

        var errorCounter = 0
        var warningCounter = 0

        for (issue in issues) {
            val codePrefix = if (issue.severity == LintSeverity.Error) {
                errorCounter++
                "E$errorCounter"
            } else {
                warningCounter++
                "W$warningCounter"
            }
            val isHighlighted = issue.id == highlightedId
            val color = if (issue.severity == LintSeverity.Error) palette.error else palette.warning
            val rawLeft = issue.stageBounds.left * sx
            val rawTop = issue.stageBounds.top * sy
            val rawRight = issue.stageBounds.right * sx
            val rawBottom = issue.stageBounds.bottom * sy

            val clampedLeft = rawLeft.coerceIn(2f, (size.width - 12f).coerceAtLeast(2f))
            val clampedTop = rawTop.coerceIn(2f, (size.height - 12f).coerceAtLeast(2f))
            val clampedRight = rawRight.coerceIn(clampedLeft + 8f, size.width - 2f)
            val clampedBottom = rawBottom.coerceIn(clampedTop + 8f, size.height - 2f)
            val boxSize = Size(clampedRight - clampedLeft, clampedBottom - clampedTop)
            val topLeft = Offset(clampedLeft, clampedTop)

            if (isHighlighted) {
                drawRoundRect(
                    color = color.copy(alpha = 0.14f),
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 2.5.dp.toPx()),
                )
            } else {
                drawRoundRect(
                    color = color.copy(alpha = 0.05f),
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawRoundRect(
                    color = color.copy(alpha = 0.9f),
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.75.dp.toPx(), pathEffect = dashEffect),
                )
            }

            val badgeText = if (isHighlighted) "$codePrefix · ${issue.ruleId.shortLabel}" else codePrefix
            val layout = textMeasurer.measure(badgeText, style = badgeStyle)
            val padH = 5.dp.toPx()
            val padV = 2.dp.toPx()
            val badgeW = layout.size.width + padH * 2f
            val badgeH = layout.size.height + padV * 2f
            val badgeX = clampedLeft.coerceIn(2f, (size.width - badgeW - 2f).coerceAtLeast(2f))
            val preferredY = clampedTop - badgeH - 3.dp.toPx()
            val badgeY = if (preferredY >= 2f) {
                preferredY
            } else {
                (clampedTop + 3.dp.toPx()).coerceIn(2f, (size.height - badgeH - 2f).coerceAtLeast(2f))
            }

            drawRoundRect(
                color = color,
                topLeft = Offset(badgeX, badgeY),
                size = Size(badgeW, badgeH),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(badgeX + padH, badgeY + padV),
            )
        }
    }
}

@Immutable
internal data class LintUiPalette(
    val error: Color,
    val warning: Color,
    val ok: Color,
)

@Composable
internal fun rememberLintPalette(): LintUiPalette {
    val globalColors = JewelTheme.globalColors
    val colorPalette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val uuid = JewelTheme.instanceUuid
    return remember(uuid, isDark) {
        LintUiPalette(
            error = colorPalette.redOrNull(if (isDark) 6 else 5) ?: globalColors.outlines.error,
            warning = colorPalette.yellowOrNull(if (isDark) 6 else 5) ?: globalColors.outlines.warning,
            ok = colorPalette.greenOrNull(if (isDark) 6 else 5) ?: Color(0xFF2E8B57),
        )
    }
}
