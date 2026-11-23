package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.TomlSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.TomlOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TomlSyntaxLexerTest {

    @Test
    fun classifiesTablesKeysStringsDatesNumbersBooleansAndInlineStructures() {
        val code = """
            # Package manifest
            [package]
            name = "ciacola"
            123 = "numeric-bare-key"
            true = "boolean-bare-key"

            [dependencies.serde]
            "a.b".'c' = { version = "1.0", features = [ "derive", "alloc" ] }

            ["quoted.table".sub]
            basic = "line1\n\u00E9\U0001F600"
            ml_basic = ${"\"\"\""}
            hello \
              world ""two quotes before end""${"\"\"\""}
            lit = 'C:\Users\raw\no\escape'
            ml_lit = '''
            raw ''two single quotes'''''

            [scalars]
            pos = +99
            neg = -17
            big = 1_000_000
            hex = 0xdead_beef
            oct = 0o755
            bin = 0b1101_0110
            flt1 = 3.1415
            flt2 = 5e+22
            flt3 = -0.01
            flt4 = 6.626e-34
            inf1 = inf
            inf2 = +inf
            inf3 = -inf
            nan1 = nan
            nan2 = +nan
            nan3 = -nan
            flag = false
            odt = 2026-11-23T10:45:00Z
            odt2 = 2026-11-23 10:45:00+02:00
            ldt = 2026-11-23T10:45:00.123
            ld = 2026-11-23
            lt = 10:45:00.5

            [[bin]]
            name = "cli"
            [[products.items]]
            nums = [ [1, 2], [3, 4] ]
        """.trimIndent()

        val tokens = assertLosslessInvariants(TomlSyntaxLexer, code, "toml-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Comment, firstKind("# Package manifest"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("package"))
        assertEquals(SyntaxTokenKind.Property, firstKind("name"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("="))
        assertEquals(SyntaxTokenKind.String, firstKind("\"ciacola\""))
        assertEquals(SyntaxTokenKind.Property, firstKind("123"))
        assertEquals(SyntaxTokenKind.Property, firstKind("true"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("dependencies"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("serde"))
        assertEquals(SyntaxTokenKind.Property, firstKind("\"a.b\""))
        assertEquals(SyntaxTokenKind.Property, firstKind("'c'"))
        assertEquals(SyntaxTokenKind.Property, firstKind("version"))
        assertEquals(SyntaxTokenKind.Property, firstKind("features"))
        assertEquals(SyntaxTokenKind.String, firstKind("\"derive\""))
        assertEquals(SyntaxTokenKind.Tag, firstKind("\"quoted.table\""))
        assertEquals(SyntaxTokenKind.Tag, firstKind("sub"))
        assertEquals(SyntaxTokenKind.String, firstKind("'C:\\Users\\raw\\no\\escape'"))
        assertTrue(tokens.any { it.slice(code).contains("two quotes before end") && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code).contains("two single quotes") && it.kind == SyntaxTokenKind.String })

        assertEquals(SyntaxTokenKind.Number, firstKind("+99"))
        assertEquals(SyntaxTokenKind.Number, firstKind("-17"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1_000_000"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xdead_beef"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0o755"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1101_0110"))
        assertEquals(SyntaxTokenKind.Number, firstKind("3.1415"))
        assertEquals(SyntaxTokenKind.Number, firstKind("5e+22"))
        assertEquals(SyntaxTokenKind.Number, firstKind("-0.01"))
        assertEquals(SyntaxTokenKind.Number, firstKind("6.626e-34"))
        assertEquals(SyntaxTokenKind.Number, firstKind("inf"))
        assertEquals(SyntaxTokenKind.Number, firstKind("+inf"))
        assertEquals(SyntaxTokenKind.Number, firstKind("-inf"))
        assertEquals(SyntaxTokenKind.Number, firstKind("nan"))
        assertEquals(SyntaxTokenKind.Number, firstKind("+nan"))
        assertEquals(SyntaxTokenKind.Number, firstKind("-nan"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("false"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("2026-11-23T10:45:00Z"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("2026-11-23 10:45:00+02:00"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("2026-11-23T10:45:00.123"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("2026-11-23"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("10:45:00.5"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("bin") { it.startIndex > code.indexOf("[[bin]]") })
        assertEquals(SyntaxTokenKind.Tag, firstKind("products"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("items"))

        val invalid = "bad_val = unquoted\n"
        val invalidTokens = assertLosslessInvariants(TomlSyntaxLexer, invalid, "toml-invalid")
        assertEquals(SyntaxTokenKind.Property, invalidTokens.first { it.slice(invalid) == "bad_val" }.kind)
        assertEquals(SyntaxTokenKind.Invalid, invalidTokens.first { it.slice(invalid) == "unquoted" }.kind)
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            [section.sub]
            key = "value"
            inline = { a = 1, b = [true, 2026-11-23] }
        """.trimIndent()
        assertPrefixTruncationSafety(TomlSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(TomlSyntaxLexer, seed = 0x544F4D4CL, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val manyEntries = buildString {
            repeat(3_000) { idx ->
                append("key_").append(idx).append(" = ").append(idx).append('\n')
            }
        }
        val hugeArray = "arr = [" + (1..20_000).joinToString(",") + "]"
        val longMultiline = "s = \"\"\"\n" + "abc\"\"def".repeat(3_500) + "\"\"\""

        assertLinearPerformance(
            lexer = TomlSyntaxLexer,
            pathologicalInputs = listOf(
                "many-entries" to manyEntries,
                "20k-array" to hugeArray,
                "long-multiline" to longMultiline,
            ),
        )
    }

    @Test
    fun matchesDualOracleFixture() {
        assertMatchesOracleFixture(TomlOracleFixtures.fixture)
    }

    @Test
    fun handlesToml11EscapesEmptyKeysDottedKeysAndMultilineInlineTables() {
        val code = """
            "" = "empty-key"
            1234 = "num-key"
            a.b."c.d".e = 1
            esc = "\e\x1b\b\t\n\f\r\"\\\u00E9\U0001F600"
            space_z = 2026-11-23 10:45:00z
            short_time = 10:45
            multiline_inline = {
                x = 1, # comment
                y = [
                    2, # item
                    3,
                ],
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(TomlSyntaxLexer, code, "toml-11-edge")
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "\"\"" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "1234" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "\"c.d\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).contains("\\e\\x1b") }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "2026-11-23 10:45:00z" }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "10:45" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "x" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "y" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code) == "# comment" }.kind)
    }
}
