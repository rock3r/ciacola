package dev.bnorm.storyboard.easel

import androidx.compose.animation.*
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.createChildTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import dev.bnorm.storyboard.*
import dev.bnorm.storyboard.easel.internal.FixedSize
import kotlinx.collections.immutable.ImmutableList

@Composable
fun Easel(
    animatic: Animatic,
    mode: SceneMode = SceneMode.Story,
    modifier: Modifier = Modifier,
) {
    // Only the presenting (Story) Easel owns the animatic's frame clock and freeze capture; preview
    // Easels on the same animatic (e.g. an assistant's "current frame") must not take them over or
    // clear them when they go away.
    val ownsHooks = mode == SceneMode.Story
    // Navigation must count frames on *this* window's clock (see Animatic.renderFrameClock).
    val renderScope = rememberCoroutineScope()
    if (ownsHooks) {
        DisposableEffect(animatic, renderScope) {
            val clock = renderScope.coroutineContext[MonotonicFrameClock]
            animatic.renderFrameClock = clock
            onDispose { if (animatic.renderFrameClock === clock) animatic.renderFrameClock = null }
        }
    }
    // While the animatic settles a segment (see Animatic.scrubSettling), keep presenting a pixel
    // snapshot of the last frame so the play-time-0 settle frames never reach the screen. A pixel
    // snapshot is required: a recorded layer still references its children's live layers.
    val presentedFrame = rememberGraphicsLayer()
    val recording = remember { PresentedFrameRecording() }
    var frozenFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    if (ownsHooks) {
        DisposableEffect(animatic, presentedFrame) {
            val capture: suspend () -> Boolean = {
                // Only snapshot a layer whose children are still alive: one recorded under the current
                // scrub epoch (each step disposes the scene subtree) and since the last unfreeze.
                // Otherwise the layer references freed render nodes, so refuse to snapshot it.
                val safe = recording.epoch == animatic.scrubEpoch && animatic.livePresentedSinceUnfreeze
                frozenFrame = if (safe) runCatching { presentedFrame.toImageBitmap() }.getOrNull() else null
                frozenFrame != null
            }
            animatic.freezeCapture = capture
            onDispose { if (animatic.freezeCapture === capture) animatic.freezeCapture = null }
        }
    }
    val settling = animatic.scrubSettling
    LaunchedEffect(settling) {
        if (!settling) frozenFrame = null
    }
    Box(
        modifier = modifier.drawWithContent {
            if (!ownsHooks) {
                drawContent()
                return@drawWithContent
            }
            val frozen = frozenFrame
            if (animatic.scrubSettling) {
                // Never draw presentedFrame here: it is not re-recorded while settling, so it may
                // reference a subtree that has been disposed since.
                if (frozen != null) {
                    drawImage(frozen, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                } else {
                    drawContent()
                }
            } else {
                presentedFrame.record { this@drawWithContent.drawContent() }
                recording.epoch = animatic.scrubEpoch
                animatic.onLiveFramePresented()
                drawLayer(presentedFrame)
            }
        },
        propagateMinConstraints = true,
    ) {
        // Keyed on the scrub epoch: every scrub step recomposes the scene subtree from scratch so
        // transition state can never leak along the scrub path (see Animatic.scrubEpoch).
        key(animatic.scrubEpoch) {
            Easel(
                storyboard = animatic.storyboard,
                transition = animatic.transition,
                mode = mode,
            )
        }
    }
}

/** Which [Animatic.scrubEpoch] the presented-frame layer was last recorded under (not state). */
private class PresentedFrameRecording(var epoch: Int = -1)

/**
 * Deterministic, seek-safe replacement for `Transition.AnimatedContent`.
 *
 * Mounts only `setOf(currentState, targetState)` in deterministic order and wraps each state in
 * `Transition.AnimatedVisibility` so random-access scrubbing (`SeekableTransitionState.seekTo`)
 * never suffers from `AnimatedContent`'s internal `currentlyVisible` queue drift.
 */
@Composable
fun <S> Transition<S>.DeterministicStateHost(
    modifier: Modifier = Modifier,
    enter: (S, S) -> EnterTransition = { _, _ -> fadeIn() },
    exit: (S, S) -> ExitTransition = { _, _ -> fadeOut() },
    contentKey: (S) -> Any? = { it },
    content: @Composable AnimatedVisibilityScope.(S) -> Unit,
) {
    val from = currentState
    val to = targetState
    val activeStates = remember(from, to) {
        if (contentKey(from) == contentKey(to)) {
            listOf(to)
        } else {
            listOf(from, to)
        }
    }
    Box(modifier = modifier) {
        for (state in activeStates) {
            key(contentKey(state)) {
                AnimatedVisibility(
                    visible = { contentKey(it) == contentKey(state) },
                    enter = enter(from, to),
                    exit = exit(from, to),
                ) {
                    content(state)
                }
            }
        }
    }
}

@Composable
fun Easel(
    storyboard: Storyboard,
    transition: Transition<out Animatic.State<*>>,
    mode: SceneMode = SceneMode.Story,
    modifier: Modifier = Modifier,
) {
    val holder = rememberSaveableStateHolder()
    SceneWrapper(storyboard, mode, modifier) {
        SharedTransitionLayout {
            transition.DeterministicStateHost(
                modifier = Modifier.fillMaxSize(),
                contentKey = { it.scene },
                enter = { fromState, toState ->
                    val direction = if (toState.scene >= fromState.scene) {
                        AdvanceDirection.Forward
                    } else {
                        AdvanceDirection.Backward
                    }
                    toState.scene.enterTransition(direction)
                },
                exit = { fromState, toState ->
                    val direction = if (toState.scene >= fromState.scene) {
                        AdvanceDirection.Forward
                    } else {
                        AdvanceDirection.Backward
                    }
                    fromState.scene.exitTransition(direction)
                },
            ) { state ->
                val scene = state.scene
                holder.SaveableStateProvider(scene) {
                    SceneContent(scene, transition)
                }
            }
        }
    }
}

private class StorySceneScope<T>(
    override val frames: ImmutableList<T>,
    override val transition: Transition<out Frame<T>>,
) : SceneScope<T>

@Composable
context(_: AnimatedVisibilityScope, _: SharedTransitionScope)
fun <T> SceneContent(
    scene: Scene<T>,
    sceneFrame: Transition<out Animatic.State<*>>,
) {
    val frame = sceneFrame.createChildTransition(label = "Frame") {
        @Suppress("UNCHECKED_CAST")
        when {
            scene > it.scene -> Frame.Start
            scene < it.scene -> Frame.End
            else -> it.frame as Frame<T>
        }
    }

    val scope = remember(scene, frame) {
        StorySceneScope(
            frames = scene.frames,
            transition = frame,
        )
    }

    scope.Render(scene.content)
}

@Composable
internal fun SceneWrapper(
    format: SceneFormat,
    decorator: ContentDecorator,
    sceneMode: SceneMode,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSceneMode provides sceneMode,
        LocalDensity provides format.density,
    ) {
        FixedSize(size = format.size, modifier = modifier) {
            decorator.decorate {
                content()
            }
        }
    }
}

@Composable
internal fun SceneWrapper(
    storyboard: Storyboard,
    sceneMode: SceneMode,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStoryboard provides storyboard,
        LocalSceneMode provides sceneMode,
        LocalDensity provides storyboard.format.density
    ) {
        FixedSize(size = storyboard.format.size, modifier = modifier) {
            storyboard.decorator.decorate {
                content()
            }
        }
    }
}
