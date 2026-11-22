package dev.sebastiano.ciacola.inspector

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StageElementContractsTest {
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

        var dynamicAlpha = 0.5f
        registry.record(s0, alphaProvider = { dynamicAlpha })
        registry.record(s1)

        assertEquals(0.5f, registry.effectiveAlphaOf("s0-title"))
        assertEquals(listOf(s0.copy(effectiveAlpha = 0.5f)), registry.nodesForSlide(0, 0))
        assertEquals(listOf(s1), registry.nodesForSlide(1, 2))
        registry.remove("s0-title")
        assertTrue(registry.nodesForSlide(0, 0).isEmpty())
    }
}
