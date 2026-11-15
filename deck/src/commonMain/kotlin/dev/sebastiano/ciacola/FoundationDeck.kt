package dev.sebastiano.ciacola

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.SceneEnterTransition
import dev.bnorm.storyboard.SceneExitTransition
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.SceneScope
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.StoryboardBuilder
import dev.bnorm.storyboard.easel.DeterministicStateHost
import dev.bnorm.storyboard.easel.stableSegment
import dev.bnorm.storyboard.layout.decorated.DecorationLayout
import dev.bnorm.storyboard.map
import dev.bnorm.storyboard.plus
import dev.bnorm.storyboard.text.highlight.CodeStyle
import dev.bnorm.storyboard.text.highlight.Language
import dev.bnorm.storyboard.text.highlight.highlight
import dev.bnorm.storyboard.toValue
import dev.sebastiano.ciacola.authoring.DeckHostParams
import dev.sebastiano.ciacola.authoring.SlideLayers
import dev.sebastiano.ciacola.authoring.deckChoreographedScene
import dev.sebastiano.ciacola.authoring.deckRootDecorator
import dev.sebastiano.ciacola.authoring.slideEnterTransition
import dev.sebastiano.ciacola.authoring.slideExitTransition
import dev.sebastiano.ciacola.effects.AnimatedCalloutOutline
import dev.sebastiano.ciacola.effects.AnimatedLine
import dev.sebastiano.ciacola.effects.AnimatedStrikeout
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.effects.DeckSurfaceCard
import dev.sebastiano.ciacola.effects.DeckWarpCodeCard
import dev.sebastiano.ciacola.effects.DottedLeaderRow
import dev.sebastiano.ciacola.effects.KeynoteStamp
import dev.sebastiano.ciacola.effects.MagnifierLoupeStage
import dev.sebastiano.ciacola.effects.animateBuildProgress
import dev.sebastiano.ciacola.effects.animateStaggerGroup
import dev.sebastiano.ciacola.effects.editableBox
import dev.sebastiano.ciacola.effects.effectiveBackgroundColor
import dev.sebastiano.ciacola.effects.keynoteFadeMoveTracked
import dev.sebastiano.ciacola.effects.keynoteFromDarkness
import dev.sebastiano.ciacola.effects.keynotePop
import dev.sebastiano.ciacola.effects.keynoteSpotlight
import dev.sebastiano.ciacola.effects.spatialEntranceOffset
import dev.sebastiano.ciacola.inspector.DeckElementOverridesFile
import dev.sebastiano.ciacola.inspector.LocalActiveBeatIndex
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.StageRootCoordinatesHolder
import dev.sebastiano.ciacola.presenter.DeckSpeakerNotesBuilder
import dev.sebastiano.ciacola.presenter.SceneSpeakerNotesBuilder
import dev.sebastiano.ciacola.presenter.StoryboardSpeakerNotesRegistry
import dev.sebastiano.ciacola.review.ReportSceneHeader
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.LocalReducedMotion
import dev.sebastiano.ciacola.theme.REDUCED_MOTION_FADE_DURATION_MS
import dev.sebastiano.ciacola.theme.withOverrides
import dev.sebastiano.ciacola.timeline.ActiveMotionArcPreview
import dev.sebastiano.ciacola.timeline.ActiveSceneTimelineContext
import dev.sebastiano.ciacola.timeline.DeckTimelineOverridesFile
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline
import dev.sebastiano.ciacola.timeline.LocalMotionPathPreviewState
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides
import dev.sebastiano.ciacola.timeline.SharedMode
import dev.sebastiano.ciacola.timeline.TimelineCurveSpec
import dev.sebastiano.ciacola.timeline.TrackCategory
import dev.sebastiano.ciacola.timeline.VirtualizedFrame
import dev.sebastiano.ciacola.timeline.buildVirtualizedFrames
import dev.sebastiano.ciacola.timeline.computeArcInterpolatedRect
import dev.sebastiano.ciacola.timeline.deckSharedBounds
import dev.sebastiano.ciacola.timeline.timelineTarget
import androidx.compose.animation.AnimatedVisibilityScope

enum class FoundationProof {
    DeterministicNavigation,
    SharedElement,
    StableStateLoop,
    AdaptedVisual,
    ExplanatoryMagicText,
    HighlightedCodeMagicText,
    TalkDesignSystem,
}

enum class FoundationSceneSpec(
    val stateCount: Int,
    val proves: Set<FoundationProof>,
) {
    Beats(
        3,
        setOf(
            FoundationProof.DeterministicNavigation,
            FoundationProof.ExplanatoryMagicText,
            FoundationProof.TalkDesignSystem,
        ),
    ),
    SharedMove(2, setOf(FoundationProof.SharedElement)),
    AmbientLoop(1, setOf(FoundationProof.StableStateLoop, FoundationProof.AdaptedVisual)),
    KotlinCode(3, setOf(FoundationProof.HighlightedCodeMagicText)),
}

fun createFoundationDeck(
    host: DeckHostParams,
    includeKeynoteEffectsLab: Boolean = false,
): Storyboard {
    val notesBuilder = DeckSpeakerNotesBuilder()
    val effectiveTimelineOverrides = if (host.timelineOverrides.hasOverrides) {
        host.timelineOverrides
    } else {
        host.timelineOverridesProvider()
    }
    val storyboard = Storyboard.build(
        title = "Ciacola",
        description = "Provisional scenes proving the presentation architecture; not talk narrative.",
        format = DeckDesign.format,
        decorator = deckRootDecorator(host),
    ) {
        beatsScene(host.theme, effectiveTimelineOverrides, notesBuilder, host.reducedMotion, host.designOverridesProvider)
        sharedMoveScene(host.theme, effectiveTimelineOverrides, notesBuilder, host.reducedMotion, host.designOverridesProvider)
        ambientLoopScene(host.theme, effectiveTimelineOverrides, notesBuilder, host.reducedMotion, host.designOverridesProvider)
        kotlinCodeScene(host.theme, effectiveTimelineOverrides, notesBuilder, host.reducedMotion, host.designOverridesProvider)
        if (includeKeynoteEffectsLab) {
            keynoteEffectsLabScene(host.theme, effectiveTimelineOverrides, notesBuilder, host.reducedMotion, host.designOverridesProvider)
        }
    }
    StoryboardSpeakerNotesRegistry.register(storyboard, notesBuilder.build())
    return storyboard
}

fun createFoundationDeck(
    textRenderer: DeckTextRenderer = MagicTextV1Renderer,
    theme: DeckTheme = DeckThemes.stageDark(),
    bundledPreset: DeckThemePreset? = null,
    includeKeynoteEffectsLab: Boolean = false,
    stageDecorator: ContentDecorator = ContentDecorator.None,
    timelineOverrides: DeckTimelineOverridesFile = DeckTimelineOverridesFile(),
    reducedMotion: Boolean = false,
    elementOverrides: DeckElementOverridesFile = DeckElementOverridesFile(),
    designOverrides: DesignSystemOverrides = DesignSystemOverrides(),
    timelineOverridesProvider: () -> DeckTimelineOverridesFile = { timelineOverrides },
    elementOverridesProvider: () -> DeckElementOverridesFile = { elementOverrides },
    designOverridesProvider: () -> DesignSystemOverrides = { designOverrides },
): Storyboard = createFoundationDeck(
    host = DeckHostParams(
        textRenderer = textRenderer,
        theme = theme,
        bundledPreset = bundledPreset,
        stageDecorator = stageDecorator,
        timelineOverrides = timelineOverrides,
        reducedMotion = reducedMotion,
        elementOverrides = elementOverrides,
        designOverrides = designOverrides,
        timelineOverridesProvider = timelineOverridesProvider,
        elementOverridesProvider = elementOverridesProvider,
        designOverridesProvider = designOverridesProvider,
    ),
    includeKeynoteEffectsLab = includeKeynoteEffectsLab,
)

private fun sharedCardCornerRadiusPx(widthPx: Float, density: Density): Float = with(density) {
    val wDp = widthPx.toDp().value
    val cornerDp = if (wDp <= 240f) {
        val t = ((wDp - 28f) / (240f - 28f)).coerceIn(0f, 1f)
        8f + (24f - 8f) * t
    } else {
        val t = ((wDp - 240f) / (336f - 240f)).coerceIn(0f, 1f)
        24f + (36f - 24f) * t
    }
    cornerDp.dp.toPx()
}

private object SharedCardMorphShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radiusPx = sharedCardCornerRadiusPx(size.width, density)
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                cornerRadius = CornerRadius(radiusPx, radiusPx),
            ),
        )
    }
}

private data class BeatState(val eyebrow: String, val prose: AnnotatedString)

private fun StoryboardBuilder.beatsScene(
    theme: DeckTheme,
    overrides: DeckTimelineOverridesFile,
    notesBuilder: DeckSpeakerNotesBuilder? = null,
    reducedMotion: Boolean = false,
    designOverridesProvider: () -> DesignSystemOverrides = { DesignSystemOverrides() },
) = deckChoreographedScene(
    sceneIndex = 0,
    sceneTitle = "Beats",
    baseFrames = listOf(
        BeatState("FOUNDATION / PROVISIONAL", AnnotatedString("A scene begins with one deterministic state.")),
        BeatState("FOUNDATION / PROVISIONAL", AnnotatedString("A scene advances to a deterministic next state.")),
        BeatState("FOUNDATION / PROVISIONAL", AnnotatedString("The same path is reversible without inventing a second navigation model.")),
    ),
    theme = theme,
    overrides = overrides,
    notesBuilder = notesBuilder,
    reducedMotion = reducedMotion,
    designOverridesProvider = designOverridesProvider,
    enterTransition = slideEnterTransition(),
    exitTransition = slideExitTransition(),
    notes = {
        overview("Open with the core promise: every beat is an explicit state, driven forward and backward by one transition model.")
        beat(0, "[Pause] Establish the baseline state. Point out that nothing runs imperatively on click — advancing targets the next state.")
        beat(1, "[Click] Highlight how MagicText diffs and moves retained words ('A scene', 'deterministic', 'state.') instead of cross-fading the whole sentence.")
        beat(2, "[Click] Watch the bottom-right shared badge appear — it previews the cross-slide Magic Move handoff into Slide 2.")
    },
) {
    val activeTheme = LocalDeckTheme.current
    val prose = transition.createEditableTextTransition("s1:prose", "foundation-prose") { it.prose }
    val (fromFrame, toFrame) = transition.stableSegment()
    val currentVf = fromFrame.toValue()
    val targetVf = toFrame.toValue()
    val badgeProgress by transition.animateFloat(
        transitionSpec = {
            if (reducedMotion) {
                tween(durationMillis = REDUCED_MOTION_FADE_DURATION_MS)
            } else {
                tween(
                    durationMillis = activeTheme.motion.fadeMoveDurationMs,
                    easing = activeTheme.motion.standardEasing,
                )
            }
        },
        label = "shared-badge-progress",
    ) { frame ->
        if (frame.toValue().baseBeatIndex >= 2) 1f else 0f
    }

    SlideLayers(palette = null) {
        FoundationFrame(currentVf.value.eyebrow) {
            DeckMagicText(prose, Modifier.fillMaxSize(), elementId = "s1:prose", label = "Prose")
            if (currentVf.baseBeatIndex >= 2 || targetVf.baseBeatIndex >= 2) {
                val sharedModifier = if (reducedMotion) {
                    Modifier.timelineTarget("shared:foundation-shared-card")
                } else {
                    Modifier.deckSharedBounds(
                        sharedKey = "foundation-shared-card",
                        label = "Shared Card",
                        defaultDurationMs = activeTheme.motion.sharedBoundsDurationMs,
                        currentBeatInScene = maxOf(currentVf.virtualBeatIndex, targetVf.virtualBeatIndex),
                        mode = SharedMode.SingleSurface,
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .then(sharedModifier)
                        .graphicsLayer {
                            val p = badgeProgress.coerceIn(0f, 1f)
                            alpha = p
                            val s = if (reducedMotion) 1f else 0.72f + 0.28f * p
                            scaleX = s
                            scaleY = s
                        }
                        .clip(SharedCardMorphShape)
                        .background(activeTheme.colors.accent, SharedCardMorphShape),
                )
            }
        }
    }
}

private data class SharedMoveState(val expanded: Boolean)

private fun StoryboardBuilder.sharedMoveScene(
    theme: DeckTheme,
    overrides: DeckTimelineOverridesFile,
    notesBuilder: DeckSpeakerNotesBuilder? = null,
    reducedMotion: Boolean = false,
    designOverridesProvider: () -> DesignSystemOverrides = { DesignSystemOverrides() },
) = deckChoreographedScene(
    sceneIndex = 1,
    sceneTitle = "Shared Move",
    baseFrames = listOf(SharedMoveState(false), SharedMoveState(true)),
    theme = theme,
    overrides = overrides,
    notesBuilder = notesBuilder,
    reducedMotion = reducedMotion,
    designOverridesProvider = designOverridesProvider,
    enterTransition = slideEnterTransition(),
    exitTransition = slideExitTransition(),
    notes = {
        overview("Demonstrates Storyboard sharedBounds across scenes and deterministic in-scene morphing.")
        beat(0, "Notice the accent badge from Slide 1 landed cleanly in the top-left slot as a 240dp card.")
        beat(1, "[Click] Card expands to center stage. Mention that reversing (←) or scrubbing follows the exact same arc trajectory.")
    },
) {
    val activeTheme = LocalDeckTheme.current
    val sceneVisibility: AnimatedVisibilityScope = contextOf<AnimatedVisibilityScope>()
    val (fromFrame, toFrame) = transition.stableSegment()
    val fromVf = fromFrame.toValue()
    val targetVf = toFrame.toValue()
    val trackId = "shared:foundation-shared-card"
    val trackOverride = LocalTimelineOverrides.current.tracks[trackId]
    val effectiveDuration = trackOverride?.durationMs ?: activeTheme.motion.sharedBoundsDurationMs
    val effectiveDelay = trackOverride?.delayMs ?: 0
    val effectiveCurve = trackOverride?.curve ?: TimelineCurveSpec()
    val effectiveArcBend = trackOverride?.spatial?.arcBend ?: 0f
    val previewState = LocalMotionPathPreviewState.current

    if (reducedMotion) {
        SlideLayers(palette = null) {
            FoundationFrame("SHARED ELEMENT / PROVISIONAL") {
                val fadeSpec = tween<Float>(durationMillis = REDUCED_MOTION_FADE_DURATION_MS)
                transition.DeterministicStateHost(
                    modifier = Modifier.fillMaxSize(),
                    enter = { _, _ -> fadeIn(fadeSpec) },
                    exit = { _, _ -> fadeOut(fadeSpec) },
                    contentKey = { it.toValue().value.expanded },
                ) { frame ->
                    val expanded = frame.toValue().value.expanded
                    val cardSize = if (expanded) 336.dp else 240.dp
                    val cornerDp = if (expanded) 36.dp else 24.dp
                    val alignment = if (expanded) Alignment.Center else Alignment.TopStart
                    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
                        DeckSurfaceCard(
                            elementId = "s2:card",
                            label = "Shared Card",
                            kind = "SharedCard",
                            fillColor = activeTheme.colors.accent,
                            borderWidth = 0.dp,
                            cornerRadius = cornerDp,
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(cardSize)
                                .timelineTarget(trackId),
                        ) {
                            DeckEditableText(
                                elementId = "s2:card-label",
                                text = "shared",
                                label = "Shared Label",
                                kind = "Heading",
                                style = activeTheme.typography.heading,
                                color = activeTheme.colors.onAccent,
                            )
                        }
                    }
                }
            }
        }
        return@deckChoreographedScene
    }

    val expandFraction by transition.animateFloat(
        transitionSpec = {
            effectiveCurve.toAnimationSpec(effectiveDuration, effectiveDelay)
        },
        label = "shared-card-expand",
    ) { frame ->
        if (frame.toValue().value.expanded) 1f else 0f
    }

    val fromExpanded = fromVf.value.expanded
    val toExpanded = targetVf.value.expanded
    // Keep the shared modifier present at rest on beat 0 and at Frame.Start so S2 -> S1 has a
    // placed source, and remove it while expandFraction animates or at beat 1 so lookahead never
    // retargets mid-expand.
    val attachShared = !fromExpanded && !toExpanded && expandFraction < 0.001f
    val cornerDp = (24f + (36f - 24f) * expandFraction.coerceIn(0f, 1f)).dp
    val cardPatch = LocalElementOverrides.current.patchFor("s2:card")
    val hasExplicitCardChromeOverride = cardPatch?.cornerRadiusDp != null || cardPatch?.borderWidthDp != null

    SlideLayers(palette = null) {
        FoundationFrame("SHARED ELEMENT / PROVISIONAL") {
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    val sharedCardMod = if (attachShared) {
                        with(sceneVisibility) {
                            Modifier.deckSharedBounds(
                                sharedKey = "foundation-shared-card",
                                label = "Shared Card",
                                defaultDurationMs = activeTheme.motion.sharedBoundsDurationMs,
                                currentBeatInScene = targetVf.virtualBeatIndex,
                                mode = SharedMode.SingleSurface,
                            )
                        }
                    } else {
                        Modifier.timelineTarget(trackId)
                    }
                    val surfaceLabelContent: @Composable BoxScope.() -> Unit = {
                        Layout(
                            content = {
                                DeckEditableText(
                                    elementId = "s2:card-label",
                                    text = "shared",
                                    label = "Shared Label",
                                    kind = "Heading",
                                    style = activeTheme.typography.heading,
                                    color = activeTheme.colors.onAccent,
                                    softWrap = false,
                                    maxLines = 1,
                                )
                            },
                        ) { labelMeasurables, labelConstraints ->
                            val cardWidthPx = labelConstraints.maxWidth.toFloat()
                            val cardHeightPx = labelConstraints.maxHeight.toFloat()
                            // Measure the label at its natural final size so it never wraps or
                            // scales down while the card grows from 28 dp to 240 dp.
                            val labelPlaceable = labelMeasurables.single().measure(Constraints())
                            val minW = 28.dp.toPx()
                            val restW = 240.dp.toPx()
                            val handOffProgress = ((cardWidthPx - minW) / (restW - minW)).coerceIn(0f, 1f)
                            val labelAlpha = ((handOffProgress - 0.70f) / 0.30f).coerceIn(0f, 1f)
                            layout(labelConstraints.maxWidth, labelConstraints.maxHeight) {
                                val x = ((cardWidthPx - labelPlaceable.width) / 2f).roundToInt()
                                val y = ((cardHeightPx - labelPlaceable.height) / 2f).roundToInt()
                                labelPlaceable.placeWithLayer(x, y) {
                                    alpha = labelAlpha
                                }
                            }
                        }
                    }
                    if (hasExplicitCardChromeOverride) {
                        DeckSurfaceCard(
                            elementId = "s2:card",
                            label = "Shared Card",
                            kind = "SharedCard",
                            fillColor = activeTheme.colors.accent,
                            borderWidth = 0.dp,
                            cornerRadius = cornerDp,
                            contentAlignment = Alignment.Center,
                            modifier = sharedCardMod,
                            content = surfaceLabelContent,
                        )
                    } else {
                        Box(
                            modifier = sharedCardMod.clip(SharedCardMorphShape),
                        ) {
                            DeckSurfaceCard(
                                elementId = "s2:card",
                                label = "Shared Card",
                                kind = "SharedCard",
                                fillColor = activeTheme.colors.accent,
                                borderWidth = 0.dp,
                                cornerRadius = 0.dp,
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize(),
                                content = surfaceLabelContent,
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val maxW = constraints.maxWidth.toFloat()
                val maxH = constraints.maxHeight.toFloat()
                val collapsedPx = 240.dp.toPx()
                val expandedPx = minOf(336.dp.toPx(), (maxH - 16.dp.toPx()).coerceAtLeast(collapsedPx))
                val startRect = Rect(0f, 0f, collapsedPx, collapsedPx)
                val endRect = Rect(
                    left = (maxW - expandedPx) / 2f,
                    top = (maxH - expandedPx) / 2f,
                    right = (maxW + expandedPx) / 2f,
                    bottom = (maxH + expandedPx) / 2f,
                )
                val stageOffset = Offset(
                    x = activeTheme.styles.slidePadding.toPx(),
                    y = activeTheme.styles.slidePadding.toPx() + 56.dp.toPx(),
                )
                if (previewState != null && previewState.selectedTrackId == trackId) {
                    previewState.latestArc = ActiveMotionArcPreview(
                        sharedKey = "foundation-shared-card",
                        startBounds = startRect.translate(stageOffset),
                        endBounds = endRect.translate(stageOffset),
                        arcBend = effectiveArcBend,
                        sceneIndex = 1,
                    )
                }
                val effectiveExpand = if (isLookingAhead) {
                    if (toExpanded) 1f else 0f
                } else {
                    expandFraction.coerceIn(0f, 1f)
                }
                val currentRect = sharedCardRect(
                    start = startRect,
                    end = endRect,
                    fraction = effectiveExpand,
                    arcBend = effectiveArcBend,
                    minTop = 0f,
                )
                val cardW = currentRect.width.roundToInt().coerceAtLeast(1)
                val cardH = currentRect.height.roundToInt().coerceAtLeast(1)
                val placeable = measurables.single().measure(Constraints.fixed(cardW, cardH))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(currentRect.left.roundToInt(), currentRect.top.roundToInt())
                }
            }
        }
    }
}

/**
 * Slide 2's shared card rect at [fraction] along its (optionally arced) path from [start] to [end],
 * never rising above [minTop] (the content top, just below the eyebrow).
 */
internal fun sharedCardRect(start: Rect, end: Rect, fraction: Float, arcBend: Float, minTop: Float): Rect {
    val rect = computeArcInterpolatedRect(start, end, fraction, arcBend)
    // An upward arc bend may carry the growing card over the eyebrow mid-morph: keep it below.
    return if (rect.top < minTop) rect.translate(0f, minTop - rect.top) else rect
}

private fun StoryboardBuilder.ambientLoopScene(
    theme: DeckTheme,
    overrides: DeckTimelineOverridesFile,
    notesBuilder: DeckSpeakerNotesBuilder? = null,
    reducedMotion: Boolean = false,
    designOverridesProvider: () -> DesignSystemOverrides = { DesignSystemOverrides() },
) = deckChoreographedScene(
    sceneIndex = 2,
    sceneTitle = "Ambient Loop",
    baseFrames = listOf(0),
    theme = theme,
    overrides = overrides,
    notesBuilder = notesBuilder,
    reducedMotion = reducedMotion,
    designOverridesProvider = designOverridesProvider,
    enterTransition = slideEnterTransition(),
    exitTransition = slideExitTransition(),
    notes = {
        overview("Single resting beat with a continuous vertex-mesh backdrop.")
        beat(0, "Emphasize separation of concerns: the ambient loop ticks independently without mutating slide navigation state, and adapts its palette to Stage Dark / Keynote Light.")
    },
) {
    val activeTheme = LocalDeckTheme.current
    SlideLayers(palette = null) {
        Box(Modifier.fillMaxSize()) {
            AnimatedMeshBackdrop(Modifier.fillMaxSize())
            FoundationFrame("CONTINUOUS COMPOSE / PROVISIONAL") {
                DeckSurfaceCard(
                    elementId = "s3:card",
                    label = "Ambient Card",
                    kind = "SurfaceCard",
                    fillColor = activeTheme.colors.surface.copy(alpha = 0.90f),
                    borderColor = activeTheme.colors.border,
                    borderWidth = activeTheme.styles.codeCard.borderWidth,
                    cornerRadius = activeTheme.styles.codeCard.cornerRadius,
                    contentPadding = 48.dp,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DeckEditableText(
                            elementId = "s3:headline",
                            text = "one stable scene state",
                            label = "Ambient Headline",
                            kind = "Heading",
                            style = activeTheme.typography.heading,
                            color = activeTheme.colors.foreground,
                        )
                        DeckEditableText(
                            elementId = "s3:subhead",
                            text = "the visual keeps moving",
                            label = "Ambient Subhead",
                            kind = "Body",
                            style = activeTheme.typography.body,
                            color = activeTheme.colors.muted,
                        )
                    }
                }
            }
        }
    }
}

private data class CodeState(val description: String, val source: String)

private fun StoryboardBuilder.kotlinCodeScene(
    deckTheme: DeckTheme,
    overrides: DeckTimelineOverridesFile,
    notesBuilder: DeckSpeakerNotesBuilder? = null,
    reducedMotion: Boolean = false,
    designOverridesProvider: () -> DesignSystemOverrides = { DesignSystemOverrides() },
) = deckChoreographedScene(
    sceneIndex = 3,
    sceneTitle = "Highlighted Code",
    baseFrames = listOf(
        CodeState("Syntax highlighting is data carried by AnnotatedString.", "fun provisional() = Unit"),
        CodeState("Retained Kotlin tokens move while styles remain attached.", "fun provisional() {\n    println(\"foundation\")\n}"),
        CodeState("The sample remains intentionally small and replaceable.", "fun provisional(message: String) {\n    println(message)\n}"),
    ),
    theme = deckTheme,
    overrides = overrides,
    notesBuilder = notesBuilder,
    reducedMotion = reducedMotion,
    designOverridesProvider = designOverridesProvider,
    enterTransition = slideEnterTransition(),
    exitTransition = slideExitTransition(),
    notes = {
        overview("Syntax-highlighted Kotlin carried as AnnotatedString spans through MagicText.")
        beat(0, "Start with the single-expression function; point out that token styles live on the AnnotatedString itself.")
        beat(1, "[Click] Expression body expands into a block. 'fun provisional()' slides cleanly while retaining its highlight colors.")
        beat(2, "[Click] Introduce parameter 'message: String' and pass it to println — new tokens fade in around stable anchors.")
    },
) {
    val theme = LocalDeckTheme.current
    val codeStyle = remember(theme.colors.code) { theme.colors.code.toCodeStyle() }
    val highlightedCache = remember(codeStyle) { HashMap<String, AnnotatedString>() }
    val description = transition.createEditableTextTransition("s4:description", "code-description") {
        AnnotatedString(it.description)
    }
    val code = transition.createEditableTextTransition(
        elementId = "s4:code",
        label = "highlighted-kotlin",
        formatOverride = { highlightedCache.getOrPut(it) { highlightFoundationKotlin(it, codeStyle) } },
    ) {
        highlightedCache.getOrPut(it.source) { highlightFoundationKotlin(it.source, codeStyle) }
    }

    SlideLayers(palette = null) {
        FoundationFrame("MAGIC TEXT / KOTLIN / PROVISIONAL") {
            Column(verticalArrangement = Arrangement.spacedBy(44.dp)) {
                DeckMagicText(description, elementId = "s4:description", label = "Code Description")
                DeckSurfaceCard(
                    elementId = "s4:code-card",
                    label = "Code Card",
                    kind = "CodeCard",
                    fillColor = theme.colors.code.background,
                    borderColor = theme.colors.border,
                    borderWidth = theme.styles.codeCard.borderWidth,
                    cornerRadius = theme.styles.codeCard.cornerRadius,
                    contentPadding = theme.styles.codeCard.padding,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DeckMagicText(
                        code,
                        Modifier.fillMaxWidth(),
                        theme.typography.code.copy(color = theme.colors.code.foreground),
                        elementId = "s4:code",
                        label = "Kotlin Code",
                        kind = "Code",
                    )
                }
            }
        }
    }
}

private fun highlightFoundationKotlin(
    source: String,
    codeStyle: CodeStyle,
): AnnotatedString = source.highlight(
    codeStyle = codeStyle,
    language = Language.Kotlin,
    identifierStyle = {
        when (it) {
            "provisional" -> codeStyle.functionDeclaration
            "println" -> codeStyle.staticFunctionCall
            else -> null
        }
    },
)

// Provisional interactive lab scene exercising every Keynote effect + DeckWarpCode + MagnifierLoupe.
private fun StoryboardBuilder.keynoteEffectsLabScene(
    deckTheme: DeckTheme,
    overrides: DeckTimelineOverridesFile,
    notesBuilder: DeckSpeakerNotesBuilder? = null,
    reducedMotion: Boolean = false,
    designOverridesProvider: () -> DesignSystemOverrides = { DesignSystemOverrides() },
) = deckChoreographedScene(
    sceneIndex = 4,
    sceneTitle = "Keynote Effects Lab",
    baseFrames = listOf(0, 1, 2, 3, 4),
    theme = deckTheme,
    overrides = overrides,
    notesBuilder = notesBuilder,
    reducedMotion = reducedMotion,
    designOverridesProvider = designOverridesProvider,
    enterTransition = slideEnterTransition(),
    exitTransition = slideExitTransition(),
    notes = {
        overview("Full choreography testbed: staggered rows, spring stamp, LineDraw callout, WarpCode morph, and Magnifier loupe.")
        beat(0, "Baseline heading only: sets up the naive claim ('Fix with SpanStyle(alpha)!') before revealing the breakdown.")
        beat(1, "[Click] Staggered leader rows + right-hand WarpCode card fade in together on a 55ms cascade.")
        beat(2, "[Click] IMMUTABLE stamp pops in with a spring and draws the curved leader arrow up to BasicText(AnnotatedString).")
        beat(3, "[Click] WarpCode card expands to multiline parameters while the dashed callout outline draws around it and comparison rows dim.")
        beat(4, "[Click] Punchline: animated red strikeout crosses out the old heading and the zoomed iris loupe highlights BasicText(AnnotatedString).")
    },
) {
    val theme = LocalDeckTheme.current
    @OptIn(ExperimentalTransitionApi::class)
    val stepTransition = transition.createChildTransition("keynote-lab-step") { it.toValue().virtualBeatIndex }

    // Each build's beat is declared once (defaultTriggerBeat); visibility is derived from the scene's
    // beat index, so the timeline block, the stage, and Inspector enterBeat overrides always agree.
    val staggerRows = transition.animateStaggerGroup(
        count = 2,
        label = "Leader Rows Stagger",
        trackId = "lab-leader-stagger",
        defaultTriggerBeat = 1,
    )
    val stampBuild by transition.animateBuildProgress(
        label = "IMMUTABLE Stamp + Arrow",
        trackId = "lab-immutable-stamp",
        defaultTriggerBeat = 2,
    )
    val calloutBuild by transition.animateBuildProgress(
        label = "Warp Callout Outline",
        trackId = "lab-warp-callout",
        category = TrackCategory.LineDraw,
        defaultTriggerBeat = 3,
        durationMillis = theme.motion.lineDrawDurationMs,
    )
    val reversalBuild by transition.animateBuildProgress(
        label = "Strikeout & Zoom Loupe",
        trackId = "lab-strikeout-loupe",
        category = TrackCategory.LineDraw,
        defaultTriggerBeat = 4,
        durationMillis = theme.motion.loupeDurationMs,
    )
    val stampEntrance = spatialEntranceOffset("lab-immutable-stamp")
    val cardEntrance = spatialEntranceOffset("lab-leader-stagger")

    val warpSnippets = remember {
        listOf(
            "fun fade(s: String) {\n  BasicText(s)\n}",
            "fun fade(\n  s: String,\n  a: Float,\n) {\n  BasicText(s, alpha = a)\n}",
        )
    }
    // Compact code type for this dense two-column slide, so nothing wraps or clips.
    val labCode = theme.typography.code.copy(fontSize = 17.sp, lineHeight = 24.sp)
    @OptIn(ExperimentalTransitionApi::class)
    val warpStep = stepTransition.createChildTransition("warp-idx") { if (it >= 3) 1 else 0 }

    // The eyebrow still sits at the shared (64 dp, 64 dp) origin of Slides 1–4 (no jump during the
    // dissolve); only the dense two-column content uses the tighter 40 dp frame (scaled by slidePadding).
    val labContentPadding = (theme.styles.slidePadding.value * (40f / 64f)).roundToInt().coerceAtLeast(16)
    val labLoupeDiameter = theme.styles.loupe.diameter * (164f / 260f)
    val labLoupeZoom = theme.styles.loupe.defaultZoom * (2.4f / 3.2f)
    SlideLayers(palette = null) {
        FoundationFrame("KEYNOTE EFFECTS LAB / PROVISIONAL", contentPadding = labContentPadding) {
        MagnifierLoupeStage(
            targetKey = "compose-target",
            progress = { reversalBuild },
            modifier = Modifier.fillMaxSize(),
            loupeAlignment = Alignment.BottomEnd,
            loupeDiameter = labLoupeDiameter,
            loupePadding = PaddingValues(end = 12.dp),
            zoom = labLoupeZoom,
            decorations = listOf(
                AnimatedLine(
                    startKey = "immutable-stamp",
                    startAlignment = Alignment.TopCenter,
                    endKey = "compose-target",
                    endAlignment = Alignment.BottomCenter,
                    progress = { stampBuild },
                    color = theme.colors.accent,
                    strokeWidth = theme.styles.leaderArrow.strokeWidth,
                    headLength = theme.styles.leaderArrow.headLength,
                    headAngleDegrees = theme.styles.leaderArrow.headAngleDegrees,
                    gap = theme.styles.leaderArrow.gap,
                ),
                AnimatedCalloutOutline(
                    targetKey = "warp-card",
                    progress = { calloutBuild },
                    color = theme.colors.secondary,
                    cornerRadius = theme.styles.calloutBox.cornerRadius,
                    strokeWidth = theme.styles.calloutBox.strokeWidth,
                    dashPattern = theme.styles.calloutBox.dashOn to theme.styles.calloutBox.dashOff,
                    inset = theme.styles.calloutBox.inset,
                ),
                AnimatedStrikeout(
                    targetKey = "optimistic-claim",
                    progress = { reversalBuild },
                    color = theme.colors.danger,
                    strokeWidth = theme.styles.strikeout.strokeWidth,
                    horizontalOverhang = theme.styles.strikeout.horizontalOverhang,
                    verticalTiltFraction = theme.styles.strikeout.verticalTiltFraction,
                ),
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1.12f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DeckEditableText(
                        elementId = "s5:headline",
                        text = "Fix with SpanStyle(alpha)!",
                        label = "Lab Headline",
                        kind = "Heading",
                        style = theme.typography.heading,
                        color = theme.colors.foreground,
                        modifier = Modifier
                            .decorate("optimistic-claim")
                            .timelineTarget("lab-strikeout-loupe")
                            .keynoteSpotlight(
                                dimProgress = { calloutBuild * (1f - reversalBuild) },
                                dimmedAlpha = theme.colors.spotlightDimAlpha,
                            ),
                    )
                    DottedLeaderRow(
                        leading = "Web",
                        trailing = "<span style=\"opacity\">",
                        trailingStyle = labCode,
                        elementId = "s5:row-web",
                        modifier = Modifier
                            .keynoteFadeMoveTracked(
                                trackId = "lab-leader-stagger",
                                progress = { staggerRows.progressFor(0) },
                                itemIndex = 0,
                            )
                            .keynoteSpotlight(dimProgress = { calloutBuild }, dimmedAlpha = theme.colors.spotlightDimAlpha),
                    )
                    DottedLeaderRow(
                        leading = "Compose",
                        trailing = "BasicText(AnnotatedString)",
                        trailingStyle = labCode,
                        highlightProgress = { stampBuild },
                        trailingModifier = Modifier.decorate("compose-target"),
                        elementId = "s5:row-compose",
                        modifier = Modifier.keynoteFadeMoveTracked(
                            trackId = "lab-leader-stagger",
                            progress = { staggerRows.progressFor(1) },
                            itemIndex = 1,
                        ),
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        KeynoteStamp(
                            text = "IMMUTABLE",
                            elementId = "s5:stamp",
                            modifier = Modifier
                                .decorate("immutable-stamp")
                                .timelineTarget("lab-immutable-stamp")
                                .keynotePop(progress = { stampBuild }, entranceOffset = stampEntrance),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(0.88f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ciacola-lab-warp-card")
                            .decorate("warp-card")
                            // The card fades in with stagger item 2 and is outlined by the callout build.
                            .timelineTarget("lab-warp-callout", "lab-leader-stagger", "lab-leader-stagger#item1")
                            .keynoteFromDarkness(progress = { staggerRows.progressFor(1) }, entranceOffset = cardEntrance),
                    ) {
                        DeckWarpCodeCard(
                            codeStates = warpSnippets,
                            stateTransition = warpStep,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = labCode.copy(fontSize = 18.sp, lineHeight = 26.sp),
                            elementId = "s5:warp-card",
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun FoundationFrame(
    eyebrow: String,
    contentPadding: Int = LocalDeckTheme.current.styles.slidePadding.value.toInt(),
    content: @Composable BoxScope.() -> Unit,
) {
    ReportSceneHeader(eyebrow)
    val theme = LocalDeckTheme.current
    val sceneIdx = dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline.current?.sceneIndex ?: 0
    val eyebrowId = "s${sceneIdx + 1}:eyebrow"
    // The eyebrow is always anchored at the theme's slide padding, so every slide shares one eyebrow
    // origin even when its content uses a tighter frame.
    val eyebrowInset = (theme.styles.slidePadding.value - contentPadding).coerceAtLeast(0f).dp
    Box(
        Modifier
            .fillMaxSize()
            .padding(contentPadding.dp)
            .reviewTarget(kind = "Frame", label = eyebrow, isContainer = true),
    ) {
        DeckEditableText(
            elementId = eyebrowId,
            text = eyebrow,
            label = "Eyebrow",
            kind = "Eyebrow",
            color = theme.colors.muted,
            style = theme.typography.eyebrow,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = eyebrowInset, top = eyebrowInset),
        )
        // Content keeps its own frame: on tighter slides the 56 dp header band still clears the
        // inset eyebrow (eyebrow line height 22 dp).
        Box(Modifier.fillMaxSize().padding(top = 56.dp), content = content)
    }
}
