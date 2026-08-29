package dev.sebastiano.deck

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FoundationDeckTest {
    @Test
    fun deckUsesFixedTalkFormatAndRepresentativeSceneStates() {
        val deck = createFoundationDeck()

        assertEquals("Conference deck — foundation spike", deck.title)
        assertEquals(IntSize(1920, 1080), deck.format.size)
        assertEquals(listOf(3, 2, 1, 3), deck.scenes.map { it.frames.size })
        assertEquals(deck.indices, deck.indices.sorted())
    }

    @Test
    fun everyFoundationProofIsOwnedByAnExplicitScene() {
        assertEquals(
            FoundationProof.entries.toSet(),
            FoundationSceneSpec.entries.flatMapTo(mutableSetOf()) { it.proves },
        )
    }

    @Test
    fun designSystemHasDistinctAndConsistentTextRoles() {
        assertTrue(DeckDesign.title.fontSize > DeckDesign.heading.fontSize)
        assertTrue(DeckDesign.heading.fontSize > DeckDesign.body.fontSize)
        assertEquals(DeckDesign.code.fontSize, DeckDesign.codeEmphasis.fontSize)
        assertNotEquals(DeckDesign.background, DeckDesign.accent)
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
}
