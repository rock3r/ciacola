package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.CssSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.CssOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CssSyntaxLexerTest {

    @Test
    fun matchesOracleFixtures() {
        for (fixture in CssOracleFixtures.all) {
            assertMatchesOracleFixture(fixture)
        }
    }

    @Test
    fun classifiesAtRulesSelectorsCustomPropertiesValuesAndImportant() {
        val code = """
            /* header comment */
            @charset "UTF-8";
            @import url("./base.css");
            @layer base, theme;
            @supports (display: grid) {
              @media (min-width: 768px) {
                @scope (.card) to (.slot) {
                  .card[data-state="open"] > h2::before,
                  #hero-title:hover,
                  li:nth-child(2n + 1),
                  * + p ~ span {
                    --accent-color: #1e293bff;
                    display: grid;
                    background-color: rgb(255, 128, 0);
                    grid-template-columns: repeat(2, 1fr);
                    padding: calc(12px + var(--spacing-md)) 100%;
                    margin: -2.5em .5rem;
                    color: oklch(0.65 0.2 250 / 80%) !important;
                    transform: rotate(45deg);
                    transition: opacity 0.25s ease-in-out;
                    border-color: #fff;
                    content: 'ok';
                  }
                }
              }
            }
            @font-face { font-family: "Inter"; }
            @property --x { syntax: "<number>"; }
            @container (min-width: 400px) { .tile { width: clamp(10px, 50%, 400px); } }
            @keyframes pulse { from { opacity: 0; } to { opacity: 1; } }
        """.trimIndent()

        val tokens = assertLosslessInvariants(CssSyntaxLexer, code, "css-features")

        fun firstKind(text: String, after: Int = 0): SyntaxTokenKind =
            tokens.first { it.startIndex >= after && it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Comment, firstKind("/* header comment */"))
        for (atRule in listOf("@charset", "@import", "@layer", "@supports", "@media", "@scope", "@font-face", "@property", "@container", "@keyframes")) {
            assertEquals(SyntaxTokenKind.Keyword, firstKind(atRule), "At-rule $atRule should be Keyword")
        }
        assertEquals(SyntaxTokenKind.Attribute, firstKind("card"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("data-state"))
        assertEquals(SyntaxTokenKind.String, firstKind("\"open\""))
        assertEquals(SyntaxTokenKind.Tag, firstKind("h2"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("before"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("#hero-title"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("hover"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("li"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("nth-child"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("*"))
        assertEquals(SyntaxTokenKind.Operator, firstKind(">"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("+"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("~"))

        assertEquals(SyntaxTokenKind.Property, firstKind("--accent-color"))
        assertEquals(SyntaxTokenKind.Number, firstKind("#1e293bff"))
        assertEquals(SyntaxTokenKind.Property, firstKind("display", after = code.indexOf("#hero-title")))
        assertEquals(SyntaxTokenKind.Property, firstKind("background-color"))
        assertEquals(SyntaxTokenKind.Property, firstKind("grid-template-columns"))
        assertEquals(SyntaxTokenKind.Function, firstKind("calc"))
        assertEquals(SyntaxTokenKind.Number, firstKind("12px"))
        assertEquals(SyntaxTokenKind.Function, firstKind("var"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("--spacing-md"))
        assertEquals(SyntaxTokenKind.Number, firstKind("100%"))
        assertEquals(SyntaxTokenKind.Number, firstKind("-2.5em"))
        assertEquals(SyntaxTokenKind.Number, firstKind(".5rem"))
        assertEquals(SyntaxTokenKind.Function, firstKind("oklch"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("!important"))
        assertEquals(SyntaxTokenKind.Number, firstKind("45deg"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0.25s"))
        assertEquals(SyntaxTokenKind.Number, firstKind("#fff"))
        assertEquals(SyntaxTokenKind.String, firstKind("'ok'"))
        assertEquals(SyntaxTokenKind.Function, firstKind("clamp"))
        assertEquals(SyntaxTokenKind.Function, firstKind("url"))
        assertEquals(SyntaxTokenKind.Function, firstKind("rgb"))
    }

    @Test
    fun handlesUnclosedCommentsStringsCrlfAndUnicode() {
        val code = ".café { content: \"🎉\"; }\r\n/* unclosed comment"
        val tokens = assertLosslessInvariants(CssSyntaxLexer, code, "css-unicode-unclosed")
        assertTrue(tokens.any { it.slice(code) == "/* unclosed comment" && it.kind == SyntaxTokenKind.Comment })
        assertTrue(tokens.any { it.slice(code) == "\"🎉\"" && it.kind == SyntaxTokenKind.String })

        val unclosedStr = ".a { content: \"open"
        val strTokens = assertLosslessInvariants(CssSyntaxLexer, unclosedStr, "css-unclosed-string")
        assertTrue(strTokens.any { it.slice(unclosedStr) == "\"open" && it.kind == SyntaxTokenKind.String })
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            :root { --c: #ff00aa; }
            @media (min-width: 600px) {
              .box[data-open="true"]::before {
                margin: calc(10px - -2.5em);
                color: var(--c) !important;
              }
            }
        """.trimIndent()
        assertPrefixTruncationSafety(CssSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(CssSyntaxLexer, seed = 0x43535331L, iterations = 70)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val unclosedComment = "/* " + "*".repeat(25_000) + " end"
        val manyRules = buildString {
            repeat(1_500) { idx ->
                append(".c").append(idx).append("{margin:").append(idx).append("px;color:#1e293b;}\n")
            }
        }
        val longString = ".a { content: \"${"x\\\"".repeat(7_000)}\"; }"
        val manySelectors = (1..3_000).joinToString(", ") { ".sel-$it:hover" } + " { display: block; }"

        assertLinearPerformance(
            lexer = CssSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-star-comment" to unclosedComment,
                "many-rules" to manyRules,
                "long-escaped-string" to longString,
                "many-selectors" to manySelectors,
            ),
        )
    }

    @Test
    fun handlesModernSelectorsUnicodeRangeEscapesAndUnquotedUrl() {
        val code = """
            @starting-style { .item { opacity: 0; } }
            @font-face {
              src: url(https://example.com/a.png?v=1);
              unicode-range: U+0025-00FF, U+4??;
            }
            @keyframes spin { 50% { rotate: 0.5turn; } }
            .card:has(> img):is(.active, :where(div)),
            li:nth-child(2n+1 of .item),
            \.foo\:bar::part(btn),
            slot::slotted(*),
            a[href^="https" i][data-val~="foo"] {
              --brand-500: #0af;
              color: color-mix(in oklch, var(--brand-500, #fffa) 70%, #0000);
              width: clamp(1.5e2px, 100dvh, 2fr);
              transition-duration: 200ms;
              & > .child:hover { opacity: 1 !important; }
            }
            @media (min-resolution: 2dppx) { .x { zoom: 1; } }
        """.trimIndent()

        val tokens = assertLosslessInvariants(CssSyntaxLexer, code, "css-edge-cases")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid }, "No Invalid tokens expected in valid CSS")

        fun firstKind(text: String, after: Int = 0): SyntaxTokenKind =
            tokens.first { it.startIndex >= after && it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Keyword, firstKind("@starting-style"))
        assertEquals(SyntaxTokenKind.String, firstKind("https://example.com/a.png?v=1"))
        assertEquals(SyntaxTokenKind.Number, firstKind("U+0025-00FF"))
        assertEquals(SyntaxTokenKind.Number, firstKind("U+4??"))
        assertEquals(SyntaxTokenKind.Number, firstKind("50%"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0.5turn"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("has"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("img"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("div"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("of"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("item", after = code.indexOf("nth-child")))
        assertEquals(SyntaxTokenKind.Tag, firstKind("\\.foo\\:bar"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("part"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("slotted"))
        assertEquals(SyntaxTokenKind.Function, firstKind("color-mix"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1.5e2px"))
        assertEquals(SyntaxTokenKind.Number, firstKind("100dvh"))
        assertEquals(SyntaxTokenKind.Number, firstKind("2fr"))
        assertEquals(SyntaxTokenKind.Number, firstKind("200ms"))
        assertEquals(SyntaxTokenKind.Number, firstKind("2dppx"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("&"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("i", after = code.indexOf("href^=")))
    }

    @Test
    fun handlesPropertyAtRuleAndSupplementaryUnicodeIdentifiers() {
        val code = """
            @property --accent-hue { syntax: "<number>"; }
            :root { --🚀-gap: 8px; --café-accent: #fff; }
        """.trimIndent()
        val tokens = assertLosslessInvariants(CssSyntaxLexer, code, "css-unicode-ident")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "--accent-hue" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "--🚀-gap" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "--café-accent" }.kind)
    }

    @Test
    fun handlesRangeMediaOperatorsPipesAndLeadingBom() {
        val code = "\uFEFF@media (400px <= width <= 700px) { a { color: red; } }\n" +
            "@container card (width < 30em) { .t { gap: 1px; } }\n" +
            "svg|rect, *|a { fill: red; }\n" +
            "col.a || td { color: red; }"
        val tokens = assertLosslessInvariants(CssSyntaxLexer, code, "css-operators-bom")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Whitespace, tokens.first().kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "@media" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "<=" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "<" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "|" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "||" }.kind)
    }
}
