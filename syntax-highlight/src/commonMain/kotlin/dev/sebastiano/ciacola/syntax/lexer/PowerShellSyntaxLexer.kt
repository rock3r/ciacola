package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.powershell.PowerShellLexer
import dev.sebastiano.ciacola.syntax.antlr.powershell.PowerShellLexer.Tokens as PsTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for PowerShell 5.1 / 7.x source code.
 */
internal object PowerShellSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.PowerShell

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = PowerShellLexer(antlrCharStream(source))
        val raw = ArrayList<RawPsToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawPsToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) PsTokens.WS else PsTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawPsToken(source, type, start, end, raw) },
        )

        classifyPowerShellTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawPsToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun emitRawPsToken(
    source: String,
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawPsToken>,
) {
    when (type) {
        PsTokens.WS,
        PsTokens.NEWLINE,
        PsTokens.LINE_CONTINUATION,
        -> out += RawPsToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        PsTokens.BLOCK_COMMENT,
        PsTokens.UNTERMINATED_BLOCK_COMMENT,
        PsTokens.LINE_COMMENT,
        -> out += RawPsToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        PsTokens.HEREDOC_EXPAND_START,
        PsTokens.HEREDOC_LITERAL_START,
        PsTokens.DQUOTE_START,
        PsTokens.SINGLE_STRING,
        PsTokens.DQ_ESCAPED_QUOTE,
        PsTokens.DQ_CLOSE,
        PsTokens.DQ_UNICODE_ESCAPE,
        PsTokens.DQ_ESCAPE,
        PsTokens.DQ_BACKTICK,
        PsTokens.DQ_DOLLAR,
        PsTokens.DQ_TEXT,
        PsTokens.HE_CLOSE,
        PsTokens.HE_UNICODE_ESCAPE,
        PsTokens.HE_ESCAPE,
        PsTokens.HE_BACKTICK,
        PsTokens.HE_DOLLAR,
        PsTokens.HE_NEWLINE,
        PsTokens.HE_DQUOTE_AT,
        PsTokens.HE_QUOTE,
        PsTokens.HE_TEXT,
        PsTokens.HL_CLOSE,
        PsTokens.HL_NEWLINE,
        PsTokens.HL_SQUOTE_AT,
        PsTokens.HL_SQUOTE,
        PsTokens.HL_TEXT,
        -> out += RawPsToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        PsTokens.HEX_LITERAL,
        PsTokens.BIN_LITERAL,
        PsTokens.FLOAT_LITERAL,
        PsTokens.DECIMAL_LITERAL,
        -> out += RawPsToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        PsTokens.SPECIAL_VAR ->
            out += RawPsToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        PsTokens.SIMPLE_VAR -> {
            val kind = if (isPowerShellConstantVar(source.substring(start, end))) {
                SyntaxTokenKind.Constant
            } else {
                SyntaxTokenKind.Property
            }
            out += RawPsToken(type, start, end, kind, isHidden = false)
        }

        PsTokens.DQ_SPECIAL_VAR,
        PsTokens.HE_SPECIAL_VAR,
        -> out += RawPsToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        PsTokens.DQ_SIMPLE_VAR,
        PsTokens.HE_SIMPLE_VAR,
        -> {
            val kind = if (isPowerShellConstantVar(source.substring(start, end))) {
                SyntaxTokenKind.Constant
            } else {
                SyntaxTokenKind.Property
            }
            out += RawPsToken(type, start, end, kind, isHidden = false)
        }

        PsTokens.BRACED_VAR,
        PsTokens.DQ_BRACED_VAR,
        PsTokens.HE_BRACED_VAR,
        -> {
            val inner = source.substring(start + 2, if (source[end - 1] == '}') end - 1 else end)
            val kind = if (isPowerShellConstantVar("$$inner")) {
                SyntaxTokenKind.Constant
            } else {
                SyntaxTokenKind.Property
            }
            out += RawPsToken(type, start, end, kind, isHidden = false)
        }

        PsTokens.SCOPED_VAR,
        PsTokens.DQ_SCOPED_VAR,
        PsTokens.HE_SCOPED_VAR,
        PsTokens.SPLAT_VAR,
        -> out += RawPsToken(type, start, end, SyntaxTokenKind.Property, isHidden = false)

        PsTokens.CLI_LONG_FLAG ->
            out += RawPsToken(type, start, end, SyntaxTokenKind.Attribute, isHidden = false)

        PsTokens.DASH_WORD -> {
            val lower = source.substring(start, end).lowercase()
            val kind = if (lower in PS_DASH_OPERATORS) SyntaxTokenKind.Operator else SyntaxTokenKind.Attribute
            out += RawPsToken(type, start, end, kind, isHidden = false)
        }

        PsTokens.STREAM_REDIR,
        PsTokens.STOP_PARSING,
        PsTokens.NULL_COALESCE_ASSIGN,
        PsTokens.NULL_COALESCE,
        PsTokens.NULL_COND_DOT,
        PsTokens.AND_AND,
        PsTokens.OR_OR,
        PsTokens.RANGE,
        PsTokens.GT_APPEND,
        PsTokens.INC,
        PsTokens.DEC,
        PsTokens.ADD_ASSIGN,
        PsTokens.SUB_ASSIGN,
        PsTokens.MUL_ASSIGN,
        PsTokens.DIV_ASSIGN,
        PsTokens.MOD_ASSIGN,
        PsTokens.ASSIGN,
        PsTokens.PIPE,
        PsTokens.AMP,
        PsTokens.GT,
        PsTokens.LT,
        PsTokens.BANG,
        PsTokens.PLUS,
        PsTokens.MINUS,
        PsTokens.STAR,
        PsTokens.SLASH,
        PsTokens.MOD,
        PsTokens.QUESTION,
        -> out += RawPsToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)

        PsTokens.SUBEXPR_START,
        PsTokens.DQ_SUBEXPR_START,
        PsTokens.HE_SUBEXPR_START,
        PsTokens.SUBEXPR_CLOSE,
        PsTokens.ARRAY_SUBEXPR_START,
        PsTokens.HASH_LITERAL_START,
        PsTokens.COLONCOLON,
        PsTokens.LPAREN,
        PsTokens.RPAREN,
        PsTokens.LBRACE,
        PsTokens.RBRACE,
        PsTokens.LBRACK,
        PsTokens.RBRACK,
        PsTokens.SEMI,
        PsTokens.COMMA,
        PsTokens.DOT,
        PsTokens.COLON,
        PsTokens.BACKSLASH,
        -> out += RawPsToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)

        PsTokens.BACKTICK_ESCAPE ->
            out += RawPsToken(type, start, end, SyntaxTokenKind.Plain, isHidden = false)

        PsTokens.ERROR_CHAR,
        PsTokens.BACKTICK,
        PsTokens.AT,
        PsTokens.DOLLAR,
        -> out += RawPsToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> out += RawPsToken(type, start, end, SyntaxTokenKind.Plain, isHidden = false)
    }
}

private enum class PsBracketKind {
    INDEX,
    TYPE,
    ATTRIBUTE,
}

private class PsBracketFrame(
    val kind: PsBracketKind,
    var parenDepth: Int = 0,
    var inAttrHeader: Boolean = kind == PsBracketKind.ATTRIBUTE,
)

private enum class PsDelimKind {
    BRACE,
    HASH,
    PAREN,
}

private class PsDelimFrame(
    val kind: PsDelimKind,
    val savedInCommandArgs: Boolean,
)

private fun classifyPowerShellTokens(code: String, raw: List<RawPsToken>) {
    val visible = ArrayList<Int>(raw.size)
    for (idx in raw.indices) {
        if (!raw[idx].isHidden) visible += idx
    }

    val bracketStack = ArrayList<PsBracketFrame>()
    val delimStack = ArrayList<PsDelimFrame>()
    var expectingTypeName = false
    var expectingFuncName = false
    var inStmtStart = true
    var inCommandArgs = false

    for (i in visible.indices) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val topBracket = bracketStack.lastOrNull()

        val sawNewline = i > 0 && hasPowerShellNewlineBetween(raw, visible[i - 1], visible[i])
        if (sawNewline && !continuesAcrossNewline(prev?.type)) {
            inCommandArgs = false
            inStmtStart = true
        }

        when (tok.type) {
            PsTokens.LBRACK -> {
                val kind = classifyOpenBracket(raw, visible, i, prev, topBracket)
                bracketStack.add(PsBracketFrame(kind = kind))
                expectingTypeName = false
                expectingFuncName = false
                inStmtStart = false
                continue
            }

            PsTokens.RBRACK -> {
                val popped = if (bracketStack.isNotEmpty()) {
                    bracketStack.removeAt(bracketStack.lastIndex)
                } else {
                    null
                }
                expectingTypeName = false
                expectingFuncName = false
                if (popped?.kind == PsBracketKind.ATTRIBUTE) {
                    inStmtStart = true
                    inCommandArgs = false
                } else {
                    inStmtStart = false
                }
                continue
            }

            PsTokens.LPAREN,
            PsTokens.SUBEXPR_START,
            PsTokens.DQ_SUBEXPR_START,
            PsTokens.HE_SUBEXPR_START,
            PsTokens.ARRAY_SUBEXPR_START,
            -> {
                if (tok.type == PsTokens.LPAREN && topBracket != null) {
                    topBracket.parenDepth++
                    if (topBracket.kind == PsBracketKind.ATTRIBUTE) {
                        topBracket.inAttrHeader = false
                    }
                }
                delimStack.add(PsDelimFrame(kind = PsDelimKind.PAREN, savedInCommandArgs = inCommandArgs))
                inCommandArgs = false
                inStmtStart = true
                expectingTypeName = false
                expectingFuncName = false
                continue
            }

            PsTokens.RPAREN,
            PsTokens.SUBEXPR_CLOSE,
            -> {
                if (tok.type == PsTokens.RPAREN && topBracket != null && topBracket.parenDepth > 0) {
                    topBracket.parenDepth--
                }
                val frame = popMatchingDelim(delimStack, isBraceClose = false)
                inCommandArgs = frame?.savedInCommandArgs ?: false
                inStmtStart = false
                expectingTypeName = false
                expectingFuncName = false
                continue
            }

            PsTokens.LBRACE,
            PsTokens.HASH_LITERAL_START,
            -> {
                val kind = if (tok.type == PsTokens.HASH_LITERAL_START) PsDelimKind.HASH else PsDelimKind.BRACE
                delimStack.add(PsDelimFrame(kind = kind, savedInCommandArgs = inCommandArgs))
                inCommandArgs = false
                inStmtStart = true
                expectingTypeName = false
                expectingFuncName = false
                continue
            }

            PsTokens.RBRACE -> {
                val frame = popMatchingDelim(delimStack, isBraceClose = true)
                inCommandArgs = frame?.savedInCommandArgs ?: false
                inStmtStart = !inCommandArgs
                expectingTypeName = false
                expectingFuncName = false
                continue
            }
        }

        if (tok.type == PsTokens.HASH) {
            // Unquoted mid-word `#` in argument mode (`echo foo#bar`) is Plain rather than Invalid.
            if (prev != null && prev.end == tok.start && prev.kind != SyntaxTokenKind.Whitespace) {
                tok.kind = SyntaxTokenKind.Plain
            }
            inStmtStart = false
            continue
        }

        if (tok.type != PsTokens.IDENT && tok.type != PsTokens.CMDLET_NAME) {
            if (tok.type != PsTokens.DOT && tok.type != PsTokens.BACKSLASH) {
                expectingTypeName = false
                expectingFuncName = false
            }
            when (tok.type) {
                PsTokens.SEMI,
                PsTokens.PIPE,
                PsTokens.AND_AND,
                PsTokens.OR_OR,
                -> {
                    inCommandArgs = false
                    inStmtStart = true
                }

                PsTokens.AMP -> if (!inStmtStart) inStmtStart = false

                else -> if (topBracket == null || topBracket.kind == PsBracketKind.INDEX) {
                    inStmtStart = false
                }
            }
            continue
        }

        val text = code.substring(tok.start, tok.end)
        val lower = text.lowercase()

        // Attribute or Type inside [...]
        if (topBracket != null && topBracket.kind != PsBracketKind.INDEX) {
            if (topBracket.kind == PsBracketKind.ATTRIBUTE) {
                if (topBracket.inAttrHeader) {
                    tok.kind = SyntaxTokenKind.Annotation
                    continue
                }
                if (next?.type == PsTokens.ASSIGN) {
                    tok.kind = SyntaxTokenKind.Property
                    continue
                }
            } else {
                // Inside [TypeName] or [System.Collections.Generic.List[string]]
                tok.kind = SyntaxTokenKind.Type
                continue
            }
        }

        // Member access (`.Prop`, `?.Prop`, `::StaticMember`, `.Method()`, `::StaticMethod()`)
        val isMember = prev?.type == PsTokens.DOT ||
            prev?.type == PsTokens.NULL_COND_DOT ||
            prev?.type == PsTokens.COLONCOLON
        if (isMember) {
            tok.kind = when {
                next?.type == PsTokens.LPAREN -> SyntaxTokenKind.Function
                else -> SyntaxTokenKind.Property
            }
            expectingTypeName = false
            expectingFuncName = false
            inStmtStart = false
            continue
        }

        // Module-qualified cmdlet: `Microsoft.PowerShell.Utility\Write-Output`
        if (prev?.type == PsTokens.BACKSLASH) {
            tok.kind = SyntaxTokenKind.Function
            expectingTypeName = false
            expectingFuncName = false
            inCommandArgs = true
            inStmtStart = false
            continue
        }

        if (expectingTypeName) {
            tok.kind = SyntaxTokenKind.Type
            expectingTypeName = false
            inStmtStart = false
            continue
        }

        if (expectingFuncName) {
            tok.kind = SyntaxTokenKind.Function
            expectingFuncName = false
            inStmtStart = false
            continue
        }

        // Unquoted hashtable keys (`@{ Begin = 1; Data = $x }`) take precedence over PS_KEYWORDS.
        val inHashContext = delimStack.lastOrNull()?.kind == PsDelimKind.HASH
        if (
            next?.type == PsTokens.ASSIGN &&
            (
                (inHashContext && inStmtStart) ||
                    prev?.type == PsTokens.HASH_LITERAL_START ||
                    prev?.type == PsTokens.SEMI ||
                    prev?.type == PsTokens.LBRACE ||
                    prev == null
                )
        ) {
            tok.kind = SyntaxTokenKind.Property
            inStmtStart = false
            continue
        }

        if (tok.type == PsTokens.CMDLET_NAME) {
            tok.kind = SyntaxTokenKind.Function
            inCommandArgs = true
            inStmtStart = false
            continue
        }

        if (!inCommandArgs && lower in PS_KEYWORDS) {
            tok.kind = SyntaxTokenKind.Keyword
            inStmtStart = false
            when (lower) {
                "class", "enum" -> expectingTypeName = true
                "function", "filter", "workflow" -> expectingFuncName = true
            }
            continue
        }

        if (next?.type == PsTokens.LPAREN) {
            tok.kind = SyntaxTokenKind.Function
            inStmtStart = false
            continue
        }

        if (looksLikeTypeName(text) && (next?.type == PsTokens.COLONCOLON || prev?.type == PsTokens.COLON)) {
            tok.kind = SyntaxTokenKind.Type
            inStmtStart = false
            continue
        }

        if (inStmtStart && isPowerShellCommandFollower(next?.type)) {
            inCommandArgs = true
        }
        inStmtStart = false
        tok.kind = SyntaxTokenKind.Plain
    }
}

private fun hasPowerShellNewlineBetween(raw: List<RawPsToken>, prevVisIdx: Int, curVisIdx: Int): Boolean {
    for (idx in prevVisIdx + 1 until curVisIdx) {
        if (raw[idx].type == PsTokens.NEWLINE) return true
    }
    return false
}

private fun continuesAcrossNewline(prevType: Int?): Boolean = when (prevType) {
    PsTokens.PIPE,
    PsTokens.AND_AND,
    PsTokens.OR_OR,
    PsTokens.COMMA,
    PsTokens.ASSIGN,
    -> true
    else -> false
}

private fun popMatchingDelim(stack: ArrayList<PsDelimFrame>, isBraceClose: Boolean): PsDelimFrame? {
    for (idx in stack.lastIndex downTo 0) {
        val kind = stack[idx].kind
        val matches = if (isBraceClose) {
            kind == PsDelimKind.BRACE || kind == PsDelimKind.HASH
        } else {
            kind == PsDelimKind.PAREN
        }
        if (matches) {
            val frame = stack[idx]
            while (stack.lastIndex >= idx) {
                stack.removeAt(stack.lastIndex)
            }
            return frame
        }
    }
    return null
}

private fun isPowerShellCommandFollower(nextType: Int?): Boolean = when (nextType) {
    null,
    PsTokens.SEMI,
    PsTokens.PIPE,
    PsTokens.AND_AND,
    PsTokens.OR_OR,
    PsTokens.RBRACE,
    PsTokens.RPAREN,
    PsTokens.SUBEXPR_CLOSE,
    PsTokens.DOT,
    PsTokens.NULL_COND_DOT,
    PsTokens.COLONCOLON,
    PsTokens.LBRACK,
    PsTokens.ASSIGN,
    PsTokens.ADD_ASSIGN,
    PsTokens.SUB_ASSIGN,
    PsTokens.MUL_ASSIGN,
    PsTokens.DIV_ASSIGN,
    PsTokens.MOD_ASSIGN,
    PsTokens.NULL_COALESCE_ASSIGN,
    PsTokens.INC,
    PsTokens.DEC,
    PsTokens.COLON,
    -> false
    else -> true
}

private fun classifyOpenBracket(
    raw: List<RawPsToken>,
    visible: List<Int>,
    openIdx: Int,
    prev: RawPsToken?,
    topBracket: PsBracketFrame?,
): PsBracketKind {
    // Nested `[...]` inside a type expression (`List[string]`) or inside an attribute arg (`[OutputType([string])]`)
    if (topBracket != null && (topBracket.kind == PsBracketKind.TYPE || (topBracket.kind == PsBracketKind.ATTRIBUTE && topBracket.parenDepth > 0))) {
        val nextTok = if (openIdx + 1 < visible.size) raw[visible[openIdx + 1]] else null
        if (nextTok?.type == PsTokens.IDENT) return PsBracketKind.TYPE
    }

    val openTok = raw[visible[openIdx]]
    // Immediately attached to an indexable expression (`$arr[0]`, `$x.Items[0]`, `(...)[0]`, `'abc'[0]`)
    if (prev != null && prev.end == openTok.start) {
        when (prev.type) {
            PsTokens.SIMPLE_VAR,
            PsTokens.SCOPED_VAR,
            PsTokens.BRACED_VAR,
            PsTokens.SPECIAL_VAR,
            PsTokens.RPAREN,
            PsTokens.RBRACK,
            PsTokens.SINGLE_STRING,
            PsTokens.DQ_CLOSE,
            PsTokens.IDENT,
            -> return PsBracketKind.INDEX
        }
    }

    // Bounded lookahead to distinguish `[CmdletBinding()]` / `[Parameter(...)]` from `[string]` / `[List[int]]` vs `[0]`
    val firstInner = if (openIdx + 1 < visible.size) raw[visible[openIdx + 1]] else null
    if (firstInner == null || (firstInner.type != PsTokens.IDENT && firstInner.type != PsTokens.CMDLET_NAME)) {
        return PsBracketKind.INDEX
    }

    var depth = 1
    var sawAttrParenAtTop = false
    val limit = minOf(visible.size, openIdx + 33)
    for (j in openIdx + 1 until limit) {
        val t = raw[visible[j]].type
        when (t) {
            PsTokens.LBRACK -> depth++
            PsTokens.RBRACK -> {
                depth--
                if (depth == 0) {
                    return if (sawAttrParenAtTop) PsBracketKind.ATTRIBUTE else PsBracketKind.TYPE
                }
            }
            PsTokens.LPAREN -> if (depth == 1) sawAttrParenAtTop = true
            PsTokens.SEMI,
            PsTokens.LBRACE,
            PsTokens.RBRACE,
            -> return PsBracketKind.INDEX
        }
    }
    return PsBracketKind.TYPE
}

private fun isPowerShellConstantVar(varText: String): Boolean = when (varText.lowercase()) {
    "\$_",
    "\$psitem",
    "\$true",
    "\$false",
    "\$null",
    "\$args",
    "\$input",
    "\$this",
    "\$psversiontable",
    "\$?",
    "\$\$",
    "\$^",
    -> true
    else -> false
}

private val PS_KEYWORDS: Set<String> = hashSetOf(
    "begin",
    "break",
    "catch",
    "class",
    "clean",
    "continue",
    "data",
    "define",
    "do",
    "dynamicparam",
    "else",
    "elseif",
    "end",
    "enum",
    "exit",
    "filter",
    "finally",
    "for",
    "foreach",
    "from",
    "function",
    "hidden",
    "if",
    "in",
    "inlinescript",
    "parallel",
    "param",
    "process",
    "return",
    "sequence",
    "static",
    "switch",
    "throw",
    "trap",
    "try",
    "until",
    "using",
    "var",
    "while",
    "workflow",
)

private val PS_DASH_OPERATORS: Set<String> = hashSetOf(
    "-eq", "-ceq", "-ieq",
    "-ne", "-cne", "-ine",
    "-gt", "-cgt", "-igt",
    "-ge", "-cge", "-ige",
    "-lt", "-clt", "-ilt",
    "-le", "-cle", "-ile",
    "-like", "-clike", "-ilike",
    "-notlike", "-cnotlike", "-inotlike",
    "-match", "-cmatch", "-imatch",
    "-notmatch", "-cnotmatch", "-inotmatch",
    "-replace", "-creplace", "-ireplace",
    "-contains", "-ccontains", "-icontains",
    "-notcontains", "-cnotcontains", "-inotcontains",
    "-in", "-cin", "-iin",
    "-notin", "-cnotin", "-inotin",
    "-split", "-csplit", "-isplit",
    "-join",
    "-is", "-isnot", "-as",
    "-and", "-or", "-xor", "-not",
    "-band", "-bor", "-bxor", "-bnot",
    "-shl", "-shr",
    "-f",
)
