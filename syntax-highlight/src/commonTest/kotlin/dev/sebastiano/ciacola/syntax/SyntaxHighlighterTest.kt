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
    fun registryProvidesRegisteredLexersAndHandlesUnsupportedCleanly() {
        assertEquals(
            setOf(
                SyntaxLanguage.Kotlin,
                SyntaxLanguage.Xml,
                SyntaxLanguage.Json,
                SyntaxLanguage.Html,
                SyntaxLanguage.Css,
                SyntaxLanguage.JavaScript,
                SyntaxLanguage.TypeScript,
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
        assertNull(SyntaxHighlighter.lexerOrNull(SyntaxLanguage.Python))

        assertFailsWith<IllegalArgumentException> {
            SyntaxHighlighter.lexer(SyntaxLanguage.Python)
        }

        val ktTokens = SyntaxHighlighter.lex("val x = 1", "kt")
        assertNotNull(ktTokens)
        assertTrue(ktTokens.isNotEmpty())
        assertEquals("val x = 1", ktTokens.joinToString("") { it.slice("val x = 1") })

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
