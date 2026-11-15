package dev.bnorm.storyboard.easel

import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.OverviewVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class, ExperimentalTransitionApi::class)
class StableSegmentTest {

    @Test
    fun stableSegmentTracksRootTransitionAndCollapsesOnSnapToCurrent() = runComposeUiTest {
        mainClock.autoAdvance = false
        val seekable = SeekableTransitionState(0)
        lateinit var scope: CoroutineScope

        var rootActive = true
        var childActive = true
        var grandchildActive = true
        var rootSeg: Pair<Int, Int> = -1 to -1
        var childSeg: Pair<Int, Int> = -1 to -1
        var grandchildSeg: Pair<String, String> = "" to ""

        setContent {
            scope = rememberCoroutineScope()
            val root = rememberTransition(seekable, label = "root")
            val child = root.createChildTransition("child") { it * 10 }
            val grandchild = child.createChildTransition("grandchild") { "v$it" }
            val rActive = root.isSegmentActive
            val cActive = child.isSegmentActive
            val gActive = grandchild.isSegmentActive
            val rSeg = root.stableSegment()
            val cSeg = child.stableSegment()
            val gSeg = grandchild.stableSegment()
            SideEffect {
                rootActive = rActive
                childActive = cActive
                grandchildActive = gActive
                rootSeg = rSeg
                childSeg = cSeg
                grandchildSeg = gSeg
            }
            Text("${grandchild.targetState}")
        }

        fun runSuspend(block: suspend () -> Unit) {
            lateinit var job: Job
            runOnIdle { job = scope.launch { block() } }
            var guard = 0
            while (!job.isCompleted && guard++ < 200) mainClock.advanceTimeByFrame()
            assertTrue(job.isCompleted, "Coroutine did not complete")
        }

        mainClock.advanceTimeByFrame()
        assertFalse(rootActive)
        assertFalse(childActive)
        assertFalse(grandchildActive)
        assertEquals(0 to 0, rootSeg)
        assertEquals(0 to 0, childSeg)
        assertEquals("v0" to "v0", grandchildSeg)

        // Actively seeking 0 -> 1: root and all descendants report active segment.
        runSuspend { seekable.seekTo(0.5f, 1) }
        repeat(2) { mainClock.advanceTimeByFrame() }
        assertTrue(rootActive)
        assertTrue(childActive)
        assertTrue(grandchildActive)
        assertEquals(0 to 1, rootSeg)
        assertEquals(0 to 10, childSeg)
        assertEquals("v0" to "v10", grandchildSeg)

        // Snap back to currentState (0): even if child's raw currentState/targetState lags or
        // inverts internally on snapTo(currentState), stableSegment() must immediately collapse
        // to (0, 0) for root, child, and grandchild.
        runSuspend { seekable.snapTo(0) }
        repeat(2) { mainClock.advanceTimeByFrame() }
        assertFalse(rootActive)
        assertFalse(childActive)
        assertFalse(grandchildActive)
        assertEquals(0 to 0, rootSeg)
        assertEquals(0 to 0, childSeg)
        assertEquals("v0" to "v0", grandchildSeg)
    }

    @Test
    fun storyNavigationIgnoresNavigationKeysWhileTextInputIsActive() = runComposeUiTest {
        val deck = Storyboard.build(title = "TextGuard") {
            scene(frames = listOf("a", "b", "c")) { Text("Scene 0") }
            scene(frames = listOf("d")) { Text("Scene 1") }
        }
        var editingText by mutableStateOf(true)
        lateinit var animatic: Animatic

        setContent {
            animatic = rememberAnimatic(storyboard = { deck })
            Box(
                Modifier
                    .size(400.dp, 240.dp)
                    .onStoryNavigation(animatic, isTextInputActive = { editingText }),
            ) {
                Easel(animatic)
            }
        }
        waitForIdle()

        val blockedKeys = listOf(
            Key.Spacebar,
            Key.DirectionRight,
            Key.DirectionLeft,
            Key.Enter,
            Key.Backspace,
            Key.PageDown,
            Key.PageUp,
            Key.DirectionDown,
            Key.DirectionUp,
        )
        for (key in blockedKeys) {
            onRoot().performKeyInput { pressKey(key) }
            waitForIdle()
            assertEquals(
                Storyboard.Index(0, 0),
                animatic.currentIndex,
                "Expected $key to be ignored while isTextInputActive() is true",
            )
        }

        // Turning off text input restores story navigation immediately.
        editingText = false
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitUntil(conditionDescription = "DirectionRight advances once text input ends", timeoutMillis = 5_000) {
            animatic.currentIndex == Storyboard.Index(0, 1)
        }
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        waitUntil(conditionDescription = "Spacebar advances once text input ends", timeoutMillis = 5_000) {
            animatic.currentIndex == Storyboard.Index(0, 2)
        }
    }

    @Test
    fun easelOverviewExitFadesOutThumbnailsBeforeRevealingStory() = runComposeUiTest {
        mainClock.autoAdvance = false
        val deck = Storyboard.build(title = "OverviewExit") {
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF00C853)))
            }
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().background(Color(0xFFFF1744)))
            }
        }
        val visibility = OverviewVisibility(initiallyVisible = true)

        setContent {
            val animatic = rememberAnimatic(storyboard = { deck })
            Box(Modifier.size(640.dp, 360.dp).testTag("root")) {
                EaselOverview(animatic = animatic, visibility = visibility) {
                    Easel(animatic)
                }
            }
        }
        mainClock.advanceTimeBy(400)

        fun samplePixels(): Pair<Int, Int> {
            val map = onNodeWithTag("root").captureToImage().toPixelMap()
            var redPixels = 0
            var outerEdgeGreenPixels = 0
            for (y in 0 until map.height step 2) {
                for (x in 0 until map.width step 2) {
                    val c = map[x, y]
                    // Scene 1 thumbnail is bright red (#FF1744).
                    if (c.red > 0.55f && c.green < 0.25f && c.blue < 0.35f) {
                        redPixels++
                    }
                    // Top-left outer margin is only green when the full-viewport story layer is visible.
                    if (x < 16 && y < 16 && c.green > 0.45f && c.red < 0.25f) {
                        outerEdgeGreenPixels++
                    }
                }
            }
            return redPixels to outerEdgeGreenPixels
        }

        val (openRed, _) = samplePixels()
        assertTrue(openRed > 50, "Expected Scene 1 red thumbnail while overview is open, got $openRed px")

        // Close overview and step frame-by-frame through the entire exit transition.
        runOnIdle { visibility.isVisible = false }
        var ghostFrames = 0
        var sawStoryVisible = false
        repeat(30) { frame ->
            mainClock.advanceTimeByFrame()
            val (red, storyGreen) = samplePixels()
            if (storyGreen > 10) sawStoryVisible = true
            if (red > 0 && storyGreen > 10) {
                ghostFrames++
            }
            // After 110ms (frame >= 7), overview fadeOut(90ms) must have reached alpha 0.
            if (frame >= 8) {
                assertEquals(
                    0,
                    red,
                    "Overview thumbnail pixels must be completely gone by frame $frame, got $red px",
                )
            }
        }
        assertEquals(0, ghostFrames, "Overview thumbnails must never overlap the entering story layer")
        assertTrue(sawStoryVisible, "Expected full-bleed Scene 0 story layer to become visible after overview exit")
    }
}
