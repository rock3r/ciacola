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
        val cNonRaw = """const char *x = R"(not raw)";"""
        val cNonRawTokens = assertLosslessInvariants(CSyntaxLexer, cNonRaw, "c-not-raw-string")
        assertTrue(cNonRawTokens.any { it.slice(cNonRaw) == "R" && it.kind == SyntaxTokenKind.Plain })
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
    fun matchesShikiAndPygmentsOracles() {
        assertAllOracleFixtures(cOracleFixtures)
    }
}
