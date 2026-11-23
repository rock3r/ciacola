package dev.sebastiano.ciacola.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyntaxHighlighterTest {

    @Test
    fun resolvesAllLanguagesByIdAndAliases() {
        assertEquals(SyntaxLanguage.entries.size, SyntaxLanguage.entries.toSet().size)
        assertEquals(22, SyntaxLanguage.entries.size)

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
        assertEquals(SyntaxLanguage.Yaml, SyntaxLanguage.fromNameOrAlias("yml"))
        assertEquals(SyntaxLanguage.Sql, SyntaxLanguage.fromNameOrAlias("postgresql"))
        assertEquals(SyntaxLanguage.Shell, SyntaxLanguage.fromNameOrAlias("zsh"))
        assertEquals(SyntaxLanguage.CSharp, SyntaxLanguage.fromNameOrAlias("c#"))
        assertEquals(SyntaxLanguage.Php, SyntaxLanguage.fromNameOrAlias("phtml"))
        assertNull(SyntaxLanguage.fromNameOrAlias(""))
        assertNull(SyntaxLanguage.fromNameOrAlias("   "))
        assertNull(SyntaxLanguage.fromNameOrAlias("brainfuck"))
    }

    @Test
    fun registryProvidesAllSupportedLanguagesAndHandlesUnknownCleanly() {
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
            "yml" to "key: true",
            "postgresql" to "SELECT 1;",
            "zsh" to "echo \"hello ${'$'}USER\"",
            "cs" to "record Point(int X, int Y);",
            "phtml" to "<?php echo 1; ?>",
        )
        assertEquals(22, aliasSamples.size)
        assertEquals(SyntaxLanguage.entries.size, aliasSamples.size)
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
    fun validSnippetsAcrossAllLanguagesProduceZeroInvalidTokens() {
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
            SyntaxLanguage.Yaml to """
                %YAML 1.2
                ---
                # Config
                name: ciacola
                ports: [8080, 8443]
                enabled: true
            """.trimIndent(),
            SyntaxLanguage.Sql to """
                /* query */
                SELECT u.id, COUNT(*) AS total
                FROM users u
                WHERE u.active = TRUE;
            """.trimIndent(),
            SyntaxLanguage.Shell to """
                #!/usr/bin/env bash
                # Deploy script
                export APP_ENV="prod"
                if [[ -n "${'$'}APP_ENV" ]]; then
                    echo "${'$'}APP_ENV"
                fi
            """.trimIndent(),
            SyntaxLanguage.CSharp to """
                [Serializable]
                public record User(string Name) {
                    public string Greet() => $"Hello, {Name}!";
                }
            """.trimIndent(),
            SyntaxLanguage.Php to """
                <?php
                #[Route('/api')]
                function greet(string ${'$'}name): string {
                    return "Hello, ${'$'}name!";
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
            SyntaxLanguage.Yaml to (
                listOf(
                    "\"hello\"" to SyntaxTokenKind.String,
                    "'world'" to SyntaxTokenKind.String,
                    "# note\n" to null,
                    "a: |\n  line 1\n  line 2\n" to null,
                ) to listOf(" b: \"next\"\n", " c: 'tail'\n", " # end\n")
                ),
            SyntaxLanguage.Sql to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "/* a /* nested */ b */" to SyntaxTokenKind.Comment,
                    "'hello'" to SyntaxTokenKind.String,
                    "${'$'}${'$'}raw${'$'}${'$'}" to SyntaxTokenKind.String,
                    "${'$'}tag${'$'}a ${'$'}x${'$'} b${'$'}tag${'$'}" to SyntaxTokenKind.String,
                ) to listOf(" /* c */", " 'd'", " ${'$'}tag${'$'}e${'$'}tag${'$'}", "*/", " SELECT 1;")
                ),
            SyntaxLanguage.Shell to (
                listOf(
                    "# comment\n" to null,
                    "'single'" to SyntaxTokenKind.String,
                    "\"double\"" to null,
                    "cat <<'EOF'\nbody\nEOF\n" to null,
                ) to listOf(" ; echo hi", " # tail\n", " 'next'")
                ),
            SyntaxLanguage.CSharp to (
                listOf(
                    "/* a */" to SyntaxTokenKind.Comment,
                    "@\"verb\"" to SyntaxTokenKind.String,
                    "\"\"\"raw\"\"\"" to SyntaxTokenKind.String,
                    "\"hello\"" to SyntaxTokenKind.String,
                ) to listOf("/* b */", " \"c\"", "*/", " \"\"\"d\"\"\"")
                ),
            SyntaxLanguage.Php to (
                listOf(
                    "<?php /* a */ ?>" to null,
                    "<?php 'hello' ?>" to null,
                    "<?php \"world\" ?>" to null,
                ) to listOf("<?php /* b */ ?>", "<div>hi</div>", "<?= 1 ?>")
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
        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val trickyInputs = listOf(
            "𠮷",
            "const x = 𠮷",
            "// trailing emoji 🎉",
            "\"emoji at end 🎉\"",
            "a$loneHigh",
            "𠮷$loneHigh",
            "𠮷${loneHigh}x",
            "${loneLow}a",
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
            SyntaxLanguage.CSharp,
        )) {
            val tokens = assertValidSnippetHasNoInvalidTokens(
                SyntaxHighlighter.lexer(language),
                commentEndingInEmoji,
                "${language.id}:trailing-emoji-comment",
            )
            assertEquals(1, tokens.size, "[${language.id}] Expected single Comment token for '$commentEndingInEmoji', got $tokens")
            assertEquals(SyntaxTokenKind.Comment, tokens.single().kind)
        }

        val hashCommentEndingInEmoji = "# hi 🎉"
        for (language in listOf(
            SyntaxLanguage.Python,
            SyntaxLanguage.Toml,
            SyntaxLanguage.Yaml,
            SyntaxLanguage.Shell,
        )) {
            val tokens = assertValidSnippetHasNoInvalidTokens(
                SyntaxHighlighter.lexer(language),
                hashCommentEndingInEmoji,
                "${language.id}:trailing-emoji-hash-comment",
            )
            assertEquals(1, tokens.size, "[${language.id}] Expected single Comment token for '$hashCommentEndingInEmoji', got $tokens")
            assertEquals(SyntaxTokenKind.Comment, tokens.single().kind)
        }

        val sqlTokens = assertValidSnippetHasNoInvalidTokens(
            SyntaxHighlighter.lexer(SyntaxLanguage.Sql),
            "-- hi 🎉",
            "sql:trailing-emoji-dash-comment",
        )
        assertEquals(1, sqlTokens.size, "[sql] Expected single Comment token for '-- hi 🎉', got $sqlTokens")
        assertEquals(SyntaxTokenKind.Comment, sqlTokens.single().kind)

        val phpTokens = assertValidSnippetHasNoInvalidTokens(
            SyntaxHighlighter.lexer(SyntaxLanguage.Php),
            "<?php // hi 🎉",
            "php:trailing-emoji-comment",
        )
        assertEquals(SyntaxTokenKind.Comment, phpTokens.last().kind)
        assertEquals("// hi 🎉", phpTokens.last().slice("<?php // hi 🎉"))
    }

    @Test
    fun unicodeIdentifiersAndWhitespaceFollowPerLanguageSpecifications() {
        fun assertSingleIdent(language: SyntaxLanguage, ident: String) {
            val tokens = assertValidSnippetHasNoInvalidTokens(
                SyntaxHighlighter.lexer(language),
                ident,
                "${language.id}:ident:$ident",
            )
            assertEquals(
                1,
                tokens.size,
                "[${language.id}] Expected '$ident' to lex as a single identifier token, got $tokens",
            )
            assertEquals(SyntaxTokenKind.Plain, tokens.single().kind, "[${language.id}] Expected '$ident' to be Plain")
        }

        fun assertSplitBy(language: SyntaxLanguage, separator: Char, expectedSepKind: SyntaxTokenKind) {
            val code = "foo${separator}bar"
            val tokens = assertLosslessInvariants(
                SyntaxHighlighter.lexer(language),
                code,
                "${language.id}:sep:U+${separator.code.toString(16).uppercase()}",
            )
            assertEquals(
                3,
                tokens.size,
                "[${language.id}] Expected U+${separator.code.toString(16).uppercase()} to split identifiers, got $tokens",
            )
            assertEquals("foo", tokens[0].slice(code))
            assertEquals(SyntaxTokenKind.Plain, tokens[0].kind)
            assertEquals(separator.toString(), tokens[1].slice(code))
            assertEquals(expectedSepKind, tokens[1].kind)
            assertEquals("bar", tokens[2].slice(code))
            assertEquals(SyntaxTokenKind.Plain, tokens[2].kind)
        }

        fun assertRejectedAtStart(language: SyntaxLanguage, badStartChar: Char) {
            val code = "${badStartChar}foo"
            val tokens = assertLosslessInvariants(
                SyntaxHighlighter.lexer(language),
                code,
                "${language.id}:bad-start:U+${badStartChar.code.toString(16).uppercase()}",
            )
            assertEquals(2, tokens.size, "[${language.id}] Expected U+${badStartChar.code.toString(16).uppercase()} not to start an identifier")
            assertEquals(SyntaxTokenKind.Invalid, tokens[0].kind)
            assertEquals(badStartChar.toString(), tokens[0].slice(code))
            assertEquals(SyntaxTokenKind.Plain, tokens[1].kind)
            assertEquals("foo", tokens[1].slice(code))
        }

        // 1. UAX #31 XID_Start / XID_Continue languages: Rust, Python, C23, C++23.
        val xidIdents = listOf("人々", "a\u302Ab", "a\uFF10\uFF3Fb", "𠮷", "𝐀")
        val xidNonIdentChars = listOf('\u037A', '\u060C', '\u1680', '\u2E00', '\uFFE0')
        for (language in listOf(SyntaxLanguage.Rust, SyntaxLanguage.Python, SyntaxLanguage.C, SyntaxLanguage.Cpp)) {
            for (ident in xidIdents) assertSingleIdent(language, ident)
            assertRejectedAtStart(language, '\u302A')
            assertRejectedAtStart(language, '\u037A')
            for (bad in xidNonIdentChars) assertSplitBy(language, bad, SyntaxTokenKind.Invalid)
        }

        // 2. ECMAScript (JS / TS): ID_Start / ID_Continue (includes U+037A, U+309B, U+FDFA) + '$' + ZWNJ/ZWJ,
        // and Unicode Zs (e.g. U+00A0, U+1680, U+3000) is WhiteSpace.
        val jsIdents = listOf(
            "人々",
            "a\u302Ab",
            "a\uFF10\uFF3Fb",
            "𠮷",
            "𝐀",
            "\$foo",
            "a\u200Cb",
            "a\u200Db",
            "\u037Avalue",
            "foo\u037Abar",
            "\u309Btag",
            "\uFDFAname",
        )
        for (language in listOf(SyntaxLanguage.JavaScript, SyntaxLanguage.TypeScript)) {
            for (ident in jsIdents) assertSingleIdent(language, ident)
            assertRejectedAtStart(language, '\u302A')
            for (bad in listOf('\u060C', '\u2E00', '\uFFE0')) assertSplitBy(language, bad, SyntaxTokenKind.Invalid)
            for (ws in listOf('\u00A0', '\u1680', '\u3000')) assertSplitBy(language, ws, SyntaxTokenKind.Whitespace)
        }

        // 3. Java (JLS §3.8): Lu|Ll|Lt|Lm|Lo|Nl|Sc|Pc for start (includes U+037A, U+FFE0, €, U+FF3F;
        // excludes U+2118), plus Nd|Mc|Mn|Cf for part (includes U+00AD, U+200B; excludes U+00B7).
        val javaIdents = listOf(
            "人々",
            "a\u302Ab",
            "a\uFF10\uFF3Fb",
            "𠮷",
            "𝐀",
            "\u037Avalue",
            "foo\u037Abar",
            "foo\uFFE0bar",
            "\uFFE0price",
            "€amount",
            "\uFF3Fstart",
            "a\u00ADb",
            "a\u200Bb",
        )
        for (ident in javaIdents) assertSingleIdent(SyntaxLanguage.Java, ident)
        assertRejectedAtStart(SyntaxLanguage.Java, '\u302A')
        assertRejectedAtStart(SyntaxLanguage.Java, '\u2118')
        for (bad in listOf('\u00B7', '\u060C', '\u1680', '\u2E00')) assertSplitBy(SyntaxLanguage.Java, bad, SyntaxTokenKind.Invalid)

        // 4. Go: unicode_letter (L, includes U+037A Lm) | '_' for start, L | Nd | '_' for continuation;
        // rejects Mn (U+302A) and non-ASCII Pc (U+FF3F).
        val goIdents = listOf("人々", "a\uFF10b", "𠮷", "𝐀", "_ident", "\u037Avalue")
        for (ident in goIdents) assertSingleIdent(SyntaxLanguage.Go, ident)
        assertRejectedAtStart(SyntaxLanguage.Go, '\u302A')
        for (bad in listOf('\u302A', '\uFF3F', '\u060C', '\u1680', '\u2E00', '\uFFE0')) {
            assertSplitBy(SyntaxLanguage.Go, bad, SyntaxTokenKind.Invalid)
        }
    }
}
