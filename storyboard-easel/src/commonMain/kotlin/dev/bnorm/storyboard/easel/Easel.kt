package dev.bnorm.storyboard.easel

import androidx.compose.animation.*
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.createChildTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
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
    Easel(
        storyboard = animatic.storyboard,
        transition = animatic.transition,
        mode = mode,
        modifier = modifier
    )
}

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
