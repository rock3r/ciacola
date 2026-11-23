package dev.sebastiano.ciacola.syntax.antlr.cpp

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.misc.Interval

/**
 * Pure Kotlin ANTLR4 lexer base for C and C++, providing linear-time C++ raw string delimiter
 * matching (`R"delim(...)delim"`) and C vs C++ mode gating without regex or JVM dependencies.
 */
internal abstract class CppLexerBase(input: CharStream) : Lexer(input) {
    /** Enables C++-specific lexical forms such as `R"delim(...)delim"` raw strings. */
    internal var enableCppFeatures: Boolean = true

    /**
     * Consumes the body and closing `)delim"` terminator of a C++ raw string literal whose
     * opening `[u8|u|U|L]R"delim(` prefix has already been matched.
     */
    protected fun consumeCppRawString() {
        val header = _input.getText(Interval(_tokenStartCharIndex, _input.index() - 1))
        val quoteIdx = header.indexOf('"')
        val delim = if (quoteIdx >= 0 && header.length - 1 > quoteIdx + 1) {
            toCodePoints(header.substring(quoteIdx + 1, header.length - 1))
        } else {
            IntArray(0)
        }

        while (_input.LA(1) != IntStream.EOF) {
            if (_input.LA(1) == ')'.code) {
                _input.consume()
                var matched = true
                for (j in delim.indices) {
                    if (_input.LA(j + 1) != delim[j]) {
                        matched = false
                        break
                    }
                }
                if (matched && _input.LA(delim.size + 1) == '"'.code) {
                    repeat(delim.size + 1) { _input.consume() }
                    return
                }
            } else {
                _input.consume()
            }
        }
    }

    private fun toCodePoints(str: String): IntArray {
        val out = IntArray(str.length)
        var count = 0
        var i = 0
        while (i < str.length) {
            val hi = str[i]
            if (i + 1 < str.length && hi.isHighSurrogate() && str[i + 1].isLowSurrogate()) {
                val lo = str[i + 1]
                out[count++] = 0x10000 + ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00)
                i += 2
            } else {
                out[count++] = hi.code
                i++
            }
        }
        return if (count == out.size) out else out.copyOf(count)
    }
}
