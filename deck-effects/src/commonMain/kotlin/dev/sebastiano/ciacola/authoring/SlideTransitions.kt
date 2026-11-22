package dev.sebastiano.ciacola.authoring

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import dev.bnorm.storyboard.SceneEnterTransition
import dev.bnorm.storyboard.SceneExitTransition
import dev.sebastiano.ciacola.effects.LocalStageAnchorRegistry
import dev.sebastiano.ciacola.effects.StageAnchorRegistry
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.provideStageVisibilityAlpha
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.LocalReducedMotion
import dev.sebastiano.ciacola.theme.LocalSlidePalette
import dev.sebastiano.ciacola.theme.SlidePalette

private const val REDUCED_MOTION_SLIDE_FADE_MS = 120
private const val CONTENT_EXIT_END_FRACTION = 0.45f
private const val CONTENT_ENTER_START_FRACTION = 0.35f

/**
 * Two-layer slide transition wrapper separating the background canvas from foreground content:
 *
 * - **Background layer:** holds the outgoing slide canvas at full opacity (`1f`) while crossfading
 *   the incoming [palette] canvas over it so palette switches never dip through a dark frame.
 * - **Content layer:** dissolves outgoing content over `[0, 0.45]` and incoming content over
 *   `[0.35, 1.0]` using a single tween across every [EnterExitState] segment (so scrubbing and
 *   reversing through `SeekableTransitionState` never lose the transition spec). Under
 *   [LocalReducedMotion], degrades to a plain 120 ms opacity fade.
 */
@Composable
context(animatedVisibilityScope: AnimatedVisibilityScope)
fun SlideLayers(
    palette: SlidePalette?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val theme = LocalDeckTheme.current
    val reducedMotion = LocalReducedMotion.current
    val dissolveDurationMs = if (reducedMotion) {
        minOf(theme.motion.dissolveDurationMs, REDUCED_MOTION_SLIDE_FADE_MS).coerceAtLeast(40)
    } else {
        theme.motion.dissolveDurationMs.coerceAtLeast(80)
    }

    val visibilityTransition = animatedVisibilityScope.transition
    // Mount the dissolve animation only while the slide is actually entering or exiting.
    // Under SeekableTransitionState, a child Transition that remains Visible -> Visible across
    // in-slide beats receives resetAnimationFraction(0f) without seekAnimations recursing into it
    // (because child.currentState == child.targetState), leaving TransitionAnimationState with
    // isFinished = false && isSeeking = false so its first exit falls back to Compose's default
    // spring interruptionSpec instead of our linear tween. Mounting only while transitioning keeps
    // every enter/exit dissolve freshly initialized with the exact tween spec.
    val isTransitioning = visibilityTransition.currentState != visibilityTransition.targetState
    val progressState: State<Float>? = if (isTransitioning) {
        visibilityTransition.animateFloat(
            transitionSpec = {
                tween(durationMillis = dissolveDurationMs, easing = LinearEasing)
            },
            label = "SlideLayersDissolveProgress",
        ) { state ->
            if (state == EnterExitState.Visible) 1f else 0f
        }
    } else {
        null
    }

    val readProgress: () -> Float = {
        progressState?.value?.coerceIn(0f, 1f)
            ?: if (visibilityTransition.targetState == EnterExitState.Visible) 1f else 0f
    }

    val backgroundAlpha: () -> Float = {
        val isExiting = visibilityTransition.targetState != EnterExitState.Visible
        if (isExiting) {
            1f
        } else {
            val p = readProgress()
            if (reducedMotion) p else EaseInOut.transform(p)
        }
    }

    val contentAlpha: () -> Float = {
        val p = readProgress()
        if (reducedMotion) {
            p
        } else {
            val isExiting = visibilityTransition.targetState != EnterExitState.Visible
            if (isExiting) {
                val u = (1f - p).coerceIn(0f, 1f)
                val exitT = (u / CONTENT_EXIT_END_FRACTION).coerceIn(0f, 1f)
                1f - EaseInOut.transform(exitT)
            } else {
                val enterT = ((p - CONTENT_ENTER_START_FRACTION) / (1f - CONTENT_ENTER_START_FRACTION))
                    .coerceIn(0f, 1f)
                EaseInOut.transform(enterT)
            }
        }
    }

    val slideAnchorRegistry = remember { StageAnchorRegistry() }

    Box(modifier = modifier.fillMaxSize()) {
        if (palette != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = backgroundAlpha() }
                    .background(palette.canvas),
            )
        }
        val contentLayer: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .provideStageVisibilityAlpha(contentAlpha)
                    .graphicsLayer { alpha = contentAlpha() },
            ) {
                content()
            }
        }
        if (palette != null) {
            CompositionLocalProvider(
                LocalEffectiveBackgroundColor provides palette.canvas,
                LocalSlidePalette provides palette,
                LocalStageAnchorRegistry provides slideAnchorRegistry,
                content = contentLayer,
            )
        } else {
            CompositionLocalProvider(
                LocalStageAnchorRegistry provides slideAnchorRegistry,
                content = contentLayer,
            )
        }
    }
}
