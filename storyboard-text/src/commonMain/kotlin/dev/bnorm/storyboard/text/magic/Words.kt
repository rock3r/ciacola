package dev.bnorm.storyboard.text.magic

// Modified from Storyboard upstream: Unicode-safe semantic token boundaries.

import androidx.compose.ui.text.AnnotatedString

// Tokenize an AnnotatedString into a list of words.
fun AnnotatedString.toWords(): List<AnnotatedString> {
    return buildList {
        var last = 0
        while (last < length) {
            val (i, word) = text.findAnyOf(SYMBOL_WORDS, startIndex = last) ?: break
            if (i > last) addAll(subSequence(last, i).split())
            add(subSequence(i, i + word.length))
            last = i + word.length
        }
        if (last < length) addAll(subSequence(last, length).split())
    }
}

private val SYMBOL_WORDS = setOf(
    "&&",
    "||",
    "==",
    "!=",
    "===",
    "!==",
    ">=",
    "<=",
    "!!",
    "?:",
    "::",
    "->",
    "\"\"\"",
)

private enum class RangeType {
    Word {
        override fun matches(c: Char): Boolean {
            return c.isLetterOrDigit() || c == '_' || c.isCombiningMark()
        }

    },
    Whitespace {
        override fun matches(c: Char): Boolean {
            return c.isWhitespace()
        }
    },
    ;

    abstract fun matches(c: Char): Boolean
}

private fun AnnotatedString.split(): List<AnnotatedString> {
    return buildList {
        var offset = 0
        while (offset < this@split.length) {
            val type = RangeType.entries.firstOrNull { it.matches(this@split[offset]) }
            val end = if (type == null) {
                extendedSymbolEnd(offset)
            } else {
                var candidate = offset + 1
                while (candidate < this@split.length && type.matches(this@split[candidate])) {
                    candidate++
                }
                candidate
            }
            add(subSequence(offset, end))
            offset = end
        }
    }
}

private fun AnnotatedString.extendedSymbolEnd(start: Int): Int {
    var end = nextCodePointEnd(start)
    var regionalIndicatorCount = if (isRegionalIndicator(start)) 1 else 0

    while (end < length) {
        when {
            this[end].isCombiningMark() || isVariationSelector(end) || isEmojiModifier(end) -> {
                end = nextCodePointEnd(end)
            }
            this[end] == '\u200D' && end + 1 < length -> {
                end = nextCodePointEnd(end + 1)
            }
            regionalIndicatorCount == 1 && isRegionalIndicator(end) -> {
                regionalIndicatorCount++
                end = nextCodePointEnd(end)
            }
            else -> return end
        }
    }
    return end
}

private fun AnnotatedString.nextCodePointEnd(start: Int): Int {
    return if (this[start].isHighSurrogate() && start + 1 < length && this[start + 1].isLowSurrogate()) {
        start + 2
    } else {
        start + 1
    }
}

private fun AnnotatedString.codePointAt(index: Int): Int {
    val high = this[index]
    if (!high.isHighSurrogate() || index + 1 >= length) return high.code
    val low = this[index + 1]
    if (!low.isLowSurrogate()) return high.code
    return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
}

private fun AnnotatedString.isEmojiModifier(index: Int): Boolean = codePointAt(index) in 0x1F3FB..0x1F3FF

private fun AnnotatedString.isRegionalIndicator(index: Int): Boolean = codePointAt(index) in 0x1F1E6..0x1F1FF

private fun AnnotatedString.isVariationSelector(index: Int): Boolean {
    return codePointAt(index) in 0xFE00..0xFE0F || codePointAt(index) in 0xE0100..0xE01EF
}

private fun Char.isCombiningMark(): Boolean {
    return category == CharCategory.COMBINING_SPACING_MARK ||
            category == CharCategory.ENCLOSING_MARK ||
            category == CharCategory.NON_SPACING_MARK
}
