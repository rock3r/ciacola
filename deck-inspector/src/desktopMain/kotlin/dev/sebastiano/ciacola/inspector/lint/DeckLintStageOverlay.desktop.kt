package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.sebastiano.ciacola.document.LocalRenderSession
import dev.sebastiano.ciacola.document.RenderPurpose

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
 * Draws amber/red dashed diagnostic callout boxes and compact `E1`/`W1` severity badges on the
 * live stage for [DocumentDeckLintController.overlayIssuesFor]. Strictly hidden when
 * [isInspectionVisible] is `false` or during Show / Fullscreen / Overview / Preview / offscreen.
 */
@Composable
fun DocumentDeckLintStageOverlay(
    controller: DocumentDeckLintController,
    activeSlideId: String?,
    activeBeatIndex: Int,
    isInspectionVisible: Boolean,
    stageFormatSize: IntSize = IntSize(STAGE_WIDTH_PX.toInt(), STAGE_HEIGHT_PX.toInt()),
    modifier: Modifier = Modifier,
) {
    val renderPurpose = LocalRenderSession.current?.purpose
    if (
        !isInspectionVisible ||
        !controller.showStageOverlay ||
        LocalPresentingFullscreen.current ||
        LocalOverviewOpen.current ||
        LocalSceneMode.current == SceneMode.Preview ||
        (renderPurpose != null && renderPurpose != RenderPurpose.Present)
    ) {
        return
    }

    val issues = controller.overlayIssuesFor(activeSlideId, activeBeatIndex)
    if (issues.isEmpty()) return

    val highlightedId = controller.selectedIssueId
    val errorColor = Color(0xFFE5484D)
    val warningColor = Color(0xFFF5A623)
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
            val color = if (issue.severity == LintSeverity.Error) errorColor else warningColor
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
                    color = color.copy(alpha = 0.90f),
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.75.dp.toPx(), pathEffect = dashEffect),
                )
            }

            // Compact severity badge pinned to top-left corner of the callout box.
            val layoutResult = textMeasurer.measure(text = codePrefix, style = badgeStyle)
            val padH = 4.dp.toPx()
            val padV = 2.dp.toPx()
            val badgeW = layoutResult.size.width + padH * 2f
            val badgeH = layoutResult.size.height + padV * 2f
            val badgeX = clampedLeft.coerceIn(2f, (size.width - badgeW - 2f).coerceAtLeast(2f))
            val badgeY = (clampedTop - badgeH - 2f).let { above ->
                if (above >= 2f) above else clampedTop + 2f
            }.coerceIn(2f, (size.height - badgeH - 2f).coerceAtLeast(2f))

            drawRoundRect(
                color = color,
                topLeft = Offset(badgeX, badgeY),
                size = Size(badgeW, badgeH),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            drawText(
                textLayoutResult = layoutResult,
                topLeft = Offset(badgeX + padH, badgeY + padV),
            )
        }
    }
}
