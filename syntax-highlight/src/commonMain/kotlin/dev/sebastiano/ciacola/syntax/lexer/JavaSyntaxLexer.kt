package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.java.JavaLexer
import dev.sebastiano.ciacola.syntax.antlr.java.JavaLexer.Tokens as JavaTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Java 21+ source code.
 */
internal object JavaSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Java

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = JavaLexer(antlrCharStream(source))
        val raw = ArrayList<RawJavaToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawJavaToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) JavaTokens.WS else JavaTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawJavaToken(type, start, end, raw) },
        )

        return classifyAndEmitJavaTokens(source, raw)
    }
}

private class RawJavaToken(
    val type: Int,
    val start: Int,
    var end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun emitRawJavaToken(
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawJavaToken>,
) {
    when (type) {
        JavaTokens.WS ->
            out += RawJavaToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        JavaTokens.LINE_COMMENT,
        JavaTokens.BLOCK_COMMENT,
        JavaTokens.UNTERMINATED_BLOCK_COMMENT,
        -> out += RawJavaToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        JavaTokens.TEXT_BLOCK,
        JavaTokens.UNTERMINATED_TEXT_BLOCK,
        JavaTokens.STRING_LITERAL,
        JavaTokens.UNTERMINATED_STRING_LITERAL,
        JavaTokens.CHAR_LITERAL,
        JavaTokens.UNTERMINATED_CHAR_LITERAL,
        -> out += RawJavaToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        JavaTokens.HEX_FLOAT_LITERAL,
        JavaTokens.FLOAT_LITERAL,
        JavaTokens.HEX_LITERAL,
        JavaTokens.BIN_LITERAL,
        JavaTokens.OCT_LITERAL,
        JavaTokens.DECIMAL_LITERAL,
        -> out += RawJavaToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        JavaTokens.TRUE,
        JavaTokens.FALSE,
        JavaTokens.NULL,
        -> out += RawJavaToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        JavaTokens.BOOLEAN,
        JavaTokens.BYTE,
        JavaTokens.CHAR,
        JavaTokens.SHORT,
        JavaTokens.INT,
        JavaTokens.LONG,
        JavaTokens.FLOAT,
        JavaTokens.DOUBLE,
        JavaTokens.VOID,
        -> out += RawJavaToken(type, start, end, SyntaxTokenKind.Type, isHidden = false)

        JavaTokens.ERROR_CHAR ->
            out += RawJavaToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> {
            val initialKind = when {
                isJavaHardKeyword(type) -> SyntaxTokenKind.Keyword
                isJavaOperator(type) -> SyntaxTokenKind.Operator
                isJavaPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawJavaToken(type, start, end, initialKind, isHidden = false)
        }
    }
}

private fun classifyAndEmitJavaTokens(code: String, raw: MutableList<RawJavaToken>): List<SyntaxToken> {
    val visible = ArrayList<Int>(raw.size)
    for (idx in raw.indices) {
        if (!raw[idx].isHidden) visible += idx
    }

    var inPackageOrImport = false
    var inTypeHeader = false
    var expectingDeclaredTypeName = false
    var inModuleHeader = false
    var inModuleBody = false
    var braceDepth = 0
    var angleDepth = 0
    var parenDepth = 0
    var inSwitchCaseLabel = false
    var caseLabelDepth = 0
    var annotationArgDepth = 0
    var justEndedAnnotationArgs = false

    val dropIndices = HashSet<Int>()
    var i = 0

    while (i < visible.size) {
        val rawIdx = visible[i]
        val tok = raw[rawIdx]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val wasAfterAnnotation = justEndedAnnotationArgs || prev?.kind == SyntaxTokenKind.Annotation
        justEndedAnnotationArgs = false

        if (tok.type == JavaTokens.SEMI) {
            inPackageOrImport = false
            inTypeHeader = false
            expectingDeclaredTypeName = false
            inSwitchCaseLabel = false
            angleDepth = 0
        }

        when (tok.type) {
            JavaTokens.PACKAGE,
            JavaTokens.IMPORT,
            -> inPackageOrImport = true

            JavaTokens.CLASS,
            JavaTokens.INTERFACE,
            JavaTokens.ENUM,
            -> {
                if (prev?.type != JavaTokens.DOT && prev?.type != JavaTokens.COLONCOLON) {
                    inTypeHeader = true
                    expectingDeclaredTypeName = true
                }
            }

            JavaTokens.LBRACE -> {
                braceDepth++
                angleDepth = 0
                inTypeHeader = false
                inSwitchCaseLabel = false
                if (inModuleHeader) {
                    inModuleHeader = false
                    inModuleBody = true
                }
            }

            JavaTokens.RBRACE -> {
                if (braceDepth > 0) braceDepth--
                angleDepth = 0
                if (braceDepth == 0) inModuleBody = false
                inSwitchCaseLabel = false
            }

            JavaTokens.LPAREN -> {
                parenDepth++
                if (inSwitchCaseLabel) caseLabelDepth++
            }

            JavaTokens.RPAREN -> {
                if (parenDepth > 0) parenDepth--
                if (inSwitchCaseLabel && caseLabelDepth > 0) caseLabelDepth--
                if (annotationArgDepth > 0 && parenDepth < annotationArgDepth) {
                    annotationArgDepth = 0
                    justEndedAnnotationArgs = true
                }
            }

            JavaTokens.LT -> if (hasMatchingJavaAngleClose(raw, visible, i)) angleDepth++
            JavaTokens.GT -> if (angleDepth > 0) angleDepth--
            JavaTokens.RSHIFT -> angleDepth = (angleDepth - 2).coerceAtLeast(0)
            JavaTokens.URSHIFT -> angleDepth = (angleDepth - 3).coerceAtLeast(0)
            JavaTokens.CASE -> {
                inSwitchCaseLabel = true
                caseLabelDepth = 0
            }
            JavaTokens.COLON,
            JavaTokens.ARROW,
            -> {
                if (inSwitchCaseLabel && caseLabelDepth == 0) {
                    inSwitchCaseLabel = false
                }
            }
        }

        // Annotations (`@Override`, `@SuppressWarnings("unchecked")`, `@org.jspecify.annotations.Nullable`, `@interface`)
        if (tok.type == JavaTokens.AT && next != null) {
            if (next.type == JavaTokens.INTERFACE) {
                tok.kind = SyntaxTokenKind.Keyword
                i++
                continue
            }
            if (next.type == JavaTokens.IDENTIFIER) {
                tok.kind = SyntaxTokenKind.Annotation
                var j = i + 1
                while (j < visible.size && raw[visible[j]].type == JavaTokens.IDENTIFIER) {
                    raw[visible[j]].kind = SyntaxTokenKind.Annotation
                    j++
                    if (
                        j + 1 < visible.size &&
                        raw[visible[j]].type == JavaTokens.DOT &&
                        raw[visible[j + 1]].type == JavaTokens.IDENTIFIER
                    ) {
                        raw[visible[j]].kind = SyntaxTokenKind.Annotation
                        j++
                    } else {
                        break
                    }
                }
                if (j < visible.size && raw[visible[j]].type == JavaTokens.LPAREN) {
                    annotationArgDepth = parenDepth + 1
                }
                i = j
                continue
            }
        }

        // Contextual `non-sealed` (only in declaration modifier position)
        if (
            tok.type == JavaTokens.IDENTIFIER &&
            code.substring(tok.start, tok.end) == "non" &&
            prev?.type != JavaTokens.DOT &&
            prev?.type != JavaTokens.COLONCOLON &&
            i + 3 < visible.size
        ) {
            val dash = raw[visible[i + 1]]
            val sealedTok = raw[visible[i + 2]]
            val after = raw[visible[i + 3]]
            if (
                dash.type == JavaTokens.MINUS &&
                tok.end == dash.start &&
                dash.end == sealedTok.start &&
                sealedTok.type == JavaTokens.IDENTIFIER &&
                code.substring(sealedTok.start, sealedTok.end) == "sealed" &&
                isJavaClassOrModifier(after.type, code.substring(after.start, after.end))
            ) {
                tok.end = sealedTok.end
                tok.kind = SyntaxTokenKind.Keyword
                dropIndices += visible[i + 1]
                dropIndices += visible[i + 2]
                i += 3
                continue
            }
        }

        if (tok.type == JavaTokens.IDENTIFIER) {
            val text = code.substring(tok.start, tok.end)

            if (isJavaContextualKeyword(
                    text = text,
                    raw = raw,
                    visible = visible,
                    index = i,
                    code = code,
                    prev = prev,
                    prevAfterAnnotation = wasAfterAnnotation,
                    inTypeHeader = inTypeHeader,
                    inSwitchCaseLabel = inSwitchCaseLabel,
                    caseLabelDepth = caseLabelDepth,
                    braceDepth = braceDepth,
                    inModuleBody = inModuleBody,
                )
            ) {
                tok.kind = SyntaxTokenKind.Keyword
                when (text) {
                    "record" -> {
                        inTypeHeader = true
                        expectingDeclaredTypeName = true
                    }
                    "module" -> if (prev?.type != JavaTokens.IMPORT) inModuleHeader = true
                }
                i++
                continue
            }

            if (expectingDeclaredTypeName) {
                tok.kind = SyntaxTokenKind.Type
                expectingDeclaredTypeName = false
                i++
                continue
            }

            tok.kind = classifyJavaIdentifier(
                text = text,
                prev = prev,
                next = next,
                inPackageOrImport = inPackageOrImport,
                angleDepth = angleDepth,
                inModuleHeader = inModuleHeader,
                inModuleBody = inModuleBody,
            )
        }

        i++
    }

    val out = ArrayList<SyntaxToken>(raw.size)
    for (idx in raw.indices) {
        if (idx in dropIndices) continue
        val t = raw[idx]
        out += SyntaxToken(kind = t.kind, startIndex = t.start, endIndex = t.end)
    }
    return out
}

private fun hasMatchingJavaAngleClose(
    raw: List<RawJavaToken>,
    visible: List<Int>,
    openIndex: Int,
): Boolean {
    var depth = 1
    var nestedParens = 0
    val limit = minOf(visible.size, openIndex + 65)
    for (j in openIndex + 1 until limit) {
        when (raw[visible[j]].type) {
            JavaTokens.LPAREN,
            JavaTokens.LBRACK,
            -> nestedParens++

            JavaTokens.RPAREN,
            JavaTokens.RBRACK,
            -> {
                if (nestedParens == 0) return false
                nestedParens--
            }

            JavaTokens.SEMI,
            JavaTokens.LBRACE,
            JavaTokens.RBRACE,
            -> return false

            JavaTokens.ASSIGN,
            JavaTokens.EQ,
            JavaTokens.NE,
            JavaTokens.AND,
            JavaTokens.OR,
            JavaTokens.RETURN,
            JavaTokens.IF,
            JavaTokens.FOR,
            JavaTokens.WHILE,
            JavaTokens.SWITCH,
            -> if (nestedParens == 0) return false

            JavaTokens.LT -> if (nestedParens == 0) depth++
            JavaTokens.GT -> if (nestedParens == 0) {
                depth--
                if (depth <= 0) return true
            }
            JavaTokens.RSHIFT -> if (nestedParens == 0) {
                depth -= 2
                if (depth <= 0) return true
            }
            JavaTokens.URSHIFT -> if (nestedParens == 0) {
                depth -= 3
                if (depth <= 0) return true
            }
        }
    }
    return false
}

private fun isJavaContextualKeyword(
    text: String,
    raw: List<RawJavaToken>,
    visible: List<Int>,
    index: Int,
    code: String,
    prev: RawJavaToken?,
    prevAfterAnnotation: Boolean,
    inTypeHeader: Boolean,
    inSwitchCaseLabel: Boolean,
    caseLabelDepth: Int,
    braceDepth: Int,
    inModuleBody: Boolean,
): Boolean {
    if (prev?.type == JavaTokens.DOT || prev?.type == JavaTokens.COLONCOLON) return false
    val next = if (index + 1 < visible.size) raw[visible[index + 1]] else null
    val next2 = if (index + 2 < visible.size) raw[visible[index + 2]] else null
    val nextText = next?.let { code.substring(it.start, it.end) }

    return when (text) {
        "record" -> {
            next?.type == JavaTokens.IDENTIFIER &&
                (next2?.type == JavaTokens.LPAREN || next2?.type == JavaTokens.LT) &&
                isDeclarationPredecessor(prev, prevAfterAnnotation)
        }

        "sealed" -> {
            isDeclarationPredecessor(prev, prevAfterAnnotation) &&
                next != null &&
                isJavaClassOrModifier(next.type, nextText.orEmpty())
        }

        "permits" -> {
            inTypeHeader &&
                prev != null &&
                (prev.type == JavaTokens.IDENTIFIER || prev.type == JavaTokens.GT) &&
                next?.type == JavaTokens.IDENTIFIER
        }

        "var" -> {
            next?.type == JavaTokens.IDENTIFIER &&
                next2 != null &&
                (
                    next2.type == JavaTokens.ASSIGN ||
                        next2.type == JavaTokens.COLON ||
                        next2.type == JavaTokens.COMMA ||
                        next2.type == JavaTokens.RPAREN ||
                        next2.type == JavaTokens.SEMI
                    ) &&
                isVarPredecessor(prev, prevAfterAnnotation)
        }

        "yield" -> {
            isStatementStartPredecessor(prev) &&
                next != null &&
                !isJavaAssignmentOrSeparator(next.type) &&
                !(next.type == JavaTokens.LPAREN && next2?.type == JavaTokens.RPAREN)
        }

        "when" -> {
            inSwitchCaseLabel &&
                caseLabelDepth == 0 &&
                next != null &&
                !isJavaAssignmentOrSeparator(next.type)
        }

        "module" -> {
            braceDepth == 0 &&
                (
                    prev == null ||
                        prev.type == JavaTokens.SEMI ||
                        prev.type == JavaTokens.IMPORT ||
                        (prev.type == JavaTokens.IDENTIFIER && code.substring(prev.start, prev.end) == "open")
                    ) &&
                next?.type == JavaTokens.IDENTIFIER
        }

        "open" -> {
            braceDepth == 0 &&
                (prev == null || prev.type == JavaTokens.SEMI) &&
                next?.type == JavaTokens.IDENTIFIER &&
                nextText == "module"
        }

        "requires",
        "exports",
        "opens",
        "uses",
        "provides",
        -> {
            inModuleBody &&
                braceDepth == 1 &&
                (prev?.type == JavaTokens.LBRACE || prev?.type == JavaTokens.SEMI) &&
                next?.type == JavaTokens.IDENTIFIER
        }

        "transitive" -> {
            inModuleBody &&
                braceDepth == 1 &&
                prev?.type == JavaTokens.IDENTIFIER &&
                code.substring(prev.start, prev.end) == "requires" &&
                next?.type == JavaTokens.IDENTIFIER
        }

        "to",
        "with",
        -> {
            inModuleBody &&
                braceDepth == 1 &&
                prev?.type == JavaTokens.IDENTIFIER &&
                next?.type == JavaTokens.IDENTIFIER
        }

        else -> false
    }
}

private fun isDeclarationPredecessor(prev: RawJavaToken?, prevAfterAnnotation: Boolean): Boolean {
    if (prev == null || prevAfterAnnotation) return true
    return when (prev.type) {
        JavaTokens.LBRACE,
        JavaTokens.RBRACE,
        JavaTokens.SEMI,
        JavaTokens.PUBLIC,
        JavaTokens.PROTECTED,
        JavaTokens.PRIVATE,
        JavaTokens.ABSTRACT,
        JavaTokens.STATIC,
        JavaTokens.FINAL,
        JavaTokens.STRICTFP,
        -> true
        else -> false
    }
}

private fun isVarPredecessor(prev: RawJavaToken?, prevAfterAnnotation: Boolean): Boolean {
    if (prev == null || prevAfterAnnotation) return true
    return when (prev.type) {
        JavaTokens.LBRACE,
        JavaTokens.RBRACE,
        JavaTokens.SEMI,
        JavaTokens.LPAREN,
        JavaTokens.COMMA,
        JavaTokens.COLON,
        JavaTokens.ARROW,
        JavaTokens.FINAL,
        -> true
        else -> false
    }
}

private fun isStatementStartPredecessor(prev: RawJavaToken?): Boolean {
    if (prev == null) return true
    return when (prev.type) {
        JavaTokens.LBRACE,
        JavaTokens.RBRACE,
        JavaTokens.SEMI,
        JavaTokens.COLON,
        JavaTokens.ARROW,
        -> true
        else -> false
    }
}

private fun isJavaClassOrModifier(type: Int, text: String): Boolean = when (type) {
    JavaTokens.CLASS,
    JavaTokens.INTERFACE,
    JavaTokens.PUBLIC,
    JavaTokens.PROTECTED,
    JavaTokens.PRIVATE,
    JavaTokens.ABSTRACT,
    JavaTokens.STATIC,
    JavaTokens.FINAL,
    JavaTokens.STRICTFP,
    -> true
    JavaTokens.IDENTIFIER -> text == "non" || text == "sealed"
    else -> false
}

private fun isJavaAssignmentOrSeparator(type: Int): Boolean = when (type) {
    JavaTokens.ASSIGN,
    JavaTokens.ADD_ASSIGN,
    JavaTokens.SUB_ASSIGN,
    JavaTokens.MUL_ASSIGN,
    JavaTokens.DIV_ASSIGN,
    JavaTokens.MOD_ASSIGN,
    JavaTokens.AND_ASSIGN,
    JavaTokens.OR_ASSIGN,
    JavaTokens.XOR_ASSIGN,
    JavaTokens.LSHIFT_ASSIGN,
    JavaTokens.RSHIFT_ASSIGN,
    JavaTokens.URSHIFT_ASSIGN,
    JavaTokens.DOT,
    JavaTokens.COLONCOLON,
    JavaTokens.SEMI,
    JavaTokens.COMMA,
    JavaTokens.RPAREN,
    JavaTokens.RBRACK,
    JavaTokens.RBRACE,
    -> true
    else -> false
}

private fun classifyJavaIdentifier(
    text: String,
    prev: RawJavaToken?,
    next: RawJavaToken?,
    inPackageOrImport: Boolean,
    angleDepth: Int,
    inModuleHeader: Boolean,
    inModuleBody: Boolean,
): SyntaxTokenKind {
    if (inPackageOrImport || inModuleHeader || inModuleBody) {
        return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Plain
    }

    val prevType = prev?.type
    val nextType = next?.type

    if (nextType == JavaTokens.LPAREN) {
        if (prevType == JavaTokens.NEW && looksLikeTypeName(text)) {
            return SyntaxTokenKind.Type
        }
        return SyntaxTokenKind.Function
    }

    if (prevType == JavaTokens.COLONCOLON) {
        return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Function
    }

    if (prevType == JavaTokens.DOT) {
        return if (looksLikeTypeName(text) && nextType == JavaTokens.DOT) {
            SyntaxTokenKind.Type
        } else {
            SyntaxTokenKind.Property
        }
    }

    if (prev?.kind == SyntaxTokenKind.Type) {
        return SyntaxTokenKind.Plain
    }

    if (
        text.length == 1 &&
        text[0].isUpperCase() &&
        (angleDepth > 0 || prevType == JavaTokens.LT || prevType == JavaTokens.COMMA || prevType == JavaTokens.EXTENDS || prevType == JavaTokens.SUPER)
    ) {
        return SyntaxTokenKind.Type
    }

    if (looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }

    return SyntaxTokenKind.Plain
}

private fun isJavaHardKeyword(type: Int): Boolean = when (type) {
    JavaTokens.ABSTRACT,
    JavaTokens.ASSERT,
    JavaTokens.BREAK,
    JavaTokens.CASE,
    JavaTokens.CATCH,
    JavaTokens.CLASS,
    JavaTokens.CONST,
    JavaTokens.CONTINUE,
    JavaTokens.DEFAULT,
    JavaTokens.DO,
    JavaTokens.ELSE,
    JavaTokens.ENUM,
    JavaTokens.EXTENDS,
    JavaTokens.FINAL,
    JavaTokens.FINALLY,
    JavaTokens.FOR,
    JavaTokens.GOTO,
    JavaTokens.IF,
    JavaTokens.IMPLEMENTS,
    JavaTokens.IMPORT,
    JavaTokens.INSTANCEOF,
    JavaTokens.INTERFACE,
    JavaTokens.NATIVE,
    JavaTokens.NEW,
    JavaTokens.PACKAGE,
    JavaTokens.PRIVATE,
    JavaTokens.PROTECTED,
    JavaTokens.PUBLIC,
    JavaTokens.RETURN,
    JavaTokens.STATIC,
    JavaTokens.STRICTFP,
    JavaTokens.SUPER,
    JavaTokens.SWITCH,
    JavaTokens.SYNCHRONIZED,
    JavaTokens.THIS,
    JavaTokens.THROW,
    JavaTokens.THROWS,
    JavaTokens.TRANSIENT,
    JavaTokens.TRY,
    JavaTokens.VOLATILE,
    JavaTokens.WHILE,
    -> true
    else -> false
}

private fun isJavaOperator(type: Int): Boolean = when (type) {
    JavaTokens.ARROW,
    JavaTokens.URSHIFT_ASSIGN,
    JavaTokens.RSHIFT_ASSIGN,
    JavaTokens.LSHIFT_ASSIGN,
    JavaTokens.ADD_ASSIGN,
    JavaTokens.SUB_ASSIGN,
    JavaTokens.MUL_ASSIGN,
    JavaTokens.DIV_ASSIGN,
    JavaTokens.MOD_ASSIGN,
    JavaTokens.AND_ASSIGN,
    JavaTokens.OR_ASSIGN,
    JavaTokens.XOR_ASSIGN,
    JavaTokens.URSHIFT,
    JavaTokens.RSHIFT,
    JavaTokens.LSHIFT,
    JavaTokens.INC,
    JavaTokens.DEC,
    JavaTokens.AND,
    JavaTokens.OR,
    JavaTokens.LE,
    JavaTokens.GE,
    JavaTokens.EQ,
    JavaTokens.NE,
    JavaTokens.ASSIGN,
    JavaTokens.GT,
    JavaTokens.LT,
    JavaTokens.BANG,
    JavaTokens.TILDE,
    JavaTokens.QUESTION,
    JavaTokens.PLUS,
    JavaTokens.MINUS,
    JavaTokens.STAR,
    JavaTokens.SLASH,
    JavaTokens.BITAND,
    JavaTokens.BITOR,
    JavaTokens.CARET,
    JavaTokens.MOD,
    -> true
    else -> false
}

private fun isJavaPunctuation(type: Int): Boolean = when (type) {
    JavaTokens.ELLIPSIS,
    JavaTokens.LPAREN,
    JavaTokens.RPAREN,
    JavaTokens.LBRACE,
    JavaTokens.RBRACE,
    JavaTokens.LBRACK,
    JavaTokens.RBRACK,
    JavaTokens.SEMI,
    JavaTokens.COMMA,
    JavaTokens.DOT,
    JavaTokens.COLON,
    JavaTokens.COLONCOLON,
    JavaTokens.AT,
    -> true
    else -> false
}
