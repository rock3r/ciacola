package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.hcl.HclLexer
import dev.sebastiano.ciacola.syntax.antlr.hcl.HclLexer.Tokens as HclTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for HCL2 / Terraform source code.
 */
internal object HclSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Hcl

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = HclLexer(antlrCharStream(source))
        val raw = ArrayList<RawHclToken>(source.length / 4 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawHclToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) HclTokens.WS else HclTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawHclToken(source, type, start, end, raw) },
        )

        classifyHclTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawHclToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun emitRawHclToken(
    source: String,
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawHclToken>,
) {
    when (type) {
        HclTokens.WS,
        HclTokens.NEWLINE,
        HclTokens.DQ_NEWLINE,
        HclTokens.HD_NEWLINE,
        -> out += RawHclToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        HclTokens.HASH_COMMENT,
        HclTokens.LINE_COMMENT,
        HclTokens.BLOCK_COMMENT,
        -> out += RawHclToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        HclTokens.HEREDOC_OPEN -> {
            val opLen = if (start + 2 < end && source[start + 2] == '-') 3 else 2
            out += RawHclToken(HclTokens.LT, start, start + opLen, SyntaxTokenKind.Operator, isHidden = false)
            if (start + opLen < end) {
                out += RawHclToken(type, start + opLen, end, SyntaxTokenKind.Keyword, isHidden = false)
            }
        }

        HclTokens.HEREDOC_CLOSE -> {
            var indentEnd = start
            while (indentEnd < end && (source[indentEnd] == ' ' || source[indentEnd] == '\t')) {
                indentEnd++
            }
            if (indentEnd > start) {
                out += RawHclToken(HclTokens.WS, start, indentEnd, SyntaxTokenKind.Whitespace, isHidden = true)
            }
            if (indentEnd < end) {
                out += RawHclToken(type, indentEnd, end, SyntaxTokenKind.Keyword, isHidden = false)
            }
        }

        HclTokens.DQ_INTERP_OPEN,
        HclTokens.HD_INTERP_OPEN,
        HclTokens.DQ_DIR_OPEN,
        HclTokens.HD_DIR_OPEN,
        HclTokens.TEMPLATE_CLOSE,
        -> out += RawHclToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)

        HclTokens.LBRACE,
        HclTokens.RBRACE,
        HclTokens.LBRACK,
        HclTokens.RBRACK,
        HclTokens.LPAREN,
        HclTokens.RPAREN,
        HclTokens.COMMA,
        HclTokens.DOUBLE_COLON,
        HclTokens.DOT,
        -> out += RawHclToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)

        HclTokens.ELLIPSIS,
        HclTokens.FAT_ARROW,
        HclTokens.EQ_EQ,
        HclTokens.NEQ,
        HclTokens.LE,
        HclTokens.GE,
        HclTokens.AND_AND,
        HclTokens.OR_OR,
        HclTokens.EQ,
        HclTokens.COLON,
        HclTokens.QUESTION,
        HclTokens.PLUS,
        HclTokens.MINUS,
        HclTokens.STAR,
        HclTokens.SLASH,
        HclTokens.PERCENT,
        HclTokens.BANG,
        HclTokens.LT,
        HclTokens.GT,
        -> out += RawHclToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)

        HclTokens.FLOAT,
        HclTokens.INT,
        -> out += RawHclToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        HclTokens.DQ_ESC_DOLLAR,
        HclTokens.DQ_ESC_PERCENT,
        HclTokens.DQ_ESCAPE,
        HclTokens.HD_ESC_DOLLAR,
        HclTokens.HD_ESC_PERCENT,
        -> out += RawHclToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        HclTokens.DQUOTE_OPEN,
        HclTokens.DQ_CLOSE,
        HclTokens.DQ_BACKSLASH,
        HclTokens.DQ_DOLLAR,
        HclTokens.DQ_PERCENT,
        HclTokens.DQ_TEXT,
        HclTokens.HD_DOLLAR,
        HclTokens.HD_PERCENT,
        HclTokens.HD_TEXT,
        -> out += RawHclToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        HclTokens.IDENT ->
            out += RawHclToken(type, start, end, SyntaxTokenKind.Plain, isHidden = false)

        else ->
            out += RawHclToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)
    }
}

private val BLOCK_KEYWORDS = setOf(
    "resource",
    "data",
    "variable",
    "output",
    "locals",
    "module",
    "provider",
    "terraform",
    "moved",
    "import",
    "removed",
    "check",
    "assert",
    "dynamic",
    "lifecycle",
    "connection",
    "provisioner",
    "backend",
    "required_providers",
    "ephemeral",
    "action",
)

private val EXPR_KEYWORDS = setOf("for", "in", "if")
private val DIRECTIVE_KEYWORDS = setOf("for", "in", "if", "else", "endif", "endfor")
private val CONSTANTS = setOf("true", "false", "null")
private val TYPE_CONSTRUCTORS = setOf("string", "number", "bool", "any", "list", "set", "map", "object", "tuple")

private class TemplateContext(
    val isDirective: Boolean,
    var braceDepth: Int = 0,
)

private class HclScope(
    val isBrace: Boolean,
    var expectingForColon: Boolean = false,
    var pendingTernaryColons: Int = 0,
)

private fun classifyHclTokens(source: String, raw: List<RawHclToken>) {
    val templateStack = ArrayList<TemplateContext>()
    val scopeStack = ArrayList<HclScope>().apply {
        add(HclScope(isBrace = false))
    }
    var atStmtStart = true
    var prevVisibleType = -1

    fun nextVisibleIndex(from: Int): Int {
        var idx = from
        while (idx < raw.size) {
            if (!raw[idx].isHidden) return idx
            idx++
        }
        return -1
    }

    for (i in raw.indices) {
        val tok = raw[i]

        if (tok.type == HclTokens.NEWLINE) {
            atStmtStart = true
            continue
        }

        if (tok.isHidden) continue

        when (tok.type) {
            HclTokens.DQ_INTERP_OPEN,
            HclTokens.HD_INTERP_OPEN,
            -> {
                templateStack.add(TemplateContext(isDirective = false))
                scopeStack.add(HclScope(isBrace = false))
                atStmtStart = true
                prevVisibleType = tok.type
                continue
            }

            HclTokens.DQ_DIR_OPEN,
            HclTokens.HD_DIR_OPEN,
            -> {
                templateStack.add(TemplateContext(isDirective = true))
                scopeStack.add(HclScope(isBrace = false))
                atStmtStart = true
                prevVisibleType = tok.type
                continue
            }

            HclTokens.LBRACE -> {
                templateStack.lastOrNull()?.let { it.braceDepth++ }
                scopeStack.add(HclScope(isBrace = true))
                atStmtStart = true
                prevVisibleType = tok.type
                continue
            }

            HclTokens.RBRACE -> {
                val top = templateStack.lastOrNull()
                if (top != null && top.braceDepth > 0) {
                    top.braceDepth--
                }
                if (scopeStack.size > 1 && scopeStack.last().isBrace) {
                    scopeStack.removeAt(scopeStack.lastIndex)
                }
                atStmtStart = true
                prevVisibleType = tok.type
                continue
            }

            HclTokens.LBRACK,
            HclTokens.LPAREN,
            -> {
                scopeStack.add(HclScope(isBrace = false))
                atStmtStart = false
                prevVisibleType = tok.type
                continue
            }

            HclTokens.RBRACK,
            HclTokens.RPAREN,
            -> {
                if (scopeStack.size > 1 && !scopeStack.last().isBrace) {
                    scopeStack.removeAt(scopeStack.lastIndex)
                }
                atStmtStart = false
                prevVisibleType = tok.type
                continue
            }

            HclTokens.TEMPLATE_CLOSE -> {
                if (templateStack.isNotEmpty()) {
                    templateStack.removeAt(templateStack.lastIndex)
                }
                if (scopeStack.size > 1 && !scopeStack.last().isBrace) {
                    scopeStack.removeAt(scopeStack.lastIndex)
                }
                atStmtStart = false
                prevVisibleType = tok.type
                continue
            }

            HclTokens.QUESTION -> {
                scopeStack.last().pendingTernaryColons++
                atStmtStart = false
                prevVisibleType = tok.type
                continue
            }

            HclTokens.COLON -> {
                val scope = scopeStack.last()
                if (scope.pendingTernaryColons > 0) {
                    scope.pendingTernaryColons--
                } else if (scope.expectingForColon) {
                    scope.expectingForColon = false
                }
                atStmtStart = false
                prevVisibleType = tok.type
                continue
            }
        }

        val nextIdx = nextVisibleIndex(i + 1)
        val nextType = if (nextIdx >= 0) raw[nextIdx].type else -1

        if (tok.type == HclTokens.IDENT) {
            val text = source.substring(tok.start, tok.end)
            val afterAccessor = prevVisibleType == HclTokens.DOT || prevVisibleType == HclTokens.DOUBLE_COLON
            val inDirective = templateStack.lastOrNull()?.let { it.isDirective && it.braceDepth == 0 } == true
            val scope = scopeStack.last()
            val isObjectColonKey = scope.isBrace &&
                !afterAccessor &&
                nextType == HclTokens.COLON &&
                !scope.expectingForColon &&
                scope.pendingTernaryColons == 0

            tok.kind = when {
                !afterAccessor && (nextType == HclTokens.EQ || isObjectColonKey) -> SyntaxTokenKind.Property
                inDirective && !afterAccessor && text in DIRECTIVE_KEYWORDS &&
                    nextType != HclTokens.DOT && nextType != HclTokens.LPAREN -> SyntaxTokenKind.Keyword
                atStmtStart && !afterAccessor && text in BLOCK_KEYWORDS &&
                    nextType != HclTokens.DOT && nextType != HclTokens.DOUBLE_COLON &&
                    nextType != HclTokens.LPAREN -> SyntaxTokenKind.Keyword
                !afterAccessor && text in EXPR_KEYWORDS &&
                    nextType != HclTokens.DOT && nextType != HclTokens.DOUBLE_COLON &&
                    nextType != HclTokens.LPAREN -> {
                    if (text == "for") {
                        scope.expectingForColon = true
                    }
                    SyntaxTokenKind.Keyword
                }
                !afterAccessor && text in CONSTANTS -> SyntaxTokenKind.Constant
                !afterAccessor && text in TYPE_CONSTRUCTORS -> SyntaxTokenKind.Type
                nextType == HclTokens.LPAREN -> SyntaxTokenKind.Function
                prevVisibleType == HclTokens.DOT -> SyntaxTokenKind.Property
                else -> SyntaxTokenKind.Plain
            }
        }

        atStmtStart = tok.type == HclTokens.RBRACE || tok.type == HclTokens.LBRACE
        prevVisibleType = tok.type
    }
}
