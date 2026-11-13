package dev.sebastiano.ciacola.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignSystemOverridesTest {
    @Test
    fun wcagLuminanceAndContrastRatioMatchReferenceValues() {
        assertEquals(0f, relativeLuminance(Color.Black), 1e-4f)
        assertEquals(1f, relativeLuminance(Color.White), 1e-4f)
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 1e-3f)
        assertEquals(1f, contrastRatio(Color.Red, Color.Red), 1e-4f)
        assertTrue(contrastRatio(DeckThemes.StageDarkColors.foreground, DeckThemes.StageDarkColors.background) >= 7f)
        assertTrue(contrastRatio(DeckThemes.KeynoteLightColors.foreground, DeckThemes.KeynoteLightColors.background) >= 7f)
    }

    @Test
    fun withOverridesAppliesColorTypographyStyleAndMotionTokens() {
        val base = DeckThemes.stageDark()
        assertFalse(DesignSystemOverrides().hasOverrides)

        val overrides = DesignSystemOverrides(
            colors = ColorTokenOverrides(
                accentArgb = 0xFF33AAFFL,
                spotlightDimAlpha = 0.25f,
            ),
            typography = TypographyTokenOverrides(
                title = TextStyleTokenOverride(fontSizeSp = 84f, fontWeight = 800),
                globalFontScale = 1.1f,
            ),
            styles = StyleTokenOverrides(
                slidePaddingDp = 80f,
                codeCardCornerRadiusDp = 28f,
            ),
            motion = MotionTokenOverrides(
                fadeMoveDurationMs = 420,
                staggerStepMs = 70,
            ),
        )
        assertTrue(overrides.hasOverrides)

        val customized = base.withOverrides(overrides)
        assertEquals(Color(0xFF33AAFFL), customized.colors.accent)
        assertEquals(0.25f, customized.colors.spotlightDimAlpha)
        assertEquals(84f * 1.1f, customized.typography.title.fontSize.value, 1e-3f)
        assertEquals(800, customized.typography.title.fontWeight?.weight)
        assertEquals(80f, customized.styles.slidePadding.value)
        assertEquals(28f, customized.styles.codeCard.cornerRadius.value)
        assertEquals(420, customized.motion.fadeMoveDurationMs)
        assertEquals(70, customized.motion.staggerStepMs)
    }
}
