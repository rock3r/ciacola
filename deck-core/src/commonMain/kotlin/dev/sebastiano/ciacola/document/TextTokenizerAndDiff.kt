package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import dev.bnorm.storyboard.text.magic.MagicTextDiff
import dev.bnorm.storyboard.text.magic.MorphBy as MagicMorphBy

/**
 * A single logical token produced by the layout-free tokenizer.
 *
 * [start] and [endExclusive] are UTF-16 code-unit offsets into the source string.
 * [occurrence] is the 0-based count of prior tokens with the exact same [text] in that string.
 */
@Immutable
data class TextToken(
    val text: String,
    val start: Int,
    val endExclusive: Int,
    val occurrence: Int,
    val lineIndex: Int,
) {
    /** Stable fragment address without direction prefix: `"<text>#<occurrence>"`. */
    val address: String get() = "$text#$occurrence"

    /** Stable fragment address for an exiting fragment: `"-<text>#<occurrence>"`. */
    val exitAddress: String get() = "-$text#$occurrence"
}

enum class TextDiffFragmentKind {
    Kept,
    Entering,
    Exiting,
}

@Immutable
data class TextDiffFragment(
    val kind: TextDiffFragmentKind,
    val address: String,
    val fromToken: TextToken?,
    val toToken: TextToken?,
) {
    val lineIndex: Int
        get() = (toToken ?: fromToken)!!.lineIndex
}

@Immutable
data class PureTextDiff(
    val fromTokens: List<TextToken>,
    val toTokens: List<TextToken>,
    val fragments: List<TextDiffFragment>,
) {
    val kept: List<TextDiffFragment> get() = fragments.filter { it.kind == TextDiffFragmentKind.Kept }
    val entering: List<TextDiffFragment> get() = fragments.filter { it.kind == TextDiffFragmentKind.Entering }
    val exiting: List<TextDiffFragment> get() = fragments.filter { it.kind == TextDiffFragmentKind.Exiting }
}

/**
 * Pure, layout-free text tokenizer and token diff shared by the planner, fragment
 * provenance remapper, and MagicText v2 renderer via [MagicTextDiff].
 */
object TextTokenizer {

    private fun MorphBy.toMagicMorphBy(): MagicMorphBy = when (this) {
        MorphBy.Word -> MagicMorphBy.Word
        MorphBy.Character -> MagicMorphBy.Character
        MorphBy.Line, MorphBy.Paragraph -> MagicMorphBy.Line
    }

    fun tokenize(text: String, by: MorphBy): List<TextToken> {
        if (text.isEmpty()) return emptyList()
        val ranges = MagicTextDiff.tokenize(AnnotatedString(text), by.toMagicMorphBy())
        return rangesToTokens(text, ranges)
    }

    fun tokenizeForDelivery(text: String, by: DeliveryBy): List<TextToken> = when (by) {
        DeliveryBy.Paragraph -> tokenize(text, MorphBy.Line)
        DeliveryBy.Word -> tokenize(text, MorphBy.Word)
        DeliveryBy.Character -> tokenize(text, MorphBy.Character)
        DeliveryBy.Whole, DeliveryBy.Child -> emptyList()
    }

    fun tokenizeWords(text: String): List<TextToken> = tokenize(text, MorphBy.Word)

    fun tokenizeGraphemes(text: String): List<TextToken> = tokenize(text, MorphBy.Character)

    fun tokenizeLines(text: String): List<TextToken> = tokenize(text, MorphBy.Line)

    /**
     * Computes a pure deterministic token diff between [fromText] and [toText] by delegating
     * directly to [MagicTextDiff.plan] and [MagicTextDiff.fragmentAddresses].
     */
    fun diff(
        fromText: String,
        toText: String,
        by: MorphBy = MorphBy.Word,
        pruneStopwords: Boolean = true,
    ): PureTextDiff {
        val fromAnnotated = AnnotatedString(fromText)
        val toAnnotated = AnnotatedString(toText)
        val magicBy = by.toMagicMorphBy()

        val fromTokens = rangesToTokens(fromText, MagicTextDiff.tokenize(fromAnnotated, magicBy))
        val toTokens = rangesToTokens(toText, MagicTextDiff.tokenize(toAnnotated, magicBy))
        if (fromTokens.isEmpty() && toTokens.isEmpty()) {
            return PureTextDiff(emptyList(), emptyList(), emptyList())
        }

        val matches = MagicTextDiff.plan(
            from = fromAnnotated,
            to = toAnnotated,
            by = magicBy,
            pruneStopwords = pruneStopwords,
        )
        val addresses = MagicTextDiff.fragmentAddresses(fromAnnotated, toAnnotated, matches)

        val fromByRange = HashMap<TextRange, TextToken>(fromTokens.size)
        for (t in fromTokens) {
            fromByRange[TextRange(t.start, t.endExclusive)] = t
        }
        val toByRange = HashMap<TextRange, TextToken>(toTokens.size)
        for (t in toTokens) {
            toByRange[TextRange(t.start, t.endExclusive)] = t
        }

        val fragments = ArrayList<TextDiffFragment>(matches.size)
        for (i in matches.indices) {
            val match = matches[i]
            val address = addresses[i]
            val fromTok = match.before?.let { fromByRange[it] }
            val toTok = match.after?.let { toByRange[it] }
            val kind = when {
                fromTok != null && toTok != null -> TextDiffFragmentKind.Kept
                toTok != null -> TextDiffFragmentKind.Entering
                else -> TextDiffFragmentKind.Exiting
            }
            fragments += TextDiffFragment(
                kind = kind,
                address = address,
                fromToken = fromTok,
                toToken = toTok,
            )
        }

        return PureTextDiff(fromTokens, toTokens, fragments)
    }

    private fun rangesToTokens(text: String, ranges: List<TextRange>): List<TextToken> {
        if (ranges.isEmpty()) return emptyList()
        val result = ArrayList<TextToken>(ranges.size)
        val counts = HashMap<String, Int>(ranges.size)
        var lineIndex = 0
        var scanPos = 0
        for (range in ranges) {
            while (scanPos < range.start) {
                if (text[scanPos] == '\n') {
                    lineIndex++
                }
                scanPos++
            }
            val slice = text.substring(range.start, range.end)
            val occ = counts[slice] ?: 0
            counts[slice] = occ + 1
            result += TextToken(
                text = slice,
                start = range.start,
                endExclusive = range.end,
                occurrence = occ,
                lineIndex = lineIndex,
            )
        }
        return result
    }
}
