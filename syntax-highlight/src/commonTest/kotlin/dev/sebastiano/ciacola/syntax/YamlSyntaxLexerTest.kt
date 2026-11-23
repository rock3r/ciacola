package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.YamlSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.YamlOracleFixtures
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YamlSyntaxLexerTest {

    @Test
    fun classifiesDirectivesMarkersCommentsKeysAnchorsAliasesTagsAndScalars() {
        val code = """
            %YAML 1.2
            %TAG !e! tag:example.com,2000:app/
            ---
            # Top-level comment
            app.env: prod
            a/b-c: true
            my-key: -42
            neg-float: -3.14
            neg-inf: -.inf
            pos-inf: +.inf
            nan-val: .nan
            sci: 1.5e-2
            hex: 0x2A
            oct: 0o755
            bools: [true, false, True, False, TRUE, FALSE]
            nulls: [null, Null, NULL, ~]
            date: 2026-11-23
            ts: 2026-11-23T10:45:00Z
            url: http://example.com#frag
            color-inline: color:#fff
            "a:b": 'it''s escaped'
            'c:d': "esc \n \x41 \u00E9 \U0001F600"
            ? [1, 2]
            : complex-val
            base: &base
              timeout: 30
              tagged: !!str 123
              custom: !custom val
              verb: !<tag:yaml.org,2002:str> text
            child:
              <<: *base
              flow: { a: 1, b: [2, 3], nested: { ok: true } }
            ...
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, code, "yaml-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Punctuation, firstKind("%"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("YAML"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1.2"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("TAG"))
        assertEquals(SyntaxTokenKind.Type, firstKind("!e!"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("---"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("..."))
        assertEquals(SyntaxTokenKind.Comment, firstKind("# Top-level comment"))
        assertEquals(SyntaxTokenKind.Property, firstKind("app.env"))
        assertEquals(SyntaxTokenKind.Property, firstKind("a/b-c"))
        assertEquals(SyntaxTokenKind.Property, firstKind("my-key"))
        assertEquals(SyntaxTokenKind.Number, firstKind("-42"))
        assertEquals(SyntaxTokenKind.Number, firstKind("-3.14"))
        assertEquals(SyntaxTokenKind.Number, firstKind("-.inf"))
        assertEquals(SyntaxTokenKind.Number, firstKind("+.inf"))
        assertEquals(SyntaxTokenKind.Number, firstKind(".nan"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1.5e-2"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0x2A"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0o755"))
        for (b in listOf("true", "false", "True", "False", "TRUE", "FALSE")) {
            assertEquals(SyntaxTokenKind.Constant, firstKind(b), "Expected $b to be Constant")
        }
        for (n in listOf("null", "Null", "NULL", "~")) {
            assertEquals(SyntaxTokenKind.Constant, firstKind(n), "Expected $n to be Constant")
        }
        assertEquals(SyntaxTokenKind.Constant, firstKind("2026-11-23"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("2026-11-23T10:45:00Z"))
        assertEquals(SyntaxTokenKind.String, firstKind("http://example.com#frag"))
        assertEquals(SyntaxTokenKind.String, firstKind("color:#fff"))
        assertEquals(SyntaxTokenKind.Property, firstKind("\"a:b\""))
        assertEquals(SyntaxTokenKind.String, firstKind("'it''s escaped'"))
        assertEquals(SyntaxTokenKind.Property, firstKind("'c:d'"))
        assertEquals(SyntaxTokenKind.String, firstKind("\"esc \\n \\x41 \\u00E9 \\U0001F600\""))
        assertEquals(SyntaxTokenKind.Punctuation, firstKind("?"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("&"))
        assertEquals(SyntaxTokenKind.Type, firstKind("base") { it.startIndex > code.indexOf("&base") - 1 })
        assertEquals(SyntaxTokenKind.Type, firstKind("!!str"))
        assertEquals(SyntaxTokenKind.Type, firstKind("!custom"))
        assertEquals(SyntaxTokenKind.Type, firstKind("!<tag:yaml.org,2002:str>"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("<<"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("*"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("base") { it.startIndex > code.indexOf("*base") - 1 })
        assertEquals(SyntaxTokenKind.Property, firstKind("nested"))
        assertEquals(SyntaxTokenKind.Property, firstKind("ok"))
    }

    @Test
    fun handlesBlockScalarsAndIndentationBoundaries() {
        val code = """
            root:
              lit: |
                line 1

                  indented 2
              strip: |- # trailing note
                no newline
              keep: |+
                kept
              fold: >2-
                folded line
              explicit: |+2
                two spaces
              empty_block: |
              next_key: 123
            ---
            after_marker: ok
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, code, "yaml-block-scalars")
        assertTrue(tokens.any { it.kind == SyntaxTokenKind.String && it.slice(code).contains("indented 2") })
        assertTrue(tokens.any { it.kind == SyntaxTokenKind.Comment && it.slice(code) == "# trailing note" })
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "empty_block" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "next_key" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "123" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == "---" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "after_marker" }.kind)

        val topLevelBlock = """
            |
            top line 1
            top line 2
            ...
            next: 1
        """.trimIndent()
        val topTokens = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, topLevelBlock, "yaml-top-block")
        assertTrue(topTokens.any { it.kind == SyntaxTokenKind.String && it.slice(topLevelBlock).contains("top line 2") })
        assertEquals(SyntaxTokenKind.Keyword, topTokens.first { it.slice(topLevelBlock) == "..." }.kind)
        assertEquals(SyntaxTokenKind.Property, topTokens.first { it.slice(topLevelBlock) == "next" }.kind)

        val taggedSeq = """
            items:
              - !!str |
                hello: 1 # not-a-comment
                world
              - !tag >
                folded: ok # still body
              - next: 2
        """.trimIndent()
        val taggedTokens = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, taggedSeq, "yaml-tagged-seq-block")
        assertTrue(
            taggedTokens.any { it.kind == SyntaxTokenKind.String && it.slice(taggedSeq).contains("hello: 1 # not-a-comment") },
            "Expected tagged sequence literal block body to stay String, got $taggedTokens",
        )
        assertTrue(
            taggedTokens.any { it.kind == SyntaxTokenKind.String && it.slice(taggedSeq).contains("folded: ok # still body") },
            "Expected tagged sequence folded block body to stay String, got $taggedTokens",
        )
        assertEquals(SyntaxTokenKind.Property, taggedTokens.first { it.slice(taggedSeq) == "next" }.kind)

        val anchoredSeq = "- &anc |\n  key: value # inside block\n"
        val anchoredTokens = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, anchoredSeq, "yaml-anchored-seq-block")
        assertTrue(
            anchoredTokens.any { it.kind == SyntaxTokenKind.String && it.slice(anchoredSeq) == "  key: value # inside block" },
            "Expected anchored sequence block body to be a single String token, got $anchoredTokens",
        )
    }

    @Test
    fun keepsNonBoundaryScalarsAndNonCol0MarkersAsPlainStrings() {
        val code = """
            semver: 1.2.3
            size: 42px
            phrase: 42 apples
            bool_like: true_flag
            bool_phrase: true story
            dash_word: -foo
            triple_dash_word: ---foo
            marker_in_val: ---
            dots_in_val: ...
            json_flow: {"a":1,"b":"x"}
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, code, "yaml-plain-boundaries")
        for (scalar in listOf("1.2.3", "42px", "42 apples", "true_flag", "true story", "-foo", "---foo", "---", "...")) {
            assertTrue(
                tokens.any { it.slice(code) == scalar && it.kind == SyntaxTokenKind.String },
                "Expected '$scalar' to be a single String token, got $tokens",
            )
        }
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "\"a\"" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "1" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "\"b\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"x\"" }.kind)

        val nestedFlow = "[foo [1, 2]]"
        val nestedTokens = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, nestedFlow, "yaml-flow-nested-ws")
        assertTrue(
            nestedTokens.any { it.slice(nestedFlow) == "foo" && it.kind == SyntaxTokenKind.String },
            "Expected 'foo' without trailing space in '$nestedFlow', got $nestedTokens",
        )
        assertTrue(
            nestedTokens.any { it.slice(nestedFlow) == " " && it.kind == SyntaxTokenKind.Whitespace },
            "Expected Whitespace token before '[' in '$nestedFlow', got $nestedTokens",
        )
    }

    @Test
    fun acceptsBomCrlfAndSupplementaryUnicodeLosslessly() {
        val code = "\uFEFF# BOM comment 🎉\r\nclé_𠮷: \"val 🎉\"\r\nemoji: 𠮷\r\n"
        val tokens = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, code, "yaml-unicode-crlf")
        assertEquals(SyntaxTokenKind.Whitespace, tokens.first().kind)
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code) == "# BOM comment 🎉" }.kind)
        assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == "clé_𠮷" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "\"val 🎉\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code) == "𠮷" }.kind)
    }

    @Test
    fun prefixTruncationMalformedAndFuzzAreLossless() {
        val sample = """
            %YAML 1.2
            ---
            key: |2- # note
              hello
            arr: [1, true, "a\"b"]
        """.trimIndent()
        assertPrefixTruncationSafety(YamlSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(YamlSyntaxLexer, seed = 0x59414D4CL, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val manyKeys = buildString {
            repeat(3_000) { idx ->
                append("key_").append(idx).append(": ").append(idx).append('\n')
            }
        }
        val deepFlow = "flow: " + "[".repeat(4_000) + "1" + "]".repeat(4_000)
        val hugeBlock = "block: |\n" + "  line of block text with : and # not-comment\n".repeat(3_000) + "after: true\n"
        val flowUnterminatedTags = "[" + "!<x,".repeat(8_000) + "]"
        val repeatedUnterminatedTags = "!<".repeat(8_000)
        val typedBlankLines = "k: 1" + "\n".repeat(10_000) + "next: 2\n"
        val hugeMultilinePlain = "key: a\n" + "  \"cont line\n".repeat(4_000) + "next: 1\n"
        val flowMidLinePops = "[" + "a\n  \"b,".repeat(3_000) + "c]"

        assertLinearPerformance(
            lexer = YamlSyntaxLexer,
            pathologicalInputs = listOf(
                "many-keys" to manyKeys,
                "deep-flow" to deepFlow,
                "huge-block" to hugeBlock,
                "flow-unterminated-tags" to flowUnterminatedTags,
                "repeated-unterminated-tags" to repeatedUnterminatedTags,
                "typed-blank-lines" to typedBlankLines,
                "huge-multiline-plain" to hugeMultilinePlain,
                "flow-midline-pops" to flowMidLinePops,
            ),
        )
    }

    @Test
    fun preservesPlainScalarContextAcrossContinuationLines() {
        val quotedCont = "key: a\n  \"hello\n  'world\nnext: value\n"
        val tQuoted = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, quotedCont, "yaml-plain-cont-quotes")
        assertEquals(SyntaxTokenKind.Property, tQuoted.first { it.slice(quotedCont) == "key" }.kind)
        assertEquals(SyntaxTokenKind.String, tQuoted.first { it.slice(quotedCont) == "a" }.kind)
        assertEquals(SyntaxTokenKind.String, tQuoted.first { it.slice(quotedCont) == "\"hello" }.kind)
        assertEquals(SyntaxTokenKind.String, tQuoted.first { it.slice(quotedCont) == "'world" }.kind)
        assertEquals(SyntaxTokenKind.Property, tQuoted.first { it.slice(quotedCont) == "next" }.kind)
        assertEquals(SyntaxTokenKind.String, tQuoted.first { it.slice(quotedCont) == "value" }.kind)

        val nestedOwnLine = "outer:\n  key:\n\n    a\n\n    \"hello # trailing\n  \"next\": value\n"
        val tNested = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, nestedOwnLine, "yaml-plain-cont-nested")
        assertEquals(SyntaxTokenKind.String, tNested.first { it.slice(nestedOwnLine) == "\"hello" }.kind)
        assertEquals(SyntaxTokenKind.Comment, tNested.first { it.slice(nestedOwnLine) == "# trailing" }.kind)
        assertEquals(SyntaxTokenKind.Property, tNested.first { it.slice(nestedOwnLine) == "\"next\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tNested.first { it.slice(nestedOwnLine) == "value" }.kind)

        val boolAndNumCont = "flag: true\n  \"still string\ncount: 123\n  'also string\nafter: 42\n"
        val tTyped = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, boolAndNumCont, "yaml-plain-cont-typed")
        assertEquals(SyntaxTokenKind.String, tTyped.first { it.slice(boolAndNumCont) == "true" }.kind)
        assertEquals(SyntaxTokenKind.String, tTyped.first { it.slice(boolAndNumCont) == "\"still string" }.kind)
        assertEquals(SyntaxTokenKind.String, tTyped.first { it.slice(boolAndNumCont) == "123" }.kind)
        assertEquals(SyntaxTokenKind.String, tTyped.first { it.slice(boolAndNumCont) == "'also string" }.kind)
        assertEquals(SyntaxTokenKind.Property, tTyped.first { it.slice(boolAndNumCont) == "after" }.kind)
        assertEquals(SyntaxTokenKind.Number, tTyped.first { it.slice(boolAndNumCont) == "42" }.kind)

        val seqAndFlowCont = """
            items:
              - a
                "seq cont
              - b: c
                  "nested map cont
                "sibling": 1
            flow: [x
              "flow cont,
              "real string"]
            next: true
        """.trimIndent() + "\n"
        val tSeqFlow = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, seqAndFlowCont, "yaml-plain-cont-seq-flow")
        assertEquals(SyntaxTokenKind.String, tSeqFlow.first { it.slice(seqAndFlowCont) == "\"seq cont" }.kind)
        assertEquals(SyntaxTokenKind.String, tSeqFlow.first { it.slice(seqAndFlowCont) == "\"nested map cont" }.kind)
        assertEquals(SyntaxTokenKind.Property, tSeqFlow.first { it.slice(seqAndFlowCont) == "\"sibling\"" }.kind)
        assertEquals(SyntaxTokenKind.String, tSeqFlow.first { it.slice(seqAndFlowCont) == "\"flow cont" }.kind)
        assertEquals(SyntaxTokenKind.String, tSeqFlow.first { it.slice(seqAndFlowCont) == "\"real string\"" }.kind)
        assertEquals(SyntaxTokenKind.Property, tSeqFlow.first { it.slice(seqAndFlowCont) == "next" }.kind)
        assertEquals(SyntaxTokenKind.Constant, tSeqFlow.first { it.slice(seqAndFlowCont) == "true" }.kind)

        val docMarkerStopsCont = "top\n  \"still top\n---\nnext: 1\n"
        val tDoc = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, docMarkerStopsCont, "yaml-plain-cont-doc")
        assertEquals(SyntaxTokenKind.String, tDoc.first { it.slice(docMarkerStopsCont) == "\"still top" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tDoc.first { it.slice(docMarkerStopsCont) == "---" }.kind)
        assertEquals(SyntaxTokenKind.Property, tDoc.first { it.slice(docMarkerStopsCont) == "next" }.kind)

        val crlfAndCr = "k1: a\r\n  \"crlf\r\nk2: b\r  'cr\rnext: 9\r\n"
        val tLineEndings = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, crlfAndCr, "yaml-plain-cont-crlf-cr")
        assertEquals(SyntaxTokenKind.String, tLineEndings.first { it.slice(crlfAndCr) == "\"crlf" }.kind)
        assertEquals(SyntaxTokenKind.Property, tLineEndings.first { it.slice(crlfAndCr) == "k2" }.kind)
        assertEquals(SyntaxTokenKind.String, tLineEndings.first { it.slice(crlfAndCr) == "'cr" }.kind)
        assertEquals(SyntaxTokenKind.Property, tLineEndings.first { it.slice(crlfAndCr) == "next" }.kind)
        assertEquals(SyntaxTokenKind.Number, tLineEndings.first { it.slice(crlfAndCr) == "9" }.kind)

        val bomCont = "\uFEFFkey: a\n \"bom cont\nnext: 1\n"
        val tBom = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, bomCont, "yaml-plain-cont-bom")
        assertEquals(SyntaxTokenKind.Property, tBom.first { it.slice(bomCont) == "key" }.kind)
        assertEquals(SyntaxTokenKind.String, tBom.first { it.slice(bomCont) == "\"bom cont" }.kind)
        assertEquals(SyntaxTokenKind.Property, tBom.first { it.slice(bomCont) == "next" }.kind)

        val complexKey = "? a\n  \"b\n: val\n"
        val tComplex = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, complexKey, "yaml-plain-cont-complex-key")
        assertEquals(SyntaxTokenKind.String, tComplex.first { it.slice(complexKey) == "\"b" }.kind)
        assertEquals(SyntaxTokenKind.Punctuation, tComplex.first { it.slice(complexKey) == ":" }.kind)
        assertEquals(SyntaxTokenKind.String, tComplex.first { it.slice(complexKey) == "val" }.kind)

        val anchorAndTag = "anchored: &a\n  v\n  \"w\ntagged:\n  !tag x\n  \"y\nblock: |\n  body line\nnext: 7\n"
        val tAnchorTag = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, anchorAndTag, "yaml-plain-cont-anchor-tag-block")
        assertEquals(SyntaxTokenKind.String, tAnchorTag.first { it.slice(anchorAndTag) == "\"w" }.kind)
        assertEquals(SyntaxTokenKind.Property, tAnchorTag.first { it.slice(anchorAndTag) == "tagged" }.kind)
        assertEquals(SyntaxTokenKind.Type, tAnchorTag.first { it.slice(anchorAndTag) == "!tag" }.kind)
        assertEquals(SyntaxTokenKind.String, tAnchorTag.first { it.slice(anchorAndTag) == "\"y" }.kind)
        assertEquals(SyntaxTokenKind.Property, tAnchorTag.first { it.slice(anchorAndTag) == "block" }.kind)
        assertTrue(tAnchorTag.any { it.kind == SyntaxTokenKind.String && it.slice(anchorAndTag).contains("body line") })
        assertEquals(SyntaxTokenKind.Property, tAnchorTag.first { it.slice(anchorAndTag) == "next" }.kind)
        assertEquals(SyntaxTokenKind.Number, tAnchorTag.first { it.slice(anchorAndTag) == "7" }.kind)

        val malformedContRecovery = "key: a\n  b: c\nnext: d\nmore: 1\n"
        val tMalformed = assertValidSnippetHasNoInvalidTokens(YamlSyntaxLexer, malformedContRecovery, "yaml-plain-cont-malformed-recovery")
        assertEquals(SyntaxTokenKind.Property, tMalformed.first { it.slice(malformedContRecovery) == "next" }.kind)
        assertEquals(SyntaxTokenKind.String, tMalformed.first { it.slice(malformedContRecovery) == "d" }.kind)
        assertEquals(SyntaxTokenKind.Property, tMalformed.first { it.slice(malformedContRecovery) == "more" }.kind)
        assertEquals(SyntaxTokenKind.Number, tMalformed.first { it.slice(malformedContRecovery) == "1" }.kind)
    }

    @Test
    fun matchesDualOracleFixture() {
        assertMatchesOracleFixture(YamlOracleFixtures.fixture)
    }
}
