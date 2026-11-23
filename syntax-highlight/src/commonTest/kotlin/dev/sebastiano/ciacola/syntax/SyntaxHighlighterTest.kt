package dev.sebastiano.ciacola.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyntaxHighlighterTest {

    @Test
    fun resolvesAllSeventeenLanguagesByIdAndAliases() {
        assertEquals(17, SyntaxLanguage.entries.size)

        for (lang in SyntaxLanguage.entries) {
            assertEquals(lang, SyntaxLanguage.fromNameOrAlias(lang.id))
            assertEquals(lang, SyntaxLanguage.fromNameOrAlias("  ${lang.id.uppercase()}  "))
            for (alias in lang.aliases) {
                assertEquals(lang, SyntaxLanguage.fromNameOrAlias(alias), "Alias $alias should resolve to $lang")
                assertEquals(lang, SyntaxLanguage.fromNameOrAlias(alias.uppercase()))
            }
        }

        assertEquals(SyntaxLanguage.Cpp, SyntaxLanguage.fromNameOrAlias("c++"))
        assertEquals(SyntaxLanguage.TypeScript, SyntaxLanguage.fromNameOrAlias("tsx"))
        assertEquals(SyntaxLanguage.Json, SyntaxLanguage.fromNameOrAlias("jsonc"))
        assertNull(SyntaxLanguage.fromNameOrAlias(""))
        assertNull(SyntaxLanguage.fromNameOrAlias("   "))
        assertNull(SyntaxLanguage.fromNameOrAlias("brainfuck"))
    }

    @Test
    fun registryProvidesAllSeventeenSupportedLanguagesAndHandlesUnknownCleanly() {
        assertEquals(
            SyntaxLanguage.entries.toSet(),
            SyntaxHighlighter.supportedLanguages,
        )

        for (language in SyntaxLanguage.entries) {
            val lexer = SyntaxHighlighter.lexerOrNull(language)
            assertNotNull(lexer, "Expected registered lexer for $language")
            assertEquals(language, lexer.language)
            assertEquals(lexer, SyntaxHighlighter.lexer(language))
        }

        val aliasSamples = mapOf(
            "kt" to "val x = 1",
            "svg" to "<svg viewBox=\"0 0 10 10\"/>",
            "jsonc" to "{ // comment\n  \"a\": 1\n}",
            "htm" to "<div class=\"hero\">Hi</div>",
            "css" to ".hero { color: #ff0; }",
            "jsx" to "export const App = () => <div/>;",
            "tsx" to "type Id = string; const el = <Box/>;",
            "rs" to "fn main() {}",
            "golang" to "func main() {}",
            "py" to "print(1)",
            "tml" to "title = \"Ciacola\"",
            "java" to "record Point(int x, int y) {}",
            "h" to "int x = 1;",
            "c++" to "auto x = 0uz;",
            "vert" to "vec4 p = vec4(1.0);",
            "sksl" to "half4 main(float2 p) { return half4(1.0); }",
            "agsl" to "uniform float2 iResolution;",
        )
        assertEquals(17, aliasSamples.size)
        for ((alias, snippet) in aliasSamples) {
            val aliasTokens = SyntaxHighlighter.lex(snippet, alias)
            assertNotNull(aliasTokens, "Expected lexer for alias '$alias'")
            assertTrue(aliasTokens.isNotEmpty())
            assertEquals(snippet, aliasTokens.joinToString("") { it.slice(snippet) })
        }

        assertNull(SyntaxHighlighter.lex("foo", "unknown-lang"))
        assertNull(SyntaxHighlighter.lex("foo", "   "))
    }

    @Test
    fun tokenValidatesBounds() {
        assertFailsWith<IllegalArgumentException> {
            SyntaxToken(SyntaxTokenKind.Plain, -1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SyntaxToken(SyntaxTokenKind.Plain, 5, 3)
        }
    }

    @Test
    fun validSnippetsAcrossAllSeventeenLanguagesProduceZeroInvalidTokens() {
        val validSnippets: Map<SyntaxLanguage, String> = mapOf(
            SyntaxLanguage.Kotlin to """
                @Composable
                fun Greeting(name: String = "World"): Unit {
                    /* outer /* nested */ comment */
                    val count = 1_000L
                    println("Hello, ${'$'}name (${'$'}{count + 1})")
                }
            """.trimIndent(),
            SyntaxLanguage.Xml to """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE note [<!ELEMENT note (#PCDATA)>]>
                <!-- header -->
                <note id="1"><![CDATA[a < b & c]]></note>
            """.trimIndent(),
            SyntaxLanguage.Json to """
                {
                  // JSONC line comment
                  /* block comment */
                  "name": "ciacola",
                  "version": 1.5e2,
                  "active": true,
                  "extra": null
                }
            """.trimIndent(),
            SyntaxLanguage.Html to """
                <!DOCTYPE html>
                <html lang="en">
                  <head><style>.hero { color: #ff0; }</style></head>
                  <body><!-- note --><script>const ok = 1 < 2;</script></body>
                </html>
            """.trimIndent(),
            SyntaxLanguage.Css to """
                @layer base {
                  .btn:hover::after {
                    --gap: 0.5rem;
                    background: url(https://example.com/a.png);
                    content: "go";
                  }
                }
            """.trimIndent(),
            SyntaxLanguage.JavaScript to """
                export const run = async (items) => {
                  const re = /foo\/[a-z]+/gi;
                  return items.map((x) => `${'$'}{x}: ${'$'}{re.test(x)}`);
                };
            """.trimIndent(),
            SyntaxLanguage.TypeScript to """
                export interface Pair<out A, out B> { readonly a: A; readonly b: B; }
                export const view = <T,>(items: Array<T>) => <section data-n={items.length}>{items.length}</section>;
            """.trimIndent(),
            SyntaxLanguage.Rust to """
                #[derive(Debug)]
                pub fn greet<'a>(name: &'a str) -> String {
                    /* outer /* nested */ */
                    let raw = r#"hello "world""#;
                    format!("{raw}: {name}")
                }
            """.trimIndent(),
            SyntaxLanguage.Go to """
                package main
                import "fmt"
                func main() {
                    raw := `hello\nworld`
                    fmt.Println(raw, 0xFF_00, 1+2i)
                }
            """.trimIndent(),
            SyntaxLanguage.Python to """
                @dataclass
                class Point:
                    x: float
                    y: float
                    def label(self) -> str:
                        return f"({self.x:.2f}, {self.y:.2f})"
            """.trimIndent(),
            SyntaxLanguage.Toml to """
                # Config
                [server]
                host = "127.0.0.1"
                port = 8_080
                created = 2026-11-23T10:45:00Z
                inline = { ok = true, tags = ['a', 'b'] }
            """.trimIndent(),
            SyntaxLanguage.Java to """
                @SuppressWarnings("unchecked")
                public record Point(int x, int y) {
                    static final String DOC = ""${'"'}
                        hello
                        ""${'"'};
                }
            """.trimIndent(),
            SyntaxLanguage.C to """
                #include <stdio.h>
                #define MAX(a, b) ((a) > (b) ? (a) : (b))
                int main(void) { return MAX(1, 2); }
            """.trimIndent(),
            SyntaxLanguage.Cpp to """
                #include <string_view>
                template <typename T>
                constexpr auto raw = R"delim(hello )delim" world)delim";
                auto n = 1'000'000uz;
            """.trimIndent(),
            SyntaxLanguage.Glsl to """
                #version 450
                layout(location = 0) in vec2 vUv;
                out vec4 fragColor;
                void main() { fragColor = vec4(vUv, 0.0, 1.0); }
            """.trimIndent(),
            SyntaxLanguage.Sksl to """
                uniform shader child;
                half4 main(float2 p) { return child.eval(p) * half4(1.0); }
            """.trimIndent(),
            SyntaxLanguage.Agsl to """
                uniform float2 iResolution;
                half4 main(float2 fragCoord) {
                    float2 uv = fragCoord / iResolution;
                    return half4(uv, 0.5, 1.0);
                }
            """.trimIndent(),
        )
        assertEquals(SyntaxLanguage.entries.toSet(), validSnippets.keys)
        for ((language, snippet) in validSnippets) {
            assertValidSnippetHasNoInvalidTokens(SyntaxHighlighter.lexer(language), snippet, language.id)
        }
    }

    @Test
    fun containerTokensAcrossAllLanguagesSatisfyConcatenationInvariants() {
        val cases: Map<SyntaxLanguage, Pair<List<Pair<String, SyntaxTokenKind?>>, List<String>>> = mapOf(
            SyntaxLanguage.Kotlin to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "/* a /* nested */ b */" to SyntaxTokenKind.Comment,
                    "\"hello\"" to null,
                    "\"\"\"raw\"\"\"" to null,
                ) to listOf(" /* b */", "x/* b */", " \"\"\"c\"\"\"", "*/", " val x = 1")
                ),
            SyntaxLanguage.Xml to (
                listOf(
                    "<!-- a -->" to SyntaxTokenKind.Comment,
                    "<![CDATA[x]]>" to SyntaxTokenKind.String,
                    "<?pi a?>" to null,
                    "<!DOCTYPE html>" to null,
                ) to listOf("<!-- b -->", "<![CDATA[y]]>", "<?pi b?>", "<tag/>", " -->", " ]]>", " ?>")
                ),
            SyntaxLanguage.Json to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "// line\n" to null,
                    "\"hello\"" to SyntaxTokenKind.String,
                ) to listOf("/* b */", "\"world\"", "*/", "123")
                ),
            SyntaxLanguage.Html to (
                listOf(
                    "<!-- a -->" to SyntaxTokenKind.Comment,
                    "<!DOCTYPE html>" to null,
                    "<style>.a{color:red}</style>" to null,
                    "<script>const x = 1;</script>" to null,
                ) to listOf("<!-- b -->", "<style>.b{}</style>", "<script>let y = 2;</script>", "<div>hi</div>", " -->")
                ),
            SyntaxLanguage.Css to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "\"hello\"" to SyntaxTokenKind.String,
                    "'world'" to SyntaxTokenKind.String,
                ) to listOf("/* b */", "\"c\"", "*/", " .cls { color: red; }")
                ),
            SyntaxLanguage.JavaScript to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "/** doc */" to SyntaxTokenKind.Comment,
                    "\"hello\"" to SyntaxTokenKind.String,
                    "'world'" to SyntaxTokenKind.String,
                    "`tpl`" to null,
                    "/a\\/b/g" to SyntaxTokenKind.String,
                ) to listOf("/* b */", " + \"c\"", ";\n/next/i", " */")
                ),
            SyntaxLanguage.TypeScript to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "\"hello\"" to SyntaxTokenKind.String,
                    "`tpl`" to null,
                    "/a\\/b/g" to SyntaxTokenKind.String,
                ) to listOf("/* b */", " + \"c\"", ";\n/next/i", " */")
                ),
            SyntaxLanguage.Rust to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "/* a /* nested */ b */" to SyntaxTokenKind.Comment,
                    "\"hello\"" to SyntaxTokenKind.String,
                    "r#\"a \"b\" c\"#" to SyntaxTokenKind.String,
                    "r##\"a \"# b\"##" to SyntaxTokenKind.String,
                ) to listOf(" /* c */", "x/* c */", " r#\"d\"#", "*/", " \"#")
                ),
            SyntaxLanguage.Go to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "`raw`" to SyntaxTokenKind.String,
                    "\"hello\"" to SyntaxTokenKind.String,
                    "'x'" to SyntaxTokenKind.String,
                ) to listOf("/* b */", "`c`", "\"d\"", "*/")
                ),
            SyntaxLanguage.Python to (
                listOf(
                    "\"\"\"a\"\"\"" to SyntaxTokenKind.String,
                    "'''a'''" to SyntaxTokenKind.String,
                    "r\"\"\"a\"\"\"" to SyntaxTokenKind.String,
                    "\"hello\"" to SyntaxTokenKind.String,
                ) to listOf(" \"\"\"b\"\"\"", " '''b'''", " \"c\"", " # note\n")
                ),
            SyntaxLanguage.Toml to (
                listOf(
                    "a = \"hello\"\n" to null,
                    "a = \"\"\"hello\"\"\"\n" to null,
                    "a = '''hello'''\n" to null,
                    "# comment\n" to null,
                ) to listOf("b = \"\"\"world\"\"\"\n", "c = '''lit'''\n", "# tail\n")
                ),
            SyntaxLanguage.Java to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "/** doc */" to SyntaxTokenKind.Comment,
                    "\"\"\"\nblock\n\"\"\"" to SyntaxTokenKind.String,
                    "\"hello\"" to SyntaxTokenKind.String,
                    "'x'" to SyntaxTokenKind.String,
                ) to listOf("/* b */", "\"\"\"\nc\n\"\"\"", "\"d\"", "*/")
                ),
            SyntaxLanguage.C to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "\"hello\"" to SyntaxTokenKind.String,
                    "'x'" to SyntaxTokenKind.String,
                ) to listOf("/* b */", "\"c\"", "*/")
                ),
            SyntaxLanguage.Cpp to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "R\"(raw)\"" to SyntaxTokenKind.String,
                    "R\"d(a )x\" b)d\"" to SyntaxTokenKind.String,
                    "\"hello\"" to SyntaxTokenKind.String,
                ) to listOf("/* b */", " R\"d(c)d\"", "\"e\"", "*/")
                ),
            SyntaxLanguage.Glsl to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "// line\n" to null,
                ) to listOf("/* b */", "*/", "vec4 p = vec4(1.0);")
                ),
            SyntaxLanguage.Sksl to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "// line\n" to null,
                ) to listOf("/* b */", "*/", "half4 p = half4(1.0);")
                ),
            SyntaxLanguage.Agsl to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "// line\n" to null,
                ) to listOf("/* b */", "*/", "float2 p = float2(1.0);")
                ),
        )
        assertEquals(SyntaxLanguage.entries.toSet(), cases.keys)
        for ((language, pair) in cases) {
            val (containers, tails) = pair
            assertContainerTokenConcatenation(
                lexer = SyntaxHighlighter.lexer(language),
                containers = containers,
                tails = tails,
                label = language.id,
            )
        }
    }

    @Test
    fun handlesTrailingSupplementaryCodePointsAndUnpairedSurrogatesLosslesslyAcrossAllLanguages() {
        val trickyInputs = listOf(
            "𠮷",
            "const x = 𠮷",
            "// trailing emoji 🎉",
            "\"emoji at end 🎉\"",
            "a\uD800",
            "𠮷\uD800",
            "𠮷\uD800x",
            "\uDC00a",
        )
        for (language in SyntaxLanguage.entries) {
            val lexer = SyntaxHighlighter.lexer(language)
            for ((idx, input) in trickyInputs.withIndex()) {
                assertLosslessInvariants(lexer, input, "${language.id}:surrogate#$idx")
            }
        }

        // Line comments ending in a supplementary code point must stay a single Comment token.
        val commentEndingInEmoji = "// hi 🎉"
        for (language in listOf(
            SyntaxLanguage.Kotlin,
            SyntaxLanguage.Json,
            SyntaxLanguage.JavaScript,
            SyntaxLanguage.TypeScript,
            SyntaxLanguage.Rust,
            SyntaxLanguage.Go,
            SyntaxLanguage.Java,
            SyntaxLanguage.C,
            SyntaxLanguage.Cpp,
            SyntaxLanguage.Glsl,
            SyntaxLanguage.Sksl,
            SyntaxLanguage.Agsl,
        )) {
            val tokens = assertValidSnippetHasNoInvalidTokens(
                SyntaxHighlighter.lexer(language),
                commentEndingInEmoji,
                "${language.id}:trailing-emoji-comment",
            )
            assertEquals(1, tokens.size, "[${language.id}] Expected single Comment token for '$commentEndingInEmoji', got $tokens")
            assertEquals(SyntaxTokenKind.Comment, tokens.single().kind)
        }
    }

    @Test
    fun unicodeXidIdentifiersAcceptValidCodePointsAndRejectNonXidPunctuation() {
        val xidLanguages = listOf(
            SyntaxLanguage.JavaScript,
            SyntaxLanguage.TypeScript,
            SyntaxLanguage.Rust,
            SyntaxLanguage.Go,
            SyntaxLanguage.Python,
            SyntaxLanguage.Java,
            SyntaxLanguage.C,
            SyntaxLanguage.Cpp,
        )
        val validIdents = listOf(
            "人々", // U+3005 CJK iteration mark (XID_Start)
            "a\u302Ab", // U+302A ideographic level tone mark (XID_Continue \ XID_Start)
            "a\uFF10\uFF3Fb", // U+FF10 fullwidth digit zero + U+FF3F fullwidth low line (XID_Continue)
            "𠮷", // U+20BB7 supplementary CJK ideograph (XID_Start)
            "𝐀", // U+1D400 mathematical bold A (XID_Start)
        )
        val nonXidChars = listOf(
            '\u060C', // Arabic comma
            '\u1680', // Ogham space mark
            '\u2E00', // Right angle substitution marker
            '\uFFE0', // Fullwidth cent sign
        )

        for (language in xidLanguages) {
            val lexer = SyntaxHighlighter.lexer(language)
            for (ident in validIdents) {
                val tokens = assertValidSnippetHasNoInvalidTokens(lexer, ident, "${language.id}:xid:$ident")
                assertEquals(
                    1,
                    tokens.size,
                    "[${language.id}] Expected '$ident' to lex as a single identifier token, got $tokens",
                )
                assertEquals(
                    SyntaxTokenKind.Plain,
                    tokens.single().kind,
                    "[${language.id}] Expected '$ident' to have kind Plain",
                )
            }

            // U+302A is XID_Continue but NOT XID_Start, so it cannot start an identifier.
            val badStart = "\u302Afoo"
            val badStartTokens = assertLosslessInvariants(lexer, badStart, "${language.id}:xid-bad-start")
            assertEquals(2, badStartTokens.size, "[${language.id}] Expected U+302A not to start an identifier")
            assertEquals(SyntaxTokenKind.Invalid, badStartTokens[0].kind)
            assertEquals("\u302A", badStartTokens[0].slice(badStart))
            assertEquals(SyntaxTokenKind.Plain, badStartTokens[1].kind)
            assertEquals("foo", badStartTokens[1].slice(badStart))

            // Non-XID punctuation/space code points must never be merged into identifiers.
            for (badChar in nonXidChars) {
                val code = "foo${badChar}bar"
                val tokens = assertLosslessInvariants(
                    lexer,
                    code,
                    "${language.id}:non-xid:U+${badChar.code.toString(16).uppercase()}",
                )
                assertEquals(
                    3,
                    tokens.size,
                    "[${language.id}] Non-XID U+${badChar.code.toString(16).uppercase()} must split identifiers, got $tokens",
                )
                assertEquals("foo", tokens[0].slice(code))
                assertEquals(SyntaxTokenKind.Plain, tokens[0].kind)
                assertEquals(badChar.toString(), tokens[1].slice(code))
                assertEquals(SyntaxTokenKind.Invalid, tokens[1].kind)
                assertEquals("bar", tokens[2].slice(code))
                assertEquals(SyntaxTokenKind.Plain, tokens[2].kind)
            }
        }
    }
}
