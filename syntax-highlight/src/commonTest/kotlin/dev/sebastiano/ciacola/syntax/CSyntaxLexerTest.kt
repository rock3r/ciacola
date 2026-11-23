package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.CSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import dev.sebastiano.ciacola.syntax.oracle.cOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CSyntaxLexerTest {

    @Test
    fun classifiesPreprocessorDirectivesHeadersStructsPointersAndC23Features() {
        val code = """
            #include <stdio.h>
            #include "header.h"
            #define MAX(a, b) \
                ((a) > (b) ? (a) : (b))
            #ifdef DEBUG
            #pragma once
            #endif

            typedef struct Node {
                int value;
                struct Node *next;
            } Node;

            [[nodiscard]] constexpr int compute(const Node *n) {
                int sep = 1'000'000;
                double hf = 0x1.8p+3f;
                size_t sz = sizeof(Node);
                const char *s = u8"hi\x1b";
                char ch = '\u00e9';
                if (n == NULL || n == nullptr) return 0;
                return MAX(n->value, (int)(sep + hf + sz + ch + (s != NULL)));
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(CSyntaxLexer, code, "c-features")

        fun firstKind(text: String): SyntaxTokenKind =
            tokens.first { it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("include"))
        assertEquals(SyntaxTokenKind.String, firstKind("<stdio.h>"))
        assertEquals(SyntaxTokenKind.String, firstKind("\"header.h\""))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("define"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("\\\n"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("ifdef"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("pragma"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("endif"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("typedef"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("struct"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Node"))
        assertEquals(SyntaxTokenKind.Type, firstKind("int"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("nodiscard"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("constexpr"))
        assertEquals(SyntaxTokenKind.Function, firstKind("compute"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1'000'000"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0x1.8p+3f"))
        assertEquals(SyntaxTokenKind.Type, firstKind("size_t"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("sizeof"))
        assertEquals(SyntaxTokenKind.String, firstKind("u8\"hi\\x1b\""))
        assertEquals(SyntaxTokenKind.String, firstKind("'\\u00e9'"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("NULL"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("nullptr"))
        assertEquals(SyntaxTokenKind.Property, tokens.last { it.slice(code) == "value" }.kind)

        // C mode must NOT parse C++ R"(...)" as raw string
        val cNonRaw = """const char *x = R"(foo)";"""
        val cNonRawTokens = assertLosslessInvariants(CSyntaxLexer, cNonRaw, "c-not-raw-string")
        assertEquals(0, cNonRawTokens.count { it.kind == SyntaxTokenKind.Invalid })
        assertTrue(cNonRawTokens.any { it.slice(cNonRaw) == "R" && it.kind == SyntaxTokenKind.Plain })
        assertTrue(cNonRawTokens.any { it.slice(cNonRaw) == "\"(foo)\"" && it.kind == SyntaxTokenKind.String })
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            #include <stdint.h>
            #define ADD(x, y) \
                ((x) + (y))
            int main(void) { return ADD(1, 2); }
        """.trimIndent()
        assertPrefixTruncationSafety(CSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(CSyntaxLexer, seed = 0x434C414EL, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val hugeDefine = "#define BIG(x) \\\n" + "    (x + 1) \\\n".repeat(2_000) + "    (x)\n"
        val unclosedComment = "/* " + "c_code ".repeat(4_000)

        assertLinearPerformance(
            lexer = CSyntaxLexer,
            pathologicalInputs = listOf(
                "huge-define-continuations" to hugeDefine,
                "unclosed-block-comment" to unclosedComment,
            ),
        )
    }

    @Test
    fun handlesC11C23KeywordsAndPreprocessorStringizePaste() {
        val code = """
            #include <sys/types.h>
            #define STR(x) #x
            #define PASTE(a, b) a##b
            _Static_assert(sizeof(int) >= 2, "int");
            _Noreturn void die(void);
            _Alignas(16) static _Atomic int cnt = 0;
            _Thread_local _Bool ok = 1;
            double _Complex z = 1.0;
            int test(int v) {
                typeof(v) c = v;
                int a = _Alignof(int) + 0b1010;
                return _Generic(c, int: c + a, default: 0);
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(CSyntaxLexer, code, "c11-c23")
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "<sys/types.h>" }.kind)
        assertEquals(SyntaxTokenKind.Preprocessor, tokens.first { it.slice(code) == "#x" }.kind)
        assertEquals(SyntaxTokenKind.Preprocessor, tokens.first { it.slice(code) == "a##b" }.kind)
        for (kw in listOf("_Static_assert", "_Noreturn", "_Alignas", "_Atomic", "_Thread_local", "typeof", "_Alignof", "_Generic")) {
            assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == kw }.kind)
        }
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "_Bool" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "_Complex" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0b1010" }.kind)
    }

    @Test
    fun supportsUnicodeIdentifiersAndStrictC23NumericSuffixes() {
        val unicodeCode = "double Δt = 0.1; int café = 1; int e\u0301 = 2; int 𠮷 = 3; int 𝐀 = 4;"
        val uniTokens = assertLosslessInvariants(CSyntaxLexer, unicodeCode, "c-unicode")
        assertEquals(0, uniTokens.count { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "Δt" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "café" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "e\u0301" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "𠮷" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "𝐀" }.kind)

        val suffixCode = "unsigned _BitInt(8) b = 42uwb; float f = 1.0f32; double d = 1.0bf16; int bad = 42ffuuzzll;"
        val suffixTokens = assertLosslessInvariants(CSyntaxLexer, suffixCode, "c-suffixes")
        assertEquals(SyntaxTokenKind.Number, suffixTokens.first { it.slice(suffixCode) == "42uwb" }.kind)
        assertEquals(SyntaxTokenKind.Number, suffixTokens.first { it.slice(suffixCode) == "1.0f32" }.kind)
        assertEquals(SyntaxTokenKind.Number, suffixTokens.first { it.slice(suffixCode) == "1.0bf16" }.kind)
        assertEquals(SyntaxTokenKind.Number, suffixTokens.first { it.slice(suffixCode) == "42" }.kind)
        assertEquals(SyntaxTokenKind.Plain, suffixTokens.first { it.slice(suffixCode) == "ffuuzzll" }.kind)

        val bomCode = "\uFEFFint x = 1;"
        val bomTokens = assertLosslessInvariants(CSyntaxLexer, bomCode, "c-bom")
        assertEquals(SyntaxTokenKind.Whitespace, bomTokens.first { it.slice(bomCode) == "\uFEFF" }.kind)
        assertEquals(SyntaxTokenKind.Type, bomTokens.first { it.slice(bomCode) == "int" }.kind)

        val bareCrCode = "#define A 1\rint x = 0;\r"
        val crTokens = assertLosslessInvariants(CSyntaxLexer, bareCrCode, "c-bare-cr-pp")
        assertEquals(SyntaxTokenKind.Preprocessor, crTokens.first { it.slice(bareCrCode) == "define" }.kind)
        assertEquals(SyntaxTokenKind.Type, crTokens.first { it.slice(bareCrCode) == "int" }.kind)
        assertEquals(SyntaxTokenKind.Plain, crTokens.first { it.slice(bareCrCode) == "x" }.kind)
        assertEquals(SyntaxTokenKind.Number, crTokens.first { it.slice(bareCrCode) == "0" }.kind)
    }

    @Test
    fun matchesShikiAndPygmentsOracles() {
        assertAllOracleFixtures(cOracleFixtures)
    }
}
