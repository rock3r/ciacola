package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.Agsl
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckMagicText
import dev.sebastiano.ciacola.authoring.DeckHostParams
import dev.sebastiano.ciacola.authoring.ciacolaDeck
import dev.sebastiano.ciacola.createEditableTextTransition
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.effects.CodeFit
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.effects.DeckShaderUniformValue
import dev.sebastiano.ciacola.effects.DeckSkslShader
import dev.sebastiano.ciacola.effects.DeckWarpCodeCard
import dev.sebastiano.ciacola.effects.LocalShaderUniformObserver
import dev.sebastiano.ciacola.effects.LocalWarpSegmentObserver
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.SlidePalette
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * QA property: whatever path the playhead takes, the frame rendered at time `t` must equal the
 * frame a fresh composition renders when seeked straight to `t`. Ambient loops are frozen so the
 * comparison is deterministic.
 */
@OptIn(ExperimentalTestApi::class)
class ScrubPathIndependenceTest {

    private companion object {
        const val LIVE_JITTER_FRAMES = 1
    }

    private fun frozenLoopDeck(): Storyboard {
        val frozenClock = DeckLoopClock().apply { scrubbedMillis = 0L }
        return createFoundationDeck(
            includeKeynoteEffectsLab = true,
            stageDecorator = ContentDecorator { content ->
                CompositionLocalProvider(LocalDeckLoopClock provides frozenClock) { content() }
            },
        )
    }

    private fun ComposeUiTest.runOnUi(scope: CoroutineScope, block: suspend () -> Unit) {
        lateinit var job: Job
        runOnIdle { job = scope.launch { block() } }
        waitUntil(timeoutMillis = 10_000) {
            mainClock.advanceTimeByFrame()
            job.isCompleted
        }
        waitForIdle()
    }

    private data class Diff(val differingPixels: Int, val total: Int, val maxChannelDelta: Float)

    private fun diff(a: ImageBitmap, b: ImageBitmap): Diff {
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        require(pa.width == pb.width && pa.height == pb.height) { "Size mismatch" }
        var differing = 0
        var maxDelta = 0f
        for (y in 0 until pa.height) {
            for (x in 0 until pa.width) {
                val ca = pa[x, y]
                val cb = pb[x, y]
                val d = maxOf(abs(ca.red - cb.red), abs(ca.green - cb.green), abs(ca.blue - cb.blue), abs(ca.alpha - cb.alpha))
                if (d > 0.06f) differing++
                if (d > maxDelta) maxDelta = d
            }
        }
        return Diff(differing, pa.width * pa.height, maxDelta)
    }

    private fun dump(name: String, image: ImageBitmap) {
        val dir = File("build/scrub-qa").apply { mkdirs() }
        ImageIO.write(image.toAwtImage(), "png", File(dir, "$name.png"))
    }

    /** Builds a forward-then-backward scrub path over [range] with the given step. */
    private fun sweep(range: IntRange, step: Int): List<Int> {
        val forward = (range.first..range.last step step).toList() + range.last
        return forward + forward.reversed()
    }

    private fun assertPathIndependent(
        pathTimes: List<Int>,
        checkpoints: Set<Int>,
        rapidFire: Boolean,
        inSegmentInPlace: Boolean = false,
    ) = runComposeUiTest {
        val deck = frozenLoopDeck()
        val snapshot = indexDeckTimeline(deck)
        lateinit var pathAnimatic: Animatic
        lateinit var refAnimatic: Animatic
        lateinit var scope: CoroutineScope
        var refGeneration by mutableIntStateOf(0)
        var refIndex by mutableStateOf(Storyboard.Index(0, 0))

        setContent {
            scope = rememberCoroutineScope()
            Row {
                Box(Modifier.size(480.dp, 270.dp).testTag("path")) {
                    pathAnimatic = rememberAnimatic(storyboard = { deck })
                    Easel(pathAnimatic)
                }
                key(refGeneration) {
                    Box(Modifier.size(480.dp, 270.dp).testTag("ref")) {
                        refAnimatic = rememberAnimatic(initialIndex = refIndex, storyboard = { deck })
                        Easel(refAnimatic)
                    }
                }
            }
        }
        waitForIdle()

        fun settledIndexFor(stateIdx: Int): Storyboard.Index {
            val d = pathAnimatic.stateDescriptors
                .last { it.stateIndex <= stateIdx && !it.isBoundaryStart && !it.isBoundaryEnd }
            return Storyboard.Index(d.sceneIndex, d.frameIndex)
        }

        val failures = mutableListOf<String>()
        val checkpointIndices = pathTimes.withIndex()
            .filter { (i, t) -> t in checkpoints && i >= pathTimes.size / 2 }
            .map { it.index }
            .toSet()
        for ((i, t) in pathTimes.withIndex()) {
            val (stateIdx, frac) = snapshot.stateFractionForTimeMs(t)
            if (rapidFire && i !in checkpointIndices) {
                // Simulate pointer-move bursts: fire without awaiting completion.
                runOnIdle { scope.launch { pathAnimatic.seekToSegment(stateIdx, frac) } }
                continue
            }
            // With the timeline's shared scrubStepPolicy, a drag step inside the active segment or
            // across intra-slide beats seeks in place; slide crossings and non-drag clicks rebuild.
            val decision = scrubStepPolicy(
                prev = ScrubStepEndpoint.fromActiveAnimatic(pathAnimatic),
                next = ScrubStepEndpoint.fromStateIndex(pathAnimatic, stateIdx, frac),
                dragging = inSegmentInPlace,
                forceRebuild = !inSegmentInPlace,
            )
            val rebuildScene = !decision.inPlace
            runOnUi(scope) { pathAnimatic.seekToSegment(stateIdx, frac, rebuildScene = rebuildScene) }
            if (i !in checkpointIndices) continue

            // Independent oracle: a fresh composition settled on the segment's resting state, then a
            // plain in-place seek (no scene rebuild) straight to t.
            runOnIdle {
                refIndex = settledIndexFor(stateIdx)
                refGeneration++
            }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            runOnUi(scope) { refAnimatic.seekToSegment(stateIdx, frac, rebuildScene = false) }

            val pathImg = onNodeWithTag("path").captureToImage()
            val refImg = onNodeWithTag("ref").captureToImage()
            val d = diff(pathImg, refImg)
            if (d.differingPixels > d.total / 400) {
                val tag = "t${t}_s${stateIdx}_f${(frac * 100).toInt()}"
                dump("$tag-path", pathImg)
                dump("$tag-ref", refImg)
                failures += "t=${t}ms (state=$stateIdx frac=${"%.2f".format(frac)}): " +
                    "${d.differingPixels}/${d.total} px differ (max Δ=${"%.2f".format(d.maxChannelDelta)})"
            }
        }
        if (failures.isNotEmpty()) {
            fail("Scrub path dependence detected (images in deck/build/scrub-qa):\n" + failures.joinToString("\n"))
        }
    }

    private fun midpointsOfSegments(range: IntRange): Set<Int> {
        val deck = frozenLoopDeck()
        val snapshot = indexDeckTimeline(deck)
        return snapshot.stateSpans
            .filter { it.durationMs > 0 }
            .flatMap { span -> listOf(span.startMs + span.durationMs / 4, span.startMs + span.durationMs / 2, span.startMs + span.durationMs * 3 / 4) }
            .filter { it in range }
            .toSet()
    }

    @Test
    fun scrubbingAcrossFirstBeatAndBackRendersTheFirstTransitionNotALaterOne() {
        val range = 0..1_200
        val checkpoints = midpointsOfSegments(range)
        val ordered = buildList {
            val fwd = (range.first..range.last step 20).toMutableSet().apply { addAll(checkpoints) }.sorted()
            addAll(fwd)
            addAll(fwd.reversed())
        }
        assertPathIndependent(ordered, checkpoints, rapidFire = false)
    }

    @Test
    fun fullDeckForwardThenBackwardScrubIsPathIndependent() {
        val deck = frozenLoopDeck()
        val total = indexDeckTimeline(deck).totalDurationMs
        val range = 0..total
        val checkpoints = midpointsOfSegments(range)
        val ordered = buildList {
            val fwd = (range.first..range.last step 45).toMutableSet().apply { addAll(checkpoints) }.sorted()
            addAll(fwd)
            addAll(fwd.reversed())
        }
        assertPathIndependent(ordered, checkpoints, rapidFire = false)
    }

    @Test
    fun rapidFireUnawaitedScrubBurstsConvergeToTheSameFrame() {
        val deck = frozenLoopDeck()
        val total = indexDeckTimeline(deck).totalDurationMs
        val range = 0..total
        val checkpoints = midpointsOfSegments(range)
        val ordered = buildList {
            val fwd = (range.first..range.last step 15).toMutableSet().apply { addAll(checkpoints) }.sorted()
            addAll(fwd)
            addAll(fwd.reversed())
        }
        assertPathIndependent(ordered, checkpoints, rapidFire = true)
    }

    @Test
    fun jitteringBackAndForthInsideEverySegmentIsPathIndependent() {
        val deck = frozenLoopDeck()
        val snapshot = indexDeckTimeline(deck)
        val checkpoints = mutableSetOf<Int>()
        val ordered = buildList {
            for (span in snapshot.stateSpans.filter { it.durationMs > 40 }) {
                val at = { f: Float -> span.startMs + (span.durationMs * f).toInt() }
                add(at(0.2f)); add(at(0.85f)); add(at(0.1f)); add(at(0.6f))
                checkpoints += at(0.6f)
            }
        }
        // Mark every element as "backward leg" by mirroring: checkpoints are only verified on the
        // second half, so append the same jitter path again.
        assertPathIndependent(ordered + ordered, checkpoints, rapidFire = false)
    }

    @Test
    fun fullDeckScrubWithInPlaceStepsInsideASegmentIsPathIndependent() {
        // A drag only rebuilds on segment changes (TimelineStudioController.runScrubWorker); steps
        // inside a segment seek in place and must still render the frame a fresh jump renders.
        val deck = frozenLoopDeck()
        val total = indexDeckTimeline(deck).totalDurationMs
        val range = 0..total
        val checkpoints = midpointsOfSegments(range)
        val ordered = buildList {
            val fwd = (range.first..range.last step 15).toMutableSet().apply { addAll(checkpoints) }.sorted()
            addAll(fwd)
            addAll(fwd.reversed())
        }
        assertPathIndependent(ordered, checkpoints, rapidFire = false, inSegmentInPlace = true)
    }

    @Test
    fun jitteringInsideEverySegmentWithInPlaceStepsIsPathIndependent() {
        val deck = frozenLoopDeck()
        val snapshot = indexDeckTimeline(deck)
        val checkpoints = mutableSetOf<Int>()
        val ordered = buildList {
            for (span in snapshot.stateSpans.filter { it.durationMs > 40 }) {
                val at = { f: Float -> span.startMs + (span.durationMs * f).toInt() }
                add(at(0.2f)); add(at(0.85f)); add(at(0.1f)); add(at(0.95f)); add(at(0.05f)); add(at(0.6f))
                checkpoints += at(0.6f)
            }
        }
        assertPathIndependent(ordered + ordered, checkpoints, rapidFire = false, inSegmentInPlace = true)
    }

    @Test
    fun sameSlideDragBackToBeatStartCrossBeatAndReturnIsPathIndependent() {
        val deck = frozenLoopDeck()
        val snapshot = indexDeckTimeline(deck)
        val slide0Beats = snapshot.eventBeats.filter { it.sceneIndex == 0 }
        val slide0Spans = snapshot.stateSpans.filter { it.sceneIndex == 0 && !it.isHold && it.durationMs > 40 }
        require(slide0Beats.size >= 3 && slide0Spans.size >= 2) {
            "Expected Slide 1 to have at least 3 beats and 2 animated spans"
        }
        val b0Start = slide0Beats[0].timeMs
        val b1Start = slide0Beats[1].timeMs
        val b2Start = slide0Beats[2].timeMs
        val seg0 = slide0Spans[0]
        val seg1 = slide0Spans[1]

        fun at(span: StateTimeSpan, frac: Float): Int = span.startMs + (span.durationMs * frac).toInt()

        val leg = listOf(
            // Drag forward into B1->B2, then pull back to B1 beat start (fraction 0)
            b0Start,
            at(seg0, 0.25f),
            at(seg0, 0.55f),
            at(seg0, 0.80f),
            at(seg0, 0.40f),
            b0Start,
            // Now drag forward across B1->B2, through B2 hold, across B2->B3
            at(seg0, 0.30f),
            at(seg0, 0.70f),
            b1Start,
            at(seg1, 0.35f),
            at(seg1, 0.75f),
            b2Start,
            // Return back across B2->B3 to B2 start, then back across B1->B2 to B1 start
            at(seg1, 0.65f),
            at(seg1, 0.25f),
            b1Start,
            at(seg0, 0.60f),
            at(seg0, 0.20f),
            b0Start,
        )
        val checkpoints = setOf(
            b0Start,
            at(seg0, 0.30f),
            at(seg0, 0.60f),
            b1Start,
            at(seg1, 0.35f),
            at(seg1, 0.65f),
            b2Start,
        )
        // Mirror leg so checkpoints in the second half cover back-to-beat-start, cross-beat, and return.
        assertPathIndependent(leg + leg, checkpoints, rapidFire = false, inSegmentInPlace = true)
    }

    @Test
    fun scrubbedFramesMatchLiveArrowKeyPlaybackWithinTwoFramesOfJitter() = runComposeUiTest {
        // Ground truth is what the audience sees: arrow-key playback. For every played frame, a scrub
        // to the same (segment, fraction) must render the same picture. Both are driven by seeking
        // the same transition, so they should agree; ±LIVE_JITTER_FRAMES absorbs the frame quantum
        // between a sampled live fraction and the frame it was rendered at.
        mainClock.autoAdvance = false
        val deck = frozenLoopDeck()
        lateinit var path: Animatic
        lateinit var live: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Row {
                Box(Modifier.size(320.dp, 180.dp).testTag("path")) {
                    path = rememberAnimatic(storyboard = { deck })
                    Easel(path)
                }
                Box(Modifier.size(320.dp, 180.dp).testTag("live")) {
                    live = rememberAnimatic(storyboard = { deck })
                    Easel(live)
                }
            }
        }
        mainClock.advanceTimeBy(500)

        class Sample(val from: Int, val fraction: Float, val image: ImageBitmap)
        val failures = mutableListOf<String>()
        val liveFrames = sortedMapOf<Int, Int>()
        val measured = indexDeckTimeline(deck).stateSpans.filter { !it.isHold }.associate { it.stateIndex to it.durationMs }
        var checked = 0
        while (true) {
            var advanced: Boolean? = null
            lateinit var job: Job
            runOnIdle { job = scope.launch { advanced = live.advance(dev.bnorm.storyboard.AdvanceDirection.Forward) } }
            val samples = mutableListOf<Sample>()
            var guard = 0
            while (!job.isCompleted && guard++ < 400) {
                mainClock.advanceTimeByFrame()
                val from = live.activeFromStateIndex
                if (live.activeToStateIndex == from + 1) {
                    liveFrames[from] = (liveFrames[from] ?: 0) + 1
                    samples += Sample(from, live.activeFraction, onNodeWithTag("live").captureToImage())
                }
            }
            mainClock.advanceTimeBy(200)
            for (i in samples.indices step 3) {
                val s = samples[i]
                runOnUi(scope) { path.seekToSegment(s.from, s.fraction) }
                val scrubTotalMs = path.effectiveDurationNanos() / 1_000_000L
                val timelineMs = measured[s.from] ?: 0
                if (s.fraction > 0f && abs(scrubTotalMs - timelineMs) > 20) {
                    failures += "segment=${s.from}: a scrub maps fractions over ${scrubTotalMs}ms but the timeline span is ${timelineMs}ms"
                }
                val img = onNodeWithTag("path").captureToImage()
                checked++
                val best = (maxOf(0, i - LIVE_JITTER_FRAMES)..minOf(samples.lastIndex, i + LIVE_JITTER_FRAMES))
                    .minOf { diff(img, samples[it].image).differingPixels }
                if (best > img.width * img.height / 400) {
                    val tag = "live_s${s.from}_f${(s.fraction * 1000).toInt()}"
                    dump("$tag-scrub", img)
                    dump("$tag-live", s.image)
                    failures += "segment=${s.from} fraction=${"%.3f".format(s.fraction)}: $best px differ from the closest live frame"
                }
            }
            if (advanced != true) break
        }
        if (checked < 50) fail("Too few live samples checked: $checked")
        // The timeline's segment lengths must be the real playback lengths. A freshly targeted
        // segment rests a few settle frames before moving, so allow ~5 frames of slack.
        for ((segment, frames) in liveFrames) {
            val liveMs = frames * 16
            val timelineMs = measured[segment] ?: 0
            if (abs(liveMs - timelineMs) > 80) {
                failures += "segment=$segment plays for ~${liveMs}ms live but the timeline says ${timelineMs}ms"
            }
        }
        println("live frames per segment: $liveFrames; timeline: $measured")
        if (failures.isNotEmpty()) {
            fail("Scrubbed frames diverge from live playback (images in deck/build/scrub-qa):\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun backToBackScrubStepsAcrossSlidesPresentEachStepInsteadOfTheFirstFrozenFrame() = runComposeUiTest {
        // The timeline's conflated scrub worker runs seeks back to back from one coroutine while
        // the playhead is dragged. Every completed step must reach the screen: the stage may lag a
        // step behind the pointer, but it must not stay frozen on the frame the drag started from.
        mainClock.autoAdvance = false
        val deck = frozenLoopDeck()
        val snapshot = indexDeckTimeline(deck)
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(480.dp, 270.dp).testTag("stage")) {
                animatic = rememberAnimatic(storyboard = { deck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(500)

        // Slide 2 resting on its last beat (the expanded shared card), across slide 3 (ambient
        // loop), into the middle of slide 4.
        val startMs = snapshot.stateSpans.last { it.sceneIndex == 1 && it.isHold }.startMs
        val slide3RestMs = snapshot.stateSpans.first { it.sceneIndex == 2 && it.isHold }.startMs
        val slide4Rest = snapshot.stateSpans.first { it.sceneIndex == 3 && it.isHold }
        val endMs = slide4Rest.startMs + slide4Rest.durationMs / 2
        val (startState, startFrac) = snapshot.stateFractionForTimeMs(startMs)
        runOnUi(scope) { animatic.seekToSegment(startState, startFrac) }
        mainClock.advanceTimeBy(300)
        val slide2 = onNodeWithTag("stage").captureToImage()

        var completedMs = startMs
        lateinit var worker: Job
        runOnIdle {
            worker = scope.launch {
                for (t in (startMs..endMs step 40) + endMs) {
                    val (state, frac) = snapshot.stateFractionForTimeMs(t)
                    animatic.seekToSegment(state, frac)
                    completedMs = t
                }
            }
        }
        class Capture(val completedMs: Int, val image: ImageBitmap)
        val captures = mutableListOf<Capture>()
        var frame = 0
        while (!worker.isCompleted && frame++ < 2_000) {
            mainClock.advanceTimeByFrame()
            if (frame % 3 == 0 && completedMs >= slide3RestMs) {
                captures += Capture(completedMs, onNodeWithTag("stage").captureToImage())
            }
        }
        if (!worker.isCompleted) fail("Scrub worker did not finish")
        if (captures.size < 5) fail("Too few mid-sequence captures: ${captures.size}")
        val stale = captures.filter { diff(it.image, slide2).differingPixels < it.image.width * it.image.height / 100 }
        if (stale.isNotEmpty()) {
            stale.take(3).forEach { dump("stale-scrub-${it.completedMs}ms", it.image) }
            dump("stale-scrub-slide2", slide2)
            fail(
                "Stage still showed the slide-2 frame in ${stale.size}/${captures.size} captures taken after " +
                    "scrub steps in slide 3/4 completed (at ${stale.map { it.completedMs }.take(10)} ms; images in deck/build/scrub-qa)",
            )
        }
    }

    @Test
    fun rapidArrowPressesSkipAheadInsteadOfQueueingAnimations() = runComposeUiTest {
        mainClock.autoAdvance = false
        val deck = frozenLoopDeck()
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(320.dp, 180.dp)) {
                animatic = rememberAnimatic(storyboard = { deck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(300)
        val segment = indexDeckTimeline(deck).stateSpans.first { !it.isHold && it.durationMs > 0 }.durationMs
        // Two presses 100 ms apart: the second one snaps the first animation to its end and plays
        // only the second segment, so after one segment's duration we already rest on beat 3.
        runOnIdle { scope.launch { animatic.advance(dev.bnorm.storyboard.AdvanceDirection.Forward) } }
        mainClock.advanceTimeBy(100)
        runOnIdle { scope.launch { animatic.advance(dev.bnorm.storyboard.AdvanceDirection.Forward) } }
        mainClock.advanceTimeBy(segment.toLong() + 200)
        runOnIdle {
            assertEquals(Storyboard.Index(0, 2), animatic.currentIndex)
            assertEquals(animatic.activeFromStateIndex, animatic.activeToStateIndex, "Settled, not still animating a queued press")
        }
    }

    @Test
    fun settledFramesAfterArrowKeyNavigationForwardAndBackMatchAFreshJump() = runComposeUiTest {
        val deck = frozenLoopDeck()
        lateinit var pathAnimatic: Animatic
        lateinit var refAnimatic: Animatic
        lateinit var scope: CoroutineScope
        var refGeneration by mutableIntStateOf(0)
        var refIndex by mutableStateOf(Storyboard.Index(0, 0))

        setContent {
            scope = rememberCoroutineScope()
            Row {
                Box(Modifier.size(480.dp, 270.dp).testTag("path")) {
                    pathAnimatic = rememberAnimatic(storyboard = { deck })
                    Easel(pathAnimatic)
                }
                key(refGeneration) {
                    Box(Modifier.size(480.dp, 270.dp).testTag("ref")) {
                        refAnimatic = rememberAnimatic(initialIndex = refIndex, storyboard = { deck })
                        Easel(refAnimatic)
                    }
                }
            }
        }
        waitForIdle()

        val failures = mutableListOf<String>()
        fun compareAt(label: String) {
            mainClock.advanceTimeBy(2_000)
            runOnIdle {
                refIndex = pathAnimatic.currentIndex
                refGeneration++
            }
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            val pathImg = onNodeWithTag("path").captureToImage()
            val refImg = onNodeWithTag("ref").captureToImage()
            val d = diff(pathImg, refImg)
            if (d.differingPixels > d.total / 400) {
                dump("$label-path", pathImg)
                dump("$label-ref", refImg)
                failures += "$label @ ${pathAnimatic.currentIndex}: ${d.differingPixels}/${d.total} px differ"
            }
        }

        var steps = 0
        while (true) {
            var advanced = false
            runOnIdle { scope.launch { advanced = pathAnimatic.advance(dev.bnorm.storyboard.AdvanceDirection.Forward) } }
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            if (!advanced) break
            compareAt("fwd${steps++}")
        }
        steps = 0
        while (true) {
            var advanced = false
            runOnIdle { scope.launch { advanced = pathAnimatic.advance(dev.bnorm.storyboard.AdvanceDirection.Backward) } }
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            if (!advanced) break
            compareAt("back${steps++}")
        }
        if (failures.isNotEmpty()) fail("Settled navigation frames diverge:\n" + failures.joinToString("\n"))
    }

    private fun createMultiBeatShaderWarpScrubFixtureDeck(
        stageDecorator: ContentDecorator,
    ): Storyboard {
        val colorEvolutionSksl = """
            uniform float2 iResolution;
            uniform float uStage;

            half4 main(float2 fragCoord) {
                vec2 uv = fragCoord / max(iResolution, vec2(1.0));
                vec2 cell = floor(fragCoord / max(iResolution.x / 12.0, 8.0));
                float checker = mod(cell.x + cell.y, 2.0);
                vec3 transparentBg = mix(vec3(0.78, 0.79, 0.80), vec3(0.95, 0.96, 0.96), checker);
                vec3 whiteCol = vec3(1.0, 1.0, 1.0);
                vec3 redCol = vec3(0.96, 0.14, 0.14);
                vec3 uvCol = vec3(uv.x, uv.y, 1.0);
                vec3 c01 = mix(transparentBg, whiteCol, clamp(uStage, 0.0, 1.0));
                vec3 c12 = mix(c01, redCol, clamp(uStage - 1.0, 0.0, 1.0));
                vec3 c23 = mix(c12, uvCol, clamp(uStage - 2.0, 0.0, 1.0));
                return half4(c23, 1.0);
            }
        """.trimIndent()

        return ciacolaDeck(
            id = "scrub-sync-fixture",
            title = "Scrub Sync Fixture",
            host = DeckHostParams(
                theme = DeckThemes.keynoteLight(),
                bundledPreset = DeckThemePreset.KeynoteLight,
                stageDecorator = stageDecorator,
            ),
        ) {
            slide(key = "intro", title = "Intro", beats = 1) {
                val activeTheme = LocalDeckTheme.current
                Box(Modifier.fillMaxSize().padding(64.dp)) {
                    DeckEditableText(
                        elementId = "scrub:s1:title",
                        text = "Opening Slide",
                        style = activeTheme.typography.title,
                        color = activeTheme.colors.foreground,
                    )
                }
            }
            slide(key = "color-uv", title = "Color Evolution to UV", beats = 4) {
                val activeTheme = LocalDeckTheme.current
                val warpStep = rememberBeatTransition("scrub-s2-warp-step")
                val shaderStage by animateByBeat("scrub-s2-shader-stage") { it.toFloat() }
                val captionTransition = transition.createEditableTextTransition(
                    "scrub:s2:caption",
                    "scrub-s2-caption",
                ) { step ->
                    when (step) {
                        0 -> AnnotatedString("Channels are 0.0..1.0 floats — 0.0 is transparent")
                        1 -> AnnotatedString("Set RGBA to 1.0 for opaque solid white")
                        2 -> AnnotatedString("Keep red at 1.0 to isolate a single channel")
                        else -> buildAnnotatedString {
                            append("Normalize by ")
                            withStyle(SpanStyle(color = activeTheme.colors.accent, fontWeight = FontWeight.Bold)) {
                                append("iResolution")
                            }
                            append(" for a smooth UV gradient")
                        }
                    }
                }
                val codeSnippets = remember {
                    listOf(
                        "half4 main(float2 fragCoord) {\n    return half4(0.0);\n}",
                        "half4 main(float2 fragCoord) {\n    return half4(1.0, 1.0, 1.0, 1.0);\n}",
                        "half4 main(float2 fragCoord) {\n    return half4(1.0, 0.0, 0.0, 1.0);\n}",
                        "uniform float2 iResolution;\n\nhalf4 main(float2 fragCoord) {\n    float2 uv = fragCoord.xy / iResolution.xy;\n    return half4(uv.x, uv.y, 1.0, 1.0);\n}",
                    )
                }
                val rgbaTransition = transition.createEditableTextTransition(
                    "scrub:s2:rgba-readout",
                    "scrub-s2-rgba-readout",
                ) { step ->
                    when (step) {
                        0 -> AnnotatedString("RGBA (0.0, 0.0, 0.0, 0.0)")
                        1 -> AnnotatedString("RGBA (1.0, 1.0, 1.0, 1.0)")
                        2 -> AnnotatedString("RGBA (1.0, 0.0, 0.0, 1.0)")
                        else -> AnnotatedString("RGBA (uv.x, uv.y, 1.0, 1.0)")
                    }
                }
                Column(Modifier.fillMaxSize().padding(56.dp), Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.fillMaxWidth().height(48.dp)) {
                        DeckMagicText(
                            transition = captionTransition,
                            elementId = "scrub:s2:caption",
                            label = "Color Evolution Headline",
                            style = activeTheme.typography.heading.copy(
                                fontSize = 32.sp,
                                lineHeight = 38.sp,
                                color = activeTheme.colors.foreground,
                            ),
                        )
                    }
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        Arrangement.spacedBy(22.dp),
                        Alignment.CenterVertically,
                    ) {
                        DeckWarpCodeCard(
                            codeStates = codeSnippets,
                            stateTransition = warpStep,
                            language = Agsl,
                            textStyle = activeTheme.typography.codeCompact,
                            elementId = "scrub:s2:code",
                            highlightedLines = { step -> if (step < 3) setOf(1) else setOf(0, 3, 4) },
                            fit = CodeFit.ShrinkToWidth(min = 0.82f),
                            modifier = Modifier.weight(1f).testTag("scrub-s2-warp-code"),
                        )
                        Column(
                            Modifier.width(316.dp),
                            Arrangement.spacedBy(12.dp),
                            Alignment.CenterHorizontally,
                        ) {
                            DeckSkslShader(
                                id = "scrub:s2:shader",
                                sksl = colorEvolutionSksl,
                                label = "Live Color Evolution Preview",
                                modifier = Modifier.size(252.dp).testTag("scrub-shader-color-uv"),
                            ) {
                                float("uStage", shaderStage)
                            }
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                DeckMagicText(
                                    transition = rgbaTransition,
                                    modifier = Modifier.wrapContentWidth(),
                                    elementId = "scrub:s2:rgba-readout",
                                    label = "RGBA Channel Readout",
                                    kind = "Code",
                                    style = activeTheme.typography.codeCompact.copy(
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = activeTheme.colors.accent,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            slide(
                key = "next-palette",
                title = "Compose Host",
                beats = 2,
                palette = SlidePalette(
                    canvas = Color(0xFFDCF2E8),
                    ink = Color(0xFF14352B),
                    accent = Color(0xFF0E7A5D),
                ),
            ) {
                val activeTheme = LocalDeckTheme.current
                Box(Modifier.fillMaxSize().padding(64.dp)) {
                    DeckEditableText(
                        elementId = "scrub:s3:title",
                        text = byBeat("RuntimeShader Host — Beat 1", "RuntimeShader Host — Beat 2"),
                        style = activeTheme.typography.title,
                        color = Color(0xFF14352B),
                    )
                }
            }
        }
    }

    @Test
    fun multiBeatShaderWarpAndMagicTextScrubWorkerMatchesFreshJumpAcrossBoundaryAndMidpoints() {
        val tempDir = Files.createTempDirectory("ciacola-shader-warp-scrub-")
        val frozenClock = DeckLoopClock().apply { scrubbedMillis = 0L }
        val scrubDeck = createMultiBeatShaderWarpScrubFixtureDeck(
            stageDecorator = ContentDecorator { content ->
                CompositionLocalProvider(LocalDeckLoopClock provides frozenClock) { content() }
            },
        )
        val snapshot = indexDeckTimeline(scrubDeck)
        try {
            runComposeUiTest {
                mainClock.autoAdvance = false
                val overridesStore = TimelineOverridesStore(tempDir)
                val pathRegistry = StageInspectionRegistry()
                val refRegistry = StageInspectionRegistry()
                var pathUStage = -99f
                var refUStage = -99f
                var pathWarpSeg: Pair<Int, Int> = -1 to -1
                var refWarpSeg: Pair<Int, Int> = -1 to -1

                lateinit var pathAnimatic: Animatic
                lateinit var refAnimatic: Animatic
                lateinit var studioController: TimelineStudioController
                lateinit var scope: CoroutineScope
                var refGen by mutableIntStateOf(0)
                var refInitialIndex by mutableStateOf(Storyboard.Index(1, 0))

                setContent {
                    scope = rememberCoroutineScope()
                    Row {
                        Box(Modifier.size(480.dp, 270.dp).testTag("s2-path")) {
                            CompositionLocalProvider(
                                LocalStageInspectionRegistry provides pathRegistry,
                                LocalShaderUniformObserver provides { id, uniforms ->
                                    if (id == "scrub:s2:shader") {
                                        pathUStage = (uniforms["uStage"] as? DeckShaderUniformValue.FloatValue)?.value ?: -99f
                                    }
                                },
                                LocalWarpSegmentObserver provides { id, fromIdx, toIdx ->
                                    if (id == "scrub:s2:code") pathWarpSeg = fromIdx to toIdx
                                },
                            ) {
                                pathAnimatic = rememberAnimatic(
                                    initialIndex = Storyboard.Index(1, 3),
                                    storyboard = { scrubDeck },
                                )
                                studioController = remember {
                                    TimelineStudioController(
                                        initialAnimatic = pathAnimatic,
                                        overridesStore = overridesStore,
                                        loopClock = frozenClock,
                                    )
                                }
                                androidx.compose.runtime.LaunchedEffect(studioController) {
                                    studioController.runScrubWorker()
                                }
                                Easel(pathAnimatic)
                            }
                        }
                        key(refGen) {
                            Box(Modifier.size(480.dp, 270.dp).testTag("s2-ref")) {
                                CompositionLocalProvider(
                                    LocalStageInspectionRegistry provides refRegistry,
                                    LocalShaderUniformObserver provides { id, uniforms ->
                                        if (id == "scrub:s2:shader") {
                                            refUStage = (uniforms["uStage"] as? DeckShaderUniformValue.FloatValue)?.value ?: -99f
                                        }
                                    },
                                    LocalWarpSegmentObserver provides { id, fromIdx, toIdx ->
                                        if (id == "scrub:s2:code") refWarpSeg = fromIdx to toIdx
                                    },
                                ) {
                                    refAnimatic = rememberAnimatic(
                                        initialIndex = refInitialIndex,
                                        storyboard = { scrubDeck },
                                    )
                                    Easel(refAnimatic)
                                }
                            }
                        }
                    }
                }
                repeat(6) { mainClock.advanceTimeByFrame() }
                runOnIdle { studioController.refreshSnapshot(scrubDeck) }
                repeat(2) { mainClock.advanceTimeByFrame() }

                fun scrubStep(timeMs: Int, dragging: Boolean) {
                    runOnIdle {
                        if (dragging && !studioController.isScrubDragging) {
                            studioController.beginScrubDrag()
                        } else if (!dragging && studioController.isScrubDragging) {
                            studioController.endScrubDrag()
                        }
                        studioController.scrubToTimeMs(timeMs)
                    }
                    var guard = 0
                    while (guard++ < 600) {
                        mainClock.advanceTimeByFrame()
                        if (!studioController.hasPendingScrubRequest && !pathAnimatic.scrubSettling) {
                            repeat(3) { mainClock.advanceTimeByFrame() }
                            if (!studioController.hasPendingScrubRequest && !pathAnimatic.scrubSettling) break
                        }
                    }
                    assertTrue(
                        !studioController.hasPendingScrubRequest && !pathAnimatic.scrubSettling,
                        "Scrub worker did not settle at t=${timeMs}ms within 600 frames",
                    )
                }

                fun settledIndexFor(stateIdx: Int): Storyboard.Index {
                    val d = pathAnimatic.stateDescriptors
                        .last { it.stateIndex <= stateIdx && !it.isBoundaryStart && !it.isBoundaryEnd }
                    return Storyboard.Index(d.sceneIndex, d.frameIndex)
                }

                fun activeTextOf(reg: StageInspectionRegistry, prefix: String): String =
                    reg.nodes.values
                        .filter { it.sceneIndex == 1 && it.elementId.startsWith(prefix) && it.effectiveAlpha > 0.05f }
                        .maxByOrNull { it.beatIndex }
                        ?.resolvedText
                        .orEmpty()

                fun assertMatchesFreshReference(label: String, timeMs: Int) {
                    val (stateIdx, frac) = snapshot.stateFractionForTimeMs(timeMs)
                    runOnIdle {
                        refRegistry.clear()
                        refInitialIndex = settledIndexFor(stateIdx)
                        refGen++
                    }
                    repeat(6) { mainClock.advanceTimeByFrame() }
                    runOnUi(scope) { refAnimatic.seekToSegment(stateIdx, frac, rebuildScene = true) }
                    repeat(4) { mainClock.advanceTimeByFrame() }

                    val pathImg = onNodeWithTag("s2-path").captureToImage()
                    val refImg = onNodeWithTag("s2-ref").captureToImage()

                    assertEquals(
                        refUStage,
                        pathUStage,
                        2e-2f,
                        "$label (t=${timeMs}ms, state=$stateIdx, frac=$frac): uStage uniform mismatch (expected=$refUStage, actual=$pathUStage)",
                    )
                    assertEquals(
                        refWarpSeg.second,
                        pathWarpSeg.second,
                        "$label (t=${timeMs}ms): warp toIdx mismatch (expected=$refWarpSeg, actual=$pathWarpSeg)",
                    )
                    val expectedCaption = activeTextOf(refRegistry, "scrub:s2:caption")
                    val actualCaption = activeTextOf(pathRegistry, "scrub:s2:caption")
                    assertEquals(expectedCaption, actualCaption, "$label (t=${timeMs}ms): headline caption mismatch")

                    val expectedReadout = activeTextOf(refRegistry, "scrub:s2:rgba-readout")
                    val actualReadout = activeTextOf(pathRegistry, "scrub:s2:rgba-readout")
                    assertEquals(expectedReadout, actualReadout, "$label (t=${timeMs}ms): RGBA readout mismatch")

                    val d = diff(pathImg, refImg)
                    if (d.differingPixels > d.total / 300) {
                        dump("s2-$label-path", pathImg)
                        dump("s2-$label-ref", refImg)
                        fail(
                            "$label (t=${timeMs}ms, state=$stateIdx, frac=${"%.2f".format(frac)}): " +
                                "${d.differingPixels}/${d.total} px differ (max Δ=${"%.2f".format(d.maxChannelDelta)}, " +
                                "pathUStage=$pathUStage, refUStage=$refUStage)"
                        )
                    }
                }

                val s2Bar = snapshot.slides.first { it.sceneIndex == 1 }
                val s2HoldSpans = snapshot.stateSpans.filter { it.sceneIndex == 1 && it.isHold }
                val s2AnimSpans = snapshot.stateSpans.filter { it.sceneIndex == 1 && !it.isHold && !it.isBoundary && it.durationMs > 40 }
                assertEquals(4, s2HoldSpans.size, "Expected 4 beat holds on multi-beat scrub fixture")
                assertEquals(3, s2AnimSpans.size, "Expected 3 intra-slide beat transitions on multi-beat scrub fixture")

                val b1HoldMs = s2HoldSpans[0].startMs + s2HoldSpans[0].durationMs / 2
                val b4HoldMs = s2HoldSpans[3].startMs + s2HoldSpans[3].durationMs / 2
                val b4ToS3BoundaryMs = s2Bar.endMs - 60
                val segB2ToB3 = s2AnimSpans[1]
                val b2b3At25Ms = segB2ToB3.startMs + (segB2ToB3.durationMs * 0.25f).toInt()
                val b2b3At75Ms = segB2ToB3.startMs + (segB2ToB3.durationMs * 0.75f).toInt()

                // (i) Backward drag from B4 to B1, sampled at B2->B3 75%, B2->B3 25%, and the B1 hold.
                val backSteps = 20
                for (step in 0..backSteps) {
                    val t = b4ToS3BoundaryMs + ((b1HoldMs - b4ToS3BoundaryMs) * step) / backSteps
                    scrubStep(t, dragging = true)
                }
                scrubStep(b1HoldMs, dragging = true)
                assertEquals(0f, pathUStage, 1e-2f, "S2:B1 hold after backward drag must bind uStage = 0.0")
                assertMatchesFreshReference("back-b4-to-b1-hold", b1HoldMs)

                // (iii) Forward drag through B2->B3 sampled at 25% and 75%.
                for (t in listOf(
                    s2AnimSpans[0].startMs + s2AnimSpans[0].durationMs / 2,
                    s2HoldSpans[1].startMs + 20,
                    b2b3At25Ms,
                )) {
                    scrubStep(t, dragging = true)
                }
                assertMatchesFreshReference("fwd-b2-to-b3-at-25pct", b2b3At25Ms)

                scrubStep(b2b3At75Ms, dragging = true)
                assertMatchesFreshReference("fwd-b2-to-b3-at-75pct", b2b3At75Ms)

                // Also reverse inside B2->B3 back to 25% and back up to 75%.
                scrubStep(b2b3At25Ms, dragging = true)
                assertMatchesFreshReference("rev-b2-to-b3-at-25pct", b2b3At25Ms)
                scrubStep(b2b3At75Ms, dragging = true)

                // (ii) Continue forward drag from B2->B3 to B4 and end on the S2:B4 -> S3:B1 boundary hold.
                val fwdTailSteps = 12
                for (step in 1..fwdTailSteps) {
                    val t = b2b3At75Ms + ((b4ToS3BoundaryMs - b2b3At75Ms) * step) / fwdTailSteps
                    scrubStep(t, dragging = true)
                }
                scrubStep(b4HoldMs, dragging = true)
                assertEquals(3f, pathUStage, 1e-2f, "S2:B4 hold after forward drag must bind uStage = 3.0")
                assertMatchesFreshReference("fwd-b1-to-b4-hold", b4HoldMs)

                scrubStep(b4ToS3BoundaryMs, dragging = true)
                assertEquals(3f, pathUStage, 1e-2f, "S2:B4->S3:B1 boundary hold must bind uStage = 3.0, never 2.0")
                assertMatchesFreshReference("fwd-s2b4-to-s3b1-boundary", b4ToS3BoundaryMs)
                mainClock.autoAdvance = true
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
