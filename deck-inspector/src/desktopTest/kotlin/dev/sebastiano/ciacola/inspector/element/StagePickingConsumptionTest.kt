package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.effects.DeckSurfaceCard
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.stageInteractive
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StagePickingConsumptionTest {

    @Test
    fun liveCheckboxTogglesWithoutHijackingElementSelectionWhileAltClickSelectsContainer() = runComposeUiTest {
        val dir = Files.createTempDirectory("ciacola-stage-interactive")
        val store = ElementOverridesStore(dir)
        val controller = ElementEditorController(store).apply { editorActive = true }
        var checked by mutableStateOf(false)

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(LocalElementOverrides provides store.data().also { store.revision }) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        controller.stageDecorator.decorate {
                            Box(Modifier.fillMaxSize()) {
                                DeckSurfaceCard(
                                    elementId = "s5:specimen-card",
                                    label = "Live Specimen Card",
                                    fillColor = Color(0xFF1E293B),
                                    borderColor = Color(0xFF38BDF8),
                                    modifier = Modifier
                                        .offset(x = 120.dp, y = 100.dp)
                                        .size(360.dp, 200.dp),
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        DeckEditableText(
                                            elementId = "s5:specimen-title",
                                            text = "Interactive Specimen",
                                            color = Color.White,
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .stageInteractive(id = "s5:live-toggle", label = "Live Checkbox")
                                                .testTag("live-specimen-box"),
                                        ) {
                                            Checkbox(
                                                checked = checked,
                                                onCheckedChange = { checked = it },
                                                modifier = Modifier.testTag("live-specimen-checkbox"),
                                            )
                                            Text("Enable Effect", color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()

        val checkboxCenter = onNodeWithTag("live-specimen-checkbox").fetchSemanticsNode().boundsInRoot.center

        // 1. Plain click on the live Checkbox must toggle it and MUST NOT select the card or any element.
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            click(checkboxCenter)
        }
        waitForIdle()
        assertTrue(checked, "Expected plain click to toggle live Checkbox")
        assertNull(controller.selectedElementId, "Clicking live Checkbox must not hijack element selection")

        // 2. Hovering over the live specimen exposes hoveredInteractiveId (renders 'Live · ⌥Click selects' badge).
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            moveTo(checkboxCenter)
        }
        waitForIdle()
        assertEquals("s5:live-toggle", controller.hoveredInteractiveId)
        assertNull(controller.hoveredElementId)

        // 3. Alt+Click on the live specimen selects the enclosing DeckSurfaceCard WITHOUT toggling the Checkbox!
        onNodeWithTag("ciacola-direct-edit-overlay").performKeyInput { keyDown(Key.AltLeft) }
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput { click(checkboxCenter) }
        onNodeWithTag("ciacola-direct-edit-overlay").performKeyInput { keyUp(Key.AltLeft) }
        waitForIdle()
        assertTrue(checked, "Alt+Click must select container without toggling live Checkbox")
        assertEquals("s5:specimen-card", controller.selectedElementId)

        // 4. Plain click on the Checkbox again toggles it back to false and leaves selection untouched.
        onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
            click(checkboxCenter)
        }
        waitForIdle()
        assertFalse(checked, "Second plain click must toggle live Checkbox back to false")
        assertEquals("s5:specimen-card", controller.selectedElementId)
    }
}
