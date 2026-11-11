package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class DeterministicEaselScrubAndLoopTest {

    private fun ComposeUiTest.scrubTo(
        uiScope: CoroutineScope,
        animatic: Animatic,
        stateIndex: Int,
        fraction: Float,
    ) {
        lateinit var job: Job
        runOnIdle {
            job = uiScope.launch {
                animatic.seekToSegment(stateIndex, fraction)
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            mainClock.advanceTimeByFrame()
            job.isCompleted
        }
        waitForIdle()
    }

    @Test
    fun animaticSeekToSegmentScrubsCrossSlideOverlapAndSharedBoundsForwardAndBackwardDeterministically() =
        runComposeUiTest {
            lateinit var animatic: Animatic
            lateinit var uiScope: CoroutineScope
            val deck = createFoundationDeck()

            setContent {
                uiScope = rememberCoroutineScope()
                animatic = rememberAnimatic(storyboard = { deck })
                Box(Modifier.size(960.dp, 540.dp)) {
                    Easel(animatic)
                }
            }

            waitForIdle()
            assertTrue(animatic.stateDescriptors.isNotEmpty(), "Expected populated stateDescriptors")

            // 1. Cross-slide boundary segment (Scene 0 Frame.End -> Scene 1 Frame.Start):
            // Both scenes overlap deterministically during (0f, 1f) and settle cleanly at 1f!
            val scene0EndIdx = animatic.stateDescriptors.first {
                it.sceneIndex == 0 && it.isBoundaryEnd
            }.stateIndex

            scrubTo(uiScope, animatic, scene0EndIdx, 0.45f)
            assertEquals(scene0EndIdx, animatic.activeFromStateIndex)
            assertEquals(0.45f, animatic.activeFraction, absoluteTolerance = 0.02f)
            onNodeWithText("FOUNDATION / PROVISIONAL").assertIsDisplayed()
            onNodeWithText("SHARED ELEMENT / PROVISIONAL").assertIsDisplayed()

            // Settling to Scene 1 Frame 0 unmounts Scene 0 cleanly without residual nodes.
            val scene1Frame0Idx = animatic.stateDescriptors.first {
                it.sceneIndex == 1 && it.frameIndex == 0 && !it.isBoundaryStart && !it.isBoundaryEnd
            }.stateIndex
            scrubTo(uiScope, animatic, scene1Frame0Idx, 0f)
            assertEquals(0, onAllNodesWithText("FOUNDATION / PROVISIONAL").fetchSemanticsNodes().size)

            // 2. Scrub sharedBounds segment forward to 25%, forward to 75%, then BACKWARD to 25%!
            scrubTo(uiScope, animatic, scene1Frame0Idx, 0.25f)
            val boundsAt25Forward: Rect = onAllNodesWithText("shared").fetchSemanticsNodes().last().boundsInRoot

            scrubTo(uiScope, animatic, scene1Frame0Idx, 0.75f)
            val boundsAt75: Rect = onAllNodesWithText("shared").fetchSemanticsNodes().last().boundsInRoot
            assertNotEquals(boundsAt25Forward.left, boundsAt75.left, "Shared element should move between 25% and 75%")

            // Scrub BACKWARD to 25% without settling — must match 25% forward with zero AnimatedContent drift!
            scrubTo(uiScope, animatic, scene1Frame0Idx, 0.25f)
            val boundsAt25Backward: Rect = onAllNodesWithText("shared").fetchSemanticsNodes().last().boundsInRoot
            assertEquals(boundsAt25Forward.left, boundsAt25Backward.left, absoluteTolerance = 2f)
        }

    @Test
    fun deckLoopClockScrubsAmbientMeshDeterministicallyWhenInSubComposition() = runComposeUiTest {
        val loopClock = DeckLoopClock().apply {
            enterSubComp("foundation-mesh-loop")
            scrubbedMillis = 0L
        }
        var observedAtZero = -1L
        var observedAtQuarter = -1L

        setContent {
            CompositionLocalProvider(LocalDeckLoopClock provides loopClock) {
                val elapsed = rememberDeckLoopMillis(
                    loopId = "foundation-mesh-loop",
                    label = "Ambient Mesh",
                    periodMillis = 6_000L,
                )
                if (loopClock.scrubbedMillis == 0L) observedAtZero = elapsed
                if (loopClock.scrubbedMillis == 1_500L) observedAtQuarter = elapsed
            }
        }

        waitForIdle()
        assertEquals(0L, observedAtZero)
        assertTrue(loopClock.registeredLoops.containsKey("foundation-mesh-loop"))

        loopClock.scrubbedMillis = 1_500L
        waitForIdle()
        assertEquals(1_500L, observedAtQuarter)
    }
}
