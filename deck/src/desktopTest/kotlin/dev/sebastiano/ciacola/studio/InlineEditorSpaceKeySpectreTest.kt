package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.effects.DeckSurfaceCard
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.element.ElementEditorController
import dev.sebastiano.ciacola.inspector.element.ElementOverridesStore
import dev.sebastiano.ciacola.inspector.stageInteractive
import dev.sebastiano.ciacola.qa.QaStudioRule
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.MotionPathPreviewState
import dev.sebastiano.ciacola.timeline.TimelineOverridesStore
import dev.sebastiano.ciacola.timeline.TimelineStageSelection
import dev.sebastiano.ciacola.timeline.computeSelectionChipOffset
import dev.sebastiano.ciacola.timeline.createTimelineStageDecorator
import dev.sebastiano.ciacola.timeline.timelineTarget
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Rule

/**
 * Real-window Spectre E2E test verifying:
 * 1. Real AWT `KEY_PRESSED VK_SPACE` x3 inside the inline stage text editor inserts spaces and
 *    NEVER advances the slide; `Apply` (`ciacola-inline-stage-editor-save`) stays inside the stage
 *    and is directly clickable by testTag; real AWT `VK_ENTER` commits cleanly.
 * 2. Clicking a live `Checkbox` inside a `stageInteractive` specimen toggles the checkbox without
 *    hijacking either Timeline stage picking or Element direct-edit selection.
 */
class InlineEditorSpaceKeySpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun realAwtSpacebarInsideInlineEditorInsertsSpacesWithoutAdvancingSlideAndApplyClicksByTag(): Unit =
        runSpectreTest(timeout = 90.seconds) {
            val studio = qa.launchStudio(
                preset = DeckThemePreset.StageDark,
                windowSize = DpSize(1360.dp, 880.dp),
                timelineDocked = true,
                includeKeynoteEffectsLab = false,
            )

            with(studio) {
                settle()

                val elementTab = checkNotNull(automator.findOneByTestTag("ciacola-studio-tab-element"))
                automator.click(elementTab)
                settle()
                assertEquals(StudioInspectorTab.Element, studioWorkspace.activeTab)

                val initialIndex = animatic.currentIndex
                val eyebrowNode = stageText("FOUNDATION / PROVISIONAL", exact = true)
                val target = eyebrowNode.centerOnScreen

                // Real AWT double-click on stage opens the inline stage text editor.
                awtDoubleClick(target)
                automator.waitForNode(tag = "ciacola-inline-stage-editor", timeout = 5.seconds)
                automator.waitUntil(description = "inline editor input focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-inline-stage-editor-input")?.isFocused == true
                }
                delay(120)

                // Move cursor to end, then dispatch 3 real AWT VK_SPACE taps interleaved with letters.
                tapAwtKey(KeyEvent.VK_END)
                delay(40)
                tapAwtKey(KeyEvent.VK_SPACE)
                tapAwtKey(KeyEvent.VK_A)
                tapAwtKey(KeyEvent.VK_SPACE)
                tapAwtKey(KeyEvent.VK_B)
                tapAwtKey(KeyEvent.VK_SPACE)
                tapAwtKey(KeyEvent.VK_C)
                delay(160)
                automator.waitForIdle()

                // Slide MUST NOT have advanced on any of the 3 Spacebar presses.
                assertEquals(initialIndex, animatic.currentIndex, "Spacebar inside inline editor must not advance slide")
                assertEquals(initialIndex, animatic.targetIndex, "Spacebar inside inline editor must not start slide transition")

                // Apply button must be inside the stage viewport and directly clickable by testTag.
                val stageBounds = checkNotNull(automator.findOneByTestTag("ciacola-preview-viewport")).boundsOnScreen
                val applyBtn = checkNotNull(automator.findOneByTestTag("ciacola-inline-stage-editor-save"))
                val applyBounds = applyBtn.boundsOnScreen
                assertTrue(
                    applyBounds.x >= stageBounds.x &&
                        applyBounds.y >= stageBounds.y &&
                        applyBounds.x + applyBounds.width <= stageBounds.x + stageBounds.width + 2 &&
                        applyBounds.y + applyBounds.height <= stageBounds.y + stageBounds.height + 2,
                    "Expected Apply button ($applyBounds) inside stage viewport ($stageBounds)",
                )

                automator.click(applyBtn)
                automator.waitUntil(description = "inline editor closed by Apply click", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-inline-stage-editor") == null
                }
                settle()

                val committed = elementStore.patchFor("s1:eyebrow")?.text
                assertNotNull(committed, "Expected committed text patch for s1:eyebrow")
                assertTrue(
                    committed.contains(" a b c"),
                    "Expected committed text to contain typed spaces ' a b c', got: '$committed'",
                )
                assertEquals(initialIndex, animatic.currentIndex)

                // Verify real AWT VK_ENTER also commits inline edits cleanly.
                val updatedNode = stageText(committed, exact = true)
                delay(460) // Clear double-click window before reopening.
                awtDoubleClick(updatedNode.centerOnScreen)
                automator.waitForNode(tag = "ciacola-inline-stage-editor", timeout = 5.seconds)
                automator.waitUntil(description = "reopened inline input focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-inline-stage-editor-input")?.isFocused == true
                }
                tapAwtKey(KeyEvent.VK_END)
                tapAwtKey(KeyEvent.VK_SPACE)
                tapAwtKey(KeyEvent.VK_Z)
                delay(80)
                tapAwtKey(KeyEvent.VK_ENTER)
                automator.waitUntil(description = "inline editor closed by VK_ENTER", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-inline-stage-editor") == null
                }
                settle()
                val afterEnter = elementStore.patchFor("s1:eyebrow")?.text
                assertTrue(
                    afterEnter?.endsWith(" z") == true,
                    "Expected VK_ENTER to commit text ending with ' z', got: '$afterEnter'",
                )
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun clickingLiveSpecimenCheckboxTogglesWithoutHijackingTimelineOrElementSelection() = runComposeUiTest {
        val dir = Files.createTempDirectory("ciacola-timeline-specimen-pick")
        val elementStore = ElementOverridesStore(dir)
        val timelineStore = TimelineOverridesStore(dir)
        val elementController = ElementEditorController(elementStore).apply { editorActive = true }
        val pickedTimelineTracks = mutableListOf<String?>()
        val timelineSelection = TimelineStageSelection().apply {
            pickingEnabled = true
            onPick = { trackId ->
                selectedTrackId = trackId
                pickedTimelineTracks += trackId
            }
        }
        val timelineDecorator = createTimelineStageDecorator(
            loopClock = DeckLoopClock(),
            motionPathPreview = MotionPathPreviewState(),
            overridesStore = timelineStore,
            stageSelection = timelineSelection,
        )
        var checked by mutableStateOf(false)

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(
                    LocalSceneMode provides SceneMode.Story,
                    LocalElementOverrides provides elementStore.data().also { elementStore.revision },
                ) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        timelineDecorator.decorate {
                            elementController.stageDecorator.decorate {
                                Box(Modifier.fillMaxSize()) {
                                    DeckSurfaceCard(
                                        elementId = "s5:card",
                                        label = "Specimen Card",
                                        fillColor = Color(0xFF1E293B),
                                        borderColor = Color(0xFF38BDF8),
                                        modifier = Modifier
                                            .offset(x = 120.dp, y = 100.dp)
                                            .size(360.dp, 200.dp)
                                            .timelineTarget("s5:card-track"),
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            DeckEditableText(
                                                elementId = "s5:title",
                                                text = "Live Widget Slide",
                                                color = Color.White,
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .stageInteractive("s5:checkbox-specimen", "Live Checkbox")
                                                    .testTag("specimen-interactive-row"),
                                            ) {
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { checked = it },
                                                    modifier = Modifier.testTag("specimen-checkbox"),
                                                )
                                                Text("Toggle Live State", color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()

        val checkboxCenter = onNodeWithTag("specimen-checkbox").fetchSemanticsNode().boundsInRoot.center

        // 1. Plain click on the live Checkbox must toggle it and MUST NOT hijack Timeline or Element selection!
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            click(checkboxCenter)
        }
        waitForIdle()
        assertTrue(checked, "Live Checkbox must toggle on plain click")
        assertTrue(pickedTimelineTracks.isEmpty(), "Clicking live Checkbox must NOT hijack Timeline selection")
        assertNull(timelineSelection.selectedTrackId)
        assertNull(elementController.selectedElementId, "Clicking live Checkbox must NOT hijack Element selection")

        // 2. Alt+Click on the specimen selects the enclosing DeckSurfaceCard without toggling Checkbox.
        onNodeWithTag("ciacola-direct-edit-overlay").performKeyInput { keyDown(Key.AltLeft) }
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput { click(checkboxCenter) }
        onNodeWithTag("ciacola-direct-edit-overlay").performKeyInput { keyUp(Key.AltLeft) }
        waitForIdle()
        assertTrue(checked, "Alt+Click must not toggle live Checkbox")
        assertEquals("s5:card", elementController.selectedElementId)
        assertTrue(pickedTimelineTracks.isEmpty(), "Alt+Click consumed by Element picker must not hijack Timeline")

        // 3. Verify Timeline selection chip clears target top border by >= 6px gap (or top-inside for full-height).
        val normalChip = computeSelectionChipOffset(
            anchor = Rect(200f, 180f, 800f, 520f),
            chipSize = Size(160f, 34f),
            canvasSize = Size(1920f, 1080f),
            gapPx = 12f,
            marginPx = 8f,
        )
        assertTrue(normalChip.y + 34f <= 180f - 12f + 0.1f, "Expected chip above top border with full gap, got $normalChip")

        val fullHeightChip = computeSelectionChipOffset(
            anchor = Rect(0f, 0f, 1920f, 1080f),
            chipSize = Size(160f, 34f),
            canvasSize = Size(1920f, 1080f),
            gapPx = 12f,
            marginPx = 8f,
        )
        assertTrue(fullHeightChip.y >= 12f, "Expected full-height fallback inside-top clear of top border, got $fullHeightChip")
    }

    private suspend fun dev.sebastiano.ciacola.qa.QaStudio.awtDoubleClick(at: Point) {
        val mask = InputEvent.BUTTON1_DOWN_MASK
        awtClick(at, buttonMask = mask)
        delay(60)
        awtClick(at, buttonMask = mask)
    }

    private suspend fun dev.sebastiano.ciacola.qa.QaStudio.tapAwtKey(keyCode: Int) {
        awtKey(pressed = true, keyCode = keyCode)
        delay(24)
        awtKey(pressed = false, keyCode = keyCode)
        delay(28)
    }
}
