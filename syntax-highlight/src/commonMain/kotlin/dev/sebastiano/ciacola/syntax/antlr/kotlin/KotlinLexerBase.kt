package dev.sebastiano.ciacola.syntax.antlr.kotlin

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [KotlinLexer] supporting Kotlin 2.1+
 * multi-dollar string interpolation (`$$"..."`, `$$$"""..."""`) with nested templates.
 */
internal abstract class KotlinLexerBase(input: CharStream) : Lexer(input) {

    private val multiDollarStack = ArrayList<Int>()

    protected fun enterMultiDollar(dollarCount: Int, targetMode: Int) {
        multiDollarStack.add(dollarCount.coerceAtLeast(1))
        pushMode(targetMode)
    }

    protected fun exitMultiDollar() {
        if (multiDollarStack.isNotEmpty()) {
            multiDollarStack.removeAt(multiDollarStack.lastIndex)
        }
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun onMultiDollarExprStart(
        defaultMode: Int,
        exprStartType: Int,
        fallbackTextType: Int,
    ) {
        val required = multiDollarStack.lastOrNull() ?: 1
        val dollars = text.length - 1
        if (dollars >= required) {
            type = exprStartType
            pushMode(defaultMode)
        } else {
            type = fallbackTextType
        }
    }

    protected fun onMultiDollarRef(
        refType: Int,
        fallbackTextType: Int,
    ) {
        val required = multiDollarStack.lastOrNull() ?: 1
        val lexeme = text
        var dollars = 0
        while (dollars < lexeme.length && lexeme[dollars] == '$') {
            dollars++
        }
        type = if (dollars >= required) refType else fallbackTextType
    }

    override fun reset() {
        super.reset()
        multiDollarStack.clear()
    }
}
