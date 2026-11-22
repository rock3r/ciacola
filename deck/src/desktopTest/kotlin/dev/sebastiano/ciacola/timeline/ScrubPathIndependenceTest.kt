package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.buildFoundationTestStoryboard
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.CodePayload
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.effects.DeckShaderUniformValue
import dev.sebastiano.ciacola.effects.DeckSkslShader
import dev.sebastiano.ciacola.effects.LocalShaderUniformObserver
import dev.sebastiano.ciacola.effects.LocalWarpSegmentObserver
import dev.sebastiano.ciacola.indexFoundationTimelineSnapshot
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.loadFoundationTestDocument
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

/**
 * QA property: whatever path the playhead takes, the frame rendered at time `t` must equal the
 * frame a fresh composition renders when seeked straight to `t`. Ambient loops are frozen via
 * deterministic `RenderSession` clocks so the comparison is 100% deterministic.
 */
@OptIn(ExperimentalTestApi::class)
class ScrubPathIndependenceTest {

    private companion object {
        const val LIVE_JITTER_FRAMES = 1
    }

    private fun newFrozenDeck(): Storyboard = buildFoundationTestStoryboard(
        freeClockMillis = { 0L },
    )

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

    private fun assertPathIndependent(
        pathTimes: List<Int>,
        checkpoints: Set<Int>,
        rapidFire: Boolean,
        inSegmentInPlace: Boolean = false,
    ) = runComposeUiTest {
        val snapshot = indexFoundationTimelineSnapshot()
        lateinit var pathAnimatic: Animatic
        lateinit var refAnimatic: Animatic
        lateinit var scope: CoroutineScope
        var refGeneration by mutableIntStateOf(0)
        var refIndex by mutableStateOf(Storyboard.Index(0, 0))

        setContent {
            scope = rememberCoroutineScope()
            val pathDeck = remember { newFrozenDeck() }
            Row {
                Box(Modifier.size(480.dp, 270.dp).testTag("path")) {
                    pathAnimatic = rememberAnimatic(storyboard = { pathDeck })
                    Easel(pathAnimatic)
                }
                key(refGeneration) {
                    val refDeck = remember { newFrozenDeck() }
                    Box(Modifier.size(480.dp, 270.dp).testTag("ref")) {
                        refAnimatic = rememberAnimatic(initialIndex = refIndex, storyboard = { refDeck })
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
                runOnIdle { scope.launch { pathAnimatic.seekToSegment(stateIdx, frac) } }
                continue
            }
            val decision = scrubStepPolicy(
                prev = ScrubStepEndpoint.fromActiveAnimatic(pathAnimatic),
                next = ScrubStepEndpoint.fromStateIndex(pathAnimatic, stateIdx, frac),
                dragging = inSegmentInPlace,
                forceRebuild = !inSegmentInPlace,
            )
            val rebuildScene = !decision.inPlace
            runOnUi(scope) { pathAnimatic.seekToSegment(stateIdx, frac, rebuildScene = rebuildScene) }
            if (i !in checkpointIndices) continue

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
        val snapshot = indexFoundationTimelineSnapshot()
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
        val total = indexFoundationTimelineSnapshot().totalDurationMs
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
        val total = indexFoundationTimelineSnapshot().totalDurationMs
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
        val snapshot = indexFoundationTimelineSnapshot()
        val checkpoints = mutableSetOf<Int>()
        val ordered = buildList {
            for (span in snapshot.stateSpans.filter { it.durationMs > 40 }) {
                val at = { f: Float -> span.startMs + (span.durationMs * f).toInt() }
                add(at(0.2f)); add(at(0.85f)); add(at(0.1f)); add(at(0.6f))
                checkpoints += at(0.6f)
            }
        }
        assertPathIndependent(ordered + ordered, checkpoints, rapidFire = false)
    }

    @Test
    fun fullDeckScrubWithInPlaceStepsInsideASegmentIsPathIndependent() {
        val total = indexFoundationTimelineSnapshot().totalDurationMs
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
        val snapshot = indexFoundationTimelineSnapshot()
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
        val snapshot = indexFoundationTimelineSnapshot()
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
            b0Start,
            at(seg0, 0.25f),
            at(seg0, 0.55f),
            at(seg0, 0.80f),
            at(seg0, 0.40f),
            b0Start,
            at(seg0, 0.30f),
            at(seg0, 0.70f),
            b1Start,
            at(seg1, 0.35f),
            at(seg1, 0.75f),
            b2Start,
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
        assertPathIndependent(leg + leg, checkpoints, rapidFire = false, inSegmentInPlace = true)
    }

    @Test
    fun scrubbedFramesMatchLiveArrowKeyPlaybackWithinTwoFramesOfJitter() = runComposeUiTest {
        mainClock.autoAdvance = false
        lateinit var path: Animatic
        lateinit var live: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            val pathDeck = remember { newFrozenDeck() }
            val liveDeck = remember { newFrozenDeck() }
            Row {
                Box(Modifier.size(320.dp, 180.dp).testTag("path")) {
                    path = rememberAnimatic(storyboard = { pathDeck })
                    Easel(path)
                }
                Box(Modifier.size(320.dp, 180.dp).testTag("live")) {
                    live = rememberAnimatic(storyboard = { liveDeck })
                    Easel(live)
                }
            }
        }
        mainClock.advanceTimeBy(500)

        class Sample(val from: Int, val fraction: Float, val image: ImageBitmap)
        val failures = mutableListOf<String>()
        val liveFrames = sortedMapOf<Int, Int>()
        val measured = indexFoundationTimelineSnapshot().stateSpans.filter { !it.isHold }.associate { it.stateIndex to it.durationMs }
        var checked = 0
        while (true) {
            var advanced: Boolean? = null
            lateinit var job: Job
            runOnIdle { job = scope.launch { advanced = live.advance(AdvanceDirection.Forward) } }
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
        for ((segment, frames) in liveFrames) {
            val liveMs = frames * 16
            val timelineMs = measured[segment] ?: 0
            if (abs(liveMs - timelineMs) > 96) {
                failures += "segment=$segment plays for ~${liveMs}ms live but the timeline says ${timelineMs}ms"
            }
        }
        if (failures.isNotEmpty()) {
            fail("Scrubbed frames diverge from live playback (images in deck/build/scrub-qa):\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun backToBackScrubStepsAcrossSlidesPresentEachStepInsteadOfTheFirstFrozenFrame() = runComposeUiTest {
        mainClock.autoAdvance = false
        val snapshot = indexFoundationTimelineSnapshot()
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            val deck = remember { newFrozenDeck() }
            Box(Modifier.size(480.dp, 270.dp).testTag("stage")) {
                animatic = rememberAnimatic(storyboard = { deck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(500)

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
                    "scrub steps in slide 3/4 completed (at ${stale.map { it.completedMs }.take(10)} ms)",
            )
        }
    }

    @Test
    fun rapidArrowPressesSkipAheadInsteadOfQueueingAnimations() = runComposeUiTest {
        mainClock.autoAdvance = false
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            val deck = remember { newFrozenDeck() }
            Box(Modifier.size(320.dp, 180.dp)) {
                animatic = rememberAnimatic(storyboard = { deck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(300)
        val segment = indexFoundationTimelineSnapshot().stateSpans.first { !it.isHold && it.durationMs > 0 }.durationMs
        runOnIdle { scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        mainClock.advanceTimeBy(100)
        runOnIdle { scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        mainClock.advanceTimeBy(segment.toLong() + 200)
        runOnIdle {
            assertEquals(Storyboard.Index(0, 2), animatic.currentIndex)
            assertEquals(animatic.activeFromStateIndex, animatic.activeToStateIndex, "Settled, not still animating a queued press")
        }
    }

    @Test
    fun settledFramesAfterArrowKeyNavigationForwardAndBackMatchAFreshJump() = runComposeUiTest {
        lateinit var pathAnimatic: Animatic
        lateinit var refAnimatic: Animatic
        lateinit var scope: CoroutineScope
        var refGeneration by mutableIntStateOf(0)
        var refIndex by mutableStateOf(Storyboard.Index(0, 0))

        setContent {
            scope = rememberCoroutineScope()
            val pathDeck = remember { newFrozenDeck() }
            Row {
                Box(Modifier.size(480.dp, 270.dp).testTag("path")) {
                    pathAnimatic = rememberAnimatic(storyboard = { pathDeck })
                    Easel(pathAnimatic)
                }
                key(refGeneration) {
                    val refDeck = remember { newFrozenDeck() }
                    Box(Modifier.size(480.dp, 270.dp).testTag("ref")) {
                        refAnimatic = rememberAnimatic(initialIndex = refIndex, storyboard = { refDeck })
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
            runOnIdle { scope.launch { advanced = pathAnimatic.advance(AdvanceDirection.Forward) } }
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            if (!advanced) break
            compareAt("fwd${steps++}")
        }
        steps = 0
        while (true) {
            var advanced = false
            runOnIdle { scope.launch { advanced = pathAnimatic.advance(AdvanceDirection.Backward) } }
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            if (!advanced) break
            compareAt("back${steps++}")
        }
        if (failures.isNotEmpty()) fail("Settled navigation frames diverge:\n" + failures.joinToString("\n"))
    }

    @Test
    fun multiBeatShaderWarpAndMagicTextScrubMatchesFreshJumpAcrossBoundaryAndMidpoints() = runComposeUiTest {
        mainClock.autoAdvance = false
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

        val scrubSpecimens: DeckSpecimenRegistry = deckSpecimens {
            specimen("test.colorEvolutionShader") {
                val stage = float("uStage", default = 0f, range = 0f..3f)
                content {
                    DeckSkslShader(
                        id = "scrub:s2:shader",
                        sksl = colorEvolutionSksl,
                        label = "Live Color Evolution Preview",
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        float("uStage", stage())
                    }
                }
            }
        }

        val baseDoc = loadFoundationTestDocument()
        val scrubDoc: DeckDocument = baseDoc.copy(
            id = "scrub-sync-fixture",
            title = "Scrub Sync Fixture",
            slides = listOf(
                SlideSpec(
                    id = "intro",
                    title = "Intro",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "scrub:s1:title",
                            frame = FrameSpec(x = 128.0, y = 128.0, w = 1200.0, h = 120.0),
                            text = TextPayload(value = RichTextValue.Plain("Opening Slide"), style = "title", color = "foreground"),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "color-uv",
                    title = "Color Evolution to UV",
                    beats = listOf(BeatSpec(), BeatSpec(), BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "col",
                            frame = FrameSpec(x = 0.0, y = 0.0, w = 1920.0, h = 1080.0),
                            column = ContainerPayload(
                                gap = 32.0,
                                padding = PaddingSpec(left = 96.0, top = 96.0, right = 96.0, bottom = 96.0),
                                children = listOf(
                                    NodeSpec(
                                        id = "scrub:s2:caption",
                                        text = TextPayload(
                                            value = RichTextValue.Plain("Channels are 0.0..1.0 floats — 0.0 is transparent"),
                                            style = "heading",
                                            color = "foreground",
                                        ),
                                        builds = listOf(
                                            Build.Change(beat = 1, set = buildJsonObject { putJsonObject("text") { put("value", JsonPrimitive("Set RGBA to 1.0 for opaque solid white")) } }),
                                            Build.Change(beat = 2, set = buildJsonObject { putJsonObject("text") { put("value", JsonPrimitive("Keep red at 1.0 to isolate a single channel")) } }),
                                            Build.Change(beat = 3, set = buildJsonObject { putJsonObject("text") { put("value", JsonPrimitive("Normalize by iResolution for a smooth UV gradient")) } }),
                                        ),
                                    ),
                                    NodeSpec(
                                        id = "scrub:s2:code",
                                        code = CodePayload(
                                            value = "half4 main(float2 fragCoord) {\n    return half4(0.0);\n}",
                                            language = "agsl",
                                        ),
                                        builds = listOf(
                                            Build.Change(beat = 1, set = buildJsonObject { putJsonObject("code") { put("value", JsonPrimitive("half4 main(float2 fragCoord) {\n    return half4(1.0, 1.0, 1.0, 1.0);\n}")) } }),
                                            Build.Change(beat = 2, set = buildJsonObject { putJsonObject("code") { put("value", JsonPrimitive("half4 main(float2 fragCoord) {\n    return half4(1.0, 0.0, 0.0, 1.0);\n}")) } }),
                                            Build.Change(beat = 3, set = buildJsonObject { putJsonObject("code") { put("value", JsonPrimitive("uniform float2 iResolution;\n\nhalf4 main(float2 fragCoord) {\n    float2 uv = fragCoord.xy / iResolution.xy;\n    return half4(uv.x, uv.y, 1.0, 1.0);\n}")) } }),
                                        ),
                                    ),
                                    NodeSpec(
                                        id = "scrub:s2:shader-node",
                                        frame = FrameSpec(w = 320.0, h = 320.0),
                                        specimen = SpecimenPayload(
                                            ref = "test.colorEvolutionShader",
                                            params = mapOf("uStage" to JsonPrimitive(0.0)),
                                        ),
                                        builds = listOf(
                                            Build.Change(beat = 1, set = buildJsonObject { putJsonObject("specimen") { putJsonObject("params") { put("uStage", JsonPrimitive(1.0)) } } }),
                                            Build.Change(beat = 2, set = buildJsonObject { putJsonObject("specimen") { putJsonObject("params") { put("uStage", JsonPrimitive(2.0)) } } }),
                                            Build.Change(beat = 3, set = buildJsonObject { putJsonObject("specimen") { putJsonObject("params") { put("uStage", JsonPrimitive(3.0)) } } }),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "next-slide",
                    title = "Compose Host",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "scrub:s3:title",
                            frame = FrameSpec(x = 128.0, y = 128.0, w = 1200.0, h = 120.0),
                            text = TextPayload(value = RichTextValue.Plain("RuntimeShader Host"), style = "title", color = "foreground"),
                        ),
                    ),
                ),
            ),
        )

        val snapshot = DocumentTimelinePlannerCache().buildSnapshot(
            document = scrubDoc,
            themeName = "light",
            specimens = scrubSpecimens,
        ).snapshot

        val pathRegistry = StageInspectionRegistry()
        val refRegistry = StageInspectionRegistry()
        var pathUStage = -99f
        var refUStage = -99f
        var pathWarpSeg: Pair<Int, Int> = -1 to -1
        var refWarpSeg: Pair<Int, Int> = -1 to -1

        lateinit var pathAnimatic: Animatic
        lateinit var refAnimatic: Animatic
        lateinit var scope: CoroutineScope
        var refGen by mutableIntStateOf(0)
        var refInitialIndex by mutableStateOf(Storyboard.Index(1, 0))

        fun buildFixtureMount(decorator: ContentDecorator): Storyboard = buildDocumentStoryboard(
            session = RenderSession.forPresent(createLoadedTestDeckStore(scrubDoc), freeClockMillis = { 0L }),
            config = DocumentRenderConfig(
                themeName = "light",
                specimens = scrubSpecimens,
                stageDecorator = decorator,
            ),
        )

        setContent {
            scope = rememberCoroutineScope()
            val pathDeck = remember {
                buildFixtureMount(
                    ContentDecorator { content ->
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
                        ) { content() }
                    },
                )
            }
            Row {
                Box(Modifier.size(480.dp, 270.dp).testTag("s2-path")) {
                    pathAnimatic = rememberAnimatic(
                        initialIndex = Storyboard.Index(1, 3),
                        storyboard = { pathDeck },
                    )
                    Easel(pathAnimatic)
                }
                key(refGen) {
                    val refDeck = remember {
                        buildFixtureMount(
                            ContentDecorator { content ->
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
                                ) { content() }
                            },
                        )
                    }
                    Box(Modifier.size(480.dp, 270.dp).testTag("s2-ref")) {
                        refAnimatic = rememberAnimatic(
                            initialIndex = refInitialIndex,
                            storyboard = { refDeck },
                        )
                        Easel(refAnimatic)
                    }
                }
            }
        }
        repeat(6) { mainClock.advanceTimeByFrame() }

        fun scrubStep(timeMs: Int) {
            val (stateIdx, frac) = snapshot.stateFractionForTimeMs(timeMs)
            val inPlace = scrubStepIsInPlace(
                activeFrom = pathAnimatic.activeFromStateIndex,
                activeTo = pathAnimatic.activeToStateIndex,
                stateIndex = stateIdx,
                fraction = frac,
            )
            runOnUi(scope) { pathAnimatic.seekToSegment(stateIdx, frac, rebuildScene = !inPlace) }
            repeat(3) { mainClock.advanceTimeByFrame() }
        }

        fun settledIndexFor(stateIdx: Int): Storyboard.Index {
            val d = pathAnimatic.stateDescriptors
                .last { it.stateIndex <= stateIdx && !it.isBoundaryStart && !it.isBoundaryEnd }
            return Storyboard.Index(d.sceneIndex, d.frameIndex)
        }

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

            val d = diff(pathImg, refImg)
            if (d.differingPixels > d.total / 300) {
                dump("s2-$label-path", pathImg)
                dump("s2-$label-ref", refImg)
                fail(
                    "$label (t=${timeMs}ms, state=$stateIdx, frac=${"%.2f".format(frac)}): " +
                        "${d.differingPixels}/${d.total} px differ (max Δ=${"%.2f".format(d.maxChannelDelta)})",
                )
            }
        }

        val s2Bar = snapshot.slides.first { it.sceneIndex == 1 }
        val s2HoldSpans = snapshot.stateSpans.filter { it.sceneIndex == 1 && it.isHold }
        val s2AnimSpans = snapshot.stateSpans.filter { it.sceneIndex == 1 && !it.isHold && !it.isBoundary && it.durationMs > 40 }
        assertEquals(4, s2HoldSpans.size)
        assertEquals(3, s2AnimSpans.size)

        val b1HoldMs = s2HoldSpans[0].startMs + s2HoldSpans[0].durationMs / 2
        val b4HoldMs = s2HoldSpans[3].startMs + s2HoldSpans[3].durationMs / 2
        val b4ToS3BoundaryMs = s2Bar.endMs - 60
        val segB2ToB3 = s2AnimSpans[1]
        val b2b3At25Ms = segB2ToB3.startMs + (segB2ToB3.durationMs * 0.25f).toInt()
        val b2b3At75Ms = segB2ToB3.startMs + (segB2ToB3.durationMs * 0.75f).toInt()

        for (step in 0..16) {
            val t = b4ToS3BoundaryMs + ((b1HoldMs - b4ToS3BoundaryMs) * step) / 16
            scrubStep(t)
        }
        scrubStep(b1HoldMs)
        assertEquals(0f, pathUStage, 1e-2f)
        assertMatchesFreshReference("back-b4-to-b1-hold", b1HoldMs)

        for (t in listOf(
            s2AnimSpans[0].startMs + s2AnimSpans[0].durationMs / 2,
            s2HoldSpans[1].startMs + 20,
            b2b3At25Ms,
        )) {
            scrubStep(t)
        }
        assertMatchesFreshReference("fwd-b2-to-b3-at-25pct", b2b3At25Ms)

        scrubStep(b2b3At75Ms)
        assertMatchesFreshReference("fwd-b2-to-b3-at-75pct", b2b3At75Ms)

        scrubStep(b2b3At25Ms)
        assertMatchesFreshReference("rev-b2-to-b3-at-25pct", b2b3At25Ms)
        scrubStep(b2b3At75Ms)

        for (step in 1..10) {
            val t = b2b3At75Ms + ((b4ToS3BoundaryMs - b2b3At75Ms) * step) / 10
            scrubStep(t)
        }
        scrubStep(b4HoldMs)
        assertEquals(3f, pathUStage, 1e-2f)
        assertMatchesFreshReference("fwd-b1-to-b4-hold", b4HoldMs)

        scrubStep(b4ToS3BoundaryMs)
        assertMatchesFreshReference("fwd-s2b4-to-s3b1-boundary", b4ToS3BoundaryMs)
        mainClock.autoAdvance = true
    }
}
