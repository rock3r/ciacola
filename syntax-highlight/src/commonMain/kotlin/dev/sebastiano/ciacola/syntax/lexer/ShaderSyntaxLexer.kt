package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.glsl.GlslLexer
import dev.sebastiano.ciacola.syntax.antlr.glsl.GlslLexer.Tokens as GlslTokens
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName
import org.antlr.v4.kotlinruntime.CharStreams

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for GLSL shaders.
 */
object GlslSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Glsl

    override fun lex(source: String): List<SyntaxToken> =
        lexShader(source, GLSL_TYPES)
}

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Skia Shading Language (SkSL).
 */
object SkslSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Sksl

    override fun lex(source: String): List<SyntaxToken> =
        lexShader(source, SKSL_AGSL_TYPES)
}

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Android Graphics Shading Language (AGSL).
 */
object AgslSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Agsl

    override fun lex(source: String): List<SyntaxToken> =
        lexShader(source, SKSL_AGSL_TYPES)
}

private class RawShaderToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun lexShader(source: String, types: Set<String>): List<SyntaxToken> {
    if (source.isEmpty()) return emptyList()

    val lexer = GlslLexer(CharStreams.fromString(source))
    val raw = ArrayList<RawShaderToken>(source.length / 3 + 8)

    driveAntlrLexer(
        source = source,
        lexer = lexer,
        onGap = { start, end ->
            emitDefaultGap(source, start, end) { kind, s, e ->
                raw += RawShaderToken(
                    type = if (kind == SyntaxTokenKind.Whitespace) GlslTokens.WS else GlslTokens.ERROR_CHAR,
                    start = s,
                    end = e,
                    kind = kind,
                    isHidden = kind == SyntaxTokenKind.Whitespace,
                )
            }
        },
        onToken = { type, start, end -> emitShaderRawToken(type, start, end, raw) },
    )

    classifyShaderRawTokens(source, raw, types)
    return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
}

private fun emitShaderRawToken(
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawShaderToken>,
) {
    when (type) {
        GlslTokens.WS,
        GlslTokens.PP_WS,
        GlslTokens.PP_NL,
        -> out += RawShaderToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        GlslTokens.LINE_COMMENT,
        GlslTokens.BLOCK_COMMENT,
        GlslTokens.UNTERMINATED_BLOCK_COMMENT,
        GlslTokens.PP_LINE_COMMENT,
        GlslTokens.PP_BLOCK_COMMENT,
        -> out += RawShaderToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        GlslTokens.PP_HASH,
        GlslTokens.PP_LINE_CONT,
        GlslTokens.PP_TEXT,
        -> out += RawShaderToken(type, start, end, SyntaxTokenKind.Preprocessor, isHidden = false)

        GlslTokens.HEX_INT,
        GlslTokens.OCT_INT,
        GlslTokens.FLOAT,
        GlslTokens.DEC_INT,
        -> out += RawShaderToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        GlslTokens.ERROR_CHAR -> out += RawShaderToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> {
            val kind = when {
                isShaderOperator(type) -> SyntaxTokenKind.Operator
                isShaderPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawShaderToken(type, start, end, kind, isHidden = false)
        }
    }
}

private fun classifyShaderRawTokens(
    code: String,
    raw: List<RawShaderToken>,
    types: Set<String>,
) {
    val visible = ArrayList<Int>(raw.size)
    for (idx in raw.indices) {
        if (!raw[idx].isHidden) visible += idx
    }

    for (i in visible.indices) {
        val tok = raw[visible[i]]
        if (tok.type != GlslTokens.IDENT) continue
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val text = code.substring(tok.start, tok.end)
        val prevText = if (prev != null && prev.type == GlslTokens.IDENT) code.substring(prev.start, prev.end) else null

        tok.kind = when {
            text in SHADER_CONSTANTS -> SyntaxTokenKind.Constant
            text in SHADER_KEYWORDS -> SyntaxTokenKind.Keyword
            text in types && prev?.type != GlslTokens.DOT -> SyntaxTokenKind.Type
            prevText == "struct" -> SyntaxTokenKind.Type
            next?.type == GlslTokens.LPAREN -> SyntaxTokenKind.Function
            prev?.type == GlslTokens.DOT -> SyntaxTokenKind.Property
            looksLikeTypeName(text) -> SyntaxTokenKind.Type
            text.length > 1 && text[0].isUpperCase() && text.all { it.isUpperCase() || it.isDigit() || it == '_' } ->
                SyntaxTokenKind.Constant
            else -> SyntaxTokenKind.Plain
        }
    }
}

private val SHADER_CONSTANTS = setOf("true", "false")

private val SHADER_KEYWORDS = setOf(
    "uniform", "layout", "in", "out", "inout", "const", "varying", "attribute",
    "flat", "smooth", "noperspective", "centroid", "precision", "highp", "mediump", "lowp",
    "buffer", "shared", "coherent", "readonly", "writeonly", "volatile", "restrict",
    "invariant", "precise", "patch", "sample", "subroutine",
    "discard", "struct", "if", "else", "for", "while", "do", "switch", "case", "default",
    "break", "continue", "return", "void",
)

private val GLSL_TYPES = setOf(
    "float", "double", "int", "uint", "bool",
    "vec2", "vec3", "vec4",
    "ivec2", "ivec3", "ivec4",
    "uvec2", "uvec3", "uvec4",
    "bvec2", "bvec3", "bvec4",
    "dvec2", "dvec3", "dvec4",
    "mat2", "mat3", "mat4",
    "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3", "mat3x4", "mat4x2", "mat4x3", "mat4x4",
    "dmat2", "dmat3", "dmat4",
    "dmat2x2", "dmat2x3", "dmat2x4", "dmat3x2", "dmat3x3", "dmat3x4", "dmat4x2", "dmat4x3", "dmat4x4",
    "sampler1D", "sampler2D", "sampler3D", "samplerCube", "sampler2DRect", "samplerBuffer",
    "sampler1DShadow", "sampler2DShadow", "samplerCubeShadow", "sampler2DRectShadow",
    "sampler1DArray", "sampler2DArray", "samplerCubeArray",
    "sampler1DArrayShadow", "sampler2DArrayShadow", "samplerCubeArrayShadow",
    "sampler2DMS", "sampler2DMSArray",
    "isampler1D", "isampler2D", "isampler3D", "isamplerCube", "isampler2DRect", "isamplerBuffer",
    "isampler1DArray", "isampler2DArray", "isamplerCubeArray", "isampler2DMS", "isampler2DMSArray",
    "usampler1D", "usampler2D", "usampler3D", "usamplerCube", "usampler2DRect", "usamplerBuffer",
    "usampler1DArray", "usampler2DArray", "usamplerCubeArray", "usampler2DMS", "usampler2DMSArray",
    "image1D", "image2D", "image3D", "imageCube", "image2DRect", "imageBuffer",
    "image1DArray", "image2DArray", "imageCubeArray", "image2DMS", "image2DMSArray",
    "iimage1D", "iimage2D", "iimage3D", "iimageCube", "iimage2DRect", "iimageBuffer",
    "iimage1DArray", "iimage2DArray", "iimageCubeArray", "iimage2DMS", "iimage2DMSArray",
    "uimage1D", "uimage2D", "uimage3D", "uimageCube", "uimage2DRect", "uimageBuffer",
    "uimage1DArray", "uimage2DArray", "uimageCubeArray", "uimage2DMS", "uimage2DMSArray",
    "atomic_uint",
)

private val SKSL_AGSL_TYPES = GLSL_TYPES + setOf(
    "half", "half2", "half3", "half4",
    "float2", "float3", "float4",
    "int2", "int3", "int4",
    "uint2", "uint3", "uint4",
    "bool2", "bool3", "bool4",
    "float2x2", "float2x3", "float2x4", "float3x2", "float3x3", "float3x4", "float4x2", "float4x3", "float4x4",
    "half2x2", "half2x3", "half2x4", "half3x2", "half3x3", "half3x4", "half4x2", "half4x3", "half4x4",
    "shader", "colorFilter", "blender",
)

private fun isShaderOperator(type: Int): Boolean = when (type) {
    GlslTokens.SHLEQ,
    GlslTokens.SHREQ,
    GlslTokens.SHL,
    GlslTokens.SHR,
    GlslTokens.PLUSPLUS,
    GlslTokens.MINUSMINUS,
    GlslTokens.ANDAND,
    GlslTokens.OROR,
    GlslTokens.XORXOR,
    GlslTokens.EQEQ,
    GlslTokens.NE,
    GlslTokens.LE,
    GlslTokens.GE,
    GlslTokens.PLUSEQ,
    GlslTokens.MINUSEQ,
    GlslTokens.STAREQ,
    GlslTokens.SLASHEQ,
    GlslTokens.PERCENTEQ,
    GlslTokens.AMPEQ,
    GlslTokens.PIPEEQ,
    GlslTokens.CARETEQ,
    GlslTokens.QUESTION,
    GlslTokens.COLON,
    GlslTokens.PLUS,
    GlslTokens.MINUS,
    GlslTokens.STAR,
    GlslTokens.SLASH,
    GlslTokens.PERCENT,
    GlslTokens.CARET,
    GlslTokens.AMP,
    GlslTokens.PIPE,
    GlslTokens.TILDE,
    GlslTokens.BANG,
    GlslTokens.EQ,
    GlslTokens.LT,
    GlslTokens.GT,
    -> true
    else -> false
}

private fun isShaderPunctuation(type: Int): Boolean = when (type) {
    GlslTokens.LPAREN,
    GlslTokens.RPAREN,
    GlslTokens.LBRACE,
    GlslTokens.RBRACE,
    GlslTokens.LBRACK,
    GlslTokens.RBRACK,
    GlslTokens.SEMI,
    GlslTokens.COMMA,
    GlslTokens.DOT,
    -> true
    else -> false
}
