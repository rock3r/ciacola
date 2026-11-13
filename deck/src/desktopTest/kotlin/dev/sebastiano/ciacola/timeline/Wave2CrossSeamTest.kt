package dev.sebastiano.ciacola.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.EaselWindow
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.rememberThemeSwitcherWindow
import dev.sebastiano.ciacola.theme.DeckThemePreset
import kotlin.test.Test
import kotlin.test.assertEquals

/** Seams between the Wave-2 fixes of different areas (easel windows, motion, timeline studio). */
@OptIn(ExperimentalTestApi::class)
class Wave2CrossSeamTest {

    @Test
    fun themeInspectorOpensAtACompactSizeInsteadOfTheRoomyToolWindowDefault() = runComposeUiTest {
        lateinit var window: EaselWindow
        setContent { window = rememberThemeSwitcherWindow(DeckThemePreset.StageDark) {} }
        waitForIdle()
        assertEquals(DpSize(420.dp, 360.dp), window.initialSize)
    }

    @Test
    fun fadeMoveTracksSeedTheInspectorWithTheirRealEntranceOffset() {
        val snapshot = indexDeckTimeline(createFoundationDeck(includeKeynoteEffectsLab = true))
        fun block(trackId: String) = snapshot.tracks.first { it.trackId == trackId }.blocks.first()

        // The leader rows fade-move up from 24 dp below; the Inspector must start from that offset,
        // or nudging Offset X would silently snap the rows' Y entrance to 0 dp.
        val fadeMove = block("lab-leader-stagger")
        assertEquals(0f, fadeMove.defaultOffsetXDp)
        assertEquals(24f, fadeMove.defaultOffsetYDp)
        val fadeMoveItem = block("lab-leader-stagger#item0")
        assertEquals(24f, fadeMoveItem.defaultOffsetYDp)

        // Pop / FromDarkness entrances have no default translation.
        val pop = block("lab-immutable-stamp")
        assertEquals(0f, pop.defaultOffsetXDp)
        assertEquals(0f, pop.defaultOffsetYDp)
    }
}
