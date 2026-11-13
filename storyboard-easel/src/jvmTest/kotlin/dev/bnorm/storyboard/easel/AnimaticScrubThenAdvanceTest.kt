package dev.bnorm.storyboard.easel

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Storyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class AnimaticScrubThenAdvanceTest {

    private val deck = Storyboard.build(title = "ScrubThenAdvance") {
        scene(frames = listOf(0, 1, 2)) {
            // Every beat moves something, so each segment has a real duration to scrub into.
            val x by transition.animateFloat(transitionSpec = { tween(300) }, label = "x") { frame ->
                when (frame) {
                    is Frame.Value -> frame.value.toFloat()
                    else -> 0f
                }
            }
            Text("Scene 0 x=$x")
        }
        scene(frames = listOf(0, 1)) { Text("Scene 1") }
    }

    private fun scrubThenPress(fromStateIndex: Int, fraction: Float, direction: AdvanceDirection): Storyboard.Index {
        var result: Storyboard.Index? = null
        runComposeUiTest {
            lateinit var animatic: Animatic
            lateinit var scope: CoroutineScope
            setContent {
                scope = rememberCoroutineScope()
                Box(Modifier.size(200.dp, 120.dp)) {
                    animatic = rememberAnimatic(storyboard = { deck })
                    Easel(animatic)
                }
            }
            waitForIdle()
            runOnIdle { scope.launch { animatic.seekToSegment(fromStateIndex, fraction) } }
            mainClock.advanceTimeBy(1_000)
            runOnIdle { scope.launch { animatic.advance(direction) } }
            mainClock.advanceTimeBy(3_000)
            runOnIdle { result = animatic.currentIndex }
        }
        return checkNotNull(result)
    }

    @Test
    fun rightAfterScrubbingIntoATransitionFinishesThatTransitionInsteadOfSkippingItsBeat() {
        // Paused halfway between S1:B1 and S1:B2 (the timeline scrub position), Right must land on
        // B2, the beat the stage is animating into, not jump ahead to B3.
        assertEquals(Storyboard.Index(0, 1), scrubThenPress(0, 0.5f, AdvanceDirection.Forward))
        assertEquals(Storyboard.Index(0, 2), scrubThenPress(1, 0.5f, AdvanceDirection.Forward))
    }

    @Test
    fun leftAfterScrubbingIntoATransitionRewindsToItsStartingBeat() {
        assertEquals(Storyboard.Index(0, 0), scrubThenPress(0, 0.5f, AdvanceDirection.Backward))
        assertEquals(Storyboard.Index(0, 1), scrubThenPress(1, 0.5f, AdvanceDirection.Backward))
    }

    @Test
    fun rebuildingStoryboardWhileScrubbedMidTransitionRetainsSegmentAndFraction() = runComposeUiTest {
        mainClock.autoAdvance = false
        var version by androidx.compose.runtime.mutableIntStateOf(0)
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            val currentDeck = androidx.compose.runtime.remember(version) {
                Storyboard.build(title = "ScrubRebuild-$version") {
                    scene(frames = listOf(0, 1, 2)) {
                        val x by transition.animateFloat(transitionSpec = { tween(300 + version * 50) }, label = "x") { frame ->
                            when (frame) {
                                is Frame.Value -> frame.value.toFloat()
                                else -> 0f
                            }
                        }
                        Text("Scene 0 v=$version x=$x")
                    }
                    scene(frames = listOf(0, 1)) { Text("Scene 1 v=$version") }
                }
            }
            Box(Modifier.size(200.dp, 120.dp)) {
                animatic = rememberAnimatic(storyboard = { currentDeck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(200)
        runOnIdle { scope.launch { animatic.seekToSegment(0, 0.5f) } }
        mainClock.advanceTimeBy(500)
        runOnIdle {
            assertEquals(0, animatic.activeFromStateIndex)
            assertEquals(1, animatic.activeToStateIndex)
            kotlin.test.assertTrue(kotlin.math.abs(animatic.activeFraction - 0.5f) < 0.05f)
        }

        // Simulate an Inspector parameter edit or theme switch that rebuilds the Storyboard.
        runOnIdle { version = 1 }
        mainClock.advanceTimeBy(500)
        runOnIdle {
            assertEquals(0, animatic.activeFromStateIndex, "Segment fromState must be retained across Storyboard rebuild")
            assertEquals(1, animatic.activeToStateIndex, "Segment toState must be retained across Storyboard rebuild")
            kotlin.test.assertTrue(
                kotlin.math.abs(animatic.activeFraction - 0.5f) < 0.05f,
                "Scrubbed fraction must be retained across Storyboard rebuild, got ${animatic.activeFraction}",
            )
        }
    }

    @Test
    fun rebuildingStoryboardMidAdvanceLandsOnTargetIndexInsteadOfSnappingBack() = runComposeUiTest {
        mainClock.autoAdvance = false
        var version by androidx.compose.runtime.mutableIntStateOf(0)
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            val currentDeck = androidx.compose.runtime.remember(version) {
                Storyboard.build(title = "AdvanceRebuild-$version") {
                    scene(frames = listOf(0, 1, 2)) {
                        val x by transition.animateFloat(transitionSpec = { tween(400) }, label = "x") { frame ->
                            when (frame) {
                                is Frame.Value -> frame.value.toFloat()
                                else -> 0f
                            }
                        }
                        Text("Scene 0 v=$version x=$x")
                    }
                }
            }
            Box(Modifier.size(200.dp, 120.dp)) {
                animatic = rememberAnimatic(storyboard = { currentDeck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(200)
        runOnIdle { scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        mainClock.advanceTimeBy(150) // mid-transition towards (0, 1)
        runOnIdle { assertEquals(Storyboard.Index(0, 1), animatic.targetIndex) }

        // Rebuild Storyboard mid-advance (e.g. switching theme while a transition is playing).
        runOnIdle { version = 1 }
        mainClock.advanceTimeBy(200)
        runOnIdle {
            assertEquals(
                Storyboard.Index(0, 1),
                animatic.currentIndex,
                "Rebuilding Storyboard mid-advance must preserve targetIndex instead of snapping back to (0, 0)",
            )
        }
    }

    @Test
    fun rapidDoubleBackwardPressMidForwardTransitionReversesCleanlyWithoutThrowing() = runComposeUiTest {
        mainClock.autoAdvance = false
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(200.dp, 120.dp)) {
                animatic = rememberAnimatic(storyboard = { deck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(200)
        var thrown: Throwable? = null

        // 1. Jump to (0, 1), press Right (starts 0,1 -> 0,2, 300ms), then after 160ms press Left #1
        // (starts reversing toward 0,1), and 50ms later (while the reversal is still in flight!)
        // press Left #2 (F-qa-stage-028).
        runOnIdle { scope.launch { animatic.jumpTo(Storyboard.Index(0, 1)) } }
        mainClock.advanceTimeBy(100)
        runOnIdle {
            scope.launch {
                runCatching { animatic.advance(AdvanceDirection.Forward) }.onFailure { thrown = it }
            }
        }
        mainClock.advanceTimeBy(160)
        runOnIdle {
            scope.launch {
                runCatching { animatic.advance(AdvanceDirection.Backward) }.onFailure { thrown = it }
            }
        }
        mainClock.advanceTimeBy(50)
        runOnIdle {
            scope.launch {
                runCatching { animatic.advance(AdvanceDirection.Backward) }.onFailure { thrown = it }
            }
        }
        mainClock.advanceTimeBy(1_000)
        runOnIdle {
            if (thrown != null) throw AssertionError("Rapid ->, <-, <- must not throw internal error", thrown)
            assertEquals(
                Storyboard.Index(0, 0),
                animatic.currentIndex,
                "Rapid ->, <-, <- from (0, 1) must reverse to (0, 1) then continue back to (0, 0)",
            )
        }

        // 2. From (0, 0), rapid ->, <-, <- must clamp cleanly at (0, 0) without throwing.
        runOnIdle {
            scope.launch {
                runCatching { animatic.advance(AdvanceDirection.Forward) }.onFailure { thrown = it }
            }
        }
        mainClock.advanceTimeBy(160)
        runOnIdle {
            scope.launch {
                runCatching { animatic.advance(AdvanceDirection.Backward) }.onFailure { thrown = it }
            }
        }
        mainClock.advanceTimeBy(50)
        runOnIdle {
            scope.launch {
                runCatching { animatic.advance(AdvanceDirection.Backward) }.onFailure { thrown = it }
            }
        }
        mainClock.advanceTimeBy(1_000)
        runOnIdle {
            if (thrown != null) throw AssertionError("Rapid ->, <-, <- at deck start must not throw", thrown)
            assertEquals(Storyboard.Index(0, 0), animatic.currentIndex)
        }
    }
}
