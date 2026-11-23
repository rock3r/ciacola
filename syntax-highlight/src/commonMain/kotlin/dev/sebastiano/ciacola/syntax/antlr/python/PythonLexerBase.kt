package dev.sebastiano.ciacola.syntax.antlr.python

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [PythonLexer] tracking nested f-string
 * interpolations, conversion/format-spec scopes, lambda expressions, and brace/paren nesting in O(N).
 */
internal abstract class PythonLexerBase(input: CharStream) : Lexer(input) {

    private enum class FStringKind(val isSingleLine: Boolean) {
        SINGLE(true),
        DOUBLE(true),
        TRIPLE_SINGLE(false),
        TRIPLE_DOUBLE(false),
    }

    private class InterpFrame(
        val preFStringModeStackSize: Int,
        var braceDepth: Int = 0,
        var parenDepth: Int = 0,
        var inFormatSpec: Boolean = false,
        var lambdaDepth: Int = -1,
        var atInterpLineStart: Boolean = false,
        var inStatementHeader: Boolean = false,
    )

    private val fstringKindStack = ArrayList<FStringKind>()
    private val fstringBaseStackSize = ArrayList<Int>()
    private val interpStack = ArrayList<InterpFrame>()

    protected fun enterFString(singleLine: Boolean, isDoubleQuote: Boolean, targetMode: Int) {
        val kind = when {
            singleLine && isDoubleQuote -> FStringKind.DOUBLE
            singleLine -> FStringKind.SINGLE
            isDoubleQuote -> FStringKind.TRIPLE_DOUBLE
            else -> FStringKind.TRIPLE_SINGLE
        }
        fstringKindStack.add(kind)
        fstringBaseStackSize.add(_modeStack.size())
        pushMode(targetMode)
    }

    protected fun exitFString() {
        if (fstringKindStack.isNotEmpty()) {
            fstringKindStack.removeAt(fstringKindStack.lastIndex)
            fstringBaseStackSize.removeAt(fstringBaseStackSize.lastIndex)
        }
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun enterInterp(defaultMode: Int) {
        val baseSize = fstringBaseStackSize.lastOrNull() ?: 0
        interpStack.add(InterpFrame(preFStringModeStackSize = baseSize))
        pushMode(defaultMode)
    }

    protected fun onOpenParenOrBracket() {
        val top = interpStack.lastOrNull() ?: return
        if (!top.inFormatSpec) {
            top.parenDepth++
            top.atInterpLineStart = false
        }
    }

    protected fun onCloseParenOrBracket() {
        val top = interpStack.lastOrNull() ?: return
        if (!top.inFormatSpec) {
            if (top.parenDepth > 0) {
                top.parenDepth--
            }
            top.atInterpLineStart = false
        }
    }

    protected fun onLBrace() {
        val top = interpStack.lastOrNull() ?: return
        if (!top.inFormatSpec) {
            top.braceDepth++
            top.atInterpLineStart = false
        }
    }

    protected fun onRBrace(interpCloseTokenType: Int) {
        val top = interpStack.lastOrNull() ?: return
        if (top.braceDepth > 0) {
            top.braceDepth--
            top.atInterpLineStart = false
            return
        }
        interpStack.removeAt(interpStack.lastIndex)
        type = interpCloseTokenType
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun onLambda() {
        val top = interpStack.lastOrNull() ?: return
        if (!top.inFormatSpec && top.lambdaDepth < 0) {
            top.lambdaDepth = top.parenDepth + top.braceDepth
        }
        top.atInterpLineStart = false
    }

    protected fun onStatementHeaderKeyword() {
        val top = interpStack.lastOrNull() ?: return
        if (!top.inFormatSpec && top.atInterpLineStart && top.parenDepth == 0 && top.braceDepth == 0) {
            top.inStatementHeader = true
        }
        top.atInterpLineStart = false
    }

    protected fun onName(lexeme: String) {
        val top = interpStack.lastOrNull() ?: return
        if (
            !top.inFormatSpec &&
            (lexeme == "match" || lexeme == "case") &&
            top.atInterpLineStart &&
            top.parenDepth == 0 &&
            top.braceDepth == 0
        ) {
            top.inStatementHeader = true
        }
        top.atInterpLineStart = false
    }

    protected fun onNonHeaderToken() {
        val top = interpStack.lastOrNull() ?: return
        top.atInterpLineStart = false
    }

    protected fun onSemicolon() {
        val top = interpStack.lastOrNull() ?: return
        if (top.parenDepth == 0 && top.braceDepth == 0) {
            top.atInterpLineStart = true
            top.inStatementHeader = false
            top.lambdaDepth = -1
        }
    }

    protected fun onDefaultNewline() {
        val top = interpStack.lastOrNull() ?: return
        top.atInterpLineStart = true
        if (top.parenDepth == 0 && top.braceDepth == 0) {
            top.inStatementHeader = false
            top.lambdaDepth = -1
            if (fstringKindStack.lastOrNull()?.isSingleLine == true) {
                unwindSingleLineFStrings()
            }
        }
    }

    protected fun onColon(formatSpecMode: Int, formatColonTokenType: Int) {
        val top = interpStack.lastOrNull() ?: return
        if (top.inFormatSpec) return
        top.atInterpLineStart = false
        val currentDepth = top.parenDepth + top.braceDepth
        if (top.lambdaDepth == currentDepth) {
            top.lambdaDepth = -1
            return
        }
        if (top.inStatementHeader && currentDepth == 0) {
            top.inStatementHeader = false
            return
        }
        if (currentDepth == 0) {
            top.inFormatSpec = true
            type = formatColonTokenType
            pushMode(formatSpecMode)
        }
    }

    protected fun onFormatSpecRBrace(interpCloseTokenType: Int) {
        type = interpCloseTokenType
        if (!_modeStack.isEmpty) {
            popMode()
        }
        if (interpStack.isNotEmpty()) {
            interpStack.removeAt(interpStack.lastIndex)
            if (!_modeStack.isEmpty) {
                popMode()
            }
        }
    }

    protected fun onFormatSpecNewline() {
        if (fstringKindStack.lastOrNull()?.isSingleLine == true) {
            unwindSingleLineFStrings()
        }
    }

    private fun unwindSingleLineFStrings() {
        while (fstringKindStack.isNotEmpty() && fstringKindStack.last().isSingleLine) {
            val targetStackSize = fstringBaseStackSize.removeAt(fstringBaseStackSize.lastIndex)
            fstringKindStack.removeAt(fstringKindStack.lastIndex)
            while (interpStack.isNotEmpty() && interpStack.last().preFStringModeStackSize >= targetStackSize) {
                interpStack.removeAt(interpStack.lastIndex)
            }
            while (_modeStack.size() > targetStackSize) {
                popMode()
            }
        }
    }

    override fun reset() {
        super.reset()
        fstringKindStack.clear()
        fstringBaseStackSize.clear()
        interpStack.clear()
    }
}
