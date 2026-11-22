package dev.sebastiano.ciacola

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import dev.sebastiano.ciacola.document.DeliveryBy
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.PieceSchedule
import dev.sebastiano.ciacola.effects.v2.EffectArgs
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind

/**
 * Text build direction at the [DeckTextRenderer] boundary.
 */
enum class TextBuildKind {
    In,
    Out,
    Action,
    ;

    fun toEffectBuildKind(): EffectBuildKind = when (this) {
        In -> EffectBuildKind.Enter
        Out -> EffectBuildKind.Exit
        Action -> EffectBuildKind.Emphasis
    }
}

/**
 * Validated effect call passed to [TextContent.Build].
 */
@Immutable
data class EffectCall(
    val effectId: String,
    val args: EffectArgs = EffectArgs.Empty,
)

/**
 * Styled paragraph payload at the [DeckTextRenderer] seam.
 */
@Immutable
data class StyledText(
    val text: AnnotatedString,
    val style: TextStyle,
    val maxWidth: Float = Float.POSITIVE_INFINITY,
)

/**
 * Deferred per-frame content contract at the [DeckTextRenderer] boundary.
 *
 * Passed as `() -> TextContent` to [DeckTextRenderer.Text] so animated frames read timing and
 * text payloads in layout and draw phases without per-frame composition rebuilding.
 */
sealed interface TextContent {
    @Immutable
    data class Rest(
        val text: StyledText,
        val characterDeliveryNode: Boolean = false,
    ) : TextContent

    @Immutable
    data class Morph(
        val from: StyledText,
        val to: StyledText,
        val by: MorphBy,
        val schedule: PieceSchedule,
        val localMs: Float,
        val pruneStopwords: Boolean = true,
        val interpolateLayoutSize: Boolean = true,
        val characterDeliveryNode: Boolean = false,
        val reducedMotion: Boolean = false,
    ) : TextContent

    @Immutable
    data class Build(
        val text: StyledText,
        val kind: TextBuildKind,
        val effect: EffectCall,
        val schedule: PieceSchedule,
        val localMs: Float,
        val by: DeliveryBy = DeliveryBy.Word,
        val characterDeliveryNode: Boolean = false,
        val reducedMotion: Boolean = false,
    ) : TextContent
}

/**
 * Application-level text-engine boundary.
 *
 * Scenes and document nodes never select text engines directly; on desktop, [DeckTextRenderer]
 * binds exclusively to `MagicText v2` (`DeckTextRendererV2` / `MagicTextV2Renderer`).
 */
interface DeckTextRenderer {
    @Composable
    fun Text(
        content: () -> TextContent,
        modifier: Modifier = Modifier,
    )
}

val LocalDeckTextRenderer = staticCompositionLocalOf<DeckTextRenderer?> { null }

@Composable
fun DeckTextRendererProvider(
    renderer: DeckTextRenderer,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDeckTextRenderer provides renderer, content = content)
}
