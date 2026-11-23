package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.AgslSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.GlslSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.SkslSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import dev.sebastiano.ciacola.syntax.oracle.shaderOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class ShaderSyntaxLexerTest {

    @Test
    fun preservesAllExistingAgslParserTestExpectations() {
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

        val tokens = assertLosslessInvariants(AgslSyntaxLexer, code, "agsl-sdf-circle")

        fun firstKind(text: String): SyntaxTokenKind =
            tokens.first { it.slice(code) == text }.kind

        assertEquals(SyntaxTokenKind.Keyword, firstKind("uniform"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("return"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("if"))
        assertEquals(SyntaxTokenKind.Type, firstKind("float2"))
        assertEquals(SyntaxTokenKind.Type, firstKind("half4"))
        assertEquals(SyntaxTokenKind.Function, firstKind("main"))
        assertEquals(SyntaxTokenKind.Function, firstKind("length"))
        assertEquals(SyntaxTokenKind.Property, firstKind("xy"))
        assertEquals(SyntaxTokenKind.Property, firstKind("x"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0.5"))
        assertEquals(SyntaxTokenKind.Number, firstKind(".35f"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("// Signed distance circle"))
        assertEquals(SyntaxTokenKind.Operator, firstKind("<="))
    }

    @Test
    fun classifiesGlslAndSkslPreprocessorTypesSwizzlesAndNumbers() {
        val glslCode = """
            #version 450 core
            #define SCALE 1.
            layout(location = 0) in vec2 vUv;
            uniform sampler2D uTex;
            out vec4 fragColor;
            void main() {
                vec4 c = texture(uTex, vUv);
                uint mask = 0xFFu;
                float eps = 1e-5;
                if (mask > 0u && eps > 0.0) {
                    fragColor = vec4(c.rgb * SCALE, 1.0);
                } else {
                    discard;
                }
            }
        """.trimIndent()

        val glslTokens = assertLosslessInvariants(GlslSyntaxLexer, glslCode, "glsl-features")
        assertEquals(SyntaxTokenKind.Preprocessor, glslTokens.first { it.slice(glslCode) == "version" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, glslTokens.first { it.slice(glslCode) == "layout" }.kind)
        assertEquals(SyntaxTokenKind.Type, glslTokens.first { it.slice(glslCode) == "sampler2D" }.kind)
        assertEquals(SyntaxTokenKind.Type, glslTokens.first { it.slice(glslCode) == "vec4" }.kind)
        assertEquals(SyntaxTokenKind.Function, glslTokens.first { it.slice(glslCode) == "texture" }.kind)
        assertEquals(SyntaxTokenKind.Property, glslTokens.first { it.slice(glslCode) == "rgb" }.kind)
        assertEquals(SyntaxTokenKind.Number, glslTokens.first { it.slice(glslCode) == "0xFFu" }.kind)
        assertEquals(SyntaxTokenKind.Number, glslTokens.first { it.slice(glslCode) == "1e-5" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, glslTokens.first { it.slice(glslCode) == "discard" }.kind)

        val skslCode = """
            #pragma settings(demo)
            uniform shader child;
            uniform colorFilter cf;
            uniform blender b;
            half4 main(float2 coord) {
                return child.eval(coord);
            }
        """.trimIndent()
        val skslTokens = assertLosslessInvariants(SkslSyntaxLexer, skslCode, "sksl-features")
        assertEquals(SyntaxTokenKind.Preprocessor, skslTokens.first { it.slice(skslCode) == "pragma" }.kind)
        assertEquals(SyntaxTokenKind.Type, skslTokens.first { it.slice(skslCode) == "shader" }.kind)
        assertEquals(SyntaxTokenKind.Type, skslTokens.first { it.slice(skslCode) == "colorFilter" }.kind)
        assertEquals(SyntaxTokenKind.Type, skslTokens.first { it.slice(skslCode) == "blender" }.kind)
        assertEquals(SyntaxTokenKind.Function, skslTokens.first { it.slice(skslCode) == "eval" }.kind)
    }

    @Test
    fun glslPrefixTruncationFuzzAndPerformance() {
        assertShaderDialectSafetyAndPerformance(GlslSyntaxLexer)
    }

    @Test
    fun skslPrefixTruncationFuzzAndPerformance() {
        assertShaderDialectSafetyAndPerformance(SkslSyntaxLexer)
    }

    @Test
    fun agslPrefixTruncationFuzzAndPerformance() {
        assertShaderDialectSafetyAndPerformance(AgslSyntaxLexer)
    }

    private fun assertShaderDialectSafetyAndPerformance(lexer: SyntaxLexer) {
        val sample = """
            #version 450 core
            uniform float2 iResolution;
            half4 main(float2 coord) {
                /* comment */
                return half4(coord / iResolution.xy, .35f, 1.);
            }
        """.trimIndent()

        val bigShader = buildString {
            append("#version 450 core\n")
            repeat(2_000) { idx ->
                append("float v").append(idx).append(" = .35f + 1. + 1e-5;\n")
            }
        }

        assertPrefixTruncationSafety(lexer, sample)
        assertMalformedAndFuzzSafety(lexer, seed = 0x53484452L, iterations = 60)
        assertLinearPerformance(lexer, listOf("2k-shader-lines" to bigShader))
    }

    @Test
    fun handlesAdvancedGlslSamplersSubroutinesSwizzlesAndArrayConstructors() {
        val code = """
            #version 460 core
            #extension GL_EXT_nonuniform_qualifier : require
            layout(std140, binding = 0, location = 1) uniform Block {
                dvec4 uPos;
                dmat4x3 uMat;
            };
            uniform sampler2DMS uMs;
            uniform usamplerBuffer uBuf;
            uniform isampler2DArray uIArr;
            uniform atomic_uint uCounter;
            layout(rgba8) uniform image2D uImg;
            invariant gl_Position;
            precise float gP;
            patch in float gPatch;
            sample in float gSample;
            subroutine vec4 ShadeFn(vec2 uv);
            void main() {
                float arr[3] = float[3](1.0, .5, 1.);
                half h = 0.5h;
                double lf = 1.0lf;
                vec4 c = vec4(arr[0], arr[1], arr[2], 1e-5f);
                gl_Position = c.xyzw + c.rgba + c.stpq;
            }
        """.trimIndent()

        val tokens = assertLosslessInvariants(GlslSyntaxLexer, code, "glsl-edge")
        assertEquals(SyntaxTokenKind.Preprocessor, tokens.first { it.slice(code) == "extension" }.kind)
        for (typeName in listOf("dvec4", "dmat4x3", "sampler2DMS", "usamplerBuffer", "isampler2DArray", "atomic_uint", "image2D")) {
            assertEquals(SyntaxTokenKind.Type, tokens.first { it.slice(code) == typeName }.kind)
        }
        for (kw in listOf("invariant", "precise", "patch", "sample", "subroutine")) {
            assertEquals(SyntaxTokenKind.Keyword, tokens.first { it.slice(code) == kw }.kind)
        }
        assertEquals(SyntaxTokenKind.Function, tokens.first { it.slice(code) == "ShadeFn" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == ".5" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "1." }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "0.5h" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "1.0lf" }.kind)
        assertEquals(SyntaxTokenKind.Number, tokens.first { it.slice(code) == "1e-5f" }.kind)
        for (swizzle in listOf("xyzw", "rgba", "stpq")) {
            assertEquals(SyntaxTokenKind.Property, tokens.first { it.slice(code) == swizzle }.kind)
        }

        val bomCode = "\uFEFFvoid main() {}"
        val bomTokens = assertLosslessInvariants(GlslSyntaxLexer, bomCode, "glsl-bom")
        assertEquals(SyntaxTokenKind.Whitespace, bomTokens.first { it.slice(bomCode) == "\uFEFF" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, bomTokens.first { it.slice(bomCode) == "void" }.kind)

        val bareCrCode = "#version 450\rvoid main() {}\r"
        val crTokens = assertLosslessInvariants(GlslSyntaxLexer, bareCrCode, "glsl-bare-cr-pp")
        assertEquals(SyntaxTokenKind.Preprocessor, crTokens.first { it.slice(bareCrCode) == "#" }.kind)
        assertEquals(SyntaxTokenKind.Preprocessor, crTokens.first { it.slice(bareCrCode) == "version" }.kind)
        assertEquals(SyntaxTokenKind.Preprocessor, crTokens.first { it.slice(bareCrCode) == "450" }.kind)
        assertEquals(SyntaxTokenKind.Keyword, crTokens.first { it.slice(bareCrCode) == "void" }.kind)
        assertEquals(SyntaxTokenKind.Function, crTokens.first { it.slice(bareCrCode) == "main" }.kind)
    }

    @Test
    fun matchesShikiAndPygmentsOracles() {
        assertAllOracleFixtures(shaderOracleFixtures)
    }
}
