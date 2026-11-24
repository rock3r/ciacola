package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.GroovySyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.GroovyOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroovySyntaxLexerTest {

    @Test
    fun classifiesShebangCommentsKeywordsTypesAnnotationsAndNumbers() {
        val code = """
            #!/usr/bin/env groovy
            package dev.example.demo

            import groovy.transform.CompileStatic
            import groovy.transform.Immutable

            /**
             * Groovydoc comment.
             */
            @CompileStatic
            @Immutable
            @Grab('com.example:lib:1.0')
            @SuppressWarnings('Unchecked')
            @groovy.transform.ToString(includeNames = true)
            public sealed class Artifact permits SignedArtifact {
                // Line comment
                /* Block comment */
                private static final long HEX = 0xFF_00L
                private static final int BIN = 0b1010_0101I
                private static final int OCT = 0755
                private static final BigInteger BIG_INT = 1_000_000G
                private static final BigDecimal BIG_DEC = 1.5e-3G
                private static final double HEX_FLT = 0x1.0p-4d
                private static final float FLT = 2.5f

                @Override
                String toString() {
                    var count = 0
                    def range = 1..10
                    def half = 1..<5
                    return "ok"
                }
            }

            non-sealed class SignedArtifact extends Artifact {}
            trait Named { String name }
            record Point(int x, int y) {}
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(GroovySyntaxLexer, code, "groovy-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Comment, firstKind("#!/usr/bin/env groovy"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("// Line comment"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/* Block comment */"))
        assertTrue(tokens.any { it.slice(code).contains("Groovydoc comment.") && it.kind == SyntaxTokenKind.Comment })

        assertEquals(SyntaxTokenKind.Keyword, firstKind("package"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("import"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("CompileStatic") { it.startIndex > code.indexOf("@CompileStatic") })
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Immutable") { it.startIndex > code.indexOf("@Immutable") })
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Grab"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("SuppressWarnings"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("ToString"))

        assertEquals(SyntaxTokenKind.Keyword, firstKind("public"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("sealed"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("class"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Artifact"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("permits"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("non-sealed"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("trait"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Named"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("record"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Point"))

        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_00L"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1010_0101I"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0755"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1_000_000G"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1.5e-3G"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0x1.0p-4d"))
        assertEquals(SyntaxTokenKind.Number, firstKind("2.5f"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1"))
        assertEquals(SyntaxTokenKind.Operator, firstKind(".."))
        assertEquals(SyntaxTokenKind.Operator, firstKind("..<"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("var"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("def"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("true"))
    }

    @Test
    fun handlesAllSixStringFormsAndNestedGStringInterpolations() {
        val code = """
            def sq = 'literal ${'$'}notInterp \'escaped\''
            def tsq = '''multi
            line ${'$'}notInterp
            end'''
            def dq = "hi ${'$'}user.profile.name, tail=${'$'}user., esc=\${'$'}lit, expr=${'$'}{items.findAll { it > 0 }.size() + "in:${'$'}{1 + 2}"}"
            def tdq = ""${'"'}
                triple ${'$'}name "inner" ""two""
                ${'$'}{ [a: 1, b: 2].collect { k, v -> "${'$'}k=${'$'}v" } }
            ""${'"'}
            def slashy = ~/^[a-z]+\/\d+${'$'}/
            def slashyInterp = /prefix\/${'$'}seg.part\/${'$'}{1 + 2}/
            def slashyMultiline = /line1
            line2/
            def dslashy = ${'$'}/
                dollar slashy: ${'$'}${'$'} literal, ${'$'}/ slash, ${'$'}user.name, ${'$'}{10 / 2}
                backslash \ stays literal
            /${'$'}
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(GroovySyntaxLexer, code, "groovy-strings")

        // Single & triple-single strings must NOT interpolate $notInterp
        assertFalse(tokens.any { it.slice(code) == "notInterp" })
        assertTrue(tokens.any { it.slice(code).startsWith("'literal") && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code).startsWith("'''multi") && it.kind == SyntaxTokenKind.String })

        // Dotted property chain `$user.profile.name` inside GString
        val dqStart = code.indexOf("def dq =")
        assertTrue(tokens.any { it.slice(code) == "user" && it.startIndex > dqStart && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "profile" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "name" && it.kind == SyntaxTokenKind.Property })
        // Trailing dot in `$user.` stays String text
        assertTrue(tokens.any { it.slice(code).startsWith("., esc=") && it.kind == SyntaxTokenKind.String })

        // Nested closure and nested GString inside ${...}
        assertTrue(tokens.any { it.slice(code) == "findAll" && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "size" && it.kind == SyntaxTokenKind.Function })

        // Slashy string interpolation and escapes
        val slashyStart = code.indexOf("def slashyInterp =")
        assertTrue(tokens.any { it.slice(code) == "\\/" && it.startIndex > slashyStart && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "seg" && it.startIndex > slashyStart && it.kind == SyntaxTokenKind.Property })
        assertTrue(tokens.any { it.slice(code) == "part" && it.startIndex > slashyStart && it.kind == SyntaxTokenKind.Property })

        // Dollar-slashy escapes & division inside ${10 / 2}
        val dsStart = code.indexOf("def dslashy =")
        assertTrue(tokens.any { it.slice(code) == "$$" && it.startIndex > dsStart && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "$/" && it.startIndex > dsStart && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "/$" && it.startIndex > dsStart && it.kind == SyntaxTokenKind.String })
        val interpDiv = code.indexOf("10 / 2")
        assertTrue(tokens.any { it.slice(code) == "/" && it.startIndex in interpDiv..(interpDiv + 6) && it.kind == SyntaxTokenKind.Operator })
    }

    @Test
    fun disambiguatesSlashyStringsFromDivisionAndComments() {
        val code = """
            def a = 10 / 2 / 5
            def b = (100 / 4) / (2 / 1)
            a /= 2
            def c = foo() / 3
            if (a > 0) /cond_slashy/
            def rx = ~/=leading_eq/
            def div = 10 // not slashy
            /* block comment */
            def d = 8 /* inline */ / 2
            def e = (
                20
                / 4
            )
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(GroovySyntaxLexer, code, "groovy-slashy-vs-div")

        val line1End = code.indexOf('\n')
        val line1Slashes = tokens.filter { it.startIndex < line1End && it.slice(code) == "/" }
        assertEquals(2, line1Slashes.size)
        assertTrue(line1Slashes.all { it.kind == SyntaxTokenKind.Operator })

        assertTrue(tokens.any { it.slice(code) == "/=" && it.kind == SyntaxTokenKind.Operator })
        assertTrue(tokens.any { it.slice(code) == "cond_slashy" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "=leading_eq" && it.kind == SyntaxTokenKind.String })
        assertTrue(tokens.any { it.slice(code) == "// not slashy" && it.kind == SyntaxTokenKind.Comment })
        assertTrue(tokens.any { it.slice(code) == "/* inline */" && it.kind == SyntaxTokenKind.Comment })
        val inlineDivPos = code.indexOf("/ 2")
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.startIndex == inlineDivPos }.kind)
        val parenDivPos = code.indexOf("/ 4")
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.startIndex == parenDivPos }.kind)
    }

    @Test
    fun classifiesAllGroovyOperatorsContextualKeywordsAndGradleDsl() {
        val code = """
            plugins {
                id 'java'
                id 'org.jetbrains.kotlin.jvm' version '2.0.0'
            }

            dependencies {
                implementation 'com.example:core:1.0.0'
                testImplementation group: 'junit', name: 'junit', version: '4.13.2'
            }

            tasks.register('checkAll') {
                doLast {
                    def x = 1 <=> 2
                    def y = null ?: 'def'
                    y ?= 'keep'
                    def z = obj?.prop
                    def el = list?[0]
                    def names = users*.name
                    def fn = String.&valueOf
                    def fld = box.@secret
                    def r1 = 'abc' =~ /[a-z]+/
                    def r2 = 'abc' ==~ /[a-z]+/
                    def ranges = [1..5, 1..<5, 1<..5, 1<..<5]
                    def p = 2 ** 3
                    p **= 2
                    def id = (a === b) || (a !== c)
                    def mem = (1 in [1, 2]) && (3 !in [1, 2]) && (x !instanceof String)
                    def map = ['quoted-key': 1, plainKey: 2]
                    def cl = { item -> println item }
                }
            }

            class Shadowed {
                int trait = 1
                String var = "v"
                int record = 2
                int non = 10, sealed = 3
                int diff = non - sealed
                void demo() {
                    this.trait()
                    var local = trait + record
                }
                void trait() {}
            }
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(GroovySyntaxLexer, code, "groovy-ops-gradle")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        for (op in listOf("<=>", "?:", "?=", "?.", "*.", ".&", ".@", "=~", "==~", "..", "..<", "<..", "<..<", "**", "**=", "===", "!==", "->")) {
            assertEquals(SyntaxTokenKind.Operator, firstKind(op), "Expected operator '$op' to be Operator")
        }
        assertEquals(SyntaxTokenKind.Keyword, firstKind("in"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("instanceof"))
        assertEquals(SyntaxTokenKind.Property, firstKind("group"))
        assertEquals(SyntaxTokenKind.Property, firstKind("version") { it.startIndex > code.indexOf("testImplementation") })
        assertEquals(SyntaxTokenKind.Property, firstKind("plainKey"))
        assertEquals(SyntaxTokenKind.String, firstKind("'quoted-key'"))
        assertEquals(SyntaxTokenKind.Property, firstKind("prop"))
        assertEquals(SyntaxTokenKind.Property, firstKind("name") { it.startIndex > code.indexOf("users*.") })
        assertEquals(SyntaxTokenKind.Function, firstKind("valueOf"))
        assertEquals(SyntaxTokenKind.Property, firstKind("secret"))
        assertEquals(SyntaxTokenKind.Function, firstKind("register"))
        assertEquals(SyntaxTokenKind.Function, firstKind("println"))

        val shadowStart = code.indexOf("class Shadowed")
        assertEquals(SyntaxTokenKind.Plain, firstKind("trait") { it.startIndex > shadowStart })
        assertEquals(SyntaxTokenKind.Plain, firstKind("var") { it.startIndex > shadowStart })
        assertEquals(SyntaxTokenKind.Plain, firstKind("record") { it.startIndex > shadowStart })
        assertEquals(SyntaxTokenKind.Plain, firstKind("non") { it.startIndex > shadowStart })
        assertEquals(SyntaxTokenKind.Plain, firstKind("sealed") { it.startIndex > shadowStart })
        assertEquals(
            SyntaxTokenKind.Keyword,
            firstKind("var") { it.startIndex > code.indexOf("var local") - 1 },
        )
    }

    @Test
    fun handlesBomCrlfCrUnicodeEmojiAndSurrogatesLosslessly() {
        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val samples = listOf(
            "\uFEFFdef α = \"𠮷 🎉\"\r\n// end 🎉\rdef β = 'café'",
            "def x = \"$loneHigh\" + '${loneLow}a'",
            "def π = 1; // #! not-shebang\n",
        )
        for ((idx, sample) in samples.withIndex()) {
            assertLosslessInvariants(GroovySyntaxLexer, sample, "groovy-unicode#$idx")
        }
        val firstTokens = assertValidSnippetHasNoInvalidTokens(GroovySyntaxLexer, samples.first(), "groovy-bom-crlf")
        assertTrue(firstTokens.any { it.slice(samples.first()) == "α" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(firstTokens.any { it.slice(samples.first()) == "𠮷 🎉" && it.kind == SyntaxTokenKind.String })
        assertTrue(firstTokens.any { it.slice(samples.first()) == "// end 🎉" && it.kind == SyntaxTokenKind.Comment })
        assertFalse(firstTokens.any { it.slice(samples.first()) == "#! not-shebang" })
    }

    @Test
    fun recoversFromUnterminatedConstructsAndMatchesPrefixFuzzAndLinearPerformance() {
        val unterminated = listOf(
            "def a = \"unclosed\ndef b = 1",
            "def a = \"unclosed\\",
            "def a = 'unclosed\ndef b = 1",
            "def a = '''unclosed",
            "def a = \"\"\"unclosed \${x + 1",
            "def a = /unclosed",
            "def a = $/unclosed",
            "/* unclosed comment",
            "#!midline",
            "def a = \"\${nested(\"\"\"inner",
        )
        for ((idx, sample) in unterminated.withIndex()) {
            val tokens = assertLosslessInvariants(GroovySyntaxLexer, sample, "groovy-unterminated#$idx")
            if (idx == 0 || idx == 2) {
                assertTrue(tokens.any { it.slice(sample) == "def" && it.startIndex > sample.indexOf('\n') && it.kind == SyntaxTokenKind.Keyword })
            }
        }

        val prefixSample = """
            #!/usr/bin/env groovy
            @CompileStatic
            def s = "hi ${'$'}a.b ${'$'}{1 + 2}"
            def r = ~/abc\/${'$'}x/
            def d = ${'$'}/multi ${'$'}${'$'} ${'$'}/ /${'$'}
        """.trimIndent()
        assertPrefixTruncationSafety(GroovySyntaxLexer, prefixSample, maxStep = 1)
        assertMalformedAndFuzzSafety(GroovySyntaxLexer, seed = 0x60071AL, iterations = 80)

        assertLinearPerformance(
            GroovySyntaxLexer,
            listOf(
                "unclosed-dquote-10k" to ("def s = \"" + "a".repeat(12_000)),
                "unclosed-tdquote-10k" to ("def s = \"\"\"" + "a".repeat(12_000)),
                "unclosed-slashy-10k" to ("def r = /" + "a".repeat(12_000)),
                "repeated-slashes-10k" to "/".repeat(10_000),
                "repeated-slash-lines-5k" to "/a\n".repeat(5_000),
                "nested-unclosed-slashy-interp-2k" to ("/\${".repeat(2_000) + "a".repeat(2_000)),
                "offset-unclosed-slashy-interp-5k" to "x = /a\${\n".repeat(5_000),
                "nested-closed-slashy-interp-5k" to ("/ \${ ".repeat(5_000) + " }".repeat(5_000)),
                "unclosed-dollar-slashy-10k" to ("def d = $/" + "a".repeat(12_000)),
                "unclosed-block-comment-10k" to ("/* " + "*".repeat(12_000)),
                "chained-division-2k" to ("10" + " / 2".repeat(2_500)),
                "deep-nested-gstring" to ("\"\${".repeat(150) + "x" + "}\"".repeat(150)),
            ),
        )
    }

    @Test
    fun handlesContinuationDivisionGenericDivisionAndModifierChainedTypeHeaders() {
        val code = """
            def ratio = numerator
                / denominator
            def next = "code"

            total
                /= 2
            def afterAssign = "ok"

            def speed = dist as Quantity<Meter> / time / 2

            @CompileStatic
            sealed trait Describable permits BuildTask {
                abstract String describe()
            }
            non-sealed trait OpenTrait {}
            sealed record Pt(int x) {}
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(GroovySyntaxLexer, code, "groovy-regressions")

        val ratioSlashIdx = code.indexOf("/ denominator")
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.startIndex == ratioSlashIdx }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "code" }.kind)

        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "/=" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "ok" }.kind)

        val genericLineStart = code.indexOf("def speed =")
        val genericLineTokens = tokens.filter {
            it.startIndex > genericLineStart &&
                it.startIndex < code.indexOf("@CompileStatic")
        }
        val genericSlashes = genericLineTokens.filter { it.slice(code) == "/" }
        assertEquals(
            2,
            genericSlashes.size,
            "Expected 2 '/' tokens in generic line, got tokens: " +
                genericLineTokens.joinToString(", ") { "${it.kind}('${it.slice(code).replace("\n", "\\n")}')" },
        )
        assertTrue(genericSlashes.all { it.kind == SyntaxTokenKind.Operator })

        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "sealed" }.kind)
        assertEquals(2, tokens.count { it.slice(code) == "trait" && it.kind == SyntaxTokenKind.Keyword })
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "permits" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "Describable" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "BuildTask" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "non-sealed" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "OpenTrait" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "record" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "Pt" }.kind)
    }

    @Test
    fun preservesNestedSlashyStringInsideUnclosedNestedSlashyInterpolations() {
        val code = "x = / \${ y = / \${ z = /b/ } }"
        val tokens = assertValidSnippetHasNoInvalidTokens(GroovySyntaxLexer, code, "groovy-nested-slashy-interp")

        val bToken = tokens.first { it.slice(code) == "b" }
        assertEquals(SyntaxTokenKind.String, bToken.kind)
        val bIndex = tokens.indexOf(bToken)
        assertEquals(SyntaxTokenKind.String, tokens[bIndex - 1].kind)
        assertEquals("/", tokens[bIndex - 1].slice(code))
        assertEquals(SyntaxTokenKind.String, tokens[bIndex + 1].kind)
        assertEquals("/", tokens[bIndex + 1].slice(code))
    }

    @Test
    fun matchesDualOracleFixtures() {
        for (fixture in GroovyOracleFixtures.all) {
            assertMatchesOracleFixture(fixture)
        }
    }
}
