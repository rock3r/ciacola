package dev.sebastiano.ciacola.syntax.antlr.rust

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin ANTLR4 lexer base for Rust, providing linear-time raw string delimiter matching
 * and lookahead helpers for trailing-dot float literals.
 */
internal abstract class RustLexerBase(input: CharStream) : Lexer(input) {
    /**
     * Consumes the body and closing `"###` delimiter of a Rust raw string (`r#"..."#`, `br#"..."#`,
     * `cr#"..."#`) whose opening prefix of length [prefixLen] (`1` for `r`, `2` for `br`/`cr`),
     * zero or more `#` characters, and opening `"` have already been matched.
     */
    protected fun consumeRustRawString(prefixLen: Int) {
        val hashes = (_input.index() - _tokenStartCharIndex - prefixLen - 1).coerceAtLeast(0)
        while (_input.LA(1) != IntStream.EOF) {
            if (_input.LA(1) == '"'.code) {
                _input.consume()
                var matched = 0
                while (matched < hashes && _input.LA(matched + 1) == '#'.code) {
                    matched++
                }
                if (matched == hashes) {
                    repeat(hashes) { _input.consume() }
                    return
                }
            } else {
                _input.consume()
            }
        }
    }

    /**
     * Returns `true` when a trailing `.` after digits is not followed by `.` (range `0..10`)
     * or an identifier start (method call `1.abs()`).
     */
    protected fun isRustTrailingDotFloat(): Boolean {
        val next = _input.LA(1)
        if (next == IntStream.EOF) return true
        if (next == '.'.code || next == '_'.code) return false
        if (next in 'a'.code..'z'.code || next in 'A'.code..'Z'.code) return false
        if (next >= 0x10000) {
            return next in 0x1F000..0x1FFFF || next > 0x323AF
        }
        return next < 0x80 || !next.toChar().isLetter()
    }
}
