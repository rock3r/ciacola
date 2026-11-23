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
}
