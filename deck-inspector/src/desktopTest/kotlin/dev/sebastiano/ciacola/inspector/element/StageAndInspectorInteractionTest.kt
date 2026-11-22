package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.inspector.design.themeHeaderVisibility
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stage chrome and inspector interaction tests: union handles, drag preview scale, mixed text, and no-op blur. */
@OptIn(ExperimentalTestApi::class)
class StageAndInspectorInteractionTest {

    private fun frameOf(store: DeckDocumentStore, id: String): FrameSpec =
        store.document.slides.first().nodes.first { it.id == id }.frame!!

    @Test
    fun multiSelectionDrawsUnionHandlesThatResizeEveryNodeProportionallyInOneUndo() = runComposeUiTest {
        val store = DeckDocumentStore(arrangeTestDocument(), initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        c.selectNodes(setOf("a", "b"), primaryId = "a")
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    DocumentStageDirectEditOverlay(controller = c, stageScale = density.density * 0.5f)
                }
            }
        }
        waitForIdle()
        // Eight handles sit on the union frame; no per-node handles while 2+ nodes are selected.
        for (handle in StageResizeHandle.entries) {
            onNodeWithTag("ciacola-stage-resize-handle-$MULTI_SELECTION_HANDLE_KEY-${handle.name}", useUnmergedTree = true).assertExists()
        }
        onNodeWithTag("ciacola-stage-resize-handle-a-SE", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-stage-resize-handle-b-SE", useUnmergedTree = true).assertDoesNotExist()

        // Union (100..500 × 100..350); dragging SE by (+100, +50) stage units scales it to 500 × 300.
        val px = density.density
        onNodeWithTag("ciacola-stage-resize-handle-$MULTI_SELECTION_HANDLE_KEY-SE", useUnmergedTree = true).performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(5f * px, 2.5f * px)) }
            release()
        }
        waitForIdle()
        assertEquals(FrameSpec(x = 100.0, y = 100.0, w = 250.0, h = 120.0), frameOf(store, "a"))
        assertEquals(FrameSpec(x = 475.0, y = 340.0, w = 125.0, h = 60.0), frameOf(store, "b"))
        runOnIdle { store.undo() }
        assertEquals(FrameSpec(x = 100.0, y = 100.0, w = 200.0, h = 100.0), frameOf(store, "a"))
        assertEquals(FrameSpec(x = 400.0, y = 300.0, w = 100.0, h = 50.0), frameOf(store, "b"), "Union resize is ONE undo step")
    }

    @Test
    fun groupResizeFramesKeepRelativeLayoutInsideTheUnion() {
        val frames = DocumentElementArrangeOps.groupResizeFrames(
            startUnion = Rect(0f, 0f, 100f, 100f),
            nextUnion = Rect(10f, 20f, 210f, 70f),
            startRects = mapOf("x" to Rect(50f, 50f, 100f, 100f)),
        )
        assertEquals(FrameSpec(x = 110.0, y = 45.0, w = 100.0, h = 25.0), frames["x"])
    }

    @Test
    fun dragPreviewOffsetScalesStageUnitsLikeTheSelectionChrome() {
        // 10 stage units at stageScale 0.5 on a 2x display: 5 overlay px = 2.5dp.
        val offset = stageDragPreviewOffset(10.0, -4.0, stageScale = 0.5f, density = 2f)
        assertEquals(2.5f, offset.x.value, 0.001f)
        assertEquals(-1f, offset.y.value, 0.001f)
        // Inside Easel (stageScale 1) the offset stays one stage unit per px.
        assertEquals(5f, stageDragPreviewOffset(10.0, 0.0, stageScale = 1f, density = 2f).x.value, 0.001f)
    }

    @Test
    fun mixedTextSelectionShowsMixedAndDisablesApply() = runComposeUiTest {
        val store = DeckDocumentStore(arrangeTestDocument(), initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        assertFalse(isMixedText(listOf("Same", "Same")))
        assertTrue(isMixedText(listOf("One", "Two")))
        assertFalse(isMixedText(listOf("Only")))
        runOnIdle {
            store.edit("Add second text") { doc ->
                val s = doc.slides.first()
                val a = s.nodes.first { it.id == "a" }
                doc.copy(slides = listOf(s.copy(nodes = s.nodes + a.copy(id = "a2", text = a.text!!.copy(value = dev.sebastiano.ciacola.document.RichTextValue.Plain("Other copy"))))))
            }
        }
        c.selectNodes(setOf("a", "a2"), primaryId = "a")
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(380.dp, 1400.dp)) {
                    DocumentElementInspectorUi(controller = c, onCopyText = { true })
                }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-doc-text-mixed", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-doc-text-apply-button", useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun numericFieldBlurWithoutTypingNeverCommitsTheRoundedDisplay() = runComposeUiTest {
        var commits = 0
        val other = FocusRequester()
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Column {
                    NumericPropertyField(
                        label = "X",
                        value = 100.14,
                        tag = "probe",
                        format = { String.format(Locale.US, "%.1f", it) },
                        onCommit = { commits++ },
                        onStep = {},
                        onScrubStart = {},
                        onScrub = {},
                        onScrubEnd = {},
                        modifier = Modifier.width(200.dp),
                    )
                    Box(Modifier.size(20.dp).focusRequester(other).focusable().testTag("other"))
                }
            }
        }
        waitForIdle()
        onNodeWithTag("probe-input", useUnmergedTree = true).performClick()
        waitForIdle()
        runOnIdle { other.requestFocus() }
        waitForIdle()
        assertEquals(0, commits, "Tabbing through a field must not round 100.14 to 100.1 and push an undo step")
    }

    @Test
    fun themeHeaderHidesOneOptionSelectorAndShowsBaseForCustomThemes() {
        val single = themeHeaderVisibility(listOf("dark"), "dark")
        assertFalse(single.showSelector, "A one-option Theme dropdown is noise")
        assertTrue(single.showBasePreset)
        val pair = themeHeaderVisibility(listOf("dark", "light"), "light")
        assertTrue(pair.showSelector)
        assertFalse(pair.showBasePreset)
        val custom = themeHeaderVisibility(listOf("dark", "light", "solarized"), "solarized")
        assertTrue(custom.showSelector)
        assertTrue(custom.showBasePreset, "Custom themes need the Base preset control")
    }

    @Test
    fun layersListSnapsToWholeRows() {
        assertEquals(22, layersTreeHeightDp(0))
        assertEquals(66, layersTreeHeightDp(3))
        assertEquals(176, layersTreeHeightDp(20))
        assertEquals(0, layersTreeHeightDp(20) % 22, "Never cut a row mid-text")
    }

    @Test
    fun groupNamesAreNotRepeatedInTheHeaderOrTheAbsentNotice() {
        assertEquals(null, inspectorSelectionSubtitle("Group · 17 items", "Group", 1))
        assertEquals("Text", inspectorSelectionSubtitle("Hello stage world", "Text", 1))
        assertEquals("Group · 2 selected", inspectorSelectionSubtitle("Group · 3 items", "Group", 2))
        assertEquals("This group isn\u2019t on Base.", absentNodeNotice("Group · 17 items", "Group", "Base"))
        assertEquals("\u201CLate card\u201D isn\u2019t on Build 1.", absentNodeNotice("Late card", "Rectangle", "Build 1"))
    }

    @Test
    fun colorPopoverFlipsAboveAndKeepsAWindowMarginInsteadOfClipping() {
        val window = androidx.compose.ui.unit.IntSize(1000, 800)
        val content = androidx.compose.ui.unit.IntSize(300, 400)
        // Room below: right-aligned under the row.
        val below = colorPopoverOffset(androidx.compose.ui.unit.IntRect(500, 100, 900, 124), window, content, gapPx = 4, marginPx = 8)
        assertEquals(androidx.compose.ui.unit.IntOffset(600, 128), below)
        // Row near the bottom edge: flips above.
        val above = colorPopoverOffset(androidx.compose.ui.unit.IntRect(500, 700, 900, 724), window, content, gapPx = 4, marginPx = 8)
        assertEquals(296, above.y)
        // Neither fits: pinned 8px inside the bottom edge, never clipped.
        val pinned = colorPopoverOffset(androidx.compose.ui.unit.IntRect(500, 300, 900, 324), window, androidx.compose.ui.unit.IntSize(300, 700), gapPx = 4, marginPx = 8)
        assertEquals(92, pinned.y)
        assertTrue(pinned.y + 700 <= 800 - 8)
    }
}
