package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.HtmlSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.HtmlOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlSyntaxLexerTest {

    @Test
    fun matchesOracleFixtures() {
        for (fixture in HtmlOracleFixtures.all) {
            assertMatchesOracleFixture(fixture)
        }
    }

    @Test
    fun delegatesEmbeddedStyleScriptJsonAndTypeScriptWithExactOffsets() {
        val code = """
            <!DOCTYPE html>
            <?custom-pi arg="1"?>
            <html lang="en">
              <head>
                <style>
                  .card { --pad: 12px; color: #ff00aa; }
                </style>
              </head>
              <body>
                <!-- comment --- still comment -->
                <div id="app" data-mode='fast' loading=lazy>
                  <img src="a.png" alt='b' />
                  <input disabled>
                  <p>Entities: &amp; &nbsp; &#10; &#x1F600; &</p>
                  <![CDATA[raw <text>]]>
                </div>
                <script type="application/json">{"count": 42, "ok": true}</script>
                <script type="text/typescript">interface Item { readonly id: number; }</script>
                <script lang="ts">const x: string = "ts";</script>
                <script>
                  const s = "</div>";
                  if (a < b) { console.log(s); }
                </script>
              </body>
            </html>
        """.trimIndent()

        val tokens = assertLosslessInvariants(HtmlSyntaxLexer, code, "html-embedded")

        fun firstKind(text: String, after: Int = 0): SyntaxTokenKind =
            tokens.first { it.startIndex >= after && it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Tag, firstKind("DOCTYPE"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("html", after = code.indexOf("DOCTYPE")))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("<?custom-pi arg=\"1\"?>"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("<!-- comment --- still comment -->"))
        assertEquals(SyntaxTokenKind.String, firstKind("<![CDATA[raw <text>]]>"))

        assertEquals(SyntaxTokenKind.Attribute, firstKind("loading"))
        assertEquals(SyntaxTokenKind.String, firstKind("lazy"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("disabled"))

        for (entity in listOf("&amp;", "&nbsp;", "&#10;", "&#x1F600;")) {
            assertEquals(SyntaxTokenKind.Constant, firstKind(entity), "Entity $entity")
        }

        // Embedded CSS
        val styleOffset = code.indexOf("<style>")
        assertEquals(SyntaxTokenKind.Attribute, firstKind("card", after = styleOffset))
        assertEquals(SyntaxTokenKind.Property, firstKind("--pad", after = styleOffset))
        assertEquals(SyntaxTokenKind.Number, firstKind("12px", after = styleOffset))
        assertEquals(SyntaxTokenKind.Property, firstKind("color", after = styleOffset))
        assertEquals(SyntaxTokenKind.Number, firstKind("#ff00aa", after = styleOffset))

        // Embedded JSON
        val jsonOffset = code.indexOf("application/json")
        assertEquals(SyntaxTokenKind.Property, firstKind("\"count\"", after = jsonOffset))
        assertEquals(SyntaxTokenKind.Number, firstKind("42", after = jsonOffset))
        assertEquals(SyntaxTokenKind.Constant, firstKind("true", after = jsonOffset))

        // Embedded TypeScript (both type="text/typescript" and lang="ts")
        val tsOffset = code.indexOf("text/typescript")
        assertEquals(SyntaxTokenKind.Keyword, firstKind("interface", after = tsOffset))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("readonly", after = tsOffset))
        assertEquals(SyntaxTokenKind.Type, firstKind("number", after = tsOffset))
        val tsLangOffset = code.indexOf("lang=\"ts\"")
        assertEquals(SyntaxTokenKind.Type, firstKind("string", after = tsLangOffset))

        // Embedded JavaScript with `</div>` string literal and `<` comparison inside body
        val jsOffset = code.lastIndexOf("<script>")
        assertEquals(SyntaxTokenKind.String, firstKind("\"</div>\"", after = jsOffset))
        assertEquals(SyntaxTokenKind.Operator, firstKind("<", after = jsOffset + 8))
        assertEquals(SyntaxTokenKind.Function, firstKind("log", after = jsOffset))
    }

    @Test
    fun handlesUnclosedScriptStyleCommentsCdataAndCrlf() {
        val unclosedComment = "<div><!-- unclosed --- comment\r\n"
        val cTokens = assertLosslessInvariants(HtmlSyntaxLexer, unclosedComment, "html-unclosed-comment")
        assertTrue(cTokens.any { it.slice(unclosedComment).startsWith("<!--") && it.kind == SyntaxTokenKind.Comment })

        val unclosedScript = "<script>const x = 42;"
        val sTokens = assertLosslessInvariants(HtmlSyntaxLexer, unclosedScript, "html-unclosed-script")
        assertEquals(SyntaxTokenKind.Keyword, sTokens.first { it.slice(unclosedScript) == "const" }.kind)
        assertEquals(SyntaxTokenKind.Number, sTokens.first { it.slice(unclosedScript) == "42" }.kind)

        val unclosedStyle = "<style>.a { color: #fff;"
        val stTokens = assertLosslessInvariants(HtmlSyntaxLexer, unclosedStyle, "html-unclosed-style")
        assertEquals(SyntaxTokenKind.Property, stTokens.first { it.slice(unclosedStyle) == "color" }.kind)
        assertEquals(SyntaxTokenKind.Number, stTokens.first { it.slice(unclosedStyle) == "#fff" }.kind)

        val emptyScript = "<script></script><style></style>"
        assertLosslessInvariants(HtmlSyntaxLexer, emptyScript, "html-empty-raw-tags")
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            <!DOCTYPE html>
            <html>
              <style>.a { color: #123; }</style>
              <body>
                <div id="x" loading=lazy><!-- note -->Hi &amp; bye</div>
                <script>const y = /re/.test("a");</script>
              </body>
            </html>
        """.trimIndent()
        assertPrefixTruncationSafety(HtmlSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(HtmlSyntaxLexer, seed = 0x48544D4CL, iterations = 70)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val unclosedComment = "<!-- " + "-".repeat(25_000)
        val hugeAttributes = "<div " + (1..2_000).joinToString(" ") { "data-a$it=\"val$it\"" } + "></div>"
        val hugeScript = "<script>" + "const x = 1 + 2;\n".repeat(1_500) + "</script>"
        val manyTags = "<ul>" + "<li>Item &amp; value</li>".repeat(1_500) + "</ul>"

        assertLinearPerformance(
            lexer = HtmlSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-comment" to unclosedComment,
                "huge-attributes" to hugeAttributes,
                "huge-script" to hugeScript,
                "many-tags" to manyTags,
            ),
        )
    }

    @Test
    fun handlesVoidElementsCustomElementsNamespacesImportmapAndEscapedScriptClose() {
        val code = """
            <!DOCTYPE html>
            <html>
              <head><meta charset="utf-8"><link rel="stylesheet" href="a.css"></head>
              <body>
                <hr><br><img src="a.png" alt="a"><input type=checkbox checked data-x='1'><source src="v.mp4">
                <my-widget xlink:href="#i">&copy; &#8212; &#x1F680;</my-widget>
                <script type="importmap">{"imports": {"app": "/app.js"}}</script>
                <script type="module">
                  if (a < b && c > d) {
                    const s = "<\/script>";
                    console.log(s);
                  }
                </script>
              </body>
            </html>
        """.trimIndent()

        val tokens = assertLosslessInvariants(HtmlSyntaxLexer, code, "html-edge-cases")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })

        fun firstKind(text: String, after: Int = 0): SyntaxTokenKind =
            tokens.first { it.startIndex >= after && it.slice(code) == text }.kind

        for (voidTag in listOf("meta", "link", "hr", "br", "img", "input", "source")) {
            assertEquals(SyntaxTokenKind.Tag, firstKind(voidTag), "Void element $voidTag")
        }
        assertEquals(SyntaxTokenKind.String, firstKind("checkbox"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("checked"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("my-widget"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("xlink:href"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("&copy;"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("&#8212;"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("&#x1F680;"))
        assertEquals(SyntaxTokenKind.Property, firstKind("\"imports\""))
        assertEquals(SyntaxTokenKind.String, firstKind("\"<\\/script>\""))
        assertEquals(SyntaxTokenKind.Function, firstKind("log"))
    }

    @Test
    fun handlesModernTemplateBindingAttributes() {
        val code = """<button @click="onSave" #btn ?disabled="isOff" *ngIf="show" [title]="t" (focus)="onFocus()" [(ngModel)]="val">Save</button>"""
        val tokens = assertLosslessInvariants(HtmlSyntaxLexer, code, "html-template-attrs")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })

        for (attr in listOf("@click", "#btn", "?disabled", "*ngIf", "[title]", "(focus)", "[(ngModel)]")) {
            assertEquals(SyntaxTokenKind.Attribute, tokens.first { it.slice(code) == attr }.kind, "Template attr $attr")
        }
    }

    @Test
    fun handlesBomAndUnicodeTagAndAttributeNames() {
        val code = "\uFEFF<café prix=\"1\"><größe/></café>"
        val tokens = assertLosslessInvariants(HtmlSyntaxLexer, code, "html-unicode-bom")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Whitespace, tokens.first().kind)
        assertEquals(SyntaxTokenKind.Tag, tokens.first { it.slice(code) == "café" }.kind)
        assertEquals(SyntaxTokenKind.Attribute, tokens.first { it.slice(code) == "prix" }.kind)
        assertEquals(SyntaxTokenKind.Tag, tokens.first { it.slice(code) == "größe" }.kind)
    }
}
