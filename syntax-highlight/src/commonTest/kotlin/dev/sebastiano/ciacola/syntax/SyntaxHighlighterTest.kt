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
    fun registryProvidesSupportedLanguagesAndHandlesUnsupportedCleanly() {
        assertEquals(
            setOf(
                SyntaxLanguage.Kotlin,
                SyntaxLanguage.Xml,
                SyntaxLanguage.Json,
                SyntaxLanguage.Html,
                SyntaxLanguage.Css,
                SyntaxLanguage.JavaScript,
                SyntaxLanguage.TypeScript,
                SyntaxLanguage.Rust,
                SyntaxLanguage.Go,
                SyntaxLanguage.C,
                SyntaxLanguage.Cpp,
                SyntaxLanguage.Glsl,
                SyntaxLanguage.Sksl,
                SyntaxLanguage.Agsl,
            ),
            SyntaxHighlighter.supportedLanguages,
        )

        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Kotlin))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Xml))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Json))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Html))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Css))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.JavaScript))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.TypeScript))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Rust))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Go))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.C))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Cpp))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Glsl))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Sksl))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Agsl))
        assertNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Python))

        assertFailsWith<IllegalArgumentException> {
            SyntaxHighlighter.lexer(SyntaxLanguage.Python)
        }

        val ktTokens = SyntaxHighlighter.lex("val x = 1", "kt")
        assertNotNull(ktTokens)
        assertTrue(ktTokens.isNotEmpty())
        assertEquals("val x = 1", ktTokens.joinToString("") { it.slice("val x = 1") })

        val aliasSamples = mapOf(
            "rs" to "fn main() {}",
            "golang" to "func main() {}",
            "h" to "int x = 1;",
            "c++" to "auto x = 0uz;",
            "vert" to "vec4 p = vec4(1.0);",
            "sksl" to "half4 main(float2 p) { return half4(1.0); }",
            "agsl" to "uniform float2 iResolution;",
        )
        for ((alias, snippet) in aliasSamples) {
            val aliasTokens = SyntaxHighlighter.lex(snippet, alias)
            assertNotNull(aliasTokens, "Expected lexer for alias '$alias'")
            assertTrue(aliasTokens.isNotEmpty())
            assertEquals(snippet, aliasTokens.joinToString("") { it.slice(snippet) })
        }

        assertNull(SyntaxHighlighter.lex("print(1)", "py"))
        assertNull(SyntaxHighlighter.lex("foo", "unknown-lang"))
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
}
