package dev.sebastiano.deck

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
    LocalDeckTextRenderer.current.AnimatedText(transition, modifier, style)
}
