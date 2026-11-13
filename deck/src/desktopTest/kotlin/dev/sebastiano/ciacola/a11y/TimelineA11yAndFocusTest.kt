package dev.sebastiano.ciacola.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.theme.LocalAmbientLoopsPaused
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.MotionPathPreviewState
import dev.sebastiano.ciacola.timeline.TimelineOverridesStore
import dev.sebastiano.ciacola.timeline.TimelineStageSelection
import dev.sebastiano.ciacola.timeline.TimelineStudioController
import dev.sebastiano.ciacola.timeline.TimelineStudioPanel
import dev.sebastiano.ciacola.ui.accessibleFocusRingColor
import dev.sebastiano.ciacola.timeline.createSpaceResetWindowFocusListener
import dev.sebastiano.ciacola.timeline.rememberTimelineStageDecorator
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioController
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioWindow
import dev.sebastiano.ciacola.timeline.wcagContrastRatio
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TimelineA11yAndFocusTest {

    @Test
    fun focusRingColorsMeetWcag3To1ContrastInBothStageDarkAndKeynoteLight() {
        val darkPanel = Color(0xFF2B2D30)
        val lightPanel = Color(0xFFF7F8FA)
        val lowContrast = Color(0xFF31343A)

        val darkRing = accessibleFocusRingColor(focused = Color(0xFF3574F0), isDark = true, surface = darkPanel)
        val lightRing = accessibleFocusRingColor(focused = Color(0xFF3574F0), isDark = false, surface = lightPanel)
        val boostedDark = accessibleFocusRingColor(focused = lowContrast, isDark = true, surface = darkPanel)
        val boostedLight = accessibleFocusRingColor(focused = Color(0xFFEAECEF), isDark = false, surface = lightPanel)

        assertTrue(wcagContrastRatio(darkRing, darkPanel) >= 3.0f, "Dark focus ring must be >= 3:1")
        assertTrue(wcagContrastRatio(lightRing, lightPanel) >= 3.0f, "Light focus ring must be >= 3:1")
        assertTrue(wcagContrastRatio(boostedDark, darkPanel) >= 3.0f, "Boosted dark focus ring must be >= 3:1")
        assertTrue(wcagContrastRatio(boostedLight, lightPanel) >= 3.0f, "Boosted light focus ring must be >= 3:1")
    }

    @Test
    fun spaceAndEnterActivateFocusedButtonsPillsAndLayerRowsWithoutGlobalPlayPause() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-a11y-space-enter")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            remember(controller) { controller.dockedInStoryWindow = true }
            val studioWindow = rememberTimelineStudioWindow(controller = controller)
            Box(Modifier.size(Dp(1280f), Dp(720f))) {
                studioWindow.decorator.decorate {
                    EaselOverview(animatic) {
                        Easel(animatic)
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { controller.refreshSnapshot(deck) }
        waitForIdle()

        // 1. Focus a Timeline layer row and press Space -> selects the row, does NOT toggle playback!
        val targetRow = controller.snapshot.tracks.first { it.trackId == "lab-immutable-stamp" }
        val rowNode = onNodeWithTag("ciacola-track-row-${targetRow.trackId}", useUnmergedTree = true)
        rowNode.requestFocus()
        waitForIdle()
        rowNode.assertIsFocused()

        rowNode.performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(targetRow.trackId, controller.selectedTrackId, "Space on focused row must select it")
        assertFalse(controller.isPlaying, "Space on focused row must NOT start global playback")

        // 2. Focus a CurvePresetButton ("Spring") and press Space -> selects Spring, does NOT toggle playback!
        val springNode = onNodeWithTag("ciacola-inspector-curve-spring", useUnmergedTree = true)
        springNode.requestFocus()
        waitForIdle()
        springNode.performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertTrue(store.data().tracks[targetRow.trackId]?.curve?.isSpring == true, "Space on focused pill must activate it")
        assertFalse(controller.isPlaying, "Space on focused pill must NOT start global playback")

        // 3. Focus "Linear" pill and press Enter -> activates Linear!
        val linearNode = onNodeWithTag("ciacola-inspector-curve-linear", useUnmergedTree = true)
        linearNode.requestFocus()
        waitForIdle()
        linearNode.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("Linear", store.data().tracks[targetRow.trackId]?.curve?.preset)

        // 4. Focus Next Beat button and press Space -> steps beat forward without toggling playback!
        val playheadBefore = controller.currentPlayheadTimeMs
        val nextBeatBtn = onNodeWithTag("ciacola-timeline-next-beat", useUnmergedTree = true)
        nextBeatBtn.requestFocus()
        waitForIdle()
        nextBeatBtn.performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertTrue(controller.currentPlayheadTimeMs > playheadBefore, "Space on Next Beat button must step beat")
        assertFalse(controller.isPlaying, "Space on Next Beat button must NOT toggle Play/Pause")
    }

    @Test
    fun upAndDownArrowsNavigateBetweenLayerRowsWhenLayerListHasFocus() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-a11y-updown")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            remember(controller) { controller.dockedInStoryWindow = true }
            val studioWindow = rememberTimelineStudioWindow(controller = controller)
            Box(Modifier.size(Dp(1280f), Dp(720f))) {
                studioWindow.decorator.decorate {
                    EaselOverview(animatic) {
                        Easel(animatic)
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { controller.refreshSnapshot(deck) }
        waitForIdle()

        val tracks = controller.snapshot.tracks
        assertTrue(tracks.size >= 2)
        val row0 = onNodeWithTag("ciacola-track-row-${tracks[0].trackId}", useUnmergedTree = true)
        val row1 = onNodeWithTag("ciacola-track-row-${tracks[1].trackId}", useUnmergedTree = true)

        row0.requestFocus()
        waitForIdle()
        row0.assertIsFocused()
        val slideBefore = controller.animatic.currentIndex

        row0.performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        row1.assertIsFocused()
        row0.assertIsNotFocused()
        assertEquals(slideBefore, controller.animatic.currentIndex, "Down arrow in layer list must not change slide")

        row1.performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        row0.assertIsFocused()
        assertEquals(slideBefore, controller.animatic.currentIndex, "Up arrow in layer list must not change slide")
    }

    @Test
    fun escInsideTimelineStudioExitsSubCompFirstThenClosesDockedStudioWithoutOpeningOverview() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-a11y-esc")
        val store = TimelineOverridesStore(tempDir)
        lateinit var controller: TimelineStudioController
        lateinit var overviewVisibility: dev.bnorm.storyboard.easel.overview.OverviewVisibility
        lateinit var deck: dev.bnorm.storyboard.Storyboard

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val motionPath = remember { MotionPathPreviewState() }
            val stageSelection = remember { TimelineStageSelection() }
            val stageDecorator = rememberTimelineStageDecorator(
                loopClock = loopClock,
                motionPathPreview = motionPath,
                overridesStore = store,
                stageSelection = stageSelection,
            )
            deck = remember {
                createFoundationDeck(
                    includeKeynoteEffectsLab = true,
                    stageDecorator = stageDecorator,
                )
            }
            val animatic = rememberAnimatic(storyboard = { deck })
            overviewVisibility = rememberOverviewVisibility()
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
                motionPathPreview = motionPath,
                stageSelection = stageSelection,
            )
            remember(controller) { controller.dockedInStoryWindow = true }
            val studioWindow = rememberTimelineStudioWindow(controller = controller)
            CompositionLocalProvider(LocalAmbientLoopsPaused provides true) {
                Box(Modifier.size(Dp(1280f), Dp(720f))) {
                    studioWindow.decorator.decorate {
                        EaselOverview(animatic, visibility = overviewVisibility) {
                            Easel(animatic)
                        }
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { controller.refreshSnapshot(deck) }
        waitForIdle()

        // Enter Loop Sub-Composition while focus is inside Timeline Studio.
        onNodeWithTag("ciacola-timeline-open-loop", useUnmergedTree = true).performClick()
        waitForIdle()
        assertNotNull(controller.loopClock.activeSubCompLoopId)
        val exitSubCompBtn = onNodeWithTag("ciacola-timeline-exit-subcomp", useUnmergedTree = true)
        exitSubCompBtn.requestFocus()
        waitForIdle()
        exitSubCompBtn.assertIsFocused()

        // First Esc: exits Loop Sub-Comp back to Master Deck (Studio stays docked, Overview stays closed).
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(controller.loopClock.activeSubCompLoopId, "First Esc must exit Loop Sub-Composition")
        assertTrue(controller.dockedInStoryWindow, "First Esc must keep docked Studio open")
        assertFalse(overviewVisibility.isVisible, "First Esc must not open Overview")
        // Second Esc (at Master Deck inside Studio): closes docked Studio without needing to re-focus!
        val studioNode = onNodeWithTag("ciacola-timeline-studio", useUnmergedTree = true)
        val panelFocusedAfterExit = studioNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)
        studioNode.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(
            controller.dockedInStoryWindow,
            "Second Esc must close docked Studio (panelFocusedAfterExit=$panelFocusedAfterExit, subLoop=${controller.loopClock.activeSubCompLoopId}, overview=${overviewVisibility.isVisible})",
        )
        assertFalse(overviewVisibility.isVisible, "Closing Studio with Esc must not open Overview")

        // Third Esc (now back on Stage with Studio closed): toggles Overview cleanly.
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertTrue(overviewVisibility.isVisible, "Third Esc outside Studio must open Overview")
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(overviewVisibility.isVisible, "Esc in Overview must close Overview")

        // F5 toggles docked Studio cleanly once per press (no double-toggle).
        onRoot().performKeyInput { pressKey(Key.F5) }
        waitForIdle()
        assertTrue(controller.dockedInStoryWindow, "F5 must open docked Studio cleanly")
        onRoot().performKeyInput { pressKey(Key.F5) }
        waitForIdle()
        assertFalse(controller.dockedInStoryWindow, "F5 must close docked Studio cleanly")
    }

    @Test
    fun stuckSpaceIsResetWhenWindowLosesFocus() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-a11y-space-reset")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            remember(controller) { controller.dockedInStoryWindow = true }
            val studioWindow = rememberTimelineStudioWindow(controller = controller)
            Box(Modifier.size(Dp(1280f), Dp(720f))) {
                studioWindow.decorator.decorate {
                    EaselOverview(animatic) {
                        Easel(animatic)
                    }
                }
            }
        }
        waitForIdle()

        onRoot().performKeyInput { keyDown(Key.Spacebar) }
        waitForIdle()
        assertTrue(controller.spaceHeld, "Space KeyDown must engage spaceHeld")

        // Simulate Cmd+Tab / AWT window focus loss before Space KeyUp arrives.
        runOnIdle {
            createSpaceResetWindowFocusListener(controller).windowLostFocus(null)
        }
        waitForIdle()
        assertFalse(controller.spaceHeld, "WindowFocusListener.windowLostFocus must reset spaceHeld")
    }

    @Test
    fun timelineSemanticsAreDescriptiveAndFreeOfInternalDebugStrings() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-a11y-semantics")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            remember(controller) { controller.dockedInStoryWindow = true }
            val studioWindow = rememberTimelineStudioWindow(controller = controller)
            Box(Modifier.size(Dp(1280f), Dp(720f))) {
                studioWindow.decorator.decorate {
                    EaselOverview(animatic) {
                        Easel(animatic)
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle {
            controller.refreshSnapshot(deck)
            controller.selectTrack("lab-immutable-stamp")
        }
        waitForIdle()

        val rulerNode = onNodeWithTag("ciacola-timeline-ruler", useUnmergedTree = true).fetchSemanticsNode()
        val rulerDesc = rulerNode.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        val rulerState = rulerNode.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
        assertTrue(rulerDesc.contains("Timeline ruler", ignoreCase = true), "Ruler description: $rulerDesc")
        assertTrue(rulerState.contains("Playhead", ignoreCase = true), "Ruler state: $rulerState")

        val rowNode = onNodeWithTag("ciacola-track-row-lab-immutable-stamp", useUnmergedTree = true).fetchSemanticsNode()
        val rowDesc = rowNode.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        val rowState = rowNode.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
        assertTrue(rowDesc.contains("IMMUTABLE", ignoreCase = true), "Row description: $rowDesc")
        assertTrue(rowState.contains("Selected", ignoreCase = true), "Row state: $rowState")

        val firstBlock = controller.snapshot.tracks.first { it.trackId == "lab-immutable-stamp" }.blocks.first()
        val blockNode = onNodeWithTag("ciacola-timeline-block-${firstBlock.id}", useUnmergedTree = true).fetchSemanticsNode()
        val blockDesc = blockNode.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        val blockState = blockNode.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
        assertTrue(blockDesc.contains("IMMUTABLE", ignoreCase = true), "Block description: $blockDesc")
        assertTrue(blockState.contains("duration", ignoreCase = true), "Block state: $blockState")

        val bezierNode = onNodeWithTag("ciacola-bezier-editor", useUnmergedTree = true).fetchSemanticsNode()
        val bezierDesc = bezierNode.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        assertTrue(bezierDesc.contains("Bezier curve editor", ignoreCase = true), "Bezier description: $bezierDesc")

        // Ensure no raw internal debug strings leak into timeline/inspector/viewport accessibility nodes.
        val forbiddenPrefixes = listOf("zoom=", "scrollX=", "scrollY=", "previewScale=", "previewOffset=", "preset=", "p1=", "p2=")
        fun checkTree(node: SemanticsNode) {
            val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            if (tag != "ciacola-stage-selection-overlay") {
                val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
                val state = node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
                for (prefix in forbiddenPrefixes) {
                    assertFalse(desc.contains(prefix), "Raw debug string '$prefix' leaked in $tag contentDescription: '$desc'")
                    assertFalse(state.contains(prefix), "Raw debug string '$prefix' leaked in $tag stateDescription: '$state'")
                }
            }
            node.children.forEach(::checkTree)
        }
        checkTree(onRoot(useUnmergedTree = true).fetchSemanticsNode())
    }
}
