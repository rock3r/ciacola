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
                SyntaxLanguage.Python,
                SyntaxLanguage.Toml,
                SyntaxLanguage.Java,
            ),
            SyntaxHighlighter.supportedLanguages,
        )

        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Kotlin))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Xml))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Json))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Python))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Toml))
        assertNotNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Java))
        assertNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Rust))

        assertFailsWith<IllegalArgumentException> {
            SyntaxHighlighter.lexer(SyntaxLanguage.Rust)
        }

        val ktTokens = SyntaxHighlighter.lex("val x = 1", "kt")
        assertNotNull(ktTokens)
        assertTrue(ktTokens.isNotEmpty())
        assertEquals("val x = 1", ktTokens.joinToString("") { it.slice("val x = 1") })

        val pyTokens = SyntaxHighlighter.lex("print(1)", "py")
        assertNotNull(pyTokens)
        assertTrue(pyTokens.isNotEmpty())

        assertNull(SyntaxHighlighter.lex("fn main() {}", "rs"))
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
