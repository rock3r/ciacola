package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.JavaSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.JavaOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaSyntaxLexerTest {

    @Test
    fun classifiesJava21ConstructsAnnotationsContextualKeywordsAndLiterals() {
        val code = """
            package dev.example.app;

            import java.util.List;

            /** Javadoc comment */
            @SuppressWarnings("unchecked")
            @org.jspecify.annotations.Nullable
            public sealed class Shape permits Circle, Rect {
                private static final int HEX = 0xFF_00;
                private static final int BIN = 0b1010_0101;
                private static final int OCT = 0755;
                private static final double HEX_FLT1 = 0x1.fp3;
                private static final float HEX_FLT2 = 0x1.0p-4f;
                private static final long BIG = 1_000_000L;
                private final String textBlock = ""${'"'}
                    hello \"quoted\"
                    ""${'"'};

                public non-sealed class Circle extends Shape {}

                public record Point(int x, int y) {}

                @Override
                public String format(Object input) {
                    var count = List.of(1, 2).size();
                    for (var n : List.of(1, 2)) {
                        if (n != 0 && this.textBlock != null) {
                            System.out.println(n);
                        }
                    }
                    // line comment
                    /* block comment */
                    char ch = '\u0041';
                    return switch (input) {
                        case String s when !s.isEmpty() -> s;
                        case Point(int x, int y) when x > 0 -> "pos";
                        default -> {
                            yield "other" + (1 >>> 1) + count + ch;
                        }
                    };
                }
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(JavaSyntaxLexer, code, "java-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Keyword, firstKind("package"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("import"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/** Javadoc comment */"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("// line comment"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/* block comment */"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("SuppressWarnings"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("org"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Nullable"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Override"))

        assertEquals(SyntaxTokenKind.Keyword, firstKind("public"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("sealed"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("class"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Shape"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("permits"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("non-sealed"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("record"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Point"))

        assertEquals(SyntaxTokenKind.Type, firstKind("int"))
        assertEquals(SyntaxTokenKind.Type, firstKind("double"))
        assertEquals(SyntaxTokenKind.Type, firstKind("float"))
        assertEquals(SyntaxTokenKind.Type, firstKind("long"))
        assertEquals(SyntaxTokenKind.Type, firstKind("String"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_00"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1010_0101"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0755"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0x1.fp3"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0x1.0p-4f"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1_000_000L"))
        assertTrue(tokens.any { it.slice(code).contains("hello \\\"quoted\\\"") && it.kind == SyntaxTokenKind.String })
        assertEquals(SyntaxTokenKind.String, firstKind("'\\u0041'"))

        assertEquals(SyntaxTokenKind.Keyword, firstKind("var"))
        assertEquals(SyntaxTokenKind.Function, firstKind("of"))
        assertEquals(SyntaxTokenKind.Function, firstKind("size"))
        assertEquals(SyntaxTokenKind.Property, firstKind("textBlock") { it.startIndex > code.indexOf("this.") })
        assertEquals(SyntaxTokenKind.Constant, firstKind("null"))
        assertEquals(SyntaxTokenKind.Property, firstKind("out"))
        assertEquals(SyntaxTokenKind.Function, firstKind("println"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("when"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("yield"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("->"))
        assertEquals(SyntaxTokenKind.Operator, firstKind(">>>"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("&&"))
    }

    @Test
    fun classifiesContextualAndModuleKeywordsOnlyInValidPositions() {
        val regularCode = """
            class Holder {
                int record = 1;
                String var = "v";
                int yield = 2;
                String when = "w";
                String module = "m";
                int non = 10, sealed = 3;
                int diff = non - sealed;

                void demo() {
                    this.record();
                    this.module();
                    var local = record + yield;
                }

                void record() {}
                void module() {}
            }
        """.trimIndent()

        val regularTokens = assertLosslessInvariants(JavaSyntaxLexer, regularCode, "java-contextual-shadowed")
        assertEquals(SyntaxTokenKind.Plain, regularTokens.first { it.slice(regularCode) == "record" }.kind)
        assertEquals(SyntaxTokenKind.Plain, regularTokens.first { it.slice(regularCode) == "var" }.kind)
        assertEquals(SyntaxTokenKind.Plain, regularTokens.first { it.slice(regularCode) == "yield" }.kind)
        assertEquals(SyntaxTokenKind.Plain, regularTokens.first { it.slice(regularCode) == "when" }.kind)
        assertEquals(SyntaxTokenKind.Plain, regularTokens.first { it.slice(regularCode) == "module" }.kind)
        assertEquals(SyntaxTokenKind.Plain, regularTokens.first { it.slice(regularCode) == "non" }.kind)
        assertEquals(SyntaxTokenKind.Plain, regularTokens.first { it.slice(regularCode) == "sealed" }.kind)
        assertEquals(
            SyntaxTokenKind.Keyword,
            regularTokens.first { it.slice(regularCode) == "var" && it.startIndex >= regularCode.indexOf("var local") }.kind,
        )

        val moduleInfo = """
            open module com.example.app {
                requires transitive java.sql;
                exports com.example.api to com.example.client;
                opens com.example.internal;
                uses com.example.spi.Service;
                provides com.example.spi.Service with com.example.impl.ServiceImpl;
            }
        """.trimIndent()

        val modTokens = assertLosslessInvariants(JavaSyntaxLexer, moduleInfo, "java-module-info")
        for (kw in listOf("open", "module", "requires", "transitive", "exports", "to", "opens", "uses", "provides", "with")) {
            assertEquals(
                SyntaxTokenKind.Keyword,
                modTokens.first { it.slice(moduleInfo) == kw }.kind,
                "Expected module keyword '$kw' to be Keyword",
            )
        }
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            @Deprecated
            public record Item(String name, int qty) {
                static final String BLOCK = ""${'"'}
                    text block
                    ""${'"'};
            }
        """.trimIndent()
        assertPrefixTruncationSafety(JavaSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(JavaSyntaxLexer, seed = 0x4A415641L, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val manyStatements = buildString {
            append("class Perf {\nvoid run() {\n")
            repeat(1_500) { idx ->
                append("var x").append(idx).append(" = \"s_").append(idx).append("\" + ").append(idx).append(";\n")
            }
            append("}\n}")
        }
        val longTextBlock = "class T { String s = \"\"\"\n" + "abc\"\"def".repeat(3_500) + "\"\"\"; }"
        val unclosedComment = "/* " + "star * slash / ".repeat(2_000)

        assertLinearPerformance(
            lexer = JavaSyntaxLexer,
            pathologicalInputs = listOf(
                "many-statements" to manyStatements,
                "long-text-block" to longTextBlock,
                "unclosed-comment" to unclosedComment,
            ),
        )
    }

    @Test
    fun matchesDualOracleFixture() {
        assertMatchesOracleFixture(JavaOracleFixtures.fixture)
    }

    @Test
    fun handlesMethodRefsTypeUseAnnotationsUnnamedVariablesAndMaxHexFloat() {
        val code = """
            class Extra {
                static final double MAX = 0x1.fffffffffffffP+1023;
                void run(List<@NonNull String> items, Object obj) {
                    var fn = String::valueOf;
                    var arr = int[]::new;
                    for (var _ : items) {
                        switch (obj) {
                            case Point(var x, int y) when x > 0 -> fn.apply(y);
                            case _ -> {}
                        }
                    }
                }
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(JavaSyntaxLexer, code, "java-21-edge")
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0x1.fffffffffffffP+1023" }.kind)
        assertEquals(SyntaxTokenKind.Annotation, tokens.first { it.slice(code) == "NonNull" }.kind)
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "valueOf" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "new" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "_" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "var" && it.startIndex > code.indexOf("Point(") }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "when" }.kind)
    }

    @Test
    fun handlesNestedGenericsComparisonAngleResetAndUnicodeSmpIdentifiers() {
        val code = """
            class GenericAndUnicode<T> {
                Map<String, List<T>> table;
                void check(int x) {
                    if (x < 0) {
                        int X = 1;
                        int café = 2;
                        double Δt = 0.5;
                        int 𠮷 = 3;
                        int 𝐀 = X + café + 𠮷;
                    }
                }
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(JavaSyntaxLexer, code, "java-angle-and-unicode")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "T" && it.startIndex > code.indexOf("List<") }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "X" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "café" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "Δt" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "𠮷" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "𝐀" }.kind)
    }

    @Test
    fun supportsJava25ImportModuleAndLeadingBom() {
        val code = "\uFEFFimport module java.base;\nclass App {\n    int module = 1;\n}\n"
        val tokens = assertLosslessInvariants(JavaSyntaxLexer, code, "java-import-module-bom")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Whitespace, tokens.first().kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "import" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "module" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "java" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "base" }.kind)
        assertEquals(
            SyntaxTokenKind.Plain,
            tokens.first { it.slice(code) == "module" && it.startIndex > code.indexOf("int ") }.kind,
        )
    }
}
