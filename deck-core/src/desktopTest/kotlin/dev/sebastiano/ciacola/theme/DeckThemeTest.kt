package dev.sebastiano.ciacola.theme

import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.theme.SyntaxColor
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorScheme
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes
import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeAppearance
import dev.sebastiano.ciacola.syntax.theme.SyntaxTokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
            assertEquals(palette.keyword, palette.colorForWarpScope("storage.modifier.java"))
            assertEquals(palette.type, palette.colorForWarpScope("storage.type.agsl"))
            assertEquals(palette.string, palette.colorForWarpScope("string.quoted.double.kotlin"))
            assertEquals(palette.functionCall, palette.colorForWarpScope("entity.name.function.kotlin"))
            assertEquals(palette.type, palette.colorForWarpScope("entity.name.type.class.kotlin"))
            assertEquals(palette.number, palette.colorForWarpScope("constant.numeric.integer.kotlin"))
            assertEquals(palette.constant, palette.colorForWarpScope("constant.language.kotlin"))
            assertEquals(palette.annotation, palette.colorForWarpScope("meta.annotation.kotlin"))
            assertEquals(palette.preprocessor, palette.colorForWarpScope("meta.annotation.preprocessor.c"))
            assertEquals(palette.attribute, palette.colorForWarpScope("entity.other.attribute-name.xml"))
            assertEquals(palette.tag, palette.colorForWarpScope("entity.name.tag.xml"))
            assertEquals(palette.operator, palette.colorForWarpScope("punctuation.operator.kotlin"))
            assertEquals(palette.invalid, palette.colorForWarpScope("invalid.illegal.kotlin"))

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

    @Test
    fun presetsUseIslandsDarkAndIntelliJLightAndPaletteHonorsAllElevenSchemesAndFontStyles() {
        assertEquals(Color(0xFF191A1C), DeckThemes.StageDarkColors.code.background)
        assertEquals(Color(0xFFBCBEC4), DeckThemes.StageDarkColors.code.foreground)
        assertEquals(SyntaxColorSchemes.IntelliJIslandsDark.id, DeckThemes.StageDarkColors.code.scheme?.id)

        assertEquals(Color(0xFFFFFFFF), DeckThemes.KeynoteLightColors.code.background)
        assertEquals(Color(0xFF080808), DeckThemes.KeynoteLightColors.code.foreground)
        assertEquals(SyntaxColorSchemes.IntelliJLight.id, DeckThemes.KeynoteLightColors.code.scheme?.id)

        assertEquals(11, SyntaxColorSchemes.all.size)
        for (scheme in SyntaxColorSchemes.all) {
            val palette = DeckCodePalette.fromScheme(scheme)
            assertEquals(Color(scheme.background.argb), palette.background, scheme.id)
            assertEquals(Color(scheme.foreground.argb), palette.foreground, scheme.id)
            assertEquals(Color(scheme.foregroundFor(SyntaxTokenKind.Constant).argb), palette.constant, scheme.id)
            assertEquals(Color(scheme.foregroundFor(SyntaxTokenKind.Attribute).argb), palette.attribute, scheme.id)
            assertEquals(Color(scheme.foregroundFor(SyntaxTokenKind.Tag).argb), palette.tag, scheme.id)
            assertEquals(Color(scheme.foregroundFor(SyntaxTokenKind.Operator).argb), palette.operator, scheme.id)
            assertEquals(Color(scheme.foregroundFor(SyntaxTokenKind.Preprocessor).argb), palette.preprocessor, scheme.id)
            assertEquals(Color(scheme.foregroundFor(SyntaxTokenKind.Invalid).argb), palette.invalid, scheme.id)
        }

        // Islands Dark: keywords are normal weight (not forced SemiBold), constants are italic
        val islandsPalette = DeckThemes.StageDarkColors.code
        val islandsCodeStyle = islandsPalette.toCodeStyle()
        assertNull(islandsCodeStyle.keyword.fontWeight)
        assertNull(islandsCodeStyle.functionDeclaration.fontWeight)
        assertEquals(FontStyle.Italic, islandsCodeStyle.constant.fontStyle)
        assertEquals(FontStyle.Italic, islandsPalette.styleForWarpScope("constant.language.kotlin").fontStyle)

        // IntelliJ Light: comments and constants are italic
        val lightPalette = DeckThemes.KeynoteLightColors.code
        val lightCodeStyle = lightPalette.toCodeStyle()
        assertEquals(FontStyle.Italic, lightCodeStyle.comment.fontStyle)
        assertEquals(FontStyle.Italic, lightPalette.styleForWarpScope("comment.line.double-slash.kotlin").fontStyle)

        // Custom synthetic scheme with bold, italic, underline, strikethrough preserves all attributes
        val styledScheme = SyntaxColorScheme(
            id = "styled-test",
            name = "Styled Test",
            appearance = SyntaxSchemeAppearance.Dark,
            background = SyntaxColor.fromRgb(0x10, 0x10, 0x10),
            foreground = SyntaxColor.fromRgb(0xEE, 0xEE, 0xEE),
            styles = mapOf(
                SyntaxTokenKind.Keyword to SyntaxTokenStyle(foreground = SyntaxColor.fromRgb(0xFF, 0x00, 0x00), bold = true),
                SyntaxTokenKind.Comment to SyntaxTokenStyle(foreground = SyntaxColor.fromRgb(0x88, 0x88, 0x88), italic = true),
                SyntaxTokenKind.Attribute to SyntaxTokenStyle(foreground = SyntaxColor.fromRgb(0x00, 0xFF, 0x00), underline = true),
                SyntaxTokenKind.Invalid to SyntaxTokenStyle(foreground = SyntaxColor.fromRgb(0xFF, 0x55, 0x55), strikethrough = true),
            ),
        )
        val styledPalette = DeckCodePalette.fromScheme(styledScheme)
        val styledCodeStyle = styledPalette.toCodeStyle()
        assertEquals(FontWeight.Bold, styledCodeStyle.keyword.fontWeight)
        assertEquals(FontStyle.Italic, styledCodeStyle.comment.fontStyle)
        assertEquals(TextDecoration.Underline, styledCodeStyle.attribute.textDecoration)
        assertEquals(TextDecoration.LineThrough, styledCodeStyle.invalid.textDecoration)
        assertEquals(FontWeight.Bold, styledPalette.styleForWarpScope("keyword.control.kotlin").fontWeight)
        assertEquals(TextDecoration.Underline, styledPalette.styleForWarpScope("entity.other.attribute-name.xml").textDecoration)
        assertEquals(TextDecoration.LineThrough, styledPalette.styleForWarpScope("invalid.illegal.kotlin").textDecoration)
    }
}
