package dev.sebastiano.ciacola.inspector.element

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val dragged = node("card", Rect(700f, 300f, 900f, 500f))
        val child = node("card:child", Rect(720f, 320f, 880f, 380f), parentId = "card")
        val sibling = node("sib", Rect(200f, 620f, 400f, 740f))
        val allNodes = listOf(dragged, child, sibling)

        val snapAt03 = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(145f, 0f),
            allNodes = allNodes,
            viewportScale = 0.3f,
        )
        assertEquals(960f, snapAt03.snappedBounds.center.x, 0.01f)
        assertTrue(snapAt03.guides.any { it.kind == SnapGuideKind.StageCenter && it.orientation == SnapGuideOrientation.Vertical })

        val noSnapAt03 = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(135f, 0f),
            allNodes = allNodes,
            viewportScale = 0.3f,
        )
        assertEquals(935f, noSnapAt03.snappedBounds.center.x, 0.01f)

        val snapAt06 = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(152f, 0f),
            allNodes = allNodes,
            viewportScale = 0.6f,
        )
        assertEquals(960f, snapAt06.snappedBounds.center.x, 0.01f)

        val noSnapAt06 = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(145f, 0f),
            allNodes = allNodes,
            viewportScale = 0.6f,
        )
        assertEquals(945f, noSnapAt06.snappedBounds.center.x, 0.01f)

        val safeMarginSnap = computeDragSnap(
            draggedElementId = "card",
            startBounds = dragged.stageBounds,
            rawDeltaStagePx = Offset(-667f, 0f),
            allNodes = allNodes,
            viewportScale = 0.6f,
        )
        assertEquals(STAGE_SAFE_MARGIN_PX, safeMarginSnap.snappedBounds.left, 0.01f)
        assertTrue(safeMarginSnap.guides.any { it.kind == SnapGuideKind.SafeMargin })

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
    fun thirtyFrameDragPreviewCommitsSingleSnappedDocumentStoreEditOnRelease() {
        val initialDoc = DeckDocument(
            id = "drag-test",
            title = "Drag Test",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    nodes = listOf(
                        NodeSpec(
                            id = "s1:card",
                            frame = FrameSpec(x = 100.0, y = 120.0, w = 300.0, h = 160.0),
                            text = TextPayload(value = RichTextValue.Plain("Card")),
                        ),
                    ),
                ),
            ),
        )
        val store = DeckDocumentStore(
            initialDocument = initialDoc,
            initialLoad = dev.sebastiano.ciacola.document.DeckLoadState.Loaded,
        )
        val controller = DocumentElementInspectorController(store)
        controller.selectNode("s1:card")

        // 30 frames of placement-phase drag preview must NOT mutate store.document or push undo steps.
        val initialRev = store.revision
        for (i in 1..30) {
            controller.updateStageDragPreview("s1:card", i * 2.1, i * 1.1)
        }
        assertEquals(initialRev, store.revision, "Placement drag preview must not mutate DeckDocumentStore mid-drag")
        assertFalse(store.canUndo)

        // Release commits ONE edit snapped to 0.5 stage units.
        controller.commitStageDrag("s1:card", 63.24, 33.24)
        assertTrue(store.canUndo)
        val moved = store.document.slides[0].nodes[0].frame!!
        assertEquals(163.0, moved.x)
        assertEquals(153.0, moved.y)

        store.undo()
        assertFalse(store.canUndo, "30-frame drag must undo in exactly one step")
        assertEquals(100.0, store.document.slides[0].nodes[0].frame?.x)
    }
}
