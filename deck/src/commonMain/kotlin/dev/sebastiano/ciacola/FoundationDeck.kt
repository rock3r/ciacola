package dev.sebastiano.ciacola

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.StoryboardBuilder
import dev.bnorm.storyboard.easel.rememberSharedContentState
import dev.bnorm.storyboard.easel.sharedBounds
import dev.bnorm.storyboard.layout.decorated.DecorationLayout
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
import dev.sebastiano.ciacola.effects.SlideDirection
import dev.sebastiano.ciacola.effects.animateBuildProgress
import dev.sebastiano.ciacola.effects.animateStaggerGroup
import dev.sebastiano.ciacola.effects.keynoteFadeMove
import dev.sebastiano.ciacola.effects.keynoteFromDarkness
import dev.sebastiano.ciacola.effects.keynotePop
import dev.sebastiano.ciacola.effects.keynoteSpotlight
import dev.sebastiano.ciacola.review.ReportSceneHeader
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalDeckTheme

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
): Storyboard = Storyboard.build(
    title = "Ciacola",
    description = "Provisional scenes proving the presentation architecture; not talk narrative.",
    format = DeckDesign.format,
    decorator = deckDecorator(textRenderer, theme, bundledPreset) + stageDecorator,
) {
    beatsScene()
    sharedMoveScene()
    ambientLoopScene()
    kotlinCodeScene(theme.colors.code.toCodeStyle())
    if (includeKeynoteEffectsLab) {
        keynoteEffectsLabScene()
    }
}

private fun deckDecorator(
    textRenderer: DeckTextRenderer,
    theme: DeckTheme,
    bundledPreset: DeckThemePreset? = null,
) = ContentDecorator { content ->
    val activeTheme = if (bundledPreset != null) {
        DeckThemes.rememberBundledDeckTheme(bundledPreset)
    } else {
        theme
    }
    DeckTextRendererProvider(textRenderer) {
        DeckThemeProvider(activeTheme) {
            Surface(Modifier.fillMaxSize(), color = activeTheme.colors.background) { content() }
        }
    }
}

private data class BeatState(val eyebrow: String, val prose: AnnotatedString)

private fun StoryboardBuilder.beatsScene() = scene(
    frames = listOf(
        BeatState("FOUNDATION / PROVISIONAL", AnnotatedString("A scene begins with one deterministic state.")),
        BeatState("FOUNDATION / PROVISIONAL", AnnotatedString("A scene advances to a deterministic next state.")),
        BeatState("FOUNDATION / PROVISIONAL", AnnotatedString("The same path is reversible without inventing a second navigation model.")),
    ),
) {
    @OptIn(ExperimentalTransitionApi::class)
    val prose = transition.createChildTransition("foundation-prose") { it.toValue().prose }
    val current = transition.currentState.toValue()

    FoundationFrame(current.eyebrow) {
        DeckMagicText(prose, Modifier.fillMaxSize())
    }
}

private data class SharedMoveState(val expanded: Boolean)

private fun StoryboardBuilder.sharedMoveScene() = scene(
    frames = listOf(SharedMoveState(false), SharedMoveState(true)),
) {
    val theme = LocalDeckTheme.current
    FoundationFrame("SHARED ELEMENT / PROVISIONAL") {
        transition.AnimatedContent(
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.fillMaxSize(),
        ) { frame ->
            val expanded = frame.toValue().expanded
            Box(Modifier.fillMaxSize()) {
                Surface(
                    color = theme.colors.accent,
                    shape = RoundedCornerShape(if (expanded) 40.dp else 24.dp),
                    modifier = Modifier
                        .align(if (expanded) Alignment.Center else Alignment.TopStart)
                        .sharedBounds(
                            rememberSharedContentState("foundation-shared-card"),
                            boundsTransform = { _, _ -> tween(theme.motion.sharedBoundsDurationMs) },
                        )
                        .size(if (expanded) 520.dp else 240.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("shared", style = theme.typography.heading, color = theme.colors.background)
                    }
                }
            }
        }
    }
}

private fun StoryboardBuilder.ambientLoopScene() = scene(frameCount = 1) {
    val theme = LocalDeckTheme.current
    FoundationFrame("CONTINUOUS COMPOSE / PROVISIONAL", contentPadding = 0) {
        AnimatedMeshBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.Center)
                .background(theme.colors.background.copy(alpha = 0.78f))
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("one stable scene state", style = theme.typography.heading, color = theme.colors.foreground)
            Text("the visual keeps moving", style = theme.typography.body, color = theme.colors.muted)
        }
    }
}

private data class CodeState(val description: AnnotatedString, val source: AnnotatedString)

private fun StoryboardBuilder.kotlinCodeScene(codeStyle: CodeStyle) = scene(
    frames = listOf(
        codeState("Syntax highlighting is data carried by AnnotatedString.", "fun provisional() = Unit", codeStyle),
        codeState("Retained Kotlin tokens move while styles remain attached.", "fun provisional() {\n    println(\"foundation\")\n}", codeStyle),
        codeState("The sample remains intentionally small and replaceable.", "fun provisional(message: String) {\n    println(message)\n}", codeStyle),
    ),
) {
    val theme = LocalDeckTheme.current
    @OptIn(ExperimentalTransitionApi::class)
    val description = transition.createChildTransition("code-description") { it.toValue().description }
    @OptIn(ExperimentalTransitionApi::class)
    val code = transition.createChildTransition("highlighted-kotlin") { it.toValue().source }

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
private fun StoryboardBuilder.keynoteEffectsLabScene() = scene(
    frames = listOf(0, 1, 2, 3, 4),
) {
    val theme = LocalDeckTheme.current
    @OptIn(ExperimentalTransitionApi::class)
    val stepTransition = transition.createChildTransition("keynote-lab-step") { it.toValue() }

    val staggerRows = transition.animateStaggerGroup(count = 2) { it.toValue() >= 1 }
    val stampBuild by transition.animateBuildProgress { it.toValue() >= 2 }
    val calloutBuild by transition.animateBuildProgress(
        durationMillis = theme.motion.lineDrawDurationMs,
    ) { it.toValue() >= 3 }
    val reversalBuild by transition.animateBuildProgress(
        durationMillis = theme.motion.loupeDurationMs,
    ) { it.toValue() >= 4 }

    val warpSnippets = remember {
        listOf(
            "fun fade(text: AnnotatedString) {\n  BasicText(text)\n}",
            "fun fade(text: AnnotatedString, alpha: Float) {\n  BasicText(text.withAlpha(alpha))\n}",
        )
    }
    @OptIn(ExperimentalTransitionApi::class)
    val warpStep = stepTransition.createChildTransition("warp-idx") { if (it >= 3) 1 else 0 }

    FoundationFrame("KEYNOTE EFFECTS LAB / PROVISIONAL") {
        MagnifierLoupeStage(
            targetKey = "compose-target",
            progress = { reversalBuild },
            modifier = Modifier.fillMaxSize(),
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
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Text(
                        text = "Fix with SpanStyle(alpha)!",
                        style = theme.typography.heading,
                        color = theme.colors.foreground,
                        modifier = Modifier
                            .decorate("optimistic-claim")
                            .keynoteSpotlight(dimProgress = { calloutBuild * (1f - reversalBuild) }),
                    )
                    DottedLeaderRow(
                        leading = "Web",
                        trailing = "<span style=\"opacity\">",
                        modifier = Modifier
                            .keynoteFadeMove(progress = { staggerRows.progressFor(0) })
                            .keynoteSpotlight(dimProgress = { calloutBuild }),
                    )
                    DottedLeaderRow(
                        leading = "Compose",
                        trailing = "BasicText(AnnotatedString)",
                        highlightTrailing = stampBuild > 0.2f,
                        trailingModifier = Modifier.decorate("compose-target"),
                        modifier = Modifier.keynoteFadeMove(progress = { staggerRows.progressFor(1) }),
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        KeynoteStamp(
                            text = "IMMUTABLE",
                            modifier = Modifier
                                .decorate("immutable-stamp")
                                .keynotePop(progress = { stampBuild }),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(0.95f)
                        .decorate("warp-card")
                        .keynoteFromDarkness(progress = { staggerRows.progressFor(1) }),
                ) {
                    DeckWarpCodeCard(
                        codeStates = warpSnippets,
                        stateTransition = warpStep,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                .reviewTarget(kind = "Eyebrow", label = eyebrow, textSnippet = eyebrow),
        )
        Box(Modifier.fillMaxSize().padding(top = 56.dp), content = content)
    }
}
