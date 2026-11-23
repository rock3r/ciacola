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
        val denseUdls = "\"x\"sv, 100ms, 0xFF_u8,\n".repeat(2_500)

        assertLinearPerformance(
            lexer = CppSyntaxLexer,
            pathologicalInputs = listOf(
                "long-cpp-raw-string" to longRaw,
                "unclosed-cpp-raw-string" to unclosedRaw,
                "huge-define-continuations" to hugeDefine,
                "dense-udls" to denseUdls,
            ),
        )
    }

    @Test
    fun handlesUserDefinedLiteralsCoroutinesGenericLambdasWeirdDelimitersAndPointerToMember() {
        val code = """
            export module demo;
            import <map>;
            [[gnu::always_inline, nodiscard("msg")]]
            auto edge() {
                auto r1 = R"---(raw )-- " text)---";
                auto r2 = uR"()" + UR"()";
                auto dur = 100ms;
                auto sv = "hi"sv;
                auto hx = 0xFF_u8;
                long double hf = 0x1.8p-3L;
                std::map<std::string, std::vector<int>> m;
                auto lam = [=, &m]<typename T>(T&& v) mutable noexcept -> auto {
                    return not (true and false) or (hf <=> 0.0);
                };
                consteval int k = 1;
                constinit int g = 2;
                return lam(k + g);
            }
            struct S { int x; };
            int pm(S* p, int S::* mptr) {
                return p->*mptr + (*p).*mptr;
            }
            auto coro() {
                co_await 1;
                co_yield 2;
                co_return 3;
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(CppSyntaxLexer, code, "cpp-edge")
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "export" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "module" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "import" }.kind)
        assertEquals(SyntaxTokenKind.Attribute, tokens.first { it.slice(code) == "gnu" }.kind)
        assertEquals(SyntaxTokenKind.Attribute, tokens.first { it.slice(code) == "always_inline" }.kind)
        assertEquals(SyntaxTokenKind.Attribute, tokens.first { it.slice(code) == "nodiscard" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"msg\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "R\"---(raw )-- \" text)---\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "uR\"()\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "UR\"()\"" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "100ms" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"hi\"sv" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0xFF_u8" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0x1.8p-3L" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.last { it.slice(code) == "map" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "string" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "T" && it.startIndex > code.indexOf(">(") }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "<=>" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "->*" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == ".*" }.kind)
        for (kw in listOf("not", "and", "or", "consteval", "constinit", "co_await", "co_yield", "co_return")) {
            assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == kw }.kind)
        }
    }

    @Test
    fun handlesSurrogatePairRawStringDelimitersAndUnicodeIdentifiers() {
        val rawEmojiCode = "auto s = R\"🚀(hello)🚀\"; int after = 1;"
        val rawTokens = assertLosslessInvariants(CppSyntaxLexer, rawEmojiCode, "cpp-emoji-raw")
        assertEquals(SyntaxTokenKind.String, rawTokens.first { it.slice(rawEmojiCode) == "R\"🚀(hello)🚀\"" }.kind)
        assertEquals(SyntaxTokenKind.Type, rawTokens.first { it.slice(rawEmojiCode) == "int" }.kind)
        assertEquals(SyntaxTokenKind.Plain, rawTokens.first { it.slice(rawEmojiCode) == "after" }.kind)
        assertEquals(SyntaxTokenKind.Number, rawTokens.first { it.slice(rawEmojiCode) == "1" }.kind)

        val unicodeCode = "double Δt = 0.1; int café = 1; int e\u0301 = 2; int 𠮷 = 3; int 𝐀 = 4; auto f = 1.0f64 + 1.0bf16;"
        val uniTokens = assertLosslessInvariants(CppSyntaxLexer, unicodeCode, "cpp-unicode")
        assertEquals(0, uniTokens.count { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "Δt" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "café" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "e\u0301" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "𠮷" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "𝐀" }.kind)
        assertEquals(SyntaxTokenKind.Number, uniTokens.first { it.slice(unicodeCode) == "1.0f64" }.kind)
        assertEquals(SyntaxTokenKind.Number, uniTokens.first { it.slice(unicodeCode) == "1.0bf16" }.kind)

        val bomCode = "\uFEFFauto x = 1;"
        val bomTokens = assertLosslessInvariants(CppSyntaxLexer, bomCode, "cpp-bom")
        assertEquals(SyntaxTokenKind.Whitespace, bomTokens.first { it.slice(bomCode) == "\uFEFF" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, bomTokens.first { it.slice(bomCode) == "auto" }.kind)

        val bareCrCode = "#define A 1\rint x = 0;\r"
        val crTokens = assertLosslessInvariants(CppSyntaxLexer, bareCrCode, "cpp-bare-cr-pp")
        assertEquals(SyntaxTokenKind.Preprocessor, crTokens.first { it.slice(bareCrCode) == "define" }.kind)
        assertEquals(SyntaxTokenKind.Type, crTokens.first { it.slice(bareCrCode) == "int" }.kind)
        assertEquals(SyntaxTokenKind.Plain, crTokens.first { it.slice(bareCrCode) == "x" }.kind)
        assertEquals(SyntaxTokenKind.Number, crTokens.first { it.slice(bareCrCode) == "0" }.kind)
    }

    @Test
    fun matchesShikiAndPygmentsOracles() {
        assertAllOracleFixtures(cppOracleFixtures)
    }
}
