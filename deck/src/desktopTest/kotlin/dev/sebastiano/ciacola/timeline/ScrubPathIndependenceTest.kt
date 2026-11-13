package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
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
            // With the timeline's in-segment policy, a step inside the segment the stage already
            // shows seeks in place; anything else rebuilds the scene subtree.
            val inPlace = inSegmentInPlace &&
                scrubStepIsInPlace(pathAnimatic.activeFromStateIndex, pathAnimatic.activeToStateIndex, stateIdx, frac)
            runOnUi(scope) { pathAnimatic.seekToSegment(stateIdx, frac, rebuildScene = !inPlace) }
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
}
