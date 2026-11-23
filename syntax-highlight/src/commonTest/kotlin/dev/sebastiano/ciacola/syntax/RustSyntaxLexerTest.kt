package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.RustSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import dev.sebastiano.ciacola.syntax.oracle.rustOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class RustSyntaxLexerTest {

    @Test
    fun classifiesCommentsRawStringsLifetimesCharsAttributesMacrosAndNumbers() {
        val code = """
            //! Crate doc
            #![no_std]

            /// Item doc /* nested */
            #[derive(Debug, Clone)]
            pub async fn parse<'a, T: Default + 'static>(input: &'a str) -> Result<Vec<T>, &'a str> {
                /* outer /* inner */ still comment */
                let raw0 = r"plain";
                let raw1 = r#"has "quotes""#;
                let raw2 = r##"a "# b"##;
                let braw = br#"bytes"#;
                let craw = cr#"cstr"#;
                let normal = "hi\n\x7F\u{1F600}";
                let bstr = b"bytes\n";
                let cstr = c"cstr\n";
                let ch = '\u{1F600}';
                let ch_nl = '\n';
                let ch_hex = '\x41';
                let bch = b'x';
                let num = 0xFF_u32 + 0b1010_0101 + 0o755;
                let flt = 1_000.5e-2f64 + 3.14_f32 + 42usize as f64;
                let flag = true || false;
                let range_end = 0..=10;
                println!("{}", raw2.len());
                macro_rules! m { () => {}; }
                let _ = (raw0, raw1, braw, craw, normal, bstr, cstr, ch, ch_nl, ch_hex, bch, num, flt, flag, range_end);
                Ok(vec![T::default()])
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(RustSyntaxLexer, code, "rust-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Comment, firstKind("//! Crate doc"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("no_std"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/// Item doc /* nested */"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("derive"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Debug"))
        assertEquals(SyntaxTokenKind.Annotation, firstKind("Clone"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("pub"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("async"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("fn"))
        assertEquals(SyntaxTokenKind.Function, firstKind("parse"))
        assertEquals(SyntaxTokenKind.Type, firstKind("'a"))
        assertEquals(SyntaxTokenKind.Type, firstKind("'static"))
        assertEquals(SyntaxTokenKind.Type, firstKind("T"))
        assertEquals(SyntaxTokenKind.Type, firstKind("str"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Result"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Vec"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/* outer /* inner */ still comment */"))
        assertEquals(SyntaxTokenKind.String, firstKind("r\"plain\""))
        assertEquals(SyntaxTokenKind.String, firstKind("r#\"has \"quotes\"\"#"))
        assertEquals(SyntaxTokenKind.String, firstKind("r##\"a \"# b\"##"))
        assertEquals(SyntaxTokenKind.String, firstKind("br#\"bytes\"#"))
        assertEquals(SyntaxTokenKind.String, firstKind("cr#\"cstr\"#"))
        assertEquals(SyntaxTokenKind.String, firstKind("\"hi\\n\\x7F\\u{1F600}\""))
        assertEquals(SyntaxTokenKind.String, firstKind("b\"bytes\\n\""))
        assertEquals(SyntaxTokenKind.String, firstKind("c\"cstr\\n\""))
        assertEquals(SyntaxTokenKind.String, firstKind("'\\u{1F600}'"))
        assertEquals(SyntaxTokenKind.String, firstKind("'\\n'"))
        assertEquals(SyntaxTokenKind.String, firstKind("'\\x41'"))
        assertEquals(SyntaxTokenKind.String, firstKind("b'x'"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_u32"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0b1010_0101"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0o755"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1_000.5e-2f64"))
        assertEquals(SyntaxTokenKind.Number, firstKind("3.14_f32"))
        assertEquals(SyntaxTokenKind.Number, firstKind("42usize"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("true"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("false"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("..="))
        assertEquals(SyntaxTokenKind.Function, firstKind("println!"))
        assertEquals(SyntaxTokenKind.Function, firstKind("len"))
        assertEquals(SyntaxTokenKind.Function, firstKind("macro_rules!"))
        assertEquals(SyntaxTokenKind.Function, firstKind("vec!"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("::"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("->"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("=>"))
    }

    @Test
    fun handlesUnclosedRawStringsNestedCommentsAndRangesCleanly() {
        val unclosedRaw = "let s = r###\"unclosed \"# still inside"
        val rawTokens = assertLosslessInvariants(RustSyntaxLexer, unclosedRaw, "rust-unclosed-raw")
        assertEquals(SyntaxTokenKind.String, rawTokens.last().kind)

        val unclosedComment = "fn main() { /* a /* b /* c"
        val commentTokens = assertLosslessInvariants(RustSyntaxLexer, unclosedComment, "rust-unclosed-comment")
        assertEquals(SyntaxTokenKind.Comment, commentTokens.last().kind)

        val ranges = "for i in 0..10 { let x = 1.abs(); let y = 1.; }"
        val rangeTokens = assertLosslessInvariants(RustSyntaxLexer, ranges, "rust-ranges")
        assertEquals(SyntaxTokenKind.Number, rangeTokens.first { it.slice(ranges) == "0" }.kind)
        assertEquals(SyntaxTokenKind.Operator, rangeTokens.first { it.slice(ranges) == ".." }.kind)
        assertEquals(SyntaxTokenKind.Number, rangeTokens.first { it.slice(ranges) == "10" }.kind)
        assertEquals(SyntaxTokenKind.Function, rangeTokens.first { it.slice(ranges) == "abs" }.kind)
        assertEquals(SyntaxTokenKind.Number, rangeTokens.first { it.slice(ranges) == "1." }.kind)
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            #[derive(Debug)]
            pub fn demo<'a>(s: &'a str) -> &'a str {
                /* outer /* inner */ */
                let r = r##"hello "# world"##;
                println!("{}", r);
                s
            }
        """.trimIndent()
        assertPrefixTruncationSafety(RustSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(RustSyntaxLexer, seed = 0x52555354L, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val deeplyNestedUnclosed = "/* ".repeat(5_000) + "still inside comment"
        val deeplyNestedClosed = "/* ".repeat(2_000) + "inner" + " */".repeat(2_000)
        val longRawString = "r#####\"" + "a \"#### b ".repeat(3_000) + "\"#####"
        val unclosedRawString = "r#####\"" + "x \"#### ".repeat(3_000)

        assertLinearPerformance(
            lexer = RustSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-nested-comments" to deeplyNestedUnclosed,
                "closed-nested-comments" to deeplyNestedClosed,
                "long-raw-string" to longRawString,
                "unclosed-raw-string" to unclosedRawString,
            ),
        )
    }

    @Test
    fun matchesShikiAndPygmentsOracles() {
        assertAllOracleFixtures(rustOracleFixtures)
    }
}
