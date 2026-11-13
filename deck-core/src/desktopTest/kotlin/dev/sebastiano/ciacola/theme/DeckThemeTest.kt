package dev.sebastiano.ciacola.theme

import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DeckThemeTest {
    @Test
    fun stageDarkAndKeynoteLightDefineCompleteDistinctTypographyColorsStylesAndMotion() {
        val dark = DeckThemes.stageDark()
        val light = DeckThemes.keynoteLight()

        assertTrue(!dark.isLight)
        assertTrue(light.isLight)
        assertNotEquals(dark.colors.background, light.colors.background)
        assertNotEquals(dark.colors.foreground, light.colors.foreground)

        // Full slide typographic hierarchy: hero > title > heading > subheading > body > callout > eyebrow
        for (theme in listOf(dark, light)) {
            val t = theme.typography
            assertTrue(t.hero.fontSize > t.title.fontSize)
            assertTrue(t.title.fontSize > t.heading.fontSize)
            assertTrue(t.heading.fontSize > t.subheading.fontSize)
            assertTrue(t.subheading.fontSize > t.body.fontSize)
            assertTrue(t.body.fontSize > t.callout.fontSize)
            assertTrue(t.callout.fontSize > t.eyebrow.fontSize)
            assertEquals(t.code.fontSize, t.codeEmphasis.fontSize)

            // Unified code palette drives both MagicText CodeStyle and Warp TextMate scopes
            val palette = theme.colors.code
            val codeStyle = palette.toCodeStyle()
            assertEquals(palette.keyword, codeStyle.keyword.color)
            assertEquals(palette.string, codeStyle.string.color)
            assertEquals(palette.comment, codeStyle.comment.color)
            assertEquals(palette.number, codeStyle.number.color)
            assertEquals(palette.annotation, codeStyle.annotation.color)
            assertEquals(palette.keyword, palette.colorForWarpScope("keyword.control.kotlin"))
            assertEquals(palette.string, palette.colorForWarpScope("string.quoted.double.kotlin"))
            assertEquals(palette.functionCall, palette.colorForWarpScope("entity.name.function.kotlin"))
            assertEquals(palette.type, palette.colorForWarpScope("entity.name.type.class.kotlin"))
            assertEquals(palette.number, palette.colorForWarpScope("constant.numeric.integer.kotlin"))
            assertEquals(palette.annotation, palette.colorForWarpScope("meta.annotation.kotlin"))

            // Motion tokens match crisp Keynote 300-400ms defaults
            assertTrue(theme.motion.fadeMoveDurationMs in 250..450)
            assertTrue(theme.motion.lineDrawDurationMs in 250..450)
            assertTrue(theme.motion.staggerStepMs in 25..120)
            assertTrue(theme.colors.spotlightDimAlpha in 0.1f..0.6f)
        }
    }

    @Test
    fun bundledDeckThemePreservesFontFamilyAndTextStyleIdentityAcrossRecompositions() = runComposeUiTest {
        var tick by mutableIntStateOf(0)
        val observedThemes = mutableListOf<DeckTheme>()

        setContent {
            val theme = DeckThemes.rememberBundledDeckTheme(DeckThemePreset.KeynoteLight)
            DeckThemeProvider(theme) {
                observedThemes += LocalDeckTheme.current
                Text("Recompose tick=$tick")
            }
        }

        waitForIdle()
        runOnIdle { tick = 1 }
        waitForIdle()
        runOnIdle { tick = 2 }
        waitForIdle()

        assertTrue(observedThemes.size >= 3, "Expected at least 3 compositions, got ${observedThemes.size}")
        val first = observedThemes.first()
        for (later in observedThemes.drop(1)) {
            assertSame(first.typography, later.typography)
            assertSame(first.typography.displayFontFamily, later.typography.displayFontFamily)
            assertSame(first.typography.monoFontFamily, later.typography.monoFontFamily)
            assertSame(first.typography.title, later.typography.title)
            assertSame(first.typography.code, later.typography.code)
        }
    }
}
