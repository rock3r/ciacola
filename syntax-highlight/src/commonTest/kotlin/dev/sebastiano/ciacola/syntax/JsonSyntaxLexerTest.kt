package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.JsonSyntaxLexer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSyntaxLexerTest {

    @Test
    fun classifiesPropertyKeysStringsNumbersConstantsCommentsAndInvalid() {
        val code = """
            {
              // line comment
              "title": "Ciacola",
              "escaped": "a\"b\\c\u0041",
              "count": -42,
              "ratio": 3.14e-2,
              "zero": 0,
              "enabled": true,
              "disabled": false,
              "empty": null,
              /* block
                 comment */
              "keyWithComment" /* inline */ : "value",
              "items": ["one", "two"]
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(JsonSyntaxLexer, code, "json-features")

        fun firstKind(text: String): SyntaxTokenKind =
            tokens.first { it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Comment, firstKind("// line comment"))
        assertEquals(SyntaxTokenKind.Property, firstKind("\"title\""))
        assertEquals(SyntaxTokenKind.String, firstKind("\"Ciacola\""))
        assertEquals(SyntaxTokenKind.Property, firstKind("\"escaped\""))
        assertEquals(SyntaxTokenKind.String, firstKind("\"a\\\"b\\\\c\\u0041\""))
        assertEquals(SyntaxTokenKind.Number, firstKind("-42"))
        assertEquals(SyntaxTokenKind.Number, firstKind("3.14e-2"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("true"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("false"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("null"))
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code).startsWith("/* block") }.kind)
        assertEquals(SyntaxTokenKind.Property, firstKind("\"keyWithComment\""))
        assertEquals(SyntaxTokenKind.String, firstKind("\"value\""))
        assertEquals(SyntaxTokenKind.Property, firstKind("\"items\""))
        assertEquals(SyntaxTokenKind.String, firstKind("\"one\""))

        val invalid = """{"ok": undefined, "bad": -}"""
        val invalidTokens = assertLosslessInvariants(JsonSyntaxLexer, invalid, "json-invalid")
        assertTrue(invalidTokens.any { it.slice(invalid) == "undefined" && it.kind == SyntaxTokenKind.Invalid })
        assertTrue(invalidTokens.any { it.slice(invalid) == "-" && it.kind == SyntaxTokenKind.Invalid })

        val unterminated = """{"a": "open\""" + "\n" + """/* unclosed"""
        val unterminatedTokens = assertLosslessInvariants(JsonSyntaxLexer, unterminated, "json-unterminated")
        assertTrue(unterminatedTokens.any { it.slice(unterminated).startsWith("\"open") && it.kind == SyntaxTokenKind.String })
        assertTrue(unterminatedTokens.any { it.slice(unterminated) == "/* unclosed" && it.kind == SyntaxTokenKind.Comment })
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            {
              // header
              "name": "Ciacola",
              "nums": [1, -2.5e+3, 0],
              "flags": [true, false, null]
            }
        """.trimIndent()
        assertPrefixTruncationSafety(JsonSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(JsonSyntaxLexer, seed = 0x4A534F4EL, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val largeArray = "[" + (1..20_000).joinToString(",") + "]"
        val manyKeys = "{" + (1..4_000).joinToString(",") { "\"key_$it\":\"val_$it\"" } + "}"
        val longString = "\"${"x\\\"".repeat(8_000)}\""

        assertLinearPerformance(
            lexer = JsonSyntaxLexer,
            pathologicalInputs = listOf(
                "20k-numbers" to largeArray,
                "4k-object-entries" to manyKeys,
                "long-escaped-string" to longString,
            ),
        )
    }

    @Test
    fun handlesFullEscapesSurrogatesSignedZerosTrailingCommasAndInvalidForms() {
        val code = """
            {
              "escapes": "\"\\\/\b\f\n\r\t\u00E9\uD83D\uDE00",
              "negZero": -0,
              "floatZero": 0.0,
              "exp": 1e+10,
              "negExp": -3.14E-2,
              "deep": { "a": [ { "b": true }, ], },
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(JsonSyntaxLexer, code, "json-backfill")
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "\"escapes\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).contains("\\uD83D\\uDE00") }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "-0" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0.0" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "1e+10" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "-3.14E-2" }.kind)

        val bad = "{ unquoted: 'single', bad: NaN }"
        val badTokens = assertLosslessInvariants(JsonSyntaxLexer, bad, "json-bad")
        assertTrue(badTokens.any { it.slice(bad) == "unquoted" && it.kind == SyntaxTokenKind.Invalid })
        assertTrue(badTokens.any { it.slice(bad) == "'" && it.kind == SyntaxTokenKind.Invalid })
        assertTrue(badTokens.any { it.slice(bad) == "NaN" && it.kind == SyntaxTokenKind.Invalid })
    }
}
