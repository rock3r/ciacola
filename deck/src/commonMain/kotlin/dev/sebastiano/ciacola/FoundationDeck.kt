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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.StoryboardBuilder
import dev.bnorm.storyboard.easel.rememberSharedContentState
import dev.bnorm.storyboard.easel.sharedBounds
import dev.bnorm.storyboard.text.highlight.CodeStyle
import dev.bnorm.storyboard.text.highlight.Language
import dev.bnorm.storyboard.text.highlight.highlight
import dev.bnorm.storyboard.toValue

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
    val background = Color(0xFF0B0D12)
    val surface = Color(0xFF171B24)
    val foreground = Color(0xFFF4F1EA)
    val muted = Color(0xFFA9B0C0)
    val accent = Color(0xFFFF6B5E)
    val secondary = Color(0xFF71D7C5)

    val title = TextStyle(fontSize = 72.sp, lineHeight = 78.sp, fontWeight = FontWeight.Bold)
    val heading = TextStyle(fontSize = 46.sp, lineHeight = 54.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 30.sp, lineHeight = 40.sp)
    val code = TextStyle(fontSize = 27.sp, lineHeight = 36.sp, fontFamily = FontFamily.Monospace)
    val codeEmphasis = code.copy(fontWeight = FontWeight.Bold)

    val format = SceneFormat(IntSize(1920, 1080), Density(2f))
}

fun createFoundationDeck(
    textRenderer: DeckTextRenderer = MagicTextV1Renderer,
): Storyboard = Storyboard.build(
    title = "Ciacola",
    description = "Provisional scenes proving the presentation architecture; not talk narrative.",
    format = DeckDesign.format,
    decorator = deckDecorator(textRenderer),
) {
    beatsScene()
    sharedMoveScene()
    ambientLoopScene()
    kotlinCodeScene()
}

private fun deckDecorator(textRenderer: DeckTextRenderer) = ContentDecorator { content ->
    val colors = darkColors(
        background = DeckDesign.background,
        surface = DeckDesign.surface,
        onBackground = DeckDesign.foreground,
        onSurface = DeckDesign.foreground,
        primary = DeckDesign.accent,
        secondary = DeckDesign.secondary,
    )
    val typography = Typography(
        h1 = DeckDesign.title,
        h2 = DeckDesign.heading,
        body1 = DeckDesign.body,
    )
    DeckTextRendererProvider(textRenderer) {
        MaterialTheme(colors = colors, typography = typography) {
            Surface(Modifier.fillMaxSize(), color = DeckDesign.background) { content() }
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
    FoundationFrame("SHARED ELEMENT / PROVISIONAL") {
        transition.AnimatedContent(
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.fillMaxSize(),
        ) { frame ->
            val expanded = frame.toValue().expanded
            Box(Modifier.fillMaxSize()) {
                Surface(
                    color = DeckDesign.accent,
                    shape = RoundedCornerShape(if (expanded) 40.dp else 24.dp),
                    modifier = Modifier
                        .align(if (expanded) Alignment.Center else Alignment.TopStart)
                        .sharedBounds(
                            rememberSharedContentState("foundation-shared-card"),
                            boundsTransform = { _, _ -> tween(700) },
                        )
                        .size(if (expanded) 520.dp else 240.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("shared", style = DeckDesign.heading, color = DeckDesign.background)
                    }
                }
            }
        }
    }
}

private fun StoryboardBuilder.ambientLoopScene() = scene(frameCount = 1) {
    FoundationFrame("CONTINUOUS COMPOSE / PROVISIONAL", contentPadding = 0) {
        AnimatedMeshBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.Center).background(DeckDesign.background.copy(alpha = 0.78f)).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("one stable scene state", style = DeckDesign.heading)
            Text("the visual keeps moving", style = DeckDesign.body, color = DeckDesign.muted)
        }
    }
}

private data class CodeState(val description: AnnotatedString, val source: AnnotatedString)

private fun StoryboardBuilder.kotlinCodeScene() = scene(
    frames = listOf(
        codeState("Syntax highlighting is data carried by AnnotatedString.", "fun provisional() = Unit"),
        codeState("Retained Kotlin tokens move while styles remain attached.", "fun provisional() {\n    println(\"foundation\")\n}"),
        codeState("The sample remains intentionally small and replaceable.", "fun provisional(message: String) {\n    println(message)\n}"),
    ),
) {
    @OptIn(ExperimentalTransitionApi::class)
    val description = transition.createChildTransition("code-description") { it.toValue().description }
    @OptIn(ExperimentalTransitionApi::class)
    val code = transition.createChildTransition("highlighted-kotlin") { it.toValue().source }

    FoundationFrame("MAGIC TEXT / KOTLIN / PROVISIONAL") {
        Column(verticalArrangement = Arrangement.spacedBy(44.dp)) {
            DeckMagicText(description)
            Surface(color = DeckDesign.surface, shape = RoundedCornerShape(24.dp)) {
                DeckMagicText(code, Modifier.fillMaxSize().padding(40.dp), DeckDesign.code)
            }
        }
    }
}

private val foundationCodeStyle = CodeStyle.build(SpanStyle(color = DeckDesign.foreground)) {
    keyword = simple + SpanStyle(color = DeckDesign.accent)
    string = simple + SpanStyle(color = DeckDesign.secondary)
    functionDeclaration = simple + SpanStyle(color = Color(0xFFFFD166), fontWeight = FontWeight.Bold)
    staticFunctionCall = simple + SpanStyle(color = Color(0xFF8CB4FF))
    punctuation = simple + SpanStyle(color = DeckDesign.muted)
}

private fun codeState(description: String, source: String): CodeState = CodeState(
    description = AnnotatedString(description),
    source = source.highlight(
        codeStyle = foundationCodeStyle,
        language = Language.Kotlin,
        identifierStyle = {
            when (it) {
                "provisional" -> foundationCodeStyle.functionDeclaration
                "println" -> foundationCodeStyle.staticFunctionCall
                else -> null
            }
        },
    ),
)

@Composable
private fun FoundationFrame(
    eyebrow: String,
    contentPadding: Int = 64,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(contentPadding.dp)) {
        Text(
            eyebrow,
            color = DeckDesign.muted,
            style = TextStyle(fontSize = 16.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.align(Alignment.TopStart),
        )
        Box(Modifier.fillMaxSize().padding(top = 56.dp), content = content)
    }
}
