package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.CppSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.cppOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class CppSyntaxLexerTest {

    @Test
    fun classifiesRawStringsConceptsAttributesTemplatesAndOperators() {
        val code = """
            #include <vector>
            #include "header.hpp"
            #define MAX(a, b) \
                ((a) > (b) ? (a) : (b))

            template <typename T>
            concept Addable = requires(T a, T b) { a + b; };

            [[nodiscard]] constexpr auto compute(int n) noexcept -> std::size_t {
                const char* r1 = R"(hello "world")";
                const char* r2 = R"delim(a ) b)delim";
                const char8_t* r3 = u8R"x(ok)x";
                const wchar_t* r4 = LR"(wide)";
                auto hf = 0x1.8p+3f;
                int sep = 1'000'000;
                if (n == 0) return 0uz;
                std::vector<int> v{1, 2};
                return v.size();
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(CppSyntaxLexer, code, "cpp-features")

        fun firstKind(text: String): SyntaxTokenKind =
            tokens.first { it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("include"))
        assertEquals(SyntaxTokenKind.String, firstKind("<vector>"))
        assertEquals(SyntaxTokenKind.String, firstKind("\"header.hpp\""))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("define"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("template"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("typename"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("concept"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("requires"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("nodiscard"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("constexpr"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("auto"))
        assertEquals(SyntaxTokenKind.Function, firstKind("compute"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("noexcept"))
        assertEquals(SyntaxTokenKind.Type, firstKind("size_t"))
        assertEquals(SyntaxTokenKind.String, firstKind("R\"(hello \"world\")\""))
        assertEquals(SyntaxTokenKind.String, firstKind("R\"delim(a ) b)delim\""))
        assertEquals(SyntaxTokenKind.String, firstKind("u8R\"x(ok)x\""))
        assertEquals(SyntaxTokenKind.String, firstKind("LR\"(wide)\""))
        assertEquals(SyntaxTokenKind.Number, firstKind("0x1.8p+3f"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1'000'000"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0uz"))
        assertEquals(SyntaxTokenKind.Type, firstKind("vector"))
        assertEquals(SyntaxTokenKind.Function, firstKind("size"))
    }

    @Test
    fun handlesUnclosedRawStringsCleanly() {
        val unclosed = """const char* s = R"delim(still ) inside without closing"""
        val tokens = assertLosslessInvariants(CppSyntaxLexer, unclosed, "cpp-unclosed-raw")
        assertEquals(SyntaxTokenKind.String, tokens.last().kind)
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            [[maybe_unused]] constexpr int f() {
                auto s = R"d(abc ) def)d";
                return 1'000;
            }
        """.trimIndent()
        assertPrefixTruncationSafety(CppSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(CppSyntaxLexer, seed = 0x4350504CL, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val longRaw = "R\"delim(" + "a ) b )de ".repeat(3_000) + ")delim\""
        val unclosedRaw = "R\"delim(" + "a ) b )de ".repeat(3_000)
        val hugeDefine = "#define M(x) \\\n" + "    ((x) + 1) \\\n".repeat(2_000) + "    (x)\n"

        assertLinearPerformance(
            lexer = CppSyntaxLexer,
            pathologicalInputs = listOf(
                "long-cpp-raw-string" to longRaw,
                "unclosed-cpp-raw-string" to unclosedRaw,
                "huge-define-continuations" to hugeDefine,
            ),
        )
    }

    @Test
    fun matchesShikiAndPygmentsOracles() {
        assertAllOracleFixtures(cppOracleFixtures)
    }
}
