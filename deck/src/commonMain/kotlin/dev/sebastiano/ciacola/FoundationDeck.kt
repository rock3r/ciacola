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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
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
import dev.bnorm.storyboard.layout.decorated.DecorationLayout
import dev.bnorm.storyboard.map
import dev.bnorm.storyboard.plus
import dev.bnorm.storyboard.text.highlight.CodeStyle
import dev.bnorm.storyboard.text.highlight.Language
import dev.bnorm.storyboard.text.highlight.highlight
import dev.bnorm.storyboard.toValue
import dev.sebastiano.ciacola.effects.AnimatedCalloutOutline
import dev.sebastiano.ciacola.effects.AnimatedLine
import dev.sebastiano.ciacola.effects.AnimatedStrikeout
import dev.sebastiano.ciacola.effects.DeckWarpCodeCard
import dev.sebastiano.ciacola.effects.DottedLeaderRow
import dev.sebastiano.ciacola.effects.KeynoteStamp
import dev.sebastiano.ciacola.effects.MagnifierLoupeStage
import dev.sebastiano.ciacola.effects.animateBuildProgress
import dev.sebastiano.ciacola.effects.animateStaggerGroup
import dev.sebastiano.ciacola.effects.keynoteFadeMoveTracked
import dev.sebastiano.ciacola.effects.keynoteFromDarkness
import dev.sebastiano.ciacola.effects.keynotePop
import dev.sebastiano.ciacola.effects.keynoteSpotlight
import dev.sebastiano.ciacola.effects.spatialEntranceOffset
import dev.sebastiano.ciacola.review.ReportSceneHeader
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.timeline.ActiveMotionArcPreview
import dev.sebastiano.ciacola.timeline.ActiveSceneTimelineContext
import dev.sebastiano.ciacola.timeline.DeckTimelineOverridesFile
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline
import dev.sebastiano.ciacola.timeline.LocalMotionPathPreviewState
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides
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

object DeckDesign {
    private val defaultTheme = DeckThemes.stageDark()

    val background get() = defaultTheme.colors.background
    val surface get() = defaultTheme.colors.surface
    val foreground get() = defaultTheme.colors.foreground
    val muted get() = defaultTheme.colors.muted
    val accent get() = defaultTheme.colors.accent
    val secondary get() = defaultTheme.colors.secondary

    val title: TextStyle get() = defaultTheme.typography.title
    val heading: TextStyle get() = defaultTheme.typography.heading
    val body: TextStyle get() = defaultTheme.typography.body
    val code: TextStyle get() = defaultTheme.typography.code
    val codeEmphasis: TextStyle get() = defaultTheme.typography.codeEmphasis

    val format = SceneFormat(IntSize(1920, 1080), Density(2f))
}

fun createFoundationDeck(
    textRenderer: DeckTextRenderer = MagicTextV1Renderer,
    theme: DeckTheme = DeckThemes.stageDark(),
    bundledPreset: DeckThemePreset? = null,
    includeKeynoteEffectsLab: Boolean = false,
    stageDecorator: ContentDecorator = ContentDecorator.None,
    timelineOverrides: DeckTimelineOverridesFile = DeckTimelineOverridesFile(),
): Storyboard = Storyboard.build(
    title = "Ciacola",
    description = "Provisional scenes proving the presentation architecture; not talk narrative.",
    format = DeckDesign.format,
    decorator = deckDecorator(textRenderer, theme, bundledPreset, timelineOverrides) + stageDecorator,
) {
    beatsScene(theme, timelineOverrides)
    sharedMoveScene(theme, timelineOverrides)
    ambientLoopScene(theme, timelineOverrides)
    kotlinCodeScene(theme, theme.colors.code.toCodeStyle(), timelineOverrides)
    if (includeKeynoteEffectsLab) {
        keynoteEffectsLabScene(theme, timelineOverrides)
    }
}

private fun deckDecorator(
    textRenderer: DeckTextRenderer,
    theme: DeckTheme,
    bundledPreset: DeckThemePreset? = null,
    timelineOverrides: DeckTimelineOverridesFile = DeckTimelineOverridesFile(),
) = ContentDecorator { content ->
    val activeTheme = if (bundledPreset != null) {
        DeckThemes.rememberBundledDeckTheme(bundledPreset)
    } else {
        theme
    }
    val effectiveOverrides = if (dev.sebastiano.ciacola.timeline.LocalTimelineOffscreen.current) {
        LocalTimelineOverrides.current
    } else {
        timelineOverrides
    }
    CompositionLocalProvider(LocalTimelineOverrides provides effectiveOverrides) {
        DeckTextRendererProvider(textRenderer) {
            DeckThemeProvider(activeTheme) {
                Surface(Modifier.fillMaxSize(), color = activeTheme.colors.background) { content() }
            }
        }
    }
}

private fun <T> StoryboardBuilder.choreographedScene(
    sceneIndex: Int,
    sceneTitle: String,
    baseFrames: List<T>,
    theme: DeckTheme,
    overrides: DeckTimelineOverridesFile,
    content: @Composable context(androidx.compose.animation.AnimatedVisibilityScope, androidx.compose.animation.SharedTransitionScope) SceneScope<VirtualizedFrame<T>>.() -> Unit,
) {
    val vFrames = buildVirtualizedFrames(baseFrames, overrides.sceneBeats[sceneIndex])
    val dissolveMs = theme.motion.dissolveDurationMs
    scene(
        frames = vFrames,
        enterTransition = SceneEnterTransition { fadeIn(tween(dissolveMs)) },
        exitTransition = SceneExitTransition { fadeOut(tween(dissolveMs)) },
    ) {
        val ctx = remember(sceneIndex, sceneTitle, vFrames) {
            ActiveSceneTimelineContext(
                sceneIndex = sceneIndex,
                sceneTitle = sceneTitle,
                totalBeats = vFrames.size,
                frameBeatIndexOf = { rawFrame ->
                    when (rawFrame) {
                        is Frame.Start -> 0
                        is Frame.End -> (vFrames.size - 1).coerceAtLeast(0)
                        is Frame.Value<*> -> (rawFrame.value as? VirtualizedFrame<*>)?.virtualBeatIndex ?: 0
                        is VirtualizedFrame<*> -> rawFrame.virtualBeatIndex
                        is Int -> rawFrame
                        else -> 0
                    }
                },
            )
        }
        val previewState = dev.sebastiano.ciacola.timeline.LocalMotionPathPreviewState.current
        if (dev.bnorm.storyboard.easel.LocalSceneMode.current == dev.bnorm.storyboard.easel.SceneMode.Story) {
            androidx.compose.runtime.SideEffect {
                previewState?.activeStageSceneIndex = sceneIndex
            }
        }
        CompositionLocalProvider(LocalActiveSceneTimeline provides ctx) {
            content()
        }
    }
}

private data class BeatState(val eyebrow: String, val prose: AnnotatedString)

private fun StoryboardBuilder.beatsScene(
    theme: DeckTheme,
    overrides: DeckTimelineOverridesFile,
) = choreographedScene(
    sceneIndex = 0,
    sceneTitle = "Beats",
    baseFrames = listOf(
        BeatState("FOUNDATION / PROVISIONAL", AnnotatedString("A scene begins with one deterministic state.")),
        BeatState("FOUNDATION / PROVISIONAL", AnnotatedString("A scene advances to a deterministic next state.")),
        BeatState("FOUNDATION / PROVISIONAL", AnnotatedString("The same path is reversible without inventing a second navigation model.")),
    ),
    theme = theme,
    overrides = overrides,
) {
    @OptIn(ExperimentalTransitionApi::class)
    val prose = transition.createChildTransition("foundation-prose") { it.toValue().value.prose }
    val currentVf = transition.currentState.toValue()
    val targetVf = transition.targetState.toValue()
    val badgeProgress by transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = theme.motion.fadeMoveDurationMs,
                easing = theme.motion.standardEasing,
            )
        },
        label = "shared-badge-progress",
    ) { frame ->
        if (frame.toValue().baseBeatIndex >= 2) 1f else 0f
    }

    FoundationFrame(currentVf.value.eyebrow) {
        DeckMagicText(prose, Modifier.fillMaxSize())
        if (currentVf.baseBeatIndex >= 2 || targetVf.baseBeatIndex >= 2) {
            val badgeShape = RoundedCornerShape(8.dp)
            Surface(
                color = theme.colors.accent,
                shape = badgeShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .deckSharedBounds(
                        sharedKey = "foundation-shared-card",
                        label = "Shared Card",
                        defaultDurationMs = theme.motion.sharedBoundsDurationMs,
                        currentBeatInScene = maxOf(currentVf.virtualBeatIndex, targetVf.virtualBeatIndex),
                    )
                    .graphicsLayer {
                        val p = badgeProgress.coerceIn(0f, 1f)
                        alpha = p
                        val s = 0.72f + 0.28f * p
                        scaleX = s
                        scaleY = s
                    },
            ) {}
        }
    }
}

private data class SharedMoveState(val expanded: Boolean)

private fun StoryboardBuilder.sharedMoveScene(
    theme: DeckTheme,
    overrides: DeckTimelineOverridesFile,
) = choreographedScene(
    sceneIndex = 1,
    sceneTitle = "Shared Move",
    baseFrames = listOf(SharedMoveState(false), SharedMoveState(true)),
    theme = theme,
    overrides = overrides,
) {
    val activeTheme = LocalDeckTheme.current
    val sceneVisibility: AnimatedVisibilityScope = contextOf<AnimatedVisibilityScope>()
    val targetVf = transition.targetState.toValue()
    val trackId = "shared:foundation-shared-card"
    val trackOverride = LocalTimelineOverrides.current.tracks[trackId]
    val effectiveDuration = trackOverride?.durationMs ?: activeTheme.motion.sharedBoundsDurationMs
    val effectiveDelay = trackOverride?.delayMs ?: 0
    val effectiveCurve = trackOverride?.curve ?: TimelineCurveSpec()
    val effectiveArcBend = trackOverride?.spatial?.arcBend ?: 0f
    val previewState = LocalMotionPathPreviewState.current

    val expandFraction by transition.animateFloat(
        transitionSpec = {
            effectiveCurve.toAnimationSpec(effectiveDuration, effectiveDelay)
        },
        label = "shared-card-expand",
    ) { frame ->
        if (frame.toValue().value.expanded) 1f else 0f
    }

    val cornerDp = (24f + (40f - 24f) * expandFraction.coerceIn(0f, 1f)).dp
    val cardShape = RoundedCornerShape(cornerDp)

    FoundationFrame("SHARED ELEMENT / PROVISIONAL") {
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Surface(
                    color = activeTheme.colors.accent,
                    shape = cardShape,
                    modifier = with(sceneVisibility) {
                        Modifier.deckSharedBounds(
                            sharedKey = "foundation-shared-card",
                            label = "Shared Card",
                            defaultDurationMs = activeTheme.motion.sharedBoundsDurationMs,
                            currentBeatInScene = targetVf.virtualBeatIndex,
                        )
                    },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("shared", style = activeTheme.typography.heading, color = activeTheme.colors.onAccent)
                    }
                }
            },
        ) { measurables, constraints ->
            val maxW = constraints.maxWidth.toFloat()
            val maxH = constraints.maxHeight.toFloat()
            val collapsedPx = 240.dp.toPx()
            val expandedPx = 520.dp.toPx()
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
            val currentRect = sharedCardRect(
                start = startRect,
                end = endRect,
                fraction = expandFraction.coerceIn(0f, 1f),
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
) = choreographedScene(
    sceneIndex = 2,
    sceneTitle = "Ambient Loop",
    baseFrames = listOf(0),
    theme = theme,
    overrides = overrides,
) {
    val activeTheme = LocalDeckTheme.current
    val cardShape = RoundedCornerShape(activeTheme.styles.codeCard.cornerRadius)
    Box(Modifier.fillMaxSize()) {
        AnimatedMeshBackdrop(Modifier.fillMaxSize())
        FoundationFrame("CONTINUOUS COMPOSE / PROVISIONAL") {
            Surface(
                color = activeTheme.colors.surface.copy(alpha = 0.90f),
                shape = cardShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .border(activeTheme.styles.codeCard.borderWidth, activeTheme.colors.border, cardShape),
            ) {
                Column(
                    Modifier.padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("one stable scene state", style = activeTheme.typography.heading, color = activeTheme.colors.foreground)
                    Text("the visual keeps moving", style = activeTheme.typography.body, color = activeTheme.colors.muted)
                }
            }
        }
    }
}

private data class CodeState(val description: AnnotatedString, val source: AnnotatedString)

private fun StoryboardBuilder.kotlinCodeScene(
    deckTheme: DeckTheme,
    codeStyle: CodeStyle,
    overrides: DeckTimelineOverridesFile,
) = choreographedScene(
    sceneIndex = 3,
    sceneTitle = "Highlighted Code",
    baseFrames = listOf(
        codeState("Syntax highlighting is data carried by AnnotatedString.", "fun provisional() = Unit", codeStyle),
        codeState("Retained Kotlin tokens move while styles remain attached.", "fun provisional() {\n    println(\"foundation\")\n}", codeStyle),
        codeState("The sample remains intentionally small and replaceable.", "fun provisional(message: String) {\n    println(message)\n}", codeStyle),
    ),
    theme = deckTheme,
    overrides = overrides,
) {
    val theme = LocalDeckTheme.current
    @OptIn(ExperimentalTransitionApi::class)
    val description = transition.createChildTransition("code-description") { it.toValue().value.description }
    @OptIn(ExperimentalTransitionApi::class)
    val code = transition.createChildTransition("highlighted-kotlin") { it.toValue().value.source }

    FoundationFrame("MAGIC TEXT / KOTLIN / PROVISIONAL") {
        Column(verticalArrangement = Arrangement.spacedBy(44.dp)) {
            DeckMagicText(description)
            Surface(
                color = theme.colors.code.background,
                shape = RoundedCornerShape(theme.styles.codeCard.cornerRadius),
            ) {
                DeckMagicText(
                    code,
                    Modifier.fillMaxSize().padding(theme.styles.codeCard.padding),
                    theme.typography.code,
                )
            }
        }
    }
}

private fun codeState(
    description: String,
    source: String,
    codeStyle: CodeStyle,
): CodeState = CodeState(
    description = AnnotatedString(description),
    source = source.highlight(
        codeStyle = codeStyle,
        language = Language.Kotlin,
        identifierStyle = {
            when (it) {
                "provisional" -> codeStyle.functionDeclaration
                "println" -> codeStyle.staticFunctionCall
                else -> null
            }
        },
    ),
)

// Provisional interactive lab scene exercising every Keynote effect + DeckWarpCode + MagnifierLoupe.
private fun StoryboardBuilder.keynoteEffectsLabScene(
    deckTheme: DeckTheme,
    overrides: DeckTimelineOverridesFile,
) = choreographedScene(
    sceneIndex = 4,
    sceneTitle = "Keynote Effects Lab",
    baseFrames = listOf(0, 1, 2, 3, 4),
    theme = deckTheme,
    overrides = overrides,
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
    // dissolve); only the dense two-column content uses the tighter 40 dp frame.
    FoundationFrame("KEYNOTE EFFECTS LAB / PROVISIONAL", contentPadding = 40) {
        MagnifierLoupeStage(
            targetKey = "compose-target",
            progress = { reversalBuild },
            modifier = Modifier.fillMaxSize(),
            loupeAlignment = Alignment.BottomEnd,
            loupeDiameter = 164.dp,
            loupePadding = PaddingValues(end = 12.dp),
            zoom = 2.4f,
            decorations = listOf(
                AnimatedLine(
                    startKey = "immutable-stamp",
                    startAlignment = Alignment.TopCenter,
                    endKey = "compose-target",
                    endAlignment = Alignment.BottomCenter,
                    progress = { stampBuild },
                    color = theme.colors.accent,
                    strokeWidth = theme.styles.leaderArrow.strokeWidth,
                    gap = theme.styles.leaderArrow.gap,
                ),
                AnimatedCalloutOutline(
                    targetKey = "warp-card",
                    progress = { calloutBuild },
                    color = theme.colors.secondary,
                ),
                AnimatedStrikeout(
                    targetKey = "optimistic-claim",
                    progress = { reversalBuild },
                    color = theme.colors.danger,
                ),
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1.04f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Fix with SpanStyle(alpha)!",
                        style = theme.typography.heading,
                        color = theme.colors.foreground,
                        modifier = Modifier
                            .decorate("optimistic-claim")
                            .reviewTarget(
                                kind = "Heading",
                                label = "Fix with SpanStyle(alpha)!",
                                textSnippet = "Fix with SpanStyle(alpha)!",
                            )
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
                        modifier = Modifier.keynoteFadeMoveTracked(
                            trackId = "lab-leader-stagger",
                            progress = { staggerRows.progressFor(1) },
                            itemIndex = 1,
                        ),
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        KeynoteStamp(
                            text = "IMMUTABLE",
                            modifier = Modifier
                                .decorate("immutable-stamp")
                                .timelineTarget("lab-immutable-stamp")
                                .keynotePop(progress = { stampBuild }, entranceOffset = stampEntrance),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(0.96f)
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
                        )
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
    // The eyebrow is always anchored at the theme's slide padding, so every slide shares one eyebrow
    // origin even when its content uses a tighter frame.
    val eyebrowInset = (theme.styles.slidePadding.value - contentPadding).coerceAtLeast(0f).dp
    Box(
        Modifier
            .fillMaxSize()
            .padding(contentPadding.dp)
            .reviewTarget(kind = "Frame", label = eyebrow, isContainer = true),
    ) {
        Text(
            eyebrow,
            color = theme.colors.muted,
            style = theme.typography.eyebrow,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = eyebrowInset, top = eyebrowInset)
                .reviewTarget(kind = "Eyebrow", label = eyebrow, textSnippet = eyebrow),
        )
        // Content keeps its own frame: on tighter slides the 56 dp header band still clears the
        // inset eyebrow (eyebrow line height 22 dp).
        Box(Modifier.fillMaxSize().padding(top = 56.dp), content = content)
    }
}
