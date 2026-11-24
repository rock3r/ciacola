package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.DartSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.DartOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DartSyntaxLexerTest {

    @Test
    fun classifiesKeywordsTypesAnnotationsNumbersOperatorsAndComments() {
        val code = """
            library demo;
            import 'dart:math' as math show min, max hide sin;
            import 'dart:convert' deferred as conv;
            export 'src/api.dart';
            part 'src/part.dart';

            /// Doc line comment
            /** Doc block /* nested */ comment */
            /* outer /* inner */ end */

            @override
            @Deprecated('old')
            @pragma('vm:prefer-inline')
            @immutable
            abstract base class Shape {
              const Shape();
            }

            sealed interface class Drawable implements Shape {}

            mixin Logger on Shape {
              late final String tag;
              covariant num weight = 1_000_000;
              static const int hex = 0xFF_FF;
              static const double flt = .5 + 1.5 + 1e-3 + 1.5e+2;
              external void paint();
              factory Logger.empty() => throw UnimplementedError();
              int get size => 1;
              set size(int value) {}
              Shape operator +(Shape other) => this;
            }

            extension type Meter(int value) {}
            extension on String { int get len => length; }
            typedef IntFn = int Function(int x);

            Stream<int> generate(dynamic input, {required bool flag}) async* {
              assert(flag);
              var list = <int>[1, 2, ...?null];
              list?..add(3)..sort();
              var item0 = list?[0];
              var a = input is! String ? (input as int?) ?? 0 : 1;
              a ??= 2;
              var q = a ~/ 2;
              q ~/= 1;
              var u = 8 >>> 1;
              u >>>= 1;
              switch ((a, u)) {
                case (var x, var y) when x > 0:
                  yield x + y;
                default:
                  yield* Stream.empty();
              }
              await for (final item in Stream.fromIterable([1])) {
                if (item == 0) continue; else break;
              }
              try {
                rethrow;
              } on Exception catch (e) {
                print(#foo.bar);
                print(#==);
              } finally {
                return;
              }
            }

            Iterable<int> syncGen() sync* { yield 1; }
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(DartSyntaxLexer, code, "dart-features")
        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Comment, firstKind("/// Doc line comment"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/** Doc block /* nested */ comment */"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/* outer /* inner */ end */"))

        assertEquals(SyntaxTokenKind.Annotation, firstKind("@override"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("@Deprecated"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("@pragma"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("@immutable"))

        for (kw in listOf(
            "library", "import", "as", "show", "hide", "deferred", "export", "part",
            "abstract", "base", "class", "const", "sealed", "interface",
            "implements", "mixin", "on", "late", "final", "covariant",
            "static", "external", "factory", "get", "set", "operator",
            "extension", "type", "typedef", "required", "async", "assert",
            "var", "is", "switch", "case", "when", "yield", "default",
            "await", "for", "in", "continue", "break", "try", "rethrow",
            "catch", "finally", "return", "sync", "throw", "this",
        )) {
            assertEquals(SyntaxTokenKind.Keyword, firstKind(kw), "Expected '$kw' to be Keyword")
        }

        assertEquals(SyntaxTokenKind.Type, firstKind("dynamic"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Function"))
        assertEquals(SyntaxTokenKind.Type, firstKind("void"))
        assertEquals(SyntaxTokenKind.Type, firstKind("int"))
        assertEquals(SyntaxTokenKind.Type, firstKind("String"))
        assertEquals(SyntaxTokenKind.Type, firstKind("num"))

        assertEquals(SyntaxTokenKind.Number, firstKind("1_000_000"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_FF"))
        assertEquals(SyntaxTokenKind.Number, firstKind(".5"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1.5"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1e-3"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1.5e+2"))

        assertEquals(SyntaxTokenKind.Operator, firstKind(".."))
        assertEquals(SyntaxTokenKind.Operator, firstKind("?.."))
        assertEquals(SyntaxTokenKind.Operator, firstKind("...?"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??="))
        assertEquals(SyntaxTokenKind.Operator, firstKind("~/"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("~/="))
        assertEquals(SyntaxTokenKind.Operator, firstKind(">>>"))
        assertEquals(SyntaxTokenKind.Operator, firstKind(">>>="))
        assertEquals(SyntaxTokenKind.Operator, firstKind("=>"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("null"))
    }

    @Test
    fun handlesRawStringsMultilineStringsAndNestedInterpolations() {
        val code = """
            var s1 = 'hello ${'$'}name ${'$'}{a + {'k': 1}['k']!} \${'$'}escaped \n';
            var s2 = "nested ${'$'}{"inner ${'$'}x" /* comment */}";
            var s3 = '''
              multi ${'$'}{1 + 2}
              'quote' ''two''
            ''';
            var s4 = ""${'"'}
              multi ${'$'}name
              "quote" ""two""
            ""${'"'};
            var r1 = r'no \n escape ${'$'}noInterp \${'$'}{noExpr} \\';
            var r2 = r"raw \n ${'$'}x";
            var r3 = r'''raw
            'single' ''double'' ${'$'}no \n''';
            var r4 = r""${'"'}raw
            "single" ""double"" ${'$'}no \n""${'"'};
            var trailingBs = r'abc\';
            var after = 42;
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(DartSyntaxLexer, code, "dart-strings")
        assertEquals(SyntaxTokenKind.Punctuation, tokens.first { it.slice(code) == "$" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens.first { it.slice(code) == "name" }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "\\$" }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "\\n" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code) == "/* comment */" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).startsWith("r'no ") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).startsWith("r\"raw") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).startsWith("r'''raw") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).startsWith("r\"\"\"raw") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "r'abc\\'" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "42" }.kind)
    }

    @Test
    fun distinguishesContextualKeywordsFromShadowedIdentifiers() {
        val code = """
            void test() {
              var async = 1;
              var await = 2;
              var get = 3;
              var set = 4;
              var when = 5;
              var show = 6;
              var hide = 7;
              var sealed = 8;
              var type = 9;
              var on = 10;
              var of = 11;
              var dynamic = 'd';
              var yield = 12;
              var result = async + await + get + set + when + show + hide + sealed + type + on + of + yield;
            }
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(DartSyntaxLexer, code, "dart-contextual")
        for (ident in listOf(
            "async", "await", "get", "set", "when", "show", "hide",
            "sealed", "type", "on", "of", "dynamic", "yield",
        )) {
            assertEquals(
                SyntaxTokenKind.Plain,
                tokens.first { it.slice(code) == ident }.kind,
                "Expected shadowed '$ident' to be Plain",
            )
        }
    }

    @Test
    fun handlesShebangAndGetterAsyncAbstractMembersTypedefVoidAndMultilineImplements() {
        val shebangCode = "#!/usr/bin/env dart\nvoid main() {}\n"
        val shebangTokens = assertValidSnippetHasNoInvalidTokens(DartSyntaxLexer, shebangCode, "dart-shebang")
        assertEquals(SyntaxTokenKind.Comment, shebangTokens.first { it.slice(shebangCode) == "#!/usr/bin/env dart" }.kind)
        assertEquals(SyntaxTokenKind.Type, shebangTokens.first { it.slice(shebangCode) == "void" }.kind)
        assertEquals(SyntaxTokenKind.Function, shebangTokens.first { it.slice(shebangCode) == "main" }.kind)

        val code = """
            abstract class C {
              abstract int count;
              abstract var x;
              abstract void run();
              Future<int> get value async => 42;
              Stream<int> get stream async* {}
              Iterable<int> get items sync* {}
            }
            typedef void Callback(int x);
            class A
              implements
                C {}
        """.trimIndent()
        val tokens = assertValidSnippetHasNoInvalidTokens(DartSyntaxLexer, code, "dart-contextual-edge-cases")
        val abstractTokens = tokens.filter { it.slice(code) == "abstract" }
        assertEquals(4, abstractTokens.size)
        for (tok in abstractTokens) {
            assertEquals(SyntaxTokenKind.Keyword, tok.kind)
        }
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "async" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.last { it.slice(code) == "async" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "sync" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "typedef" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.last { it.slice(code) == "void" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "implements" }.kind)
    }

    @Test
    fun recoversFromUnterminatedStringsCommentsAndHandlesBomCrlfUnicodeAndSurrogates() {
        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val recovery = "\uFEFFvar s = 'unclosed \${x\r\nint next = 99;\rvar u = \"emoji 🎉 $loneHigh$loneLow\";\n/* /* unclosed"
        val tokens = assertLosslessInvariants(DartSyntaxLexer, recovery, "dart-recovery-unicode")
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(recovery) == "int" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(recovery) == "99" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tokens.last().kind)
    }

    @Test
    fun prefixTruncationFuzzAndLinearPerformance() {
        val sample = """
            sealed class Node<T> {
              String describe() => 'node=${'$'}{T.toString()}';
              /* outer /* inner */ end */
            }
        """.trimIndent()
        assertPrefixTruncationSafety(DartSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(DartSyntaxLexer, seed = 0x44415254L, iterations = 60)

        val unclosedStrings = "var s = 'unclosed \${x\n".repeat(1_500)
        val unclosedRawMulti = "var s = r'''" + "line '' text\n".repeat(3_000)
        val unclosedNestedComments = "/* ".repeat(5_000) + "body"
        val unclosedInterps = "\"\${".repeat(500) + "x"
        val manyInterps = buildString {
            repeat(1_000) { idx ->
                append("var s").append(idx).append(" = 'v=\${a + ").append(idx).append("}';\n")
            }
        }

        assertLinearPerformance(
            lexer = DartSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-strings" to unclosedStrings,
                "unclosed-raw-multi" to unclosedRawMulti,
                "unclosed-nested-comments" to unclosedNestedComments,
                "unclosed-interps" to unclosedInterps,
                "many-interps" to manyInterps,
            ),
        )
    }

    @Test
    fun matchesDualOracleFixture() {
        assertMatchesOracleFixture(DartOracleFixtures.classesPatternsStringsAndOperators)
    }
}
