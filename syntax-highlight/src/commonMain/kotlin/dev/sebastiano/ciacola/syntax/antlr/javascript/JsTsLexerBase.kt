package dev.sebastiano.ciacola.syntax.antlr.javascript

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Token

/**
 * Pure Kotlin Multiplatform base lexer for JavaScript/JSX and TypeScript/TSX.
 *
 * Tracks the previous non-hidden token and parenthesized condition context so `/` can be
 * disambiguated between division and regex literals, and `<` can be disambiguated between
 * comparison/generics and JSX elements.
 */
internal abstract class JsTsLexerBase(input: CharStream) : Lexer(input) {
    private var lastNonHiddenType: Int = Token.INVALID_TYPE
    private var lastNonHiddenText: String? = null
    private var lastParenWasCondition: Boolean = false
    private val parenConditionStack = ArrayList<Boolean>()

    protected abstract fun isHiddenTokenType(type: Int): Boolean
    protected abstract fun isIdentTokenType(type: Int): Boolean
    protected abstract fun isLparenTokenType(type: Int): Boolean
    protected abstract fun isRparenTokenType(type: Int): Boolean
    protected abstract fun isConditionKeywordText(text: String?): Boolean
    protected abstract fun allowsRegexAfterToken(type: Int, text: String?, lastParenWasCond: Boolean): Boolean

    override fun reset() {
        super.reset()
        lastNonHiddenType = Token.INVALID_TYPE
        lastNonHiddenText = null
        lastParenWasCondition = false
        parenConditionStack.clear()
    }

    override fun nextToken(): Token {
        val tok = super.nextToken()
        if (tok.type != Token.EOF && !isHiddenTokenType(tok.type)) {
            val type = tok.type
            val text = if (isIdentTokenType(type)) tok.text else null
            if (isLparenTokenType(type)) {
                parenConditionStack.add(isConditionKeywordText(lastNonHiddenText))
                lastParenWasCondition = false
            } else if (isRparenTokenType(type)) {
                lastParenWasCondition = if (parenConditionStack.isNotEmpty()) {
                    parenConditionStack.removeAt(parenConditionStack.size - 1)
                } else {
                    false
                }
            } else {
                lastParenWasCondition = false
            }
            lastNonHiddenType = type
            lastNonHiddenText = text
        }
        return tok
    }

    protected fun isAtStartOfFile(): Boolean = _tokenStartCharIndex == 0

    protected fun isRegexAllowed(): Boolean {
        if (lastNonHiddenType == Token.INVALID_TYPE) return true
        return allowsRegexAfterToken(lastNonHiddenType, lastNonHiddenText, lastParenWasCondition)
    }

    protected fun isJsxFragmentStartAfterAngle(): Boolean = isRegexAllowed()

    protected fun isChildTagStartAfterAngle(): Boolean =
        isJsIdentStartCodePoint(_input.LA(1))

    protected fun isJsxTagStartAfterAngle(): Boolean {
        if (!isRegexAllowed()) return false
        val c1 = _input.LA(1)
        if (!isJsIdentStartCodePoint(c1)) return false

        // Scan bounded tag name: Ident ( ('.' | ':' | '-') IdentPart )*
        val nameStart = 1
        var pos = 2
        val maxScan = 256
        while (pos < maxScan) {
            val ch = _input.LA(pos)
            if (ch == IntStream.EOF) break
            if (isJsIdentPartCodePoint(ch) || ch == '.'.code || ch == ':'.code || ch == '-'.code) {
                pos++
            } else {
                break
            }
        }
        val nameLen = pos - nameStart
        if (nameLen == 5 &&
            _input.LA(1) == 'c'.code &&
            _input.LA(2) == 'o'.code &&
            _input.LA(3) == 'n'.code &&
            _input.LA(4) == 's'.code &&
            _input.LA(5) == 't'.code
        ) {
            // TypeScript `<const T>` const type parameter is never JSX
            return false
        }

        // Skip whitespace after tag name (bounded)
        while (pos < maxScan) {
            val ch = _input.LA(pos)
            if (ch == ' '.code || ch == '\t'.code || ch == '\r'.code || ch == '\n'.code) {
                pos++
            } else {
                break
            }
        }

        var next = _input.LA(pos)
        if (next == '<'.code) {
            var angle = 1
            pos++
            while (pos < maxScan && angle > 0) {
                val ch = _input.LA(pos)
                if (ch == IntStream.EOF || ch == ';'.code) return false
                if (ch == '<'.code) {
                    angle++
                } else if (ch == '>'.code && _input.LA(pos - 1) != '='.code) {
                    angle--
                }
                pos++
            }
            if (angle > 0) return false
            while (pos < maxScan) {
                val ch = _input.LA(pos)
                if (ch == ' '.code || ch == '\t'.code || ch == '\r'.code || ch == '\n'.code) {
                    pos++
                } else {
                    break
                }
            }
            next = _input.LA(pos)
            return when {
                next == '('.code -> false
                next == '/'.code && _input.LA(pos + 1) == '>'.code -> true
                next == '>'.code -> true
                next == '{'.code -> true
                isJsIdentStartCodePoint(next) -> true
                else -> false
            }
        }

        return when {
            next == ','.code || next == '='.code || next == ':'.code -> false
            next == 'e'.code && matchesExtendsAt(pos) -> false
            next == '/'.code && _input.LA(pos + 1) == '>'.code -> true
            next == '>'.code -> true
            next == '{'.code -> true
            isJsIdentStartCodePoint(next) -> true
            else -> false
        }
    }

    private fun matchesExtendsAt(pos: Int): Boolean {
        val word = "extends"
        for (i in word.indices) {
            if (_input.LA(pos + i) != word[i].code) return false
        }
        val after = _input.LA(pos + word.length)
        return after == ' '.code ||
            after == '\t'.code ||
            after == '\r'.code ||
            after == '\n'.code ||
            after == '{'.code ||
            after == '('.code ||
            after == '['.code ||
            after == '<'.code ||
            after == '\''.code ||
            after == '"'.code ||
            after == IntStream.EOF
    }

    private fun isJsIdentStartCodePoint(cp: Int): Boolean {
        if (cp <= 0) return false
        return (cp in 'a'.code..'z'.code) ||
            (cp in 'A'.code..'Z'.code) ||
            cp == '_'.code ||
            cp == '$'.code ||
            cp >= 0xC0
    }

    private fun isJsIdentPartCodePoint(cp: Int): Boolean =
        isJsIdentStartCodePoint(cp) ||
            (cp in '0'.code..'9'.code) ||
            (cp in 0x0300..0x036F) ||
            cp == 0x200C ||
            cp == 0x200D
}
