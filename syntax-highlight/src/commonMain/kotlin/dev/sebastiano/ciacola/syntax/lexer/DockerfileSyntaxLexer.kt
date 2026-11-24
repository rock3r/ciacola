package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.dockerfile.DockerfileLexer
import dev.sebastiano.ciacola.syntax.antlr.dockerfile.DockerfileLexer.Tokens as DfTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Dockerfile / Containerfile source code.
 */
internal object DockerfileSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Dockerfile

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = DockerfileLexer(antlrCharStream(source))
        val raw = ArrayList<RawDfToken>(source.length / 4 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawDfToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) DfTokens.WS else DfTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawDfToken(source, type, start, end, raw) },
        )

        classifyDockerfileTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawDfToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private const val SYNTHETIC_HEREDOC_OP = -101
private const val SYNTHETIC_HEREDOC_DELIM = -102
private const val SYNTHETIC_EXPOSE_SLASH = -103
private const val SYNTHETIC_EXPOSE_PROTO = -104

private fun emitRawDfToken(
    source: String,
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawDfToken>,
) {
    when (type) {
        DfTokens.WS,
        DfTokens.BACKSLASH_LINE_CONT,
        DfTokens.BACKTICK_LINE_CONT,
        DfTokens.NEWLINE,
        DfTokens.DQ_NEWLINE,
        DfTokens.DQ_AFTER_COMMENT_NEWLINE,
        DfTokens.PE_WS,
        DfTokens.PE_BACKSLASH_LINE_CONT,
        DfTokens.PE_BACKTICK_LINE_CONT,
        DfTokens.PE_NEWLINE,
        DfTokens.HL_NEWLINE,
        DfTokens.HE_NEWLINE,
        -> out += RawDfToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        DfTokens.COMMENT ->
            out += RawDfToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        DfTokens.DQ_COMMENT -> {
            var wsEnd = start
            while (wsEnd < end && (source[wsEnd] == ' ' || source[wsEnd] == '\t')) {
                wsEnd++
            }
            if (wsEnd > start) {
                out += RawDfToken(DfTokens.WS, start, wsEnd, SyntaxTokenKind.Whitespace, isHidden = true)
            }
            if (wsEnd < end) {
                out += RawDfToken(DfTokens.COMMENT, wsEnd, end, SyntaxTokenKind.Comment, isHidden = true)
            }
        }

        DfTokens.HEREDOC_OPEN -> {
            val opLen = if (start + 2 < end && source[start + 2] == '-') 3 else 2
            out += RawDfToken(SYNTHETIC_HEREDOC_OP, start, start + opLen, SyntaxTokenKind.Operator, isHidden = false)
            if (start + opLen < end) {
                out += RawDfToken(SYNTHETIC_HEREDOC_DELIM, start + opLen, end, SyntaxTokenKind.Keyword, isHidden = false)
            }
        }

        DfTokens.HEREDOC_END -> {
            var indentEnd = start
            while (indentEnd < end && source[indentEnd] == '\t') {
                indentEnd++
            }
            if (indentEnd > start) {
                out += RawDfToken(DfTokens.WS, start, indentEnd, SyntaxTokenKind.Whitespace, isHidden = true)
            }
            if (indentEnd < end) {
                out += RawDfToken(type, indentEnd, end, SyntaxTokenKind.Keyword, isHidden = false)
            }
        }

        DfTokens.PARAM_EXP_OPEN,
        DfTokens.DQ_PARAM_EXP_OPEN,
        DfTokens.PE_PARAM_EXP_OPEN,
        DfTokens.HE_PARAM_EXP_OPEN,
        DfTokens.PARAM_EXP_CLOSE,
        DfTokens.PE_LBRACE,
        DfTokens.PE_RBRACE,
        DfTokens.LBRACK,
        DfTokens.RBRACK,
        DfTokens.LPAREN,
        DfTokens.RPAREN,
        DfTokens.LBRACE,
        DfTokens.RBRACE,
        DfTokens.COMMA,
        DfTokens.SEMI,
        -> out += RawDfToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)

        DfTokens.SIMPLE_VAR,
        DfTokens.DQ_SIMPLE_VAR,
        DfTokens.PE_SIMPLE_VAR,
        DfTokens.HE_SIMPLE_VAR,
        -> out += RawDfToken(type, start, end, SyntaxTokenKind.Property, isHidden = false)

        DfTokens.SPECIAL_VAR,
        DfTokens.DQ_SPECIAL_VAR,
        DfTokens.PE_SPECIAL_VAR,
        DfTokens.HE_SPECIAL_VAR,
        -> out += RawDfToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        DfTokens.FLAG ->
            out += RawDfToken(type, start, end, SyntaxTokenKind.Attribute, isHidden = false)

        DfTokens.OP_MULTI,
        DfTokens.OP,
        DfTokens.PE_OP,
        -> out += RawDfToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)

        DfTokens.NUMBER,
        DfTokens.PE_INT,
        -> out += RawDfToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        DfTokens.DQUOTE_OPEN,
        DfTokens.DQ_CLOSE,
        DfTokens.DQ_TEXT,
        DfTokens.DQ_DOLLAR,
        DfTokens.DQ_BS_LITERAL,
        DfTokens.DQ_BT_LITERAL,
        DfTokens.DQ_HASH,
        DfTokens.PE_DQUOTE_OPEN,
        DfTokens.SINGLE_STRING,
        DfTokens.PE_SINGLE_STRING,
        DfTokens.HL_TEXT,
        DfTokens.HE_TEXT,
        DfTokens.HE_BS_LITERAL,
        DfTokens.HE_BT_LITERAL,
        DfTokens.HE_DOLLAR,
        -> out += RawDfToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        DfTokens.DQ_ESC_BS,
        DfTokens.DQ_ESC_BT,
        DfTokens.PE_ESC_BS,
        DfTokens.PE_ESC_BT,
        DfTokens.HE_ESC_BS,
        DfTokens.HE_ESC_BT,
        DfTokens.BACKSLASH_ESC,
        DfTokens.BACKTICK_ESC,
        -> out += RawDfToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        DfTokens.PE_IDENT ->
            out += RawDfToken(type, start, end, SyntaxTokenKind.Property, isHidden = false)

        DfTokens.DOLLAR,
        DfTokens.BACKSLASH_LITERAL,
        DfTokens.BACKTICK_LITERAL,
        DfTokens.WORD,
        DfTokens.PE_TEXT,
        -> out += RawDfToken(type, start, end, SyntaxTokenKind.Plain, isHidden = false)

        else -> out += RawDfToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)
    }
}

private val DOCKERFILE_INSTRUCTIONS = setOf(
    "FROM",
    "RUN",
    "CMD",
    "LABEL",
    "MAINTAINER",
    "EXPOSE",
    "ENV",
    "ADD",
    "COPY",
    "ENTRYPOINT",
    "VOLUME",
    "USER",
    "WORKDIR",
    "ARG",
    "ONBUILD",
    "STOPSIGNAL",
    "HEALTHCHECK",
    "SHELL",
)

private val SHELL_BUILTINS = setOf(
    "echo",
    "exit",
    "export",
    "cd",
    "set",
    "unset",
    "mkdir",
    "rm",
    "cp",
    "mv",
    "ln",
    "chmod",
    "chown",
    "true",
    "false",
)

private fun classifyDockerfileTokens(source: String, raw: MutableList<RawDfToken>) {
    var atInstructionStart = true
    var currentInstruction = ""
    var inFromInstruction = false
    var inHealthcheckHeader = false
    var inShellCommand = false
    var inKeyValueInstruction = false
    var expectLegacyKey = false
    var inLegacyEnvValue = false
    var afterArgEq = false
    val paramExpNeedsIdentStack = ArrayList<Boolean>()
    val paramExpBraceDepthStack = ArrayList<Int>()
    var seenJsonOpen = false
    var afterFlagEq = false
    var inFlagPosition = false
    var inFlagValue = false
    var pendingHeredocCount = 0
    var continuationPending = false
    var commentLineInContinuation = false
    var atLineStartInClassifier = true

    val result = ArrayList<RawDfToken>(raw.size)

    fun resetInstructionState() {
        atInstructionStart = true
        currentInstruction = ""
        inFromInstruction = false
        inHealthcheckHeader = false
        inShellCommand = false
        inKeyValueInstruction = false
        expectLegacyKey = false
        inLegacyEnvValue = false
        afterArgEq = false
        seenJsonOpen = false
        afterFlagEq = false
        inFlagPosition = false
        inFlagValue = false
        continuationPending = false
        commentLineInContinuation = false
    }

    var i = 0
    while (i < raw.size) {
        val tok = raw[i]

        if (tok.type == SYNTHETIC_HEREDOC_OP) {
            pendingHeredocCount++
            atLineStartInClassifier = false
            inFlagPosition = false
            inFlagValue = false
            result += tok
            i++
            continue
        }

        if (tok.type == DfTokens.BACKSLASH_LINE_CONT || tok.type == DfTokens.BACKTICK_LINE_CONT) {
            continuationPending = true
            commentLineInContinuation = false
            atLineStartInClassifier = true
            if (paramExpNeedsIdentStack.isEmpty()) {
                afterArgEq = false
                inFlagValue = false
            }
            result += tok
            i++
            continue
        }

        if (tok.type == DfTokens.COMMENT) {
            if (continuationPending) {
                commentLineInContinuation = true
            }
            atLineStartInClassifier = false
            result += tok
            i++
            continue
        }

        if (tok.type == DfTokens.HEREDOC_END) {
            if (pendingHeredocCount > 0) {
                pendingHeredocCount--
            }
            if (pendingHeredocCount == 0) {
                // Next line starts a fresh Dockerfile instruction once the final heredoc closes.
                resetInstructionState()
                atLineStartInClassifier = true
            }
            result += tok
            i++
            continue
        }

        if (tok.type == DfTokens.NEWLINE ||
            tok.type == DfTokens.DQ_NEWLINE ||
            tok.type == DfTokens.DQ_AFTER_COMMENT_NEWLINE ||
            tok.type == DfTokens.PE_NEWLINE ||
            tok.type == DfTokens.HL_NEWLINE ||
            tok.type == DfTokens.HE_NEWLINE
        ) {
            if (tok.type == DfTokens.DQ_AFTER_COMMENT_NEWLINE) {
                result += tok
                i++
                continue
            }
            if (tok.type == DfTokens.PE_NEWLINE) {
                paramExpNeedsIdentStack.clear()
                paramExpBraceDepthStack.clear()
                if (pendingHeredocCount == 0) {
                    resetInstructionState()
                    atLineStartInClassifier = true
                }
                result += tok
                i++
                continue
            }
            if (tok.type == DfTokens.NEWLINE && (commentLineInContinuation || (continuationPending && atLineStartInClassifier))) {
                commentLineInContinuation = false
                atLineStartInClassifier = true
                result += tok
                i++
                continue
            }
            continuationPending = false
            atLineStartInClassifier = true
            val isDefaultModeNewline = tok.type == DfTokens.NEWLINE || tok.type == DfTokens.DQ_NEWLINE
            if (paramExpNeedsIdentStack.isEmpty() && isDefaultModeNewline && pendingHeredocCount == 0) {
                resetInstructionState()
            } else if (tok.type == DfTokens.DQ_NEWLINE && pendingHeredocCount == 0) {
                paramExpNeedsIdentStack.clear()
                paramExpBraceDepthStack.clear()
                resetInstructionState()
            }
            result += tok
            i++
            continue
        }

        if (tok.isHidden) {
            if (tok.type == DfTokens.WS && paramExpNeedsIdentStack.isEmpty()) {
                afterArgEq = false
                inFlagValue = false
            }
            result += tok
            i++
            continue
        }

        atLineStartInClassifier = false

        if (tok.type == DfTokens.PARAM_EXP_OPEN ||
            tok.type == DfTokens.DQ_PARAM_EXP_OPEN ||
            tok.type == DfTokens.PE_PARAM_EXP_OPEN ||
            tok.type == DfTokens.HE_PARAM_EXP_OPEN
        ) {
            paramExpNeedsIdentStack.add(true)
            paramExpBraceDepthStack.add(0)
            result += tok
            i++
            continue
        }

        if (paramExpNeedsIdentStack.isNotEmpty()) {
            val topIdx = paramExpNeedsIdentStack.lastIndex
            if (tok.type == DfTokens.PE_LBRACE) {
                paramExpBraceDepthStack[topIdx] = paramExpBraceDepthStack[topIdx] + 1
            } else if (tok.type == DfTokens.PE_RBRACE) {
                if (paramExpBraceDepthStack[topIdx] > 0) {
                    paramExpBraceDepthStack[topIdx] = paramExpBraceDepthStack[topIdx] - 1
                }
            } else if (tok.type == DfTokens.PARAM_EXP_CLOSE) {
                if (paramExpBraceDepthStack[topIdx] > 0) {
                    paramExpBraceDepthStack[topIdx] = paramExpBraceDepthStack[topIdx] - 1
                } else {
                    paramExpBraceDepthStack.removeAt(topIdx)
                    paramExpNeedsIdentStack.removeAt(topIdx)
                }
            } else if (tok.type == DfTokens.PE_IDENT) {
                if (paramExpNeedsIdentStack[topIdx]) {
                    tok.kind = SyntaxTokenKind.Property
                    paramExpNeedsIdentStack[topIdx] = false
                } else {
                    tok.kind = SyntaxTokenKind.Plain
                }
            }
            result += tok
            i++
            continue
        }

        val text = source.substring(tok.start, tok.end)

        if (atInstructionStart && tok.type == DfTokens.WORD) {
            val upper = text.uppercase()
            if (upper in DOCKERFILE_INSTRUCTIONS) {
                tok.kind = SyntaxTokenKind.Keyword
                currentInstruction = upper
                inFromInstruction = upper == "FROM"
                inHealthcheckHeader = upper == "HEALTHCHECK"
                inShellCommand = upper == "RUN" || upper == "CMD" || upper == "ENTRYPOINT"
                inKeyValueInstruction = upper == "ARG" || upper == "ENV" || upper == "LABEL"
                expectLegacyKey = upper == "ENV" || upper == "ARG"
                inLegacyEnvValue = false
                afterArgEq = false
                seenJsonOpen = false
                afterFlagEq = false
                inFlagValue = false
                inFlagPosition = upper != "ONBUILD"
                // ONBUILD nests a full instruction immediately on the same logical line.
                atInstructionStart = upper == "ONBUILD"
                result += tok
                i++
                continue
            }
        }

        atInstructionStart = false

        if (inHealthcheckHeader && tok.type == DfTokens.WORD) {
            val upper = text.uppercase()
            if (upper == "CMD" || upper == "NONE") {
                tok.kind = SyntaxTokenKind.Keyword
                inHealthcheckHeader = false
                inFlagPosition = false
                inFlagValue = false
                if (upper == "CMD") {
                    inShellCommand = true
                }
                result += tok
                i++
                continue
            }
        }

        if (inFromInstruction && tok.type == DfTokens.WORD && text.equals("AS", ignoreCase = true)) {
            tok.kind = SyntaxTokenKind.Keyword
            inFlagPosition = false
            inFlagValue = false
            result += tok
            i++
            continue
        }

        if (tok.type == DfTokens.FLAG) {
            if (inFlagPosition) {
                afterFlagEq = isNextNonHiddenText(raw, source, i + 1, "=")
                inFlagValue = false
            } else {
                tok.kind = SyntaxTokenKind.Plain
                afterFlagEq = false
            }
            result += tok
            i++
            continue
        }

        if (tok.type == DfTokens.OP && text == "=") {
            if (afterFlagEq) {
                afterFlagEq = false
                inFlagValue = true
            }
            if (currentInstruction == "ARG") {
                afterArgEq = true
            }
            result += tok
            i++
            continue
        }

        if (!inFlagValue) {
            inFlagPosition = false
        }

        if (tok.type == DfTokens.LBRACK) {
            seenJsonOpen = true
            result += tok
            i++
            continue
        }

        // Key=Value instructions (ARG / ENV / LABEL) & BuildKit --mount=type=cache,target=... keys.
        if (tok.type == DfTokens.WORD) {
            val allowsKeyBeforeEq = (inKeyValueInstruction && !inLegacyEnvValue) || inFlagValue
            if (allowsKeyBeforeEq && isNextNonHiddenText(raw, source, i + 1, "=")) {
                tok.kind = SyntaxTokenKind.Property
                if (currentInstruction != "ARG") {
                    expectLegacyKey = false
                }
                afterArgEq = false
                result += tok
                i++
                continue
            }
            if (!afterArgEq && expectLegacyKey && inKeyValueInstruction) {
                tok.kind = SyntaxTokenKind.Property
                if (currentInstruction == "ENV") {
                    expectLegacyKey = false
                    inLegacyEnvValue = true
                } else if (currentInstruction != "ARG") {
                    expectLegacyKey = false
                }
                result += tok
                i++
                continue
            }
            if (currentInstruction == "EXPOSE") {
                val slashIdx = text.indexOf('/')
                if (slashIdx > 0 && slashIdx + 1 < text.length) {
                    val portPart = text.substring(0, slashIdx)
                    val protoPart = text.substring(slashIdx + 1)
                    if (portPart.all { it.isDigit() || it == '-' } &&
                        (protoPart.equals("tcp", ignoreCase = true) || protoPart.equals("udp", ignoreCase = true))
                    ) {
                        result += RawDfToken(DfTokens.NUMBER, tok.start, tok.start + slashIdx, SyntaxTokenKind.Number, isHidden = false)
                        result += RawDfToken(SYNTHETIC_EXPOSE_SLASH, tok.start + slashIdx, tok.start + slashIdx + 1, SyntaxTokenKind.Punctuation, isHidden = false)
                        result += RawDfToken(SYNTHETIC_EXPOSE_PROTO, tok.start + slashIdx + 1, tok.end, SyntaxTokenKind.Constant, isHidden = false)
                        i++
                        continue
                    }
                }
            }
            if (inShellCommand && !seenJsonOpen && text in SHELL_BUILTINS) {
                tok.kind = SyntaxTokenKind.Function
                result += tok
                i++
                continue
            }
        }

        result += tok
        i++
    }

    raw.clear()
    raw.addAll(result)
}

private fun isNextNonHiddenText(
    raw: List<RawDfToken>,
    source: String,
    startIndex: Int,
    expected: String,
): Boolean {
    var idx = startIndex
    while (idx < raw.size) {
        val tok = raw[idx]
        if (tok.type == DfTokens.NEWLINE ||
            tok.type == DfTokens.DQ_NEWLINE ||
            tok.type == DfTokens.PE_NEWLINE ||
            tok.type == DfTokens.HL_NEWLINE ||
            tok.type == DfTokens.HE_NEWLINE
        ) {
            return false
        }
        if (!tok.isHidden) {
            return tok.end - tok.start == expected.length &&
                source.regionMatches(tok.start, expected, 0, expected.length)
        }
        idx++
    }
    return false
}
