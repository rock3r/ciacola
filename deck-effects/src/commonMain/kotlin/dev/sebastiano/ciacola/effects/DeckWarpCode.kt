package dev.sebastiano.ciacola.effects

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.BasicCode
import dev.bnorm.storyboard.DiffContents
import dev.bnorm.storyboard.Kotlin
import dev.bnorm.storyboard.Language
import dev.bnorm.storyboard.State
import dev.bnorm.storyboard.Token
import dev.bnorm.storyboard.buildDiffList
import dev.bnorm.storyboard.content
import dev.bnorm.storyboard.easel.DeterministicStateHost
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.sebastiano.ciacola.inspector.LocalActiveBeatIndex
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
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
import dev.sebastiano.ciacola.timeline.LocalTimelineOffscreen
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides
import dev.sebastiano.ciacola.timeline.TrackCategory
import dev.sebastiano.ciacola.timeline.timelineTarget

@Composable
fun DeckWarpCodeCard(
    codeStates: List<String>,
    stateTransition: Transition<Int>,
    modifier: Modifier = Modifier,
    language: Language = Kotlin,
    textStyle: TextStyle = LocalDeckTheme.current.typography.code,
    elementId: String? = null,
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
    val offsetX = (patch?.offsetXDp ?: 0f).dp
    val offsetY = (patch?.offsetYDp ?: 0f).dp
    val sceneCtx = LocalActiveSceneTimeline.current
    val collector = LocalTimelineHarvestCollector.current
    val warpTrackId = if (sceneCtx != null) "s${sceneCtx.sceneIndex}:warp-code" else "warp-code"
    val warpOverride = LocalTimelineOverrides.current.tracks[warpTrackId]
    val tokenDurationMs = (warpOverride?.durationMs ?: theme.motion.warpTokenDurationMs).coerceAtLeast(40)

    SideEffect {
        if (sceneCtx != null && codeStates.size > 1) {
            collector?.record(
                HarvestedTrackRecord(
                    trackId = warpTrackId,
                    label = "Warp Code Morph",
                    category = TrackCategory.WarpCode,
                    sceneIndex = sceneCtx.sceneIndex,
                    triggerBeat = warpOverride?.enterBeat ?: 3.coerceAtMost((sceneCtx.totalBeats - 1).coerceAtLeast(0)),
                    durationMs = tokenDurationMs,
                    delayMs = warpOverride?.delayMs ?: 0,
                )
            )
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
    // Stagger Deletions -> Movement (+ Card Bounds Morph) -> Additions so outgoing clutter clears
    // first, retained tokens and the card body glide together, and inserted tokens materialize in
    // place inside their newly opened slots after moving tokens have cleared out of the way.
    val startDelayMs = (warpOverride?.delayMs ?: 0).coerceAtLeast(0)
    val fromIdx = stateTransition.currentState.coerceIn(0, diffList.lastIndex)
    val toIdx = stateTransition.targetState.coerceIn(0, diffList.lastIndex)
    val sharedKeys = remember(diffList, fromIdx, toIdx) {
        val fromKeys = diffList[fromIdx].rows.flatten().mapNotNullTo(HashSet()) { it.warpStableKey() }
        val toKeys = diffList[toIdx].rows.flatten().mapNotNullTo(HashSet()) { it.warpStableKey() }
        fromKeys intersect toKeys
    }
    fun segmentHasDeletes(segFromRaw: Int, segToRaw: Int): Boolean {
        val sf = segFromRaw.coerceIn(0, diffList.lastIndex)
        val st = segToRaw.coerceIn(0, diffList.lastIndex)
        if (sf == st) return false
        val toKeys = diffList[st].rows.flatten().mapNotNullTo(HashSet()) { it.warpStableKey() }
        return diffList[sf].rows.flatten().any { s ->
            s.content().isNotBlank() && s.warpStableKey().let { k -> k == null || k !in toKeys }
        }
    }
    val hasVisibleDeletes = remember(diffList, fromIdx, toIdx) { segmentHasDeletes(fromIdx, toIdx) }
    val deleteDurationMs = (tokenDurationMs * 0.48f).roundToInt().coerceAtLeast(40)
    val moveDelayMs = startDelayMs + if (hasVisibleDeletes) (tokenDurationMs * 0.25f).roundToInt() else 0
    val moveDurationMs = (tokenDurationMs * 1.05f).roundToInt().coerceAtLeast(60)
    val insertDelayMs = moveDelayMs + (moveDurationMs * 0.65f).roundToInt()
    val insertDurationMs = (tokenDurationMs * 0.65f).roundToInt().coerceAtLeast(50)

    val warpBoundsTransform = remember(moveDelayMs, moveDurationMs) {
        BoundsTransform { _, _ -> tween(moveDurationMs, delayMillis = moveDelayMs, easing = FastOutSlowInEasing) }
    }

    val currentCodeSnippet = codeStates[stateTransition.targetState.coerceIn(0, codeStates.lastIndex)]
    val shape = RoundedCornerShape(effectiveCornerRadius)
    val reducedMotion = LocalReducedMotion.current
    val registry = LocalStageInspectionRegistry.current
    val beatIndex = LocalActiveBeatIndex.current
    val rootHolder = LocalStageRootCoordinates.current
    val shouldInspect = elementId != null && registry != null && !LocalOverviewOpen.current && !LocalTimelineOffscreen.current
    val ownerToken = remember { Any() }
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    if (shouldInspect && elementId != null) {
        DisposableEffect(registry, elementId) {
            onDispose { registry?.remove(elementId, ownerToken) }
        }
        val coordsNow = cardCoords
        SideEffect {
            registry?.recordMetadata(
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
                registry?.record(
                    snapshot = StageNodeSnapshot(
                        elementId = elementId,
                        kind = "WarpCodeCard",
                        label = currentCodeSnippet.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "WarpCodeCard" },
                        sceneIndex = sceneCtx?.sceneIndex ?: 0,
                        beatIndex = beatIndex,
                        stageBounds = bounds,
                        resolvedText = currentCodeSnippet,
                        effectiveForeground = palette.foreground,
                        effectiveBackground = effectiveFill,
                        effectiveFontSizeSp = if (codeTextStyle.fontSize.isSp) codeTextStyle.fontSize.value else 18f,
                        isEditable = true,
                    ),
                    owner = ownerToken,
                )
            }
        }
    }

    val offsetMod = if (offsetX != 0.dp || offsetY != 0.dp) Modifier.offset(x = offsetX, y = offsetY) else Modifier

    CompositionLocalProvider(LocalEffectiveBackgroundColor provides effectiveFill) {
    Box(
        modifier = modifier
            .then(offsetMod)
            .onPlaced { cardCoords = it }
            .reviewTarget(
                kind = "WarpCodeCard",
                label = currentCodeSnippet.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "WarpCodeCard" },
                textSnippet = currentCodeSnippet,
            )
            .timelineTarget(warpTrackId),
    ) {
        if (reducedMotion) {
            val fadeSpec = tween<Float>(durationMillis = REDUCED_MOTION_FADE_DURATION_MS)
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                stateTransition.DeterministicStateHost(
                    enter = { _, _ -> fadeIn(fadeSpec) },
                    exit = { _, _ -> fadeOut(fadeSpec) },
                ) { rawIndex ->
                    val clampedIndex = rawIndex.coerceIn(0, diffList.lastIndex)
                    val diffContents = diffList[clampedIndex]
                    Column(
                        Modifier
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
                        diffContents.rows.forEach { rowStates ->
                            Row {
                                rowStates.forEach { state ->
                                    if (state == State.Empty) return@forEach
                                    val text = state.content()
                                    if (text.isBlank()) {
                                        Text(
                                            text = text,
                                            style = codeTextStyle,
                                            softWrap = false,
                                            maxLines = 1,
                                        )
                                    } else {
                                        val colorToken = state.resolveWarpColorToken()
                                        Text(
                                            text = text,
                                            style = codeTextStyle,
                                            color = colorToken.colorIn(palette),
                                            softWrap = false,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            SharedTransitionLayout(Modifier.horizontalScroll(rememberScrollState())) {
            stateTransition.DeterministicStateHost(
                enter = { _, _ -> fadeIn(tween(insertDurationMs, delayMillis = insertDelayMs, easing = EaseOutCubic)) },
                exit = { _, _ -> fadeOut(tween(deleteDurationMs, delayMillis = startDelayMs, easing = EaseInCubic)) },
            ) { rawIndex ->
                val clampedIndex = rawIndex.coerceIn(0, diffList.lastIndex)
                val diffContents = diffList[clampedIndex]
                // The card body is itself a shared bounds between the outgoing and incoming code
                // states, so its size morphs with the exact same transform and timing as the tokens
                // it holds, instead of snapping on the first or last frame.
                Column(
                    with(this@SharedTransitionLayout) {
                        Modifier
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "warp-card-body"),
                                animatedVisibilityScope = this@DeterministicStateHost,
                                enter = EnterTransition.None,
                                exit = ExitTransition.None,
                                boundsTransform = warpBoundsTransform,
                                zIndexInOverlay = -1f,
                            )
                            .clip(shape)
                            .let { if (effectiveFill.alpha > 0f) it.background(effectiveFill, shape) else it }
                            .let {
                                if (effectiveBorderWidth > 0.dp && effectiveBorderColor.alpha > 0f) {
                                    it.border(effectiveBorderWidth, effectiveBorderColor, shape)
                                } else {
                                    it
                                }
                            }
                            .padding(effectivePadding)
                    },
                ) {
                    diffContents.rows.forEachIndexed { rowIdx, rowStates ->
                        Row {
                            rowStates.forEachIndexed { colIdx, state ->
                                if (state == State.Empty) return@forEachIndexed
                                val text = state.content()
                                if (text.isBlank()) {
                                    Text(
                                        text = text,
                                        style = codeTextStyle,
                                        softWrap = false,
                                        maxLines = 1,
                                    )
                                } else {
                                    val stableKey = state.warpStableKey()
                                    val isRetained = (fromIdx == toIdx) ||
                                        (stableKey != null && stableKey in sharedKeys) ||
                                        (state is State.Match && stableKey == null)
                                    val colorToken = state.resolveWarpColorToken()
                                    if (isRetained) {
                                        val sharedKey = stableKey
                                            ?: (state as? State.Match)?.sharedWarpKey(rowIdx, colIdx)
                                            ?: "warp-retained:$rowIdx:$colIdx"
                                        with(this@SharedTransitionLayout) {
                                            Text(
                                                text = text,
                                                style = codeTextStyle,
                                                color = colorToken.colorIn(palette),
                                                softWrap = false,
                                                maxLines = 1,
                                                modifier = Modifier.sharedElement(
                                                    sharedContentState = rememberSharedContentState(key = sharedKey),
                                                    animatedVisibilityScope = this@DeterministicStateHost,
                                                    boundsTransform = warpBoundsTransform,
                                                ),
                                            )
                                        }
                                    } else {
                                        with(this@DeterministicStateHost) {
                                            Text(
                                                text = text,
                                                style = codeTextStyle,
                                                color = colorToken.colorIn(palette),
                                                softWrap = false,
                                                maxLines = 1,
                                                modifier = Modifier.animateEnterExit(
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
                                                ),
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

private fun Token.colorIn(palette: DeckCodePalette) =
    palette.colorForWarpScope(scope)

private fun State.resolveWarpColorToken(): Token = if (
    this is State.Match &&
    current.scope.startsWith("source.") &&
    !previous.scope.startsWith("source.")
) {
    previous
} else {
    warpToken()
}

private fun State.warpToken(): Token = when (this) {
    is State.Match -> current
    is State.Insert -> token
    is State.Delete -> token
    State.Empty -> error("State.Empty does not hold a token")
}

private fun State.Match.sharedWarpKey(rowIdx: Int, colIdx: Int): String =
    if (previous.hasKey() && current.hasKey()) {
        current.key()
    } else {
        "WarpMatch($previousIdx->$currentIdx@$rowIdx:$colIdx)"
    }

private fun State.warpStableKey(): String? = when (this) {
    is State.Match -> if (current.hasKey()) current.key() else null
    is State.Insert -> if (token.hasKey()) token.key() else null
    is State.Delete -> if (token.hasKey()) token.key() else null
    State.Empty -> null
}
