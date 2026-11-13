package dev.sebastiano.ciacola

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import dev.bnorm.storyboard.text.magic.DefaultDelayDurationMillis
import dev.bnorm.storyboard.text.magic.DefaultFadeDurationMillis
import dev.bnorm.storyboard.text.magic.DefaultMoveDurationMillis
import dev.bnorm.storyboard.text.magic.MagicText
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.timeline.HarvestedTrackRecord
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline
import dev.sebastiano.ciacola.timeline.LocalTimelineHarvestCollector
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides
import dev.sebastiano.ciacola.timeline.TrackCategory
import dev.sebastiano.ciacola.timeline.TrackOverride
import dev.sebastiano.ciacola.timeline.timelineTarget

interface DeckTextRenderer {
    @Composable
    fun AnimatedText(
        transition: Transition<AnnotatedString>,
        modifier: Modifier,
        style: TextStyle,
    )
}

/** How a MagicText track override maps onto [MagicText]'s phase length, start delay, and easing. */
internal data class MagicTextOverrideTiming(val phaseMs: Int, val delayMs: Int, val easing: Easing?)

internal fun magicTextOverrideTiming(override: TrackOverride?): MagicTextOverrideTiming {
    val totalDefault = DefaultFadeDurationMillis * 2 + DefaultMoveDurationMillis
    val effectiveTotal = (override?.durationMs ?: totalDefault).coerceAtLeast(90)
    return MagicTextOverrideTiming(
        phaseMs = (effectiveTotal / 3).coerceAtLeast(30),
        delayMs = (override?.delayMs ?: DefaultDelayDurationMillis).coerceAtLeast(0),
        easing = override?.curve?.toEasing(),
    )
}

object MagicTextV1Renderer : DeckTextRenderer {
    @Composable
    override fun AnimatedText(
        transition: Transition<AnnotatedString>,
        modifier: Modifier,
        style: TextStyle,
    ) {
        val sceneCtx = LocalActiveSceneTimeline.current
        val trackId = if (sceneCtx != null) "s${sceneCtx.sceneIndex}:magic-text" else "magic-text"
        val override = LocalTimelineOverrides.current.tracks[trackId]
        val timing = magicTextOverrideTiming(override)

        ProvideTextStyle(style) {
            MagicText(
                transition = transition,
                modifier = modifier,
                moveDurationMillis = timing.phaseMs,
                fadeDurationMillis = timing.phaseMs,
                delayDurationMillis = DefaultDelayDurationMillis,
                delayMillis = timing.delayMs,
                easing = timing.easing,
            )
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
    val sceneCtx = LocalActiveSceneTimeline.current
    val collector = LocalTimelineHarvestCollector.current
    val trackId = if (sceneCtx != null) "s${sceneCtx.sceneIndex}:magic-text" else "magic-text"
    val override = LocalTimelineOverrides.current.tracks[trackId]
    val defaultTotal = DefaultFadeDurationMillis * 2 + DefaultMoveDurationMillis

    SideEffect {
        if (sceneCtx != null && sceneCtx.totalBeats > 1) {
            // MagicText animates on every beat change of its scene, so it harvests one block per beat.
            // The trigger beat always stays the beat that drives the stage: an `enterBeat` override would
            // collapse every block onto one key while the stage kept animating on each beat.
            for (beat in 1 until sceneCtx.totalBeats) {
                collector?.record(
                    HarvestedTrackRecord(
                        trackId = trackId,
                        label = "MagicText Diff",
                        category = TrackCategory.MagicText,
                        sceneIndex = sceneCtx.sceneIndex,
                        triggerBeat = beat,
                        durationMs = override?.durationMs ?: defaultTotal,
                        delayMs = override?.delayMs ?: 0,
                    )
                )
            }
        }
    }

    val currentText = transition.targetState.text
    val taggedModifier = modifier.reviewTarget(
        kind = "MagicText",
        label = currentText.take(48).ifBlank { "MagicText" },
        textSnippet = currentText,
    )
    // The timeline target wraps the text's own bounds (not the filled slot) so stage picking and
    // the selection highlight hug the visible prose.
    Box(taggedModifier) {
        LocalDeckTextRenderer.current.AnimatedText(
            transition,
            Modifier.wrapContentSize(Alignment.TopStart).timelineTarget(trackId),
            style,
        )
    }
}
