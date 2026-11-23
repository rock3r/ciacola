package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.PythonSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.PythonOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PythonSyntaxLexerTest {

    @Test
    fun classifiesStringsFStringsDecoratorsKeywordsBuiltinsNumbersAndAccess() {
        val code = """
            from __future__ import annotations
            import functools
            from dataclasses import dataclass

            # Comment line
            @dataclass
            @functools.lru_cache(maxsize=128)
            class Worker:
                title: str = "demo"
                raw_win: str = r"foo\\"
                raw_sq: str = R'bar\'
                raw_bytes: bytes = rb"bin\n"
                uni: str = u'''multi
                line'''
                count: int = 1_000_000
                hex_v: int = 0xFF_00
                bin_v: int = 0b1010
                oct_v: int = 0o755
                f1: float = .5
                f2: float = 1.
                f3: float = 1e-5
                c1: complex = 3.5j
                c2: complex = 2J

                async def run(self, items: dict[str, int], mat: object) -> list[int]:
                    a = mat @ mat
                    walrus_val = (n := len(items))
                    msg = f"Hello {self.title!r:>10}, total={1 + 2}, val={items['key']}, {{esc}}"
                    multi = f""${'"'}line {f"{n}"} end""${'"'}
                    raw_f = rf"path\n{n}"
                    if n >= 1 and n != 0 and True and not False and None is not Ellipsis:
                        print(list(enumerate(zip(range(n)))))
                        return [x for x in items.values() if isinstance(x, int)]
                    raise Exception(NotImplemented)

                @classmethod
                def make(cls) -> Worker:
                    return super().make()

            type Point = tuple[float, float]
        """.trimIndent()

        val tokens = assertLosslessInvariants(PythonSyntaxLexer, code, "python-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Keyword, firstKind("from"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("import"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("# Comment line"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("dataclass") { it.startIndex > code.indexOf("@dataclass") })
        assertEquals(SyntaxTokenKind.Annotation, firstKind("functools") { it.startIndex > code.indexOf("@functools") - 1 })
        assertEquals(SyntaxTokenKind.Annotation, firstKind("lru_cache"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("class"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Worker"))
        assertEquals(SyntaxTokenKind.Type, firstKind("str"))
        assertEquals(SyntaxTokenKind.String, firstKind("r\"foo\\\\\""))
        assertEquals(SyntaxTokenKind.String, firstKind("rb\"bin\\n\""))
        assertEquals(SyntaxTokenKind.Number, firstKind("1_000_000"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_00"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1010"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0o755"))
        assertEquals(SyntaxTokenKind.Number, firstKind(".5"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1."))
        assertEquals(SyntaxTokenKind.Number, firstKind("1e-5"))
        assertEquals(SyntaxTokenKind.Number, firstKind("3.5j"))
        assertEquals(SyntaxTokenKind.Number, firstKind("2J"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("async"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("def"))
        assertEquals(SyntaxTokenKind.Function, firstKind("run"))
        assertEquals(SyntaxTokenKind.Type, firstKind("dict"))
        assertEquals(SyntaxTokenKind.Type, firstKind("object"))
        assertEquals(SyntaxTokenKind.Type, firstKind("list"))

        // Matrix multiplication `@` must be Operator, not Annotation
        assertEquals(
            SyntaxTokenKind.Operator,
            firstKind("@") { it.startIndex > code.indexOf("a = mat") },
        )
        assertEquals(SyntaxTokenKind.Operator, firstKind(":="))
        assertEquals(SyntaxTokenKind.Operator, firstKind("->"))
        assertEquals(SyntaxTokenKind.Function, firstKind("len"))
        assertEquals(SyntaxTokenKind.Property, firstKind("title") { it.startIndex > code.indexOf("self.title") })
        assertEquals(SyntaxTokenKind.String, firstKind("'key'"))
        assertEquals(SyntaxTokenKind.String, firstKind("{{"))
        assertEquals(SyntaxTokenKind.String, firstKind("}}"))
        assertEquals(SyntaxTokenKind.String, firstKind("!"))
        assertEquals(SyntaxTokenKind.String, firstKind(">10"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("True"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("False"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("None"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("Ellipsis"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("NotImplemented"))
        assertEquals(SyntaxTokenKind.Function, firstKind("enumerate"))
        assertEquals(SyntaxTokenKind.Function, firstKind("zip"))
        assertEquals(SyntaxTokenKind.Function, firstKind("range"))
        assertEquals(SyntaxTokenKind.Function, firstKind("isinstance"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Exception"))
        assertEquals(SyntaxTokenKind.Function, firstKind("super"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("type") { it.startIndex > code.indexOf("return super") })
        assertEquals(SyntaxTokenKind.Type, firstKind("Point"))
        assertEquals(SyntaxTokenKind.Type, firstKind("tuple"))
    }

    @Test
    fun classifiesSoftKeywordsContextuallyOnly() {
        val code = """
            type = 1
            match = re.match(r"x", "x")
            match.group()
            case = "plain"
            match: int = 2

            match value:
                case {"k": v} if v > 0:
                    pass
                case _:
                    pass

            type Alias[T] = list[T]
        """.trimIndent()

        val tokens = assertLosslessInvariants(PythonSyntaxLexer, code, "python-soft-keywords")

        // Shadowed variable/method usages must NOT be Keyword
        val assignType = tokens.first { it.slice(code) == "type" }
        assertEquals(SyntaxTokenKind.Plain, assignType.kind)

        val assignMatch = tokens.first { it.slice(code) == "match" }
        assertEquals(SyntaxTokenKind.Plain, assignMatch.kind)

        val callMatch = tokens.first { it.slice(code) == "match" && it.startIndex > code.indexOf("re.") }
        assertEquals(SyntaxTokenKind.Function, callMatch.kind)

        val attrMatch = tokens.first { it.slice(code) == "match" && it.startIndex > code.indexOf("match.group") - 1 }
        assertEquals(SyntaxTokenKind.Plain, attrMatch.kind)

        val assignCase = tokens.first { it.slice(code) == "case" }
        assertEquals(SyntaxTokenKind.Plain, assignCase.kind)

        val annotatedMatch = tokens.first { it.slice(code) == "match" && it.startIndex > code.indexOf("match: int") - 1 }
        assertEquals(SyntaxTokenKind.Plain, annotatedMatch.kind)

        // Statement-leading match / case / type MUST be Keyword
        val stmtMatch = tokens.first { it.slice(code) == "match" && it.startIndex >= code.indexOf("match value:") }
        assertEquals(SyntaxTokenKind.Keyword, stmtMatch.kind)

        val stmtCases = tokens.filter { it.slice(code) == "case" && it.startIndex > code.indexOf("match value:") }
        assertEquals(2, stmtCases.size)
        assertTrue(stmtCases.all { it.kind == SyntaxTokenKind.Keyword })

        val stmtType = tokens.first { it.slice(code) == "type" && it.startIndex >= code.indexOf("type Alias") }
        assertEquals(SyntaxTokenKind.Keyword, stmtType.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "Alias" }.kind)
    }

    @Test
    fun handlesRawStringsAndUnterminatedFStringsCleanly() {
        val tricky = listOf(
            "r\"foo\\\\\"",
            "r'bar\\'",
            "f\"unclosed {x + 1",
            "f\"unclosed {x:>10\ny = 2",
            "f\"\"\"unclosed {a\nb = 3",
            "\"\"\"triple with \"\" inside\"\"\"",
            "b\"\"\"bytes unclosed",
        )
        for ((idx, sample) in tricky.withIndex()) {
            assertLosslessInvariants(PythonSyntaxLexer, sample, "python-tricky-$idx")
        }
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            @dataclass
            def greet(name: str) -> str:
                return f"Hi {name!r:>8}, {{ok}} { {'a': 1} }"
        """.trimIndent()
        assertPrefixTruncationSafety(PythonSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(PythonSyntaxLexer, seed = 0x50595448L, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val manyFStrings = buildString {
            repeat(1_000) { idx ->
                append("x_").append(idx).append(" = f\"val={").append(idx).append(" + 1:>4}\"\n")
            }
        }
        val deepNestedFString = "f\"" + "{f\"".repeat(80) + "x" + "\"}".repeat(80) + "\""
        val longRawString = "r\"" + "a\\\\".repeat(8_000) + "\""
        val unclosedTriple = "\"\"\"" + "abc\"\"def".repeat(3_500)

        assertLinearPerformance(
            lexer = PythonSyntaxLexer,
            pathologicalInputs = listOf(
                "many-fstrings" to manyFStrings,
                "deep-nested-fstring" to deepNestedFString,
                "long-raw-string" to longRawString,
                "unclosed-triple-string" to unclosedTriple,
            ),
        )
    }

    @Test
    fun matchesDualOracleFixture() {
        assertMatchesOracleFixture(PythonOracleFixtures.fixture)
    }

    @Test
    fun handlesAllStringPrefixQuoteCombosPep695GenericsAndPep701EdgeCases() {
        val prefixes = listOf("", "r", "R", "b", "B", "u", "U", "f", "F", "rb", "rB", "Rb", "RB", "br", "bR", "Br", "BR", "rf", "rF", "Rf", "RF", "fr", "fR", "Fr", "FR")
        val quotes = listOf("'", "\"", "'''", "\"\"\"")
        val allStrings = buildString {
            for ((idx, prefix) in prefixes.withIndex()) {
                for (q in quotes) {
                    append("s_").append(idx).append(" = ").append(prefix).append(q).append("hi").append(q).append('\n')
                }
            }
        }
        val allStringTokens = assertLosslessInvariants(PythonSyntaxLexer, allStrings, "python-all-prefix-quotes")
        assertTrue(allStringTokens.none { it.kind == SyntaxTokenKind.Invalid })

        val pepCode = """
            def f[T: int](x: T | str | None = ...) -> list[T]:
                y = x + \
                    1
                yield from [y]

            class Box[K, V]:
                def get(self, d: dict[K, V]) -> str:
                    return f"{d["key"]=!r:>8}, {f"{d['nested']}"}"
        """.trimIndent()
        val tokens = assertLosslessInvariants(PythonSyntaxLexer, pepCode, "python-pep695-pep701")
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(pepCode) == "f" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(pepCode) == "T" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(pepCode) == "K" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(pepCode) == "V" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(pepCode) == "yield" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(pepCode) == "from" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(pepCode) == "\"key\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(pepCode) == "'nested'" }.kind)
    }

    @Test
    fun handlesFStringLambdaUnclosedNewlineRecoveryAndUnicodeSmpIdentifiers() {
        val lambdaCode = """s = f"{x if cond else lambda y: y + 1}""""
        val lambdaTokens = assertLosslessInvariants(PythonSyntaxLexer, lambdaCode, "python-fstr-lambda")
        assertEquals(SyntaxTokenKind.Keyword, lambdaTokens.first { it.slice(lambdaCode) == "lambda" }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, lambdaTokens.first { it.slice(lambdaCode) == ":" }.kind)
        assertEquals(SyntaxTokenKind.Operator, lambdaTokens.first { it.slice(lambdaCode) == "+" }.kind)
        assertEquals(SyntaxTokenKind.Number, lambdaTokens.first { it.slice(lambdaCode) == "1" }.kind)

        val unclosedSingle = "x = f\"hello {name\nif ok:\n    return 1"
        val singleTokens = assertLosslessInvariants(PythonSyntaxLexer, unclosedSingle, "python-fstr-unclosed-nl")
        assertEquals(SyntaxTokenKind.Keyword, singleTokens.first { it.slice(unclosedSingle) == "if" }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, singleTokens.first { it.slice(unclosedSingle) == ":" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, singleTokens.first { it.slice(unclosedSingle) == "return" }.kind)
        assertEquals(SyntaxTokenKind.Number, singleTokens.first { it.slice(unclosedSingle) == "1" }.kind)

        val unclosedTriple = "x = f\"\"\"hello {name\nif ok:\n    return 1\"\"\""
        val tripleTokens = assertLosslessInvariants(PythonSyntaxLexer, unclosedTriple, "python-fstr-triple-stmt")
        assertEquals(SyntaxTokenKind.Keyword, tripleTokens.first { it.slice(unclosedTriple) == "if" }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, tripleTokens.first { it.slice(unclosedTriple) == ":" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tripleTokens.first { it.slice(unclosedTriple) == "return" }.kind)

        val unicodeCode = "café = 1\nΔt = 0.5\n𠮷 = 2\n𝐀 = café + 𠮷\n"
        val unicodeTokens = assertLosslessInvariants(PythonSyntaxLexer, unicodeCode, "python-unicode-smp")
        assertTrue(unicodeTokens.none { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Plain, unicodeTokens.first { it.slice(unicodeCode) == "café" }.kind)
        assertEquals(SyntaxTokenKind.Plain, unicodeTokens.first { it.slice(unicodeCode) == "Δt" }.kind)
        assertEquals(SyntaxTokenKind.Plain, unicodeTokens.first { it.slice(unicodeCode) == "𠮷" }.kind)
        assertEquals(SyntaxTokenKind.Plain, unicodeTokens.first { it.slice(unicodeCode) == "𝐀" }.kind)
    }
}
