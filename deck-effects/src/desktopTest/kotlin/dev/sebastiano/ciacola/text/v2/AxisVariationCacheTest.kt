package dev.sebastiano.ciacola.text.v2

import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Typeface

/** Bounded, quantized variable-axis typeface clones used by axis motion presets. */
class AxisVariationCacheTest {

    @AfterTest
    fun tearDown() = AxisVariationCache.clearForTests()

    private fun variableFace(name: String): Typeface {
        val bytes = File(MagicTextV2TestSupport.bundledFontsDir(), name).readBytes()
        return Data.makeFromBytes(bytes).use { data -> assertNotNull(FontMgr.default.makeFromData(data, 0)) }
    }

    private fun Typeface.coordinate(tag: String): Float = assertNotNull(variations?.firstOrNull { it.tag == tag }).value

    @Test
    fun `offsets are added to the resting coordinate and clamped to the axis range`() {
        val base = variableFace("HankenGrotesk-VariableFont_wght.ttf")
        val rest = base.makeClone(arrayOf(FontVariation("wght", 400f)), 0)
        val axis = assertNotNull(rest.variationAxes?.single { it.tag == "wght" })

        val bolder = assertNotNull(AxisVariationCache.withOffsets(rest, mapOf("wght" to 250f)))
        assertTrue(abs(bolder.coordinate("wght") - 650f) <= (axis.maxValue - axis.minValue) / 512f, "${bolder.coordinate("wght")}")

        val clamped = assertNotNull(AxisVariationCache.withOffsets(rest, mapOf("wght" to 5000f)))
        assertEquals(axis.maxValue, clamped.coordinate("wght"), 0.01f)

        // Same quantized coordinates come from the cache.
        assertSame(bolder, AxisVariationCache.withOffsets(rest, mapOf("wght" to 250.2f)))
    }

    @Test
    fun `offsets that miss the face or round to rest need no clone`() {
        val rest = variableFace("HankenGrotesk-VariableFont_wght.ttf")
        assertNull(AxisVariationCache.withOffsets(rest, mapOf("wdth" to 20f)))
        assertNull(AxisVariationCache.withOffsets(rest, mapOf("wght" to 0.01f)))
        assertNull(AxisVariationCache.withOffsets(rest, emptyMap()))
    }

    @Test
    fun `two axes vary independently`() {
        val rest = variableFace("Roboto-VariableFont_wdth_wght.ttf")
        val restWdth = rest.variations?.firstOrNull { it.tag == "wdth" }?.value
            ?: assertNotNull(rest.variationAxes?.single { it.tag == "wdth" }).defaultValue
        val narrow = assertNotNull(AxisVariationCache.withOffsets(rest, mapOf("wdth" to -15f)))
        assertEquals(restWdth - 15f, narrow.coordinate("wdth"), 0.5f)
    }

    @Test
    fun `native clones stay bounded over a long animation`() {
        val rest = variableFace("HankenGrotesk-VariableFont_wght.ttf")
        repeat(5_000) { frame ->
            AxisVariationCache.withOffsets(rest, mapOf("wght" to (frame % 1_600) * 0.5f - 400f))
        }
        assertTrue(
            AxisVariationCache.liveCount <= AxisVariationCache.CAPACITY + AxisVariationCache.RETIRED_LIMIT,
            "${AxisVariationCache.liveCount}",
        )
    }
}
