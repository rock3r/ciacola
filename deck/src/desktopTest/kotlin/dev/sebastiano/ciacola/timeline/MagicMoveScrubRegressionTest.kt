package dev.sebastiano.ciacola.timeline

import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.toValue
import dev.sebastiano.ciacola.buildFoundationTestStoryboard
import dev.sebastiano.ciacola.indexFoundationTimelineSnapshot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Hard Probe Gate regression suite for no-rebuild `snapToResting` + `stableSegment()` +
 * `MagicText v2` / `DeckWarpCodeCard` / `animateFloat` scrub consistency across Rows D, E, G, H, J,
 * K, L, S, stationary-word luminance flatness, and persistent child transitions.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalTransitionApi::class)
class MagicMoveScrubRegressionTest {

    private fun frozenLoopDeck(): Storyboard = buildFoundationTestStoryboard(
        freeClockMillis = { 0L },
    )

    private fun ComposeUiTest.runOnUi(scope: CoroutineScope, block: suspend () -> Unit) {
        lateinit var job: Job
        runOnIdle { job = scope.launch { block() } }
        var guard = 0
        while (!job.isCompleted && guard++ < 2500) mainClock.advanceTimeByFrame()
        assertTrue(job.isCompleted, "UI coroutine did not complete within 2500 frames")
    }

    private fun ComposeUiTest.seek(scope: CoroutineScope, a: Animatic, seg: Int, f: Float, rebuild: Boolean) =
        runOnUi(scope) { a.seekToSegment(seg, f, rebuildScene = rebuild) }

    private fun ComposeUiTest.frames(n: Int) = repeat(n) { mainClock.advanceTimeByFrame() }

    private fun imageDiffPixels(a: ImageBitmap, b: ImageBitmap): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        var diff = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val ca = pa[x, y]
                val cb = pb[x, y]
                if (abs(ca.red - cb.red) > 0.03f ||
                    abs(ca.green - cb.green) > 0.03f ||
                    abs(ca.blue - cb.blue) > 0.03f
                ) {
                    diff++
                }
            }
        }
        return diff
    }

    private fun assertStageMatches(label: String, expected: ImageBitmap, actual: ImageBitmap, maxDiffDivisor: Int = 500) {
        val diffPx = imageDiffPixels(expected, actual)
        val maxAllowed = (expected.width * expected.height) / maxDiffDivisor
        assertTrue(
            diffPx <= maxAllowed,
            "$label: stage frame diverged from fresh reference ($diffPx px > $maxAllowed px)",
        )
    }

    @Test
    fun probeRowsDEGHJLSMatchFreshReference() = runComposeUiTest {
        mainClock.autoAdvance = false
        lateinit var a: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            val deck = remember { frozenLoopDeck() }
            Box(Modifier.size(640.dp, 360.dp).testTag("stage")) {
                a = rememberAnimatic(storyboard = { deck })
                Easel(a)
            }
        }
        mainClock.advanceTimeBy(500)

        fun capture(): ImageBitmap = onNodeWithTag("stage").captureToImage()

        val fs0 = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
        val freshB0 = run {
            seek(scope, a, 0, 0f, true); frames(3)
            capture()
        }
        val freshSeg0 = fs0.associateWith { f ->
            seek(scope, a, 0, f, true); frames(2)
            capture()
        }

        // --- Rows D & E: seg 0 forward -> back -> SnapCurrent hold b0 -> forward again ---
        seek(scope, a, 0, 0f, true); frames(3)
        for (f in fs0) seek(scope, a, 0, f, false)
        for (f in floatArrayOf(0.5f, 0.3f, 0.1f)) seek(scope, a, 0, f, false)
        seek(scope, a, 0, 0f, false); frames(3)
        assertStageMatches("Row D hold b0 after SnapCurrent", freshB0, capture())
        mainClock.advanceTimeBy(200)
        assertStageMatches("Row D hold b0 +200ms", freshB0, capture())
        for (f in fs0) {
            seek(scope, a, 0, f, false); frames(1)
            assertStageMatches("Row E seg0 fwd-again f=$f", freshSeg0.getValue(f), capture())
        }

        // --- Rows G & H: seg 1 forward -> back -> SnapCurrent hold b1 -> forward again ---
        val fs1 = floatArrayOf(0.15f, 0.3f, 0.5f, 0.7f, 0.85f)
        val freshB1 = run {
            seek(scope, a, 1, 0f, true); frames(3)
            capture()
        }
        val freshSeg1 = fs1.associateWith { f ->
            seek(scope, a, 1, f, true); frames(2)
            capture()
        }
        seek(scope, a, 1, 0f, true); frames(3)
        for (f in fs1) seek(scope, a, 1, f, false)
        seek(scope, a, 1, 0.15f, false)
        seek(scope, a, 1, 0f, false); frames(3)
        assertStageMatches("Row G hold b1 after SnapCurrent", freshB1, capture())
        for (f in fs1) {
            seek(scope, a, 1, f, false); frames(1)
            assertStageMatches("Row H seg1 fwd-again f=$f", freshSeg1.getValue(f), capture())
        }

        // --- Row J: seg1@0.3 -> hold b0 -> seg0@0.5 with gap = 0..2 frames ---
        for (gap in 0..2) {
            seek(scope, a, 1, 0f, true); frames(3)
            seek(scope, a, 1, 0.3f, false)
            runOnUi(scope) {
                a.seekToSegment(0, 0f, rebuildScene = false)
                repeat(gap) { withFrameNanos { } }
                a.seekToSegment(0, 0.5f, rebuildScene = false)
            }
            frames(2)
            assertStageMatches("Row J gap=$gap seg0@0.5", freshSeg0.getValue(0.5f), capture())
        }

        // --- Row L: forward through hold b1 into seg1, back into seg0, SnapCurrent b0, forward ---
        seek(scope, a, 0, 0f, true); frames(3)
        seek(scope, a, 0, 0.5f, false)
        seek(scope, a, 0, 0.95f, false)
        seek(scope, a, 1, 0f, false); frames(2)
        seek(scope, a, 1, 0.3f, false)
        seek(scope, a, 0, 0.5f, false); frames(2)
        assertStageMatches("Row L back into seg0@0.5", freshSeg0.getValue(0.5f), capture())
        seek(scope, a, 0, 0.2f, false); frames(1)
        seek(scope, a, 0, 0f, false); frames(3)
        assertStageMatches("Row L hold b0 via SnapCurrent", freshB0, capture())
        seek(scope, a, 0, 0.5f, false); frames(2)
        assertStageMatches("Row L seg0@0.5 after SnapCurrent", freshSeg0.getValue(0.5f), capture())

        // --- Row S: Slide 2 card expand (seg 5) after SnapCurrent ---
        val freshS1B0 = run {
            seek(scope, a, 5, 0f, true); frames(3)
            capture()
        }
        val freshSeg5Mid = run {
            seek(scope, a, 5, 0.5f, true); frames(2)
            capture()
        }
        seek(scope, a, 5, 0f, true); frames(3)
        seek(scope, a, 5, 0.5f, false); frames(1)
        seek(scope, a, 5, 0f, false); frames(3)
        assertStageMatches("Row S hold s1b0 after SnapCurrent", freshS1B0, capture())
        seek(scope, a, 5, 0.5f, false); frames(2)
        assertStageMatches("Row S seg5@0.5 after SnapCurrent", freshSeg5Mid, capture())
    }

    @Test
    fun probeRowKLiveArrowKeysForwardReverseForwardMatchesCleanReference() = runComposeUiTest {
        mainClock.autoAdvance = false
        lateinit var a: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            val deck = remember { frozenLoopDeck() }
            Box(Modifier.size(640.dp, 360.dp).testTag("stage")) {
                a = rememberAnimatic(storyboard = { deck })
                Easel(a)
            }
        }
        mainClock.advanceTimeBy(500)

        data class FrameSample(val fraction: Float, val image: ImageBitmap)

        seek(scope, a, 0, 0f, true); frames(5)
        val freshB0 = onNodeWithTag("stage").captureToImage()
        val refSamples = mutableListOf<FrameSample>()
        lateinit var job: Job
        runOnIdle { job = scope.launch { a.advance(AdvanceDirection.Forward) } }
        var guard = 0
        while (!job.isCompleted && guard++ < 600) {
            mainClock.advanceTimeByFrame()
            if (!a.scrubSettling && a.activeFromStateIndex == 0 && a.activeToStateIndex == 1 && a.activeFraction > 0f) {
                refSamples += FrameSample(a.activeFraction, onNodeWithTag("stage").captureToImage())
            }
        }
        assertTrue(refSamples.size >= 15, "Expected at least 15 live reference frames, got ${refSamples.size}")

        // Live forward (18 frames) -> reverse to settle -> forward again.
        seek(scope, a, 0, 0f, true); frames(5)
        runOnIdle { job = scope.launch { a.advance(AdvanceDirection.Forward) } }
        var n = 0
        while (!job.isCompleted && n < 18) {
            mainClock.advanceTimeByFrame()
            n++
        }
        runOnIdle { job = scope.launch { a.advance(AdvanceDirection.Backward) } }
        guard = 0
        while (!job.isCompleted && guard++ < 600) mainClock.advanceTimeByFrame()
        assertTrue(job.isCompleted, "Reverse advance did not complete")
        frames(20)
        assertStageMatches("Row K hold b0 after live reverse", freshB0, onNodeWithTag("stage").captureToImage())

        val replaySamples = mutableListOf<FrameSample>()
        runOnIdle { job = scope.launch { a.advance(AdvanceDirection.Forward) } }
        guard = 0
        while (!job.isCompleted && guard++ < 600) {
            mainClock.advanceTimeByFrame()
            if (!a.scrubSettling && a.activeFromStateIndex == 0 && a.activeToStateIndex == 1 && a.activeFraction > 0f) {
                replaySamples += FrameSample(a.activeFraction, onNodeWithTag("stage").captureToImage())
            }
        }
        assertTrue(job.isCompleted, "Second forward advance did not complete")
        assertTrue(replaySamples.size >= 15, "Expected at least 15 replay frames, got ${replaySamples.size}")

        for (idx in replaySamples.indices step 4) {
            val sample = replaySamples[idx]
            val closestRef = refSamples.minBy { abs(it.fraction - sample.fraction) }
            val fracGap = abs(closestRef.fraction - sample.fraction)
            assertTrue(fracGap < 0.03f, "No matching reference fraction near ${sample.fraction} (closest gap=$fracGap)")
            assertStageMatches(
                label = "Row K fwd-again f=${"%.3f".format(sample.fraction)} vs ref f=${"%.3f".format(closestRef.fraction)}",
                expected = closestRef.image,
                actual = sample.image,
                maxDiffDivisor = 250,
            )
        }
    }

    @Test
    fun stationaryWordsLuminanceStaysFlatWithinOnePercentAndWarpCardMatchesFresh() = runComposeUiTest {
        mainClock.autoAdvance = false
        val snapshot = indexFoundationTimelineSnapshot()
        lateinit var a: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            val deck = remember { frozenLoopDeck() }
            Box(Modifier.size(1920.dp, 1080.dp).testTag("stage")) {
                a = rememberAnimatic(storyboard = { deck })
                Easel(a)
            }
        }
        mainClock.advanceTimeBy(500)

        // Locate stationary "A scene" bounding box on s1:prose via GetTextLayoutResult semantics
        seek(scope, a, 0, 0f, true); frames(3)
        val proseNode = onNodeWithTag("doc-node-s1:prose").fetchSemanticsNode()
        val proseRootBounds = proseNode.boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        proseNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val tlr = layouts.single()
        // Note: prose is inside 960x540 scaled stage (scale = 0.5 relative to 1920x1080 layout coords if scaled,
        // so scale layout box by proseRootBounds.width / tlr.size.width).
        val sx = proseRootBounds.width / tlr.size.width.coerceAtLeast(1)
        val sy = proseRootBounds.height / tlr.size.height.coerceAtLeast(1)
        val firstBox = tlr.getBoundingBox(0)
        val lastBox = tlr.getBoundingBox(6) // "A scene"
        val cropLeft = floor(proseRootBounds.left + firstBox.left * sx).toInt()
        val cropTop = floor(proseRootBounds.top + minOf(firstBox.top, lastBox.top) * sy).toInt()
        val cropRight = ceil(proseRootBounds.left + lastBox.right * sx).toInt()
        val cropBottom = ceil(proseRootBounds.top + maxOf(firstBox.bottom, lastBox.bottom) * sy).toInt()

        fun meanLuminance(): Double {
            val pm = onNodeWithTag("stage").captureToImage().toPixelMap()
            val x0 = cropLeft.coerceIn(0, pm.width - 1)
            val y0 = cropTop.coerceIn(0, pm.height - 1)
            val x1 = cropRight.coerceIn(x0 + 1, pm.width)
            val y1 = cropBottom.coerceIn(y0 + 1, pm.height)
            var sum = 0.0
            var count = 0
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val c = pm[x, y]
                    sum += 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue
                    count++
                }
            }
            return sum / count.coerceAtLeast(1)
        }

        val restLum = meanLuminance()
        assertTrue(restLum > 0.01, "Expected non-zero resting luminance for 'A scene', got $restLum")
        for (f in floatArrayOf(0.02f, 0.50f, 0.97f)) {
            seek(scope, a, 0, f, true); frames(2)
            val lum = meanLuminance()
            val absDelta = abs(lum - restLum)
            assertTrue(
                absDelta <= 0.01,
                "Stationary 'A scene' luminance drifted by ${"%.2f".format(absDelta * 100)}% at f=$f (rest=$restLum, now=$lum)",
            )
        }

        // --- WarpCodeCard probe after SnapCurrent ---
        val labSegs = snapshot.stateSpans
            .filter { !it.isHold && it.durationMs > 0 && a.stateDescriptors[it.stateIndex].sceneIndex == 4 }
            .map { it.stateIndex }
        assertTrue(labSegs.isNotEmpty(), "Expected active warp segments in scene 4")
        val warpSeg = labSegs.last()
        val warpFractions = floatArrayOf(0.15f, 0.30f, 0.50f)
        val freshWarp = warpFractions.associateWith { f ->
            seek(scope, a, warpSeg, f, true); frames(2)
            onNodeWithTag("stage").captureToImage()
        }
        seek(scope, a, warpSeg, 0f, true); frames(3)
        seek(scope, a, warpSeg, 0.5f, false); frames(1)
        seek(scope, a, warpSeg, 0f, false); frames(3)
        for (f in warpFractions) {
            seek(scope, a, warpSeg, f, false); frames(2)
            assertStageMatches("WarpCodeCard seg=$warpSeg f=$f", freshWarp.getValue(f), onNodeWithTag("stage").captureToImage())
        }
    }

    @Test
    fun backwardScrubShaderLikeAnimateFloatAndPersistentChildAcrossThirdStateSnap() = runComposeUiTest {
        mainClock.autoAdvance = false
        var observedShaderValue = -1f
        var observedChildValue = -1f

        val storyboard = Storyboard.build(title = "AnimateFloatScrubProbes") {
            scene(frameCount = 4) {
                val shaderFloat by transition.animateFloat(
                    transitionSpec = { tween(durationMillis = 400) },
                    label = "shader-uniform",
                ) { frame ->
                    frame.toValue().toFloat()
                }
                observedShaderValue = shaderFloat
                Box(Modifier.fillMaxSize())
            }
            scene(frameCount = 3) {
                val childTransition = transition.createChildTransition(label = "persistent-child") { frame ->
                    frame.toValue()
                }
                val childFloat by childTransition.animateFloat(
                    transitionSpec = { tween(durationMillis = 400) },
                    label = "persistent-child-float",
                ) { state ->
                    state * 10f
                }
                observedChildValue = childFloat
                Box(Modifier.fillMaxSize())
            }
        }

        lateinit var a: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(320.dp, 180.dp).testTag("stage")) {
                a = rememberAnimatic(storyboard = { storyboard })
                Easel(a)
            }
        }
        mainClock.advanceTimeBy(300)

        val s0Segs = a.stateDescriptors.filter { it.sceneIndex == 0 && !it.isBoundaryStart && !it.isBoundaryEnd }.map { it.stateIndex }
        assertEquals(4, s0Segs.size)
        val freshMid = run {
            seek(scope, a, s0Segs[0], 0.5f, true); frames(2)
            observedShaderValue
        }
        assertTrue(freshMid in 0.2f..0.8f, "Expected mid shader float in (0.2, 0.8), got $freshMid")

        seek(scope, a, s0Segs[0], 0f, true); frames(3)
        seek(scope, a, s0Segs[0], 0.5f, false); frames(1)
        seek(scope, a, s0Segs[1], 0.5f, false); frames(1)
        seek(scope, a, s0Segs[2], 0.5f, false); frames(1)
        seek(scope, a, s0Segs[3], 0f, false); frames(3)
        assertEquals(3f, observedShaderValue, 1e-3f, "Shader float at B4 rest must be 3.0")

        seek(scope, a, s0Segs[2], 0.5f, false); frames(1)
        seek(scope, a, s0Segs[1], 0.5f, false); frames(1)
        seek(scope, a, s0Segs[0], 0.5f, false); frames(1)
        seek(scope, a, s0Segs[0], 0f, false); frames(3)
        assertEquals(0f, observedShaderValue, 1e-3f, "Shader float at B1 rest after backward scrub must be 0.0")

        seek(scope, a, s0Segs[0], 0.5f, false); frames(2)
        assertEquals(
            freshMid,
            observedShaderValue,
            1e-2f,
            "Shader float at B1->B2@0.5 after backward scrub must match fresh ($freshMid vs $observedShaderValue)",
        )

        val s1Segs = a.stateDescriptors.filter { it.sceneIndex == 1 && !it.isBoundaryStart && !it.isBoundaryEnd }.map { it.stateIndex }
        assertEquals(3, s1Segs.size)

        val freshS1Mid12 = run {
            seek(scope, a, s1Segs[1], 0.5f, true); frames(2)
            observedChildValue
        }
        assertTrue(freshS1Mid12 in 12f..18f, "Expected fresh state 1->2@0.5 in 12..18, got $freshS1Mid12")

        seek(scope, a, s1Segs[0], 0f, true); frames(3)
        assertEquals(0f, observedChildValue, 1e-3f)
        seek(scope, a, s1Segs[0], 0.5f, false); frames(2)
        assertTrue(observedChildValue in 2f..8f, "Expected mid 0->1 child float in 2..8, got $observedChildValue")

        seek(scope, a, s1Segs[2], 0f, false); frames(3)
        assertEquals(
            20f,
            observedChildValue,
            1e-3f,
            "Persistent child animateFloat must settle cleanly to state 2 (20.0) after mid-seek third-state snap",
        )

        seek(scope, a, s1Segs[1], 0.5f, false); frames(2)
        assertEquals(
            freshS1Mid12,
            observedChildValue,
            1e-2f,
            "Persistent child animateFloat after third-state snap must match fresh at 1->2@0.5",
        )
    }
}
