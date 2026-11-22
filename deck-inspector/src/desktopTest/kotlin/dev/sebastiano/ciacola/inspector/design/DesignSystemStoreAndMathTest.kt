package dev.sebastiano.ciacola.inspector.design

import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesignSystemStoreAndMathTest {
    @Test
    fun colorConversionsAndHexParsingRoundtripAccurately() {
        val sample = Color(0xFFFF6B5EL)
        assertEquals(0xFFFF6B5EL, sample.toArgbLong())
        assertEquals("#FF6B5E", sample.toHexString())
        assertEquals("#FFFF6B5E", sample.toHexString(includeAlpha = true))
        assertEquals("0xFFFF6B5EL", sample.toKotlinHexLiteral())

        assertEquals(sample, parseHexColorOrNull("#ff6b5e"))
        assertEquals(sample, parseHexColorOrNull("FF6B5E"))
        assertEquals(Color(0x80FF6B5EL), parseHexColorOrNull("#80FF6B5E"))
        assertNull(parseHexColorOrNull("#12345"))
        assertNull(parseHexColorOrNull("not-hex"))

        val hsv = sample.toHsv()
        val fromHsv = hsvToColor(hsv.hue, hsv.saturation, hsv.value, hsv.alpha)
        assertEquals(sample.red, fromHsv.red, 0.01f)
        assertEquals(sample.green, fromHsv.green, 0.01f)
        assertEquals(sample.blue, fromHsv.blue, 0.01f)

        val hsl = sample.toHsl()
        val fromHsl = hslToColor(hsl.hue, hsl.saturation, hsl.lightness, hsl.alpha)
        assertEquals(sample.red, fromHsl.red, 0.01f)
        assertEquals(sample.green, fromHsl.green, 0.01f)
        assertEquals(sample.blue, fromHsl.blue, 0.01f)
    }

    @Test
    fun fixColorForWcagAaNudgesLightnessToAtLeast455ForDarkAndLightThemes() {
        val darkBg = DeckThemes.StageDarkColors.background
        val darkSurface = DeckThemes.StageDarkColors.surface
        val lowContrastDark = Color(0xFF3E4451)
        assertTrue(worstWcagContrastRatio(lowContrastDark, darkBg, darkSurface) < 4.5f)

        val fixedDark = fixColorForWcagAa(lowContrastDark, darkBg, darkSurface)
        assertTrue(
            worstWcagContrastRatio(fixedDark, darkBg, darkSurface) >= 4.55f,
            "Expected fixed dark token to reach >= 4.55:1, got ${worstWcagContrastRatio(fixedDark, darkBg, darkSurface)}",
        )

        val lightBg = DeckThemes.KeynoteLightColors.background
        val lightSurface = DeckThemes.KeynoteLightColors.surface
        val lowContrastLight = Color(0xFFD99B16)
        assertTrue(worstWcagContrastRatio(lowContrastLight, lightBg, lightSurface) < 4.5f)

        val fixedLight = fixColorForWcagAa(lowContrastLight, lightBg, lightSurface)
        assertTrue(
            worstWcagContrastRatio(fixedLight, lightBg, lightSurface) >= 4.55f,
            "Expected fixed light token to reach >= 4.55:1, got ${worstWcagContrastRatio(fixedLight, lightBg, lightSurface)}",
        )

        // Already compliant colors are returned untouched.
        val compliant = DeckThemes.StageDarkColors.foreground
        assertEquals(compliant, fixColorForWcagAa(compliant, darkBg, darkSurface))
    }
}
