package dev.sebastiano.ciacola.inspector

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StageElementContractsTest {
    @Test
    fun elementPatchAndOverridesTrackNonEmptyChanges() {
        val empty = ElementPatch(elementId = "s0-title")
        assertTrue(empty.isEmpty)

        val patch = ElementPatch(elementId = "s0-title", text = "Updated", fontSizeSp = 64f)
        assertFalse(patch.isEmpty)

        val file = DeckElementOverridesFile(elements = mapOf(patch.elementId to patch))
        assertTrue(file.hasOverrides)
        assertEquals("Updated", file.patchFor("s0-title")?.text)
        assertNull(file.patchFor("missing"))
    }

    @Test
    fun stageInspectionRegistryRecordsAndFiltersBySlide() {
        val registry = StageInspectionRegistry()
        val s0 = StageNodeSnapshot(
            elementId = "s0-title",
            kind = "Title",
            label = "Title",
            sceneIndex = 0,
            beatIndex = 0,
            stageBounds = Rect(64f, 64f, 600f, 160f),
            resolvedText = "Hello",
            effectiveForeground = Color.White,
            effectiveBackground = Color.Black,
            effectiveFontSizeSp = 72f,
        )
        val s1 = s0.copy(elementId = "s1-body", sceneIndex = 1, beatIndex = 2)

        registry.record(s0)
        registry.record(s1)

        assertEquals(listOf(s0), registry.nodesForSlide(0, 0))
        assertEquals(listOf(s1), registry.nodesForSlide(1, 2))
        registry.remove("s0-title")
        assertTrue(registry.nodesForSlide(0, 0).isEmpty())
    }
}
