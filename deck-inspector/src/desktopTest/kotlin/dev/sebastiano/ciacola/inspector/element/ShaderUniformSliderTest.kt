package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.inspector.StageElementMetadata
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.inspector.StageShaderUniformSpec
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ShaderUniformSliderTest {

    @Test
    fun shaderUniformSlidersEditElementPatchUniformsExportKotlinAndUndoCleanly() = runComposeUiTest {
        val dir = Files.createTempDirectory("ciacola-shader-uniforms")
        val store = ElementOverridesStore(dir)
        val registry = StageInspectionRegistry()
        val uniforms = listOf(
            StageShaderUniformSpec(name = "uAmplitude", default = 0.5f, min = 0f, max = 2f),
            StageShaderUniformSpec(name = "uFrequency", default = 4f, min = 1f, max = 12f),
        )
        registry.recordMetadata(
            StageElementMetadata(
                elementId = "sq:s5:wave-shader",
                supportsText = false,
            ),
        )
        registry.record(
            StageNodeSnapshot(
                elementId = "sq:s5:wave-shader",
                kind = "ShaderBox",
                label = "Wave Shader",
                sceneIndex = 0,
                beatIndex = 0,
                stageBounds = Rect(120f, 120f, 920f, 620f),
                isEditable = true,
                shaderUniforms = uniforms,
            ),
        )

        val controller = ElementEditorController(
            overridesStore = store,
            inspectionRegistry = registry,
        ).apply {
            editorActive = true
            selectElement("sq:s5:wave-shader")
        }

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                Box(Modifier.width(340.dp)) {
                    CiacolaJewelTheme(isDark = true) {
                        ElementEditorPanel(controller = controller)
                    }
                }
            }
        }
        waitForIdle()

        // Both uniform controls render and start non-overridden.
        onNodeWithTag("ciacola-element-uniform-uAmplitude-slider", useUnmergedTree = true).performScrollTo().assertExists()
        onNodeWithTag("ciacola-element-uniform-uFrequency-slider", useUnmergedTree = true).performScrollTo().assertExists()
        onNodeWithTag("ciacola-element-uniform-uAmplitude-reset", useUnmergedTree = true).performScrollTo().assertIsNotEnabled()

        // Dragging uAmplitude slider writes ElementPatch.uniforms["uAmplitude"] in ONE coalesced undo entry.
        onNodeWithTag("ciacola-element-uniform-uAmplitude-slider", useUnmergedTree = true).performScrollTo().performMouseInput {
            val midY = height / 2f
            moveTo(Offset(width * 0.25f, midY))
            press()
            moveTo(Offset(width * 0.65f, midY))
            moveTo(Offset(width * 0.80f, midY))
            release()
        }
        waitForIdle()

        val updatedAmplitude = assertNotNull(store.patchFor("sq:s5:wave-shader")?.uniforms?.get("uAmplitude"))
        assertTrue(updatedAmplitude > 1.0f, "Expected uAmplitude > 1.0 after dragging to 80%, got $updatedAmplitude")
        onNodeWithTag("ciacola-element-uniform-uAmplitude-reset", useUnmergedTree = true).assertIsEnabled()

        // Increment uFrequency via stepper button.
        onNodeWithTag("ciacola-element-uniform-uFrequency-inc", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        val updatedFrequency = assertNotNull(store.patchFor("sq:s5:wave-shader")?.uniforms?.get("uFrequency"))
        assertTrue(updatedFrequency > 4f, "Expected uFrequency > 4f after + click, got $updatedFrequency")

        // Exported Kotlin snippet includes uniforms = mapOf(...).
        val snippet = store.exportKotlinSnippet("sq:s5:wave-shader")
        assertTrue(snippet.contains("uniforms = mapOf("), "Expected exported snippet to include uniforms map, got:\n$snippet")
        assertTrue(snippet.contains("\"uAmplitude\" to "), snippet)
        assertTrue(snippet.contains("\"uFrequency\" to "), snippet)

        // Per-uniform reset removes uFrequency while keeping uAmplitude.
        onNodeWithTag("ciacola-element-uniform-uFrequency-reset", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        assertNull(store.patchFor("sq:s5:wave-shader")?.uniforms?.get("uFrequency"))
        assertEquals(updatedAmplitude, store.patchFor("sq:s5:wave-shader")?.uniforms?.get("uAmplitude"))

        // Undo reverts the uFrequency reset, then undo twice more cleans out the entire patch.
        runOnIdle { assertTrue(store.undo()) }
        assertNotNull(store.patchFor("sq:s5:wave-shader")?.uniforms?.get("uFrequency"))
        runOnIdle { assertTrue(store.undo()) }
        assertNull(store.patchFor("sq:s5:wave-shader")?.uniforms?.get("uFrequency"))
        runOnIdle { assertTrue(store.undo()) }
        assertNull(store.patchFor("sq:s5:wave-shader"))
    }
}
