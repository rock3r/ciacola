package dev.sebastiano.ciacola.document.render

import androidx.compose.ui.unit.Density
import dev.sebastiano.ciacola.core.fonts.BundledFontCatalog
import dev.sebastiano.ciacola.core.fonts.ClasspathFontResourceLoader
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.SystemFontLookup
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TextRun
import dev.sebastiano.ciacola.document.TextStyleOverride
import dev.sebastiano.ciacola.theme.DeckThemes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Static `axes` flow from theme typography roles through text nodes into inline runs when the
 * document text bridge builds MagicText v2 styled text.
 */
class DocumentTextAxesBridgeTest {

    private val resolver: DeckFontResolver by lazy {
        val fontsDir = File(assertNotNull(bundledFontsDirPath(), "bundled fonts dir"))
        DeckFontResolver(
            declarations = listOf(BundledFontCatalog.Fredoka),
            resourceLoader = ClasspathFontResourceLoader(fallbackDirectories = listOf(fontsDir)),
            systemFontLookup = SystemFontLookup { null },
        )
    }

    @Test
    fun roleNodeAndRunAxesMergePerTagAndClamp() {
        val theme = DeckThemes.stageDark()
        val themeSpec = DeckThemeSpec(
            typography = mapOf("body" to TextStyleOverride(font = "Fredoka", axes = mapOf("wdth" to 90.0, "wght" to 450.0))),
        )
        val colorResolver = DocumentColorResolver(themeSpec = themeSpec, baseTheme = theme, slidePaletteSpec = null)
        val payload = TextPayload(
            value = RichTextValue.Runs(
                listOf(
                    TextRun(text = "inherits "),
                    TextRun(text = "overrides", axes = mapOf("wdth" to 500.0)),
                ),
            ),
            style = "body",
            axes = mapOf("wdth" to 80.0),
        )
        val styled = buildStyledTextV2(
            payload = payload,
            theme = theme,
            themeTypography = themeSpec.typography,
            colorResolver = colorResolver,
            fontResolver = resolver,
            density = Density(1f),
            maxWidthPx = 1600,
            disableLigatures = false,
        )

        val paragraphFamily = assertNotNull(resolver.findResolvedFamily(styled.style.fontFamily))
        // Node wdth replaces the role's wdth; the role's wght is inherited.
        assertEquals(mapOf("wdth" to 80f, "wght" to 450f), paragraphFamily.faces.first().axisCoordinates)

        val spans = styled.text.spanStyles.sortedBy { it.start }
        val inheriting = spans.first { it.start == 0 }
        assertEquals(paragraphFamily, resolver.findResolvedFamily(inheriting.item.fontFamily))

        val overriding = spans.first { it.start == "inherits ".length }
        val runFamily = assertNotNull(resolver.findResolvedFamily(overriding.item.fontFamily))
        // Run wdth clamps to Fredoka's 125 maximum and keeps the inherited wght.
        assertEquals(mapOf("wdth" to 125f, "wght" to 450f), runFamily.faces.first().axisCoordinates)
    }

    @Test
    fun payloadWithoutAxesKeepsTheBaseFamily() {
        val theme = DeckThemes.stageDark()
        val colorResolver = DocumentColorResolver(themeSpec = null, baseTheme = theme, slidePaletteSpec = null)
        val styled = buildStyledTextV2(
            payload = TextPayload(value = RichTextValue.Plain("No axes"), font = "Fredoka"),
            theme = theme,
            themeTypography = emptyMap(),
            colorResolver = colorResolver,
            fontResolver = resolver,
            density = Density(1f),
            maxWidthPx = 1600,
            disableLigatures = false,
        )
        assertEquals(resolver.resolveFamily("Fredoka")!!.fontFamily, styled.style.fontFamily)
    }
}
