package dev.sebastiano.ciacola

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FoundationDeckTest {
    @Test
    fun catalogRegistersAllFourCanonicalDocumentDecks() {
        assertEquals("foundation", DeckCatalog.FOUNDATION_ID)
        assertEquals("shaders-quickie-2025", DeckCatalog.SHADERS_QUICKIE_ID)
        assertEquals("jewel-talk-2024", DeckCatalog.JEWEL_TALK_ID)
        assertEquals("not-just-plain-text-2024", DeckCatalog.NOT_JUST_PLAIN_TEXT_ID)
        assertEquals(DeckCatalog.FOUNDATION_ID, DeckCatalog.defaultEntry.id)
        assertEquals("decks/foundation.deck.json", DeckCatalog.foundation.resourcePath)
        assertEquals("decks/foundation.assets", DeckCatalog.foundation.assetsResourceDir)
    }

    @Test
    fun designSystemThemesHaveDistinctAndConsistentTextAndColorRoles() {
        for (theme in listOf(DeckThemes.stageDark(), DeckThemes.keynoteLight())) {
            assertTrue(theme.typography.title.fontSize > theme.typography.heading.fontSize)
            assertTrue(theme.typography.heading.fontSize > theme.typography.body.fontSize)
            assertEquals(theme.typography.code.fontSize, theme.typography.codeEmphasis.fontSize)
            assertNotEquals(theme.colors.background, theme.colors.accent)
        }
    }

    @Test
    fun meshPlannerProducesACompleteTriangleGrid() {
        val mesh = planMesh(
            listOf(
                listOf(Offset(0f, 0f) to Color.Red, Offset(1f, 0f) to Color.Blue),
                listOf(Offset(0f, 1f) to Color.Green, Offset(1f, 1f) to Color.Yellow),
            ),
        )

        assertEquals(4, mesh.positions.size)
        assertEquals(4, mesh.colors.size)
        assertEquals(listOf(0, 2, 3, 0, 3, 1), mesh.indices)
    }

    @Test
    fun loopingVisualChangesWithTimeWithoutAStateAdvance() {
        val state = MeshLoopState(sceneState = 0)

        val first = animatedMeshPoints(state, elapsedMillis = 0)
        val later = animatedMeshPoints(state, elapsedMillis = 1_000)

        assertEquals(0, state.sceneState)
        assertNotEquals(first, later)
    }

    @Test
    fun meshPaletteAdaptsLuminanceToLightAndDarkThemes() {
        val state = MeshLoopState(sceneState = 0)
        val darkColors = animatedMeshPoints(state, elapsedMillis = 500, theme = DeckThemes.stageDark())
            .flatten()
            .map { it.second }
        val lightColors = animatedMeshPoints(state, elapsedMillis = 500, theme = DeckThemes.keynoteLight())
            .flatten()
            .map { it.second }

        assertTrue(
            lightColors.all { it.luminance() > 0.45f },
            "Expected all Keynote Light mesh vertices to stay light (luminance > 0.45), got ${lightColors.map { it.luminance() }}",
        )
        assertTrue(
            lightColors.map { it.luminance() }.average() > darkColors.map { it.luminance() }.average() + 0.40,
            "Expected Keynote Light mesh average luminance to substantially exceed Stage Dark",
        )
    }
}
