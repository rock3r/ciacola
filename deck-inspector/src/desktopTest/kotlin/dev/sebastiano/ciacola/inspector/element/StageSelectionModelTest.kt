package dev.sebastiano.ciacola.inspector.element

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.sebastiano.ciacola.inspector.StageInteractiveEntry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StageSelectionModelTest {

    private fun snapshot(
        id: String,
        kind: String,
        bounds: Rect,
        parentId: String? = null,
        text: String = if (kind == "Text") id else "",
    ): StageNodeSnapshot = StageNodeSnapshot(
        elementId = id,
        kind = kind,
        label = id,
        sceneIndex = 0,
        beatIndex = 0,
        stageBounds = bounds,
        resolvedText = text,
        isEditable = true,
        parentElementId = parentId,
    )

    @Test
    fun singleClickSelectsContainerFirstDoubleClickEntersAndEditsTextLeafAndEscPops() {
        val card = snapshot("s1:card", "SurfaceCard", Rect(100f, 100f, 700f, 420f))
        val title = snapshot("s1:card:title", "Text", Rect(140f, 140f, 540f, 200f), parentId = "s1:card")
        val body = snapshot("s1:card:body", "Text", Rect(140f, 220f, 640f, 340f), parentId = "s1:card")
        val outside = snapshot("s1:footer", "Text", Rect(100f, 920f, 500f, 980f))
        val nodes = listOf(card, title, body, outside)
        val supportsText: (StageNodeSnapshot) -> Boolean = { it.kind == "Text" }

        var ctx = StageSelectionContext()

        // 1. Clicking directly on the child title selects the parent SurfaceCard first.
        ctx = StageSelectionModel.resolveSingleClick(Offset(240f, 170f), nodes, ctx)
        assertEquals("s1:card", ctx.selectedElementId)
        assertNull(ctx.contextParentId)

        // 2. Double-clicking the title enters s1:card and selects s1:card:title (does not open editor yet).
        val enterOutcome = StageSelectionModel.resolveDoubleClick(
            point = Offset(240f, 170f),
            nodes = nodes,
            current = ctx,
            supportsInlineTextEdit = supportsText,
        )
        val entered = assertIs<StageDoubleClickOutcome.EnteredContainer>(enterOutcome)
        assertEquals("s1:card", entered.next.contextParentId)
        assertEquals("s1:card:title", entered.next.selectedElementId)
        ctx = entered.next

        // 3. Single-clicking a sibling inside the same container switches to that sibling immediately.
        ctx = StageSelectionModel.resolveSingleClick(Offset(240f, 260f), nodes, ctx)
        assertEquals("s1:card", ctx.contextParentId)
        assertEquals("s1:card:body", ctx.selectedElementId)

        // 4. Double-clicking a text leaf inside the open container starts inline text editing.
        val editOutcome = StageSelectionModel.resolveDoubleClick(
            point = Offset(240f, 260f),
            nodes = nodes,
            current = ctx,
            supportsInlineTextEdit = supportsText,
        )
        val startEdit = assertIs<StageDoubleClickOutcome.StartInlineTextEdit>(editOutcome)
        assertEquals("s1:card:body", startEdit.elementId)
        assertEquals("s1:card", startEdit.next.contextParentId)

        // 5. First Esc pops out of the container and selects the container itself.
        ctx = assertNotNull(StageSelectionModel.resolveEscape(ctx, nodes))
        assertNull(ctx.contextParentId)
        assertEquals("s1:card", ctx.selectedElementId)

        // 6. Second Esc clears selection entirely.
        ctx = assertNotNull(StageSelectionModel.resolveEscape(ctx, nodes))
        assertNull(ctx.contextParentId)
        assertNull(ctx.selectedElementId)

        // Third Esc returns null (nothing selected -> event not consumed).
        assertNull(StageSelectionModel.resolveEscape(ctx, nodes))

        // 7. Re-enter container, then single-click an element outside -> exits container & selects outside.
        ctx = StageSelectionContext(contextParentId = "s1:card", selectedElementId = "s1:card:title")
        ctx = StageSelectionModel.resolveSingleClick(Offset(200f, 950f), nodes, ctx)
        assertNull(ctx.contextParentId)
        assertEquals("s1:footer", ctx.selectedElementId)
    }

    @Test
    fun nestedContainersStepDownOnDoubleClickAndPopStepByStepOnEscape() {
        val outer = snapshot("outer", "SurfaceCard", Rect(40f, 40f, 900f, 600f))
        val inner = snapshot("inner", "SurfaceCard", Rect(80f, 80f, 500f, 400f), parentId = "outer")
        val leaf = snapshot("leaf", "Text", Rect(120f, 120f, 420f, 180f), parentId = "inner")
        val nodes = listOf(outer, inner, leaf)
        val supportsText: (StageNodeSnapshot) -> Boolean = { it.kind == "Text" }
        val pt = Offset(200f, 150f)

        var ctx = StageSelectionModel.resolveSingleClick(pt, nodes, StageSelectionContext())
        assertEquals("outer", ctx.selectedElementId)
        assertNull(ctx.contextParentId)

        ctx = assertIs<StageDoubleClickOutcome.EnteredContainer>(
            StageSelectionModel.resolveDoubleClick(pt, nodes, ctx, supportsText),
        ).next
        assertEquals("outer", ctx.contextParentId)
        assertEquals("inner", ctx.selectedElementId)

        ctx = assertIs<StageDoubleClickOutcome.EnteredContainer>(
            StageSelectionModel.resolveDoubleClick(pt, nodes, ctx, supportsText),
        ).next
        assertEquals("inner", ctx.contextParentId)
        assertEquals("leaf", ctx.selectedElementId)

        assertIs<StageDoubleClickOutcome.StartInlineTextEdit>(
            StageSelectionModel.resolveDoubleClick(pt, nodes, ctx, supportsText),
        )

        // Esc pops inner -> outer -> cleared.
        ctx = assertNotNull(StageSelectionModel.resolveEscape(ctx, nodes))
        assertEquals("outer", ctx.contextParentId)
        assertEquals("inner", ctx.selectedElementId)

        ctx = assertNotNull(StageSelectionModel.resolveEscape(ctx, nodes))
        assertNull(ctx.contextParentId)
        assertEquals("outer", ctx.selectedElementId)

        ctx = assertNotNull(StageSelectionModel.resolveEscape(ctx, nodes))
        assertNull(ctx.selectedElementId)
    }

    @Test
    fun buildPickerHierarchyIndentsChildrenUnderParentsAndGuardsAgainstCycles() {
        val card = snapshot("card", "SurfaceCard", Rect(100f, 100f, 600f, 400f))
        val title = snapshot("title", "Text", Rect(120f, 120f, 400f, 160f), parentId = "card")
        val badge = snapshot("badge", "Box", Rect(120f, 180f, 240f, 220f), parentId = "card")
        val badgeText = snapshot("badgeText", "Text", Rect(130f, 190f, 220f, 210f), parentId = "badge")
        val standalone = snapshot("standalone", "Text", Rect(700f, 100f, 900f, 160f))

        val hierarchy = StageSelectionModel.buildPickerHierarchy(
            listOf(badgeText, title, standalone, badge, card),
        )
        assertEquals(
            listOf(
                "card" to 0,
                "title" to 1,
                "badge" to 1,
                "badgeText" to 2,
                "standalone" to 0,
            ),
            hierarchy.map { it.node.elementId to it.depth },
        )

        // Cyclic parent links must not infinite-loop or drop elements.
        val cycleA = snapshot("cycleA", "Box", Rect(0f, 0f, 100f, 100f), parentId = "cycleB")
        val cycleB = snapshot("cycleB", "Box", Rect(10f, 10f, 90f, 90f), parentId = "cycleA")
        val cycleHierarchy = StageSelectionModel.buildPickerHierarchy(listOf(cycleA, cycleB))
        assertEquals(setOf("cycleA", "cycleB"), cycleHierarchy.map { it.node.elementId }.toSet())
    }

    @Test
    fun resolveContainerForInteractiveSelectsEnclosingContainer() {
        val card = snapshot("s1:card", "SurfaceCard", Rect(100f, 100f, 700f, 420f))
        val nodes = listOf(card)

        val withExplicitParent = StageInteractiveEntry(
            id = "s1:specimen-checkbox",
            label = "Checkbox",
            stageBounds = Rect(140f, 160f, 360f, 220f),
            parentElementId = "s1:card",
        )
        assertEquals(
            "s1:card",
            StageSelectionModel.resolveContainerForInteractive(withExplicitParent, nodes),
        )

        val fallbackByBounds = StageInteractiveEntry(
            id = "s1:specimen-unnamed",
            label = "Slider",
            stageBounds = Rect(140f, 160f, 360f, 220f),
            parentElementId = null,
        )
        assertEquals(
            "s1:card",
            StageSelectionModel.resolveContainerForInteractive(fallbackByBounds, nodes),
        )
    }
}
