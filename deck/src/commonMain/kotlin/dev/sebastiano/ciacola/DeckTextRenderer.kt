package dev.sebastiano.ciacola

import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import dev.bnorm.storyboard.text.magic.MagicText
import dev.sebastiano.ciacola.review.reviewTarget

interface DeckTextRenderer {
    @Composable
    fun AnimatedText(
        transition: Transition<AnnotatedString>,
        modifier: Modifier,
        style: TextStyle,
    )
}

object MagicTextV1Renderer : DeckTextRenderer {
    @Composable
    override fun AnimatedText(
        transition: Transition<AnnotatedString>,
        modifier: Modifier,
        style: TextStyle,
    ) {
        ProvideTextStyle(style) {
            MagicText(transition, modifier)
        }
    }
}

private val LocalDeckTextRenderer = staticCompositionLocalOf<DeckTextRenderer> {
    error("DeckTextRenderer is not installed at the application boundary")
}

@Composable
fun DeckTextRendererProvider(
    renderer: DeckTextRenderer,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDeckTextRenderer provides renderer, content = content)
}

@Composable
fun DeckMagicText(
    transition: Transition<AnnotatedString>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    style: TextStyle = DeckDesign.body,
) {
    val currentText = transition.targetState.text
    val taggedModifier = modifier.reviewTarget(
        kind = "MagicText",
        label = currentText.take(48).ifBlank { "MagicText" },
        textSnippet = currentText,
    )
    LocalDeckTextRenderer.current.AnimatedText(transition, taggedModifier, style)
}
