package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.JavaScriptSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.JavaScriptOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaScriptSyntaxLexerTest {

    @Test
    fun matchesOracleFixtures() {
        for (fixture in JavaScriptOracleFixtures.all) {
            assertMatchesOracleFixture(fixture)
        }
    }

    @Test
    fun disambiguatesRegexLiteralsVsDivisionAcrossContexts() {
        val code = """
            const re1 = /foo\/[a-z]+/gi;
            const re2 = /=bar/;
            const ratio = a / b / c;
            const divParen = (a + b) / 2;
            function check(x) {
              if (x > 0) /abc/.test("abc");
              while (x < 0) /def/.test("def");
              return /ghi/;
            }
            let n = 10;
            n /= 2;
            const arr = [/x[\/]y/, a / b];
        """.trimIndent()

        val tokens = assertLosslessInvariants(JavaScriptSyntaxLexer, code, "js-regex-div")

        fun firstKind(text: String, after: Int = 0): SyntaxTokenKind =
            tokens.first { it.startIndex >= after && it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.String, firstKind("/foo\\/[a-z]+/gi"))
        assertEquals(SyntaxTokenKind.String, firstKind("/=bar/"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("/", after = code.indexOf("ratio")))
        assertEquals(SyntaxTokenKind.Operator, firstKind("/", after = code.indexOf("divParen")))
        assertEquals(SyntaxTokenKind.String, firstKind("/abc/"))
        assertEquals(SyntaxTokenKind.String, firstKind("/def/"))
        assertEquals(SyntaxTokenKind.String, firstKind("/ghi/"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("/="))
        assertEquals(SyntaxTokenKind.String, firstKind("/x[\\/]y/"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("/", after = code.indexOf("a / b")))
    }

    @Test
    fun handlesNestedTemplatesNumbersKeywordsDecoratorsAndJsx() {
        val code = """
            #!/usr/bin/env node
            /** JSDoc */
            const msg = `hello ${'$'}{user.name + ` (${'$'}{items.length})`}`;
            const nums = [0, 42, 3.14, .5, 1e-5, 0xFF, 0b1010, 0o755, 1_000_000, 42n];
            const consts = [true, false, null, undefined, NaN, Infinity];
            @Component
            export class Counter extends Base {
              #secret = 0;
              static async run(fn) {
                const v = (await fn?.()) ?? { key: 1, ...nums };
                return (
                  <Button disabled onClick={() => this.#secret++}>
                    Don't click {v.key}
                    <Fragment />
                    <></>
                  </Button>
                );
              }
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(JavaScriptSyntaxLexer, code, "js-features")

        fun firstKind(text: String, after: Int = 0): SyntaxTokenKind =
            tokens.first { it.startIndex >= after && it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Comment, firstKind("#!/usr/bin/env node"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/** JSDoc */"))
        assertEquals(SyntaxTokenKind.Property, firstKind("name"))
        assertEquals(SyntaxTokenKind.Property, firstKind("length"))
        for (num in listOf("0", "42", "3.14", ".5", "1e-5", "0xFF", "0b1010", "0o755", "1_000_000", "42n")) {
            assertEquals(SyntaxTokenKind.Number, firstKind(num, after = code.indexOf("nums")), "Number $num")
        }
        for (c in listOf("true", "false", "null", "undefined", "NaN", "Infinity")) {
            assertEquals(SyntaxTokenKind.Constant, firstKind(c, after = code.indexOf("consts")), "Constant $c")
        }
        assertEquals(SyntaxTokenKind.Annotation, firstKind("@"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Component"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Counter"))
        assertEquals(SyntaxTokenKind.Property, firstKind("#secret"))
        assertEquals(SyntaxTokenKind.Punctuation, firstKind("?."))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??"))
        assertEquals(SyntaxTokenKind.Property, firstKind("key"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("..."))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("=>"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("Button"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("disabled"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("onClick"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("Don't"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("Fragment"))

        // Shebang only at offset 0
        val nonZeroShebang = "  #!/usr/bin/env node"
        val nonZeroTokens = assertLosslessInvariants(JavaScriptSyntaxLexer, nonZeroShebang, "js-shebang-offset")
        assertTrue(nonZeroTokens.none { it.kind == SyntaxTokenKind.Comment })
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            const r = /a\/b/g;
            const s = `hi ${'$'}{`nested ${'$'}{1 + 2}`}`;
            const ui = <Card id="1">{items.map((x) => <Row key={x} />)}</Card>;
        """.trimIndent()
        assertPrefixTruncationSafety(JavaScriptSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(JavaScriptSyntaxLexer, seed = 0x4A534A53L, iterations = 70)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val unclosedComment = "/* " + "*".repeat(25_000)
        val nestedTemplates = "`a" + "\${`b".repeat(400) + "x" + "}`".repeat(400) + "`"
        val alternatingSlashes = "const x = " + (1..3_000).joinToString(" / ") { "a$it" } + ";"
        val longString = "\"${"x\\\"".repeat(7_000)}\""
        val largeJsx = "const ui = <div>" + "<span data-k=\"v\">text</span>".repeat(800) + "</div>;"

        assertLinearPerformance(
            lexer = JavaScriptSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-comment" to unclosedComment,
                "nested-templates" to nestedTemplates,
                "alternating-slashes" to alternatingSlashes,
                "long-string" to longString,
                "large-jsx" to largeJsx,
            ),
        )
    }

    @Test
    fun handlesAdvancedRegexOperatorsTaggedTemplatesUsingAndComplexJsx() {
        val code = """
            function* gen() { yield /abc/gimsuyv; yield* [1, 2]; }
            const d1 = foo() / 2;
            const d2 = arr[0] / 2;
            const d3 = i++ / 2;
            ++i; /re/.test("re");
            const r4 = a + /re/[0];
            const r5 = void /re/;
            const r6 = typeof /re/;
            const r7 = await /re/;
            const r8 = /[a/b]/g;
            const q = sql`SELECT * FROM ${'$'}{t} WHERE id = ${'$'}{`nested_${'$'}{id}`}`;
            class Box { #x = 0xFF_FFn; has(o) { return #x in o; } }
            const ns = [0b1010n, 0o755n, 1_000.5e-3];
            let a = null; a ??= 1; a &&= 2; a ||= 3;
            const u = import.meta.url;
            using h = open();
            await using ah = openAsync();
            const el = <><svg:path d="M0 0" /><Form.Input {...props} disabled /></>;
        """.trimIndent()

        val tokens = assertLosslessInvariants(JavaScriptSyntaxLexer, code, "js-edge-cases")
        assertTrue(tokens.none { it.kind == SyntaxTokenKind.Invalid })

        fun firstKind(text: String, after: Int = 0): SyntaxTokenKind =
            tokens.first { it.startIndex >= after && it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.String, firstKind("/abc/gimsuyv"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("/", after = code.indexOf("d1")))
        assertEquals(SyntaxTokenKind.Operator, firstKind("/", after = code.indexOf("d2")))
        assertEquals(SyntaxTokenKind.Operator, firstKind("/", after = code.indexOf("d3")))
        assertEquals(SyntaxTokenKind.String, firstKind("/re/", after = code.indexOf("++i")))
        assertEquals(SyntaxTokenKind.String, firstKind("/re/", after = code.indexOf("r4")))
        assertEquals(SyntaxTokenKind.String, firstKind("/re/", after = code.indexOf("r5")))
        assertEquals(SyntaxTokenKind.String, firstKind("/re/", after = code.indexOf("r6")))
        assertEquals(SyntaxTokenKind.String, firstKind("/re/", after = code.indexOf("r7")))
        assertEquals(SyntaxTokenKind.String, firstKind("/[a/b]/g"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_FFn"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1010n"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0o755n"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1_000.5e-3"))
        assertEquals(SyntaxTokenKind.Property, firstKind("#x"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("in", after = code.indexOf("has(")))
        assertEquals(SyntaxTokenKind.Operator, firstKind("??="))
        assertEquals(SyntaxTokenKind.Operator, firstKind("&&="))
        assertEquals(SyntaxTokenKind.Operator, firstKind("||="))
        assertEquals(SyntaxTokenKind.Property, firstKind("meta"))
        assertEquals(SyntaxTokenKind.Property, firstKind("url", after = code.indexOf("import.meta")))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("using"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("svg"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("path"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("Form"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("Input"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("disabled"))
    }
}
