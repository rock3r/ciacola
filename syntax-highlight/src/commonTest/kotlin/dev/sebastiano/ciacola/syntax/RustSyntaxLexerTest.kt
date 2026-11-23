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
        val denseMacrosAndTuples = "vec![1]; t.0.1;\n".repeat(3_500)

        assertLinearPerformance(
            lexer = RustSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-nested-comments" to deeplyNestedUnclosed,
                "closed-nested-comments" to deeplyNestedClosed,
                "long-raw-string" to longRawString,
                "unclosed-raw-string" to unclosedRawString,
                "dense-macros-and-tuples" to denseMacrosAndTuples,
            ),
        )
    }

    @Test
    fun handlesAdvancedEdgeCasesTupleChainingHrtbTurbofishConstGenericsAndRawIdents() {
        val code = """
            #![feature(generic_const_exprs)]
            #[cfg_attr(all(unix, not(target_os = "macos")), inline)]
            /*! inner block doc */
            unsafe extern "C" fn hrtb<'a, F, T, const N: usize>(
                f: F,
                arr: [T; N],
                tup: (i32, i32),
            ) -> i32
            where
                F: for<'b> Fn(&'b str) -> &'b str + Send + 'static,
            {
                let v = Vec::<i32>::new();
                let d: &(dyn Send + 'static) = &v;
                let a = b'\x7f';
                let b = b'\\';
                let c = '\u{10FFFF}';
                let y = tup.0.1;
                let r = 'outer: loop {
                    break 'outer r#match(42);
                };
                r + y + (a as i32) + (b as i32) + (c as i32)
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(RustSyntaxLexer, code, "rust-edge")
        assertEquals(SyntaxTokenKind.Annotation, tokens.first { it.slice(code) == "feature" }.kind)
        assertEquals(SyntaxTokenKind.Annotation, tokens.first { it.slice(code) == "cfg_attr" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code) == "/*! inner block doc */" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "unsafe" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "extern" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "dyn" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "Fn" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "'b" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "T" && it.startIndex > code.indexOf("arr:") }.kind)
        assertEquals(SyntaxTokenKind.Constant, tokens.first { it.slice(code) == "N" && it.startIndex > code.indexOf("arr:") }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "b'\\x7f'" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "b'\\\\'" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "'\\u{10FFFF}'" }.kind)
        assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == "'outer" }.kind)
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "r#match" }.kind)

        // Ensure `tup.0.1` splits into `tup`, `.`, `0`, `.`, `1` rather than float `0.1`
        val tupStart = code.indexOf("tup.0.1")
        val tupTokens = tokens.filter { it.startIndex >= tupStart && it.endIndex <= tupStart + "tup.0.1".length }
        assertEquals(
            listOf(
                SyntaxTokenKind.Plain to "tup",
                SyntaxTokenKind.Punctuation to ".",
                SyntaxTokenKind.Number to "0",
                SyntaxTokenKind.Punctuation to ".",
                SyntaxTokenKind.Number to "1",
            ),
            tupTokens.map { it.kind to it.slice(code) },
        )
    }

    @Test
    fun resetsAngleDepthAfterComparisonsAndSupportsSupplementaryUnicodeIdentifiers() {
        val comparisonCode = """
            fn f(x: i32) {
                if x < 0 { return; }
                let y = X + 1;
                let _ = Vec::<Result<T, E>>::new();
                let Z = 2;
            }
        """.trimIndent()
        val cmpTokens = assertLosslessInvariants(RustSyntaxLexer, comparisonCode, "rust-angle-reset")
        assertEquals(SyntaxTokenKind.Plain, cmpTokens.first { it.slice(comparisonCode) == "X" }.kind)
        assertEquals(SyntaxTokenKind.Type, cmpTokens.first { it.slice(comparisonCode) == "T" }.kind)
        assertEquals(SyntaxTokenKind.Type, cmpTokens.first { it.slice(comparisonCode) == "E" }.kind)
        assertEquals(SyntaxTokenKind.Plain, cmpTokens.first { it.slice(comparisonCode) == "Z" }.kind)

        val unicodeCode = "let café = 1; let e\u0301 = 2; let 𠮷 = 3; let 𝐀 = 4; let call = 1.𠀡();"
        val uniTokens = assertLosslessInvariants(RustSyntaxLexer, unicodeCode, "rust-unicode")
        assertEquals(0, uniTokens.count { it.kind == SyntaxTokenKind.Invalid })
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "café" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "e\u0301" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "𠮷" }.kind)
        assertEquals(SyntaxTokenKind.Plain, uniTokens.first { it.slice(unicodeCode) == "𝐀" }.kind)
        val dotCallStart = unicodeCode.indexOf("1.𠀡()")
        val dotCallTokens = uniTokens.filter { it.startIndex >= dotCallStart && it.endIndex <= dotCallStart + "1.𠀡()".length }
        assertEquals(
            listOf(
                SyntaxTokenKind.Number to "1",
                SyntaxTokenKind.Punctuation to ".",
                SyntaxTokenKind.Function to "𠀡",
                SyntaxTokenKind.Punctuation to "(",
                SyntaxTokenKind.Punctuation to ")",
            ),
            dotCallTokens.map { it.kind to it.slice(unicodeCode) },
        )
    }

    @Test
    fun matchesShikiAndPygmentsOracles() {
        assertAllOracleFixtures(rustOracleFixtures)
    }
}
