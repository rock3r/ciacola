package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.KotlinSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.OracleFixture
import dev.sebastiano.ciacola.syntax.oracle.OracleTokenExpectation
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinSyntaxLexerTest {

    @Test
    fun classifiesDeclarationsModifiersAnnotationsTemplatesAndComments() {
        val code = """
            @file:JvmName("Demo")
            package dev.sebastiano.demo

            import androidx.compose.ui.Modifier

            @OptIn(ExperimentalApi::class)
            data class User(
                val name: String = "Ada\n",
                val active: Boolean = true,
            ) : Entity() {
                private suspend inline fun <reified T : Any> greet(
                    data: Int,
                    label: String = "Hi ${'$'}name, ${'$'}{data + 1}",
                ) {
                    val x = data
                    /* outer /* nested */ comment */
                    val hex = 0xFF_00
                    val bin = 0b1010_0101u
                    val dbl = 3.14e-2
                    val ch = 'a'
                    if (name !in listOf("a", "b") && this != null) {
                        println(text = x.toString())
                        loop@ for (i in 0..<2) {
                            return@loop
                        }
                    }
                }
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(KotlinSyntaxLexer, code, "kotlin-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Annotation, firstKind("file"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("JvmName"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("OptIn"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("package"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("import"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("androidx"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Modifier"))

        assertEquals(SyntaxTokenKind.Keyword, firstKind("data") { code.indexOf("data class") <= it.startIndex && it.startIndex < code.indexOf("class User") })
        assertEquals(SyntaxTokenKind.Keyword, firstKind("class") { it.startIndex > code.indexOf("data class") - 1 })
        assertEquals(SyntaxTokenKind.Type, firstKind("User"))
        assertEquals(SyntaxTokenKind.Type, firstKind("String"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("\\n"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("true"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Entity"))

        assertEquals(SyntaxTokenKind.Keyword, firstKind("private"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("suspend"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("inline"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("fun"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("reified"))
        assertEquals(SyntaxTokenKind.Type, firstKind("T"))
        assertEquals(SyntaxTokenKind.Function, firstKind("greet"))

        // Soft keyword `data` used as identifier must stay Plain
        assertEquals(SyntaxTokenKind.Plain, firstKind("data") { it.startIndex > code.indexOf("greet(") })

        assertEquals(SyntaxTokenKind.Comment, firstKind("/* outer /* nested */ comment */"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_00"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1010_0101u"))
        assertEquals(SyntaxTokenKind.Number, firstKind("3.14e-2"))
        assertEquals(SyntaxTokenKind.String, firstKind("'a'"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("this"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("null"))
        assertEquals(SyntaxTokenKind.Function, firstKind("println"))
        assertEquals(SyntaxTokenKind.Property, firstKind("text"))
        assertEquals(SyntaxTokenKind.Function, firstKind("toString"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("loop"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("return"))
    }

    @Test
    fun handlesRawStringsShebangAndCrlf() {
        val code = "#!/usr/bin/env kotlin\r\nval raw = \"\"\"\r\n  line 1\r\n  ${'$'}value\r\n\"\"\"\r\n"
        val tokens = assertLosslessInvariants(KotlinSyntaxLexer, code, "kotlin-raw-crlf")
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code).startsWith("#!") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).contains("line 1") }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "$" }.kind)
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            @Composable
            fun Greeting(name: String = "World") {
                /* nested /* comment */ */
                val raw = ""${'"'}hello ${'$'}{name.uppercase()}""${'"'}
                println(raw)
            }
        """.trimIndent()
        assertPrefixTruncationSafety(KotlinSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(KotlinSyntaxLexer, seed = 0x4B4F544CL, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val manyStatements = buildString {
            repeat(1_200) { idx ->
                append("val item").append(idx).append(" = \"text_").append(idx).append("\" + ").append(idx).append('\n')
            }
        }
        val nestedComments = "/* ".repeat(500) + "inner" + " */".repeat(500)
        val unclosedNestedComments = "/* ".repeat(2_000) + "unterminated"
        val longRawString = "\"\"\"" + "a".repeat(25_000) + "\"\"\""

        assertLinearPerformance(
            lexer = KotlinSyntaxLexer,
            pathologicalInputs = listOf(
                "many-statements" to manyStatements,
                "nested-comments" to nestedComments,
                "unclosed-nested-comments" to unclosedNestedComments,
                "long-raw-string" to longRawString,
            ),
        )
    }

    @Test
    fun oracleSupportSmokeTest() {
        val code = "val count: Int = 42"
        val fixture = OracleFixture(
            name = "kotlin-smoke",
            language = SyntaxLanguage.Kotlin,
            code = code,
            expectations = listOf(
                OracleTokenExpectation("val", 0, 3, SyntaxTokenKind.Keyword, SyntaxTokenKind.Keyword, note = "STRICT: keyword"),
                OracleTokenExpectation("count", 4, 9, SyntaxTokenKind.Plain, SyntaxTokenKind.Plain),
                OracleTokenExpectation("Int", 11, 14, SyntaxTokenKind.Type, SyntaxTokenKind.Type, note = "STRICT: builtin type"),
                OracleTokenExpectation("42", 17, 19, SyntaxTokenKind.Number, SyntaxTokenKind.Number, note = "STRICT: integer"),
            ),
        )
        assertMatchesOracleFixture(fixture)
    }

    @Test
    fun handlesMultiDollarInterpolationBackticksContextParamsAndModifiers() {
        val code = """
            context(logger: Logger)
            suspend inline fun <reified T> run(
                noinline f: () -> Unit,
                crossinline g: () -> Unit,
            ) {
                val `when` = 1
                val `in space` = 2
                val sealed = 3
                val price = ${'$'}${'$'}"price ${'$'}100, ${'$'}${'$'}sealed, ${'$'}${'$'}{`when` + 1}"
                val rawMulti = ${'$'}${'$'}${'$'}""${'"'}raw ${'$'}${'$'}keep ${'$'}${'$'}${'$'}price""${'"'}
                val nested = "${'$'}{"inner ${'$'}{sealed + 1}"}"
                val uHex = 0xFF_00u
                val uLong = 42UL
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(KotlinSyntaxLexer, code, "kotlin-backfill")
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "context" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "noinline" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "crossinline" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "`when`" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "`in space`" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "sealed" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "$$" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).startsWith("100") }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, tokens.first { it.slice(code) == "$${'$'}{" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).contains("keep") }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "$$$" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0xFF_00u" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "42UL" }.kind)
    }

    @Test
    fun comparisonAngleBracketDoesNotLeakIntoSubsequentOutOrSingleCapIdentifiers() {
        val code = """
            fun check(n: Int, out: Int): Int {
                if (n < 0) return 0
                val res = out * 2
                val X = 1
                return res + X
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(KotlinSyntaxLexer, code, "kotlin-angle-reset")
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "out" && it.startIndex > code.indexOf("val res") }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "X" }.kind)
    }

    @Test
    fun supportsThisAtInParensVisibilitySettersSmpIdentifiersAndLeadingBom() {
        val code = "\uFEFFclass A {\n    var n = 0\n        private set\n    val 𠮷 = 1\n    fun f() {\n        g(this@A)\n    }\n}\n"
        val tokens = assertLosslessInvariants(KotlinSyntaxLexer, code, "kotlin-this-at-setter-bom")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Whitespace, tokens.first().kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "private" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "set" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "𠮷" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "this" }.kind)
        assertEquals(SyntaxTokenKind.Annotation, tokens.first { it.slice(code) == "@A" }.kind)
    }

    @Test
    fun recognizesBmpCjkIdeographAndHangulIdentifiers() {
        val code = "val 名前 = 1\nval 변수 = 2\n"
        val tokens = assertLosslessInvariants(KotlinSyntaxLexer, code, "kotlin-cjk-hangul-idents")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "名前" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "변수" }.kind)
    }
}
