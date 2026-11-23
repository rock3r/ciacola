package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.shell.ShellLexer
import dev.sebastiano.ciacola.syntax.antlr.shell.ShellLexer.Tokens as ShellTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Shell (POSIX sh, Bash, and Zsh) source code.
 */
internal object ShellSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Shell

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = ShellLexer(antlrCharStream(source))
        val raw = ArrayList<RawShellToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawShellToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) ShellTokens.WS else ShellTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawShellToken(source, type, start, end, raw) },
        )

        classifyShellTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawShellToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun emitRawShellToken(
    source: String,
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawShellToken>,
) {
    when (type) {
        ShellTokens.WS,
        ShellTokens.LINE_CONTINUATION,
        ShellTokens.NEWLINE,
        ShellTokens.DQ_LINE_CONT,
        ShellTokens.PE_WS,
        ShellTokens.AR_WS,
        ShellTokens.AR_LINE_CONT,
        ShellTokens.HD_WS,
        ShellTokens.HL_NEWLINE,
        ShellTokens.HE_NEWLINE,
        ShellTokens.HE_LINE_CONT,
        -> out += RawShellToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        ShellTokens.SHEBANG,
        ShellTokens.COMMENT,
        -> out += RawShellToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        ShellTokens.FD_REDIR -> {
            // Split `{fd}>` into `{`, `fd`, `}`, and redirection operator `>` / `>>` / etc.
            val closeBrace = source.indexOf('}', start)
            if (closeBrace in (start + 1) until (end - 1)) {
                out += RawShellToken(ShellTokens.LBRACE, start, start + 1, SyntaxTokenKind.Punctuation, isHidden = false)
                out += RawShellToken(SYNTHETIC_FD_NAME, start + 1, closeBrace, SyntaxTokenKind.Property, isHidden = false)
                out += RawShellToken(ShellTokens.RBRACE, closeBrace, closeBrace + 1, SyntaxTokenKind.Punctuation, isHidden = false)
                out += RawShellToken(SYNTHETIC_REDIR_OP, closeBrace + 1, end, SyntaxTokenKind.Operator, isHidden = false)
            } else {
                out += RawShellToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)
            }
        }

        ShellTokens.NUM_REDIR -> {
            var opStart = start
            while (opStart < end && source[opStart] in '0'..'9') opStart++
            if (opStart > start && opStart < end) {
                out += RawShellToken(ShellTokens.INT, start, opStart, SyntaxTokenKind.Number, isHidden = false)
                out += RawShellToken(SYNTHETIC_REDIR_OP, opStart, end, SyntaxTokenKind.Operator, isHidden = false)
            } else {
                out += RawShellToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)
            }
        }

        ShellTokens.HEREDOC_DELIM_END -> {
            var tabEnd = start
            while (tabEnd < end && source[tabEnd] == '\t') tabEnd++
            if (tabEnd > start) {
                out += RawShellToken(ShellTokens.WS, start, tabEnd, SyntaxTokenKind.Whitespace, isHidden = true)
            }
            if (tabEnd < end) {
                out += RawShellToken(type, tabEnd, end, SyntaxTokenKind.String, isHidden = false)
            }
        }

        ShellTokens.ANSI_C_STRING,
        ShellTokens.LOCALE_DQUOTE_START,
        ShellTokens.DQUOTE_START,
        ShellTokens.SINGLE_STRING,
        ShellTokens.DQ_CLOSE,
        ShellTokens.DQ_ESCAPE,
        ShellTokens.DQ_BACKSLASH,
        ShellTokens.DQ_DOLLAR,
        ShellTokens.DQ_TEXT,
        ShellTokens.PE_ANSI_C_STRING,
        ShellTokens.PE_LOCALE_DQUOTE_START,
        ShellTokens.PE_DQUOTE_START,
        ShellTokens.PE_SINGLE_STRING,
        ShellTokens.AR_ANSI_C_STRING,
        ShellTokens.AR_DQUOTE_START,
        ShellTokens.AR_SINGLE_STRING,
        ShellTokens.HD_DELIM,
        ShellTokens.HD_UNCLOSED_DELIM,
        ShellTokens.HL_TEXT,
        ShellTokens.HE_ESCAPE,
        ShellTokens.HE_BACKSLASH,
        ShellTokens.HE_DOLLAR,
        ShellTokens.HE_TEXT,
        -> out += RawShellToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        ShellTokens.HEX_INT,
        ShellTokens.BASE_INT,
        ShellTokens.FLOAT,
        ShellTokens.INT,
        ShellTokens.AR_HEX,
        ShellTokens.AR_BASE_INT,
        ShellTokens.AR_FLOAT,
        ShellTokens.AR_INT,
        ShellTokens.PE_INT,
        -> out += RawShellToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        ShellTokens.SIMPLE_VAR,
        ShellTokens.DQ_SIMPLE_VAR,
        ShellTokens.PE_SIMPLE_VAR,
        ShellTokens.AR_SIMPLE_VAR,
        ShellTokens.HE_SIMPLE_VAR,
        -> {
            val kind = if (end - start == 2 && source[start + 1] == '_') {
                SyntaxTokenKind.Constant
            } else {
                SyntaxTokenKind.Property
            }
            out += RawShellToken(type, start, end, kind, isHidden = false)
        }

        ShellTokens.SPECIAL_VAR,
        ShellTokens.DQ_SPECIAL_VAR,
        ShellTokens.PE_SPECIAL_VAR,
        ShellTokens.AR_SPECIAL_VAR,
        ShellTokens.HE_SPECIAL_VAR,
        -> out += RawShellToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        ShellTokens.AR_IDENT ->
            out += RawShellToken(type, start, end, SyntaxTokenKind.Property, isHidden = false)

        ShellTokens.FLAG ->
            out += RawShellToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        ShellTokens.HERE_STRING,
        ShellTokens.HEREDOC_STRIP_OP,
        ShellTokens.HEREDOC_OP,
        -> {
            if (source[start] == '{') {
                val closeBrace = source.indexOf('}', start)
                if (closeBrace in (start + 1) until (end - 1)) {
                    out += RawShellToken(SYNTHETIC_FD_PREFIX, start, start + 1, SyntaxTokenKind.Punctuation, isHidden = false)
                    out += RawShellToken(SYNTHETIC_FD_NAME, start + 1, closeBrace, SyntaxTokenKind.Property, isHidden = false)
                    out += RawShellToken(SYNTHETIC_FD_PREFIX, closeBrace, closeBrace + 1, SyntaxTokenKind.Punctuation, isHidden = false)
                    out += RawShellToken(type, closeBrace + 1, end, SyntaxTokenKind.Operator, isHidden = false)
                } else {
                    out += RawShellToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)
                }
            } else if (source[start] in '0'..'9') {
                var opStart = start
                while (opStart < end && source[opStart] in '0'..'9') opStart++
                if (opStart > start && opStart < end) {
                    out += RawShellToken(SYNTHETIC_FD_PREFIX, start, opStart, SyntaxTokenKind.Number, isHidden = false)
                    out += RawShellToken(type, opStart, end, SyntaxTokenKind.Operator, isHidden = false)
                } else {
                    out += RawShellToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)
                }
            } else {
                out += RawShellToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)
            }
        }

        ShellTokens.AMP_GT_APPEND,
        ShellTokens.AMP_GT,
        ShellTokens.PIPE_AMP,
        ShellTokens.GT_APPEND,
        ShellTokens.GT_CLOBBER,
        ShellTokens.LT_GT,
        ShellTokens.GT_AMP,
        ShellTokens.LT_AMP,
        ShellTokens.AND_AND,
        ShellTokens.OR_OR,
        ShellTokens.EQ_TILDE,
        ShellTokens.EQ_EQ,
        ShellTokens.BANG_EQ,
        ShellTokens.PLUS_EQ,
        ShellTokens.PIPE,
        ShellTokens.AMP,
        ShellTokens.LT,
        ShellTokens.GT,
        ShellTokens.EQ,
        ShellTokens.BANG,
        ShellTokens.PE_OP,
        ShellTokens.AR_OP3,
        ShellTokens.AR_OP2,
        ShellTokens.AR_OP1,
        -> out += RawShellToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)

        ShellTokens.ARITH_EXP_START,
        ShellTokens.CMD_SUB_START,
        ShellTokens.PARAM_EXP_START,
        ShellTokens.PROC_SUB_IN,
        ShellTokens.PROC_SUB_OUT,
        ShellTokens.ARITH_CMD_START,
        ShellTokens.BACKTICK,
        ShellTokens.SEMI_SEMI_AMP,
        ShellTokens.SEMI_SEMI,
        ShellTokens.SEMI_AMP,
        ShellTokens.LBRACK_LBRACK,
        ShellTokens.RBRACK_RBRACK,
        ShellTokens.LPAREN,
        ShellTokens.RPAREN,
        ShellTokens.LBRACE,
        ShellTokens.RBRACE,
        ShellTokens.LBRACK,
        ShellTokens.RBRACK,
        ShellTokens.SEMI,
        ShellTokens.COMMA,
        ShellTokens.DQ_ARITH_START,
        ShellTokens.DQ_CMD_SUB_START,
        ShellTokens.DQ_PARAM_EXP_START,
        ShellTokens.DQ_BACKTICK,
        ShellTokens.PE_ARITH_START,
        ShellTokens.PE_CMD_SUB_START,
        ShellTokens.PE_PARAM_EXP_START,
        ShellTokens.PE_BACKTICK,
        ShellTokens.PE_LBRACE,
        ShellTokens.PE_RBRACE,
        ShellTokens.PE_LPAREN,
        ShellTokens.PE_RPAREN,
        ShellTokens.PE_LBRACK,
        ShellTokens.PE_RBRACK,
        ShellTokens.AR_END,
        ShellTokens.AR_ARITH_EXP_START,
        ShellTokens.AR_CMD_SUB_START,
        ShellTokens.AR_PARAM_EXP_START,
        ShellTokens.AR_BACKTICK,
        ShellTokens.AR_LPAREN,
        ShellTokens.AR_RPAREN,
        ShellTokens.AR_LBRACK,
        ShellTokens.AR_RBRACK,
        ShellTokens.AR_COMMA,
        ShellTokens.HE_ARITH_START,
        ShellTokens.HE_CMD_SUB_START,
        ShellTokens.HE_PARAM_EXP_START,
        ShellTokens.HE_BACKTICK,
        -> out += RawShellToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)

        ShellTokens.ERROR_CHAR,
        ShellTokens.AR_ERROR,
        ShellTokens.HD_FALLBACK,
        -> out += RawShellToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> out += RawShellToken(type, start, end, SyntaxTokenKind.Plain, isHidden = false)
    }
}

private enum class CaseStage {
    AWAITING_WORD,
    AWAITING_IN,
    IN_PATTERN,
    IN_ARM,
}

private enum class TestBracketKind {
    SINGLE,
    DOUBLE,
}

private enum class ForStage {
    NONE,
    AWAITING_VAR,
    AWAITING_IN_OR_DO,
    IN_LIST,
}

private class CommandScopeFrame(
    val openedByType: Int,
    var expectingCommand: Boolean = true,
    var expectingFuncName: Boolean = false,
    var inDeclarationCmd: Boolean = false,
    var inPrefixAssignRhs: Boolean = false,
    var assignParenDepth: Int = 0,
    var assignSubscriptDepth: Int = 0,
    var forStage: ForStage = ForStage.NONE,
    var expectingRedirTarget: Boolean = false,
    var redirWasAtCommandPos: Boolean = false,
    val caseStack: ArrayList<CaseStage> = ArrayList(),
    var testBracket: TestBracketKind? = null,
    var innerParenDepth: Int = 0,
    var sawFuncDeclParen: Boolean = false,
    var justClosedFuncHeader: Boolean = false,
)

private class ParamExpFrame(
    var expectingVarName: Boolean = true,
    var inZshFlagsParen: Boolean = false,
    var braceDepth: Int = 0,
    var bracketDepth: Int = 0,
    var sawModifierOp: Boolean = false,
)

private fun classifyShellTokens(source: String, raw: List<RawShellToken>) {
    val cmdStack = ArrayList<CommandScopeFrame>().apply {
        add(CommandScopeFrame(openedByType = 0))
    }
    val paramExpStack = ArrayList<ParamExpFrame>()
    val arithParenDepthStack = ArrayList<Int>()
    // Mode stack mirrors ShellLexer's mode transitions for PARAM_EXP / ARITH / DQUOTE vs command contexts.
    val activeSubMode = ArrayList<Int>() // 0 = command/default, 1 = DQUOTE, 2 = PARAM_EXP, 3 = ARITH

    fun currentCmd(): CommandScopeFrame {
        if (cmdStack.isEmpty()) cmdStack.add(CommandScopeFrame(openedByType = 0))
        return cmdStack.last()
    }

    fun nextVisibleIndex(fromRawIdx: Int): Int {
        var j = fromRawIdx + 1
        while (j < raw.size && raw[j].isHidden) j++
        return j
    }

    fun isAdjacent(a: RawShellToken, b: RawShellToken): Boolean = a.end == b.start

    var i = 0
    while (i < raw.size) {
        val tok = raw[i]
        val type = tok.type
        val text = source.substring(tok.start, tok.end)
        val curMode = activeSubMode.lastOrNull() ?: MODE_CMD

        // Handle mode openers / closers across all modes
        when (type) {
            ShellTokens.DQUOTE_START,
            ShellTokens.LOCALE_DQUOTE_START,
            ShellTokens.PE_DQUOTE_START,
            ShellTokens.PE_LOCALE_DQUOTE_START,
            ShellTokens.AR_DQUOTE_START,
            -> {
                if (curMode == MODE_CMD) {
                    val cmd = currentCmd()
                    if (cmd.caseStack.lastOrNull() == CaseStage.AWAITING_WORD) {
                        cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.AWAITING_IN
                    }
                    if (!cmd.inPrefixAssignRhs && !cmd.expectingRedirTarget) {
                        cmd.expectingCommand = false
                    }
                    cmd.expectingRedirTarget = false
                }
                activeSubMode.add(MODE_DQUOTE)
                i++
                continue
            }

            ShellTokens.DQ_CLOSE -> {
                if (activeSubMode.lastOrNull() == MODE_DQUOTE) {
                    activeSubMode.removeAt(activeSubMode.lastIndex)
                }
                i++
                continue
            }

            ShellTokens.PARAM_EXP_START,
            ShellTokens.DQ_PARAM_EXP_START,
            ShellTokens.PE_PARAM_EXP_START,
            ShellTokens.AR_PARAM_EXP_START,
            ShellTokens.HE_PARAM_EXP_START,
            -> {
                if (curMode == MODE_CMD) {
                    val cmd = currentCmd()
                    if (cmd.caseStack.lastOrNull() == CaseStage.AWAITING_WORD) {
                        cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.AWAITING_IN
                    }
                    if (!cmd.inPrefixAssignRhs && !cmd.expectingRedirTarget) {
                        cmd.expectingCommand = false
                    }
                    cmd.expectingRedirTarget = false
                }
                paramExpStack.add(ParamExpFrame())
                activeSubMode.add(MODE_PARAM_EXP)
                i++
                continue
            }

            ShellTokens.PE_RBRACE -> {
                val pe = paramExpStack.lastOrNull()
                if (pe != null && pe.braceDepth > 0) {
                    pe.braceDepth--
                } else {
                    if (paramExpStack.isNotEmpty()) paramExpStack.removeAt(paramExpStack.lastIndex)
                    if (activeSubMode.lastOrNull() == MODE_PARAM_EXP) activeSubMode.removeAt(activeSubMode.lastIndex)
                }
                i++
                continue
            }

            ShellTokens.ARITH_EXP_START,
            ShellTokens.ARITH_CMD_START,
            ShellTokens.DQ_ARITH_START,
            ShellTokens.PE_ARITH_START,
            ShellTokens.AR_ARITH_EXP_START,
            ShellTokens.HE_ARITH_START,
            -> {
                if (curMode == MODE_CMD) {
                    val cmd = currentCmd()
                    if (cmd.caseStack.lastOrNull() == CaseStage.AWAITING_WORD) {
                        cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.AWAITING_IN
                    }
                    cmd.expectingCommand = false
                    cmd.expectingRedirTarget = false
                }
                arithParenDepthStack.add(0)
                activeSubMode.add(MODE_ARITH)
                i++
                continue
            }

            ShellTokens.AR_END -> {
                if (arithParenDepthStack.isNotEmpty()) {
                    arithParenDepthStack.removeAt(arithParenDepthStack.lastIndex)
                }
                if (activeSubMode.lastOrNull() == MODE_ARITH) {
                    activeSubMode.removeAt(activeSubMode.lastIndex)
                }
                if (activeSubMode.lastOrNull() == MODE_CMD) {
                    val cmd = currentCmd()
                    if (cmd.forStage == ForStage.AWAITING_VAR) {
                        // C-style `for (( i = 0; i < n; i++ ))`
                        cmd.forStage = ForStage.NONE
                    }
                }
                i++
                continue
            }

            ShellTokens.CMD_SUB_START,
            ShellTokens.PROC_SUB_IN,
            ShellTokens.PROC_SUB_OUT,
            ShellTokens.DQ_CMD_SUB_START,
            ShellTokens.PE_CMD_SUB_START,
            ShellTokens.AR_CMD_SUB_START,
            ShellTokens.HE_CMD_SUB_START,
            -> {
                if (curMode == MODE_CMD) {
                    val cmd = currentCmd()
                    if (cmd.caseStack.lastOrNull() == CaseStage.AWAITING_WORD) {
                        cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.AWAITING_IN
                    }
                    if (!cmd.inPrefixAssignRhs && !cmd.expectingRedirTarget) {
                        cmd.expectingCommand = false
                    }
                    cmd.expectingRedirTarget = false
                }
                activeSubMode.add(MODE_CMD)
                cmdStack.add(CommandScopeFrame(openedByType = ShellTokens.CMD_SUB_START))
                i++
                continue
            }

            ShellTokens.BACKTICK,
            ShellTokens.DQ_BACKTICK,
            ShellTokens.PE_BACKTICK,
            ShellTokens.AR_BACKTICK,
            ShellTokens.HE_BACKTICK,
            -> {
                val topCmd = cmdStack.lastOrNull()
                if (curMode == MODE_CMD && topCmd != null && topCmd.openedByType == ShellTokens.BACKTICK && cmdStack.size > 1) {
                    cmdStack.removeAt(cmdStack.lastIndex)
                    if (activeSubMode.lastOrNull() == MODE_CMD) activeSubMode.removeAt(activeSubMode.lastIndex)
                } else {
                    if (curMode == MODE_CMD) {
                        val cmd = currentCmd()
                        if (cmd.caseStack.lastOrNull() == CaseStage.AWAITING_WORD) {
                            cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.AWAITING_IN
                        }
                        if (!cmd.inPrefixAssignRhs && !cmd.expectingRedirTarget) {
                            cmd.expectingCommand = false
                        }
                        cmd.expectingRedirTarget = false
                    }
                    activeSubMode.add(MODE_CMD)
                    cmdStack.add(CommandScopeFrame(openedByType = ShellTokens.BACKTICK))
                }
                i++
                continue
            }

            ShellTokens.ESCAPED_BACKTICK -> {
                val topCmd = cmdStack.lastOrNull()
                if (curMode == MODE_CMD && topCmd != null && topCmd.openedByType == ShellTokens.ESCAPED_BACKTICK && cmdStack.size > 1) {
                    tok.kind = SyntaxTokenKind.Punctuation
                    cmdStack.removeAt(cmdStack.lastIndex)
                    if (activeSubMode.lastOrNull() == MODE_CMD) activeSubMode.removeAt(activeSubMode.lastIndex)
                } else if (curMode == MODE_CMD && topCmd != null && topCmd.openedByType == ShellTokens.BACKTICK) {
                    tok.kind = SyntaxTokenKind.Punctuation
                    if (topCmd.caseStack.lastOrNull() == CaseStage.AWAITING_WORD) {
                        topCmd.caseStack[topCmd.caseStack.lastIndex] = CaseStage.AWAITING_IN
                    }
                    if (!topCmd.inPrefixAssignRhs && !topCmd.expectingRedirTarget) {
                        topCmd.expectingCommand = false
                    }
                    topCmd.expectingRedirTarget = false
                    activeSubMode.add(MODE_CMD)
                    cmdStack.add(CommandScopeFrame(openedByType = ShellTokens.ESCAPED_BACKTICK))
                } else if (curMode == MODE_CMD) {
                    val cmd = currentCmd()
                    cmd.expectingCommand = false
                }
                i++
                continue
            }
        }

        // Mode-specific classification
        if (curMode == MODE_PARAM_EXP) {
            val pe = paramExpStack.lastOrNull()
            if (pe != null) {
                when (type) {
                    ShellTokens.PE_LBRACE -> pe.braceDepth++
                    ShellTokens.PE_LPAREN -> {
                        if (pe.expectingVarName) pe.inZshFlagsParen = true
                    }
                    ShellTokens.PE_RPAREN -> {
                        pe.inZshFlagsParen = false
                    }
                    ShellTokens.PE_LBRACK -> {
                        pe.bracketDepth++
                        pe.expectingVarName = false
                    }
                    ShellTokens.PE_RBRACK -> {
                        if (pe.bracketDepth > 0) pe.bracketDepth--
                    }
                    ShellTokens.PE_OP -> {
                        if (!pe.inZshFlagsParen) {
                            if (pe.expectingVarName && (text == "#" || text == "!" || text == "~" || text == "+" || text == "=" || text == "^")) {
                                // Prefix operator before variable name: `${#arr[@]}`, `${!var}`
                            } else if (pe.bracketDepth == 0) {
                                pe.expectingVarName = false
                                pe.sawModifierOp = true
                            }
                        }
                    }
                    ShellTokens.PE_IDENT -> {
                        if (pe.inZshFlagsParen) {
                            tok.kind = SyntaxTokenKind.Plain
                        } else if (pe.expectingVarName && pe.bracketDepth == 0) {
                            tok.kind = SyntaxTokenKind.Property
                            pe.expectingVarName = false
                        } else if (pe.bracketDepth > 0) {
                            tok.kind = SyntaxTokenKind.Property
                        } else {
                            tok.kind = SyntaxTokenKind.Plain
                        }
                    }
                    ShellTokens.PE_INT -> {
                        if (pe.expectingVarName && pe.bracketDepth == 0 && !pe.sawModifierOp) {
                            tok.kind = SyntaxTokenKind.Constant
                            pe.expectingVarName = false
                        } else {
                            tok.kind = SyntaxTokenKind.Number
                        }
                    }
                }
            }
            i++
            continue
        }

        if (curMode == MODE_ARITH) {
            when (type) {
                ShellTokens.AR_LPAREN -> {
                    if (arithParenDepthStack.isNotEmpty()) {
                        val idx = arithParenDepthStack.lastIndex
                        arithParenDepthStack[idx] = arithParenDepthStack[idx] + 1
                    }
                }
                ShellTokens.AR_RPAREN -> {
                    if (arithParenDepthStack.isNotEmpty()) {
                        val idx = arithParenDepthStack.lastIndex
                        val depth = arithParenDepthStack[idx]
                        if (depth > 0) {
                            arithParenDepthStack[idx] = depth - 1
                        } else {
                            arithParenDepthStack.removeAt(idx)
                            if (activeSubMode.lastOrNull() == MODE_ARITH) {
                                activeSubMode.removeAt(activeSubMode.lastIndex)
                            }
                        }
                    } else if (activeSubMode.lastOrNull() == MODE_ARITH) {
                        activeSubMode.removeAt(activeSubMode.lastIndex)
                    }
                }
            }
            i++
            continue
        }

        if (curMode != MODE_CMD) {
            i++
            continue
        }

        // MODE_CMD classification
        val cmd = currentCmd()

        // Hidden tokens (Whitespace / Newline / Comment)
        if (tok.isHidden) {
            if (type == ShellTokens.NEWLINE || type == ShellTokens.HL_NEWLINE || type == ShellTokens.HE_NEWLINE) {
                if (cmd.inPrefixAssignRhs && cmd.assignParenDepth == 0) {
                    cmd.inPrefixAssignRhs = false
                }
                if (cmd.assignParenDepth == 0) {
                    cmd.expectingCommand = true
                    cmd.expectingFuncName = false
                    cmd.justClosedFuncHeader = false
                    cmd.sawFuncDeclParen = false
                    cmd.inDeclarationCmd = false
                    cmd.expectingRedirTarget = false
                    cmd.redirWasAtCommandPos = false
                    if (cmd.forStage == ForStage.IN_LIST || cmd.forStage == ForStage.AWAITING_IN_OR_DO) {
                        cmd.forStage = ForStage.NONE
                    }
                    if (cmd.testBracket == TestBracketKind.SINGLE) {
                        cmd.testBracket = null
                    }
                }
            } else if (cmd.inPrefixAssignRhs && cmd.assignParenDepth == 0 && type == ShellTokens.WS) {
                cmd.inPrefixAssignRhs = false
                cmd.expectingCommand = !cmd.inDeclarationCmd
            }
            i++
            continue
        }

        // Redirection target word consumption (`>out.log cat`)
        if (cmd.expectingRedirTarget) {
            cmd.expectingRedirTarget = false
            if (type == ShellTokens.WORD || type == ShellTokens.DOT || type == ShellTokens.MINUS) {
                tok.kind = SyntaxTokenKind.Plain
            }
            if (cmd.redirWasAtCommandPos) {
                cmd.redirWasAtCommandPos = false
                cmd.expectingCommand = true
            }
            i++
            continue
        }

        when (type) {
            ShellTokens.SEMI,
            ShellTokens.AMP,
            ShellTokens.PIPE,
            ShellTokens.PIPE_AMP,
            ShellTokens.AND_AND,
            ShellTokens.OR_OR,
            -> {
                val topCase = cmd.caseStack.lastOrNull()
                if (type == ShellTokens.PIPE && topCase == CaseStage.IN_PATTERN) {
                    cmd.expectingCommand = false
                } else {
                    cmd.expectingCommand = true
                    cmd.expectingFuncName = false
                    cmd.inDeclarationCmd = false
                    cmd.inPrefixAssignRhs = false
                    cmd.assignParenDepth = 0
                    if (cmd.forStage == ForStage.IN_LIST || cmd.forStage == ForStage.AWAITING_IN_OR_DO) {
                        cmd.forStage = ForStage.NONE
                    }
                    if (type != ShellTokens.AND_AND && type != ShellTokens.OR_OR) {
                        if (cmd.testBracket == TestBracketKind.SINGLE) cmd.testBracket = null
                    }
                }
            }

            ShellTokens.SEMI_SEMI,
            ShellTokens.SEMI_AMP,
            ShellTokens.SEMI_SEMI_AMP,
            -> {
                if (cmd.caseStack.isNotEmpty()) {
                    cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.IN_PATTERN
                    cmd.expectingCommand = false
                } else {
                    cmd.expectingCommand = true
                }
                cmd.inDeclarationCmd = false
                cmd.inPrefixAssignRhs = false
            }

            ShellTokens.LBRACE -> {
                val canOpenGroup = cmd.expectingCommand || cmd.justClosedFuncHeader
                val prevOk = i == 0 || raw[i - 1].isHidden || isGroupOpenPredecessor(raw[i - 1].type)
                val nextOk = tok.end == source.length || isShellWhitespaceOrNewline(source[tok.end])
                if (canOpenGroup && prevOk && nextOk) {
                    cmd.expectingCommand = true
                    cmd.inDeclarationCmd = false
                    cmd.inPrefixAssignRhs = false
                } else {
                    if (cmd.caseStack.lastOrNull() == CaseStage.AWAITING_WORD) {
                        cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.AWAITING_IN
                    }
                    cmd.expectingCommand = false
                }
                cmd.justClosedFuncHeader = false
            }

            ShellTokens.RBRACE -> {
                cmd.expectingCommand = false
                cmd.justClosedFuncHeader = false
                cmd.inDeclarationCmd = false
                cmd.inPrefixAssignRhs = false
            }

            ShellTokens.LPAREN -> {
                cmd.justClosedFuncHeader = false
                if (cmd.inPrefixAssignRhs) {
                    cmd.assignParenDepth++
                } else if (cmd.caseStack.lastOrNull() == CaseStage.IN_PATTERN) {
                    cmd.innerParenDepth++
                } else {
                    cmd.innerParenDepth++
                    if (!cmd.sawFuncDeclParen) {
                        cmd.expectingCommand = true
                    }
                }
            }

            ShellTokens.RPAREN -> {
                if (cmd.assignParenDepth > 0) {
                    cmd.assignParenDepth--
                    if (cmd.assignParenDepth == 0) {
                        cmd.inPrefixAssignRhs = false
                        cmd.expectingCommand = !cmd.inDeclarationCmd
                    }
                } else if (cmd.caseStack.lastOrNull() == CaseStage.IN_PATTERN && cmd.innerParenDepth <= 1) {
                    if (cmd.innerParenDepth > 0) cmd.innerParenDepth--
                    cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.IN_ARM
                    cmd.expectingCommand = true
                } else if (cmd.innerParenDepth > 0) {
                    cmd.innerParenDepth--
                    cmd.expectingCommand = false
                    if (cmd.sawFuncDeclParen) {
                        cmd.sawFuncDeclParen = false
                        cmd.justClosedFuncHeader = true
                    }
                } else if (cmd.openedByType == ShellTokens.CMD_SUB_START && cmdStack.size > 1) {
                    cmdStack.removeAt(cmdStack.lastIndex)
                    if (activeSubMode.lastOrNull() == MODE_CMD) activeSubMode.removeAt(activeSubMode.lastIndex)
                }
            }

            ShellTokens.LBRACK -> {
                if (cmd.expectingCommand && cmd.testBracket == null) {
                    cmd.testBracket = TestBracketKind.SINGLE
                    cmd.expectingCommand = false
                } else if (cmd.inPrefixAssignRhs || cmd.inDeclarationCmd) {
                    cmd.assignSubscriptDepth++
                }
            }

            ShellTokens.RBRACK -> {
                if (cmd.assignSubscriptDepth > 0) {
                    cmd.assignSubscriptDepth--
                } else if (cmd.testBracket == TestBracketKind.SINGLE) {
                    cmd.testBracket = null
                }
            }

            ShellTokens.LBRACK_LBRACK -> {
                cmd.testBracket = TestBracketKind.DOUBLE
                cmd.expectingCommand = false
            }

            ShellTokens.RBRACK_RBRACK -> {
                if (cmd.testBracket == TestBracketKind.DOUBLE) {
                    cmd.testBracket = null
                }
            }

            SYNTHETIC_FD_PREFIX,
            SYNTHETIC_FD_NAME,
            -> {
                cmd.justClosedFuncHeader = false
            }

            ShellTokens.HERE_STRING,
            ShellTokens.HEREDOC_STRIP_OP,
            ShellTokens.HEREDOC_OP,
            -> {
                cmd.justClosedFuncHeader = false
                // Next token on line (if HD_DELIM) is handled by its own rule; HERE_STRING consumes next arg.
                if (type == ShellTokens.HERE_STRING) {
                    cmd.expectingCommand = false
                }
            }

            ShellTokens.HD_DELIM,
            ShellTokens.HD_UNCLOSED_DELIM,
            ShellTokens.HL_TEXT,
            ShellTokens.HE_TEXT,
            ShellTokens.HE_ESCAPE,
            ShellTokens.HE_BACKSLASH,
            ShellTokens.HE_DOLLAR,
            ShellTokens.HE_SIMPLE_VAR,
            ShellTokens.HE_SPECIAL_VAR,
            ShellTokens.HEREDOC_DELIM_END,
            -> {
                // Heredoc delimiters and body tokens must not disturb surrounding command/case state.
                cmd.justClosedFuncHeader = false
            }

            ShellTokens.AMP_GT_APPEND,
            ShellTokens.AMP_GT,
            ShellTokens.GT_APPEND,
            ShellTokens.GT_CLOBBER,
            ShellTokens.LT_GT,
            ShellTokens.GT_AMP,
            ShellTokens.LT_AMP,
            ShellTokens.GT,
            ShellTokens.LT,
            SYNTHETIC_REDIR_OP,
            -> {
                if (cmd.testBracket == null) {
                    cmd.redirWasAtCommandPos = cmd.expectingCommand
                    if (type != SYNTHETIC_REDIR_OP) {
                        cmd.expectingRedirTarget = true
                    }
                    if (!cmd.redirWasAtCommandPos) {
                        cmd.expectingCommand = false
                    }
                }
            }

            ShellTokens.BANG -> {
                if (cmd.expectingCommand) {
                    // Pipeline negation `! grep foo` keeps command position for `grep`.
                    cmd.expectingCommand = true
                }
            }

            ShellTokens.FLAG -> {
                if (cmd.testBracket != null && text in TEST_OPERATORS) {
                    tok.kind = SyntaxTokenKind.Operator
                } else {
                    tok.kind = SyntaxTokenKind.Constant
                }
                // Flags on `command -v cmd`, `local -r x=1`, `time -p cmd` preserve prefix state.
            }

            ShellTokens.DOT -> {
                if (cmd.expectingCommand) {
                    val nxtIdx = nextVisibleIndex(i)
                    val nxt = if (nxtIdx < raw.size) raw[nxtIdx] else null
                    // `. ./env.sh` (source builtin) has whitespace after `.`
                    if (nxt != null && nxt.start > tok.end) {
                        tok.kind = SyntaxTokenKind.Function
                    } else {
                        tok.kind = SyntaxTokenKind.Plain
                    }
                    cmd.expectingCommand = false
                } else {
                    tok.kind = SyntaxTokenKind.Plain
                }
            }

            ShellTokens.MINUS -> {
                if (cmd.testBracket != null) {
                    tok.kind = SyntaxTokenKind.Operator
                } else {
                    tok.kind = SyntaxTokenKind.Plain
                }
                cmd.expectingCommand = false
            }

            ShellTokens.EQ,
            ShellTokens.PLUS_EQ,
            -> {
                if (cmd.assignSubscriptDepth == 0 && i > 0) {
                    val prev = raw[i - 1]
                    if (prev.kind == SyntaxTokenKind.Property && isAdjacent(prev, tok)) {
                        cmd.inPrefixAssignRhs = true
                        cmd.expectingCommand = false
                    }
                }
            }

            ShellTokens.WORD -> {
                classifyWordToken(
                    source = source,
                    raw = raw,
                    index = i,
                    tok = tok,
                    text = text,
                    cmd = cmd,
                )
            }

            else -> {
                if (cmd.caseStack.lastOrNull() == CaseStage.AWAITING_WORD) {
                    cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.AWAITING_IN
                }
                if (!cmd.inPrefixAssignRhs && cmd.assignParenDepth == 0) {
                    cmd.expectingCommand = false
                }
            }
        }

        i++
    }
}

private fun classifyWordToken(
    source: String,
    raw: List<RawShellToken>,
    index: Int,
    tok: RawShellToken,
    text: String,
    cmd: CommandScopeFrame,
) {
    // 1. Check `case` header / `esac`
    val topCase = cmd.caseStack.lastOrNull()
    if (topCase != null) {
        when (topCase) {
            CaseStage.AWAITING_WORD -> {
                tok.kind = SyntaxTokenKind.Plain
                cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.AWAITING_IN
                return
            }
            CaseStage.AWAITING_IN -> {
                if (text == "in") {
                    tok.kind = SyntaxTokenKind.Keyword
                    cmd.caseStack[cmd.caseStack.lastIndex] = CaseStage.IN_PATTERN
                } else {
                    tok.kind = SyntaxTokenKind.Plain
                }
                return
            }
            CaseStage.IN_PATTERN -> {
                if (text == "esac") {
                    tok.kind = SyntaxTokenKind.Keyword
                    cmd.caseStack.removeAt(cmd.caseStack.lastIndex)
                } else {
                    tok.kind = SyntaxTokenKind.Plain
                }
                return
            }
            CaseStage.IN_ARM -> {
                if (text == "esac" && cmd.expectingCommand) {
                    tok.kind = SyntaxTokenKind.Keyword
                    cmd.caseStack.removeAt(cmd.caseStack.lastIndex)
                    cmd.expectingCommand = false
                    return
                }
            }
        }
    }

    // 2. Check `for` / `select` header
    when (cmd.forStage) {
        ForStage.AWAITING_VAR -> {
            tok.kind = SyntaxTokenKind.Plain
            cmd.forStage = ForStage.AWAITING_IN_OR_DO
            return
        }
        ForStage.AWAITING_IN_OR_DO -> {
            if (text == "in") {
                tok.kind = SyntaxTokenKind.Keyword
                cmd.forStage = ForStage.IN_LIST
                return
            }
            if (text == "do") {
                tok.kind = SyntaxTokenKind.Keyword
                cmd.forStage = ForStage.NONE
                cmd.expectingCommand = true
                return
            }
            cmd.forStage = ForStage.NONE
        }
        ForStage.IN_LIST -> {
            if (text == "do" && cmd.expectingCommand) {
                tok.kind = SyntaxTokenKind.Keyword
                cmd.forStage = ForStage.NONE
                cmd.expectingCommand = true
                return
            }
            tok.kind = SyntaxTokenKind.Plain
            return
        }
        ForStage.NONE -> Unit
    }

    // 3. Check function name right after `function`
    if (cmd.expectingFuncName) {
        tok.kind = SyntaxTokenKind.Function
        cmd.expectingFuncName = false
        cmd.expectingCommand = false
        cmd.justClosedFuncHeader = true
        return
    }
    cmd.justClosedFuncHeader = false

    // 4. Check variable assignment `FOO=...` / `arr+=(...)` / `map[k]=v`
    val nextTok = if (index + 1 < raw.size) raw[index + 1] else null
    val isAdjacentAssign = nextTok != null &&
        tok.end == nextTok.start &&
        (nextTok.type == ShellTokens.EQ || nextTok.type == ShellTokens.PLUS_EQ)
    val isArrayElemAssign = isAdjacentArrayElementAssign(raw, index, tok)
    if ((cmd.expectingCommand || cmd.inDeclarationCmd) &&
        isShellIdentifier(text) &&
        (isAdjacentAssign || isArrayElemAssign || (cmd.inDeclarationCmd && cmd.assignSubscriptDepth == 0 && cmd.assignParenDepth == 0))
    ) {
        // Inside `export FOO=1` or `local -r x=2`, `x` is Property, while RHS values stay Plain/Number/String.
        if (isAdjacentAssign || isArrayElemAssign || !isKeywordOrBuiltin(text)) {
            if (cmd.inDeclarationCmd && !isAdjacentAssign && !isArrayElemAssign) {
                // `local -r x` without `=` (declaring variable without initializer)
                tok.kind = SyntaxTokenKind.Property
                return
            }
            if (isAdjacentAssign || isArrayElemAssign) {
                tok.kind = SyntaxTokenKind.Property
                return
            }
        }
    }

    // 5. Check command-position keywords, function definitions `foo()`, and builtins/commands
    if (cmd.expectingCommand) {
        // Function definition: `foo()`
        if (isFunctionDeclLookahead(raw, index)) {
            tok.kind = SyntaxTokenKind.Function
            cmd.expectingCommand = false
            cmd.sawFuncDeclParen = true
            return
        }

        when {
            text in CONTROL_KEYWORDS -> {
                tok.kind = SyntaxTokenKind.Keyword
                when (text) {
                    "case" -> {
                        cmd.caseStack.add(CaseStage.AWAITING_WORD)
                        cmd.expectingCommand = false
                    }
                    "for", "select" -> {
                        cmd.forStage = ForStage.AWAITING_VAR
                        cmd.expectingCommand = false
                    }
                    "function", "coproc" -> {
                        cmd.expectingFuncName = true
                        cmd.expectingCommand = false
                    }
                    "if", "then", "elif", "else", "while", "until", "do", "time" -> {
                        cmd.expectingCommand = true
                    }
                    else -> {
                        cmd.expectingCommand = false
                    }
                }
            }

            text in DECLARATION_KEYWORDS -> {
                tok.kind = SyntaxTokenKind.Keyword
                cmd.inDeclarationCmd = true
                cmd.expectingCommand = false
            }

            text in COMMAND_MODIFIER_BUILTINS -> {
                tok.kind = SyntaxTokenKind.Function
                cmd.expectingCommand = true
            }

            text in SHELL_BUILTINS -> {
                tok.kind = SyntaxTokenKind.Function
                cmd.expectingCommand = false
            }

            else -> {
                // External command name in command position
                tok.kind = SyntaxTokenKind.Function
                cmd.expectingCommand = false
            }
        }
        return
    }

    // 6. Argument position (`do` after `for`/`while` if separated by space without `;`, or regular arg)
    tok.kind = SyntaxTokenKind.Plain
    // Note: if `tok` was an argument inside `FOO=bar` RHS, `inPrefixAssignRhs` clears on whitespace/separator.
}

private fun isAdjacentArrayElementAssign(
    raw: List<RawShellToken>,
    index: Int,
    tok: RawShellToken,
): Boolean {
    if (index + 1 >= raw.size) return false
    val lbrack = raw[index + 1]
    if (lbrack.type != ShellTokens.LBRACK || tok.end != lbrack.start) return false
    var j = index + 2
    var depth = 1
    while (j < raw.size && depth > 0) {
        val t = raw[j]
        if (t.type == ShellTokens.NEWLINE || t.type == ShellTokens.SEMI) return false
        if (t.type == ShellTokens.LBRACK) depth++
        else if (t.type == ShellTokens.RBRACK) {
            depth--
            if (depth == 0) {
                val eq = if (j + 1 < raw.size) raw[j + 1] else return false
                return t.end == eq.start && (eq.type == ShellTokens.EQ || eq.type == ShellTokens.PLUS_EQ)
            }
        }
        j++
    }
    return false
}

private fun isFunctionDeclLookahead(raw: List<RawShellToken>, index: Int): Boolean {
    var j = index + 1
    while (j < raw.size && raw[j].type == ShellTokens.WS) j++
    if (j >= raw.size || raw[j].type != ShellTokens.LPAREN) return false
    j++
    while (j < raw.size && raw[j].type == ShellTokens.WS) j++
    return j < raw.size && raw[j].type == ShellTokens.RPAREN
}

private fun isGroupOpenPredecessor(type: Int): Boolean =
    type == ShellTokens.SEMI ||
        type == ShellTokens.AMP ||
        type == ShellTokens.PIPE ||
        type == ShellTokens.PIPE_AMP ||
        type == ShellTokens.AND_AND ||
        type == ShellTokens.OR_OR ||
        type == ShellTokens.LPAREN ||
        type == ShellTokens.RPAREN ||
        type == ShellTokens.LBRACE ||
        type == ShellTokens.CMD_SUB_START ||
        type == ShellTokens.PROC_SUB_IN ||
        type == ShellTokens.PROC_SUB_OUT ||
        type == ShellTokens.BACKTICK ||
        type == ShellTokens.ESCAPED_BACKTICK

private fun isShellWhitespaceOrNewline(ch: Char): Boolean =
    ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == '\u000C' || ch == '\uFEFF'

private fun isShellIdentifier(text: String): Boolean {
    if (text.isEmpty()) return false
    val first = text[0]
    if (!(first in 'a'..'z' || first in 'A'..'Z' || first == '_')) return false
    for (i in 1 until text.length) {
        val ch = text[i]
        if (!(ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_')) return false
    }
    return true
}

private fun isKeywordOrBuiltin(text: String): Boolean =
    text in CONTROL_KEYWORDS ||
        text in DECLARATION_KEYWORDS ||
        text in COMMAND_MODIFIER_BUILTINS ||
        text in SHELL_BUILTINS

private const val MODE_CMD = 0
private const val MODE_DQUOTE = 1
private const val MODE_PARAM_EXP = 2
private const val MODE_ARITH = 3

private const val SYNTHETIC_FD_NAME = -1001
private const val SYNTHETIC_REDIR_OP = -1002
private const val SYNTHETIC_FD_PREFIX = -1003

private val CONTROL_KEYWORDS = setOf(
    "if",
    "then",
    "elif",
    "else",
    "fi",
    "for",
    "select",
    "while",
    "until",
    "do",
    "done",
    "case",
    "esac",
    "in",
    "function",
    "time",
    "coproc",
    "return",
    "break",
    "continue",
)

private val DECLARATION_KEYWORDS = setOf(
    "export",
    "local",
    "declare",
    "typeset",
    "readonly",
)

private val COMMAND_MODIFIER_BUILTINS = setOf(
    "command",
    "builtin",
    "exec",
    "eval",
)

private val SHELL_BUILTINS = setOf(
    ":",
    "echo",
    "printf",
    "read",
    "cd",
    "pwd",
    "pushd",
    "popd",
    "test",
    "set",
    "shopt",
    "alias",
    "unalias",
    "type",
    "true",
    "false",
    "getopts",
    "wait",
    "kill",
    "jobs",
    "fg",
    "bg",
    "umask",
    "ulimit",
    "mapfile",
    "readarray",
    "exit",
    "unset",
    "shift",
    "trap",
    "source",
    "autoload",
    "setopt",
    "unsetopt",
    "zstyle",
    "emulate",
    "let",
    "hash",
    "enable",
    "help",
    "logout",
    "times",
    "suspend",
    "disown",
    "compgen",
    "complete",
    "compopt",
    "caller",
    "bind",
    "dirs",
    "fc",
    "history",
)

private val TEST_OPERATORS = setOf(
    "-a", "-b", "-c", "-d", "-e", "-f", "-g", "-h", "-k", "-p",
    "-r", "-s", "-t", "-u", "-w", "-x", "-G", "-L", "-N", "-O",
    "-S", "-z", "-n", "-o", "-v", "-R",
    "-eq", "-ne", "-lt", "-gt", "-le", "-ge",
    "-nt", "-ot", "-ef",
)
