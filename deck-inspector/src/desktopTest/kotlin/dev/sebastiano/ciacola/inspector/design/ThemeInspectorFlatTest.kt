package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.ThemeMotionDefaults
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ThemeInspectorFlatTest {

    private fun doc(themes: Map<String, DeckThemeSpec>) = DeckDocument(
        id = "theme-doc",
        title = "Theme",
        defaultTheme = themes.keys.first(),
        themes = themes,
        slides = listOf(SlideSpec(id = "s1")),
    )

    @Test
    fun readableTokenAndCurveNames() {
        assertEquals("Elevated Surface", humanizeTokenKey("elevatedSurface"))
        assertEquals("On Accent", humanizeTokenKey("onAccent"))
        assertEquals("Standard", curveDisplayName(Curve.Preset("fastOutSlowIn")))
        assertEquals("Standard", curveDisplayName(null))
        assertEquals("Ease in-out", curveDisplayName(Curve.Bezier(0.42, 0.0, 0.58, 1.0)))
        assertEquals("Linear", curveDisplayName(Curve.Linear))
        assertEquals("Spring", curveDisplayName(Curve.Spring(0.8, 380.0)))
        assertEquals("Custom", curveDisplayName(Curve.Bezier(0.11, 0.22, 0.33, 0.44)))
        assertEquals(listOf("Standard", "Ease in-out", "Ease out", "Linear", "Spring"), THEME_CURVE_CHOICES.map(::curveDisplayName))
        assertEquals("Dark", themePresetLabel("stageDark"))
        assertEquals("Light", themePresetLabel("keynoteLight"))
    }

    @Test
    fun flatThemeEditorHasNoDuplicateUndoAndHumanizedLabels() = runComposeUiTest {
        val store = DeckDocumentStore(
            doc(mapOf("dark" to DeckThemeSpec(preset = "stageDark", motion = ThemeMotionDefaults(curve = Curve.Bezier(0.42, 0.0, 0.58, 1.0))))),
            initialLoad = DeckLoadState.Loaded,
        )
        val controller = DocumentThemeInspectorController(store)
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(420.dp, 900.dp)) { DocumentThemeInspectorUi(controller = controller) }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-doc-theme-undo", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Elevated Surface", useUnmergedTree = true).assertExists()
        onAllNodesWithText("elevatedSurface", useUnmergedTree = true).assertCountEquals(0)
        // Single theme: the base Dark/Light row is shown (no selector can switch it).
        onNodeWithTag("ciacola-doc-theme-preset-stageDark", useUnmergedTree = true).assertExists()

        onNodeWithTag("ciacola-doc-theme-tab-motion", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("ciacola-doc-theme-motion-section", useUnmergedTree = true).assertExists()
        onNodeWithText("Ease in-out", useUnmergedTree = true).assertExists()
        onAllNodesWithText("Bezier", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun darkLightRowHiddenWhenThemeSelectorOwnsItAndUndoOptional() = runComposeUiTest {
        val store = DeckDocumentStore(
            doc(mapOf("dark" to DeckThemeSpec(preset = "stageDark"), "light" to DeckThemeSpec(preset = "keynoteLight"))),
            initialLoad = DeckLoadState.Loaded,
        )
        val controller = DocumentThemeInspectorController(store)
        setContent {
            CiacolaJewelTheme(isDark = false) {
                Box(Modifier.size(420.dp, 700.dp)) { DocumentThemeInspectorUi(controller = controller, showHeaderUndoRedo = true) }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-doc-theme-preset-stageDark", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-doc-theme-undo", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-doc-theme-colors-section", useUnmergedTree = true).assertExists()
    }
}
