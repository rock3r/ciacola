package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.StrokeSpec
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.document.render.DefaultMagicTextV2Renderer
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentStageDirectEditOverlay
import dev.sebastiano.ciacola.qa.QaStudioRule
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
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
 *    NEVER advances the slide; `Commit` (`ciacola-doc-inline-text-commit`) stays inside the stage
 *    and is directly clickable by testTag; real AWT `⌘Enter` commits cleanly.
 * 2. Clicking a live `Checkbox` inside an interactive specimen toggles the checkbox without
 *    hijacking Direct Edit selection, while `Alt+Click` selects the enclosing container card.
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
                automator.waitForNode(tag = "ciacola-doc-inline-text-popover", timeout = 5.seconds)
                automator.waitUntil(description = "inline editor input focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-doc-inline-text-field")?.isFocused == true
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

                // Commit button must be inside the stage viewport and directly clickable by testTag.
                val stageBounds = checkNotNull(automator.findOneByTestTag("ciacola-preview-viewport")).boundsOnScreen
                val commitBtn = checkNotNull(automator.findOneByTestTag("ciacola-doc-inline-text-commit"))
                val commitBounds = commitBtn.boundsOnScreen
                assertTrue(
                    commitBounds.x >= stageBounds.x &&
                        commitBounds.y >= stageBounds.y &&
                        commitBounds.x + commitBounds.width <= stageBounds.x + stageBounds.width + 2 &&
                        commitBounds.y + commitBounds.height <= stageBounds.y + stageBounds.height + 2,
                    "Expected Commit button ($commitBounds) inside stage viewport ($stageBounds)",
                )

                automator.click(commitBtn)
                automator.waitUntil(description = "inline editor closed by Commit click", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-doc-inline-text-popover") == null
                }
                settle()

                val committed = store.document.slides[0].nodes
                    .firstOrNull { it.id == "s1:eyebrow" }
                    ?.text?.value?.plainText
                assertNotNull(committed, "Expected committed text for s1:eyebrow")
                assertTrue(
                    committed.contains(" a b c"),
                    "Expected committed text to contain typed spaces ' a b c', got: '$committed'",
                )
                assertEquals(initialIndex, animatic.currentIndex)

                // Verify real AWT ⌘Enter also commits inline edits cleanly.
                val updatedNode = stageText(committed, exact = true)
                delay(460) // Clear double-click window before reopening.
                awtDoubleClick(updatedNode.centerOnScreen)
                automator.waitForNode(tag = "ciacola-doc-inline-text-popover", timeout = 5.seconds)
                automator.waitUntil(description = "reopened inline input focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-doc-inline-text-field")?.isFocused == true
                }
                tapAwtKey(KeyEvent.VK_END)
                tapAwtKey(KeyEvent.VK_SPACE)
                tapAwtKey(KeyEvent.VK_Z)
                delay(80)
                withHeldKey(KeyEvent.VK_META) {
                    tapAwtKey(KeyEvent.VK_ENTER)
                }
                automator.waitUntil(description = "inline editor closed by ⌘Enter", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-doc-inline-text-popover") == null
                }
                settle()
                val afterEnter = store.document.slides[0].nodes
                    .firstOrNull { it.id == "s1:eyebrow" }
                    ?.text?.value?.plainText
                assertTrue(
                    afterEnter?.endsWith(" z") == true,
                    "Expected ⌘Enter to commit text ending with ' z', got: '$afterEnter'",
                )

                // Wait for stage focus restoration, then press Enter to reopen inline editor cleanly.
                automator.waitUntil(description = "stage focus restored after ⌘Enter commit", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-review-stage")?.isFocused == true
                }
                assertEquals("s1:eyebrow", elementController.selectedNodeId)
                tapAwtKey(KeyEvent.VK_ENTER)
                automator.waitForNode(tag = "ciacola-doc-inline-text-popover", timeout = 5.seconds)
                automator.waitUntil(description = "Enter-opened inline input focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-doc-inline-text-field")?.isFocused == true
                }
                tapAwtKey(KeyEvent.VK_END)
                tapAwtKey(KeyEvent.VK_SPACE)
                tapAwtKey(KeyEvent.VK_X)
                delay(60)
                // Esc cancels cleanly without saving ' x' and restores stage focus without opening Overview.
                tapAwtKey(KeyEvent.VK_ESCAPE)
                automator.waitUntil(description = "inline editor closed by Esc", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-doc-inline-text-popover") == null
                }
                automator.waitUntil(description = "stage focus restored after Esc cancel", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-review-stage")?.isFocused == true
                }
                settle()
                assertEquals(afterEnter, store.document.slides[0].nodes.firstOrNull { it.id == "s1:eyebrow" }?.text?.value?.plainText)
                assertEquals(initialIndex, animatic.currentIndex)

                // Enter reopens and plain Enter commits without advancing the slide, restoring stage focus.
                tapAwtKey(KeyEvent.VK_ENTER)
                automator.waitForNode(tag = "ciacola-doc-inline-text-popover", timeout = 5.seconds)
                automator.waitUntil(description = "reopened inline input focused", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-doc-inline-text-field")?.isFocused == true
                }
                tapAwtKey(KeyEvent.VK_END)
                tapAwtKey(KeyEvent.VK_SPACE)
                tapAwtKey(KeyEvent.VK_Y)
                delay(60)
                tapAwtKey(KeyEvent.VK_ENTER)
                automator.waitUntil(description = "inline editor closed by plain Enter", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-doc-inline-text-popover") == null
                }
                automator.waitUntil(description = "stage focus restored after Enter commit", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-review-stage")?.isFocused == true
                }
                settle()
                val afterPlainEnter = store.document.slides[0].nodes
                    .firstOrNull { it.id == "s1:eyebrow" }
                    ?.text?.value?.plainText
                assertTrue(
                    afterPlainEnter?.endsWith(" y") == true,
                    "Expected plain Enter to commit text ending with ' y', got: '$afterPlainEnter'",
                )
                assertEquals(initialIndex, animatic.currentIndex, "Plain Enter commit must not leak to slide navigation")
                // Stage focus works immediately for slide navigation right after inline edit (Space advances even with element selected).
                tapAwtKey(KeyEvent.VK_SPACE)
                automator.waitUntil(description = "Spacebar advances after stage focus restore", timeout = 5.seconds) {
                    animatic.targetIndex != initialIndex
                }
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun clickingLiveSpecimenCheckboxTogglesWithoutHijackingTimelineOrElementSelection() = runComposeUiTest {
        var checked by mutableStateOf(false)
        val specimens = deckSpecimens {
            specimen("test.liveCheckbox") {
                content {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .stageHitRegion()
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

        val doc = DeckDocument(
            id = "specimen-pick-doc",
            title = "Specimen Pick Doc",
            slides = listOf(
                SlideSpec(
                    id = "s5",
                    nodes = listOf(
                        NodeSpec(
                            id = "s5:card",
                            frame = FrameSpec(x = 120.0, y = 100.0, w = 520.0, h = 240.0),
                            column = ContainerPayload(
                                gap = 12.0,
                                padding = PaddingSpec(all = 16.0),
                                surface = SurfaceSpec(
                                    fill = Fill.Solid("#1E293B"),
                                    stroke = StrokeSpec(color = "#38BDF8", width = 1.5),
                                    cornerRadius = 12.0,
                                ),
                                children = listOf(
                                    NodeSpec(
                                        id = "s5:title",
                                        text = TextPayload(value = RichTextValue.Plain("Live Widget Slide")),
                                    ),
                                    NodeSpec(
                                        id = "s5:checkbox-specimen",
                                        interactive = "pointer",
                                        specimen = SpecimenPayload(ref = "test.liveCheckbox"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val store = DeckDocumentStore(doc)
        val elementController = DocumentElementInspectorController(store = store, specimens = specimens)
        val inspectionRegistry = StageInspectionRegistry()
        val interactiveRegistry = StageInteractiveRegistry()
        val session = RenderSession.forStill(documentSnapshot = doc)
        val storyboard = buildDocumentStoryboard(
            session = session,
            config = DocumentRenderConfig(
                specimens = specimens,
                isEditMode = true,
                stageDecorator = ContentDecorator { inner ->
                    DocumentStageDirectEditOverlay(
                        controller = elementController,
                        enabled = true,
                    ) {
                        inner()
                    }
                },
            ),
        )

        setContent {
            DeckTextRendererProvider(DefaultMagicTextV2Renderer) {
                CompositionLocalProvider(
                    LocalStageInspectionRegistry provides inspectionRegistry,
                    LocalStageInteractiveRegistry provides interactiveRegistry,
                ) {
                    CiacolaJewelTheme(isDark = true) {
                        Box(Modifier.size(960.dp, 540.dp)) {
                            Easel(
                                animatic = rememberAnimatic(
                                    initialIndex = Storyboard.Index(0, 0),
                                    storyboard = { storyboard },
                                ),
                                mode = SceneMode.Story,
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()

        val checkboxCenter = onNodeWithTag("specimen-checkbox").fetchSemanticsNode().boundsInRoot.center

        // 1. Plain click on the live Checkbox must toggle it and MUST NOT hijack Element selection!
        onRoot().performMouseInput {
            click(checkboxCenter)
        }
        waitForIdle()
        assertTrue(checked, "Live Checkbox must toggle on plain click")
        assertNull(elementController.selectedNodeId, "Clicking live Checkbox must NOT hijack Element selection")

        // 2. Alt+Click on the specimen selects the enclosing container card without toggling Checkbox.
        onRoot().performKeyInput { keyDown(Key.AltLeft) }
        onRoot().performMouseInput { click(checkboxCenter) }
        onRoot().performKeyInput { keyUp(Key.AltLeft) }
        waitForIdle()
        assertTrue(checked, "Alt+Click must not toggle live Checkbox")
        assertEquals("s5:card", elementController.selectedNodeId)
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
