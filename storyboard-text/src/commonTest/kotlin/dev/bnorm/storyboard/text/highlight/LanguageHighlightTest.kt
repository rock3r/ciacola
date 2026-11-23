package dev.bnorm.storyboard.text.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageHighlightTest {

    private val testStyle = CodeStyle.build(SpanStyle(color = Color(0xFFBCBEC4))) {
        keyword = simple + SpanStyle(color = Color(0xFFCF8E6D))
        string = simple + SpanStyle(color = Color(0xFF6AAB73))
        comment = simple + SpanStyle(color = Color(0xFF7A7E85))
        number = simple + SpanStyle(color = Color(0xFF2AACB8))
        property = simple + SpanStyle(color = Color(0xFFC77DBB))
        typeParameters = simple + SpanStyle(color = Color(0xFF16BAAC))
        staticFunctionCall = simple + SpanStyle(color = Color(0xFF56A8F5))
        annotation = simple + SpanStyle(color = Color(0xFFB3AE60))
        punctuation = simple + SpanStyle(color = Color(0xFFA3A6AD))
    }

    @Test
    fun highlightsKotlinXmlJsonAndFallsBackForUnregisteredLanguages() {
        val kt = "val x: Int = 42"
        val ktAnnotated = kt.highlight(testStyle, SyntaxLanguage.Kotlin)
        assertEquals(kt, ktAnnotated.text)
        assertTrue(ktAnnotated.spanStyles.any { it.start == 0 && it.end == 3 && it.item == testStyle.keyword })
        assertTrue(ktAnnotated.spanStyles.any { it.start == 7 && it.end == 10 && it.item == testStyle.typeParameters })
        assertTrue(ktAnnotated.spanStyles.any { it.start == 13 && it.end == 15 && it.item == testStyle.number })

        val json = """{"key": "val"}"""
        val jsonAnnotated = json.highlight(testStyle, SyntaxLanguage.Json)
        assertEquals(json, jsonAnnotated.text)
        assertTrue(jsonAnnotated.spanStyles.any { it.start == 1 && it.end == 6 && it.item == testStyle.property })
        assertTrue(jsonAnnotated.spanStyles.any { it.start == 8 && it.end == 13 && it.item == testStyle.string })

        val xml = """<Tag attr="1"/>"""
        val xmlAnnotated = xml.highlight(testStyle, SyntaxLanguage.Xml)
        assertEquals(xml, xmlAnnotated.text)
        assertTrue(xmlAnnotated.spanStyles.any { it.start == 1 && it.end == 4 && it.item == testStyle.keyword })
        assertTrue(xmlAnnotated.spanStyles.any { it.start == 5 && it.end == 9 && it.item == testStyle.annotation })

        // Unregistered language falls back cleanly to base simple style
        val py = "def foo(): pass"
        val pyAnnotated = py.highlight(testStyle, SyntaxLanguage.Python)
        assertEquals(py, pyAnnotated.text)
        assertEquals(1, pyAnnotated.spanStyles.size)
        assertEquals(testStyle.simple, pyAnnotated.spanStyles.single().item)
    }

    @Test
    fun legacySyntaxTokenDelegatesRemainLossless() {
        assertEquals("val a = 1", lexKotlinSyntax("val a = 1").joinToString("") { it.slice("val a = 1") })
        assertEquals("<a/>", lexXmlSyntax("<a/>").joinToString("") { it.slice("<a/>") })
        assertEquals("{\"a\":1}", lexJsonSyntax("{\"a\":1}").joinToString("") { it.slice("{\"a\":1}") })
    }
}
