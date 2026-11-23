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
    var isTypeScript: Boolean = false

    private var lastNonHiddenType: Int = Token.INVALID_TYPE
    private var lastNonHiddenText: String? = null
    private var lastParenWasCondition: Boolean = false
    private var lastBraceAllowedRegex: Boolean = true
    private var lastColonWasTernary: Boolean = false
    private var ternaryDepth: Int = 0
    private val parenConditionStack = ArrayList<Boolean>()
    private val braceAllowsRegexStack = ArrayList<Boolean>()
    private val savedBraceTernaryDepthStack = ArrayList<Int>()
    private val savedParenTernaryDepthStack = ArrayList<Int>()
    private val savedBrackTernaryDepthStack = ArrayList<Int>()
    private val savedTemplateTernaryDepthStack = ArrayList<Int>()
    private var lastClosingTagOffsetByName: HashMap<String, Int>? = null

    protected abstract fun isHiddenTokenType(type: Int): Boolean
    protected abstract fun isIdentTokenType(type: Int): Boolean
    protected abstract fun isLparenTokenType(type: Int): Boolean
    protected abstract fun isRparenTokenType(type: Int): Boolean
    protected abstract fun isLbrackTokenType(type: Int): Boolean
    protected abstract fun isRbrackTokenType(type: Int): Boolean
    protected abstract fun isLbraceTokenType(type: Int): Boolean
    protected abstract fun isJsxExprStartTokenType(type: Int): Boolean
    protected abstract fun isRbraceTokenType(type: Int): Boolean
    protected abstract fun isTemplateExprStartTokenType(type: Int): Boolean
    protected abstract fun isTemplateExprEndTokenType(type: Int): Boolean
    protected abstract fun isQuestionTokenType(type: Int): Boolean
    protected abstract fun isColonTokenType(type: Int): Boolean
    protected abstract fun isSemiTokenType(type: Int): Boolean
    protected abstract fun isConditionKeywordText(text: String?): Boolean
    protected abstract fun opensBlockBraceAfterToken(
        type: Int,
        text: String?,
        enclosingBraceAllowsRegex: Boolean,
        lastColonWasTernary: Boolean,
    ): Boolean
    protected abstract fun allowsRegexAfterToken(
        type: Int,
        text: String?,
        lastParenWasCond: Boolean,
        lastBraceAllowedRegex: Boolean,
    ): Boolean

    override fun reset() {
        super.reset()
        lastNonHiddenType = Token.INVALID_TYPE
        lastNonHiddenText = null
        lastParenWasCondition = false
        lastBraceAllowedRegex = true
        lastColonWasTernary = false
        ternaryDepth = 0
        parenConditionStack.clear()
        braceAllowsRegexStack.clear()
        savedBraceTernaryDepthStack.clear()
        savedParenTernaryDepthStack.clear()
        savedBrackTernaryDepthStack.clear()
        savedTemplateTernaryDepthStack.clear()
        lastClosingTagOffsetByName = null
    }

    override fun nextToken(): Token {
        val tok = super.nextToken()
        if (tok.type != Token.EOF && !isHiddenTokenType(tok.type)) {
            val type = tok.type
            val text = if (isIdentTokenType(type)) tok.text else null
            if (isLparenTokenType(type)) {
                parenConditionStack.add(isConditionKeywordText(lastNonHiddenText))
                savedParenTernaryDepthStack.add(ternaryDepth)
                ternaryDepth = 0
                lastParenWasCondition = false
                lastBraceAllowedRegex = false
                lastColonWasTernary = false
            } else if (isRparenTokenType(type)) {
                lastParenWasCondition = if (parenConditionStack.isNotEmpty()) {
                    parenConditionStack.removeAt(parenConditionStack.size - 1)
                } else {
                    false
                }
                ternaryDepth = if (savedParenTernaryDepthStack.isNotEmpty()) {
                    savedParenTernaryDepthStack.removeAt(savedParenTernaryDepthStack.size - 1)
                } else {
                    0
                }
                lastBraceAllowedRegex = false
                lastColonWasTernary = false
            } else if (isLbrackTokenType(type)) {
                savedBrackTernaryDepthStack.add(ternaryDepth)
                ternaryDepth = 0
                lastParenWasCondition = false
                lastBraceAllowedRegex = false
                lastColonWasTernary = false
            } else if (isRbrackTokenType(type)) {
                ternaryDepth = if (savedBrackTernaryDepthStack.isNotEmpty()) {
                    savedBrackTernaryDepthStack.removeAt(savedBrackTernaryDepthStack.size - 1)
                } else {
                    0
                }
                lastParenWasCondition = false
                lastBraceAllowedRegex = false
                lastColonWasTernary = false
            } else if (isLbraceTokenType(type)) {
                val enclosingAllowsRegex = if (braceAllowsRegexStack.isNotEmpty()) {
                    braceAllowsRegexStack[braceAllowsRegexStack.size - 1]
                } else {
                    true
                }
                val opensBlock = opensBlockBraceAfterToken(
                    type = lastNonHiddenType,
                    text = lastNonHiddenText,
                    enclosingBraceAllowsRegex = enclosingAllowsRegex,
                    lastColonWasTernary = lastColonWasTernary,
                )
                braceAllowsRegexStack.add(opensBlock)
                savedBraceTernaryDepthStack.add(ternaryDepth)
                ternaryDepth = 0
                lastParenWasCondition = false
                lastBraceAllowedRegex = false
                lastColonWasTernary = false
            } else if (isJsxExprStartTokenType(type)) {
                // JSX expression container `{...}` is an expression context.
                braceAllowsRegexStack.add(false)
                savedBraceTernaryDepthStack.add(ternaryDepth)
                ternaryDepth = 0
                lastParenWasCondition = false
                lastBraceAllowedRegex = false
                lastColonWasTernary = false
            } else if (isRbraceTokenType(type)) {
                lastBraceAllowedRegex = if (braceAllowsRegexStack.isNotEmpty()) {
                    braceAllowsRegexStack.removeAt(braceAllowsRegexStack.size - 1)
                } else {
                    true
                }
                ternaryDepth = if (savedBraceTernaryDepthStack.isNotEmpty()) {
                    savedBraceTernaryDepthStack.removeAt(savedBraceTernaryDepthStack.size - 1)
                } else {
                    0
                }
                lastParenWasCondition = false
                lastColonWasTernary = false
            } else if (isTemplateExprStartTokenType(type)) {
                savedTemplateTernaryDepthStack.add(ternaryDepth)
                ternaryDepth = 0
                lastParenWasCondition = false
                lastBraceAllowedRegex = false
                lastColonWasTernary = false
            } else if (isTemplateExprEndTokenType(type)) {
                ternaryDepth = if (savedTemplateTernaryDepthStack.isNotEmpty()) {
                    savedTemplateTernaryDepthStack.removeAt(savedTemplateTernaryDepthStack.size - 1)
                } else {
                    0
                }
                lastParenWasCondition = false
                lastBraceAllowedRegex = false
                lastColonWasTernary = false
            } else {
                if (isQuestionTokenType(type)) {
                    ternaryDepth++
                    lastColonWasTernary = false
                } else if (isColonTokenType(type)) {
                    if (ternaryDepth > 0) {
                        ternaryDepth--
                        lastColonWasTernary = true
                    } else {
                        lastColonWasTernary = false
                    }
                } else {
                    if (isSemiTokenType(type)) {
                        ternaryDepth = 0
                    }
                    lastColonWasTernary = false
                }
                lastParenWasCondition = false
                lastBraceAllowedRegex = false
            }
            lastNonHiddenType = type
            lastNonHiddenText = text
        }
        return tok
    }

    protected fun isAtStartOfFile(): Boolean = _tokenStartCharIndex == 0

    protected fun isRegexAllowed(): Boolean {
        if (lastNonHiddenType == Token.INVALID_TYPE) return true
        return allowsRegexAfterToken(
            type = lastNonHiddenType,
            text = lastNonHiddenText,
            lastParenWasCond = lastParenWasCondition,
            lastBraceAllowedRegex = lastBraceAllowedRegex,
        )
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
        val tagEnd = pos
        val nameLen = tagEnd - nameStart
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
        var hadTypeArgs = false
        if (next == '<'.code) {
            hadTypeArgs = true
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
        }

        return when {
            !hadTypeArgs && (next == ','.code || next == '='.code || next == ':'.code) -> false
            !hadTypeArgs && next == 'e'.code && matchesExtendsAt(pos) -> false
            next == '/'.code && _input.LA(pos + 1) == '>'.code -> true
            next == '{'.code || isJsIdentStartCodePoint(next) -> true
            next == '>'.code -> {
                if (!isTypeScript) return true
                val tagName = readTagName(nameStart, tagEnd)
                val gtOffset = _input.index() + pos - 1
                hasClosingTagAfter(tagName, gtOffset)
            }
            else -> false
        }
    }

    private fun hasClosingTagAfter(tagName: String, gtOffset: Int): Boolean {
        val index = lastClosingTagOffsetByName ?: buildClosingTagIndex().also { lastClosingTagOffsetByName = it }
        return (index[tagName] ?: -1) > gtOffset
    }

    private fun buildClosingTagIndex(): HashMap<String, Int> {
        val map = HashMap<String, Int>()
        val baseIndex = _input.index()
        var offset = 1
        while (true) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF) break
            if (ch == '<'.code && _input.LA(offset + 1) == '/'.code && isJsIdentStartCodePoint(_input.LA(offset + 2))) {
                val tagStartOffset = offset + 2
                var p = tagStartOffset + 1
                while (true) {
                    val c = _input.LA(p)
                    if (c != IntStream.EOF && (isJsIdentPartCodePoint(c) || c == '.'.code || c == ':'.code || c == '-'.code)) {
                        p++
                    } else {
                        break
                    }
                }
                val after = _input.LA(p)
                if (after == '>'.code || after == ' '.code || after == '\t'.code || after == '\r'.code || after == '\n'.code) {
                    val tagName = readTagName(tagStartOffset, p)
                    map[tagName] = baseIndex + offset - 1
                }
                offset = p
            } else {
                offset++
            }
        }
        return map
    }

    private fun readTagName(startOffset: Int, endOffset: Int): String = buildString(endOffset - startOffset) {
        for (k in startOffset until endOffset) {
            appendCodePoint(_input.LA(k))
        }
    }

    private fun StringBuilder.appendCodePoint(cp: Int) {
        if (cp <= 0xFFFF) {
            append(cp.toChar())
        } else {
            val u = cp - 0x10000
            append(((u ushr 10) + 0xD800).toChar())
            append(((u and 0x3FF) + 0xDC00).toChar())
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
