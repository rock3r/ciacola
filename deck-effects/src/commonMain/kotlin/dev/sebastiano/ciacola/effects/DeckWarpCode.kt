package dev.sebastiano.ciacola.effects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.BasicCode
import dev.bnorm.storyboard.DiffContents
import dev.bnorm.storyboard.Kotlin
import dev.bnorm.storyboard.Language
import dev.bnorm.storyboard.State as WarpTokenState
import dev.bnorm.storyboard.Token
import dev.bnorm.storyboard.buildDiffList
import dev.bnorm.storyboard.content
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.stableSegment
import dev.sebastiano.ciacola.inspector.LocalActiveBeatIndex
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageParentElementId
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.ModifierLocalStageAlpha
import dev.sebastiano.ciacola.inspector.StageElementMetadata
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.DeckCodePalette
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.LocalReducedMotion
import dev.sebastiano.ciacola.theme.REDUCED_MOTION_FADE_DURATION_MS
import dev.sebastiano.ciacola.timeline.HarvestedTrackRecord
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline
import dev.sebastiano.ciacola.timeline.LocalTimelineHarvestCollector
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides
import dev.sebastiano.ciacola.timeline.TrackCategory
import dev.sebastiano.ciacola.timeline.timelineTarget
import kotlin.math.roundToInt

/**
 * Horizontal sizing policy for [DeckWarpCodeCard] when code lines exceed the available card width.
 */
sealed interface CodeFit {
    data object None : CodeFit
    data class ShrinkToWidth(val min: Float = 0.8f) : CodeFit
}

/** Optional test observer notified of `(elementId, fromIdx, toIdx)` resolved by [DeckWarpCodeCard]. */
val LocalWarpSegmentObserver =
    compositionLocalOf<((elementId: String?, fromIdx: Int, toIdx: Int) -> Unit)?> { null }

@Composable
fun DeckWarpCodeCard(
    codeStates: List<String>,
    stateTransition: Transition<Int>,
    modifier: Modifier = Modifier,
    language: Language = Kotlin,
    textStyle: TextStyle = LocalDeckTheme.current.typography.code,
    elementId: String? = null,
    trackId: String? = null,
    highlightedLines: (stateIndex: Int) -> Set<Int>? = { null },
    dimmedLineAlpha: Float = 0.35f,
    fit: CodeFit = CodeFit.None,
) {
    require(codeStates.isNotEmpty()) { "codeStates must not be empty" }
    val theme = LocalDeckTheme.current
    val cardStyle = theme.styles.codeCard
    val palette = theme.colors.code
    val patch = elementId?.let { LocalElementOverrides.current.patchFor(it) }
    val (codeTextStyle, _) = resolveOverriddenTextStyle(
        baseStyle = textStyle,
        resolvedColor = textStyle.color,
        patch = patch,
    )
    val effectiveFill = patch?.fillColorArgb?.let(::Color) ?: palette.background
    val effectiveBorderColor = patch?.borderColorArgb?.let(::Color) ?: theme.colors.border
    val effectiveBorderWidth = (patch?.borderWidthDp ?: cardStyle.borderWidth.value).coerceAtLeast(0f).dp
    val effectiveCornerRadius = (patch?.cornerRadiusDp ?: cardStyle.cornerRadius.value).coerceAtLeast(0f).dp
    val effectivePadding = (patch?.paddingDp ?: cardStyle.padding.value).coerceAtLeast(0f).dp
    val baseOffsetXDp = patch?.offsetXDp ?: 0f
    val baseOffsetYDp = patch?.offsetYDp ?: 0f
    val sceneCtx = LocalActiveSceneTimeline.current
    val collector = LocalTimelineHarvestCollector.current
    val warpTrackId = trackId ?: elementId ?: if (sceneCtx != null) "s${sceneCtx.sceneIndex}:warp-code" else "warp-code"
    val warpOverride = LocalTimelineOverrides.current.tracks[warpTrackId]
    val tokenDurationMs = (warpOverride?.durationMs ?: theme.motion.warpTokenDurationMs).coerceAtLeast(40)

    val activeBeat = LocalActiveBeatIndex.current
    SideEffect {
        if (sceneCtx != null && codeStates.size > 1 && collector != null) {
            val overrideBeat = warpOverride?.enterBeat
            val triggerBeat = when {
                overrideBeat != null -> overrideBeat
                sceneCtx.totalBeats <= 1 -> 0
                stateTransition.targetState > 0 -> {
                    val recorded = collector.records.values.count { it.trackId == warpTrackId }
                    if (recorded < codeStates.size - 1) activeBeat else null
                }
                else -> null
            }
            if (triggerBeat != null) {
                collector.record(
                    HarvestedTrackRecord(
                        trackId = warpTrackId,
                        label = "Warp Code Morph",
                        category = TrackCategory.WarpCode,
                        sceneIndex = sceneCtx.sceneIndex,
                        triggerBeat = triggerBeat,
                        durationMs = tokenDurationMs,
                        delayMs = warpOverride?.delayMs ?: 0,
                    )
                )
            }
        }
    }

    val diffList: List<DiffContents> = remember(codeStates, language) {
        val blocks = if (codeStates.size == 1) {
            listOf(BasicCode(codeStates[0]), BasicCode(codeStates[0]))
        } else {
            codeStates.map(::BasicCode)
        }
        buildDiffList(blocks, language)
    }

    val (rawFromIdx, rawToIdx) = stateTransition.stableSegment()
    val fromIdx = rawFromIdx.coerceIn(0, diffList.lastIndex)
    val toIdx = rawToIdx.coerceIn(0, diffList.lastIndex)
    LocalWarpSegmentObserver.current?.invoke(elementId, fromIdx, toIdx)

    val sharedKeys = remember(diffList, fromIdx, toIdx) {
        val fromKeys = diffList[fromIdx].rows.flatten().mapNotNullTo(HashSet()) { it.warpStableKey() }
        val toKeys = diffList[toIdx].rows.flatten().mapNotNullTo(HashSet()) { it.warpStableKey() }
        fromKeys intersect toKeys
    }
    val fromRowByKey = remember(diffList, fromIdx) {
        buildMap {
            diffList[fromIdx].rows.forEachIndexed { rowIdx, rowStates ->
                rowStates.forEach { st ->
                    val k = st.warpStableKey()
                    if (k != null) put(k, rowIdx)
                }
            }
        }
    }
    val hasVisibleDeletes = remember(diffList, fromIdx, toIdx) {
        if (fromIdx == toIdx) {
            false
        } else {
            val toKeys = diffList[toIdx].rows.flatten().mapNotNullTo(HashSet()) { it.warpStableKey() }
            diffList[fromIdx].rows.flatten().any { s ->
                s.content().isNotBlank() && s.warpStableKey().let { k -> k == null || k !in toKeys }
            }
        }
    }

    // Stagger Deletions -> Movement (+ Card Size Morph) -> Additions with overlap between delete
    // tail and insert head so the card never flashes empty mid-morph.
    val startDelayMs = (warpOverride?.delayMs ?: 0).coerceAtLeast(0)
    val deleteDurationMs = (tokenDurationMs * 0.48f).roundToInt().coerceAtLeast(40)
    val moveDelayMs = startDelayMs + if (hasVisibleDeletes) (tokenDurationMs * 0.15f).roundToInt() else 0
    val moveDurationMs = (tokenDurationMs * 0.70f).roundToInt().coerceAtLeast(60)
    val insertDelayMs = moveDelayMs + (moveDurationMs * 0.40f).roundToInt()
    val insertDurationMs = (tokenDurationMs * 0.45f).roundToInt().coerceAtLeast(50)

    val warpBoundsTransform = remember(moveDelayMs, moveDurationMs) {
        BoundsTransform { _, _ -> tween(moveDurationMs, delayMillis = moveDelayMs, easing = FastOutSlowInEasing) }
    }

    val reducedMotion = LocalReducedMotion.current
    val sizeSpec = remember(reducedMotion, moveDelayMs, moveDurationMs) {
        if (reducedMotion) {
            tween<Float>(durationMillis = REDUCED_MOTION_FADE_DURATION_MS)
        } else {
            tween<Float>(durationMillis = moveDurationMs, delayMillis = moveDelayMs, easing = FastOutSlowInEasing)
        }
    }

    val currentCodeSnippet = codeStates[toIdx.coerceIn(0, codeStates.lastIndex)]
    val shape = RoundedCornerShape(effectiveCornerRadius)
    val registry = LocalStageInspectionRegistry.current
    val dragPreview = LocalStageDragPreview.current
    val parentElementId = LocalStageParentElementId.current
    val beatIndex = LocalActiveBeatIndex.current
    val rootHolder = LocalStageRootCoordinates.current
    val shouldInspect = elementId != null && registry != null && !LocalOverviewOpen.current
    val ownerToken = remember { Any() }
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var stageAlphaProvider by remember { mutableStateOf<() -> Float>({ 1f }) }
    var reportedVisualOverflow by remember { mutableStateOf(false) }

    fun publishCardSnapshot(
        coordsNow: LayoutCoordinates?,
        overflowNow: Boolean,
        effectiveFontSizeSp: Float,
    ) {
        if (!shouldInspect) return
        registry.recordMetadata(
            meta = StageElementMetadata(
                elementId = elementId,
                supportsBoxStyle = true,
                defaultFillColor = palette.background,
                defaultBorderColor = theme.colors.border,
                defaultBorderWidthDp = cardStyle.borderWidth.value,
                defaultCornerRadiusDp = cardStyle.cornerRadius.value,
                defaultPaddingDp = cardStyle.padding.value,
            ),
            owner = ownerToken,
        )
        val bounds = resolveStageBounds(coordsNow, rootHolder)
        if (bounds != null) {
            registry.record(
                snapshot = StageNodeSnapshot(
                    elementId = elementId,
                    kind = "WarpCodeCard",
                    label = currentCodeSnippet.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "WarpCodeCard" },
                    sceneIndex = sceneCtx?.sceneIndex ?: 0,
                    beatIndex = beatIndex,
                    stageBounds = bounds,
                    resolvedText = currentCodeSnippet,
                    hasVisualOverflow = overflowNow,
                    effectiveForeground = palette.foreground,
                    effectiveBackground = effectiveFill,
                    effectiveFontSizeSp = effectiveFontSizeSp,
                    isEditable = true,
                    parentElementId = parentElementId,
                ),
                owner = ownerToken,
                alphaProvider = { stageAlphaProvider() },
                coordinatesProvider = { cardCoords },
                stageRootProvider = { rootHolder?.coordinates },
            )
        }
    }

    val offsetMod = if (baseOffsetXDp != 0f || baseOffsetYDp != 0f || dragPreview != null) {
        Modifier.offset {
            val previewDelta = if (dragPreview != null && elementId != null) {
                dragPreview.offsetFor(elementId)
            } else {
                DpOffset.Zero
            }
            IntOffset(
                x = (baseOffsetXDp.dp + previewDelta.x).roundToPx(),
                y = (baseOffsetYDp.dp + previewDelta.y).roundToPx(),
            )
        }
    } else {
        Modifier
    }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val clampedDimAlpha = dimmedLineAlpha.coerceIn(0f, 1f)
    val anchorRegistry = LocalStageAnchorRegistry.current

    if (elementId != null && anchorRegistry != null) {
        DisposableEffect(anchorRegistry, elementId) {
            onDispose { anchorRegistry.remove(elementId, ownerToken) }
        }
    }

    fun lineAlphaFor(stateIndex: Int, lineIndex: Int): Float {
        val active = highlightedLines(stateIndex) ?: return 1f
        return if (lineIndex in active) 1f else clampedDimAlpha
    }

    CompositionLocalProvider(LocalEffectiveBackgroundColor provides effectiveFill) {
        BoxWithConstraints(
            modifier = modifier
                .modifierLocalConsumer { stageAlphaProvider = ModifierLocalStageAlpha.current }
                .then(offsetMod)
                .onPlaced { coords ->
                    cardCoords = coords
                    if (elementId != null) {
                        anchorRegistry?.record(elementId, coords, ownerToken)
                    }
                }
                .reviewTarget(
                    kind = "WarpCodeCard",
                    label = currentCodeSnippet.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "WarpCodeCard" },
                    textSnippet = currentCodeSnippet,
                )
                .timelineTarget(warpTrackId),
            propagateMinConstraints = true,
        ) {
            val innerMaxWidthPx = if (constraints.hasBoundedWidth) {
                (constraints.maxWidth - with(density) { (effectivePadding * 2).roundToPx() }).coerceAtLeast(1)
            } else {
                Int.MAX_VALUE
            }

            fun measureMaxRowWidthPx(styleToMeasure: TextStyle): Int {
                var widest = 0
                for (diff in diffList) {
                    for (row in diff.rows) {
                        var rowWidth = 0
                        for (st in row) {
                            if (st == WarpTokenState.Empty) continue
                            val text = st.content()
                            if (text.isEmpty()) continue
                            rowWidth += textMeasurer.measure(
                                text = text,
                                style = styleToMeasure,
                                softWrap = false,
                                maxLines = 1,
                            ).size.width
                        }
                        if (rowWidth > widest) widest = rowWidth
                    }
                }
                return widest
            }

            val fittedStyle = remember(diffList, codeTextStyle, fit, innerMaxWidthPx, density) {
                if (fit !is CodeFit.ShrinkToWidth || innerMaxWidthPx == Int.MAX_VALUE) {
                    codeTextStyle
                } else {
                    val naturalMaxPx = measureMaxRowWidthPx(codeTextStyle)
                    if (naturalMaxPx <= innerMaxWidthPx || naturalMaxPx <= 0) {
                        codeTextStyle
                    } else {
                        val minScale = fit.min.coerceIn(0.1f, 1f)
                        val initialScale = (innerMaxWidthPx.toFloat() / naturalMaxPx.toFloat()).coerceIn(minScale, 1f)
                        val firstPass = codeTextStyle.scaleFontSize(initialScale)
                        val firstPassPx = measureMaxRowWidthPx(firstPass)
                        if (firstPassPx <= innerMaxWidthPx || initialScale <= minScale) {
                            firstPass
                        } else {
                            val adjustedScale = (initialScale * innerMaxWidthPx.toFloat() / firstPassPx.toFloat() * 0.98f)
                                .coerceIn(minScale, 1f)
                            codeTextStyle.scaleFontSize(adjustedScale)
                        }
                    }
                }
            }
            val effectiveFontSizeSp = if (fittedStyle.fontSize.isSp) fittedStyle.fontSize.value else 18f

            if (shouldInspect) {
                DisposableEffect(registry, elementId) {
                    onDispose { registry.remove(elementId, ownerToken) }
                }
                val coordsNow = cardCoords
                observeStageRootCoordinates(rootHolder)
                val overflowNow = reportedVisualOverflow
                SideEffect {
                    publishCardSnapshot(coordsNow, overflowNow, effectiveFontSizeSp)
                }
            }

            // Single outer card surface: width and height lerp smoothly via WarpStateHost's Layout
            // so there is never a second border or overlapping sharedBounds card body mid-morph.
            Box(
                modifier = Modifier
                    .testTag("warp-card-surface")
                    .clip(shape)
                    .let { if (effectiveFill.alpha > 0f) it.background(effectiveFill, shape) else it }
                    .let {
                        if (effectiveBorderWidth > 0.dp && effectiveBorderColor.alpha > 0f) {
                            it.border(effectiveBorderWidth, effectiveBorderColor, shape)
                        } else {
                            it
                        }
                    }
                    .padding(effectivePadding),
            ) {
                if (reducedMotion) {
                    val fadeSpec = tween<Float>(durationMillis = REDUCED_MOTION_FADE_DURATION_MS)
                    stateTransition.WarpStateHost(
                        fromIdx = fromIdx,
                        toIdx = toIdx,
                        maxStateIndex = diffList.lastIndex,
                        sizeSpec = sizeSpec,
                        enter = fadeIn(fadeSpec),
                        exit = fadeOut(fadeSpec),
                        onVisualOverflow = { overflow ->
                            if (reportedVisualOverflow != overflow) reportedVisualOverflow = overflow
                        },
                    ) { clampedIndex, _ ->
                        val diffContents = diffList[clampedIndex]
                        Column {
                            diffContents.rows.forEachIndexed { rowIdx, rowStates ->
                                val lineAlpha = lineAlphaFor(clampedIndex, rowIdx)
                                if (rowStates.isEmpty()) {
                                    Text(
                                        text = " ",
                                        style = fittedStyle,
                                        softWrap = false,
                                        maxLines = 1,
                                    )
                                } else {
                                    Row {
                                        rowStates.forEach { state ->
                                            if (state == WarpTokenState.Empty) return@forEach
                                            val text = state.content()
                                            if (text.isBlank()) {
                                                Text(
                                                    text = text,
                                                    style = fittedStyle,
                                                    softWrap = false,
                                                    maxLines = 1,
                                                )
                                            } else {
                                                val colorToken = state.resolveWarpColorToken()
                                                Text(
                                                    text = text,
                                                    style = fittedStyle,
                                                    color = colorToken.colorIn(palette),
                                                    softWrap = false,
                                                    maxLines = 1,
                                                    modifier = Modifier.graphicsLayer { alpha = lineAlpha },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    SharedTransitionLayout {
                        stateTransition.WarpStateHost(
                            fromIdx = fromIdx,
                            toIdx = toIdx,
                            maxStateIndex = diffList.lastIndex,
                            sizeSpec = sizeSpec,
                            enter = fadeIn(tween(insertDurationMs, delayMillis = insertDelayMs, easing = EaseOutCubic)),
                            exit = fadeOut(tween(deleteDurationMs, delayMillis = startDelayMs, easing = EaseInCubic)),
                            onVisualOverflow = { overflow ->
                                if (reportedVisualOverflow != overflow) reportedVisualOverflow = overflow
                            },
                        ) { clampedIndex, morphProgress ->
                            val diffContents = diffList[clampedIndex]
                            Column {
                                diffContents.rows.forEachIndexed { rowIdx, rowStates ->
                                    val lineAlpha = lineAlphaFor(clampedIndex, rowIdx)
                                    if (rowStates.isEmpty()) {
                                        Text(
                                            text = " ",
                                            style = fittedStyle,
                                            softWrap = false,
                                            maxLines = 1,
                                        )
                                    } else {
                                        Row {
                                            rowStates.forEachIndexed { colIdx, state ->
                                                if (state == WarpTokenState.Empty) return@forEachIndexed
                                                val text = state.content()
                                                if (text.isBlank()) {
                                                    Text(
                                                        text = text,
                                                        style = fittedStyle,
                                                        softWrap = false,
                                                        maxLines = 1,
                                                    )
                                                } else {
                                                    val stableKey = state.warpStableKey()
                                                    val isRetained = (fromIdx == toIdx) ||
                                                        (stableKey != null && stableKey in sharedKeys) ||
                                                        (state is WarpTokenState.Match && stableKey == null)
                                                    val colorToken = state.resolveWarpColorToken()
                                                    if (isRetained) {
                                                        val sharedKey = stableKey
                                                            ?: (state as? WarpTokenState.Match)?.sharedWarpKey(rowIdx, colIdx)
                                                            ?: "warp-retained:$rowIdx:$colIdx"
                                                        val fromRowIdx = stableKey?.let { fromRowByKey[it] } ?: rowIdx
                                                        val fromAlpha = lineAlphaFor(fromIdx, fromRowIdx)
                                                        val toAlpha = lineAlphaFor(toIdx, rowIdx)
                                                        with(this@SharedTransitionLayout) {
                                                            Text(
                                                                text = text,
                                                                style = fittedStyle,
                                                                color = colorToken.colorIn(palette),
                                                                softWrap = false,
                                                                maxLines = 1,
                                                                modifier = Modifier
                                                                    .sharedElement(
                                                                        sharedContentState = rememberSharedContentState(key = sharedKey),
                                                                        animatedVisibilityScope = this@WarpStateHost,
                                                                        boundsTransform = warpBoundsTransform,
                                                                    )
                                                                    .graphicsLayer {
                                                                        alpha = if (fromIdx == toIdx) {
                                                                            toAlpha
                                                                        } else {
                                                                            val p = morphProgress().coerceIn(0f, 1f)
                                                                            fromAlpha + (toAlpha - fromAlpha) * p
                                                                        }
                                                                    },
                                                            )
                                                        }
                                                    } else {
                                                        with(this@WarpStateHost) {
                                                            Text(
                                                                text = text,
                                                                style = fittedStyle,
                                                                color = colorToken.colorIn(palette),
                                                                softWrap = false,
                                                                maxLines = 1,
                                                                modifier = Modifier
                                                                    .animateEnterExit(
                                                                        enter = fadeIn(
                                                                            tween(
                                                                                insertDurationMs,
                                                                                delayMillis = insertDelayMs,
                                                                                easing = EaseOutCubic,
                                                                            )
                                                                        ),
                                                                        exit = fadeOut(
                                                                            tween(
                                                                                deleteDurationMs,
                                                                                delayMillis = startDelayMs,
                                                                                easing = EaseInCubic,
                                                                            )
                                                                        ),
                                                                    )
                                                                    .graphicsLayer { alpha = lineAlpha },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Deterministic, seek-safe state host for [DeckWarpCodeCard] that mounts only `[toIdx]` at rest
 * and `[fromIdx, toIdx]` during a transition, interpolating container bounds smoothly from a single
 * `PreEnter -> Visible` [sizeSpec] animation on the incoming branch.
 */
@Composable
private fun Transition<Int>.WarpStateHost(
    fromIdx: Int,
    toIdx: Int,
    maxStateIndex: Int,
    sizeSpec: FiniteAnimationSpec<Float>,
    enter: EnterTransition,
    exit: ExitTransition,
    onVisualOverflow: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.(stateIdx: Int, morphProgress: () -> Float) -> Unit,
) {
    val active = if (fromIdx == toIdx) listOf(toIdx) else listOf(fromIdx, toIdx)
    val sizeProgressHolder = remember { arrayOfNulls<State<Float>>(1) }
    if (active.size < 2) sizeProgressHolder[0] = null
    val morphProgress: () -> Float = { sizeProgressHolder[0]?.value ?: 1f }

    Layout(
        modifier = modifier,
        content = {
            for (stateIdx in active) {
                key(stateIdx) {
                    AnimatedVisibility(
                        visible = { raw -> raw.coerceIn(0, maxStateIndex) == stateIdx },
                        enter = enter,
                        exit = exit,
                    ) {
                        if (active.size == 2 && stateIdx == toIdx) {
                            sizeProgressHolder[0] = transition.animateFloat(
                                transitionSpec = { sizeSpec },
                                label = "WarpCardSizeProgress",
                            ) { enterExit ->
                                if (enterExit == EnterExitState.Visible) 1f else 0f
                            }
                        }
                        content(stateIdx, morphProgress)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // Measure code rows unbounded horizontally so natural line width is never prematurely
        // clamped; overflow against bounded card constraints is then reported accurately.
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity)
        val placeables = measurables.map { it.measure(childConstraints) }
        val targetNaturalWidth = if (placeables.size == 2) {
            placeables[1].width
        } else {
            placeables.firstOrNull()?.width ?: 0
        }
        val targetNaturalHeight = if (placeables.size == 2) {
            placeables[1].height
        } else {
            placeables.firstOrNull()?.height ?: 0
        }
        if (!isLookingAhead) {
            val overflow = (constraints.hasBoundedWidth && targetNaturalWidth > constraints.maxWidth + 2) ||
                (constraints.hasBoundedHeight && targetNaturalHeight > constraints.maxHeight + 2)
            onVisualOverflow(overflow)
        }
        val (width, height) = if (placeables.size == 2) {
            if (isLookingAhead) {
                placeables[1].width to placeables[1].height
            } else {
                val p = (sizeProgressHolder[0]?.value ?: 1f).coerceIn(0f, 1f)
                val w = placeables[0].width + (placeables[1].width - placeables[0].width) * p
                val h = placeables[0].height + (placeables[1].height - placeables[0].height) * p
                w.roundToInt() to h.roundToInt()
            }
        } else {
            (placeables.firstOrNull()?.width ?: 0) to (placeables.firstOrNull()?.height ?: 0)
        }
        layout(
            width = width.coerceIn(constraints.minWidth, constraints.maxWidth),
            height = height.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

private fun TextStyle.scaleFontSize(scale: Float): TextStyle {
    val scaledSize = if (fontSize.isSp) (fontSize.value * scale).coerceAtLeast(6f).sp else fontSize
    val scaledLineHeight = if (lineHeight.isSp) (lineHeight.value * scale).coerceAtLeast(8f).sp else lineHeight
    return copy(fontSize = scaledSize, lineHeight = scaledLineHeight)
}

private fun Token.colorIn(palette: DeckCodePalette) =
    palette.colorForWarpScope(scope)

private fun WarpTokenState.resolveWarpColorToken(): Token = if (
    this is WarpTokenState.Match &&
    current.scope.startsWith("source.") &&
    !previous.scope.startsWith("source.")
) {
    previous
} else {
    warpToken()
}

private fun WarpTokenState.warpToken(): Token = when (this) {
    is WarpTokenState.Match -> current
    is WarpTokenState.Insert -> token
    is WarpTokenState.Delete -> token
    WarpTokenState.Empty -> error("State.Empty does not hold a token")
}

private fun WarpTokenState.Match.sharedWarpKey(rowIdx: Int, colIdx: Int): String =
    if (previous.hasKey() && current.hasKey()) {
        current.key()
    } else {
        "WarpMatch($previousIdx->$currentIdx@$rowIdx:$colIdx)"
    }

private fun WarpTokenState.warpStableKey(): String? = when (this) {
    is WarpTokenState.Match -> if (current.hasKey()) current.key() else null
    is WarpTokenState.Insert -> if (token.hasKey()) token.key() else null
    is WarpTokenState.Delete -> if (token.hasKey()) token.key() else null
    WarpTokenState.Empty -> null
}
