package dev.bnorm.storyboard

/**
 * Lexical [Parser] for AGSL / SkSL / GLSL shader snippets used with `storyboard-warp`.
 *
 * Emits atomic lexical tokens with TextMate-compatible scopes (`keyword.control.agsl`,
 * `storage.type.agsl`, `entity.name.function.agsl`, `variable.other.property.agsl`,
 * `constant.numeric.agsl`, `comment.line.double-slash.agsl`, `punctuation.agsl`) and preserves
 * blank lines with an empty placeholder token so row indexing in `DiffLists` always has at least
 * one token per line.
 */
object AgslParser : Parser {
    private val keywords = setOf(
        "uniform",
        "return",
        "if",
        "else",
        "for",
        "while",
        "const",
        "in",
        "out",
        "inout",
        "struct",
        "void",
        "true",
        "false",
        "break",
        "continue",
    )

    private val shaderTypes = setOf(
        "half",
        "half2",
        "half3",
        "half4",
        "float",
        "float2",
        "float3",
        "float4",
        "vec2",
        "vec3",
        "vec4",
        "mat2",
        "mat3",
        "mat4",
        "int",
        "int2",
        "int3",
        "int4",
        "bool",
        "shader",
        "colorFilter",
        "blender",
    )

    private val multiCharOps = listOf("==", "!=", ">=", "<=", "&&", "||", "+=", "-=", "*=", "/=")

    override fun parse(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val lines = code.lines()
        lines.forEachIndexed { lineNumber, line ->
            if (line.isEmpty()) {
                // DiffLists allocates Array(rows.size) indexed by lineNumber; every line needs >= 1 token.
                tokens += makeToken(
                    content = "",
                    scope = "source.agsl",
                    lineNumber = lineNumber,
                    startIndex = 0,
                    endIndex = 0,
                )
                return@forEachIndexed
            }

            var i = 0
            var previousNonSpaceWasDot = false
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                        tokens += makeToken(
                            content = line.substring(i),
                            scope = "comment.line.double-slash.agsl",
                            lineNumber = lineNumber,
                            startIndex = i,
                            endIndex = line.length,
                        )
                        i = line.length
                        previousNonSpaceWasDot = false
                    }

                    ch.isWhitespace() -> {
                        val start = i
                        while (i < line.length && line[i].isWhitespace()) i++
                        tokens += makeToken(
                            content = line.substring(start, i),
                            scope = "source.agsl",
                            lineNumber = lineNumber,
                            startIndex = start,
                            endIndex = i,
                        )
                    }

                    ch.isDigit() || (ch == '.' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                        val start = i
                        if (ch == '.') i++
                        while (i < line.length && line[i].isDigit()) i++
                        if (i < line.length && line[i] == '.') {
                            i++
                            while (i < line.length && line[i].isDigit()) i++
                        }
                        if (i < line.length && (line[i] == 'f' || line[i] == 'F')) i++
                        tokens += makeToken(
                            content = line.substring(start, i),
                            scope = "constant.numeric.agsl",
                            lineNumber = lineNumber,
                            startIndex = start,
                            endIndex = i,
                        )
                        previousNonSpaceWasDot = false
                    }

                    ch.isLetter() || ch == '_' -> {
                        val start = i
                        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                        val word = line.substring(start, i)
                        var lookahead = i
                        while (lookahead < line.length && line[lookahead] == ' ') lookahead++
                        val isCall = lookahead < line.length && line[lookahead] == '('
                        val scope = when {
                            word in keywords -> "keyword.control.agsl"
                            word in shaderTypes -> "storage.type.agsl"
                            isCall -> "entity.name.function.agsl"
                            previousNonSpaceWasDot -> "variable.other.property.agsl"
                            else -> "variable.other.agsl"
                        }
                        tokens += makeToken(
                            content = word,
                            scope = scope,
                            lineNumber = lineNumber,
                            startIndex = start,
                            endIndex = i,
                        )
                        previousNonSpaceWasDot = false
                    }

                    else -> {
                        val multi = multiCharOps.firstOrNull { op -> line.startsWith(op, i) }
                        val end = if (multi != null) i + multi.length else i + 1
                        val text = line.substring(i, end)
                        tokens += makeToken(
                            content = text,
                            scope = "punctuation.agsl",
                            lineNumber = lineNumber,
                            startIndex = i,
                            endIndex = end,
                        )
                        previousNonSpaceWasDot = text == "."
                        i = end
                    }
                }
            }
        }
        return tokens
    }

    private fun makeToken(
        content: String,
        scope: String,
        lineNumber: Int,
        startIndex: Int,
        endIndex: Int,
    ): Token = Token(
        content = content,
        scope = scope,
        depth = if (scope == "source.agsl") 1 else 2,
        language = Agsl,
        allScopes = if (scope == "source.agsl") listOf("source.agsl") else listOf("source.agsl", scope),
        lineNumber = lineNumber,
        startIndex = startIndex,
        endIndex = endIndex,
    )
}

/** First-class AGSL / SkSL / GLSL shader language for `storyboard-warp`. */
val Agsl: Language = Custom(AgslParser)
