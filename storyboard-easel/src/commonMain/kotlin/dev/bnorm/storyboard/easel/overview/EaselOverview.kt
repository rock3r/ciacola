package dev.bnorm.storyboard.easel.overview

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.clampStoryboardIndex
import dev.bnorm.storyboard.easel.onStoryNavigation
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Whether [EaselOverview] shows the grid; hoist it to toggle the overview from outside (e.g. a menu). */
@Stable
class OverviewVisibility(initiallyVisible: Boolean = false) {
    var isVisible: Boolean by mutableStateOf(initiallyVisible)

    fun toggle() {
        isVisible = !isVisible
    }
}

@Composable
fun rememberOverviewVisibility(initiallyVisible: Boolean = false): OverviewVisibility =
    remember { OverviewVisibility(initiallyVisible) }

@Composable
fun EaselOverview(
    animatic: Animatic,
    modifier: Modifier = Modifier,
    visibility: OverviewVisibility = rememberOverviewVisibility(),
    content: @Composable () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    val holder = rememberSaveableStateHolder()

    val storyboard = animatic.storyboard
    DisposableEffect(holder, storyboard) {
        onDispose {
            holder.removeState(storyboard)
        }
    }
    val overviewState = remember(storyboard) {
        OverviewState.of(storyboard).apply {
            if (storyboard.indices.isNotEmpty()) {
                jumpToIndex(clampStoryboardIndex(storyboard.indices, animatic.currentIndex))
            }
        }
    }
    var overviewVisible by visibility::isVisible

    // Track the animatic in the overview's selection: a timeline scrub or step (overview open or
    // closed) and opening the overview from any entry point (Esc, menu) highlight the current beat,
    // so Enter never jumps back to a stale selection. The overview's own selection changes move the
    // animatic to the same index, so this converges instead of looping.
    LaunchedEffect(animatic, overviewState, visibility) {
        snapshotFlow { animatic.currentIndex to visibility.isVisible }.collect { (index, _) ->
            if (storyboard.indices.isNotEmpty()) {
                overviewState.jumpToIndex(clampStoryboardIndex(storyboard.indices, index))
            }
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
            overviewState.jumpToIndex(clampStoryboardIndex(storyboard.indices, animatic.currentIndex))
            overviewVisible = true
            return true
        }
        return false
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
    ) {
        SharedTransitionLayout {
            AnimatedContent(
                targetState = overviewVisible,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }
            ) { isOverview ->
                if (isOverview) {
                    val isVisible = transition.currentState == EnterExitState.Visible &&
                            transition.currentState == transition.targetState
                    CompositionLocalProvider(LocalOverviewOpen provides true) {
                        EaselOverviewInternal(
                            animatic = animatic,
                            overviewState = overviewState,
                            isVisible = isVisible,
                            onExitOverview = {
                                job?.cancel()
                                job = coroutineScope.launch {
                                    animatic.jumpTo(it)
                                    job = null
                                    overviewVisible = false
                                }
                            },
                            onDismiss = { overviewVisible = false },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    holder.SaveableStateProvider(storyboard) {
                        var containerSize by remember { mutableStateOf(IntSize.Zero) }
                        var viewportSize by remember { mutableStateOf(IntSize.Zero) }
                        val isLetterboxedOrPillarboxed = containerSize.width > 0 &&
                            containerSize.height > 0 &&
                            viewportSize.width > 0 &&
                            viewportSize.height > 0 &&
                            (abs(containerSize.width - viewportSize.width) > 1 ||
                                abs(containerSize.height - viewportSize.height) > 1)
                        val showViewportHairline = !LocalPresentingFullscreen.current || isLetterboxedOrPillarboxed
                        val colors = MaterialTheme.colors
                        val hairlineColor = colors.onBackground.copy(alpha = if (colors.isLight) 0.16f else 0.18f)

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { containerSize = it },
                        ) {
                            Box(
                                modifier = Modifier
                                    .sharedElement(
                                        rememberSharedContentState(OverviewCurrentItemKey),
                                        animatedVisibilityScope = this@AnimatedContent
                                    )
                                    .onSizeChanged { viewportSize = it }
                                    .drawWithContent {
                                        drawContent()
                                        if (showViewportHairline && size.width > 2f && size.height > 2f) {
                                            val strokePx = 1.dp.toPx()
                                            drawRect(
                                                color = hairlineColor,
                                                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                                                size = Size(size.width - strokePx, size.height - strokePx),
                                                style = Stroke(width = strokePx),
                                            )
                                        }
                                    }
                                    .onStoryNavigation(animatic)
                                    .onKeyEvent { handleKeyEvent(it) }
                            ) {
                                content()
                            }
                        }
                    }
                }
            }
        }
    }
}
