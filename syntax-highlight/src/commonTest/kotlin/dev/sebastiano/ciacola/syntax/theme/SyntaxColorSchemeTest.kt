package dev.sebastiano.ciacola.syntax.theme

import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyntaxColorSchemeTest {

    @Test
    fun colorChannelExtractionAndFormatting() {
        val color = SyntaxColor.fromRgb(red = 0x56, green = 0xA8, blue = 0xF5, alpha = 0x80)
        assertEquals(0x80, color.alpha)
        assertEquals(0x56, color.red)
        assertEquals(0xA8, color.green)
        assertEquals(0xF5, color.blue)
        assertEquals("#56A8F5", color.toHexRgb())
        assertEquals("#56A8F580", color.toHexRgba())
        assertEquals("#8056A8F5", color.toHexArgb())

        assertFailsWith<IllegalArgumentException> { SyntaxColor.fromRgb(256, 0, 0) }
        assertFailsWith<IllegalArgumentException> { SyntaxColor.fromRgb(0, -1, 0) }
        assertFailsWith<IllegalArgumentException> { SyntaxColor.fromRgb(0, 0, 0, 300) }
    }

    @Test
    fun parsesCssArgbAndIntelliJHexFormats() {
        assertEquals(SyntaxColor.fromRgb(0xFF, 0x00, 0xAA), SyntaxColor.parseCssHexOrNull("#f0a"))
        assertEquals(SyntaxColor.fromRgb(0xFF, 0x00, 0xAA, 0x88), SyntaxColor.parseCssHexOrNull("#f0a8"))
        assertEquals(SyntaxColor.fromRgb(0x19, 0x1A, 0x1C), SyntaxColor.parseCssHexOrNull("#191A1C"))
        assertEquals(SyntaxColor.fromRgb(0x4A, 0xC2, 0x6B, 0x66), SyntaxColor.parseCssHexOrNull("#4ac26b66"))
        assertNull(SyntaxColor.parseCssHexOrNull("191A1C"))
        assertNull(SyntaxColor.parseCssHexOrNull("#12345"))
        assertNull(SyntaxColor.parseCssHexOrNull("#ZZZZZZ"))

        assertEquals(SyntaxColor.fromRgb(0x11, 0x22, 0x33, 0xAA), SyntaxColor.parseArgbHexOrNull("#A123"))
        assertEquals(SyntaxColor.fromRgb(0x4A, 0xC2, 0x6B, 0x66), SyntaxColor.parseArgbHexOrNull("#664ac26b"))
        assertNull(SyntaxColor.parseArgbHexOrNull("664ac26b"))

        // IntelliJ left-padded 1..6 digit hex + 8-digit RRGGBBAA + optional '#'
        assertEquals(SyntaxColor.fromRgb(0x00, 0x00, 0x00), SyntaxColor.parseIntelliJHexOrNull("0"))
        assertEquals(SyntaxColor.fromRgb(0x00, 0x00, 0x80), SyntaxColor.parseIntelliJHexOrNull("80"))
        assertEquals(SyntaxColor.fromRgb(0x00, 0x33, 0xB3), SyntaxColor.parseIntelliJHexOrNull("33b3"))
        assertEquals(SyntaxColor.fromRgb(0x08, 0x08, 0x08), SyntaxColor.parseIntelliJHexOrNull("80808"))
        assertEquals(SyntaxColor.fromRgb(0x00, 0x62, 0x7A), SyntaxColor.parseIntelliJHexOrNull("#627a"))
        assertEquals(SyntaxColor.fromRgb(0xFF, 0xFF, 0xFF, 0x4D), SyntaxColor.parseIntelliJHexOrNull("FFFFFF4D"))
        assertNull(SyntaxColor.parseIntelliJHexOrNull(""))
        assertNull(SyntaxColor.parseIntelliJHexOrNull("1234567"))
        assertNull(SyntaxColor.parseIntelliJHexOrNull("123456789"))
        assertNull(SyntaxColor.parseIntelliJHexOrNull("12G4"))
    }

    @Test
    fun luminanceAndContrastCalculations() {
        val black = SyntaxColor(0xFF000000.toInt())
        val white = SyntaxColor(0xFFFFFFFF.toInt())
        assertEquals(0.0, black.relativeLuminance(), 1e-6)
        assertEquals(1.0, white.relativeLuminance(), 1e-6)
        assertEquals(21.0, black.contrastRatio(white), 1e-4)
        assertEquals(21.0, white.contrastRatio(black), 1e-4)
        assertEquals(1.0, black.contrastRatio(black), 1e-6)
    }

    @Test
    fun appearanceFlagsAndStyleFallbacks() {
        assertTrue(SyntaxSchemeAppearance.Light.isLight)
        assertFalse(SyntaxSchemeAppearance.Light.isHighContrast)
        assertFalse(SyntaxSchemeAppearance.Dark.isLight)
        assertFalse(SyntaxSchemeAppearance.Dark.isHighContrast)
        assertTrue(SyntaxSchemeAppearance.HighContrastLight.isLight)
        assertTrue(SyntaxSchemeAppearance.HighContrastLight.isHighContrast)
        assertFalse(SyntaxSchemeAppearance.HighContrastDark.isLight)
        assertTrue(SyntaxSchemeAppearance.HighContrastDark.isHighContrast)

        val bg = SyntaxColor(0xFF191A1C.toInt())
        val fg = SyntaxColor(0xFFBCBEC4.toInt())
        val kw = SyntaxColor(0xFFCF8E6D.toInt())
        val fn = SyntaxColor(0xFF56A8F5.toInt())
        val scheme = SyntaxColorScheme(
            id = "sample",
            name = "Sample",
            appearance = SyntaxSchemeAppearance.Dark,
            background = bg,
            foreground = fg,
            styles = mapOf(
                SyntaxTokenKind.Keyword to SyntaxTokenStyle(foreground = kw, bold = true),
                SyntaxTokenKind.Function to SyntaxTokenStyle(foreground = fn),
            ),
        )
        assertEquals(SyntaxTokenStyle(foreground = kw, bold = true), scheme.styleFor(SyntaxTokenKind.Keyword))
        assertEquals(SyntaxTokenStyle(foreground = fg), scheme.styleFor(SyntaxTokenKind.Number))
        assertEquals(fg, scheme.foregroundFor(SyntaxTokenKind.Number))
        assertEquals(kw, scheme.foregroundFor(SyntaxTokenKind.Keyword))
        assertEquals(SyntaxTokenStyle(foreground = fn), scheme.effectiveFunctionDeclarationStyle())
    }

    @Test
    fun compactJsonRoundTripAndValidation() {
        val scheme = SyntaxColorScheme(
            id = "custom-test",
            name = "Custom Test",
            appearance = SyntaxSchemeAppearance.Dark,
            background = SyntaxColor(0xFF101216.toInt()),
            foreground = SyntaxColor(0xFFE0E2E6.toInt()),
            selectionBackground = SyntaxColor(0xFF214283.toInt()),
            lineHighlightBackground = SyntaxColor(0x801F2024.toInt()),
            lineNumberForeground = SyntaxColor(0xFF4B5059.toInt()),
            styles = mapOf(
                SyntaxTokenKind.Plain to SyntaxTokenStyle(foreground = SyntaxColor(0xFFE0E2E6.toInt())),
                SyntaxTokenKind.Keyword to SyntaxTokenStyle(foreground = SyntaxColor(0xFFCF8E6D.toInt()), bold = true),
                SyntaxTokenKind.Comment to SyntaxTokenStyle(foreground = SyntaxColor(0xFF7A7E85.toInt()), italic = true),
                SyntaxTokenKind.Invalid to SyntaxTokenStyle(
                    foreground = SyntaxColor(0xFFF75464.toInt()),
                    background = SyntaxColor(0xFF402020.toInt()),
                    underline = true,
                    strikethrough = true,
                ),
            ),
            functionDeclaration = SyntaxTokenStyle(foreground = SyntaxColor(0xFF56A8F5.toInt()), bold = true),
            diffInserted = SyntaxColor(0x664AC26B),
            diffDeleted = SyntaxColor(0x66FF8182),
            diffChanged = SyntaxColor(0x66D4A72C),
            metadata = SyntaxSchemeMetadata(
                family = "Custom",
                upstream = "https://example.org/theme",
                license = "MIT",
                revision = "abc123",
            ),
        )

        val compact = scheme.toCompactJson(pretty = false)
        assertFalse(compact.contains('\n'))
        assertTrue(compact.contains("\"plain\":\"#E0E2E6\""))
        assertTrue(compact.contains("\"bold\":true"))
        assertFalse(compact.contains("\"italic\":false"))
        assertEquals(scheme, SyntaxColorScheme.fromCompactJson(compact))

        val pretty = scheme.toCompactJson(pretty = true)
        assertTrue(pretty.contains('\n'))
        assertEquals(scheme, SyntaxColorScheme.fromCompactJson(pretty))
        assertNotNull(SyntaxColorScheme.fromCompactJson(compact).functionDeclaration)

        assertFailsWith<SyntaxSchemeParseException> { SyntaxColorScheme.fromCompactJson("{}") }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxColorScheme.fromCompactJson("""{"format":"other","version":1}""")
        }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxColorScheme.fromCompactJson("""{"format":"ciacola-syntax-scheme","version":2}""")
        }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxColorScheme.fromCompactJson(
                """{"format":"ciacola-syntax-scheme","version":1,"id":"x","name":"X","appearance":"neon","background":"#000000","foreground":"#FFFFFF","tokens":{}}""",
            )
        }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxColorScheme.fromCompactJson(
                """{"format":"ciacola-syntax-scheme","version":1,"id":"x","name":"X","appearance":"dark","background":"not-a-hex","foreground":"#FFFFFF","tokens":{}}""",
            )
        }
    }
}
