package dev.sebastiano.ciacola.studio

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.presenter.StageCurtain
import dev.sebastiano.ciacola.qa.QaStudio
import dev.sebastiano.ciacola.qa.QaStudioRule
import dev.sebastiano.ciacola.qa.SyntheticInput
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.timeline.DocumentTimelineTrackRef
import dev.sebastiano.ciacola.timeline.TimelineSurface
import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Rule

/**
 * End-to-end Spectre suite driving document-backed Ciacola Studio with realistic continuous human
 * pointer physics (multi-frame 16 ms interpolated mouse drags, mid-drag human hand pauses longer
 * than debounce thresholds, reverse drags before button release, mouse-wheel scrolling, and
 * character-by-character keyboard typing including global shortcut letters) in real non-focus-stealing
 * ComposeWindows.
 */
class HumanRealisticInteractionSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun detachedWindowMidSegmentScrubAndContinuousFontSizeSliderDragNeverRebuildsOrUnmounts(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = qa.launchStudio(
                preset = DeckThemePreset.StageDark,
                windowSize = DpSize(1280.dp, 470.dp),
                windowPosition = WindowPosition(40.dp, 28.dp),
                timelineDocked = false,
                timelineDetached = false,
                detachedWindowSize = DpSize(1280.dp, 510.dp),
                detachedWindowPosition = WindowPosition(40.dp, 520.dp),
            )

            with(studio) {
                settle()
                val initialStoryboard = animatic.storyboard

                // 1. Open Detached "Ciacola - Timeline" (F6 mode) and obtain dedicated AWT input handles
                //    for both the detached Timeline window and the Story window (which hosts the hoisted inspector).
                val detachedWin = openDetachedTimeline()
                val detachedIn = inputFor(detachedWin)
                val storyIn = inputFor(window)
                assertEquals("Ciacola - Timeline", detachedWin.title)
                assertTrue(timelineWindow.detachedWindowVisible)
                assertFalse(timelineWindow.dockedInStoryWindow)

                val ruler = automator.waitForNode(tag = "ciacola-doc-timeline-ruler", timeout = 5.seconds)
                val startRulerPt = rulerPoint(ruler, timeMs = 12, targetWindow = detachedWin, surface = TimelineSurface.Detached)
                val targetMidSegmentMs = 185
                val midSegmentRulerPt = rulerPoint(
                    ruler,
                    timeMs = targetMidSegmentMs,
                    targetWindow = detachedWin,
                    surface = TimelineSurface.Detached,
                )

                // 2. Smooth 20-step human drag along the timeline ruler to ~160–220 ms on Slide 1.
                humanContinuousDrag(
                    input = detachedIn,
                    segments = listOf(DragLeg(to = midSegmentRulerPt, steps = 20, stepDelayMs = 16L)),
                    start = startRulerPt,
                ) {
                    assertSame(initialStoryboard, animatic.storyboard, "Ruler scrub must never rebuild Storyboard")
                }
                settle()
                assertTrue(
                    controller.playheadMs in 130..260,
                    "Expected playhead near ~160–220 ms on Slide 1, got ${controller.playheadMs} ms",
                )
                assertEquals(0, animatic.targetIndex.sceneIndex)

                // 3. Switch to Element tab and click s1:prose in the hoisted Story-window inspector via real AWT clicks.
                val elementTab = automator.waitForNode(tag = "ciacola-studio-tab-element", timeout = 5.seconds)
                humanClick(storyIn, elementTab.centerOnScreen)
                settle()
                assertEquals(StudioInspectorTab.Element, studioWorkspace.activeTab)

                val proseChip = automator.waitForNode(tag = "ciacola-doc-node-item-s1:prose", timeout = 5.seconds)
                humanClick(storyIn, proseChip.centerOnScreen)
                settle()
                assertEquals("s1:prose", elementController.selectedNodeId)
                assertNull(automator.findOneByTestTag("ciacola-doc-node-not-present"))

                val scrubNode = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "ciacola-doc-element-inspector",
                    targetTag = "ciacola-doc-text-size-scrub",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                val scrubBounds = checkNotNull(scrubNode.boundsOnScreen)
                val initialSliderVal = 60f
                // Label scrubbing runs in dp (identical on 1x and 2x displays), and AWT points are dp.
                val rightAwtDx = 64
                val leftAwtDx = 52

                val thumbStart = Point(scrubBounds.x + scrubBounds.width / 2, scrubBounds.y + scrubBounds.height / 2)
                val dragRightTarget = Point(thumbStart.x + rightAwtDx, thumbStart.y)
                val dragLeftTarget = Point(dragRightTarget.x - leftAwtDx, thumbStart.y)

                val rightwardFontSamples = mutableListOf<Float>()
                val pauseFontSamples = mutableListOf<Float>()
                val leftwardFontSamples = mutableListOf<Float>()

                fun currentProseSize(): Float {
                    val slide = store.document.slides.first { it.id == "beats" }
                    val raw = DocumentThemeResolver.findNode(slide.nodes, "s1:prose") ?: return initialSliderVal
                    val resolved = JsonMergePatch.resolveNodeAtBeat(raw, elementController.activeBeat)
                    return (resolved.text?.size ?: initialSliderVal.toDouble()).toFloat()
                }

                fun assertInvariantsEveryStep(phase: String) {
                    assertSame(
                        initialStoryboard,
                        animatic.storyboard,
                        "Storyboard rebuilt during Size drag ($phase)!",
                    )
                    assertNull(
                        automator.findOneByTestTag("ciacola-doc-node-not-present"),
                        "'not present on beat' flashed during Size drag ($phase)!",
                    )
                }

                // 4. Press on the Size thumb -> 30 steps right -> 350 ms stationary human pause
                //    while holding button down -> 20 steps left -> release!
                storyIn.mouseMove(thumbStart.x, thumbStart.y)
                delay(20)
                storyIn.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                try {
                    delay(16)
                    assertInvariantsEveryStep("press")

                    for (step in 1..30) {
                        val x = lerpInt(thumbStart.x, dragRightTarget.x, step, 30)
                        val y = lerpInt(thumbStart.y, dragRightTarget.y, step, 30)
                        storyIn.mouseMove(x, y)
                        delay(16)
                        assertInvariantsEveryStep("right-$step")
                        rightwardFontSamples += currentProseSize()
                    }

                    // 350 ms mid-drag human pause while still holding mouse button 1 down!
                    val pauseSlices = 7
                    repeat(pauseSlices) { idx ->
                        delay(50)
                        assertInvariantsEveryStep("mid-drag-pause-$idx")
                        assertTrue(
                            elementController.hasActiveTransaction,
                            "Expected open gesture transaction during 350 ms mid-drag pause ($idx)",
                        )
                        pauseFontSamples += currentProseSize()
                    }

                    for (step in 1..20) {
                        val x = lerpInt(dragRightTarget.x, dragLeftTarget.x, step, 20)
                        val y = lerpInt(dragRightTarget.y, dragLeftTarget.y, step, 20)
                        storyIn.mouseMove(x, y)
                        delay(16)
                        assertInvariantsEveryStep("left-$step")
                        leftwardFontSamples += currentProseSize()
                    }
                } finally {
                    storyIn.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                }
                delay(32)
                settle()
                assertInvariantsEveryStep("post-release")
                assertFalse(elementController.hasActiveTransaction, "Gesture transaction must commit on release")

                // Verify continuous monotonic progression: 60 -> >100 -> steady during pause -> [50, 92].
                val peakRightSp = rightwardFontSamples.maxOrNull() ?: initialSliderVal
                assertTrue(
                    peakRightSp > 100f,
                    "Expected rightward Size drag to exceed 100, samples=$rightwardFontSamples",
                )
                for (i in 1 until rightwardFontSamples.size) {
                    assertTrue(
                        rightwardFontSamples[i] >= rightwardFontSamples[i - 1] - 0.55f,
                        "Expected monotonic non-decreasing Size while dragging right: $rightwardFontSamples",
                    )
                }
                val pauseMin = pauseFontSamples.minOrNull() ?: peakRightSp
                val pauseMax = pauseFontSamples.maxOrNull() ?: peakRightSp
                assertTrue(
                    pauseMax - pauseMin <= 0.55f,
                    "Size must stay rock-steady during 350 ms stationary mid-drag pause: $pauseFontSamples",
                )
                for (i in 1 until leftwardFontSamples.size) {
                    assertTrue(
                        leftwardFontSamples[i] <= leftwardFontSamples[i - 1] + 0.55f,
                        "Expected monotonic non-increasing Size while dragging left: $leftwardFontSamples",
                    )
                }
                val settledFontSp = currentProseSize()
                assertTrue(
                    settledFontSp in 50f..92f,
                    "Expected settled Size in [50.0, 92.0] after reverse drag, got $settledFontSp",
                )

                // 5. Immediately grab the Timeline playhead again and scrub smoothly Slide 1 -> Slide 2 -> Slide 1!
                val rulerAfter = automator.waitForNode(tag = "ciacola-doc-timeline-ruler", timeout = 5.seconds)
                val slide2SlideBar = controller.snapshot.snapshot.slides.first { it.sceneIndex == 1 }
                val slide2MidMs = slide2SlideBar.startMs + ((slide2SlideBar.endMs - slide2SlideBar.startMs) / 2)
                val fromPlayheadPt = rulerPoint(
                    rulerAfter,
                    controller.playheadMs,
                    targetWindow = detachedWin,
                    surface = TimelineSurface.Detached,
                )
                val slide2Pt = rulerPoint(
                    rulerAfter,
                    slide2MidMs,
                    targetWindow = detachedWin,
                    surface = TimelineSurface.Detached,
                )
                val backToSlide1Pt = rulerPoint(
                    rulerAfter,
                    180,
                    targetWindow = detachedWin,
                    surface = TimelineSurface.Detached,
                )

                var observedSlide2 = false
                humanContinuousDrag(
                    input = detachedIn,
                    start = fromPlayheadPt,
                    segments = listOf(
                        DragLeg(to = slide2Pt, steps = 25, stepDelayMs = 16L),
                        DragLeg(to = backToSlide1Pt, steps = 25, stepDelayMs = 16L),
                    ),
                ) {
                    assertSame(initialStoryboard, animatic.storyboard, "Cross-slide scrub must not rebuild Storyboard")
                    if (animatic.targetIndex.sceneIndex == 1) {
                        observedSlide2 = true
                    }
                }
                settle()
                assertTrue(observedSlide2, "Expected continuous ruler scrub to cross into Slide 2")
                assertEquals(0, animatic.targetIndex.sceneIndex, "Expected continuous ruler scrub to return to Slide 1")
            }
        }

    @Test
    fun characterByCharacterColorAndTextCopyTypingNeverDropsFocusOrTriggersShortcuts(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = qa.launchStudio(
                preset = DeckThemePreset.StageDark,
                windowSize = DpSize(1360.dp, 900.dp),
                windowPosition = WindowPosition(50.dp, 40.dp),
                timelineDocked = true,
            )

            with(studio) {
                settle()
                val storyIn = inputFor(window)
                val initialStoryboard = animatic.storyboard
                val initialZoomDpPerMs = controller.viewport.dpPerMs

                // Switch to Element tab and select Slide 1 Prose via real click.
                val elementTab = automator.waitForNode(tag = "ciacola-studio-tab-element", timeout = 5.seconds)
                humanClick(storyIn, elementTab.centerOnScreen)
                settle()

                val proseChip = automator.waitForNode(tag = "ciacola-doc-node-item-s1:prose", timeout = 5.seconds)
                humanClick(storyIn, proseChip.centerOnScreen)
                settle()
                assertEquals("s1:prose", elementController.selectedNodeId)

                // 1. Open Element -> Color popover (`ciacola-doc-text-color-token-dropdown`), focus hex field
                //    (`ciacola-doc-text-color-input`) and type shortcut-heavy characters ('b') inside "#22BB88"
                //    without triggering curtains.
                val colorTrigger = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "ciacola-doc-element-inspector",
                    targetTag = "ciacola-doc-text-color-token-dropdown",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                humanClick(storyIn, colorTrigger.centerOnScreen)
                settle()

                val colorNode = automator.waitForNode(tag = "ciacola-doc-text-color-input", timeout = 5.seconds)
                val colorBounds = checkNotNull(colorNode.boundsOnScreen)
                humanClick(
                    storyIn,
                    Point(colorBounds.x + colorBounds.width - 10, colorBounds.y + colorBounds.height / 2),
                )
                automator.waitUntil(description = "color field focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-doc-text-color-input")?.isFocused == true
                }
                assertTrue(textInputTracker.hasFocusedTextInput)

                fun assertColorTypingInvariants(stepLabel: String) {
                    val liveNode = checkNotNull(
                        automator.findOneByTestTag("ciacola-doc-text-color-input"),
                    ) { "Color field unmounted during $stepLabel" }
                    assertTrue(liveNode.isFocused, "Color field lost focus during $stepLabel")
                    assertTrue(textInputTracker.hasFocusedTextInput, "AuthoringTextInputFocusTracker lost focus during $stepLabel")
                    assertEquals(
                        StageCurtain.None,
                        presenter.curtain,
                        "Shortcut 'B'/'W' leaked to Blackout/Whiteout curtain during $stepLabel",
                    )
                    assertFalse(controller.isPlaying, "Timeline started playing unexpectedly during $stepLabel")
                    assertSame(initialStoryboard, animatic.storyboard, "Storyboard rebuilt during $stepLabel")
                }

                // Clear initial hex ("#F4F1EA") and type "22bb88" (contains 'b' twice — Blackout shortcut letter).
                repeat(10) { idx ->
                    humanKeyTap(storyIn, KeyEvent.VK_BACK_SPACE)
                    delay(16)
                    automator.waitForIdle()
                    assertColorTypingInvariants("color-backspace-${idx + 1}")
                }
                "22bb88".forEachIndexed { idx, ch ->
                    humanKeyTap(storyIn, keyCodeForLowercaseOrSpace(ch))
                    delay(16)
                    automator.waitForIdle()
                    assertColorTypingInvariants("color-char-${idx + 1}")
                }
                humanKeyTap(storyIn, KeyEvent.VK_ENTER)
                delay(16)
                humanKeyTap(storyIn, KeyEvent.VK_ESCAPE)
                settle()

                // 2. Scroll back up and click Element -> Text field (`ciacola-doc-text-value-input`),
                //    moving focus cleanly after committing "#22BB88"!
                val textInputNode = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "ciacola-doc-element-inspector",
                    targetTag = "ciacola-doc-text-value-input",
                    viewportBoundsProvider = { window.screenBounds() },
                    scrollUp = true,
                )
                val textBounds = checkNotNull(textInputNode.boundsOnScreen)
                humanClick(
                    storyIn,
                    Point(textBounds.x + textBounds.width / 2, textBounds.y + textBounds.height / 2),
                )
                automator.waitUntil(description = "text copy input focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-doc-text-value-input")?.isFocused == true
                }
                settle()
                assertEquals(
                    "#22BB88",
                    store.document.slides.first { it.id == "beats" }.nodes.first { it.id == "s1:prose" }.text?.color,
                    "Committing color hex field must save '#22BB88'",
                )

                // Move caret deterministically to the end of the multi-line text with Cmd+Down.
                storyIn.keyPress(KeyEvent.VK_META)
                storyIn.keyPress(KeyEvent.VK_DOWN)
                storyIn.keyRelease(KeyEvent.VK_DOWN)
                storyIn.keyRelease(KeyEvent.VK_META)
                delay(16)

                val baselineProse = "A scene begins with one deterministic state."

                fun assertTextCopyInvariants(stepLabel: String) {
                    val liveTextNode = checkNotNull(
                        automator.findOneByTestTag("ciacola-doc-text-value-input"),
                    ) { "Text Copy field unmounted during $stepLabel" }
                    assertTrue(liveTextNode.isFocused, "Text Copy field lost focus during $stepLabel")
                    assertFalse(controller.isPlaying, "Space toggled timeline playback during $stepLabel!")
                    assertEquals(
                        StageCurtain.None,
                        presenter.curtain,
                        "Curtain shortcut triggered while typing in Text Copy ($stepLabel)!",
                    )
                    assertEquals(
                        initialZoomDpPerMs,
                        controller.viewport.dpPerMs,
                        1e-5f,
                        "'-' zoomed timeline while typing in Text Copy ($stepLabel)!",
                    )
                    assertSame(initialStoryboard, animatic.storyboard, "Storyboard rebuilt during $stepLabel!")
                }

                // Exercise '-', 'b', 'w', 't', 'r', Space and clean them back with Backspace.
                val shortcutProbeKeys = listOf(
                    KeyEvent.VK_SPACE,
                    KeyEvent.VK_MINUS,
                    KeyEvent.VK_B,
                    KeyEvent.VK_W,
                    KeyEvent.VK_T,
                    KeyEvent.VK_R,
                )
                shortcutProbeKeys.forEachIndexed { idx, keyCode ->
                    humanKeyTap(storyIn, keyCode)
                    delay(16)
                    automator.waitForIdle()
                    assertTextCopyInvariants("probe-${idx + 1}")
                }
                repeat(shortcutProbeKeys.size) { idx ->
                    humanKeyTap(storyIn, KeyEvent.VK_BACK_SPACE)
                    delay(16)
                    automator.waitForIdle()
                    assertTextCopyInvariants("probe-bs-${idx + 1}")
                }

                // Now type " smooth human test" character-by-character (16 ms apart) and click Apply Text.
                val phrase = " smooth human test"
                phrase.forEachIndexed { idx, ch ->
                    humanKeyTap(storyIn, keyCodeForLowercaseOrSpace(ch))
                    delay(16)
                    automator.waitForIdle()
                    assertTextCopyInvariants("phrase-$idx('$ch')")
                }
                val applyBtn = automator.waitForNode(tag = "ciacola-doc-text-apply-button", timeout = 5.seconds)
                humanClick(storyIn, applyBtn.centerOnScreen)
                settle()

                val expectedFullText = baselineProse + phrase
                assertEquals(
                    expectedFullText,
                    store.document.slides.first { it.id == "beats" }.nodes.first { it.id == "s1:prose" }.text?.value?.plainText,
                    "Every typed character (including spaces and 't') must land in order",
                )
                assertNotNull(
                    stageText(expectedFullText, exact = true),
                    "Live stage MagicText must display the exact typed prose without Storyboard rebuild",
                )
            }
        }

    @Test
    fun themeTypographySizeDragCoalescesIntoSingleUndoPlusMotionDurationSliderWithMidDragPause(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = qa.launchStudio(
                preset = DeckThemePreset.StageDark,
                windowSize = DpSize(1360.dp, 900.dp),
                windowPosition = WindowPosition(50.dp, 40.dp),
                timelineDocked = true,
            )

            with(studio) {
                settle()
                val storyIn = inputFor(window)
                val initialStoryboard = animatic.storyboard

                // 1. Switch to Theme -> Typography, select "body" role (60 sp), and drag Size slider over 25 steps + release.
                onUi { studioWorkspace.toggleDesignSystemEditor() }
                settle()
                assertTrue(studioWorkspace.themeSheetOpen)

                val typoSubTab = automator.waitForNode(tag = "ciacola-doc-theme-tab-typography", timeout = 5.seconds)
                humanClick(storyIn, typoSubTab.centerOnScreen)
                settle()

                onUi { themeController.selectedTypographyRole = "body" }
                settle()

                val sizeSlider = automator.waitForNode(tag = "ciacola-doc-theme-typo-size-slider", timeout = 5.seconds)
                val sizeBounds = checkNotNull(sizeSlider.boundsOnScreen)
                val sizeStart = sliderPointForValue(sizeBounds, value = 60f, min = 10f, max = 140f)
                val sizeEnd = sliderPointForValue(sizeBounds, value = 98f, min = 10f, max = 140f)

                val observedSizes = mutableListOf<Double>()
                humanContinuousDrag(
                    input = storyIn,
                    start = sizeStart,
                    segments = listOf(DragLeg(to = sizeEnd, steps = 25, stepDelayMs = 16L)),
                ) {
                    assertSame(initialStoryboard, animatic.storyboard, "Typography role size drag must not rebuild Storyboard")
                    observedSizes += themeController.currentThemeSpec().typography["body"]?.size ?: 60.0
                }
                settle()
                val appliedSize = themeController.currentThemeSpec().typography["body"]?.size ?: 60.0
                assertTrue(
                    appliedSize > 80.0,
                    "Expected body typography size > 80 after 25-step drag, got $appliedSize (samples=$observedSizes)",
                )
                assertTrue(store.canUndo, "Expected Typography Size drag to record undo state")

                // Verify the entire 25-step drag coalesced into ONE single undo entry, then Redo it via real App Bar clicks.
                val themeUndoBtn = automator.waitForNode(tag = "ciacola-app-bar-undo", timeout = 5.seconds)
                humanClick(storyIn, themeUndoBtn.centerOnScreen)
                settle()
                assertEquals(
                    60.0,
                    themeController.currentThemeSpec().typography["body"]?.size ?: 60.0,
                    0.5,
                    "Single Undo click must revert the entire 25-step Typography Size drag",
                )
                assertFalse(store.canUndo, "Expected single undo entry for the 25-step Typography Size drag")

                val themeRedoBtn = automator.waitForNode(tag = "ciacola-app-bar-redo", timeout = 5.seconds)
                humanClick(storyIn, themeRedoBtn.centerOnScreen)
                settle()
                assertEquals(appliedSize, themeController.currentThemeSpec().typography["body"]?.size ?: 60.0, 0.5)

                // Revert back to clean baseline and close Theme sheet so we can verify single-undo on Animate Duration scrub too!
                humanClick(storyIn, themeUndoBtn.centerOnScreen)
                settle()
                assertFalse(store.canUndo)
                onUi { studioWorkspace.themeSheetOpen = false }
                settle()

                // 2. Switch to Animate tab, select Slide 1 prose build track, scrub Duration label for 15 steps,
                //    hold mouse button stationary for 400 ms, assert gesture transaction stays open, then drag
                //    15 more steps and release -> single undo entry!
                val motionTab = automator.waitForNode(tag = "ciacola-studio-tab-motion", timeout = 5.seconds)
                humanClick(storyIn, motionTab.centerOnScreen)
                settle()
                assertEquals(StudioInspectorTab.Motion, studioWorkspace.activeTab)

                val proseTrackRow = controller.snapshot.snapshot.tracks.first { it.trackId.contains("s1:prose") }
                onUi {
                    controller.selectTrack(proseTrackRow.trackId, proseTrackRow.blocks.firstOrNull()?.id)
                }
                settle()

                val durLabel = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "ciacola-doc-timeline-inspector",
                    targetTag = "ciacola-scrub-label-track-duration",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                val durBounds = checkNotNull(durLabel.boundsOnScreen)
                // Ms-field scrubbing runs in dp (identical on 1x and 2x displays), and AWT points are dp.
                val legAwtDx = 240
                val durStart = Point(durBounds.x + durBounds.width / 2, durBounds.y + durBounds.height / 2)
                val durMid = Point(durStart.x + legAwtDx, durStart.y)
                val durEnd = Point(durMid.x + legAwtDx, durStart.y)

                assertFalse(controller.hasActiveGesture)

                storyIn.mouseMove(durStart.x, durStart.y)
                delay(20)
                storyIn.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                try {
                    delay(16)
                    for (step in 1..15) {
                        val x = lerpInt(durStart.x, durMid.x, step, 15)
                        val y = lerpInt(durStart.y, durMid.y, step, 15)
                        storyIn.mouseMove(x, y)
                        delay(16)
                        assertSame(initialStoryboard, animatic.storyboard, "Duration drag must not rebuild Storyboard")
                    }
                    assertTrue(
                        controller.hasActiveGesture && store.hasOpenTransaction,
                        "Expected controller.hasActiveGesture == true while holding Duration slider down",
                    )

                    // Hold stationary for 400 ms while button stays pressed!
                    repeat(8) {
                        delay(50)
                        assertTrue(
                            controller.hasActiveGesture && store.hasOpenTransaction,
                            "hasActiveGesture must remain true throughout 400 ms mid-drag pause",
                        )
                        assertSame(initialStoryboard, animatic.storyboard)
                    }

                    // Drag 15 more steps further right before releasing.
                    for (step in 1..15) {
                        val x = lerpInt(durMid.x, durEnd.x, step, 15)
                        val y = lerpInt(durMid.y, durEnd.y, step, 15)
                        storyIn.mouseMove(x, y)
                        delay(16)
                        assertSame(initialStoryboard, animatic.storyboard)
                    }
                } finally {
                    storyIn.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                }
                delay(32)
                settle()

                assertFalse(
                    controller.hasActiveGesture || store.hasOpenTransaction,
                    "hasActiveGesture must clear immediately upon releasing Duration slider",
                )
                val updatedRef = controller.snapshot.trackRefsByTrackId[proseTrackRow.trackId] as? DocumentTimelineTrackRef.SlideBuildTrack
                val finalDurMs = updatedRef?.trackPlan?.durationMs ?: 500
                assertTrue(
                    finalDurMs > 1500,
                    "Expected final build duration > 1500 ms after second 15-step leg, got $finalDurMs ms",
                )
                assertTrue(store.canUndo)
                onUi { store.undo() }
                settle()
                assertFalse(store.canUndo, "Two-leg Duration drag with 400 ms pause must coalesce into a single Undo entry")
                assertSame(initialStoryboard, animatic.storyboard)
            }
        }

    // region Human-physics input helpers

    private data class DragLeg(
        val to: Point,
        val steps: Int,
        val stepDelayMs: Long = 16L,
    )

    private fun ComposeWindow.screenBounds(): Rectangle =
        Rectangle(locationOnScreen.x, locationOnScreen.y, width, height)

    private fun sliderPointForValue(
        sliderBounds: Rectangle,
        value: Float,
        min: Float,
        max: Float,
    ): Point {
        val insetPx = 10
        val usableWidth = (sliderBounds.width - insetPx * 2).coerceAtLeast(1)
        val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val x = sliderBounds.x + insetPx + (usableWidth * fraction).roundToInt()
        val y = sliderBounds.y + sliderBounds.height / 2
        return Point(x, y)
    }

    private fun lerpInt(from: Int, to: Int, step: Int, totalSteps: Int): Int =
        from + ((to - from) * step.toDouble() / totalSteps.toDouble()).roundToInt()

    private suspend fun humanClick(input: SyntheticInput, target: Point) {
        input.mouseMove(target.x, target.y)
        delay(16)
        input.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        delay(16)
        input.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        delay(24)
    }

    private suspend fun humanKeyTap(input: SyntheticInput, keyCode: Int) {
        input.keyPress(keyCode)
        delay(8)
        input.keyRelease(keyCode)
    }

    private suspend fun humanContinuousDrag(
        input: SyntheticInput,
        start: Point,
        segments: List<DragLeg>,
        onEachStep: () -> Unit = {},
    ) {
        input.mouseMove(start.x, start.y)
        delay(20)
        input.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        try {
            delay(16)
            onEachStep()
            var origin = start
            for (leg in segments) {
                for (step in 1..leg.steps) {
                    val x = lerpInt(origin.x, leg.to.x, step, leg.steps)
                    val y = lerpInt(origin.y, leg.to.y, step, leg.steps)
                    input.mouseMove(x, y)
                    delay(leg.stepDelayMs)
                    onEachStep()
                }
                origin = leg.to
            }
        } finally {
            input.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        delay(28)
    }

    private suspend fun QaStudio.scrollIntoViewIfNeeded(
        input: SyntheticInput,
        scrollContainerTag: String,
        targetTag: String,
        viewportBoundsProvider: () -> Rectangle,
        scrollUp: Boolean = false,
    ): AutomatorNode {
        val container = automator.waitForNode(tag = scrollContainerTag, timeout = 5.seconds)
        val containerCenter = container.centerOnScreen
        val containerBounds = checkNotNull(container.boundsOnScreen)
        val gutterX = containerBounds.x + 5
        repeat(28) {
            val candidate = automator.findOneByTestTag(targetTag)
            val b = candidate?.boundsOnScreen
            val winBounds = viewportBoundsProvider()
            if (candidate != null &&
                b != null &&
                b.height >= 10 &&
                b.y >= maxOf(containerBounds.y, winBounds.y) + 4 &&
                b.y + b.height <= minOf(containerBounds.y + containerBounds.height, winBounds.y + winBounds.height) - 4
            ) {
                return candidate
            }
            input.mouseMove(gutterX, containerCenter.y)
            input.mouseWheel(if (scrollUp) -4 else 4)
            delay(32)
            automator.waitForIdle()
        }
        return automator.waitForNode(tag = targetTag, timeout = 5.seconds)
    }

    private fun keyCodeForLowercaseOrSpace(ch: Char): Int = when (ch) {
        ' ' -> KeyEvent.VK_SPACE
        '-' -> KeyEvent.VK_MINUS
        '.' -> KeyEvent.VK_PERIOD
        in 'a'..'z' -> KeyEvent.VK_A + (ch - 'a')
        in '0'..'9' -> KeyEvent.VK_0 + (ch - '0')
        else -> error("Unsupported character '$ch'")
    }

    // endregion
}
