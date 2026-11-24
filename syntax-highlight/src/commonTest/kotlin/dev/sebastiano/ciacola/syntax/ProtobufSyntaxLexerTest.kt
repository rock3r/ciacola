package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.ProtobufSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.ProtobufOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtobufSyntaxLexerTest {

    @Test
    fun matchesDualOracleFixtures() {
        assertAllOracleFixtures(ProtobufOracleFixtures.all)
    }

    @Test
    fun classifiesProto2Proto3EditionsAndAllKeywordCategories() {
        val code = """
            edition = "2024";
            package acme.orders.v1;
            import weak "legacy.proto";
            import public "common.proto";
            option features.field_presence = EXPLICIT;

            export message Order {
              reserved 2, 15, 9 to 11, 40 to max;
              reserved "legacy_id", 'old_code';
              extensions 100 to 199, 500 to max;
              optional group LineItem = 1 {
                required int32 qty = 1 [default = -1];
                optional double weight = 2 [default = 1.5e-2];
                optional sint64 delta = 3 [default = 0x1F];
                optional fixed32 mode = 4 [default = 0755];
                optional float inf_val = 5 [default = inf];
                optional float nan_val = 6 [default = nan];
              }
              oneof target {
                int64 i64 = 11;
                uint32 id = 2;
                uint64 big_id = 3;
                sint32 s32 = 4;
                fixed64 f64 = 5;
                sfixed32 sf32 = 6;
                sfixed64 sf64 = 7;
                bool active = 8;
                bytes payload = 10;
              }
              map<string, .google.protobuf.Timestamp> attrs = 12;
              repeated Status statuses = 13;
            }

            local enum Status {
              STATUS_UNSPECIFIED = 0;
              STATUS_OK = 1;
            }

            extend google.protobuf.MessageOptions {
              optional string custom_tag = 50001;
            }
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(ProtobufSyntaxLexer, code, "protobuf-all-versions")

        fun firstKind(text: String): SyntaxTokenKind =
            tokens.first { it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Keyword, firstKind("edition"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("package"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("import"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("weak"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("public"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("option"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("export"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("local"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("message"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Order"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("reserved"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("to"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("max"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("extensions"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("optional"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("group"))
        assertEquals(SyntaxTokenKind.Type, firstKind("LineItem"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("required"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("oneof"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("repeated"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("extend"))
        assertEquals(SyntaxTokenKind.Type, firstKind("Timestamp"))
        assertEquals(SyntaxTokenKind.Type, firstKind("MessageOptions"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0x1F"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0755"))
        assertEquals(SyntaxTokenKind.Number, firstKind("1.5e-2"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("inf"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("nan"))
        assertEquals(SyntaxTokenKind.Constant, firstKind("STATUS_UNSPECIFIED"))

        for (scalar in listOf(
            "double", "float", "int32", "int64", "uint32", "uint64",
            "sint32", "sint64", "fixed32", "fixed64", "sfixed32", "sfixed64",
            "bool", "string", "bytes",
        )) {
            assertEquals(SyntaxTokenKind.Type, firstKind(scalar), "Expected scalar $scalar to be Type")
        }
    }

    @Test
    fun disambiguatesContextualKeywordsAndRpcStreamsAndTextprotoAggregates() {
        val code = """
            syntax = "proto3";
            message Contextual {
              string message = 1;
              int32 option = 2;
              bool syntax = 3;
              string stream = 4;
              int32 to = 5;
              string map = 6;
              int32 max = 7;
              string edition = 8;
              string optional = 9;
              int32 group = 10;
            }
            service StreamService {
              rpc stream(stream Req) returns (stream Res) {
                option (google.api.http) = {
                  get: "/v1/{name=items/*}"
                  [my.ext]: [1, 2]
                };
              }
            }
        """.trimIndent() + "\n"

        val tokens = assertValidSnippetHasNoInvalidTokens(ProtobufSyntaxLexer, code, "protobuf-contextual")

        val fieldBlockStart = code.indexOf("string message")
        val fieldBlockEnd = code.indexOf("service StreamService")
        val fieldSectionTokens = tokens.filter { it.startIndex in fieldBlockStart until fieldBlockEnd }
        for (fieldName in listOf("message", "option", "syntax", "stream", "to", "map", "max", "edition", "optional", "group")) {
            val tok = fieldSectionTokens.first { it.slice(code) == fieldName }
            assertEquals(SyntaxTokenKind.Plain, tok.kind, "Contextual field name '$fieldName' must be Plain")
        }

        val rpcLineStart = code.indexOf("rpc stream")
        val rpcTokens = tokens.filter { it.startIndex >= rpcLineStart }
        val streamTokens = rpcTokens.filter { it.slice(code) == "stream" }
        assertEquals(3, streamTokens.size)
        assertEquals(SyntaxTokenKind.Function, streamTokens[0].kind)
        assertEquals(SyntaxTokenKind.Keyword, streamTokens[1].kind)
        assertEquals(SyntaxTokenKind.Keyword, streamTokens[2].kind)

        val getTok = rpcTokens.first { it.slice(code) == "get" }
        assertEquals(SyntaxTokenKind.Property, getTok.kind)

        val angleOptionCode = """
            option (my.opt) = {
              sub_msg: <
                name: "foo"
                enabled: true
              >
              other_msg <
                count: 42
              >
            };
            option (top.angle) = < flag: false >;
        """.trimIndent() + "\n"
        val angleTokens = assertValidSnippetHasNoInvalidTokens(ProtobufSyntaxLexer, angleOptionCode, "protobuf-angle-options")
        for (prop in listOf("sub_msg", "name", "enabled", "other_msg", "count", "flag")) {
            assertEquals(
                SyntaxTokenKind.Property,
                angleTokens.first { it.slice(angleOptionCode) == prop }.kind,
                "Expected textproto field '$prop' inside angle-bracket message to be Property",
            )
        }
        assertEquals(SyntaxTokenKind.Constant, angleTokens.first { it.slice(angleOptionCode) == "true" }.kind)
        assertEquals(SyntaxTokenKind.Constant, angleTokens.first { it.slice(angleOptionCode) == "false" }.kind)

        val importOptionCode = "edition = \"2024\";\nimport option \"foo.proto\";\n"
        val importOptionTokens = assertValidSnippetHasNoInvalidTokens(ProtobufSyntaxLexer, importOptionCode, "protobuf-import-option")
        assertEquals(SyntaxTokenKind.Keyword, importOptionTokens.first { it.slice(importOptionCode) == "import" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, importOptionTokens.first { it.slice(importOptionCode) == "option" }.kind)

        val unclosedScopesCode = """
            message Broken {
              string a = 1 [deprecated = true;
              option (unclosed.opt = "x";
              map<string, int32 broken = 2;
            }
            message After { int32 ok = 1; }
        """.trimIndent() + "\n"
        val unclosedScopeTokens = assertValidSnippetHasNoInvalidTokens(ProtobufSyntaxLexer, unclosedScopesCode, "protobuf-unclosed-scopes")
        val afterStart = unclosedScopesCode.indexOf("message After")
        val afterTokens = unclosedScopeTokens.filter { it.startIndex >= afterStart }
        assertEquals(SyntaxTokenKind.Keyword, afterTokens.first { it.slice(unclosedScopesCode) == "message" }.kind)
        assertEquals(SyntaxTokenKind.Type, afterTokens.first { it.slice(unclosedScopesCode) == "After" }.kind)
    }

    @Test
    fun handlesEscapedAdjacentStringsUnclosedCommentsAndSurrogates() {
        val loneHigh = 0xD800.toChar()
        val loneLow = 0xDC00.toChar()
        val escaped = """
            option desc = "hex \x41 \u00E9 \U0001F600 \" \\" 'single \' \101';
        """.trimIndent()
        assertValidSnippetHasNoInvalidTokens(ProtobufSyntaxLexer, escaped, "protobuf-strings")

        val samples = listOf(
            "\uFEFF// header 🎉\r\nsyntax = \"proto3\";\r\n/* unclosed block",
            "option x = \"𠮷$loneHigh\";\r\n// ${loneLow}tail",
            "/* unclosed comment at EOF",
        )
        for ((idx, sample) in samples.withIndex()) {
            assertLosslessInvariants(ProtobufSyntaxLexer, sample, "protobuf-malformed#$idx")
        }

        // Verify unclosed block comment is one Comment token.
        val unclosedCommentTokens = ProtobufSyntaxLexer.lex("/* unclosed")
        assertEquals(1, unclosedCommentTokens.size)
        assertEquals(SyntaxTokenKind.Comment, unclosedCommentTokens.single().kind)
    }

    @Test
    fun satisfiesPrefixTruncationAndFuzzSafety() {
        assertPrefixTruncationSafety(ProtobufSyntaxLexer, ProtobufOracleFixtures.proto3AndEditions.code, maxStep = 4)
        assertPrefixTruncationSafety(ProtobufSyntaxLexer, ProtobufOracleFixtures.proto2GroupsAndExtensions.code, maxStep = 4)
        assertMalformedAndFuzzSafety(ProtobufSyntaxLexer, seed = 0x912070BL, iterations = 40)
    }

    @Test
    fun lexesPathologicalInputsInLinearTime() {
        assertLinearPerformance(
            lexer = ProtobufSyntaxLexer,
            pathologicalInputs = listOf(
                "unclosed-block-comment-8k" to ("/* " + "nested /* text ".repeat(2_000)),
                "unclosed-string-8k" to ("option a = \"" + "\\\"abc".repeat(2_000)),
                "nested-braces-8k" to ("option (a) = " + "{ a: ".repeat(1_500) + "1" + " }".repeat(1_500) + ";"),
            ),
            maxMillisPerInput = 300L,
        )
    }
}
