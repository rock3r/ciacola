package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesignInspectorUiTest {

    private fun setup(): Pair<DeckDocumentStore, DocumentElementInspectorController> {
        val store = DeckDocumentStore(arrangeTestDocument(), initialLoad = DeckLoadState.Loaded)
        val c = DocumentElementInspectorController(store)
        c.selectLocation("s1", 0)
        return store to c
    }

    @Test
    fun appBarOwnsUndoAndNoBeatBadgeOrRawIdLabelsByDefault() = runComposeUiTest {
        val (_, c) = setup()
        c.selectNode("a")
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(380.dp, 900.dp)) { DocumentElementInspectorUi(controller = c, onCopyText = { true }) }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-doc-inspector-undo", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-doc-inspector-beat-badge", useUnmergedTree = true).assertDoesNotExist()
        onAllNodesWithText("Beat 0 · Base", substring = true).assertCountEquals(0)
        // Layers show plain display names; raw IDs never appear as primary labels.
        onNodeWithTag("ciacola-doc-node-item-a", useUnmergedTree = true).assertExists()
        onAllNodesWithText("Hello stage world", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        onAllNodesWithText("Rectangle", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        onAllNodesWithText("late", useUnmergedTree = true).assertCountEquals(0)
        // Full morph labels and no truncated `Parag` / `Chara`.
        onNodeWithText("Character", useUnmergedTree = true).assertExists()
        onAllNodesWithText("Chara", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag("ciacola-doc-text-align-start", useUnmergedTree = true).assertExists()
    }

    @Test
    fun mixedValuesShowPlaceholderAndTypedEntryCommitsToAllInOneUndo() = runComposeUiTest {
        val (store, c) = setup()
        c.selectNodes(setOf("a", "b"), primaryId = "a")
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(380.dp, 900.dp)) { DocumentElementInspectorUi(controller = c, onCopyText = { true }) }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-doc-frame-x-mixed", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-doc-distribute-horizontal", useUnmergedTree = true).assertIsNotEnabled()

        val input = onNodeWithTag("ciacola-doc-frame-x-input", useUnmergedTree = true)
        input.performClick()
        input.performTextReplacement("720")
        input.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        val nodes = store.document.slides.first().nodes
        assertEquals(720.0, nodes.first { it.id == "a" }.frame?.x)
        assertEquals(720.0, nodes.first { it.id == "b" }.frame?.x)
        runOnIdle { store.undo() }
        val after = store.document.slides.first().nodes
        assertEquals(100.0, after.first { it.id == "a" }.frame?.x)
        assertEquals(400.0, after.first { it.id == "b" }.frame?.x)

        onNodeWithTag("ciacola-doc-align-left", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(100.0, store.document.slides.first().nodes.first { it.id == "b" }.frame?.x)
    }

    @Test
    fun absentNodeOffersGoToBuildAndRawIdCopies() = runComposeUiTest {
        val (_, c) = setup()
        val navigated = mutableListOf<Int>()
        val copied = mutableListOf<String>()
        c.onNavigateToBeat = { navigated += it }
        c.selectNode("late")
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(380.dp, 900.dp)) {
                    DocumentElementInspectorUi(controller = c, onCopyText = { copied += it; true })
                }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-doc-node-not-present", useUnmergedTree = true).assertExists()
        assertEquals(2, onAllNodesWithText("Go to Build 1", useUnmergedTree = true).fetchSemanticsNodes().size)
        onNodeWithTag("ciacola-doc-go-to-build", useUnmergedTree = true).performClick()
        onNodeWithTag("ciacola-doc-node-go-to-build-late", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf(1, 1), navigated)

        onNodeWithTag("ciacola-doc-raw-id-row", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("s1/late"), copied)
        onNodeWithTag("ciacola-doc-raw-id-copied", useUnmergedTree = true).assertExists()
    }

    @Test
    fun labelScrubAndArrowSteppingEditTheValue() = runComposeUiTest {
        val (store, c) = setup()
        c.selectNode("b")
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(380.dp, 900.dp)) { DocumentElementInspectorUi(controller = c, onCopyText = { true }) }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-doc-frame-x-scrub", useUnmergedTree = true).performMouseInput {
            moveTo(center)
            press()
            repeat(6) { moveBy(Offset(5f, 0f)) }
            release()
        }
        waitForIdle()
        val scrubbed = store.document.slides.first().nodes.first { it.id == "b" }.frame!!.x!!
        assertTrue(scrubbed > 400.0, "Dragging the label scrubs X upward (got $scrubbed)")
        runOnIdle { store.undo() }
        assertEquals(400.0, store.document.slides.first().nodes.first { it.id == "b" }.frame?.x, "Scrub is one undo step")

        val input = onNodeWithTag("ciacola-doc-frame-y-input", useUnmergedTree = true)
        input.performClick()
        input.performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        assertEquals(301.0, store.document.slides.first().nodes.first { it.id == "b" }.frame?.y)
        input.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) } }
        waitForIdle()
        assertEquals(291.0, store.document.slides.first().nodes.first { it.id == "b" }.frame?.y, "Shift steps ×10")
    }

    @Test
    fun layersRowClickSelectsAndUnsetColourUsesSlashSwatch() = runComposeUiTest {
        val (_, c) = setup()
        c.selectNode("a")
        setContent {
            CiacolaJewelTheme(isDark = false) {
                Box(Modifier.size(380.dp, 1200.dp)) { DocumentElementInspectorUi(controller = c, onCopyText = { true }) }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-doc-node-item-c", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals("c", c.selectedNodeId)
        assertEquals(setOf("c"), c.selectedNodeIds)
        // Ellipse has no fill and no stroke: both swatches draw the diagonal slash.
        onNodeWithTag("ciacola-doc-shape-fill-swatch-unset", useUnmergedTree = true).assertExists()
        assertTrue(onAllNodesWithText("None", useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
        onAllNodesWithText("Tok", useUnmergedTree = true).assertCountEquals(0)
    }
}
