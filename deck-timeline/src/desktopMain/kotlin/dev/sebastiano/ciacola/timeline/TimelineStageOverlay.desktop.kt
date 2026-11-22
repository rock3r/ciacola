package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.sebastiano.ciacola.document.CrossSlideGeometry
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.theme.LocalDeckTheme

/** Dark-navy fill for stage selection label chips (>= 15:1 WCAG contrast with white text on both themes). */
val STAGE_SELECTION_CHIP_FILL = Color(0xFF0F223D)
val STAGE_SELECTION_CHIP_TEXT = Color.White

/**
 * Computes the top-left offset for the stage selection label chip outside [anchor], preferring
 * above with [gapPx] clearance, flipping below when near the top edge, and clamping within [canvasSize].
 */
fun computeSelectionChipOffset(
    anchor: Rect,
    chipSize: Size,
    canvasSize: Size,
    gapPx: Float,
    marginPx: Float,
): Offset {
    val maxX = (canvasSize.width - chipSize.width - marginPx).coerceAtLeast(marginPx)
    val x = anchor.left.coerceIn(marginPx, maxX)
    val aboveY = anchor.top - chipSize.height - gapPx
    val belowY = anchor.bottom + gapPx
    val y = when {
        aboveY >= marginPx -> aboveY
        belowY + chipSize.height <= canvasSize.height - marginPx -> belowY
        else -> (anchor.top + gapPx).coerceIn(
            marginPx,
            (canvasSize.height - chipSize.height - marginPx).coerceAtLeast(marginPx),
        )
    }
    return Offset(x, y)
}

/**
 * Restored Stage Timeline Overlay bound to [DocumentTimelineController] and [LocalStageInspectionRegistry]:
 * draws the selected Timeline track's live bounding box + high-contrast label chip, plus a dashed
 * motion-path trajectory arc when a cross-slide `MagicMove` travelling track is selected.
 *
 * Automatically bypassed in Show/Fullscreen, Overview, Preview, or when Studio is closed.
 */
@Composable
fun DocumentTimelineStageOverlay(
    controller: DocumentTimelineController,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    stageSelectedNodeIds: Set<String> = emptySet(),
) {
    if (!enabled) return
    if (LocalOverviewOpen.current || LocalPresentingFullscreen.current) return
    if (LocalSceneMode.current != SceneMode.Story) return

    val hoveredRef = controller.hoveredTrackId?.let { controller.snapshot.trackRefsByTrackId[it] }
    val selectedRef = hoveredRef ?: controller.selectedTrackRef() ?: return
    val decision = timelineStageOverlayDecision(
        targetNodeId = selectedRef.referencedNodeId(),
        hoveringTrack = hoveredRef != null,
        stageSelectedNodeIds = stageSelectedNodeIds,
        gestureActive = controller.hasActiveGesture,
    )
    val inspectionRegistry = LocalStageInspectionRegistry.current
    val stageBg = LocalDeckTheme.current.colors.background
    val isLightStage = wcagRelativeLuminance(stageBg) > 0.35f
    val accent = if (isLightStage) Color(0xFF0D5BD9) else Color(0xFF4CA8FF)
    val motionPathColor = LocalDeckTheme.current.colors.motionPathPreview
    val textMeasurer = rememberTextMeasurer()

    val targetNodeId = when (selectedRef) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> selectedRef.nodeId
        is DocumentTimelineTrackRef.SpecimenTrackBar -> selectedRef.nodeId
        is DocumentTimelineTrackRef.TransitionTrackBar -> selectedRef.nodeId
        is DocumentTimelineTrackRef.LoopTrackBar -> selectedRef.nodeId
    }
    val targetLabel = controller.snapshot.snapshot.tracks.firstOrNull { it.trackId == selectedRef.trackId }?.label
        ?: when (selectedRef) {
            is DocumentTimelineTrackRef.LoopTrackBar -> "Loop · ${selectedRef.label}"
            else -> TimelineDisplayNames.humanizeId(targetNodeId)
        }

    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(selectedRef.trackId) {
        while (true) withFrameNanos { frameTick = it }
    }

    // Resolve cross-slide start/end bounds when a TransitionTrackBar is selected.
    val motionEndpoints: Pair<Rect, Rect>? = remember(selectedRef, controller.store.document) {
        if (selectedRef is DocumentTimelineTrackRef.TransitionTrackBar) {
            val doc = controller.store.document
            val fromSlide = doc.slides.firstOrNull { it.id == selectedRef.fromSlideId }
            val toSlide = doc.slides.firstOrNull { it.id == selectedRef.toSlideId }
            if (fromSlide != null && toSlide != null) {
                val match = CrossSlideGeometry.matchCrossSlidePairs(fromSlide, toSlide, doc.stage)
                    .pairs
                    .firstOrNull { it.nodeId == selectedRef.nodeId }
                if (match != null) {
                    val s = rectFromFrame(match.fromNode)
                    val e = rectFromFrame(match.toNode)
                    if (s != null && e != null) s to e else null
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("ciacola-stage-selection-overlay")
            .semantics { contentDescription = "selected=${selectedRef.trackId} node=$targetNodeId" },
    ) {
        frameTick
        val stageW = controller.store.document.stage.width.toFloat().coerceAtLeast(1f)
        val stageH = controller.store.document.stage.height.toFloat().coerceAtLeast(1f)
        val scaleX = size.width / stageW
        val scaleY = size.height / stageH

        // 1. Draw cross-slide motion path preview if selected track is a travelling transition node.
        //    Only while hovering the track or during an active drag — never as a marker at rest.
        if (motionEndpoints != null && decision.drawMotionPath) {
            val (fromR, toR) = motionEndpoints
            val s = Offset(fromR.center.x * scaleX, fromR.center.y * scaleY)
            val e = Offset(toR.center.x * scaleX, toR.center.y * scaleY)
            val path = Path().apply {
                moveTo(s.x, s.y)
                lineTo(e.x, e.y)
            }
            drawPath(
                path = path,
                color = motionPathColor,
                style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)),
            )
            val dotRing = if (isLightStage) Color.White else Color(0xFF0B0D12)
            drawCircle(color = dotRing, radius = 8f, center = s)
            drawCircle(color = motionPathColor, radius = 6f, center = s)
            drawCircle(color = dotRing, radius = 8f, center = e)
            drawCircle(color = motionPathColor, radius = 6f, center = e)
        }

        // 2. Highlight live stage bounds for the selected track's node — skipped when the stage
        //    selection chrome already outlines that node (one outline per selection).
        if (!decision.drawOutline) return@Canvas
        val rawStageBounds = inspectionRegistry?.liveStageBoundsOf(targetNodeId)
            ?: findFallbackNodeBounds(controller.store.document, selectedRef)
            ?: return@Canvas

        val mapped = Rect(
            left = rawStageBounds.left * scaleX,
            top = rawStageBounds.top * scaleY,
            right = rawStageBounds.right * scaleX,
            bottom = rawStageBounds.bottom * scaleY,
        )
        if (mapped.width <= 0f || mapped.height <= 0f) return@Canvas

        val pad = 6.dp.toPx()
        val stroke = 2.5.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()), 0f)
        val inflated = Rect(mapped.left - pad, mapped.top - pad, mapped.right + pad, mapped.bottom + pad)
        // Outline only: a fill wash over an absent node's ghost covers real slide content.
        drawRoundRect(
            color = accent,
            topLeft = inflated.topLeft,
            size = inflated.size,
            cornerRadius = CornerRadius(8.dp.toPx()),
            style = Stroke(width = stroke, pathEffect = dash),
        )

        if (!decision.drawLabelPill) return@Canvas
        val layout = textMeasurer.measure(
            text = targetLabel,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = STAGE_SELECTION_CHIP_TEXT),
            maxLines = 1,
        )
        val chipPadH = 8.dp.toPx()
        val chipPadV = 4.dp.toPx()
        val chipStroke = 1.5.dp.toPx()
        val chipW = layout.size.width + chipPadH * 2
        val chipH = layout.size.height + chipPadV * 2
        val chipPos = computeSelectionChipOffset(
            anchor = inflated.inflate(stroke / 2f + chipStroke / 2f),
            chipSize = Size(chipW, chipH),
            canvasSize = size,
            gapPx = 6.dp.toPx(),
            marginPx = 4.dp.toPx(),
        )
        val chipCorner = CornerRadius(6.dp.toPx())
        drawRoundRect(STAGE_SELECTION_CHIP_FILL, chipPos, Size(chipW, chipH), chipCorner)
        drawRoundRect(accent, chipPos, Size(chipW, chipH), chipCorner, style = Stroke(width = chipStroke))
        drawText(layout, topLeft = Offset(chipPos.x + chipPadH, chipPos.y + chipPadV))
    }
}

/** What the stage overlay draws for the current timeline state (pure for tests). */
data class TimelineStageOverlayDecision(
    val drawOutline: Boolean,
    val drawLabelPill: Boolean,
    val drawMotionPath: Boolean,
)

/**
 * The floating track label pill is shown only while the pointer hovers a timeline track, never at
 * rest. The dashed timeline outline is skipped when the stage selection already outlines the node, and
 * the cross-slide motion-path marker only appears while hovering the track or dragging.
 */
fun timelineStageOverlayDecision(
    targetNodeId: String?,
    hoveringTrack: Boolean,
    stageSelectedNodeIds: Set<String>,
    gestureActive: Boolean = false,
): TimelineStageOverlayDecision {
    val alreadySelectedOnStage = targetNodeId != null && targetNodeId in stageSelectedNodeIds
    return TimelineStageOverlayDecision(
        drawOutline = hoveringTrack || !alreadySelectedOnStage,
        drawLabelPill = hoveringTrack,
        drawMotionPath = hoveringTrack || gestureActive,
    )
}

private fun rectFromFrame(node: NodeSpec): Rect? {
    val f = node.frame ?: return null
    val x = f.x?.toFloat() ?: return null
    val y = f.y?.toFloat() ?: return null
    val w = f.w?.toFloat()?.coerceAtLeast(16f) ?: 160f
    val h = f.h?.toFloat()?.coerceAtLeast(16f) ?: 64f
    return Rect(x, y, x + w, y + h)
}

private fun findFallbackNodeBounds(
    doc: dev.sebastiano.ciacola.document.DeckDocument,
    ref: DocumentTimelineTrackRef,
): Rect? {
    val slideId = when (ref) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> ref.slideId
        is DocumentTimelineTrackRef.SpecimenTrackBar -> ref.slideId
        is DocumentTimelineTrackRef.TransitionTrackBar -> ref.fromSlideId
        is DocumentTimelineTrackRef.LoopTrackBar -> ref.slideId
    }
    val nodeId = when (ref) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> ref.nodeId
        is DocumentTimelineTrackRef.SpecimenTrackBar -> ref.nodeId
        is DocumentTimelineTrackRef.TransitionTrackBar -> ref.nodeId
        is DocumentTimelineTrackRef.LoopTrackBar -> ref.nodeId
    }
    val slide = doc.slides.firstOrNull { it.id == slideId } ?: return null
    fun find(nodes: List<NodeSpec>): NodeSpec? {
        for (n in nodes) {
            if (n.id == nodeId) return n
            find(n.children)?.let { return it }
        }
        return null
    }
    return find(slide.nodes)?.let(::rectFromFrame)
}
