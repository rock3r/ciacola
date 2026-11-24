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
    fun highlightsAllRegisteredSyntaxLanguages() {
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

        val py = "def foo(): pass"
        val pyAnnotated = py.highlight(testStyle, SyntaxLanguage.Python)
        assertEquals(py, pyAnnotated.text)
        assertTrue(pyAnnotated.spanStyles.any { it.start == 0 && it.end == 3 && it.item == testStyle.keyword })
        assertTrue(pyAnnotated.spanStyles.any { it.start == 4 && it.end == 7 && it.item == testStyle.staticFunctionCall })
        assertTrue(pyAnnotated.spanStyles.any { it.start == 11 && it.end == 15 && it.item == testStyle.keyword })

        val samples = mapOf(
            SyntaxLanguage.Kotlin to kt,
            SyntaxLanguage.Xml to xml,
            SyntaxLanguage.Json to json,
            SyntaxLanguage.Html to "<div class=\"hero\">Hi</div>",
            SyntaxLanguage.Css to ".hero { color: #ff0; }",
            SyntaxLanguage.JavaScript to "const x = 42;",
            SyntaxLanguage.TypeScript to "type Id = string;",
            SyntaxLanguage.Rust to "fn main() {}",
            SyntaxLanguage.Go to "func main() {}",
            SyntaxLanguage.Python to py,
            SyntaxLanguage.Toml to "name = \"ciacola\"",
            SyntaxLanguage.Java to "record Point(int x) {}",
            SyntaxLanguage.C to "int main(void) { return 0; }",
            SyntaxLanguage.Cpp to "template <typename T> void f(T v) {}",
            SyntaxLanguage.Glsl to "uniform vec4 uColor;",
            SyntaxLanguage.Sksl to "half4 main(float2 p) { return half4(1.0); }",
            SyntaxLanguage.Agsl to "uniform float2 iResolution;",
            SyntaxLanguage.Yaml to "name: \"ciacola\"\nport: 8080",
            SyntaxLanguage.Sql to "SELECT id FROM users WHERE active = TRUE;",
            SyntaxLanguage.Shell to "if [[ -n \"${'$'}USER\" ]]; then echo 1; fi",
            SyntaxLanguage.CSharp to "public record Point(int X, int Y);",
            SyntaxLanguage.Php to "<?php function greet(string ${'$'}s): int { return 1; }",
            SyntaxLanguage.Swift to "@MainActor struct Point { let x = 42 }",
            SyntaxLanguage.Ruby to "def greet(name) = \"Hello, #{name}!\"",
            SyntaxLanguage.Dart to "sealed class Result { final int code = 1; }",
            SyntaxLanguage.Groovy to "def greet(String s) { return \"hi ${'$'}s\" }",
            SyntaxLanguage.PowerShell to "function Get-Count { return 42 }",
            SyntaxLanguage.Dockerfile to "FROM alpine:3.19 AS base\nRUN echo \"hi\"",
            SyntaxLanguage.Diff to "--- a/Main.kt\n+++ b/Main.kt\n@@ -1 +1 @@\n-val x = 1\n+val x = 2",
            SyntaxLanguage.Markdown to "# Heading\n\nParagraph with *em* and `code`.",
            SyntaxLanguage.Protobuf to "syntax = \"proto3\";\nmessage Ping { int32 id = 1; }",
            SyntaxLanguage.Hcl to "resource \"aws_s3_bucket\" \"app\" { count = 1 }",
        )
        assertEquals(SyntaxLanguage.entries.toSet(), samples.keys)
        for ((language, snippet) in samples) {
            val annotated = snippet.highlight(testStyle, language)
            assertEquals(snippet, annotated.text)
            assertTrue(
                annotated.spanStyles.size > 1,
                "Expected highlighted spans beyond base style for $language",
            )
        }
    }

    @Test
    fun legacySyntaxTokenDelegatesRemainLossless() {
        assertEquals("val a = 1", lexKotlinSyntax("val a = 1").joinToString("") { it.slice("val a = 1") })
        assertEquals("<a/>", lexXmlSyntax("<a/>").joinToString("") { it.slice("<a/>") })
        assertEquals("{\"a\":1}", lexJsonSyntax("{\"a\":1}").joinToString("") { it.slice("{\"a\":1}") })
    }
}
