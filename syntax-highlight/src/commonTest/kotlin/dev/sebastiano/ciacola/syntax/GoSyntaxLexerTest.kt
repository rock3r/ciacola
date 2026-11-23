package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.GoSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import dev.sebastiano.ciacola.syntax.oracle.goOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class GoSyntaxLexerTest {

    @Test
    fun classifiesKeywordsPredeclaredTypesBuiltinsReceiversLiteralsAndOperators() {
        val code = """
            package main

            import "fmt"

            type Server struct {
                Port int
                Name string
            }

            // Serve handles incoming requests.
            func (s *Server) Serve(ctx any, ch chan<- int) (int, error) {
                /* block comment */
                raw := `multi\nline`
                str := "hello\n"
                r := '\u1234'
                n := 0b1010_0101 + 0o755 + 0755 + 0xFF + 0x1.fp+2 + 3.14i + 2i + 1_000_000
                var ok bool = true || false
                items := make([]string, 0, len(raw))
                items = append(items, fmt.Sprint(s.Port, iota, nil, str, r, n, ok))
                go func() { <-ch }()
                return cap(items), nil
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(GoSyntaxLexer, code, "go-features")

        fun firstKind(text: String): SyntaxTokenKind =
            tokens.first { it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Keyword, firstKind("package"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("import"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("type"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Server"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("struct"))
        assertEquals(SyntaxTokenKind.Property, firstKind("Port"))
        assertEquals(SyntaxTokenKind.Type, firstKind("int"))
        assertEquals(SyntaxTokenKind.Property, firstKind("Name"))
        assertEquals(SyntaxTokenKind.Type, firstKind("string"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("// Serve handles incoming requests."))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("func"))
        assertEquals(SyntaxTokenKind.Function, firstKind("Serve"))
        assertEquals(SyntaxTokenKind.Type, firstKind("any"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("chan"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("<-"))
        assertEquals(SyntaxTokenKind.Type, firstKind("error"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/* block comment */"))
        assertEquals(SyntaxTokenKind.Operator, firstKind(":="))
        assertEquals(SyntaxTokenKind.String, firstKind("`multi\\nline`"))
        assertEquals(SyntaxTokenKind.String, firstKind("\"hello\\n\""))
        assertEquals(SyntaxTokenKind.String, firstKind("'\\u1234'"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1010_0101"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0o755"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0755"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0x1.fp+2"))
        assertEquals(SyntaxTokenKind.Number, firstKind("3.14i"))
        assertEquals(SyntaxTokenKind.Number, firstKind("2i"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1_000_000"))
        assertEquals(SyntaxTokenKind.Type, firstKind("bool"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("true"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("false"))
        assertEquals(SyntaxTokenKind.Function, firstKind("make"))
        assertEquals(SyntaxTokenKind.Function, firstKind("len"))
        assertEquals(SyntaxTokenKind.Function, firstKind("append"))
        assertEquals(SyntaxTokenKind.Function, firstKind("Sprint"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("iota"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("nil"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("go"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("return"))
        assertEquals(SyntaxTokenKind.Function, firstKind("cap"))
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            package p
            func (s *Server) Serve() int {
                raw := `backtick`
                return len(raw) + s.Port
            }
        """.trimIndent()
        assertPrefixTruncationSafety(GoSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(GoSyntaxLexer, seed = 0x474F4C41L, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val longRaw = "`" + "line\n".repeat(6_000) + "`"
        val unclosedComment = "/* " + "abc ".repeat(7_000)
        val manyFuncs = buildString {
            repeat(1_000) { idx ->
                append("func f").append(idx).append("(x int) int { return x + ").append(idx).append(" }\n")
            }
        }

        assertLinearPerformance(
            lexer = GoSyntaxLexer,
            pathologicalInputs = listOf(
                "long-raw-string" to longRaw,
                "unclosed-block-comment" to unclosedComment,
                "many-functions" to manyFuncs,
            ),
        )
    }

    @Test
    fun matchesShikiAndPygmentsOracles() {
        assertAllOracleFixtures(goOracleFixtures)
    }
}
