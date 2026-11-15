package dev.sebastiano.ciacola.studio

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.sebastiano.ciacola.presenter.StageCurtain
import dev.sebastiano.ciacola.qa.QaStudio
import dev.sebastiano.ciacola.qa.QaStudioRule
import dev.sebastiano.ciacola.qa.SyntheticInput
import dev.sebastiano.ciacola.theme.DeckThemePreset
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
 * End-to-end Spectre suite driving Ciacola Studio with realistic continuous human pointer physics
 * (multi-frame 16 ms interpolated mouse drags, mid-drag human hand pauses longer than debounce
 * thresholds, reverse drags before button release, mouse-wheel scrolling, and character-by-character
 * keyboard typing including global shortcut letters) in real non-focus-stealing ComposeWindows.
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
                includeKeynoteEffectsLab = true,
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
                assertTrue(controller.detachedWindowVisible)
                assertFalse(controller.dockedInStoryWindow)

                // Verify arrow keys work IMMEDIATELY inside the freshly opened F6 window without clicking first!
                humanKeyTap(detachedIn, KeyEvent.VK_RIGHT)
                automator.waitUntil(description = "immediate F6 Right arrow advances to S1:B2", timeout = 5.seconds) {
                    animatic.targetIndex.sceneIndex == 0 && animatic.targetIndex.frameIndex == 1
                }
                settle()
                humanKeyTap(detachedIn, KeyEvent.VK_LEFT)
                automator.waitUntil(description = "immediate F6 Left arrow returns to S1:B1", timeout = 5.seconds) {
                    animatic.targetIndex.sceneIndex == 0 && animatic.targetIndex.frameIndex == 0
                }
                settle()

                val canvas = automator.waitForNode(tag = "ciacola-timeline-canvas", timeout = 5.seconds)
                val startRulerPt = detachedRulerPoint(canvas, detachedWin, timeMs = 12)
                val targetMidSegmentMs = 185
                val midSegmentRulerPt = detachedRulerPoint(canvas, detachedWin, timeMs = targetMidSegmentMs)

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
                    controller.currentPlayheadTimeMs in 140..250,
                    "Expected playhead near ~160–220 ms on Slide 1, got ${controller.currentPlayheadTimeMs} ms",
                )
                assertEquals(0, animatic.targetIndex.sceneIndex)

                // 3. Switch to Element tab and click Prose in the hoisted Story-window inspector via real AWT clicks.
                val elementTab = automator.waitForNode(tag = "ciacola-studio-tab-element", timeout = 5.seconds)
                humanClick(storyIn, elementTab.centerOnScreen)
                settle()
                assertEquals(StudioInspectorTab.Element, studioWorkspace.activeTab)

                val proseChip = automator.waitForNode(tag = "ciacola-element-picker-s1:prose:b0", timeout = 5.seconds)
                humanClick(storyIn, proseChip.centerOnScreen)
                settle()
                assertEquals("s1:prose:b0", elementController.selectedElementId)
                assertNull(automator.findOneByTestTag("ciacola-element-missing-on-beat"))

                val sliderNode = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "ciacola-element-editor-panel",
                    targetTag = "ciacola-element-fontsize-slider",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                val sliderBounds = checkNotNull(sliderNode.boundsOnScreen)
                val initialFontSize = stageInspectionRegistry.nodes["s1:prose:b0"]?.effectiveFontSizeSp ?: 30f
                assertEquals(30f, initialFontSize, 0.5f)

                val thumbStart = sliderPointForValue(sliderBounds, value = initialFontSize, min = 8f, max = 140f)
                val dragRightTarget = sliderPointForValue(sliderBounds, value = 108f, min = 8f, max = 140f)
                val dragLeftTarget = sliderPointForValue(sliderBounds, value = 52f, min = 8f, max = 140f)

                val rightwardFontSamples = mutableListOf<Float>()
                val pauseFontSamples = mutableListOf<Float>()
                val leftwardFontSamples = mutableListOf<Float>()

                fun currentProseFontSp(): Float =
                    elementStore.patchFor("s1:prose:b0")?.fontSizeSp
                        ?: stageInspectionRegistry.nodes["s1:prose:b0"]?.effectiveFontSizeSp
                        ?: initialFontSize

                fun assertInvariantsEveryStep(phase: String) {
                    assertSame(
                        initialStoryboard,
                        animatic.storyboard,
                        "Storyboard rebuilt during Font Size drag ($phase)!",
                    )
                    assertNull(
                        automator.findOneByTestTag("ciacola-element-missing-on-beat"),
                        "'is not on this beat' flashed during Font Size drag ($phase)!",
                    )
                }

                // 4. Press on the Font Size thumb -> 30 steps right -> 350 ms stationary human pause
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
                        rightwardFontSamples += currentProseFontSp()
                    }

                    // 350 ms mid-drag human pause while still holding mouse button 1 down!
                    val pauseSlices = 7
                    repeat(pauseSlices) { idx ->
                        delay(50)
                        assertInvariantsEveryStep("mid-drag-pause-$idx")
                        pauseFontSamples += currentProseFontSp()
                    }

                    for (step in 1..20) {
                        val x = lerpInt(dragRightTarget.x, dragLeftTarget.x, step, 20)
                        val y = lerpInt(dragRightTarget.y, dragLeftTarget.y, step, 20)
                        storyIn.mouseMove(x, y)
                        delay(16)
                        assertInvariantsEveryStep("left-$step")
                        leftwardFontSamples += currentProseFontSp()
                    }
                } finally {
                    storyIn.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                }
                delay(32)
                settle()
                assertInvariantsEveryStep("post-release")

                // Verify continuous monotonic progression: 30 sp -> >80 sp -> steady during pause -> <60 sp.
                val peakRightSp = rightwardFontSamples.maxOrNull() ?: initialFontSize
                assertTrue(
                    peakRightSp > 80f,
                    "Expected rightward Font Size drag to exceed 80 sp, samples=$rightwardFontSamples",
                )
                for (i in 1 until rightwardFontSamples.size) {
                    assertTrue(
                        rightwardFontSamples[i] >= rightwardFontSamples[i - 1] - 0.05f,
                        "Expected monotonic non-decreasing Font Size while dragging right: $rightwardFontSamples",
                    )
                }
                val pauseMin = pauseFontSamples.minOrNull() ?: peakRightSp
                val pauseMax = pauseFontSamples.maxOrNull() ?: peakRightSp
                assertTrue(
                    pauseMax - pauseMin <= 0.5f,
                    "Font Size must stay rock-steady during 350 ms stationary mid-drag pause: $pauseFontSamples",
                )
                for (i in 1 until leftwardFontSamples.size) {
                    assertTrue(
                        leftwardFontSamples[i] <= leftwardFontSamples[i - 1] + 0.05f,
                        "Expected monotonic non-increasing Font Size while dragging left: $leftwardFontSamples",
                    )
                }
                val settledFontSp = currentProseFontSp()
                assertTrue(
                    settledFontSp < 60f && settledFontSp > 36f,
                    "Expected settled Font Size in (36.0, 60.0) sp after reverse drag, got $settledFontSp sp",
                )
                val liveStageSp = stageInspectionRegistry.nodes["s1:prose:b0"]?.effectiveFontSizeSp ?: 0f
                assertEquals(settledFontSp, liveStageSp, 0.5f, "Live stage MagicText must reflect dragged Font Size")

                // 5. Immediately grab the Timeline playhead again and scrub smoothly Slide 1 -> Slide 2 -> Slide 1!
                val canvasAfter = automator.waitForNode(tag = "ciacola-timeline-canvas", timeout = 5.seconds)
                val slide2SlideBar = controller.snapshot.slides.first { it.sceneIndex == 1 }
                val slide2MidMs = slide2SlideBar.startMs + ((slide2SlideBar.endMs - slide2SlideBar.startMs) / 2)
                val fromPlayheadPt = detachedRulerPoint(canvasAfter, detachedWin, controller.currentPlayheadTimeMs)
                val slide2Pt = detachedRulerPoint(canvasAfter, detachedWin, slide2MidMs)
                val backToSlide1Pt = detachedRulerPoint(canvasAfter, detachedWin, 180)

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
                assertEquals("s1:prose:b0", elementController.selectedElementId)
                assertNull(
                    automator.findOneByTestTag("ciacola-element-missing-on-beat"),
                    "Returning to Slide 1 must keep s1:prose selected without missing-on-beat flash",
                )

                // 6. Also verify hoisted inspector hex typing in detached mode never loses focus.
                val detachedHexNode = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "ciacola-element-editor-panel",
                    targetTag = "ciacola-element-textcolor-hex",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                val detachedHexBounds = checkNotNull(detachedHexNode.boundsOnScreen)
                humanClick(
                    storyIn,
                    Point(detachedHexBounds.x + detachedHexBounds.width - 12, detachedHexBounds.y + detachedHexBounds.height / 2),
                )
                automator.waitUntil(description = "detached hex focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-element-textcolor-hex")?.isFocused == true
                }
                repeat(6) {
                    humanKeyTap(storyIn, KeyEvent.VK_BACK_SPACE)
                    delay(16)
                    automator.waitForIdle()
                    assertTrue(
                        automator.findOneByTestTag("ciacola-element-textcolor-hex")?.isFocused == true,
                        "Detached hex field must retain focus across Backspace",
                    )
                }
                listOf(KeyEvent.VK_3, KeyEvent.VK_8, KeyEvent.VK_B, KeyEvent.VK_D, KeyEvent.VK_F, KeyEvent.VK_8).forEach { kc ->
                    humanKeyTap(storyIn, kc)
                    delay(16)
                    automator.waitForIdle()
                    assertTrue(
                        automator.findOneByTestTag("ciacola-element-textcolor-hex")?.isFocused == true,
                        "Detached hex field must retain focus across hex typing",
                    )
                    assertSame(initialStoryboard, animatic.storyboard)
                }
                settle()
                assertEquals(0xFF38BDF8L, elementStore.patchFor("s1:prose:b0")?.textColorArgb)
            }
        }

    @Test
    fun characterByCharacterHexAndTextCopyTypingNeverDropsFocusOccludesOrTriggersShortcuts(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = qa.launchStudio(
                preset = DeckThemePreset.StageDark,
                windowSize = DpSize(1360.dp, 900.dp),
                windowPosition = WindowPosition(50.dp, 40.dp),
                timelineDocked = true,
                includeKeynoteEffectsLab = true,
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

                val proseChip = automator.waitForNode(tag = "ciacola-element-picker-s1:prose:b0", timeout = 5.seconds)
                humanClick(storyIn, proseChip.centerOnScreen)
                settle()
                assertEquals("s1:prose:b0", elementController.selectedElementId)

                // 1. Click near the right end of Element -> Text Color hex field (#F4F1EA) so caret sits at the end.
                val hexNode = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "ciacola-element-editor-panel",
                    targetTag = "ciacola-element-textcolor-hex",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                val hexBounds = checkNotNull(hexNode.boundsOnScreen)
                humanClick(
                    storyIn,
                    Point(hexBounds.x + hexBounds.width - 12, hexBounds.y + hexBounds.height / 2),
                )
                automator.waitUntil(description = "hex field focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-element-textcolor-hex")?.isFocused == true
                }
                assertTrue(textInputTracker.hasFocusedTextInput)

                fun assertHexTypingInvariants(stepLabel: String) {
                    val liveHexNode = checkNotNull(
                        automator.findOneByTestTag("ciacola-element-textcolor-hex"),
                    ) { "Hex field unmounted during $stepLabel" }
                    assertTrue(liveHexNode.isFocused, "Hex field lost focus during $stepLabel")
                    assertTrue(textInputTracker.hasFocusedTextInput, "AuthoringTextInputFocusTracker lost focus during $stepLabel")
                    assertEquals(
                        StageCurtain.None,
                        presenter.curtain,
                        "Shortcut 'B'/'W' leaked to Blackout/Whiteout curtain during $stepLabel",
                    )
                    assertFalse(controller.isPlaying, "Timeline started playing unexpectedly during $stepLabel")
                    assertSame(initialStoryboard, animatic.storyboard, "Storyboard rebuilt during $stepLabel")
                    assertTrue(
                        automator.findByText("Enter #RRGGBB or #AARRGGBB hex color", exact = true).isEmpty(),
                        "Occluding hover Tooltip appeared over hex field during $stepLabel",
                    )
                }

                // Press Backspace 6 times (#F4F1EA -> #), 16 ms apart.
                repeat(6) { idx ->
                    humanKeyTap(storyIn, KeyEvent.VK_BACK_SPACE)
                    delay(16)
                    automator.waitForIdle()
                    assertHexTypingInvariants("hex-backspace-${idx + 1}")
                }
                // Partial "#" must display inline validation without dropping focus.
                assertNotNull(
                    automator.findOneByTestTag("ciacola-element-textcolor-hex-error"),
                    "Expected inline validation message while hex is incomplete ('#')",
                )

                // Type F, F, B, B, 0, 0 one key at a time (16 ms apart) — 'B' is the Blackout shortcut key!
                val hexKeys = listOf(
                    KeyEvent.VK_F,
                    KeyEvent.VK_F,
                    KeyEvent.VK_B,
                    KeyEvent.VK_B,
                    KeyEvent.VK_0,
                    KeyEvent.VK_0,
                )
                hexKeys.forEachIndexed { idx, keyCode ->
                    humanKeyTap(storyIn, keyCode)
                    delay(16)
                    automator.waitForIdle()
                    assertHexTypingInvariants("hex-char-${idx + 1}")
                }
                settle()
                assertHexTypingInvariants("hex-complete")
                assertNull(
                    automator.findOneByTestTag("ciacola-element-textcolor-hex-error"),
                    "Inline hex error must clear as soon as #FFBB00 is valid",
                )
                assertEquals(
                    0xFFFFBB00L,
                    elementStore.patchFor("s1:prose:b0")?.textColorArgb,
                    "Expected #FFBB00 live override on s1:prose:b0",
                )
                assertEquals(
                    Color(0xFFFFBB00),
                    stageInspectionRegistry.nodes["s1:prose:b0"]?.effectiveForeground,
                    "Stage prose foreground must update live to #FFBB00 without Storyboard rebuild",
                )

                // 2. Scroll back up if needed, click at the end of Element -> Text Copy, and type
                //    shortcut-heavy characters (" -bwtr" + 6 Backspaces) followed by " smooth human test"!
                val textInputNode = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "ciacola-element-editor-panel",
                    targetTag = "ciacola-element-text-input",
                    viewportBoundsProvider = { window.screenBounds() },
                    scrollUp = true,
                )
                val textBounds = checkNotNull(textInputNode.boundsOnScreen)
                humanClick(
                    storyIn,
                    Point(textBounds.x + textBounds.width - 14, textBounds.y + textBounds.height - 12),
                )
                automator.waitUntil(description = "text copy input focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-element-text-input")?.isFocused == true
                }
                // Move caret deterministically to the very end of the multi-line text with Cmd+Down / End.
                storyIn.keyPress(KeyEvent.VK_META)
                storyIn.keyPress(KeyEvent.VK_DOWN)
                storyIn.keyRelease(KeyEvent.VK_DOWN)
                storyIn.keyRelease(KeyEvent.VK_META)
                delay(16)

                val baselineProse = stageInspectionRegistry.nodes["s1:prose:b0"]?.resolvedText
                    ?: "A scene begins with one deterministic state."

                fun assertTextCopyInvariants(stepLabel: String) {
                    val liveTextNode = checkNotNull(
                        automator.findOneByTestTag("ciacola-element-text-input"),
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

                // Now type " smooth human test" character-by-character (16 ms apart).
                val phrase = " smooth human test"
                phrase.forEachIndexed { idx, ch ->
                    humanKeyTap(storyIn, keyCodeForLowercaseOrSpace(ch))
                    delay(16)
                    automator.waitForIdle()
                    assertTextCopyInvariants("phrase-$idx('$ch')")
                }
                settle()

                val expectedFullText = baselineProse + phrase
                assertEquals(
                    expectedFullText,
                    elementStore.patchFor("s1:prose:b0")?.text,
                    "Every typed character (including spaces and 't') must land in order",
                )
                assertEquals(
                    expectedFullText,
                    stageInspectionRegistry.nodes["s1:prose:b0"]?.resolvedText,
                    "Live stage MagicText must display the exact typed prose without Storyboard rebuild",
                )

                // 3. Switch to Motion tab, select s0:magic-text, click Bezier y1 field (focus alone must NOT
                //    mutate curve!), and type "-0.25" character-by-character without '-' zooming the timeline.
                val motionTab = automator.waitForNode(tag = "ciacola-studio-tab-motion", timeout = 5.seconds)
                humanClick(storyIn, motionTab.centerOnScreen)
                settle()
                val magicTrack = scrollTrackRowIntoView(
                    input = storyIn,
                    targetTrackRowTag = "ciacola-track-row-s0:magic-text",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                humanClick(storyIn, magicTrack.centerOnScreen)
                settle()
                assertNull(overridesStore.data().tracks["s0:magic-text"]?.curve)

                val y1Node = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "ciacola-studio-unified-inspector",
                    targetTag = "ciacola-bezier-input-y1",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                val y1Bounds = checkNotNull(y1Node.boundsOnScreen)
                humanClick(
                    storyIn,
                    Point(y1Bounds.x + y1Bounds.width - 8, y1Bounds.y + y1Bounds.height / 2),
                )
                automator.waitUntil(description = "bezier y1 focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-bezier-input-y1")?.isFocused == true
                }
                settle()
                assertNull(
                    overridesStore.data().tracks["s0:magic-text"]?.curve,
                    "Focusing Bezier y1 field alone must not prematurely commit a Custom curve override",
                )
                repeat(5) {
                    humanKeyTap(storyIn, KeyEvent.VK_BACK_SPACE)
                    delay(16)
                }
                "-0.25".forEach { ch ->
                    humanKeyTap(storyIn, keyCodeForLowercaseOrSpace(ch))
                    delay(16)
                    automator.waitForIdle()
                    assertEquals(
                        initialZoomDpPerMs,
                        controller.viewport.dpPerMs,
                        1e-5f,
                        "Typing '$ch' in Bezier y1 field must never zoom timeline!",
                    )
                    assertSame(initialStoryboard, animatic.storyboard)
                }
                settle()
                assertEquals(
                    -0.25f,
                    overridesStore.data().tracks["s0:magic-text"]?.curve?.y1 ?: 0f,
                    1e-3f,
                )
            }
        }

    @Test
    fun themeGlobalFontScaleAnd2dColorDragPlusMotionOffsetSliderWithMidDragPause(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = qa.launchStudio(
                preset = DeckThemePreset.StageDark,
                windowSize = DpSize(1360.dp, 900.dp),
                windowPosition = WindowPosition(50.dp, 40.dp),
                timelineDocked = true,
                includeKeynoteEffectsLab = true,
            )

            with(studio) {
                settle()
                val storyIn = inputFor(window)
                val initialStoryboard = animatic.storyboard

                // 1. Switch to Theme -> Typography and smoothly drag Global Font Scale over 25 steps + release.
                val themeTab = automator.waitForNode(tag = "ciacola-studio-tab-theme", timeout = 5.seconds)
                humanClick(storyIn, themeTab.centerOnScreen)
                settle()
                assertEquals(StudioInspectorTab.Theme, studioWorkspace.activeTab)

                val typoSubTab = automator.waitForNode(tag = "design-tab-typography", timeout = 5.seconds)
                humanClick(storyIn, typoSubTab.centerOnScreen)
                settle()

                val unscaledProseHeight = checkNotNull(
                    stageInspectionRegistry.nodes["s1:prose:b0"]?.stageBounds,
                ).height
                val scaleSlider = automator.waitForNode(tag = "design-typo-global-scale-slider", timeout = 5.seconds)
                val scaleBounds = checkNotNull(scaleSlider.boundsOnScreen)
                val scaleStart = sliderPointForValue(scaleBounds, value = 1.00f, min = 0.75f, max = 1.50f)
                val scaleEnd = sliderPointForValue(scaleBounds, value = 1.32f, min = 0.75f, max = 1.50f)

                val observedScales = mutableListOf<Float>()
                humanContinuousDrag(
                    input = storyIn,
                    start = scaleStart,
                    segments = listOf(DragLeg(to = scaleEnd, steps = 25, stepDelayMs = 16L)),
                ) {
                    assertSame(initialStoryboard, animatic.storyboard, "Global Font Scale drag must not rebuild Storyboard")
                    observedScales += designStore.overridesFor(DeckThemePreset.StageDark).typography.globalFontScale ?: 1.0f
                }
                settle()
                val appliedScale = designStore.overridesFor(DeckThemePreset.StageDark).typography.globalFontScale ?: 1.0f
                assertTrue(
                    appliedScale > 1.15f,
                    "Expected Global Font Scale > 1.15x after 25-step drag, got $appliedScale (samples=$observedScales)",
                )
                val scaledProseHeight = checkNotNull(
                    stageInspectionRegistry.nodes["s1:prose:b0"]?.stageBounds,
                ).height
                assertTrue(
                    scaledProseHeight > unscaledProseHeight * 1.12f,
                    "Expected Slide 1 Prose stage height to scale live via LocalDensity.fontScale (unscaled=$unscaledProseHeight, scaled=$scaledProseHeight, scale=$appliedScale)",
                )
                assertTrue(designStore.canUndo, "Expected Global Font Scale drag to record undo state")

                // Verify the entire 25-step drag coalesced into ONE single undo entry, then Redo it via real clicks.
                val themeUndoBtn = automator.waitForNode(tag = "design-editor-undo", timeout = 5.seconds)
                humanClick(storyIn, themeUndoBtn.centerOnScreen)
                settle()
                assertNull(
                    designStore.overridesFor(DeckThemePreset.StageDark).typography.globalFontScale,
                    "Single Undo click must revert the entire 25-step Global Font Scale drag",
                )
                assertFalse(designStore.canUndo, "Expected single undo entry for the 25-step Global Font Scale drag")

                val themeRedoBtn = automator.waitForNode(tag = "design-editor-redo", timeout = 5.seconds)
                humanClick(storyIn, themeRedoBtn.centerOnScreen)
                settle()
                assertEquals(appliedScale, designStore.overridesFor(DeckThemePreset.StageDark).typography.globalFontScale)

                // 2. Switch to Colors sub-tab, expand Accent picker, and drag smoothly across 2D SV canvas over 20 steps.
                val colorsSubTab = automator.waitForNode(tag = "design-tab-colors", timeout = 5.seconds)
                humanClick(storyIn, colorsSubTab.centerOnScreen)
                settle()

                val accentSwatch = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "design-system-editor-panel",
                    targetTag = "design-color-accent-swatch",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                humanClick(storyIn, accentSwatch.centerOnScreen)
                settle()

                val svCanvas = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "design-system-editor-panel",
                    targetTag = "design-color-accent-sv-canvas",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                val svBounds = checkNotNull(svCanvas.boundsOnScreen)
                val svFrom = Point(svBounds.x + (svBounds.width * 0.20).roundToInt(), svBounds.y + (svBounds.height * 0.75).roundToInt())
                val svTo = Point(svBounds.x + (svBounds.width * 0.88).roundToInt(), svBounds.y + (svBounds.height * 0.16).roundToInt())
                val initialAccentArgb = designStore.overridesFor(DeckThemePreset.StageDark).colors.accentArgb

                val distinctAccents = LinkedHashSet<Long>()
                humanContinuousDrag(
                    input = storyIn,
                    start = svFrom,
                    segments = listOf(DragLeg(to = svTo, steps = 20, stepDelayMs = 16L)),
                ) {
                    assertSame(initialStoryboard, animatic.storyboard, "2D SV color drag must not rebuild Storyboard")
                    designStore.overridesFor(DeckThemePreset.StageDark).colors.accentArgb?.let(distinctAccents::add)
                }
                settle()
                val finalAccentArgb = designStore.overridesFor(DeckThemePreset.StageDark).colors.accentArgb
                assertNotNull(finalAccentArgb, "Expected Accent color override after 2D SV drag")
                assertTrue(
                    finalAccentArgb != initialAccentArgb && distinctAccents.size >= 5,
                    "Expected smooth continuous Accent color updates across 20-step 2D SV drag (distinct=${distinctAccents.size})",
                )

                // 3. Switch to Motion tab, select a build track via real scroll + click, drag Offset X for 15 steps,
                //    hold mouse button stationary for 400 ms (> INDEX_DEBOUNCE_MS = 300 ms!), assert isInEditGesture
                //    deferred re-indexing, then drag 15 more steps and release!
                val motionTab = automator.waitForNode(tag = "ciacola-studio-tab-motion", timeout = 5.seconds)
                humanClick(storyIn, motionTab.centerOnScreen)
                settle()
                assertEquals(StudioInspectorTab.Motion, studioWorkspace.activeTab)

                val stampTrackRow = scrollTrackRowIntoView(
                    input = storyIn,
                    targetTrackRowTag = "ciacola-track-row-lab-immutable-stamp",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                humanClick(storyIn, stampTrackRow.centerOnScreen)
                settle()
                assertEquals("lab-immutable-stamp", controller.selectedTrackId)

                val offsetXSlider = scrollIntoViewIfNeeded(
                    input = storyIn,
                    scrollContainerTag = "ciacola-studio-unified-inspector",
                    targetTag = "ciacola-inspector-offset-x-slider",
                    viewportBoundsProvider = { window.screenBounds() },
                )
                val offsetBounds = checkNotNull(offsetXSlider.boundsOnScreen)
                val offsetStart = sliderPointForValue(offsetBounds, value = 0f, min = -96f, max = 96f)
                val offsetMid = sliderPointForValue(offsetBounds, value = 38f, min = -96f, max = 96f)
                val offsetEnd = sliderPointForValue(offsetBounds, value = 76f, min = -96f, max = 96f)

                val snapshotBeforeGesture = controller.snapshot
                assertFalse(overridesStore.isInEditGesture)

                storyIn.mouseMove(offsetStart.x, offsetStart.y)
                delay(20)
                storyIn.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                try {
                    delay(16)
                    for (step in 1..15) {
                        val x = lerpInt(offsetStart.x, offsetMid.x, step, 15)
                        val y = lerpInt(offsetStart.y, offsetMid.y, step, 15)
                        storyIn.mouseMove(x, y)
                        delay(16)
                        assertSame(initialStoryboard, animatic.storyboard, "Offset X drag must not rebuild Storyboard")
                    }
                    assertTrue(
                        overridesStore.isInEditGesture,
                        "Expected overridesStore.isInEditGesture == true while holding Offset X slider down",
                    )
                    val midOffsetX = overridesStore.data().tracks["lab-immutable-stamp"]?.spatial?.offsetXDp ?: 0f
                    assertTrue(midOffsetX > 12f, "Expected positive Offset X mid-drag, got $midOffsetX dp")

                    // Hold stationary for 400 ms (> INDEX_DEBOUNCE_MS = 300 ms) while button stays pressed!
                    repeat(8) {
                        delay(50)
                        assertTrue(
                            overridesStore.isInEditGesture,
                            "isInEditGesture must remain true throughout 400 ms mid-drag pause",
                        )
                        assertSame(
                            snapshotBeforeGesture,
                            controller.snapshot,
                            "Offscreen indexDeckTimeline must NOT fire during 400 ms mid-drag pause!",
                        )
                        assertSame(initialStoryboard, animatic.storyboard)
                    }

                    // Drag 15 more steps further right before releasing.
                    for (step in 1..15) {
                        val x = lerpInt(offsetMid.x, offsetEnd.x, step, 15)
                        val y = lerpInt(offsetMid.y, offsetEnd.y, step, 15)
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
                    overridesStore.isInEditGesture,
                    "isInEditGesture must clear immediately upon releasing Offset X slider",
                )
                val finalOffsetX = overridesStore.data().tracks["lab-immutable-stamp"]?.spatial?.offsetXDp ?: 0f
                assertTrue(
                    finalOffsetX > 45f,
                    "Expected final Offset X > 45 dp after second 15-step leg, got $finalOffsetX dp",
                )
                assertTrue(overridesStore.canUndo)
                assertSame(initialStoryboard, animatic.storyboard)
            }
        }

    // region Human-physics input helpers

    private data class DragLeg(
        val to: Point,
        val steps: Int,
        val stepDelayMs: Long = 16L,
    )

    private fun QaStudio.detachedRulerPoint(
        canvas: AutomatorNode,
        detachedWin: ComposeWindow,
        timeMs: Int,
    ): Point {
        val bounds = checkNotNull(canvas.boundsOnScreen) { "Detached timeline canvas has no screen bounds" }
        val px = controller.viewport.xForTime(timeMs.toFloat(), TimelineSurface.Detached)
        val x = (bounds.x + (px / pixelScale(detachedWin)).roundToInt())
            .coerceIn(bounds.x + 8, bounds.x + bounds.width - 8)
        return Point(x, bounds.y + 39)
    }

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
        repeat(16) {
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
            input.mouseMove(containerCenter.x, containerCenter.y)
            input.mouseWheel(if (scrollUp) -4 else 4)
            delay(32)
            automator.waitForIdle()
        }
        return automator.waitForNode(tag = targetTag, timeout = 5.seconds)
    }

    private suspend fun QaStudio.scrollTrackRowIntoView(
        input: SyntheticInput,
        targetTrackRowTag: String,
        viewportBoundsProvider: () -> Rectangle,
    ): AutomatorNode {
        val firstRow = automator.findOneByTestTag("ciacola-track-row-shared:foundation-shared-card")
            ?: automator.waitForNode(tag = "ciacola-timeline-studio", timeout = 5.seconds)
        val wheelAnchor = firstRow.centerOnScreen
        repeat(18) {
            val candidate = automator.findOneByTestTag(targetTrackRowTag)
            val b = candidate?.boundsOnScreen
            val winBounds = viewportBoundsProvider()
            if (candidate != null &&
                b != null &&
                b.height >= 12 &&
                b.y >= winBounds.y + 8 &&
                b.y + b.height <= winBounds.y + winBounds.height - 8
            ) {
                return candidate
            }
            input.mouseMove(wheelAnchor.x, wheelAnchor.y)
            input.mouseWheel(4)
            delay(32)
            automator.waitForIdle()
        }
        return automator.waitForNode(tag = targetTrackRowTag, timeout = 5.seconds)
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
