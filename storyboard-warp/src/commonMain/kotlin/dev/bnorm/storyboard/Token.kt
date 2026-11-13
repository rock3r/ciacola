package dev.bnorm.storyboard

/**
 * The [Token] that will be passed to the diffing algorithm to find structural similarities.
 *
 * We are using a combination of the [content], and the `primary` [scope] + its [depth] to find
 * `anchor`s.
 */
class Token(
    /** The actual content of the parsed token. */
    val content: String,
    /** The primary scope */
    val scope: String,
    /** The depth of the primary scope. */
    val depth: Int,
    /* More context for animations and rendering. */
    val language: Language,
    val allScopes: List<String>,
    val lineNumber: Int,
    val startIndex: Int,
    val endIndex: Int
) {
    /** The underlying unique key that was assigned to the token.
     * This is guaranteed to be stable across a story board. */
    private var key: String? = null

    fun hasKey(): Boolean {
        return key != null
    }

    fun assignKey(newKey: String) {
        val key = key
        check(key == null) { "Cannot override `key` for $this" }
        this.key = newKey
    }

    fun key(): String {
        val contentId = key
        check(contentId != null) { "`key` was not assigned to $this" }
        return contentId
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Token) return false
        if (depth != other.depth) return false
        if (content != other.content) return false
        if (scope != other.scope) return false

        return true
    }

    override fun hashCode(): Int {
        var result = depth
        result = 31 * result + content.hashCode()
        result = 31 * result + scope.hashCode()
        return result
    }

    override fun toString(): String {
        return "Token(content='$content', scope='$scope', depth=$depth, lineNumber=$lineNumber, startIndex=$startIndex, endIndex=$endIndex)"
    }
}

private val CODE_MULTI_CHAR_OPERATORS = arrayOf(
    "===", "!==", "&&", "||", "==", "!=", ">=", "<=", "!!", "?:", "::", "->", "..",
)

/**
 * Splits coarse TextMate/Shiki spans (such as `"(s"`, `"(s)"`, `"  s"`, or `"(s, alpha "`) into
 * atomic lexical code tokens while preserving each slice's exact syntax highlight scope and line
 * offsets, so delimiters like `'('` and `')'` and identifiers like `'s'` match individually across
 * single-line <-> multi-line code reflows.
 */
internal fun List<Token>.splitIntoLexicalTokens(): List<Token> = buildList(size * 2) {
    for (token in this@splitIntoLexicalTokens) {
        val text = token.content
        if (text.length <= 1) {
            add(token)
            continue
        }
        var offset = 0
        while (offset < text.length) {
            val multiOp = CODE_MULTI_CHAR_OPERATORS.firstOrNull { op -> text.startsWith(op, offset) }
            val end = when {
                multiOp != null -> offset + multiOp.length
                text[offset].isLetterOrDigit() || text[offset] == '_' -> {
                    var i = offset + 1
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    i
                }
                text[offset].isWhitespace() -> {
                    var i = offset + 1
                    while (i < text.length && text[i].isWhitespace()) i++
                    i
                }
                else -> offset + 1
            }
            if (offset == 0 && end == text.length) {
                add(token)
            } else {
                add(
                    Token(
                        content = text.substring(offset, end),
                        scope = token.scope,
                        depth = token.depth,
                        language = token.language,
                        allScopes = token.allScopes,
                        lineNumber = token.lineNumber,
                        startIndex = token.startIndex + offset,
                        endIndex = token.startIndex + end,
                    )
                )
            }
            offset = end
        }
    }
}
