package dev.sebastiano.ciacola.syntax.theme

import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundledSyntaxSchemesTest {

    @Test
    fun allElevenBundledSchemesLoadWithUniqueIdsAndNames() {
        assertEquals(6, SyntaxColorSchemes.intelliJ.size)
        assertEquals(5, SyntaxColorSchemes.gitHub.size)
        assertEquals(11, SyntaxColorSchemes.all.size)
        assertEquals(11, SyntaxColorSchemes.all.map { it.id }.toSet().size)
        assertEquals(11, SyntaxColorSchemes.all.map { it.name }.toSet().size)
    }

    @Test
    fun bundledSchemesMatchExpectedSignatureColors() {
        val islands = SyntaxColorSchemes.IntelliJIslandsDark
        assertEquals("#191A1C", islands.background.toHexRgb())
        assertEquals("#BCBEC4", islands.foreground.toHexRgb())
        assertEquals("#CF8E6D", islands.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#6AAB73", islands.foregroundFor(SyntaxTokenKind.String).toHexRgb())
        assertEquals("#56A8F5", islands.foregroundFor(SyntaxTokenKind.Function).toHexRgb())
        assertEquals("#16BAAC", islands.foregroundFor(SyntaxTokenKind.Type).toHexRgb())
        assertEquals("#BABABA", islands.foregroundFor(SyntaxTokenKind.Attribute).toHexRgb())
        assertEquals("#D5B778", islands.foregroundFor(SyntaxTokenKind.Tag).toHexRgb())
        assertEquals("#F75464", islands.foregroundFor(SyntaxTokenKind.Invalid).toHexRgb())
        assertEquals(SyntaxSchemeAppearance.Dark, islands.appearance)

        val ijDark = SyntaxColorSchemes.IntelliJDark
        assertEquals("#1E1F22", ijDark.background.toHexRgb())
        assertEquals("#CF8E6D", ijDark.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())

        val ijLight = SyntaxColorSchemes.IntelliJLight
        assertEquals("#FFFFFF", ijLight.background.toHexRgb())
        assertEquals("#080808", ijLight.foreground.toHexRgb())
        assertEquals("#0033B3", ijLight.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#067D17", ijLight.foregroundFor(SyntaxTokenKind.String).toHexRgb())
        assertEquals("#00627A", ijLight.foregroundFor(SyntaxTokenKind.Function).toHexRgb())
        assertEquals("#174AD4", ijLight.foregroundFor(SyntaxTokenKind.Attribute).toHexRgb())
        assertTrue(ijLight.styleFor(SyntaxTokenKind.Comment).italic)
        assertEquals(SyntaxSchemeAppearance.Light, ijLight.appearance)

        val darcula = SyntaxColorSchemes.IntelliJDarcula
        assertEquals("#2B2B2B", darcula.background.toHexRgb())
        assertEquals("#CC7832", darcula.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#FFC66D", darcula.foregroundFor(SyntaxTokenKind.Function).toHexRgb())
        assertEquals("#BC3F3C", darcula.foregroundFor(SyntaxTokenKind.Invalid).toHexRgb())

        val darculaContrast = SyntaxColorSchemes.IntelliJDarculaContrast
        assertEquals("#1E1F22", darculaContrast.background.toHexRgb())
        assertEquals("#CC7832", darculaContrast.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())

        val ijHc = SyntaxColorSchemes.IntelliJHighContrast
        assertEquals("#000000", ijHc.background.toHexRgb())
        assertEquals("#ED864A", ijHc.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#FFCF40", ijHc.foregroundFor(SyntaxTokenKind.Function).toHexRgb())
        assertEquals("#FFFFFF", ijHc.foregroundFor(SyntaxTokenKind.Attribute).toHexRgb())
        assertEquals("#FA3232", ijHc.foregroundFor(SyntaxTokenKind.Invalid).toHexRgb())
        assertEquals(SyntaxSchemeAppearance.HighContrastDark, ijHc.appearance)

        val ghLight = SyntaxColorSchemes.GitHubLight
        assertEquals("#FFFFFF", ghLight.background.toHexRgb())
        assertEquals("#1F2328", ghLight.foreground.toHexRgb())
        assertEquals("#CF222E", ghLight.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#8250DF", ghLight.foregroundFor(SyntaxTokenKind.Function).toHexRgb())
        assertEquals("#953800", ghLight.foregroundFor(SyntaxTokenKind.Type).toHexRgb())
        assertEquals("#116329", ghLight.foregroundFor(SyntaxTokenKind.Tag).toHexRgb())
        assertEquals(SyntaxSchemeAppearance.Light, ghLight.appearance)

        val ghDark = SyntaxColorSchemes.GitHubDark
        assertEquals("#0D1117", ghDark.background.toHexRgb())
        assertEquals("#FF7B72", ghDark.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#D2A8FF", ghDark.foregroundFor(SyntaxTokenKind.Function).toHexRgb())

        val ghDimmed = SyntaxColorSchemes.GitHubDarkDimmed
        assertEquals("#22272E", ghDimmed.background.toHexRgb())
        assertEquals("#F47067", ghDimmed.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())

        val ghLightHc = SyntaxColorSchemes.GitHubLightHighContrast
        assertEquals("#FFFFFF", ghLightHc.background.toHexRgb())
        assertEquals("#A0111F", ghLightHc.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals(SyntaxSchemeAppearance.HighContrastLight, ghLightHc.appearance)

        val ghDarkHc = SyntaxColorSchemes.GitHubDarkHighContrast
        assertEquals("#0A0C10", ghDarkHc.background.toHexRgb())
        assertEquals("#FF9492", ghDarkHc.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals(SyntaxSchemeAppearance.HighContrastDark, ghDarkHc.appearance)
    }

    @Test
    fun allBundledSchemesRoundTripCompactAndPrettyJson() {
        for (scheme in SyntaxColorSchemes.all) {
            val compact = scheme.toCompactJson(pretty = false)
            val pretty = scheme.toCompactJson(pretty = true)
            assertEquals(scheme, SyntaxColorScheme.fromCompactJson(compact), "Compact round-trip failed for ${scheme.id}")
            assertEquals(scheme, SyntaxColorScheme.fromCompactJson(pretty), "Pretty round-trip failed for ${scheme.id}")
        }
    }

    @Test
    fun allBundledSchemesSatisfyContrastAndNoLeakageInvariants() {
        val leakedBlue = SyntaxColor(0xFF0000FF.toInt())
        val leakedPastelBg = SyntaxColor(0xFFFFCCCC.toInt())

        for (scheme in SyntaxColorSchemes.all) {
            val plainContrast = scheme.foreground.contrastRatio(scheme.background)
            val minPlainContrast = if (scheme == SyntaxColorSchemes.IntelliJDarcula) 6.9 else 7.0
            assertTrue(
                plainContrast >= minPlainContrast,
                "${scheme.id} plain contrast $plainContrast must be >= $minPlainContrast",
            )

            if (!scheme.appearance.isLight) {
                for ((kind, style) in scheme.styles) {
                    assertNotEquals(
                        leakedBlue,
                        style.foreground,
                        "Dark scheme ${scheme.id} leaked #0000FF on $kind",
                    )
                    assertNotEquals(
                        leakedPastelBg,
                        style.background,
                        "Dark scheme ${scheme.id} leaked #FFCCCC background on $kind",
                    )
                }
            }

            for (kind in SyntaxTokenKind.entries) {
                assertNotNull(scheme.styles[kind], "${scheme.id} is missing explicit style for $kind")
                if (kind == SyntaxTokenKind.Whitespace) continue
                val tokenFg = scheme.foregroundFor(kind)
                val ratio = tokenFg.contrastRatio(scheme.background)
                assertTrue(
                    ratio >= 2.5,
                    "${scheme.id} token $kind (${tokenFg.toHexRgb()} on ${scheme.background.toHexRgb()}) contrast $ratio < 2.5",
                )
            }
        }
    }

    @Test
    fun registryLookupsAndDefaultsWork() {
        assertEquals(SyntaxColorSchemes.IntelliJIslandsDark, SyntaxColorSchemes.findById("intellij-islands-dark"))
        assertEquals(SyntaxColorSchemes.IntelliJIslandsDark, SyntaxColorSchemes.findById("Islands Dark"))
        assertEquals(SyntaxColorSchemes.IntelliJIslandsDark, SyntaxColorSchemes.findById("islands-dark"))
        assertEquals(SyntaxColorSchemes.IntelliJDarcula, SyntaxColorSchemes.findById("DARCULA"))
        assertEquals(SyntaxColorSchemes.IntelliJDarculaContrast, SyntaxColorSchemes.findById("darcula-contrast"))
        assertEquals(SyntaxColorSchemes.IntelliJLight, SyntaxColorSchemes.findById("light"))
        assertEquals(SyntaxColorSchemes.IntelliJDark, SyntaxColorSchemes.findById("dark"))
        assertEquals(SyntaxColorSchemes.IntelliJHighContrast, SyntaxColorSchemes.findById("high-contrast"))
        assertEquals(SyntaxColorSchemes.GitHubLight, SyntaxColorSchemes.findById("github-light"))
        assertEquals(SyntaxColorSchemes.GitHubDark, SyntaxColorSchemes.findById("github-dark"))
        assertEquals(SyntaxColorSchemes.GitHubDarkDimmed, SyntaxColorSchemes.findById("GitHub Dark Dimmed"))
        assertNull(SyntaxColorSchemes.findById(""))
        assertNull(SyntaxColorSchemes.findById("   "))
        assertNull(SyntaxColorSchemes.findById("non-existent-theme"))

        assertEquals(SyntaxColorSchemes.IntelliJLight, SyntaxColorSchemes.defaultFor(isLight = true))
        assertEquals(SyntaxColorSchemes.IntelliJIslandsDark, SyntaxColorSchemes.defaultFor(isLight = false))
    }
}
