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
    private val failedClosingTagRanges = HashMap<String, Long>()

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
        failedClosingTagRanges.clear()
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
            next == '>'.code -> isBareOpeningTagJsx(nameStart, tagEnd, pos + 1)
            else -> false
        }
    }

    private fun isBareOpeningTagJsx(tagStart: Int, tagEnd: Int, afterGtOffset: Int): Boolean {
        val scanLimit = 1024
        var p = afterGtOffset
        while (p < afterGtOffset + scanLimit) {
            val ch = _input.LA(p)
            if (ch == ' '.code || ch == '\t'.code || ch == '\r'.code || ch == '\n'.code) {
                p++
            } else {
                break
            }
        }
        if (_input.LA(p) == '('.code && looksLikeGenericArrowParams(p, afterGtOffset + scanLimit)) {
            return false
        }
        return hasMatchingClosingTag(tagStart, tagEnd, afterGtOffset, scanLimit)
    }

    private fun looksLikeGenericArrowParams(openParenOffset: Int, maxOffset: Int): Boolean {
        var paren = 1
        var p = openParenOffset + 1
        while (p < maxOffset && paren > 0) {
            val ch = _input.LA(p)
            if (ch == IntStream.EOF) return false
            if (ch == '('.code) {
                paren++
            } else if (ch == ')'.code) {
                paren--
            }
            p++
        }
        if (paren > 0) return false
        while (p < maxOffset) {
            val ch = _input.LA(p)
            if (ch == ' '.code || ch == '\t'.code || ch == '\r'.code || ch == '\n'.code) {
                p++
            } else {
                break
            }
        }
        val after = _input.LA(p)
        return after == ':'.code || (after == '='.code && _input.LA(p + 1) == '>'.code)
    }

    private fun hasMatchingClosingTag(
        tagStart: Int,
        tagEnd: Int,
        scanFromOffset: Int,
        scanLimit: Int,
    ): Boolean {
        val tagLen = tagEnd - tagStart
        val tagName = buildString(tagLen) {
            for (k in tagStart until tagEnd) {
                appendCodePoint(_input.LA(k))
            }
        }
        val baseIndex = _input.index()
        val absStart = baseIndex + scanFromOffset - 1
        val absEndLimit = absStart + scanLimit

        val cached = failedClosingTagRanges[tagName]
        var scanAbs = absStart
        if (cached != null) {
            val cachedStart = (cached ushr 32).toInt()
            val cachedEnd = cached.toInt()
            if (absStart in cachedStart..cachedEnd) {
                if (cachedEnd >= absEndLimit) return false
                scanAbs = (cachedEnd - tagLen - 2).coerceAtLeast(absStart)
            }
        }

        var offset = scanAbs - baseIndex + 1
        val maxOffset = scanFromOffset + scanLimit
        while (offset < maxOffset) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF) {
                failedClosingTagRanges[tagName] = (absStart.toLong() shl 32) or (absEndLimit.toLong() and 0xFFFFFFFFL)
                return false
            }
            if (ch == '<'.code && _input.LA(offset + 1) == '/'.code) {
                var matched = true
                for (i in 0 until tagLen) {
                    if (_input.LA(offset + 2 + i) != _input.LA(tagStart + i)) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    val after = _input.LA(offset + 2 + tagLen)
                    if (after == '>'.code || after == ' '.code || after == '\t'.code || after == '\r'.code || after == '\n'.code) {
                        return true
                    }
                }
            }
            offset++
        }
        failedClosingTagRanges[tagName] = (absStart.toLong() shl 32) or (absEndLimit.toLong() and 0xFFFFFFFFL)
        return false
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
