package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.buildFoundationTestStoryboard
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun animaticSeekToSegmentScrubsCrossSlideOverlapAndSharedCardForwardAndBackwardDeterministically() =
        runComposeUiTest {
            lateinit var animatic: Animatic
            lateinit var uiScope: CoroutineScope
            val deck = buildFoundationTestStoryboard()

            setContent {
                uiScope = rememberCoroutineScope()
                animatic = rememberAnimatic(storyboard = { deck })
                Box(Modifier.size(960.dp, 540.dp)) {
                    Easel(animatic)
                }
            }

            waitForIdle()
            assertTrue(animatic.stateDescriptors.isNotEmpty(), "Expected populated stateDescriptors")

            val scene0EndIdx = animatic.stateDescriptors.first {
                it.sceneIndex == 0 && it.isBoundaryEnd
            }.stateIndex

            // Early in cross-slide MagicMove (0.10f), outgoing eyebrow is visible; late (0.90f), incoming eyebrow is visible.
            scrubTo(uiScope, animatic, scene0EndIdx, 0.10f)
            assertEquals(scene0EndIdx, animatic.activeFromStateIndex)
            assertEquals(0.10f, animatic.activeFraction, absoluteTolerance = 0.02f)
            onNodeWithText("FOUNDATION / PROVISIONAL").assertIsDisplayed()

            scrubTo(uiScope, animatic, scene0EndIdx, 0.90f)
            onNodeWithText("SHARED ELEMENT / PROVISIONAL").assertIsDisplayed()

            // Settling to Scene 1 Frame 0 unmounts Scene 0 cleanly without residual nodes.
            val scene1Frame0Idx = animatic.stateDescriptors.first {
                it.sceneIndex == 1 && it.frameIndex == 0 && !it.isBoundaryStart && !it.isBoundaryEnd
            }.stateIndex
            scrubTo(uiScope, animatic, scene1Frame0Idx, 0f)
            assertEquals(0, onAllNodesWithText("FOUNDATION / PROVISIONAL").fetchSemanticsNodes().size)

            // Scrub S2:B0 -> B1 shared-card segment forward to 25%, forward to 75%, then BACKWARD to 25%!
            scrubTo(uiScope, animatic, scene1Frame0Idx, 0.25f)
            val boundsAt25Forward: Rect = onNodeWithTag("doc-node-foundation-shared-card").fetchSemanticsNode().boundsInRoot

            scrubTo(uiScope, animatic, scene1Frame0Idx, 0.75f)
            val boundsAt75: Rect = onNodeWithTag("doc-node-foundation-shared-card").fetchSemanticsNode().boundsInRoot
            assertNotEquals(boundsAt25Forward.left, boundsAt75.left, "Shared card should move/expand between 25% and 75%")

            // Scrub BACKWARD to 25% without settling — must match 25% forward!
            scrubTo(uiScope, animatic, scene1Frame0Idx, 0.25f)
            val boundsAt25Backward: Rect = onNodeWithTag("doc-node-foundation-shared-card").fetchSemanticsNode().boundsInRoot
            assertEquals(boundsAt25Forward.left, boundsAt25Backward.left, absoluteTolerance = 2f)
        }

    @Test
    fun renderSessionScrubsAmbientMeshDeterministicallyViaMasterPlayheadMillis() = runComposeUiTest {
        var playheadMs by mutableLongStateOf(0L)
        val store = createLoadedTestDeckStore()

        setContent {
            val session = remember {
                RenderSession.forPresent(
                    store = store,
                    freeClockMillis = { 999L },
                    masterPlayheadMillis = { playheadMs },
                )
            }
            val sb = rememberDocumentStoryboard(
                session = session,
                config = DocumentRenderConfig(
                    themeName = "dark",
                    specimens = AllDocumentDecks.foundation.specimens,
                ),
            )
            Box(Modifier.size(320.dp, 180.dp).testTag("mesh-scrub")) {
                Easel(
                    rememberAnimatic(
                        initialIndex = Storyboard.Index(2, 0),
                        storyboard = { sb },
                    ),
                )
            }
        }
        waitForIdle()
        val atZero = onNodeWithTag("mesh-scrub").captureToImage().toPixelMap()

        runOnIdle { playheadMs = 1_500L }
        waitForIdle()
        val atQuarter = onNodeWithTag("mesh-scrub").captureToImage().toPixelMap()

        runOnIdle { playheadMs = 0L }
        waitForIdle()
        val backToZero = onNodeWithTag("mesh-scrub").captureToImage().toPixelMap()

        var diffZeroQuarter = 0
        var diffZeroReturn = 0
        for (y in 0 until atZero.height step 4) {
            for (x in 0 until atZero.width step 4) {
                val c0 = atZero[x, y]
                val cq = atQuarter[x, y]
                val cr = backToZero[x, y]
                if (abs(c0.red - cq.red) > 0.02f || abs(c0.green - cq.green) > 0.02f || abs(c0.blue - cq.blue) > 0.02f) {
                    diffZeroQuarter++
                }
                if (abs(c0.red - cr.red) > 0.01f || abs(c0.green - cr.green) > 0.01f || abs(c0.blue - cr.blue) > 0.01f) {
                    diffZeroReturn++
                }
            }
        }
        assertTrue(diffZeroQuarter > 10, "Scrubbing masterPlayheadMillis 0 -> 1500ms must move ambient mesh")
        assertEquals(0, diffZeroReturn, "Scrubbing masterPlayheadMillis back to 0ms must reproduce exact frame")
    }
}
