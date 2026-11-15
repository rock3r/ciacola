package dev.sebastiano.ciacola.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.inspector.DeckElementOverridesFile
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DeckSkslShaderTest {

    private val tunableColorShader = """
        uniform float2 iResolution;
        uniform float redLevel;

        half4 main(float2 fragCoord) {
            return half4(clamp(redLevel, 0.0, 1.0), 0.1, 0.1, 1.0);
        }
    """.trimIndent()

    @Test
    fun compileCacheReturnsSameRuntimeEffectInstanceForIdenticalSource() {
        val first = compileOrGetSkslEffect(tunableColorShader).getOrThrow()
        val second = compileOrGetSkslEffect(tunableColorShader).getOrThrow()
        assertSame(first, second, "Expected identical SkSL source to hit RuntimeEffect cache")
        assertNull(validateSkslShader(tunableColorShader))
    }

    @Test
    fun invalidSkslShowsRedCompileErrorOverlay() = runComposeUiTest {
        val brokenSksl = "half4 main(float2 fragCoord) { return totallyBrokenSymbol; }"
        assertNotNull(validateSkslShader(brokenSksl))

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                DeckSkslShader(
                    id = "test:broken",
                    sksl = brokenSksl,
                    modifier = Modifier.size(320.dp, 180.dp),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("deck-sksl-error-test:broken").assertIsDisplayed()
    }

    @Test
    fun uniformFloatOverrideUpdatesPixelsAndRecordsStageNodeSnapshot() = runComposeUiTest {
        var overrides by mutableStateOf(DeckElementOverridesFile())
        val registry = StageInspectionRegistry()
        var lastResolvedRed = -1f

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(
                    LocalElementOverrides provides overrides,
                    LocalStageInspectionRegistry provides registry,
                ) {
                    Box(Modifier.size(160.dp, 160.dp)) {
                        DeckSkslShader(
                            id = "test:shader",
                            sksl = tunableColorShader,
                            label = "Tunable Red Shader",
                            modifier = Modifier.size(160.dp, 160.dp).testTag("tunable-shader"),
                        ) {
                            lastResolvedRed = uniformFloat("redLevel", default = 0.15f, range = 0f..1f)
                        }
                    }
                }
            }
        }
        waitForIdle()

        assertEquals(0.15f, lastResolvedRed, 1e-4f)
        val snap = registry.nodes["test:shader"]
        assertNotNull(snap, "Expected DeckSkslShader to record StageNodeSnapshot")
        assertEquals("Shader", snap.kind)
        assertEquals(1, snap.shaderUniforms.size)
        assertEquals("redLevel", snap.shaderUniforms[0].name)
        assertEquals(0.15f, snap.shaderUniforms[0].default, 1e-4f)
        assertEquals(0f, snap.shaderUniforms[0].min, 1e-4f)
        assertEquals(1f, snap.shaderUniforms[0].max, 1e-4f)

        val beforePixels = onNodeWithTag("tunable-shader").captureToImage().toPixelMap()
        val beforeCenterRed = beforePixels[beforePixels.width / 2, beforePixels.height / 2].red

        runOnIdle {
            overrides = DeckElementOverridesFile(
                elements = mapOf(
                    "test:shader" to ElementPatch(
                        elementId = "test:shader",
                        uniforms = mapOf("redLevel" to 0.92f),
                    ),
                ),
            )
        }
        waitForIdle()

        assertEquals(0.92f, lastResolvedRed, 1e-4f)
        val afterPixels = onNodeWithTag("tunable-shader").captureToImage().toPixelMap()
        val afterCenterRed = afterPixels[afterPixels.width / 2, afterPixels.height / 2].red
        assertTrue(
            beforeCenterRed < 0.35f && afterCenterRed > 0.75f,
            "Expected uniformFloat override to change rendered red channel ($beforeCenterRed -> $afterCenterRed)",
        )
    }

    @Test
    fun compiledEffectCacheIsBoundedAndUnknownUniformSurfacesDiagnosticOverlay() = runComposeUiTest {
        for (i in 0 until MAX_COMPILED_SKSL_CACHE_ENTRIES + 20) {
            val variant = """
                half4 main(float2 fragCoord) {
                    return half4(${(i % 100) / 100f}, 0.2, 0.3, 1.0);
                }
            """.trimIndent()
            assertNull(validateSkslShader(variant))
        }
        assertTrue(
            compiledSkslCacheSize() <= MAX_COMPILED_SKSL_CACHE_ENTRIES,
            "Expected compiled SkSL cache size <= $MAX_COMPILED_SKSL_CACHE_ENTRIES, got ${compiledSkslCacheSize()}",
        )

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                DeckSkslShader(
                    id = "test:bad-uniform",
                    sksl = tunableColorShader,
                    modifier = Modifier.size(200.dp, 140.dp),
                ) {
                    float("misspelledUniformName", 0.5f)
                }
            }
        }
        waitForIdle()
        onNodeWithTag("deck-sksl-error-test:bad-uniform").assertIsDisplayed()
    }

    @Test
    fun uniformParserAcceptsArraysCommaListsAndLayoutQualifiersWithoutFalseOverlay() = runComposeUiTest {
        val richUniformShader = """
            // uniform float commentedLineUniform;
            /* uniform float commentedBlockUniform; */
            uniform float2 iResolution;
            uniform float weights[4];
            uniform float gain, bias;
            uniform half2 offsets[2], pivot;
            layout(color) uniform float3 tint;

            half4 main(float2 fragCoord) {
                float w = weights[0] + weights[1] + weights[2] + weights[3];
                float off = offsets[0].x + offsets[1].y + pivot.x;
                return half4(clamp(tint * gain + vec3(bias + w * 0.1 + off * 0.0), 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        assertEquals(
            setOf("iResolution", "weights", "gain", "bias", "offsets", "pivot", "tint"),
            parseDeclaredSkslUniformNames(richUniformShader),
        )
        assertNull(validateSkslShader(richUniformShader))

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                DeckSkslShader(
                    id = "test:rich-uniforms",
                    sksl = richUniformShader,
                    modifier = Modifier.size(160.dp, 160.dp).testTag("rich-uniform-shader"),
                ) {
                    floatArray("weights", 0.25f, 0.25f, 0.25f, 0.25f)
                    float("gain", 0.8f)
                    float("bias", 0.05f)
                    floatArray("offsets", 0f, 0f, 0f, 0f)
                    float2("pivot", 0f, 0f)
                    float3("tint", 0.9f, 0.2f, 0.1f)
                }
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodesWithTag("deck-sksl-error-test:rich-uniforms").fetchSemanticsNodes().size)

        val pixels = onNodeWithTag("rich-uniform-shader").captureToImage().toPixelMap()
        assertEquals(0, onAllNodesWithTag("deck-sksl-error-test:rich-uniforms").fetchSemanticsNodes().size)
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue(
            center.red > 0.7f && center.blue < 0.35f,
            "Expected layout-qualified, comma-listed, and array uniforms to render cleanly (got R=${center.red}, B=${center.blue})",
        )
    }
}
