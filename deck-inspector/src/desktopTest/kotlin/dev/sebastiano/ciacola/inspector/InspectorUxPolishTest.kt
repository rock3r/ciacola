package dev.sebastiano.ciacola.inspector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorUi
import dev.sebastiano.ciacola.inspector.element.DocumentSliderControl
import dev.sebastiano.ciacola.inspector.element.StageResizeHandle
import dev.sebastiano.ciacola.inspector.element.computeClampedInlineEditorOffset
import dev.sebastiano.ciacola.inspector.element.computeResizedFrame
import dev.sebastiano.ciacola.inspector.element.snapSliderValue
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintEngine
import dev.sebastiano.ciacola.inspector.lint.DocumentLintSource
import dev.sebastiano.ciacola.inspector.lint.LintSeverityFilter
import dev.sebastiano.ciacola.inspector.lint.humanLintFilterLabel
import dev.sebastiano.ciacola.inspector.lint.humanLintSourceLabel
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalTestApi::class)
class InspectorUxPolishTest {

    private fun testSpecimens(): DeckSpecimenRegistry = deckSpecimens {
        specimen("demo.shader", "Demo Shader") {
            uniformFloat(name = "intensity", default = 0.5f, range = 0f..1f, step = 0.05f, label = "Intensity")
            content {}
        }
    }

    private fun sampleDocument(): DeckDocument = DeckDocument(
        id = "inspector-ux-test",
        title = "Inspector UX Test",
        defaultTheme = "dark",
        themes = mapOf(
            "dark" to DeckThemeSpec(
                preset = "stageDark",
                colors = mapOf("canvas" to "#111827", "ink" to "#F8FAFC"),
            ),
        ),
        slides = listOf(
            SlideSpec(
                id = "s1",
                background = Fill.Solid("#111827"),
                nodes = listOf(
                    NodeSpec(
                        id = "shader-card",
                        frame = FrameSpec(x = 200.0, y = 160.0, w = 640.0, h = 360.0),
                        specimen = SpecimenPayload(
                            ref = "demo.shader",
                            params = mapOf("intensity" to JsonPrimitive(0.5)),
                        ),
                    ),
                    NodeSpec(
                        id = "low-contrast-text",
                        frame = FrameSpec(x = 200.0, y = 600.0, w = 500.0, h = 80.0),
                        text = TextPayload(
                            value = RichTextValue.Plain("Barely visible"),
                            color = "#111827",
                            size = 24.0,
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun nonOccludingInlineEditorOffsetPlacesPopoverOutsideTargetAndAvoidsSiblingText() {
        val stageW = 1920f
        val stageH = 1080f
        val popover = Size(340f, 160f)
        val target = Rect(400f, 440f, 920f, 520f)
        val siblingBelow = Rect(400f, 540f, 920f, 680f)

        // When a sibling text node sits directly below, popover picks ABOVE target with >= 12px clearance.
        val offset = computeClampedInlineEditorOffset(
            targetBounds = target,
            popoverSizePx = popover,
            stageWidthPx = stageW,
            stageHeightPx = stageH,
            marginPx = 12f,
            gapPx = 12f,
            siblingTextBounds = listOf(siblingBelow),
        )
        val popoverRect = Rect(offset.x, offset.y, offset.x + popover.width, offset.y + popover.height)
        assertFalse(popoverRect.overlaps(target), "Popover must never occlude the edited text node")
        assertTrue(popoverRect.bottom <= target.top - 11.5f, "Popover should sit at least 12px above target when below is occupied")
    }

    @Test
    fun eightPointResizeHandlesSnapToHalfUnitsEnforceMinSizeAndCommitSingleUndoStep() {
        val initial = FrameSpec(x = 200.0, y = 160.0, w = 640.0, h = 360.0)

        val se = computeResizedFrame(StageResizeHandle.SE, initial, deltaStageX = 40.3, deltaStageY = -20.2)
        assertEquals(200.0, se.x)
        assertEquals(160.0, se.y)
        assertEquals(680.5, se.w)
        assertEquals(340.0, se.h)

        val nw = computeResizedFrame(StageResizeHandle.NW, initial, deltaStageX = 20.0, deltaStageY = 10.0)
        assertEquals(220.0, nw.x)
        assertEquals(170.0, nw.y)
        assertEquals(620.0, nw.w)
        assertEquals(350.0, nw.h)

        // Minimum size clamp protects against inverted negative widths when pulling W handle past right edge.
        val clampedW = computeResizedFrame(StageResizeHandle.W, initial, deltaStageX = 900.0, deltaStageY = 0.0, minWidth = 24.0)
        assertEquals(24.0, clampedW.w)
        assertEquals(200.0 + 640.0 - 24.0, clampedW.x)

        // Store gesture coalescing: begin -> multiple updates -> commit == 1 undo step.
        val store = DeckDocumentStore(sampleDocument(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentElementInspectorController(store, specimens = testSpecimens())
        controller.selectLocation("s1", 0)
        controller.selectNode("shader-card")

        assertTrue(controller.beginResizeGesture("shader-card", initial))
        controller.updateResizeGesture("shader-card", computeResizedFrame(StageResizeHandle.E, initial, 20.0, 0.0))
        controller.updateResizeGesture("shader-card", computeResizedFrame(StageResizeHandle.E, initial, 80.0, 0.0))
        controller.commitResizeGesture()

        assertEquals(720.0, store.document.slides.first().nodes.first().frame?.w)
        assertTrue(store.canUndo)
        store.undo()
        assertEquals(640.0, store.document.slides.first().nodes.first().frame?.w)
        assertFalse(store.canUndo, "Resize gesture must coalesce into exactly ONE undo step")
    }

    @Test
    fun sliderSnapAndControlCoalesceGestureIntoSingleUndoStep() = runComposeUiTest {
        assertEquals(0.55f, snapSliderValue(0.54f, 0f..1f, 0.05f), 0.0001f)

        val store = DeckDocumentStore(sampleDocument(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentElementInspectorController(store, specimens = testSpecimens())
        controller.selectLocation("s1", 0)
        controller.selectNode("shader-card")

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(360.dp, 120.dp)) {
                    val cur = controller.selectedResolvedNode()?.opacity?.toFloat() ?: 1f
                    DocumentSliderControl(
                        label = "Opacity",
                        value = cur,
                        valueRange = 0f..1f,
                        valueLabel = "%.2f".format(Locale.US, cur),
                        tagPrefix = "test-coalesced",
                        controller = controller,
                        onChanged = { next, inGesture -> controller.setOpacity(next.toDouble(), useActiveGesture = inGesture) },
                        onReset = null,
                        onApplyAll = null,
                        step = 0.05f,
                    )
                }
            }
        }
        waitForIdle()

        // Drive slider via SemanticsActions.SetProgress (simulates user scrub & release) and verify 1 undo step.
        onNodeWithTag("ciacola-test-coalesced-slider", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.35f) }
        waitForIdle()
        runOnIdle { if (controller.hasActiveTransaction) controller.commitGesture() }

        val updated = controller.selectedResolvedNode()?.opacity ?: 1.0
        assertTrue(abs(updated - 0.35) < 0.06, "Expected opacity ~0.35 after slider update, got $updated")
        assertTrue(store.canUndo)
        store.undo()
        assertEquals(1.0, controller.selectedResolvedNode()?.opacity)
        assertFalse(store.canUndo, "Continuous slider edit must revert cleanly in ONE undo step")
    }

    @Test
    fun elementInspectorPinsUndoHeaderAndSurfacesSpecimenUniformsAboveFrameAndTransform() = runComposeUiTest {
        val store = DeckDocumentStore(sampleDocument(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentElementInspectorController(store, specimens = testSpecimens())
        controller.selectLocation("s1", 0)
        controller.selectNode("shader-card")

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(388.dp, 720.dp)) {
                    DocumentElementInspectorUi(controller = controller, showHeaderUndoRedo = true)
                }
            }
        }
        waitForIdle()

        onNodeWithTag("ciacola-doc-inspector-undo", useUnmergedTree = true).assertIsDisplayed()
        val specimenNode = onNodeWithTag("ciacola-doc-specimen-params-section", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val frameNode = onNodeWithTag("ciacola-doc-inspector-frame-section", useUnmergedTree = true)
            .fetchSemanticsNode()

        assertTrue(
            specimenNode.positionInRoot.y < frameNode.positionInRoot.y,
            "Specimen Uniforms (${specimenNode.positionInRoot.y}) must appear above Frame & Transform (${frameNode.positionInRoot.y})",
        )
    }

    @Test
    fun linterFormatsContrastWithLocaleUsUnderEuropeanLocaleAndHumanizesLabels() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            val engine = DocumentDeckLintEngine(specimens = testSpecimens())
            val report = runBlocking {
                engine.lintSingleSlide(
                    document = sampleDocument(),
                    slideIndex = 0,
                    themeNames = listOf("dark"),
                )
            }
            val contrastIssue = report.issues.firstOrNull { it.code == "LOW_CONTRAST" }
            assertNotNull(contrastIssue, "Expected LOW_CONTRAST issue for #111827 text on #111827 background")
            assertTrue(
                contrastIssue.title.contains("1.00:1"),
                "Contrast title must use US decimal dot ('1.00:1'), got: ${contrastIssue.title}",
            )
            assertFalse(
                contrastIssue.detail.contains(','),
                "Contrast detail must never use locale comma decimal separator: ${contrastIssue.detail}",
            )
            assertTrue(contrastIssue.detail.contains("WCAG 4.5:1"), "Expected 'WCAG 4.5:1', got: ${contrastIssue.detail}")
            engine.close()
        } finally {
            Locale.setDefault(previousLocale)
        }

        // Humanized filter & source labels.
        assertEquals("All (5)", humanLintFilterLabel(LintSeverityFilter.All, 5, 2, 3))
        assertEquals("Errors Only (2)", humanLintFilterLabel(LintSeverityFilter.ErrorsOnly, 5, 2, 3))
        assertEquals("Warnings Only (3)", humanLintFilterLabel(LintSeverityFilter.WarningsOnly, 5, 2, 3))
        assertEquals("Contrast", humanLintSourceLabel(DocumentLintSource.StaticContrast))
        assertEquals("Pixel Contrast", humanLintSourceLabel(DocumentLintSource.PixelContrast480x270))
    }

    @Test
    fun themeInspectorUndoHookAndCrossControllerGestureHandoffWork() {
        val store = DeckDocumentStore(sampleDocument(), initialLoad = DeckLoadState.Loaded)
        val themeController = DocumentThemeInspectorController(store)
        var undoHookCalls = 0
        var beforeBeginCalls = 0
        themeController.onUndoRequest = {
            undoHookCalls++
            store.undo()
        }
        themeController.onBeforeBeginGesture = { beforeBeginCalls++ }

        assertTrue(themeController.beginGesture("Adjust color"))
        assertEquals(1, beforeBeginCalls)
        themeController.setThemeColor("accent", "#22D3EE", useActiveGesture = true)
        themeController.commitGesture()

        themeController.performUndo()
        assertEquals(1, undoHookCalls)
        assertFalse(store.canUndo)
    }
}
