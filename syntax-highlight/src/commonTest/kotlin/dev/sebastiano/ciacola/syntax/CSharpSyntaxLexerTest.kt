package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.CSharpSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.CSharpOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CSharpSyntaxLexerTest {

    @Test
    fun classifiesKeywordsContextualKeywordsTypesNumbersOperatorsAndComments() {
        val code = """
            #nullable enable
            #pragma warning disable 1591
            #region Model
            #if DEBUG
            #define TRACE_ON
            #elif RELEASE
            #warning Release build
            #else
            #error Unsupported
            #endif
            #undef TRACE_ON
            #line 10 "Generated.cs"
            #endregion

            using System;
            using global::System.Collections.Generic;

            namespace Demo.Models;

            /// <summary>Represents a user record.</summary>
            [assembly: AssemblyVersion("1.0")]
            [Serializable]
            [HttpGet("api/v1")]
            public sealed partial record class User(string Name, int Age) {
                /* block comment */
                // line comment
                private required string _label;
                private nint _ptr;
                private nuint _uptr;
                private dynamic _extra;

                public string Label {
                    get => _label;
                    init => _label = value;
                }

                public async Task<int> ComputeAsync([NotNull] string input, scoped ReadOnlySpan<byte> span) {
                    var @class = "kw";
                    var @event = 1;
                    var @string = "str";
                    var hex = 0xFF_00UL;
                    var bin = 0b1010_0101u;
                    var dec = 1_000_000L;
                    var flt = 3.5f + 1.2e-3d + 9.99m;
                    var ch = '\u0041';
                    int[] arr = [1, 2, 3];
                    var v = arr?[0] ?? 0;
                    v ??= 1;
                    var slice = arr[0..^1];
                    var msg = nameof(input);
                    var next = this with { Name = msg };
                    var shift = 1 >>> 1;
                    var ok = true && !false && null is null;
                    await Task.Yield();
                    return arr[0];
                }

                public static IEnumerable<int> Range(int n) {
                    for (int i = 0; i < n; i++) {
                        yield return i;
                    }
                    yield break;
                }
            }
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(CSharpSyntaxLexer, code, "csharp-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("#"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("nullable"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("pragma"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("region"))
        assertEquals(SyntaxTokenKind.Preprocessor, firstKind("endif"))
        assertEquals(SyntaxTokenKind.String, firstKind("\"Generated.cs\""))

        assertEquals(SyntaxTokenKind.Comment, firstKind("/// <summary>Represents a user record.</summary>"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/* block comment */"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("// line comment"))

        assertEquals(SyntaxTokenKind.Keyword, firstKind("assembly"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("AssemblyVersion"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Serializable"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("HttpGet"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("NotNull"))
        assertEquals(SyntaxTokenKind.Punctuation, firstKind("[") { it.startIndex > code.indexOf("arr[0]") - 1 })

        assertEquals(SyntaxTokenKind.Keyword, firstKind("using"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("global"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("namespace"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("public"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("sealed"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("partial"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("record"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("class"))
        assertEquals(SyntaxTokenKind.Type, firstKind("User"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("required"))
        assertEquals(SyntaxTokenKind.Type, firstKind("nint"))
        assertEquals(SyntaxTokenKind.Type, firstKind("nuint"))
        assertEquals(SyntaxTokenKind.Type, firstKind("dynamic"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("get"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("init"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("async"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("scoped"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("await"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("nameof"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("with"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("yield"))

        assertEquals(SyntaxTokenKind.Plain, firstKind("@class"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("@event"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("@string"))

        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_00UL"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1010_0101u"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1_000_000L"))
        assertEquals(SyntaxTokenKind.Number, firstKind("3.5f"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1.2e-3d"))
        assertEquals(SyntaxTokenKind.Number, firstKind("9.99m"))
        assertEquals(SyntaxTokenKind.String, firstKind("'\\u0041'"))

        assertEquals(SyntaxTokenKind.Constant, firstKind("true"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("false"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("null"))

        assertEquals(SyntaxTokenKind.Operator, firstKind("=>"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??="))
        assertEquals(SyntaxTokenKind.Operator, firstKind(".."))
        assertEquals(SyntaxTokenKind.Operator, firstKind(">>>"))
        assertEquals(SyntaxTokenKind.Punctuation, firstKind("::"))
    }

    @Test
    fun handlesAllStringFormsInterpolationTernaryAndRawDelimiters() {
        val code = """
            var a = "hi\n\u0041"u8;
            var b = @"multi
            ""quoted"" \path"u8;
            var c = $"hello {name,10:N2} {{escaped}}";
            var d = $@"v ""x"" {cond ? "yes" : "no"}";
            var e = @$"nested {$"inner {x + 1}"} end";
            var f = ""${'"'}raw "1" ""2"" end""${'"'}u8;
            var g = ""${'"'}"four ""${'"'} inside""${'"'}" ;
            var h = ${'$'}${'$'}""${'"'}one {lit} two {{val:N2}} {{{x}}} end""${'"'};
            var k = $"obj {new { A = 1 }} done";
            var ml = $"line1 {
                name
            } line2";
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(CSharpSyntaxLexer, code, "csharp-strings")

        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"hi\\n\\u0041\"u8" }.kind)
        assertTrue(tokens.any { it.slice(code).endsWith("\"u8") && it.slice(code).contains("\"\"quoted\"\"") && it.kind == SyntaxTokenKind.String })
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == ":" && it.startIndex > code.indexOf("10") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "N2" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "escaped" }.kind)
        assertEquals(SyntaxTokenKind.Operator, tokens.first { it.slice(code) == "?" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"yes\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"no\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"\"\"raw \"1\" \"\"2\"\" end\"\"\"u8" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"\"\"\"four \"\"\" inside\"\"\"\"" }.kind)
        val rawInterpStart = code.indexOf("var h =")
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "{" && it.startIndex > rawInterpStart }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "lit" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "}" && it.startIndex > rawInterpStart }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "x" && it.startIndex > code.indexOf("{{{") }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, tokens.first { it.slice(code) == "{{" && it.startIndex > rawInterpStart }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, tokens.first { it.slice(code) == "}}" && it.startIndex > rawInterpStart }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "val" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == " line2" }.kind)
    }

    @Test
    fun distinguishesContextualKeywordsFromShadowedIdentifiers() {
        val code = """
            class Box<T> where T : unmanaged, notnull {
                int record = 1;
                string async = "a";
                int select = 2;
                int get = 3;
                void Test(int from, int where) {
                    var x = from + where + select + get;
                    var q = from n in items
                            where n > 0
                            orderby n descending
                            group n by n into g
                            let k = g.Key
                            join y in other on k equals y
                            select k;
                    var pat = x switch {
                        > 0 and < 10 when x != 5 => "mid",
                        not 0 or 99 => "edge",
                        _ => "zero"
                    };
                    try { } catch (Exception ex) when (ex.Message != null) { }
                }
            }
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(CSharpSyntaxLexer, code, "csharp-contextual")

        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "where" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "unmanaged" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "notnull" }.kind)

        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "record" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "async" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "select" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "get" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "from" }.kind)

        val linqStart = code.indexOf("var q =")
        for (kw in listOf("from", "where", "orderby", "descending", "group", "by", "into", "let", "join", "on", "equals", "select")) {
            assertEquals(
                SyntaxTokenKind.Keyword,
                tokens.first { it.slice(code) == kw && it.startIndex > linqStart }.kind,
                "Expected LINQ contextual keyword '$kw' to be Keyword",
            )
        }
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "and" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "or" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "not" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "when" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.last { it.slice(code) == "when" }.kind)
    }

    @Test
    fun recoversGracefullyFromUnterminatedStringsCommentsAndMidLineHashes() {
        val code = "var s = $\"unclosed {x\nint y = 1;\nint z = 2 # 3;\n/* unclosed"
        val tokens = assertLosslessInvariants(CSharpSyntaxLexer, code, "csharp-recovery")
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "int" }.kind)
        assertEquals(SyntaxTokenKind.Invalid, tokens.first { it.slice(code) == "#" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tokens.last().kind)
    }

    @Test
    fun prefixTruncationFuzzAndLinearPerformance() {
        val sample = """
            #if DEBUG
            [Serializable]
            public record Item(string Name) {
                string Format() => $$""${'"'}[{Name}]={{1 + 2}}""${'"'};
            }
            #endif
        """.trimIndent()
        assertPrefixTruncationSafety(CSharpSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(CSharpSyntaxLexer, seed = 0x43534852L, iterations = 60)

        val manyInterps = buildString {
            repeat(1_000) { idx ->
                append("var s").append(idx).append(" = $\"v={").append(idx).append(":N2}\";\n")
            }
        }
        val longRaw = "var r = \"\"\"\"" + "abc\"\"\"def".repeat(3_500) + "\"\"\"\";"
        val unclosedComment = "/* " + "star * slash / ".repeat(2_000)
        assertLinearPerformance(
            lexer = CSharpSyntaxLexer,
            pathologicalInputs = listOf(
                "many-interps" to manyInterps,
                "long-raw" to longRaw,
                "unclosed-comment" to unclosedComment,
            ),
        )
    }

    @Test
    fun handlesMultilineVerbatimRawAndLongExpressionsInsideRegularInterpolation() {
        val longArm = (1..25).joinToString("\n            ") { i -> "$i => \"item_$i\"," }
        val code = """
            var a = ${'$'}"res: {
                @"line 1
            line 2"
            }";
            var b = ${'$'}"raw: {
                ""${'"'}
                multi
                line
                ""${'"'}
            }";
            var c = ${'$'}"big: {
                x switch {
                    $longArm
                    _ => "default"
                }
            }";
            int after = 1;
        """.trimIndent()

        assertTrue(code.indexOf("int after") > 550)
        val tokens = assertValidSnippetHasNoInvalidTokens(CSharpSyntaxLexer, code, "csharp-multiline-interp-hole")
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "int" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "after" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.last { it.slice(code) == "1" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).contains("line 2") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).contains("multi") }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "switch" }.kind)
    }

    @Test
    fun handlesFormatSpecifierEscapesBracesNullableTargetsU8SuffixesAndInlinePpComments() {
        val code = """
            #if DEBUG /* inline comment */
            var a = ${'$'}"val: {n:0\"px\"}";
            var b = $@"val: {n:0""px""}";
            var c = ${'$'}"val: {n:0}}}";
            var d = ${'$'}"price: {x as decimal? + 1:#,##0}";
            var e = ${'$'}"hello {x}"u8;
            var f = $@"verb {x}"U8;
            var g = ${'$'}${'$'}""${'"'}raw {{x}}""${'"'}u8;
            #endif
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(CSharpSyntaxLexer, code, "csharp-format-u8-pp")
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code) == "/* inline comment */" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\\\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "}}" && it.startIndex > code.indexOf("var c =") }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, tokens.first { it.slice(code) == "}" && it.startIndex > code.indexOf("var c =") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "#,##0" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"u8" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"U8" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"\"\"u8" }.kind)
    }

    @Test
    fun matchesDualOracleFixture() {
        assertMatchesOracleFixture(CSharpOracleFixtures.classesAttributesAndPreprocessor)
    }
}
