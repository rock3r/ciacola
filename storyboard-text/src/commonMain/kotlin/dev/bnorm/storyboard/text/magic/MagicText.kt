package dev.bnorm.storyboard.text.magic

// Modified from Storyboard upstream: transition bookkeeping and interruption stability hardened.

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmName
import kotlin.math.roundToInt

const val DefaultMoveDurationMillis = 300
const val DefaultFadeDurationMillis = 300
const val DefaultDelayDurationMillis = 0

@Composable
fun MagicText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    // Used to time FadeOut --(Delay)-> Move --(Delay)-> FadeIn animations.
    moveDurationMillis: Int = DefaultMoveDurationMillis,
    fadeDurationMillis: Int = DefaultFadeDurationMillis,
    delayDurationMillis: Int = DefaultDelayDurationMillis,
) {
    val words = remember(text) { text.toWords() }
    val transition = updateTransition(words)
    MagicText(transition, modifier, moveDurationMillis, fadeDurationMillis, delayDurationMillis)
}

@Composable
@JvmName("MagicTextAnnotatedString")
fun MagicText(
    transition: Transition<AnnotatedString>,
    modifier: Modifier = Modifier,
    // Used to time FadeOut --(Delay)-> Move --(Delay)-> FadeIn animations.
    moveDurationMillis: Int = DefaultMoveDurationMillis,
    fadeDurationMillis: Int = DefaultFadeDurationMillis,
    delayDurationMillis: Int = DefaultDelayDurationMillis,
) {
    val worlds = transition.createChildTransition { remember(it) { it.toWords() } }
    MagicText(worlds, modifier, moveDurationMillis, fadeDurationMillis, delayDurationMillis)
}

@Composable
fun MagicText(
    tokenizedText: List<AnnotatedString>,
    modifier: Modifier = Modifier,
    // Used to time FadeOut --(Delay)-> Move --(Delay)-> FadeIn animations.
    moveDurationMillis: Int = DefaultMoveDurationMillis,
    fadeDurationMillis: Int = DefaultFadeDurationMillis,
    delayDurationMillis: Int = DefaultDelayDurationMillis,
) {
    val transition = updateTransition(tokenizedText)
    MagicText(transition, modifier, moveDurationMillis, fadeDurationMillis, delayDurationMillis)
}

@Composable
@JvmName("MagicTextList")
fun MagicText(
    transition: Transition<List<AnnotatedString>>,
    modifier: Modifier = Modifier,
    // Used to time FadeOut --(Delay)-> Move --(Delay)-> FadeIn animations.
    moveDurationMillis: Int = DefaultMoveDurationMillis,
    fadeDurationMillis: Int = DefaultFadeDurationMillis,
    delayDurationMillis: Int = DefaultDelayDurationMillis,
) {
    MagicTextInternal(transition, modifier, fadeDurationMillis, moveDurationMillis, delayDurationMillis)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MagicTextInternal(
    transition: Transition<List<AnnotatedString>>,
    modifier: Modifier,
    fadeDuration: Int, // Millis
    moveDuration: Int, // Millis
    delayDuration: Int, // Millis
) {
    val moveDelay = delayDuration + fadeDuration
    val fadeInDelay = 2 * delayDuration + fadeDuration + moveDuration
    val totalDuration = fadeInDelay + fadeDuration

    // The correspondence plan is a pure function of the (from, to) pair, so a seek renders the same
    // frame no matter which states were visited before (timeline scrubbing depends on this).
    val from = transition.currentState
    val to = transition.targetState
    val plan = remember(from, to) {
        MagicTextPlanner.plan(from.flatMap { toLines(it) }, to.flatMap { toLines(it) })
    }
    val sizeProgress = transition.animateFloat(
        transitionSpec = { tween(totalDuration, easing = EaseInOut) },
        label = "MagicTextSize",
    ) { if (it == transition.targetState) 1f else 0f }

    SharedTransitionLayout(modifier) {
        transition.SeekSafeTokenHost(
            sizeProgress = sizeProgress,
            enter = fadeIn(tween(fadeDuration, delayMillis = fadeInDelay, easing = EaseInCubic)),
            exit = fadeOut(tween(fadeDuration, easing = EaseOutCubic)),
            modifier = Modifier.wrapContentSize(align = Alignment.TopStart, unbounded = true),
        ) { tokens ->
            val parts = if (from != to && tokens == to) plan.target else plan.source

            @Composable
            fun SharedText.toModifier(): Modifier {
                return if (key == null) {
                    Modifier
                } else {
                    Modifier.sharedBounds(
                        rememberSharedContentState(key),
                        animatedVisibilityScope = this@SeekSafeTokenHost,
                        enter =
                            if (crossFade) fadeIn(tween(moveDuration, delayMillis = moveDelay, easing = EaseInOut))
                            else EnterTransition.None,
                        exit =
                            if (crossFade) fadeOut(tween(moveDuration, delayMillis = moveDelay, easing = EaseInOut))
                            else ExitTransition.None,
                        boundsTransform = { _, _ ->
                            tween(moveDuration, delayMillis = moveDelay, easing = EaseInOut)
                        },
                    )
                }
            }

            Column(horizontalAlignment = Alignment.Start) {
                val iterator = parts.iterator()
                while (iterator.hasNext()) {
                    Row {
                        var itemCount = 0
                        while (iterator.hasNext()) {
                            val sharedText = iterator.next().takeIf { it.value.text != "\n" }
                            if (sharedText != null) {
                                Text(sharedText.value, sharedText.toModifier().alignByBaseline())
                                itemCount++
                            } else {
                                if (itemCount == 0) Text("") // Need something in the row...
                                break
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Seek-safe replacement for `AnimatedContent`: mounts only the transition's current and target
 * states (never a queue of stale states), and interpolates the container size between them with a
 * transition-driven [sizeProgress], so every frame is a pure function of the transition position.
 */
@Composable
private fun <S> Transition<S>.SeekSafeTokenHost(
    sizeProgress: State<Float>,
    enter: EnterTransition,
    exit: ExitTransition,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.(S) -> Unit,
) {
    val from = currentState
    val to = targetState
    val active = if (from == to) listOf(to) else listOf(from, to)
    Layout(
        modifier = modifier,
        content = {
            for (state in active) {
                key(state) {
                    AnimatedVisibility(
                        visible = { it == state },
                        enter = enter,
                        exit = exit,
                    ) {
                        content(state)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val (width, height) = if (placeables.size == 2) {
            val p = sizeProgress.value.coerceIn(0f, 1f)
            val w = placeables[0].width + (placeables[1].width - placeables[0].width) * p
            val h = placeables[0].height + (placeables[1].height - placeables[0].height) * p
            w.roundToInt() to h.roundToInt()
        } else {
            (placeables.firstOrNull()?.width ?: 0) to (placeables.firstOrNull()?.height ?: 0)
        }
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

internal class MagicTextPlan(
    val source: List<SharedText>,
    val target: List<SharedText>,
)

/**
 * Pure, history-free correspondence planning. The source side always uses canonical, index-derived
 * keys, so a settled state keeps the same keys it will start its next transition with, and the
 * target side derives its keys from that canonical source. Planning never mutates shared inputs.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object MagicTextPlanner {
    fun canonical(tokens: List<AnnotatedString>): List<SharedText> =
        tokens.mapIndexed { index, value -> SharedText(value, "c:$index") }

    fun plan(from: List<AnnotatedString>, to: List<AnnotatedString>): MagicTextPlan {
        val source = canonical(from)
        if (from == to) return MagicTextPlan(source, source)
        val target = findShared(AtomicLong(0), source, to)
        return MagicTextPlan(source, target)
    }
}

private fun toLines(string: AnnotatedString): List<AnnotatedString> = buildList {
    return buildList {
        var offset = 0
        while (true) {
            val index = string.indexOf('\n', offset)
            if (index == -1) break

            if (index > offset) add(string.subSequence(offset, index))
            add(AnnotatedString("\n"))
            offset = index + 1
        }
        if (offset < string.length) {
            add(string.subSequence(offset, string.length))
        }
    }
}
