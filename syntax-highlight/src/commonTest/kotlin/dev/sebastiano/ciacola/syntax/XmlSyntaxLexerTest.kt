package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.XmlSyntaxLexer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlSyntaxLexerTest {

    @Test
    fun classifiesPrologDoctypeTagsAttributesEntitiesCdataAndComments() {
        val code = """
            <?xml version="1.0" encoding="utf-8"?>
            <?custom-pi target?>
            <!DOCTYPE layout>
            <LinearLayout
                android:layout_width="match_parent"
                android:orientation='vertical'>
                <!-- multi-line
                     comment -->
                <TextView text="Hello &amp; &#10; &#x20; world" />
                <![CDATA[raw <text> &stuff;]]>
            </LinearLayout>
        """.trimIndent()

        val tokens = assertLosslessInvariants(XmlSyntaxLexer, code, "xml-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.first { it.slice(code) == text && predicate(it) }.kind

        assertEquals(SyntaxTokenKind.Keyword, firstKind("<?xml"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("<?custom-pi target?>"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("<!DOCTYPE layout>"))
        assertEquals(SyntaxTokenKind.Tag, firstKind("LinearLayout"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("android:layout_width"))
        assertEquals(SyntaxTokenKind.String, firstKind("\"match_parent\""))
        assertEquals(SyntaxTokenKind.String, firstKind("'vertical'"))
        assertEquals(SyntaxTokenKind.Comment, tokens.first { it.slice(code).startsWith("<!--") }.kind)
        assertEquals(SyntaxTokenKind.Tag, firstKind("TextView"))
        assertEquals(SyntaxTokenKind.Attribute, firstKind("text"))
        assertEquals(SyntaxTokenKind.String, firstKind("<![CDATA[raw <text> &stuff;]]>"))

        val inlineRefCode = "<p>Hello &amp; &#10; &#x20;</p>"
        val inlineTokens = assertLosslessInvariants(XmlSyntaxLexer, inlineRefCode, "xml-refs")
        assertEquals(SyntaxTokenKind.Constant, inlineTokens.first { it.slice(inlineRefCode) == "&amp;" }.kind)
        assertEquals(SyntaxTokenKind.Constant, inlineTokens.first { it.slice(inlineRefCode) == "&#10;" }.kind)
        assertEquals(SyntaxTokenKind.Constant, inlineTokens.first { it.slice(inlineRefCode) == "&#x20;" }.kind)

        val malformed = "<Tag @bad=\"1\" />"
        val badTokens = assertLosslessInvariants(XmlSyntaxLexer, malformed, "xml-malformed")
        assertTrue(badTokens.any { it.slice(malformed) == "@" && it.kind == SyntaxTokenKind.Invalid })
    }

    @Test
    fun prefixTruncationAndFuzzAreLossless() {
        val sample = """
            <?xml version="1.0"?>
            <root id="1">
                <!-- note -->
                <child enabled="true">&lt;ok&gt;</child>
            </root>
        """.trimIndent()
        assertPrefixTruncationSafety(XmlSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(XmlSyntaxLexer, seed = 0x584D4C21L, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val manyElements = buildString {
            append("<root>")
            repeat(1_500) { idx ->
                append("<item id=\"").append(idx).append("\">val").append(idx).append("</item>")
            }
            append("</root>")
        }
        val hugeText = "<doc>" + "plain text &amp; ".repeat(2_000) + "</doc>"
        val unclosedAttr = "<tag " + "attr=\"val\" ".repeat(2_000)

        assertLinearPerformance(
            lexer = XmlSyntaxLexer,
            pathologicalInputs = listOf(
                "many-elements" to manyElements,
                "huge-text" to hugeText,
                "unclosed-tag" to unclosedAttr,
            ),
        )
    }

    @Test
    fun handlesStandalonePrologDtdInternalSubsetAndCdataWithDoubleBrackets() {
        val code = """
            <?xml version="1.0" encoding="utf-8" standalone="yes"?>
            <?xml-stylesheet type="text/xsl" href="style.xsl"?>
            <!DOCTYPE note [
              <!ELEMENT note (to,from)>
              <!ENTITY app "Ciacola">
            ]>
            <note xmlns:app="http://example.com/ns">
                <![CDATA[value ]] inside &amp; <notATag>]]>
                <app:item id="1" />
            </note>
        """.trimIndent()

        val tokens = assertLosslessInvariants(XmlSyntaxLexer, code, "xml-backfill")
        assertEquals(SyntaxTokenKind.Attribute, tokens.first { it.slice(code) == "standalone" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code).startsWith("<!DOCTYPE") && it.slice(code).endsWith("]>") }.kind)
        assertEquals(SyntaxTokenKind.Attribute, tokens.first { it.slice(code) == "xmlns:app" }.kind)
        assertEquals(SyntaxTokenKind.String, tokens.first { it.slice(code).startsWith("<![CDATA[") && it.slice(code).contains("]] inside") }.kind)
        assertEquals(SyntaxTokenKind.Tag, tokens.first { it.slice(code) == "app:item" }.kind)
    }
}
