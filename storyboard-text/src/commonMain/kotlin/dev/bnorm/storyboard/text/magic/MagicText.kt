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
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.AnnotatedString
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmName
import kotlin.math.abs
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
    // Offsets the whole choreography from the start of the transition.
    delayMillis: Int = 0,
    // Overrides the move and fade-in easing (exits always accelerate away).
    easing: Easing? = null,
) {
    val words = remember(text) { text.toWords() }
    val transition = updateTransition(words)
    MagicText(transition, modifier, moveDurationMillis, fadeDurationMillis, delayDurationMillis, delayMillis, easing)
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
    // Offsets the whole choreography from the start of the transition.
    delayMillis: Int = 0,
    // Overrides the move and fade-in easing (exits always accelerate away).
    easing: Easing? = null,
) {
    val worlds = transition.createChildTransition { remember(it) { it.toWords() } }
    MagicText(worlds, modifier, moveDurationMillis, fadeDurationMillis, delayDurationMillis, delayMillis, easing)
}

@Composable
fun MagicText(
    tokenizedText: List<AnnotatedString>,
    modifier: Modifier = Modifier,
    // Used to time FadeOut --(Delay)-> Move --(Delay)-> FadeIn animations.
    moveDurationMillis: Int = DefaultMoveDurationMillis,
    fadeDurationMillis: Int = DefaultFadeDurationMillis,
    delayDurationMillis: Int = DefaultDelayDurationMillis,
    // Offsets the whole choreography from the start of the transition.
    delayMillis: Int = 0,
    // Overrides the move and fade-in easing (exits always accelerate away).
    easing: Easing? = null,
) {
    val transition = updateTransition(tokenizedText)
    MagicText(transition, modifier, moveDurationMillis, fadeDurationMillis, delayDurationMillis, delayMillis, easing)
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
    // Offsets the whole choreography from the start of the transition.
    delayMillis: Int = 0,
    // Overrides the move and fade-in easing (exits always accelerate away).
    easing: Easing? = null,
) {
    MagicTextInternal(transition, modifier, fadeDurationMillis, moveDurationMillis, delayDurationMillis, delayMillis, easing)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MagicTextInternal(
    transition: Transition<List<AnnotatedString>>,
    modifier: Modifier,
    fadeDuration: Int, // Millis
    moveDuration: Int, // Millis
    delayDuration: Int, // Millis
    startDelay: Int, // Millis
    easing: Easing?,
) {
    // The correspondence plan is a pure function of the (from, to) pair, so a seek renders the same
    // frame no matter which states were visited before (timeline scrubbing depends on this).
    val from = transition.currentState
    val to = transition.targetState
    val plan = remember(from, to) {
        MagicTextPlanner.plan(from.flatMap { toLines(it) }, to.flatMap { toLines(it) })
    }
    val choreography = remember(plan, fadeDuration, moveDuration, delayDuration, startDelay, easing) {
        MagicTextChoreography.of(
            fadeDurationMillis = fadeDuration,
            moveDurationMillis = moveDuration,
            delayDurationMillis = delayDuration,
            startDelayMillis = startDelay,
            easing = easing,
            hasMovement = plan.hasMovement,
        )
    }
    val moveDelay = choreography.moveDelayMillis
    val moveSpan = choreography.moveDurationMillis
    val moveEasing = choreography.moveEasing

    val sizeSpec = remember(choreography) {
        tween<Float>(
            durationMillis = choreography.sizeDurationMillis,
            delayMillis = choreography.sizeDelayMillis,
            easing = choreography.sizeEasing,
        )
    }

    SharedTransitionLayout(modifier) {
        transition.SeekSafeTokenHost(
            sizeSpec = sizeSpec,
            enter = fadeIn(
                tween(choreography.enterDurationMillis, delayMillis = choreography.enterDelayMillis, easing = choreography.enterEasing),
            ),
            exit = fadeOut(
                tween(choreography.exitDurationMillis, delayMillis = choreography.exitDelayMillis, easing = choreography.exitEasing),
            ),
            modifier = Modifier.wrapContentSize(align = Alignment.TopStart),
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
                            if (crossFade) fadeIn(tween(moveSpan, delayMillis = moveDelay, easing = moveEasing))
                            else EnterTransition.None,
                        exit =
                            if (crossFade) fadeOut(tween(moveSpan, delayMillis = moveDelay, easing = moveEasing))
                            else ExitTransition.None,
                        boundsTransform = { _, _ ->
                            tween(moveSpan, delayMillis = moveDelay, easing = moveEasing)
                        },
                    )
                }
            }

            Column(horizontalAlignment = Alignment.Start) {
                val iterator = parts.iterator()
                while (iterator.hasNext()) {
                    val lineTokens = buildList {
                        while (iterator.hasNext()) {
                            val next = iterator.next()
                            if (next.value.text == "\n") break
                            add(next)
                        }
                    }
                    if (lineTokens.isEmpty()) {
                        Text("") // Preserve blank-line height.
                    } else {
                        WrappingTokenLine(lineTokens) { sharedText ->
                            Text(sharedText.value, sharedText.toModifier())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WrappingTokenLine(
    tokens: List<SharedText>,
    itemContent: @Composable (SharedText) -> Unit,
) {
    val isWhitespace = remember(tokens) {
        BooleanArray(tokens.size) { i ->
            val t = tokens[i].value.text
            t.isNotEmpty() && t.all { it.isWhitespace() }
        }
    }
    Layout(
        content = {
            for (token in tokens) {
                itemContent(token)
            }
        },
    ) { measurables, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }
        val maxWidth = constraints.maxWidth

        class PlacedToken(val placeable: Placeable, val x: Int, val countTowardWidth: Boolean)
        class VisualLine(val items: MutableList<PlacedToken> = mutableListOf(), var usedWidth: Int = 0)

        val lines = mutableListOf(VisualLine())
        var currentHasNonSpace = false
        var cursorX = 0
        var idx = 0

        while (idx < placeables.size) {
            if (isWhitespace[idx]) {
                val p = placeables[idx]
                if (!currentHasNonSpace && lines.size == 1) {
                    // Explicit leading indentation at the start of a logical line (e.g. code).
                    lines.last().items += PlacedToken(p, cursorX, countTowardWidth = true)
                    cursorX += p.width
                    lines.last().usedWidth = cursorX
                    idx++
                } else {
                    // Look ahead to the next non-whitespace word cluster to decide whether to wrap.
                    var clusterEnd = idx + 1
                    while (clusterEnd < placeables.size && !isWhitespace[clusterEnd]) {
                        clusterEnd++
                    }
                    var clusterWidth = 0
                    for (k in (idx + 1) until clusterEnd) {
                        clusterWidth += placeables[k].width
                    }
                    val fitsOnCurrentLine = clusterEnd == idx + 1 || (cursorX + p.width + clusterWidth <= maxWidth)
                    if (fitsOnCurrentLine) {
                        lines.last().items += PlacedToken(p, cursorX, countTowardWidth = clusterEnd < placeables.size)
                        cursorX += p.width
                        if (clusterEnd < placeables.size) {
                            lines.last().usedWidth = cursorX
                        }
                    } else {
                        // Soft wrap: park the space token at the end of the outgoing line without
                        // pushing the wrapped word off x = 0 on the continuation line.
                        val parkedX = cursorX.coerceAtMost((maxWidth - p.width).coerceAtLeast(0))
                        lines.last().items += PlacedToken(p, parkedX, countTowardWidth = false)
                        lines += VisualLine()
                        cursorX = 0
                        currentHasNonSpace = false
                    }
                    idx++
                }
            } else {
                var clusterEnd = idx + 1
                while (clusterEnd < placeables.size && !isWhitespace[clusterEnd]) {
                    clusterEnd++
                }
                var clusterWidth = 0
                for (k in idx until clusterEnd) {
                    clusterWidth += placeables[k].width
                }
                if (currentHasNonSpace && cursorX + clusterWidth > maxWidth) {
                    lines += VisualLine()
                    cursorX = 0
                    currentHasNonSpace = false
                }
                for (k in idx until clusterEnd) {
                    val p = placeables[k]
                    if (currentHasNonSpace && cursorX + p.width > maxWidth) {
                        lines += VisualLine()
                        cursorX = 0
                    }
                    lines.last().items += PlacedToken(p, cursorX, countTowardWidth = true)
                    cursorX += p.width
                    lines.last().usedWidth = cursorX
                    currentHasNonSpace = true
                }
                idx = clusterEnd
            }
        }

        val lineAscents = IntArray(lines.size)
        val lineHeights = IntArray(lines.size)
        var totalHeight = 0
        var maxLineWidth = 0

        for ((lineIdx, line) in lines.withIndex()) {
            if (line.usedWidth > maxLineWidth) maxLineWidth = line.usedWidth
            var maxAscent = 0
            var maxDescent = 0
            var maxRawH = 0
            for (item in line.items) {
                val p = item.placeable
                if (p.height > maxRawH) maxRawH = p.height
                val baseline = p[FirstBaseline]
                if (baseline != AlignmentLine.Unspecified) {
                    if (baseline > maxAscent) maxAscent = baseline
                    val descent = p.height - baseline
                    if (descent > maxDescent) maxDescent = descent
                } else if (p.height > maxAscent) {
                    maxAscent = p.height
                }
            }
            val h = maxOf(maxAscent + maxDescent, maxRawH)
            lineAscents[lineIdx] = maxAscent
            lineHeights[lineIdx] = h
            totalHeight += h
        }

        val layoutW = maxLineWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutH = totalHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(layoutW, layoutH) {
            var yTop = 0
            for ((lineIdx, line) in lines.withIndex()) {
                val ascent = lineAscents[lineIdx]
                for (item in line.items) {
                    val p = item.placeable
                    val baseline = p[FirstBaseline]
                    val y = yTop + if (baseline != AlignmentLine.Unspecified) (ascent - baseline) else 0
                    p.placeRelative(item.x, y)
                }
                yTop += lineHeights[lineIdx]
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
    sizeSpec: FiniteAnimationSpec<Float>,
    enter: EnterTransition,
    exit: ExitTransition,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.(S) -> Unit,
) {
    val from = currentState
    val to = targetState
    val active = if (from == to) listOf(to) else listOf(from, to)
    // Driven by the incoming branch's fresh `EnterExitState` transition (`PreEnter -> Visible`),
    // whose initial and target states differ from birth so `SeekableTransitionState.seekTo` never
    // triggers `TransitionAnimationState`'s `defaultSpring` fallback from a `0f == 0f` rest state.
    val sizeProgressHolder = remember { arrayOfNulls<State<Float>>(1) }
    if (active.size < 2) sizeProgressHolder[0] = null
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
                        if (active.size == 2 && state == to) {
                            sizeProgressHolder[0] = transition.animateFloat(
                                transitionSpec = { sizeSpec },
                                label = "MagicTextSizeProgress",
                            ) { enterExit ->
                                if (enterExit == EnterExitState.Visible) 1f else 0f
                            }
                        }
                        content(state)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val (width, height) = if (placeables.size == 2) {
            if (isLookingAhead) {
                placeables[1].width to placeables[1].height
            } else {
                val p = (sizeProgressHolder[0]?.value ?: 1f).coerceIn(0f, 1f)
                val w = placeables[0].width + (placeables[1].width - placeables[0].width) * p
                val h = placeables[0].height + (placeables[1].height - placeables[0].height) * p
                w.roundToInt() to h.roundToInt()
            }
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
) {
    /**
     * Whether any visible matched (shared-element) token can change position between [source] and
     * [target]. Tokens inside the common prefix lay out identically on both sides, so a transition
     * whose matched tokens all sit inside that prefix moves them by 0 px and needs no move phase.
     */
    val hasMovement: Boolean by lazy {
        var prefix = 0
        while (prefix < source.size && prefix < target.size && source[prefix].value == target[prefix].value) {
            prefix++
        }
        val sourceKeys = source.mapNotNullTo(HashSet()) { it.key }
        target.withIndex().any { (index, token) ->
            index >= prefix && token.key != null && token.key in sourceKeys && token.value.text.isNotBlank()
        }
    }
}

/**
 * Pure timing plan for one MagicText transition: when outgoing words fade out, when shared words
 * move, and when incoming words fade in (all in millis from the start of the transition).
 */
internal class MagicTextChoreography(
    val exitDelayMillis: Int,
    val exitDurationMillis: Int,
    val exitEasing: Easing,
    val moveDelayMillis: Int,
    val moveDurationMillis: Int,
    val moveEasing: Easing,
    val enterDelayMillis: Int,
    val enterDurationMillis: Int,
    val enterEasing: Easing,
    val sizeDelayMillis: Int = moveDelayMillis,
    val sizeDurationMillis: Int = moveDurationMillis,
    val sizeEasing: Easing = moveEasing,
) {
    val totalDurationMillis: Int = maxOf(
        exitDelayMillis + exitDurationMillis,
        moveDelayMillis + moveDurationMillis,
        enterDelayMillis + enterDurationMillis,
        sizeDelayMillis + sizeDurationMillis,
    )

    /** Alpha of an outgoing (deleted) word at [timeMillis]. */
    fun exitAlpha(timeMillis: Int): Float = 1f - exitEasing.transform(fraction(timeMillis, exitDelayMillis, exitDurationMillis))

    /** Alpha of an incoming (inserted) word at [timeMillis]. */
    fun enterAlpha(timeMillis: Int): Float = enterEasing.transform(fraction(timeMillis, enterDelayMillis, enterDurationMillis))

    private fun fraction(time: Int, delay: Int, duration: Int): Float =
        if (duration <= 0) (if (time >= delay) 1f else 0f) else ((time - delay).toFloat() / duration).coerceIn(0f, 1f)

    companion object {
        fun of(
            fadeDurationMillis: Int,
            moveDurationMillis: Int,
            delayDurationMillis: Int,
            startDelayMillis: Int = 0,
            easing: Easing? = null,
            hasMovement: Boolean = true,
        ): MagicTextChoreography {
            val fade = fadeDurationMillis.coerceAtLeast(0)
            val move = moveDurationMillis.coerceAtLeast(0)
            val gap = delayDurationMillis.coerceAtLeast(0)
            val start = startDelayMillis.coerceAtLeast(0)
            // The overall span stays FadeOut + Move + FadeIn (+ gaps) so the timeline segment is unchanged.
            val total = 2 * fade + move + 2 * gap
            // Exits accelerate away (outgoing words linger, then leave); entrances decelerate in.
            val exitEasing = EaseInCubic
            val moveCurve = easing ?: FastOutSlowInEasing
            val enterEasing = easing ?: EaseOutCubic
            return if (!hasMovement || move == 0) {
                // Nothing travels: stagger Deletions -> Container Size Morph -> Additions so outgoing
                // text visibly clears before layout bounds glide along a symmetric S-curve and incoming
                // text materializes into the newly sized slot.
                val exitSpan = (total * 0.42f).roundToInt()
                val enterSpan = total - exitSpan
                val sizeDelay = (total * 0.30f).roundToInt()
                val sizeSpan = (total * 0.48f).roundToInt().coerceAtLeast(1)
                MagicTextChoreography(
                    exitDelayMillis = start,
                    exitDurationMillis = exitSpan,
                    exitEasing = exitEasing,
                    moveDelayMillis = start,
                    moveDurationMillis = total,
                    moveEasing = easing ?: EaseInOut,
                    enterDelayMillis = start + exitSpan,
                    enterDurationMillis = enterSpan,
                    enterEasing = enterEasing,
                    sizeDelayMillis = start + sizeDelay,
                    sizeDurationMillis = sizeSpan,
                    sizeEasing = easing ?: EaseInOutCubic,
                )
            } else {
                // Staggered Keynote-style FadeOut -> Move (+ Layout Bounds Morph) -> FadeIn:
                // deletions clear first, then shared words and container bounds glide together, and
                // additions fade into the newly opened slots.
                val moveDelay = gap + fade
                val enterDelay = 2 * gap + fade + move
                MagicTextChoreography(
                    exitDelayMillis = start,
                    exitDurationMillis = fade,
                    exitEasing = exitEasing,
                    moveDelayMillis = start + moveDelay,
                    moveDurationMillis = move,
                    moveEasing = easing ?: EaseInOut,
                    enterDelayMillis = start + enterDelay,
                    enterDurationMillis = fade,
                    enterEasing = enterEasing,
                    sizeDelayMillis = start + moveDelay,
                    sizeDurationMillis = move,
                    sizeEasing = moveCurve,
                )
            }
        }
    }
}

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
