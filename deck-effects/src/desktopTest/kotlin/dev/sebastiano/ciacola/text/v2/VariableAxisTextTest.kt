package dev.sebastiano.ciacola.text.v2

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface

/** Specimen text drawn at absolute, quantized variable-axis coordinates. */
class VariableAxisTextTest {

    @AfterTest
    fun tearDown() = AxisVariationCache.clearForTests()

    private val roboto: Typeface by lazy {
        val bytes = File(MagicTextV2TestSupport.bundledFontsDir(), "Roboto-VariableFont_wdth_wght.ttf").readBytes()
        Data.makeFromBytes(bytes).use { data -> assertNotNull(FontMgr.default.makeFromData(data, 0)) }
    }

    private fun Typeface.coordinate(tag: String): Float = assertNotNull(variations?.firstOrNull { it.tag == tag }).value

    @Test
    fun `absolute coordinates are clamped and quantized onto shared clones`() {
        val light = VariableAxisTextLayout.typefaceAt(roboto, mapOf("wght" to 250.0, "wdth" to 80.0))
        assertEquals(250f, light.coordinate("wght"), 1.6f)
        assertEquals(80f, light.coordinate("wdth"), 0.05f)
        // Within one quantization step the same clone comes back.
        assertSame(light, VariableAxisTextLayout.typefaceAt(roboto, mapOf("wght" to 250.3, "wdth" to 80.002)))
        val clamped = VariableAxisTextLayout.typefaceAt(roboto, mapOf("wdth" to 10.0))
        assertEquals(75f, clamped.coordinate("wdth"), 0.01f)
    }

    @Test
    fun `per-character axes lay out each character with its own clone`() {
        val uniform = assertNotNull(VariableAxisTextLayout.measure("Axis", roboto, 100f, mapOf("wdth" to 100.0), null))
        assertEquals(1, uniform.runs.size)
        val condensed = assertNotNull(
            VariableAxisTextLayout.measure("Axis", roboto, 100f, mapOf("wdth" to 100.0)) { index, _ ->
                if (index == 0) mapOf("wdth" to 75.0) else emptyMap()
            },
        )
        assertEquals(4, condensed.runs.size)
        assertTrue(condensed.runs[0].typeface !== condensed.runs[1].typeface)
        assertTrue(condensed.width < uniform.width, "${condensed.width} vs ${uniform.width}")
        assertTrue(condensed.height > 0f)
    }

    @Test
    fun `text that cannot vary its axes falls back`() {
        assertNull(VariableAxisTextLayout.measure("Axis", roboto, 100f, mapOf("XXXX" to 1.0), null))
        assertNull(VariableAxisTextLayout.measure("שלום abc", roboto, 100f, mapOf("wght" to 700.0), null))
        assertNull(VariableAxisTextLayout.measure("a😀", roboto, 100f, mapOf("wght" to 700.0), null))
        assertNull(VariableAxisTextLayout.measure("", roboto, 100f, mapOf("wght" to 700.0), null))
    }

    @Test
    fun `a long per-character loop keeps native clones bounded`() {
        val text = "Variable"
        val frames = 2_000
        for (frame in 0 until frames) {
            val phase = 2.0 * PI * frame / 144.0
            VariableAxisTextLayout.measure(text, roboto, 220f, mapOf("wght" to 400.0, "wdth" to 100.0)) { index, _ ->
                val s = sin(phase - index * 0.6)
                mapOf("wght" to 500.0 + 300.0 * s, "wdth" to 87.5 + 12.5 * s)
            }
        }
        val bound = AxisVariationCache.CAPACITY + AxisVariationCache.RETIRED_LIMIT
        assertTrue(AxisVariationCache.liveCount <= bound, "live clones ${AxisVariationCache.liveCount} > $bound")
        // A repeating loop revisits the same quantized coordinates rather than minting new ones.
        val again = VariableAxisTextLayout.measure(text, roboto, 220f, emptyMap()) { index, _ ->
            val s = sin(-index * 0.6)
            mapOf("wght" to 500.0 + 300.0 * s, "wdth" to 87.5 + 12.5 * s)
        }
        assertTrue(abs(assertNotNull(again).runs[0].typeface.coordinate("wght") - 500f) <= 1.6f)
    }
}
