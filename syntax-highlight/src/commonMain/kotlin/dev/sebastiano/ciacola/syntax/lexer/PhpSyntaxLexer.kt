package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.php.PhpLexer
import dev.sebastiano.ciacola.syntax.antlr.php.PhpLexer.Tokens as PhpTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for PHP 8.3/8.4, delegating HTML sections
 * outside `<?php` / `<?=` / `<?` ... `?>` boundaries to [HtmlSyntaxLexer].
 */
internal object PhpSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Php

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = PhpLexer(antlrCharStream(source))
        val raw = ArrayList<RawPhpToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawPhpToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) PhpTokens.WS else PhpTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawPhpToken(source, type, start, end, raw) },
        )

        classifyPhpTokens(source, raw)

        val out = ArrayList<SyntaxToken>(raw.size)
        var i = 0
        while (i < raw.size) {
            val tok = raw[i]
            if (tok.type == PhpTokens.HTML_CHUNK) {
                val htmlStart = tok.start
                var htmlEnd = tok.end
                var j = i + 1
                while (j < raw.size && raw[j].type == PhpTokens.HTML_CHUNK) {
                    htmlEnd = raw[j].end
                    j++
                }
                val htmlTokens = HtmlSyntaxLexer.lex(source.substring(htmlStart, htmlEnd))
                for (ht in htmlTokens) {
                    out += SyntaxToken(
                        kind = ht.kind,
                        startIndex = htmlStart + ht.startIndex,
                        endIndex = htmlStart + ht.endIndex,
                    )
                }
                i = j
            } else {
                out += SyntaxToken(kind = tok.kind, startIndex = tok.start, endIndex = tok.end)
                i++
            }
        }
        return out
    }
}

private class RawPhpToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun emitRawPhpToken(
    source: String,
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawPhpToken>,
) {
    when (type) {
        PhpTokens.WS ->
            out += RawPhpToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        PhpTokens.DOC_COMMENT,
        PhpTokens.BLOCK_COMMENT,
        PhpTokens.UNTERMINATED_BLOCK_COMMENT,
        PhpTokens.LINE_COMMENT,
        PhpTokens.HASH_COMMENT,
        -> out += RawPhpToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        PhpTokens.PHP_OPEN_ECHO,
        PhpTokens.PHP_OPEN_FULL,
        PhpTokens.PHP_OPEN_SHORT,
        PhpTokens.PHP_CLOSE,
        -> out += RawPhpToken(type, start, end, SyntaxTokenKind.Preprocessor, isHidden = false)

        PhpTokens.DSTR_ARROW_PROP,
        PhpTokens.BT_ARROW_PROP,
        PhpTokens.HEREDOC_ARROW_PROP,
        -> {
            val arrowLen = if (source.startsWith("?->", start)) 3 else 2
            val split = (start + arrowLen).coerceAtMost(end)
            out += RawPhpToken(PhpTokens.ARROW, start, split, SyntaxTokenKind.Operator, isHidden = false)
            if (split < end) {
                out += RawPhpToken(PhpTokens.IDENT, split, end, SyntaxTokenKind.Property, isHidden = true)
            }
        }

        PhpTokens.NOWDOC,
        PhpTokens.HEREDOC_START,
        PhpTokens.HEREDOC_NL,
        PhpTokens.HEREDOC_ESCAPE,
        PhpTokens.HEREDOC_BACKSLASH,
        PhpTokens.HEREDOC_TEXT,
        PhpTokens.SINGLE_STRING,
        PhpTokens.UNTERMINATED_SINGLE_STRING,
        PhpTokens.DSTR_OPEN,
        PhpTokens.DSTR_CLOSE,
        PhpTokens.DSTR_ESCAPE,
        PhpTokens.DSTR_BACKSLASH,
        PhpTokens.DSTR_TEXT,
        PhpTokens.BACKTICK_OPEN,
        PhpTokens.BT_CLOSE,
        PhpTokens.BT_ESCAPE,
        PhpTokens.BT_BACKSLASH,
        PhpTokens.BT_TEXT,
        -> out += RawPhpToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        PhpTokens.INTERP_OPEN,
        PhpTokens.INTERP_CLOSE,
        PhpTokens.DSTR_DOLLAR_CURLY_OPEN,
        PhpTokens.BT_DOLLAR_CURLY_OPEN,
        PhpTokens.HEREDOC_DOLLAR_CURLY_OPEN,
        PhpTokens.DSTR_INDEX_OPEN,
        PhpTokens.BT_INDEX_OPEN,
        PhpTokens.HEREDOC_INDEX_OPEN,
        PhpTokens.SIDX_CLOSE,
        -> out += RawPhpToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)

        PhpTokens.HEX_LITERAL,
        PhpTokens.BIN_LITERAL,
        PhpTokens.OCT_LITERAL,
        PhpTokens.FLOAT_LITERAL,
        PhpTokens.DECIMAL_LITERAL,
        PhpTokens.SIDX_NUM,
        -> out += RawPhpToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        PhpTokens.VAR_IDENT,
        PhpTokens.DSTR_VAR,
        PhpTokens.BT_VAR,
        PhpTokens.HEREDOC_VAR,
        PhpTokens.SIDX_VAR,
        PhpTokens.SIDX_IDENT,
        PhpTokens.HTML_CHUNK,
        -> out += RawPhpToken(type, start, end, SyntaxTokenKind.Plain, isHidden = false)

        PhpTokens.SIDX_MINUS ->
            out += RawPhpToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)

        PhpTokens.ERROR_CHAR ->
            out += RawPhpToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> {
            val initialKind = when {
                isPhpOperator(type) -> SyntaxTokenKind.Operator
                isPhpPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawPhpToken(type, start, end, initialKind, isHidden = false)
        }
    }
}

private fun classifyPhpTokens(code: String, raw: List<RawPhpToken>) {
    val visible = ArrayList<Int>(raw.size)
    for (idx in raw.indices) {
        if (!raw[idx].isHidden) visible += idx
    }

    var inNamespaceOrUse = false
    var inReturnType = false
    var expectingFunctionName = false
    var expectingTypeName = false
    var inAttribute = false
    var attrNestedBracketDepth = 0
    var attrParenDepth = 0
    var parenDepth = 0
    var braceDepth = 0
    var ternaryDepth = 0

    for (i in visible.indices) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null

        if (tok.type == PhpTokens.HTML_CHUNK) {
            continue
        }

        if (tok.type == PhpTokens.PHP_OPEN_FULL ||
            tok.type == PhpTokens.PHP_OPEN_ECHO ||
            tok.type == PhpTokens.PHP_OPEN_SHORT ||
            tok.type == PhpTokens.PHP_CLOSE
        ) {
            inNamespaceOrUse = false
            inReturnType = false
            expectingFunctionName = false
            expectingTypeName = false
            inAttribute = false
            attrNestedBracketDepth = 0
            attrParenDepth = 0
            ternaryDepth = 0
            continue
        }

        if (tok.type == PhpTokens.SEMI) {
            inNamespaceOrUse = false
            inReturnType = false
            expectingFunctionName = false
            expectingTypeName = false
            ternaryDepth = 0
        }

        if (inReturnType && (tok.type == PhpTokens.LBRACE || tok.type == PhpTokens.DOUBLE_ARROW)) {
            inReturnType = false
        }

        if (inAttribute) {
            when (tok.type) {
                PhpTokens.LBRACK -> attrNestedBracketDepth++
                PhpTokens.RBRACK -> {
                    if (attrNestedBracketDepth > 0) {
                        attrNestedBracketDepth--
                    } else {
                        inAttribute = false
                    }
                    continue
                }
                PhpTokens.LPAREN -> {
                    parenDepth++
                    attrParenDepth++
                    continue
                }
                PhpTokens.RPAREN -> {
                    if (parenDepth > 0) parenDepth--
                    if (attrParenDepth > 0) attrParenDepth--
                    continue
                }
            }
            if (tok.type == PhpTokens.IDENT && attrNestedBracketDepth == 0 && attrParenDepth == 0) {
                tok.kind = SyntaxTokenKind.Annotation
                continue
            }
        }

        when (tok.type) {
            PhpTokens.ATTR_OPEN -> {
                inAttribute = true
                attrNestedBracketDepth = 0
                attrParenDepth = 0
                continue
            }
            PhpTokens.LPAREN -> parenDepth++
            PhpTokens.RPAREN -> if (parenDepth > 0) parenDepth--
            PhpTokens.LBRACE -> braceDepth++
            PhpTokens.RBRACE -> if (braceDepth > 0) braceDepth--
            PhpTokens.QUESTION -> {
                if (next?.type != PhpTokens.IDENT && next?.type != PhpTokens.BACKSLASH && next?.type != PhpTokens.LPAREN) {
                    ternaryDepth++
                } else if (!isPhpNullableTypeContext(prev, next, inReturnType, parenDepth)) {
                    ternaryDepth++
                }
            }
            PhpTokens.COLON -> {
                if (ternaryDepth > 0) {
                    ternaryDepth--
                } else if (prev?.type == PhpTokens.RPAREN) {
                    inReturnType = true
                }
            }
        }

        if (tok.type != PhpTokens.IDENT) {
            if (tok.type != PhpTokens.BITAND) {
                expectingFunctionName = false
            }
            continue
        }

        val text = code.substring(tok.start, tok.end)
        val lower = text.lowercase()
        val prevLower = if (prev?.type == PhpTokens.IDENT) code.substring(prev.start, prev.end).lowercase() else null

        if (expectingFunctionName) {
            tok.kind = SyntaxTokenKind.Function
            expectingFunctionName = false
            continue
        }
        if (expectingTypeName) {
            tok.kind = SyntaxTokenKind.Type
            expectingTypeName = false
            continue
        }

        if (prev?.type == PhpTokens.ARROW || prev?.type == PhpTokens.NULLSAFE_ARROW) {
            tok.kind = if (next?.type == PhpTokens.LPAREN) SyntaxTokenKind.Function else SyntaxTokenKind.Property
            continue
        }

        if (prev?.type == PhpTokens.COLONCOLON) {
            tok.kind = when {
                lower == "class" -> SyntaxTokenKind.Keyword
                next?.type == PhpTokens.LPAREN -> SyntaxTokenKind.Function
                isAllCapsConst(text) -> SyntaxTokenKind.Constant
                else -> SyntaxTokenKind.Plain
            }
            continue
        }

        // Named arguments in calls or PHP 8 attributes (`foo(name: 1)`, `#[Route('/api', methods: ['GET'])]`)
        if (next?.type == PhpTokens.COLON && parenDepth > 0 && (prev?.type == PhpTokens.LPAREN || prev?.type == PhpTokens.COMMA)) {
            tok.kind = SyntaxTokenKind.Property
            continue
        }

        // PHP type casts: `(int)$x`, `(array)$x`, `(string)$x`, `(bool)$x`, `(float)$x`, `(object)$x`, `(unset)$x`, `(binary)$x`
        if (prev?.type == PhpTokens.LPAREN && next?.type == PhpTokens.RPAREN && lower in PHP_CAST_TYPES) {
            tok.kind = SyntaxTokenKind.Type
            continue
        }

        if (lower in PHP_MAGIC_CONSTANTS) {
            tok.kind = SyntaxTokenKind.Constant
            continue
        }

        val inTypePos = isPhpTypePosition(prev, prevLower, next, inReturnType)

        if (lower in PHP_TYPE_OR_CONSTANT_KEYWORDS) {
            tok.kind = if (inTypePos) SyntaxTokenKind.Type else SyntaxTokenKind.Constant
            continue
        }

        if (lower in PHP_BUILTIN_TYPES) {
            tok.kind = SyntaxTokenKind.Type
            continue
        }

        if (lower == "array" || lower == "callable") {
            tok.kind = if (inTypePos && next?.type != PhpTokens.LPAREN) SyntaxTokenKind.Type else SyntaxTokenKind.Keyword
            continue
        }

        if (lower in PHP_KEYWORDS) {
            tok.kind = SyntaxTokenKind.Keyword
            when (lower) {
                "namespace",
                "use",
                -> if (prev?.type != PhpTokens.RPAREN) inNamespaceOrUse = true

                "function" -> if (next?.type != PhpTokens.LPAREN) expectingFunctionName = true

                "class",
                "interface",
                "trait",
                "enum",
                -> if (prevLower != "new") expectingTypeName = true
            }
            continue
        }

        if (inNamespaceOrUse) {
            tok.kind = if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Plain
            continue
        }

        if (next?.type == PhpTokens.LPAREN) {
            tok.kind = if (prevLower == "new" && looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Function
            continue
        }

        if (looksLikeTypeName(text)) {
            tok.kind = SyntaxTokenKind.Type
            continue
        }

        if (isAllCapsConst(text)) {
            tok.kind = SyntaxTokenKind.Constant
            continue
        }

        tok.kind = SyntaxTokenKind.Plain
    }
}

private fun isPhpNullableTypeContext(
    prev: RawPhpToken?,
    next: RawPhpToken?,
    inReturnType: Boolean,
    parenDepth: Int,
): Boolean {
    if (inReturnType) return true
    if (prev == null) return false
    val validPrev = prev.type == PhpTokens.COLON ||
        prev.type == PhpTokens.LPAREN ||
        prev.type == PhpTokens.COMMA ||
        prev.type == PhpTokens.BITOR ||
        prev.type == PhpTokens.BITAND ||
        prev.type == PhpTokens.IDENT
    return validPrev && (parenDepth > 0 || prev.type == PhpTokens.IDENT) &&
        (next?.type == PhpTokens.IDENT || next?.type == PhpTokens.BACKSLASH)
}

private fun isPhpTypePosition(
    prev: RawPhpToken?,
    prevLower: String?,
    next: RawPhpToken?,
    inReturnType: Boolean,
): Boolean {
    if (inReturnType) return true
    if (prev?.type == PhpTokens.QUESTION || prev?.type == PhpTokens.BITOR || prev?.type == PhpTokens.BITAND) {
        return true
    }
    if (next?.type == PhpTokens.VAR_IDENT || next?.type == PhpTokens.ELLIPSIS || next?.type == PhpTokens.BITOR || next?.type == PhpTokens.BITAND) {
        return true
    }
    if (prevLower in PHP_TYPE_MODIFIER_PREDECESSORS) {
        return true
    }
    return false
}

private fun isAllCapsConst(text: String): Boolean =
    text.length > 1 && text[0].isUpperCase() && text.all { it.isUpperCase() || it.isDigit() || it == '_' }

private fun isPhpOperator(type: Int): Boolean = when (type) {
    PhpTokens.SPACESHIP,
    PhpTokens.NULLSAFE_ARROW,
    PhpTokens.NULL_COALESCE_ASSIGN,
    PhpTokens.NULL_COALESCE,
    PhpTokens.IDENTICAL,
    PhpTokens.NOT_IDENTICAL,
    PhpTokens.POW_ASSIGN,
    PhpTokens.POW,
    PhpTokens.RSHIFT_ASSIGN,
    PhpTokens.LSHIFT_ASSIGN,
    PhpTokens.RSHIFT,
    PhpTokens.LSHIFT,
    PhpTokens.ARROW,
    PhpTokens.DOUBLE_ARROW,
    PhpTokens.DIAMOND_NE,
    PhpTokens.INC,
    PhpTokens.DEC,
    PhpTokens.AND_AND,
    PhpTokens.OR_OR,
    PhpTokens.EQ,
    PhpTokens.NE,
    PhpTokens.LE,
    PhpTokens.GE,
    PhpTokens.CONCAT_ASSIGN,
    PhpTokens.ADD_ASSIGN,
    PhpTokens.SUB_ASSIGN,
    PhpTokens.MUL_ASSIGN,
    PhpTokens.DIV_ASSIGN,
    PhpTokens.MOD_ASSIGN,
    PhpTokens.AND_ASSIGN,
    PhpTokens.OR_ASSIGN,
    PhpTokens.XOR_ASSIGN,
    PhpTokens.ASSIGN,
    PhpTokens.GT,
    PhpTokens.LT,
    PhpTokens.BANG,
    PhpTokens.TILDE,
    PhpTokens.QUESTION,
    PhpTokens.PLUS,
    PhpTokens.MINUS,
    PhpTokens.STAR,
    PhpTokens.SLASH,
    PhpTokens.BITAND,
    PhpTokens.BITOR,
    PhpTokens.CARET,
    PhpTokens.MOD,
    PhpTokens.AT,
    PhpTokens.DOLLAR,
    PhpTokens.DOT,
    -> true
    else -> false
}

private fun isPhpPunctuation(type: Int): Boolean = when (type) {
    PhpTokens.ATTR_OPEN,
    PhpTokens.ELLIPSIS,
    PhpTokens.COLONCOLON,
    PhpTokens.LPAREN,
    PhpTokens.RPAREN,
    PhpTokens.LBRACE,
    PhpTokens.RBRACE,
    PhpTokens.LBRACK,
    PhpTokens.RBRACK,
    PhpTokens.SEMI,
    PhpTokens.COMMA,
    PhpTokens.COLON,
    PhpTokens.BACKSLASH,
    -> true
    else -> false
}

private val PHP_KEYWORDS = setOf(
    "abstract", "and", "as", "break", "case", "catch", "class", "clone", "const",
    "continue", "declare", "default", "do", "echo", "else", "elseif", "empty",
    "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile", "enum",
    "eval", "exit", "die", "extends", "final", "finally", "fn", "for", "foreach",
    "function", "global", "goto", "if", "implements", "include", "include_once",
    "instanceof", "insteadof", "interface", "isset", "list", "match", "namespace",
    "new", "or", "print", "private", "protected", "public", "readonly", "require",
    "require_once", "return", "static", "switch", "throw", "trait", "try", "unset",
    "use", "var", "while", "xor", "yield", "from", "parent", "self",
)

private val PHP_BUILTIN_TYPES = setOf(
    "int", "float", "string", "bool", "void", "never", "mixed", "object", "iterable",
)

private val PHP_TYPE_OR_CONSTANT_KEYWORDS = setOf(
    "null", "false", "true",
)

private val PHP_CAST_TYPES = setOf(
    "int", "integer", "bool", "boolean", "float", "double", "real",
    "string", "array", "object", "unset", "binary",
)

private val PHP_TYPE_MODIFIER_PREDECESSORS = setOf(
    "public", "protected", "private", "readonly", "var", "static", "const",
)

private val PHP_MAGIC_CONSTANTS = setOf(
    "__line__", "__file__", "__dir__", "__function__", "__class__",
    "__trait__", "__method__", "__namespace__", "__property__",
)
