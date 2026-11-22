package dev.bnorm.storyboard

import dev.bnorm.storyboard.ui.Annotation
import dev.bnorm.storyboard.ui.Comment
import dev.bnorm.storyboard.ui.Function
import dev.bnorm.storyboard.ui.Keyword
import dev.bnorm.storyboard.ui.Number
import dev.bnorm.storyboard.ui.Property
import dev.bnorm.storyboard.ui.String as StringColor
import dev.bnorm.storyboard.ui.Type
import dev.bnorm.storyboard.ui.color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntaxParserTest {

    private fun assertLosslessAndWellFormed(code: String, language: Language): List<Token> {
        val tokens = parse(code, language)
        if (code.isEmpty()) {
            assertTrue(tokens.isEmpty(), "Empty input should emit zero tokens")
            return tokens
        }
        val normalizedLines = code.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (token in tokens) {
            assertFalse('\n' in token.content, "Token content must not contain LF: $token")
            assertFalse('\r' in token.content, "Token content must not contain CR: $token")
            assertEquals(
                token.content.length,
                token.endIndex - token.startIndex,
                "Token index span must match content length: $token",
            )
            assertTrue(
                token.lineNumber in normalizedLines.indices,
                "Token lineNumber out of bounds (${token.lineNumber} not in ${normalizedLines.indices}): $token",
            )
        }
        val byLine = tokens.groupBy { it.lineNumber }
        assertEquals(normalizedLines.indices.toSet(), byLine.keys, "Every line must emit at least one token")
        for ((lineIdx, expectedLine) in normalizedLines.withIndex()) {
            val lineTokens = byLine.getValue(lineIdx).sortedBy { it.startIndex }
            val reconstructed = lineTokens.joinToString(separator = "") { it.content }
            assertEquals(expectedLine, reconstructed, "Lossless reconstruction failed on line $lineIdx for $language")
            var prevEnd = 0
            for (t in lineTokens) {
                assertEquals(prevEnd, t.startIndex, "Non-contiguous token offsets on line $lineIdx: $lineTokens")
                prevEnd = t.endIndex
            }
            assertEquals(expectedLine.length, prevEnd, "Line end offset mismatch on line $lineIdx")
        }
        return tokens
    }

    @Test
    fun kotlinParserClassifiesKeywordsModifiersAnnotationsTypesCallsStringsAndComments() {
        val code = """
            @file:JvmName("Demo")
            package dev.sebastiano.demo

            import androidx.compose.ui.Modifier

            @OptIn(ExperimentalApi::class)
            data class User(
                val name: String = "Ada\n",
                val active: Boolean = true,
            ) : Entity() {
                private suspend inline fun <reified T : Any> greet(
                    data: Int,
                    label: String = "Hi ${'$'}name, ${'$'}{data + 1}",
                ) {
                    val x = data
                    /* outer /* nested */ comment */
                    val hex = 0xFF_00
                    if (name !in listOf("a", "b") && this != null) {
                        println(text = x.toString())
                        loop@ for (i in 0..1) {
                            return@loop
                        }
                    }
                }
            }
        """.trimIndent()

        val tokens = assertLosslessAndWellFormed(code, Kotlin)

        fun firstToken(content: String, predicate: (Token) -> Boolean = { true }): Token =
            tokens.first { it.content == content && predicate(it) }

        assertEquals(Annotation, firstToken("file").color())
        assertEquals(Annotation, firstToken("JvmName").color())
        assertEquals(Annotation, firstToken("OptIn").color())
        assertEquals(Keyword, firstToken("class") { it.lineNumber == 5 }.color(), "::class inside parens")
        assertEquals(Keyword, firstToken("package").color())
        assertEquals(Keyword, firstToken("import").color())
        assertEquals("source.kotlin", firstToken("androidx").scope, "Import path segment stays plain")
        assertEquals(Type, firstToken("Modifier").color())

        assertEquals(Keyword, firstToken("data") { it.lineNumber == 6 }.color(), "data modifier before class")
        assertEquals(Keyword, firstToken("class") { it.lineNumber == 6 }.color())
        assertEquals(Type, firstToken("User").color())
        assertEquals(Type, firstToken("String").color())
        assertEquals(StringColor, firstToken("Ada").color())
        assertEquals(Type, firstToken("Entity").color(), "Supertype constructor stays Type")

        assertEquals(Keyword, firstToken("private").color())
        assertEquals(Keyword, firstToken("suspend").color())
        assertEquals(Keyword, firstToken("inline").color())
        assertEquals(Keyword, firstToken("fun").color())
        assertEquals(Keyword, firstToken("reified").color())
        assertEquals(Type, firstToken("T").color())
        assertEquals(Function, firstToken("greet").color())

        // Soft keyword `data` used as parameter / variable name must NOT be highlighted as a keyword.
        assertEquals("source.kotlin", firstToken("data") { it.lineNumber == 11 }.scope)
        assertEquals("source.kotlin", firstToken("data") { it.lineNumber == 14 }.scope)

        assertEquals(Comment, firstToken("nested").color(), "Nested block comment stays Comment")
        assertEquals(Number, firstToken("0xFF_00").color())
        assertEquals(Keyword, firstToken("this").color(), "`this` inside parens stays Keyword")
        assertEquals(Function, firstToken("println").color())
        assertEquals(Property, firstToken("text").color(), "Named argument highlighted as Property")
        assertEquals(Function, firstToken("toString").color())
        assertEquals(Annotation, firstToken("loop") { it.lineNumber == 19 }.color(), "Trailing label highlighted")
        assertEquals(Keyword, firstToken("return").color())
    }

    @Test
    fun kotlinParserHandlesMultiLineCommentsRawStringsBlankLinesCrlfAndPartialSnippets() {
        val multiline = "\r\n/* line 1\r\n   line 2 */\r\nval s = \"\"\"\r\n  hello\r\n  world\r\n\"\"\"\r\n"
        val tokens = assertLosslessAndWellFormed(multiline, Kotlin)
        assertEquals(Comment, tokens.first { it.content == "line" && it.lineNumber == 1 }.color())
        assertEquals(Comment, tokens.first { it.content == "line" && it.lineNumber == 2 }.color())
        assertEquals(StringColor, tokens.first { it.content == "hello" }.color())
        assertEquals(StringColor, tokens.first { it.content == "world" }.color())

        // Partial / malformed code must still tokenize cleanly without throwing or losing text.
        val partial = "fun broken(x: Int = /* unclosed\n  val y = \"unterminated"
        assertLosslessAndWellFormed(partial, Kotlin)
    }

    @Test
    fun xmlParserPreservesSkippedWhitespaceAndDoctypeAndClassifiesTagsAttributesAndStrings() {
        val code = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE layout>
            <LinearLayout
                android:layout_width="match_parent"
                android:orientation="vertical">
                <!-- multi-line
                     comment -->
                <TextView text="Hello &amp; &#10; world" />
                <![CDATA[raw <text>]]>
            </LinearLayout>
        """.trimIndent()

        val tokens = assertLosslessAndWellFormed(code, Xml)
        fun firstToken(content: String, predicate: (Token) -> Boolean = { true }): Token =
            tokens.first { it.content == content && predicate(it) }

        assertEquals(Keyword, firstToken("xml").color())
        assertEquals(Keyword, firstToken("DOCTYPE").color())
        assertEquals("entity.name.tag.xml", firstToken("LinearLayout") { it.lineNumber == 2 }.scope)
        assertEquals(Keyword, firstToken("LinearLayout") { it.lineNumber == 2 }.color())
        assertEquals("entity.name.tag.xml", firstToken("LinearLayout") { it.lineNumber == 9 }.scope)
        assertEquals("entity.other.attribute-name.xml", firstToken("layout_width").scope)
        assertEquals(Annotation, firstToken("layout_width").color())
        assertEquals(StringColor, firstToken("match_parent").color())
        assertEquals(Comment, firstToken("multi").color())
        assertEquals(Comment, firstToken("comment").color())
        assertEquals("entity.name.tag.xml", firstToken("TextView").scope)
        assertEquals(Annotation, firstToken("text") { it.lineNumber == 7 }.color())
        assertEquals(StringColor, firstToken("Hello").color())

        // Unrecognized characters inside an XML tag are recovered losslessly as invalid tokens.
        val malformed = "<Tag @bad=\"1\" />"
        val badTokens = assertLosslessAndWellFormed(malformed, Xml)
        assertTrue(badTokens.any { it.content == "@" && it.scope.contains("invalid") })
    }

    @Test
    fun jsonParserDistinguishesPropertyKeysFromStringValuesAndSupportsJsonc() {
        val code = """
            {
              // line comment
              "title": "Ciacola",
              "count": -42,
              "ratio": 3.14e-2,
              "enabled": true,
              "empty": null,
              /* block
                 comment */
              "items": ["one", "two"]
            }
        """.trimIndent()

        val tokens = assertLosslessAndWellFormed(code, Json)
        fun firstToken(content: String): Token = tokens.first { it.content == content }

        assertEquals(Comment, firstToken("comment").color())
        assertEquals(Property, firstToken("title").color())
        assertEquals(StringColor, firstToken("Ciacola").color())
        assertEquals(Property, firstToken("count").color())
        assertEquals(Number, firstToken("42").color())
        assertEquals(Number, firstToken("3").color())
        assertEquals("constant.language.json", firstToken("true").scope)
        assertEquals("constant.language.json", firstToken("null").scope)
        assertEquals(Property, firstToken("items").color())
        assertEquals(StringColor, firstToken("one").color())

        val invalid = """{"ok": undefined}"""
        val invalidTokens = assertLosslessAndWellFormed(invalid, Json)
        assertTrue(invalidTokens.any { it.content == "undefined" && it.scope.contains("invalid") })
    }

    @Test
    fun buildDiffListWorksAcrossKotlinXmlAndJsonWithoutTm4eOrShiki() {
        val ktDiffs = buildDiffList(
            listOf(BasicCode("val x = 1\n\nprintln(x)"), BasicCode("val x = 2\n\nprintln(x)")),
            Kotlin,
        )
        assertEquals(2, ktDiffs.size)
        assertEquals(3, ktDiffs[0].rows.size)
        assertEquals(3, ktDiffs[1].rows.size)

        val xmlDiffs = buildDiffList(
            listOf(BasicCode("<Box a=\"1\"/>"), BasicCode("<Box a=\"2\"/>")),
            Xml,
        )
        assertEquals(2, xmlDiffs.size)
        assertEquals(1, xmlDiffs[0].rows.size)

        val jsonDiffs = buildDiffList(
            listOf(BasicCode("{\n  \"a\": 1\n}"), BasicCode("{\n  \"a\": 2\n}")),
            Json,
        )
        assertEquals(2, jsonDiffs.size)
        assertEquals(3, jsonDiffs[0].rows.size)
    }
}
