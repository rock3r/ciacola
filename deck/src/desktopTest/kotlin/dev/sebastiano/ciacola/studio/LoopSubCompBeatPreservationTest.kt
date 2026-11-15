package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.authoring.DeckHostParams
import dev.sebastiano.ciacola.authoring.ciacolaDeck
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.MotionPathPreviewState
import dev.sebastiano.ciacola.timeline.TimelineOverridesStore
import dev.sebastiano.ciacola.timeline.TimelineStageSelection
import dev.sebastiano.ciacola.timeline.TimelineStudioController
import dev.sebastiano.ciacola.timeline.TimelineStudioPanel
import dev.sebastiano.ciacola.timeline.TimelineTrackFilter
import dev.sebastiano.ciacola.timeline.rememberDeckLoopMillis
import dev.sebastiano.ciacola.timeline.rememberTimelineStageDecorator
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioController
import java.nio.file.Files
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.CoroutineScope

/**
 * Verifies UX-04: entering Loop Sub-Composition on a multi-beat slide's Beat 2 (`frameIndex = 1`)
 * preserves the active beat and playhead so `blue` responds to sub-comp scrubbing instead of
 * resetting to Beat 1 (`frameIndex = 0`, locked at `1.00`), and double-clicking a loop bar enters
 * Loop Sub-Composition directly.
 */
@OptIn(ExperimentalTestApi::class)
class LoopSubCompBeatPreservationTest {

    private companion object {
        const val TEST_LOOP_ID = "fixture-loop-rgb"
        const val LOOP_PERIOD_MS = 4_000L

        fun createLoopBeatFixtureDeck(host: DeckHostParams): Storyboard = ciacolaDeck(
            id = "loop-beat-preservation-fixture",
            title = "Loop Beat Preservation Fixture",
            host = host,
        ) {
            slide(key = "intro", title = "Intro", beats = 1) {
                val theme = LocalDeckTheme.current
                Box(Modifier.fillMaxSize().padding(64.dp)) {
                    DeckEditableText(
                        elementId = "loop-fixture:s1:title",
                        text = "Intro Slide",
                        style = theme.typography.title,
                        color = theme.colors.foreground,
                    )
                }
            }
            slide(key = "animated-channel", title = "Animated Channel", beats = 2) {
                val theme = LocalDeckTheme.current
                val loopMs = rememberDeckLoopMillis(
                    loopId = TEST_LOOP_ID,
                    label = "Channel Wave",
                    periodMillis = LOOP_PERIOD_MS,
                    harmonicChannels = listOf("Blue Channel sin(ωt)"),
                )
                val phase = (loopMs.toDouble() / LOOP_PERIOD_MS.toDouble()) * 2.0 * PI
                val blueValue = if (beat == 0) 1.0f else (0.5 + 0.5 * sin(phase)).toFloat()
                Column(
                    modifier = Modifier.fillMaxSize().padding(64.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    DeckEditableText(
                        elementId = "loop-fixture:s2:title",
                        text = "Time-Driven Channel",
                        style = theme.typography.heading,
                        color = theme.colors.foreground,
                    )
                    DeckEditableText(
                        elementId = "loop-fixture:s2:blue",
                        text = "blue = %.2f".format(Locale.US, blueValue),
                        style = theme.typography.code,
                        color = theme.colors.accent,
                    )
                }
            }
        }
    }

    @Test
    fun enteringSubCompOnBeatTwoPreservesBeatAndScrubbingUpdatesBlue() = runComposeUiTest {
        val tmpDir = Files.createTempDirectory("ciacola-loop-subcomp-")
        val overridesStore = TimelineOverridesStore(storyboardDir = tmpDir)
        lateinit var animatic: Animatic
        lateinit var controller: TimelineStudioController
        lateinit var loopClock: DeckLoopClock
        lateinit var scope: CoroutineScope

        try {
            setContent {
                scope = rememberCoroutineScope()
                loopClock = remember { DeckLoopClock().apply { masterTimeMillis = 0L } }
                val motionPath = remember { MotionPathPreviewState() }
                val stageSelection = remember { TimelineStageSelection() }
                val stageDecorator = rememberTimelineStageDecorator(
                    loopClock = loopClock,
                    motionPathPreview = motionPath,
                    overridesStore = overridesStore,
                    stageSelection = stageSelection,
                )
                val storyboard = remember {
                    createLoopBeatFixtureDeck(
                        DeckHostParams(
                            theme = DeckThemes.stageDark(),
                            bundledPreset = DeckThemePreset.StageDark,
                            stageDecorator = stageDecorator,
                            timelineOverrides = overridesStore.data(),
                            timelineOverridesProvider = {
                                overridesStore.parameterRevision
                                overridesStore.structuralRevision
                                overridesStore.data()
                            },
                        ),
                    )
                }
                animatic = rememberAnimatic(
                    initialIndex = Storyboard.Index(sceneIndex = 1, frameIndex = 1),
                    storyboard = { storyboard },
                )
                controller = rememberTimelineStudioController(
                    animatic = animatic,
                    overridesStore = overridesStore,
                    loopClock = loopClock,
                    motionPathPreview = motionPath,
                    stageSelection = stageSelection,
                ).also {
                    it.dockedInStoryWindow = true
                }

                CompositionLocalProvider(LocalSceneMode provides SceneMode.Story) {
                    Column(Modifier.size(1200.dp, 820.dp)) {
                        Box(Modifier.fillMaxWidth().height(480.dp)) {
                            Easel(animatic)
                        }
                        Box(Modifier.fillMaxWidth().height(340.dp)) {
                            TimelineStudioPanel(
                                controller = controller,
                                isDark = true,
                            )
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(1_500)
            waitForIdle()

            // Confirm we are resting on Slide 2 Beat 2 (sceneIndex = 1, frameIndex = 1).
            assertEquals(Storyboard.Index(1, 1), animatic.presentedIndex)
            val masterStateBefore = animatic.activeFromStateIndex
            val masterPlayheadBefore = controller.currentPlayheadTimeMs

            // Freeze loop clock at 0ms -> sin(0) = 0 -> blue = 0.50 on S2:B2 (whereas S2:B1 locks blue = 1.00).
            runOnIdle { loopClock.scrubbedMillis = 0L }
            waitForIdle()
            val blueBefore = readBlueReadout()
            assertEquals("blue = 0.50", blueBefore)

            // Enter Loop Sub-Composition while already on S2:B2: master beat & state must NOT reset to B1!
            runOnIdle {
                controller.enterLoopSubComp(scope, loopId = TEST_LOOP_ID, sceneIndex = 1)
            }
            mainClock.advanceTimeBy(400)
            waitForIdle()

            assertEquals(TEST_LOOP_ID, loopClock.activeSubCompLoopId)
            assertEquals(Storyboard.Index(1, 1), animatic.presentedIndex)
            assertEquals(masterStateBefore, animatic.activeFromStateIndex)
            assertEquals(blueBefore, readBlueReadout())

            // Scrubbing sub-comp to 3000ms (3/4 of 4000ms cycle -> sin(3*pi/2) = -1 -> blue = 0.00) must change blue.
            runOnIdle { loopClock.scrubbedMillis = 3_000L }
            waitForIdle()
            val blueAt3s = readBlueReadout()
            assertEquals("blue = 0.00", blueAt3s)
            assertNotEquals(blueBefore, blueAt3s)

            // Exit sub-comp: master playhead must still be parked on S2:B2 (masterPlayheadBefore),
            // then filter to Current Slide so S2's loop bar is at top of canvas and double-click it.
            runOnIdle {
                controller.exitLoopSubComp()
                controller.trackFilter = TimelineTrackFilter.CurrentSlide
            }
            waitForIdle()
            assertEquals(null, loopClock.activeSubCompLoopId)
            assertEquals(
                masterPlayheadBefore,
                controller.currentPlayheadTimeMs,
                "Exiting sub-comp must return to the preserved S2:B2 master playhead",
            )

            val loopRow = controller.visibleTracks(1).first { row ->
                row.blocks.any { it.loopId == TEST_LOOP_ID }
            }
            val loopBlock = loopRow.blocks.first { it.loopId == TEST_LOOP_ID }
            runOnIdle {
                controller.viewport.ensureTimeVisible(loopBlock.startMs.toFloat())
            }
            waitForIdle()
            val blockNode = onNodeWithTag("ciacola-timeline-block-${loopBlock.id}")
            val blockBounds = blockNode.fetchSemanticsNode().boundsInRoot
            val canvasBounds = onNodeWithTag("ciacola-timeline-canvas").fetchSemanticsNode().boundsInRoot
            val visibleLeft = maxOf(blockBounds.left, canvasBounds.left + 8f)
            val visibleRight = minOf(blockBounds.right, canvasBounds.right - 8f)
            val localCenter = Offset(
                x = (visibleLeft + visibleRight) / 2f - canvasBounds.left,
                y = (blockBounds.top + blockBounds.bottom) / 2f - canvasBounds.top,
            )

            // TimelineSelectionStageOverlay drives a per-frame redraw loop while a track is selected,
            // so step the virtual clock explicitly across the double-click gesture.
            mainClock.autoAdvance = false
            onNodeWithTag("ciacola-timeline-canvas").performMouseInput {
                click(localCenter)
                advanceEventTime(80L)
                click(localCenter)
            }
            mainClock.advanceTimeBy(300)
            mainClock.advanceTimeByFrame()

            assertEquals(
                TEST_LOOP_ID,
                loopClock.activeSubCompLoopId,
                "Double-clicking the loop bar must enter Loop Sub-Composition immediately",
            )
            assertEquals(Storyboard.Index(1, 1), animatic.presentedIndex)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.readBlueReadout(): String {
        val node = onNodeWithText("blue = ", substring = true).fetchSemanticsNode()
        return node.config[SemanticsProperties.Text].joinToString("") { it.text }
    }
}
