package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StageMultiSelectOverlayTest {

    private fun xOf(store: DeckDocumentStore, id: String) = store.document.slides.first().nodes.first { it.id == id }.frame!!.x!!

    @Test
    fun marqueeThenSharedDragMovesTheWholeSelectionInOneUndo() = runComposeUiTest {
        val store = DeckDocumentStore(arrangeTestDocument(), initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(960.dp, 540.dp).testTag("stage")) {
                    DocumentStageDirectEditOverlay(controller = c, stageScale = density.density * 0.5f)
                }
            }
        }
        waitForIdle()
        val px = density.density
        // Empty canvas drag → marquee covering a (100..300, 100..200) and b (400..500, 300..350).
        onNodeWithTag("ciacola-doc-stage-direct-edit").performMouseInput {
            moveTo(Offset(20f * px, 20f * px))
            press()
            repeat(10) { moveBy(Offset(24f * px, 15f * px)) }
            release()
        }
        waitForIdle()
        assertEquals(setOf("a", "b"), c.selectedNodeIds)
        onNodeWithTag("ciacola-doc-stage-multi-selection-bounds", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-stage-dimension-pill", useUnmergedTree = true).assertDoesNotExist()

        // Drag node a: both selected nodes move together.
        onNodeWithTag("ciacola-doc-stage-direct-edit").performMouseInput {
            moveTo(Offset(100f * px, 75f * px))
            press()
            repeat(8) { moveBy(Offset(5f * px, 0f)) }
            release()
        }
        waitForIdle()
        val dxA = xOf(store, "a") - 100.0
        val dxB = xOf(store, "b") - 400.0
        assertTrue(dxA > 20.0, "a moved right (dx=$dxA)")
        assertEquals(dxA, dxB, "Both selected nodes move by the same delta")
        runOnIdle { store.undo() }
        assertEquals(100.0, xOf(store, "a"))
        assertEquals(400.0, xOf(store, "b"), "Shared drag is ONE undo step")

        // Click on empty canvas clears the selection.
        onNodeWithTag("ciacola-doc-stage-direct-edit").performMouseInput { click(Offset(900f * px, 40f * px)) }
        waitForIdle()
        assertTrue(c.selectedNodeIds.isEmpty())

        // Single selection shows resize handles; arrow keys nudge it via the overlay.
        onNodeWithTag("ciacola-doc-stage-direct-edit").performMouseInput { click(Offset(225f * px, 162f * px)) }
        waitForIdle()
        assertEquals(setOf("b"), c.selectedNodeIds)
        onNodeWithTag("ciacola-stage-resize-handle-b-SE", useUnmergedTree = true).assertExists()
        runOnIdle { assertTrue(c.nudgeSelectedNodes(0.0, 25.0)) }
        assertEquals(325.0, store.document.slides.first().nodes.first { it.id == "b" }.frame?.y)
    }

    @Test
    fun shiftClickOnStageTogglesMultiSelection() = runComposeUiTest {
        val store = DeckDocumentStore(arrangeTestDocument(), initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        c.selectNode("a")
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    DocumentStageDirectEditOverlay(controller = c, stageScale = density.density * 0.5f)
                }
            }
        }
        waitForIdle()
        val px = density.density
        val overlay = onNodeWithTag("ciacola-doc-stage-direct-edit")
        overlay.performKeyInput { keyDown(Key.ShiftLeft) }
        overlay.performMouseInput { click(Offset(225f * px, 162f * px)) }
        overlay.performKeyInput { keyUp(Key.ShiftLeft) }
        waitForIdle()
        assertEquals(setOf("a", "b"), c.selectedNodeIds)
        assertEquals("b", c.selectedNodeId)
    }
}
