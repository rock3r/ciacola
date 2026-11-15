package dev.sebastiano.ciacola

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.LocalDeckLoopClock
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Verifies RC5 cross-slide coral badge -> card hand-off in both directions (`S1 -> S2` and
 * `S2 -> S1`), eyebrow dissolve-through non-overlap (`alpha1 * alpha2 < 0.05`), and zero
 * stray coral dot when scrubbing back to `S1:B2` (beat index 1).
 */
@OptIn(ExperimentalTestApi::class)
class FoundationHandOffTest {

    private data class CoralRegionStats(
        val pixelCount: Int,
        val componentCount: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val centerRed: Float,
    )

    private fun Color.isCoralAccent(): Boolean =
        red > 0.72f && green in 0.25f..0.60f && blue in 0.15f..0.52f && (red - blue) > 0.28f

    private fun analyzeCoral(image: ImageBitmap): CoralRegionStats {
        val pm = image.toPixelMap()
        val w = pm.width
        val h = pm.height
        val mask = BooleanArray(w * h)
        var count = 0
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (pm[x, y].isCoralAccent()) {
                    mask[y * w + x] = true
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (count == 0) {
            return CoralRegionStats(0, 0, 0, 0, 0, 0, 0f)
        }
        // Collect 4-connected components with >= 25 pixels and count distinct silhouettes
        // (excluding interior glyph counters like the enclosed holes of 'a', 'e', 'd' in "shared"
        // that lie strictly inside the main coral card's bounding box).
        data class Comp(val size: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val comps = mutableListOf<Comp>()
        for (i in mask.indices) {
            if (!mask[i] || visited[i]) continue
            var head = 0
            var tail = 0
            queue[tail++] = i
            visited[i] = true
            var compSize = 0
            var cMinX = w
            var cMaxX = -1
            var cMinY = h
            var cMaxY = -1
            while (head < tail) {
                val cur = queue[head++]
                compSize++
                val cx = cur % w
                val cy = cur / w
                if (cx < cMinX) cMinX = cx
                if (cx > cMaxX) cMaxX = cx
                if (cy < cMinY) cMinY = cy
                if (cy > cMaxY) cMaxY = cy
                if (cx > 0 && mask[cur - 1] && !visited[cur - 1]) { visited[cur - 1] = true; queue[tail++] = cur - 1 }
                if (cx + 1 < w && mask[cur + 1] && !visited[cur + 1]) { visited[cur + 1] = true; queue[tail++] = cur + 1 }
                if (cy > 0 && mask[cur - w] && !visited[cur - w]) { visited[cur - w] = true; queue[tail++] = cur - w }
                if (cy + 1 < h && mask[cur + w] && !visited[cur + w]) { visited[cur + w] = true; queue[tail++] = cur + w }
            }
            if (compSize >= 25) comps += Comp(compSize, cMinX, cMaxX, cMinY, cMaxY)
        }
        val mainComp = comps.maxByOrNull { it.size }
        val silhouettes = if (mainComp == null) {
            0
        } else {
            1 + comps.count { c ->
                c !== mainComp &&
                    !(c.minX >= mainComp.minX && c.maxX <= mainComp.maxX && c.minY >= mainComp.minY && c.maxY <= mainComp.maxY)
            }
        }
        val midX = (minX + maxX) / 2
        val midY = (minY + maxY) / 2
        return CoralRegionStats(
            pixelCount = count,
            componentCount = silhouettes,
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            centerRed = pm[midX, midY].red,
        )
    }

    private fun ComposeUiTest.runOnUi(scope: CoroutineScope, block: suspend () -> Unit) {
        lateinit var job: Job
        runOnIdle { job = scope.launch { block() } }
        var guard = 0
        while (!job.isCompleted && guard++ < 1500) mainClock.advanceTimeByFrame()
        assertTrue(job.isCompleted, "UI coroutine did not complete")
    }

    @Test
    fun forwardAndBackwardHandOffAreSingleSilhouetteWithCleanEyebrowDissolveAndNoStrayDot() = runComposeUiTest {
        mainClock.autoAdvance = false
        val frozenClock = DeckLoopClock().apply { scrubbedMillis = 0L }
        val registry = StageInspectionRegistry()
        val deck = createFoundationDeck(
            includeKeynoteEffectsLab = false,
            stageDecorator = ContentDecorator { content ->
                CompositionLocalProvider(
                    LocalDeckLoopClock provides frozenClock,
                    LocalStageInspectionRegistry provides registry,
                ) {
                    content()
                }
            },
        )
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(960.dp, 540.dp).testTag("stage")) {
                animatic = rememberAnimatic(storyboard = { deck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(400)

        // Locate segment indices dynamically:
        // seg 1 = S1:B1 -> S1:B2 (beat index 1), seg 3 = S1:End -> S2:Start (cross-slide hand-off),
        // seg 5 = S2:B0 -> S2:B1.
        val handOffSeg = animatic.stateDescriptors.indexOfFirst { it.sceneIndex == 0 && it.isBoundaryEnd }
        assertTrue(handOffSeg >= 0, "Could not locate S1:End boundary state")
        val s1Beat1Idx = animatic.stateDescriptors.indexOfFirst { it.sceneIndex == 0 && it.frameIndex == 1 }
        val s2Beat0Idx = animatic.stateDescriptors.indexOfFirst { it.sceneIndex == 1 && it.frameIndex == 0 }
        assertTrue(s1Beat1Idx >= 0 && s2Beat0Idx >= 0)

        // 1. Forward S1 -> S2 scrub across hand-off: single solid coral silhouette + eyebrow overlap < 0.05.
        for (f in floatArrayOf(0.10f, 0.25f, 0.40f, 0.50f, 0.75f)) {
            runOnUi(scope) { animatic.seekToSegment(handOffSeg, f, rebuildScene = true) }
            repeat(2) { mainClock.advanceTimeByFrame() }
            val stats = analyzeCoral(onNodeWithTag("stage").captureToImage())
            assertEquals(
                1,
                stats.componentCount,
                "Forward S1->S2 hand-off at f=$f must render 1 coral silhouette, got ${stats.componentCount} (pixels=${stats.pixelCount})",
            )
            assertTrue(
                stats.centerRed > 0.75f,
                "Forward S1->S2 coral interior at f=$f must remain solid (centerRed=${stats.centerRed})",
            )
            val a1 = registry.effectiveAlphaOf("s1:eyebrow")
            val a2 = registry.effectiveAlphaOf("s2:eyebrow")
            assertTrue(
                a1 * a2 < 0.05f,
                "Eyebrow overlap alpha product must stay < 0.05 at forward f=$f (a1=$a1, a2=$a2, product=${a1 * a2})",
            )
        }

        // Verify corner radius continuity on Slide 2 card at rest (s2Beat0Idx):
        // card is 240dp with cornerRadius = 10% = 24dp -> corner pixel is background, not coral,
        // while interior pixel is solid coral (not a sharp 8dp corner nor a circle).
        runOnUi(scope) { animatic.seekToSegment(s2Beat0Idx, 0f, rebuildScene = true) }
        repeat(4) { mainClock.advanceTimeByFrame() }
        val s2RestImg = onNodeWithTag("stage").captureToImage()
        val s2Stats = analyzeCoral(s2RestImg)
        assertEquals(1, s2Stats.componentCount, "S2:B0 must show 1 solid coral card")
        val s2Pm = s2RestImg.toPixelMap()
        // Outer corner (minX+1, minY+1) is rounded off (not coral); 10% diagonal inset IS coral.
        assertTrue(!s2Pm[s2Stats.minX + 1, s2Stats.minY + 1].isCoralAccent(), "S2 card outer corner must be rounded")
        val cardW = (s2Stats.maxX - s2Stats.minX).coerceAtLeast(1)
        val inset = (cardW * 0.12f).toInt().coerceAtLeast(4)
        assertTrue(s2Pm[s2Stats.minX + inset, s2Stats.minY + inset].isCoralAccent(), "S2 card 12% inset must be solid coral")

        // 2. Live BACKWARD S2:B0 -> S1:B3 hand-off: single coral silhouette + eyebrow overlap < 0.05.
        runOnUi(scope) { animatic.seekToSegment(s2Beat0Idx, 0f, rebuildScene = true) }
        repeat(6) { mainClock.advanceTimeByFrame() }
        lateinit var backJob: Job
        runOnIdle { backJob = scope.launch { animatic.advance(AdvanceDirection.Backward) } }
        var observedBackMid = 0
        var guard = 0
        while (!backJob.isCompleted && guard++ < 400) {
            mainClock.advanceTimeByFrame()
            if (!animatic.scrubSettling &&
                animatic.activeFromStateIndex == handOffSeg + 1 &&
                animatic.activeToStateIndex == handOffSeg &&
                animatic.activeFraction in 0.15f..0.85f &&
                guard % 4 == 0
            ) {
                observedBackMid++
                val stats = analyzeCoral(onNodeWithTag("stage").captureToImage())
                assertEquals(
                    1,
                    stats.componentCount,
                    "Backward S2->S1 live hand-off at f=${animatic.activeFraction} must render 1 coral silhouette, got ${stats.componentCount}",
                )
                assertTrue(
                    stats.centerRed > 0.75f,
                    "Backward S2->S1 coral interior must remain solid (centerRed=${stats.centerRed})",
                )
                val a1 = registry.effectiveAlphaOf("s1:eyebrow")
                val a2 = registry.effectiveAlphaOf("s2:eyebrow")
                assertTrue(
                    a1 * a2 < 0.05f,
                    "Eyebrow overlap alpha product must stay < 0.05 during backward hand-off (a1=$a1, a2=$a2)",
                )
            }
        }
        assertTrue(backJob.isCompleted, "Live backward S2->S1 advance did not complete")
        assertTrue(observedBackMid >= 2, "Expected to inspect at least 2 mid-flight backward hand-off frames, saw $observedBackMid")

        // 3. Scrubbing back from S2 / S1:B3 to S1:B2 (beat index 1) must leave ZERO stray coral dot.
        runOnUi(scope) { animatic.seekToSegment(s1Beat1Idx, 0f, rebuildScene = false) }
        repeat(4) { mainClock.advanceTimeByFrame() }
        val s1B2Stats = analyzeCoral(onNodeWithTag("stage").captureToImage())
        assertEquals(
            0,
            s1B2Stats.pixelCount,
            "S1:B2 (beat index 1) must have zero coral badge pixels after scrubbing back from S1:B3/S2, found ${s1B2Stats.pixelCount}",
        )

        // And scrubbing forward to S1:B2 -> S1:B3 mid-fade (seg 1 @ 0.5f) should not jump full-alpha prematurely.
        runOnUi(scope) { animatic.seekToSegment(s1Beat1Idx, 0.5f, rebuildScene = false) }
        repeat(2) { mainClock.advanceTimeByFrame() }
        runOnUi(scope) { animatic.seekToSegment(s1Beat1Idx, 0f, rebuildScene = false) }
        repeat(4) { mainClock.advanceTimeByFrame() }
        val afterSnapBackStats = analyzeCoral(onNodeWithTag("stage").captureToImage())
        assertEquals(
            0,
            afterSnapBackStats.pixelCount,
            "S1:B2 must remain completely free of stray coral pixels after SnapCurrent from seg1@0.5",
        )
    }
}
