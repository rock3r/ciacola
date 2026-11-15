package dev.bnorm.storyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgslParserTest {
    @Test
    fun parsesKeywordsTypesFunctionsSwizzlesNumbersCommentsAndBlankLines() {
        val code = """
            uniform float2 iResolution;

            // Signed distance circle
            half4 main(float2 coord) {
                float2 uv = coord / iResolution.xy - 0.5;
                float d = length(uv) - .35f;
                if (d <= 0.0) return half4(1.0);
                return half4(uv.x, uv.y, 0.0, 1.0);
            }
        """.trimIndent()

        val tokens = AgslParser.parse(code)
        val lines = code.lines()

        // Every line index 0 until lineCount must have >= 1 token (including blank line 1).
        for (lineIdx in lines.indices) {
            val lineTokens = tokens.filter { it.lineNumber == lineIdx }
            assertTrue(lineTokens.isNotEmpty(), "Expected >= 1 token on line $lineIdx")
            assertEquals(lines[lineIdx], lineTokens.joinToString("") { it.content })
        }

        fun scopeOf(content: String): String =
            tokens.first { it.content == content }.scope

        assertEquals("keyword.control.agsl", scopeOf("uniform"))
        assertEquals("keyword.control.agsl", scopeOf("return"))
        assertEquals("keyword.control.agsl", scopeOf("if"))
        assertEquals("storage.type.agsl", scopeOf("float2"))
        assertEquals("storage.type.agsl", scopeOf("half4"))
        assertEquals("entity.name.function.agsl", scopeOf("main"))
        assertEquals("entity.name.function.agsl", scopeOf("length"))
        assertEquals("variable.other.property.agsl", scopeOf("xy"))
        assertEquals("variable.other.property.agsl", scopeOf("x"))
        assertEquals("constant.numeric.agsl", scopeOf("0.5"))
        assertEquals("constant.numeric.agsl", scopeOf(".35f"))
        assertEquals("comment.line.double-slash.agsl", scopeOf("// Signed distance circle"))
        assertEquals("punctuation.agsl", scopeOf("<="))
        assertTrue(tokens.all { it.language === Agsl })
    }
}
