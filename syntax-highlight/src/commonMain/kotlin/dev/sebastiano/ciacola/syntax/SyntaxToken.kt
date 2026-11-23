package dev.sebastiano.ciacola.syntax

/**
 * Lexical span into an input source string with a semantic [SyntaxTokenKind].
 *
 * Character offsets [startIndex] (inclusive) and [endIndex] (exclusive) index directly into the
 * original input string passed to [SyntaxLexer.lex].
 */
public data class SyntaxToken(
    public val kind: SyntaxTokenKind,
    public val startIndex: Int,
    public val endIndex: Int,
) {
    init {
        require(startIndex >= 0) { "startIndex must be >= 0, was $startIndex" }
        require(endIndex >= startIndex) { "endIndex ($endIndex) must be >= startIndex ($startIndex)" }
    }

    /** Number of UTF-16 code units covered by this token. */
    public val length: Int get() = endIndex - startIndex

    /** Returns the substring of [source] covered by this token. */
    public fun slice(source: String): String = source.substring(startIndex, endIndex)
}

/**
 * Semantic classification of a [SyntaxToken].
 */
public enum class SyntaxTokenKind {
    Plain,
    Whitespace,
    Keyword,
    String,
    Comment,
    Number,
    Constant,
    Function,
    Type,
    Property,
    Annotation,
    Attribute,
    Tag,
    Operator,
    Preprocessor,
    Punctuation,
    Invalid,
}
