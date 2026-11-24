package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.CodePayload
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.toCodeSchemeSpec
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.theme.SyntaxColor
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorScheme
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes
import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeAppearance
import dev.sebastiano.ciacola.syntax.theme.SyntaxTokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CodeSchemeResolutionAndWarpStyleTest {

    @Test
    fun resolveDeckThemeHonorsCodeSchemeCustomCodeSchemeAndTokenOverrides() {
        val baseDoc = DeckDocument(
            id = "scheme-res",
            defaultTheme = "dark",
            themes = mapOf(
                "dark" to DeckThemeSpec(
                    preset = "stageDark",
                    codeScheme = "github-dark",
                    colors = mapOf("accent" to "#FF6B5E"),
                ),
            ),
            slides = listOf(SlideSpec(id = "s1")),
        )

        val resolvedGitHub = resolveDeckTheme(baseDoc, "dark")
        assertEquals(Color(0xFF0D1117), resolvedGitHub.colors.code.background)
        assertEquals(Color(0xFFE6EDF3), resolvedGitHub.colors.code.foreground)
        assertEquals(Color(0xFFFF7B72), resolvedGitHub.colors.code.keyword)

        val resolver = DocumentColorResolver(baseDoc.themes["dark"], resolvedGitHub)
        assertEquals(Color(0xFF0D1117), resolver.resolveToken("codeBackground"))
        assertEquals(Color(0xFF0D1117), resolver.resolveToken("code.background"))
        assertEquals(Color(0xFFE6EDF3), resolver.resolveToken("codeForeground"))
        assertEquals(Color(0xFFE6EDF3), resolver.resolveToken("code.foreground"))

        // Explicit codeBackground override layers cleanly on top of bundled scheme
        val overriddenDoc = baseDoc.copy(
            themes = mapOf(
                "dark" to DeckThemeSpec(
                    preset = "stageDark",
                    codeScheme = "github-dark",
                    colors = mapOf("codeBackground" to "#101010"),
                ),
            ),
        )
        val resolvedOverridden = resolveDeckTheme(overriddenDoc, "dark")
        assertEquals(Color(0xFF101010), resolvedOverridden.colors.code.background)
        assertEquals(Color(0xFFFF7B72), resolvedOverridden.colors.code.keyword)

        // code.function alias updates functionCall, and primary keys win deterministically over aliases
        val fnAliasDoc = baseDoc.copy(
            themes = mapOf(
                "dark" to DeckThemeSpec(
                    preset = "stageDark",
                    colors = mapOf("code.function" to "#FF0000"),
                ),
            ),
        )
        assertEquals(Color(0xFFFF0000), resolveDeckTheme(fnAliasDoc, "dark").colors.code.functionCall)

        for (colors in listOf(
            linkedMapOf(
                "code.function" to "#111111",
                "code.functionCall" to "#222222",
                "code.background" to "#333333",
                "codeBackground" to "#444444",
                "code.foreground" to "#555555",
                "codeForeground" to "#666666",
            ),
            linkedMapOf(
                "code.functionCall" to "#222222",
                "code.function" to "#111111",
                "codeBackground" to "#444444",
                "code.background" to "#333333",
                "codeForeground" to "#666666",
                "code.foreground" to "#555555",
            ),
        )) {
            val spec = DeckThemeSpec(preset = "stageDark", colors = colors)
            val precedenceDoc = baseDoc.copy(themes = mapOf("dark" to spec))
            val resolved = resolveDeckTheme(precedenceDoc, "dark")
            assertEquals(Color(0xFF222222), resolved.colors.code.functionCall)
            assertEquals(Color(0xFF444444), resolved.colors.code.background)
            assertEquals(Color(0xFF666666), resolved.colors.code.foreground)

            val tokenResolver = DocumentColorResolver(spec, resolved)
            assertEquals(Color(0xFF444444), tokenResolver.resolveToken("codeBackground"))
            assertEquals(Color(0xFF444444), tokenResolver.resolveToken("code.background"))
            assertEquals(Color(0xFF666666), tokenResolver.resolveToken("codeForeground"))
            assertEquals(Color(0xFF666666), tokenResolver.resolveToken("code.foreground"))
            assertEquals(Color(0xFF222222), tokenResolver.resolveToken("code.functionCall"))
            assertEquals(Color(0xFF222222), tokenResolver.resolveToken("code.function"))
        }

        // Invalid primary falls back to valid alias in both resolveDeckTheme and resolveToken
        val invalidPrimarySpec = DeckThemeSpec(
            preset = "stageDark",
            colors = mapOf(
                "codeBackground" to "not-a-color",
                "code.background" to "#333333",
                "codeForeground" to "not-a-color",
                "code.foreground" to "#555555",
                "code.functionCall" to "not-a-color",
                "code.function" to "#111111",
            ),
        )
        val invalidPrimaryTheme = resolveDeckTheme(baseDoc.copy(themes = mapOf("dark" to invalidPrimarySpec)), "dark")
        assertEquals(Color(0xFF333333), invalidPrimaryTheme.colors.code.background)
        assertEquals(Color(0xFF555555), invalidPrimaryTheme.colors.code.foreground)
        assertEquals(Color(0xFF111111), invalidPrimaryTheme.colors.code.functionCall)
        val invalidPrimaryResolver = DocumentColorResolver(invalidPrimarySpec, invalidPrimaryTheme)
        assertEquals(Color(0xFF333333), invalidPrimaryResolver.resolveToken("codeBackground"))
        assertEquals(Color(0xFF333333), invalidPrimaryResolver.resolveToken("code.background"))
        assertEquals(Color(0xFF555555), invalidPrimaryResolver.resolveToken("codeForeground"))
        assertEquals(Color(0xFF555555), invalidPrimaryResolver.resolveToken("code.foreground"))
        assertEquals(Color(0xFF111111), invalidPrimaryResolver.resolveToken("code.functionCall"))
        assertEquals(Color(0xFF111111), invalidPrimaryResolver.resolveToken("code.function"))

        // Custom imported scheme overrides bundled codeScheme id
        val custom = SyntaxColorScheme(
            id = "custom-solar",
            name = "Custom Solar",
            appearance = SyntaxSchemeAppearance.Light,
            background = SyntaxColor.fromRgb(0xFD, 0xF6, 0xE3),
            foreground = SyntaxColor.fromRgb(0x65, 0x7B, 0x83),
            styles = mapOf(
                SyntaxTokenKind.Keyword to SyntaxTokenStyle(foreground = SyntaxColor.fromRgb(0x85, 0x99, 0x00), bold = true),
                SyntaxTokenKind.Comment to SyntaxTokenStyle(foreground = SyntaxColor.fromRgb(0x93, 0xA1, 0xA1), italic = true),
            ),
        )
        val customDoc = baseDoc.copy(
            themes = mapOf(
                "dark" to DeckThemeSpec(
                    preset = "stageDark",
                    codeScheme = "github-dark",
                    customCodeScheme = custom.toCodeSchemeSpec(),
                ),
            ),
        )
        val resolvedCustom = resolveDeckTheme(customDoc, "dark")
        assertEquals(Color(0xFFFDF6E3), resolvedCustom.colors.code.background)
        assertEquals(Color(0xFF859900), resolvedCustom.colors.code.keyword)
        assertEquals(FontWeight.Bold, resolvedCustom.colors.code.styleForWarpScope("keyword.control.kotlin").fontWeight)
        assertEquals(FontStyle.Italic, resolvedCustom.colors.code.styleForWarpScope("comment.line.double-slash.kotlin").fontStyle)
    }

    @Test
    fun warpCodeAdapterAppliesSchemeColorsAndFontStylesToRenderedTokens() = runComposeUiTest {
        val custom = SyntaxColorScheme(
            id = "warp-style-check",
            name = "Warp Style Check",
            appearance = SyntaxSchemeAppearance.Dark,
            background = SyntaxColor.fromRgb(0x14, 0x16, 0x1A),
            foreground = SyntaxColor.fromRgb(0xEE, 0xEE, 0xEE),
            styles = mapOf(
                SyntaxTokenKind.Keyword to SyntaxTokenStyle(foreground = SyntaxColor.fromRgb(0xFF, 0x55, 0x33), bold = true),
                SyntaxTokenKind.Comment to SyntaxTokenStyle(foreground = SyntaxColor.fromRgb(0x77, 0x88, 0x99), italic = true),
            ),
        )
        val slide = SlideSpec(
            id = "s1",
            nodes = listOf(
                NodeSpec(
                    id = "code-node",
                    code = CodePayload(
                        value = "// note\nval x = 1",
                        language = "kotlin",
                    ),
                ),
            ),
        )
        val doc = DeckDocument(
            id = "warp-scheme",
            defaultTheme = "dark",
            themes = mapOf(
                "dark" to DeckThemeSpec(
                    preset = "stageDark",
                    codeScheme = SyntaxColorSchemes.IntelliJIslandsDark.id,
                    customCodeScheme = custom.toCodeSchemeSpec(),
                ),
            ),
            slides = listOf(slide),
        )
        val theme = resolveDeckTheme(doc, "dark")
        val resolver = DocumentColorResolver(doc.themes["dark"], theme)

        setContent {
            Box(Modifier.size(800.dp, 400.dp)) {
                WarpCodeAdapter(
                    rawNode = slide.nodes.single(),
                    resolvedNode = slide.nodes.single(),
                    slide = slide,
                    frameTransition = null,
                    segmentPlan = dev.sebastiano.ciacola.document.SegmentPlan.Empty,
                    theme = theme,
                    colorResolver = resolver,
                    reducedMotion = false,
                )
            }
        }
        waitForIdle()

        val valNodes = onAllNodesWithText("val", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(valNodes.isNotEmpty(), "Expected 'val' token node")
        val valLayouts = mutableListOf<TextLayoutResult>()
        valNodes.first().config[SemanticsActions.GetTextLayoutResult].action?.invoke(valLayouts)
        val valStyle = valLayouts.single().layoutInput.style
        assertEquals(Color(0xFFFF5533), valStyle.color)
        assertEquals(FontWeight.Bold, valStyle.fontWeight)

        val commentNodes = onAllNodesWithText("note", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(commentNodes.isNotEmpty(), "Expected comment token node")
        val commentLayouts = mutableListOf<TextLayoutResult>()
        commentNodes.first().config[SemanticsActions.GetTextLayoutResult].action?.invoke(commentLayouts)
        val commentStyle = commentLayouts.single().layoutInput.style
        assertEquals(Color(0xFF778899), commentStyle.color)
        assertEquals(FontStyle.Italic, commentStyle.fontStyle)
    }
}
