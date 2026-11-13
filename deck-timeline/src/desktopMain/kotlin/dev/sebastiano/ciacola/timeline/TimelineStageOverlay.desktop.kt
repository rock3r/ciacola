package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isAltPressed as isPointerAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as isPointerMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import kotlin.math.hypot

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
        else -> (canvasSize.height - chipSize.height - marginPx).coerceAtLeast(marginPx)
    }
    return Offset(x, y)
}

/**
 * Creates the `1920x1080` slide-stage [ContentDecorator]: provides the loop clock, motion-path
 * preview, and timeline/stage selection bridge, draws the selection highlight and motion arcs, and
 * lets a plain click on the stage select the track(s) animating the element under the pointer.
 */
fun createTimelineStageDecorator(
    loopClock: DeckLoopClock,
    motionPathPreview: MotionPathPreviewState,
    overridesStore: TimelineOverridesStore,
    stageSelection: TimelineStageSelection = TimelineStageSelection(),
): ContentDecorator = ContentDecorator { content ->
    if (LocalTimelineOffscreen.current) {
        // Offscreen indexing/measuring scenes: keep their own frozen clock, no live selection.
        content()
        return@ContentDecorator
    }
    val sceneMode = LocalSceneMode.current
    val overviewOpen = LocalOverviewOpen.current
    val isStory = sceneMode == SceneMode.Story
    val insidePreviewViewport = LocalInsideStagePreviewViewport.current
    if (insidePreviewViewport) {
        DisposableEffect(stageSelection, sceneMode) {
            when (sceneMode) {
                SceneMode.Story -> stageSelection.storyStageMountCount++
                SceneMode.Preview -> stageSelection.overviewPreviewMountCount++
                null -> Unit
            }
            onDispose {
                when (sceneMode) {
                    SceneMode.Story -> stageSelection.storyStageMountCount = (stageSelection.storyStageMountCount - 1).coerceAtLeast(0)
                    SceneMode.Preview -> stageSelection.overviewPreviewMountCount = (stageSelection.overviewPreviewMountCount - 1).coerceAtLeast(0)
                    null -> Unit
                }
            }
        }
    }
    if (!isStory || overviewOpen) {
        // Preview / Overview / offscreen export scenes use an isolated loop clock inheriting period
        // overrides and never mount live selection overlays or motion-path arcs.
        val isolatedClock = remember { DeckLoopClock() }.also { isolated ->
            val staleKeys = isolated.periodOverridesMillis.keys.filter { it !in loopClock.periodOverridesMillis }
            staleKeys.forEach { isolated.periodOverridesMillis.remove(it) }
            for ((k, v) in loopClock.periodOverridesMillis) {
                if (isolated.periodOverridesMillis[k] != v) isolated.periodOverridesMillis[k] = v
            }
        }
        CompositionLocalProvider(
            LocalDeckLoopClock provides isolatedClock,
            LocalMotionPathPreviewState provides null,
            LocalTimelineStageSelection provides null,
        ) {
            content()
        }
        return@ContentDecorator
    }
    CompositionLocalProvider(
        LocalDeckLoopClock provides loopClock,
        LocalMotionPathPreviewState provides motionPathPreview,
        LocalTimelineStageSelection provides stageSelection,
    ) {
        val isPresentingStoryStage = stageSelection.overviewPreviewMountCount == 0 && !LocalPresentingFullscreen.current
        Box(Modifier.fillMaxSize().then(if (isPresentingStoryStage) Modifier.stagePicking(stageSelection) else Modifier)) {
            content()
            if (isPresentingStoryStage && !stageSelection.pickingSuppressed()) {
                TimelineSelectionStageOverlay(stageSelection)
                if (loopClock.activeSubCompLoopId == null && stageSelection.pickingEnabled) {
                    MotionArcStageOverlay(
                        motionPathPreview = motionPathPreview,
                        overridesStore = overridesStore,
                    )
                }
            }
        }
    }
}

@Composable
fun rememberTimelineStageDecorator(
    loopClock: DeckLoopClock,
    motionPathPreview: MotionPathPreviewState,
    overridesStore: TimelineOverridesStore,
    stageSelection: TimelineStageSelection = remember { TimelineStageSelection() },
): ContentDecorator = remember(loopClock, motionPathPreview, overridesStore, stageSelection) {
    createTimelineStageDecorator(loopClock, motionPathPreview, overridesStore, stageSelection)
}

/** Whether two coordinates live in the same layout hierarchy (same window / composition root). */
private fun LayoutCoordinates.sharesRootWith(other: LayoutCoordinates): Boolean =
    findRootCoordinates() === other.findRootCoordinates()

/** Plain primary click (no modifiers, no drag) on the stage selects the topmost tagged element. */
private fun Modifier.stagePicking(selection: TimelineStageSelection): Modifier = composed {
    var ownCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { ownCoordinates = it }
        .pointerInput(selection) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.isConsumed || !selection.pickingEnabled || selection.pickingSuppressed()) return@awaitEachGesture
                val event = currentEvent
                val mods = event.keyboardModifiers
                if (!event.buttons.isPrimaryPressed || mods.isPointerCtrlPressed || mods.isPointerMetaPressed || mods.isPointerAltPressed || mods.isPointerShiftPressed) {
                    return@awaitEachGesture
                }
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                if ((up.position - down.position).getDistance() > 6f) return@awaitEachGesture
                val own = ownCoordinates ?: return@awaitEachGesture
                if (!own.isAttached) return@awaitEachGesture
                val hit = selection.targets.values
                    .filter { it.coordinates.isAttached && it.coordinates.sharesRootWith(own) }
                    .map { it to own.localBoundingBoxOf(it.coordinates, clipBounds = false) }
                    .filter { (_, bounds) -> bounds.contains(up.position) }
                    .minByOrNull { (_, bounds) -> bounds.width * bounds.height }
                selection.onPick(hit?.let { selection.mostSpecificTrackId(it.first) })
            }
        }
}

/** Highlights every stage element animated by the selected track (After Effects layer bounds). */
@Composable
private fun TimelineSelectionStageOverlay(selection: TimelineStageSelection) {
    if (LocalOverviewOpen.current || LocalSceneMode.current != SceneMode.Story) return
    // Authoring-only: while presenting (studio closed) nothing is highlighted and no frame loop runs;
    // the selection itself is kept for when the studio reopens.
    val selected = if (selection.pickingEnabled) selection.selectedTrackId else null
    var overlayCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var frameTick by remember { mutableLongStateOf(0L) }
    val textMeasurer = rememberTextMeasurer()
    if (selected != null) {
        // Targets move while animating: redraw every frame while something is selected.
        LaunchedEffect(selected) {
            while (true) withFrameNanos { frameTick = it }
        }
    }
    val entries = if (selected != null) {
        selection.entriesFor(selected).filter { entry ->
            val own = overlayCoordinates
            own != null && own.isAttached && entry.coordinates.isAttached && entry.coordinates.sharesRootWith(own)
        }
    } else {
        emptyList()
    }
    val stageBg = LocalDeckTheme.current.colors.background
    val isLightStage = wcagRelativeLuminance(stageBg) > 0.35f
    val accent = if (isLightStage) Color(0xFF0D5BD9) else Color(0xFF4CA8FF)
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayCoordinates = it }
            .testTag("ciacola-stage-selection-overlay")
            .semantics { contentDescription = "selected=${selected ?: "none"} highlighted=${entries.size}" },
    ) {
        frameTick
        val own = overlayCoordinates ?: return@Canvas
        if (selected == null || !own.isAttached) return@Canvas
        val pad = 6.dp.toPx()
        val stroke = 3.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()), 0f)
        var labelAnchor: Rect? = null
        for (entry in entries) {
            if (!entry.coordinates.isAttached) continue
            val r = own.localBoundingBoxOf(entry.coordinates, clipBounds = false)
            if (r.width <= 0f || r.height <= 0f) continue
            val inflated = Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
            drawRoundRect(accent.copy(alpha = 0.10f), inflated.topLeft, inflated.size, CornerRadius(8.dp.toPx()))
            drawRoundRect(
                color = accent,
                topLeft = inflated.topLeft,
                size = inflated.size,
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = stroke, pathEffect = dash),
            )
            if (labelAnchor == null || inflated.top < labelAnchor.top) labelAnchor = inflated
        }
        val anchor = labelAnchor ?: return@Canvas
        val label = selection.selectedLabel ?: selected
        val layout = textMeasurer.measure(
            text = label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = STAGE_SELECTION_CHIP_TEXT),
            maxLines = 1,
        )
        val chipPadH = 8.dp.toPx()
        val chipPadV = 4.dp.toPx()
        val chipW = layout.size.width + chipPadH * 2
        val chipH = layout.size.height + chipPadV * 2
        val chipPos = computeSelectionChipOffset(
            anchor = anchor,
            chipSize = Size(chipW, chipH),
            canvasSize = size,
            gapPx = 6.dp.toPx(),
            marginPx = 4.dp.toPx(),
        )
        val chipCorner = CornerRadius(6.dp.toPx())
        drawRoundRect(STAGE_SELECTION_CHIP_FILL, chipPos, Size(chipW, chipH), chipCorner)
        drawRoundRect(accent, chipPos, Size(chipW, chipH), chipCorner, style = Stroke(width = 1.5.dp.toPx()))
        drawText(layout, topLeft = Offset(chipPos.x + chipPadH, chipPos.y + chipPadV))
    }
}

@Composable
private fun MotionArcStageOverlay(
    motionPathPreview: MotionPathPreviewState,
    overridesStore: TimelineOverridesStore,
) {
    if (LocalOverviewOpen.current || LocalSceneMode.current != SceneMode.Story) return
    val selId = motionPathPreview.selectedTrackId ?: return
    if (!selId.startsWith("shared:")) return
    val arc = motionPathPreview.latestArc ?: return
    val activeScene = motionPathPreview.activeStageSceneIndex
    if (activeScene != null && arc.sceneIndex != null && activeScene != arc.sceneIndex) return
    val overrideBend = overridesStore.data().tracks[selId]?.spatial?.arcBend ?: arc.arcBend
    val stageBg = LocalDeckTheme.current.colors.background
    // The theme's motion-path token is contrast-checked against each preset's stage background.
    val arcStrokeColor = LocalDeckTheme.current.colors.motionPathPreview
    val isLightStage = wcagRelativeLuminance(stageBg) > 0.35f

    Canvas(Modifier.fillMaxSize()) {
        val s = arc.startBounds.center
        val e = arc.endBounds.center
        val dx = e.x - s.x
        val dy = e.y - s.y
        val dist = hypot(dx, dy).coerceAtLeast(64f)
        val nx = -dy / dist
        val ny = dx / dist
        val peak = -overrideBend * dist * 0.42f
        val ctrl = Offset((s.x + e.x) * 0.5f + nx * peak, (s.y + e.y) * 0.5f + ny * peak)

        val trajectory = Path().apply {
            moveTo(s.x, s.y)
            quadraticTo(ctrl.x, ctrl.y, e.x, e.y)
        }
        drawPath(
            path = trajectory,
            color = arcStrokeColor,
            style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)),
        )
        drawCircle(color = if (isLightStage) Color.White else Color(0xFF0B0D12), radius = 8.5f, center = s)
        drawCircle(color = arcStrokeColor, radius = 7f, center = s)
        drawCircle(color = if (isLightStage) Color.White else Color(0xFF0B0D12), radius = 8.5f, center = e)
        drawCircle(color = arcStrokeColor, radius = 7f, center = e)
    }
}
