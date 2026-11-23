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
    fun handlesGenericsConstraintsStructTagsImaginaryAndChannelDirections() {
        val code = """
            package edge

            type Number interface {
                ~int | ~string
            }

            type Item struct {
                ID string `json:"id,omitempty"`
            }

            func Map[T any, U comparable](s []T, fn func(T) U, rest ...T) []U {
                out := make([]U, 0, len(s))
                var inCh <-chan int
                var outCh chan<- int
                x := 0i + 0x1.fp2i + 1_000i + 0x1.p-2
                r1 := '\U0010FFFF'
                r2 := '\377'
                _, _, _, _, _, _ = inCh, outCh, x, r1, r2, rest
            outer:
                for _, v := range s {
                    if len(out) > max(1, min(2, 3)) {
                        break outer
                    }
                    out = append(out, fn(v))
                }
                clear(out)
                return out
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(GoSyntaxLexer, code, "go-edge")
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "Map" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "comparable" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "~" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "ID" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "`json:\"id,omitempty\"`" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "..." }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0i" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0x1.fp2i" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "1_000i" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0x1.p-2" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "'\\U0010FFFF'" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "'\\377'" }.kind)
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "max" }.kind)
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "min" }.kind)
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "clear" }.kind)

        // All generic `T` and `U` occurrences (including `fn func(T) U`) must be `Type`, never `Function`
        val tTokens = tokens.filter { it.slice(code) == "T" }
        val uTokens = tokens.filter { it.slice(code) == "U" }
        assertEquals(4, tTokens.size)
        assertEquals(4, uTokens.size)
        for (tok in tTokens + uTokens) {
            assertEquals(SyntaxTokenKind.Type, tok.kind, "Expected Type for ${tok.slice(code)} at ${tok.startIndex}")
        }
    }

    @Test
    fun supportsBmpAndSupplementaryUnicodeIdentifiers() {
        val code = "package main\nfunc привет(α float64, café string, e\u0301 int, 𠮷 int, 𝐀 bool) {}"
        val tokens = assertLosslessInvariants(GoSyntaxLexer, code, "go-unicode")
        assertEquals(0, tokens.count { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "привет" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "α" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "café" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "e\u0301" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "𠮷" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "𝐀" }.kind)
    }

    @Test
    fun distinguishesTopLevelFuncTypesFromMethodReceiversAndHandlesBom() {
        val handlerCode = "type HandlerFunc func(w Writer, r *Request)\n\nfunc (f HandlerFunc) Serve() {}\n\ntype Mapper func(int) string"
        val handlerTokens = assertLosslessInvariants(GoSyntaxLexer, handlerCode, "go-func-type-vs-receiver")
        assertEquals(SyntaxTokenKind.Keyword, handlerTokens.first { it.slice(handlerCode) == "func" }.kind)
        assertEquals(SyntaxTokenKind.Function, handlerTokens.first { it.slice(handlerCode) == "Serve" }.kind)
        assertEquals(SyntaxTokenKind.Type, handlerTokens.first { it.slice(handlerCode) == "string" }.kind)

        val callbackCode = "type Callback func(code int)\n\nvar registry = 1"
        val callbackTokens = assertLosslessInvariants(GoSyntaxLexer, callbackCode, "go-callback-var")
        assertEquals(SyntaxTokenKind.Keyword, callbackTokens.first { it.slice(callbackCode) == "var" }.kind)

        val bomCode = "\uFEFFpackage main\n"
        val bomTokens = assertLosslessInvariants(GoSyntaxLexer, bomCode, "go-bom")
        assertEquals(SyntaxTokenKind.Whitespace, bomTokens.first { it.slice(bomCode) == "\uFEFF" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, bomTokens.first { it.slice(bomCode) == "package" }.kind)
    }

    @Test
    fun matchesShikiAndPygmentsOracles() {
        assertAllOracleFixtures(goOracleFixtures)
    }
}
