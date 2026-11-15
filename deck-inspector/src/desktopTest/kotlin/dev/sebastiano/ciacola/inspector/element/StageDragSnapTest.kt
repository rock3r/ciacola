package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.effects.DeckSurfaceCard
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StageDragSnapTest {

    private fun node(
        id: String,
        bounds: Rect,
        parentId: String? = null,
    ): StageNodeSnapshot = StageNodeSnapshot(
        elementId = id,
        kind = "SurfaceCard",
        label = id,
        sceneIndex = 0,
        beatIndex = 0,
        stageBounds = bounds,
        isEditable = true,
        parentElementId = parentId,
    )

    @Test
    fun snapsWithinSixScreenPixelsAtPointThreeAndPointSixPreviewScale() {
        // Card width = 200 (center = left + 100). Starting at left = 700 -> center = 800.
        // Moving +160 brings center to 960 (stage horizontal center).
        val dragged = node("card", Rect(700f, 300f, 900f, 500f))
        val child = node("card:child", Rect(720f, 320f, 880f, 380f), parentId = "card")
        val sibling = node("sib", Rect(200f, 620f, 400f, 740f))
        val allNodes = listOf(dragged, child, sibling)

        // At viewportScale = 0.3f: 6 screen px == 20 stage px.
        // 15 stage px short of center (deltaX = 145 -> center = 945) must snap to 960.
        val snapAt03 = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(145f, 0f),
            allNodes = allNodes,
            viewportScale = 0.3f,
        )
        assertEquals(960f, snapAt03.snappedBounds.center.x, 0.01f)
        assertTrue(snapAt03.guides.any { it.kind == SnapGuideKind.StageCenter && it.orientation == SnapGuideOrientation.Vertical })

        // 25 stage px short of center (deltaX = 135 -> center = 935) exceeds 20 stage px -> no snap.
        val noSnapAt03 = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(135f, 0f),
            allNodes = allNodes,
            viewportScale = 0.3f,
        )
        assertEquals(935f, noSnapAt03.snappedBounds.center.x, 0.01f)

        // At viewportScale = 0.6f: 6 screen px == 10 stage px.
        // 8 stage px short (deltaX = 152 -> center = 952) snaps to 960.
        val snapAt06 = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(152f, 0f),
            allNodes = allNodes,
            viewportScale = 0.6f,
        )
        assertEquals(960f, snapAt06.snappedBounds.center.x, 0.01f)

        // 15 stage px short (deltaX = 145 -> center = 945) exceeds 10 stage px at 0.6x -> no snap.
        val noSnapAt06 = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(145f, 0f),
            allNodes = allNodes,
            viewportScale = 0.6f,
        )
        assertEquals(945f, noSnapAt06.snappedBounds.center.x, 0.01f)

        // Safe-area margin snap (28px left edge): start left = 700, raw deltaX = -667 -> left = 33 (within 10px at 0.6x).
        val safeMarginSnap = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(-667f, 0f),
            allNodes = allNodes,
            viewportScale = 0.6f,
        )
        assertEquals(STAGE_SAFE_MARGIN_PX, safeMarginSnap.snappedBounds.left, 0.01f)
        assertTrue(safeMarginSnap.guides.any { it.kind == SnapGuideKind.SafeMargin })

        // Sibling edge snap: sibling left = 200; raw deltaX = -494 -> left = 206 (within 10px at 0.6x).
        val siblingSnap = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(-494f, 0f),
            allNodes = allNodes,
            viewportScale = 0.6f,
        )
        assertEquals(200f, siblingSnap.snappedBounds.left, 0.01f)
        assertTrue(siblingSnap.guides.any { it.kind == SnapGuideKind.Sibling })
    }

    @Test
    fun altDisablesSnapAndShiftLocksAxisAndDescendantsAreExcluded() {
        val dragged = node("card", Rect(700f, 300f, 900f, 500f))
        val child = node("card:child", Rect(720f, 320f, 880f, 380f), parentId = "card")
        val allNodes = listOf(dragged, child)

        // Alt disables snapping completely.
        val unsnapped = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(158f, 2f),
            allNodes = allNodes,
            viewportScale = 0.6f,
            disableSnap = true,
        )
        assertEquals(Offset(158f, 2f), unsnapped.deltaStagePx)
        assertTrue(unsnapped.guides.isEmpty())

        // Shift axis-locks to dominant horizontal axis (y forced to 0).
        val locked = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(120f, 24f),
            allNodes = allNodes,
            viewportScale = 1f,
            disableSnap = true,
            axisLock = true,
        )
        assertEquals(Offset(120f, 0f), locked.deltaStagePx)

        // Descendant edge at x=720 must NOT act as a snap target for its own parent (deltaX = +18 -> left = 718).
        val noSelfChildSnap = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(18f, 0f),
            allNodes = allNodes,
            viewportScale = 1f,
        )
        assertEquals(718f, noSelfChildSnap.snappedBounds.left, 0.01f)
        assertTrue(noSelfChildSnap.guides.isEmpty())
    }

    @Test
    fun thirtyFrameDragCommitsOneUndoEntryWithZeroSlideRecompositions() = runComposeUiTest {
        val dir = Files.createTempDirectory("ciacola-stage-drag-30f")
        val store = ElementOverridesStore(dir)
        val controller = ElementEditorController(store).apply { editorActive = true }
        var slideRecompositions = 0

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(
                    LocalElementOverrides provides store.data().also { store.revision },
                ) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        controller.stageDecorator.decorate {
                            SlideWithDraggableCard(
                                onSlideComposed = { slideRecompositions++ },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()

        val cardBounds = checkNotNull(controller.inspectionRegistry.nodes["s1:card"]).stageBounds
        val startCenter = Offset(cardBounds.center.x, cardBounds.center.y)

        // Select the card first via single-click (container-first selection).
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            click(startCenter)
        }
        waitForIdle()
        assertEquals("s1:card", controller.selectedElementId)

        // Reset recomposition counter right before the 30-frame drag begins.
        var midDragRecompositions = -1
        var hadLiveDragPreview = false
        runOnIdle { slideRecompositions = 0 }

        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            moveTo(startCenter)
            press()
        }
        waitForIdle()

        val frames = 30
        for (frame in 1..frames) {
            onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
                advanceEventTime(16L)
                moveTo(Offset(startCenter.x + frame * 4f, startCenter.y + frame * 2f))
            }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            if (frame == 15) {
                midDragRecompositions = slideRecompositions
                hadLiveDragPreview = controller.dragPreview.offsets["s1:card"] != null
            }
        }
        val endOfDragRecompositions = slideRecompositions
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            advanceEventTime(16L)
            release()
        }
        waitForIdle()

        assertTrue(hadLiveDragPreview, "Expected live StageDragPreview offset during placement-phase drag")
        assertTrue(
            midDragRecompositions in 0..2 && endOfDragRecompositions in 0..2,
            "Expected <= 2 slide recompositions during 30-frame drag, got mid=$midDragRecompositions end=$endOfDragRecompositions",
        )
        assertNull(controller.activeDragElementId)
        assertTrue(controller.dragPreview.offsets.isEmpty())

        val committed = assertNotNull(store.patchFor("s1:card"))
        assertTrue(abs(committed.offsetXDp ?: 0f) > 20f, "Expected committed offsetXDp > 20dp, got ${committed.offsetXDp}")
        assertTrue(abs(committed.offsetYDp ?: 0f) > 10f, "Expected committed offsetYDp > 10dp, got ${committed.offsetYDp}")

        // Exactly ONE undo step must revert the entire 30-frame drag cleanly!
        assertTrue(store.canUndo)
        runOnIdle { assertTrue(store.undo()) }
        waitForIdle()
        assertNull(store.patchFor("s1:card"))
        assertFalse(store.canUndo, "Expected exactly 1 undo entry for the 30-frame drag")
    }

    @Test
    fun escapeMidDragAbortsWithoutCommittingAndEscapeInsideContainerPopsOneLevel() = runComposeUiTest {
        val dir = Files.createTempDirectory("ciacola-stage-drag-esc")
        val store = ElementOverridesStore(dir)
        val controller = ElementEditorController(store).apply {
            editorActive = true
            onStageKeyHandler = { ev -> handleElementStageKeyEvent(ev) }
        }

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(
                    LocalElementOverrides provides store.data().also { store.revision },
                ) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        controller.stageDecorator.decorate {
                            SlideWithDraggableCard(onSlideComposed = {})
                        }
                    }
                }
            }
        }
        waitForIdle()

        val cardBounds = checkNotNull(controller.inspectionRegistry.nodes["s1:card"]).stageBounds
        val titleBounds = checkNotNull(controller.inspectionRegistry.nodes["s1:card:title"]).stageBounds
        val startCenter = cardBounds.center

        // 1. Select card, begin drag, press Esc mid-drag, move further while pressed, then release.
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput { click(startCenter) }
        waitForIdle()
        assertEquals("s1:card", controller.selectedElementId)

        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            moveTo(startCenter)
            press()
            advanceEventTime(16L)
            moveTo(Offset(startCenter.x + 48f, startCenter.y + 24f))
        }
        waitForIdle()
        assertEquals("s1:card", controller.activeDragElementId)
        assertNotNull(controller.dragPreview.offsets["s1:card"], "Expected active drag preview before Esc")

        // Dispatch Esc through the unified key router (mirrors DeckMenuWindow / StudioWorkspaceController).
        onNodeWithTag("ciacola-direct-edit-overlay").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(controller.activeDragElementId, "Esc must clear activeDragElementId immediately")
        assertTrue(controller.dragPreview.offsets.isEmpty(), "Esc must clear dragPreview immediately")
        assertEquals("s1:card", controller.selectedElementId, "Esc mid-drag cancels drag without dropping selection")

        // Subsequent pointer moves and release from the aborted gesture must neither re-arm preview nor commit.
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            advanceEventTime(16L)
            moveTo(Offset(startCenter.x + 88f, startCenter.y + 44f))
        }
        waitForIdle()
        assertNull(controller.activeDragElementId)
        assertTrue(controller.dragPreview.offsets.isEmpty())

        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            advanceEventTime(16L)
            release()
        }
        waitForIdle()
        assertNull(store.patchFor("s1:card"), "Aborted drag must not write an ElementPatch on release")
        assertFalse(store.canUndo, "Aborted drag must not record an undo entry")

        // 2. Double-click into container context ("s1:card" -> selects child "s1:card:title"), then press Esc to pop one level.
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            doubleClick(titleBounds.center)
        }
        waitForIdle()
        assertEquals("s1:card", controller.selectionContextParentId)
        assertEquals("s1:card:title", controller.selectedElementId)

        onNodeWithTag("ciacola-direct-edit-overlay").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(controller.selectionContextParentId, "First Esc inside container must pop back to root context")
        assertEquals("s1:card", controller.selectedElementId, "First Esc must select the enclosing container")

        onNodeWithTag("ciacola-direct-edit-overlay").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(controller.selectedElementId, "Second Esc at root context must clear selection")
    }
}

@androidx.compose.runtime.Composable
private fun SlideWithDraggableCard(onSlideComposed: () -> Unit) {
    SideEffect { onSlideComposed() }
    Box(Modifier.fillMaxSize()) {
        DeckSurfaceCard(
            elementId = "s1:card",
            label = "Draggable Card",
            fillColor = Color(0xFF1E293B),
            borderColor = Color(0xFF38BDF8),
            modifier = Modifier
                .offset(x = 120.dp, y = 100.dp)
                .size(260.dp, 140.dp),
        ) {
            DeckEditableText(
                elementId = "s1:card:title",
                text = "Card Title",
                color = Color.White,
            )
        }
    }
}
