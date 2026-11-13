package dev.sebastiano.ciacola

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.inspector.DeckElementOverridesFile
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class FoundationDeckElementOverridesTest {

    @Test
    fun foundationDeckCapturesStageSnapshotsAndAppliesPerBeatAndStampOverrides() = runComposeUiTest {
        val registry = StageInspectionRegistry()
        var overrides by mutableStateOf(DeckElementOverridesFile())
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope

        setContent {
            val storyboard = createFoundationDeck(
                theme = DeckThemes.stageDark(),
                includeKeynoteEffectsLab = true,
                elementOverrides = overrides,
            )
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalStageInspectionRegistry provides registry) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    animatic = rememberAnimatic(storyboard = { storyboard })
                    Easel(animatic)
                }
            }
        }

        waitForIdle()
        // Beat 0 of Slide 1 renders default eyebrow + prose and captures StageNodeSnapshots.
        onNodeWithText("FOUNDATION / PROVISIONAL").assertIsDisplayed()
        assertNotNull(registry.nodes["s1:eyebrow"])
        assertNotNull(registry.nodes["s1:prose:b0"])
        assertEquals("A scene begins with one deterministic state.", registry.nodes["s1:prose:b0"]?.resolvedText)

        // Apply per-beat override on Beat 1 only: Beat 0 stays untouched!
        overrides = DeckElementOverridesFile(
            elements = mapOf(
                "s1:prose:b1" to ElementPatch(
                    elementId = "s1:prose:b1",
                    text = "Custom Beat One Copy",
                ),
                "s5:stamp" to ElementPatch(
                    elementId = "s5:stamp",
                    text = "CUSTOMIZED",
                    fillColorArgb = 0xFF1E3A8AL,
                ),
            ),
        )
        waitForIdle()
        assertEquals("A scene begins with one deterministic state.", registry.nodes["s1:prose:b0"]?.resolvedText)

        // Advance to Beat 1 -> MagicText displays custom beat-1 copy and records s1:prose:b1.
        runOnIdle { scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        waitForIdle()
        assertEquals("Custom Beat One Copy", registry.nodes["s1:prose:b1"]?.resolvedText)

        // Jump to Slide 5 Beat 2 (Keynote Effects Lab with IMMUTABLE stamp).
        runOnIdle { scope.launch { animatic.jumpTo(Storyboard.Index(4, 2)) } }
        waitForIdle()
        onNodeWithText("CUSTOMIZED").assertIsDisplayed()
        val stampSnap = registry.nodes["s5:stamp"]
        assertNotNull(stampSnap)
        assertEquals("CUSTOMIZED", stampSnap.resolvedText)
        assertEquals(Color(0xFF1E3A8AL), stampSnap.effectiveBackground)
        assertTrue(registry.nodes.containsKey("s5:headline"))
        assertTrue(registry.nodes.containsKey("s5:row-web:leading"))
        assertTrue(registry.nodes.containsKey("s5:row-web:trailing"))
    }
}
