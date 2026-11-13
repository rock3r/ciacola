package dev.bnorm.storyboard.text.magic

// Modified from Storyboard upstream: stable whitespace correspondence and large-run invariant.

import androidx.compose.ui.text.AnnotatedString
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class SharedText(
    val value: AnnotatedString,
    val key: String? = null,
    var crossFade: Boolean = false,
)

private class MutableSharedText(
    val index: Int,
    val value: AnnotatedString,
) : Comparable<MutableSharedText> {

    var key: String? = null
    var crossFade: Boolean = false
    var previousShared: SharedText? = null

    var prev: MutableSharedText? = null
    var next: MutableSharedText? = null

    fun move(direction: Move, stopOnLineBreaks: Boolean): MutableSharedText? {
        tailrec fun rec(text: MutableSharedText): MutableSharedText? {
            val iter = when (direction) {
                Move.Next -> text.next
                Move.Previous -> text.prev
            }
            return when {
                iter == null -> null
                iter.value.text != "\n" -> iter
                stopOnLineBreaks -> null
                else -> rec(iter)
            }
        }

        return rec(this)
    }

    fun toSharedItem(): SharedText = SharedText(value, key, crossFade)

    override fun compareTo(other: MutableSharedText): Int {
        return compareValues(index, other.index)
    }

    override fun toString(): String {
        return "MutableSharedItem(value=$value, key=$key, crossFade=$crossFade)"
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal fun findShared(
    key: AtomicLong,
    before: List<SharedText>,
    after: List<AnnotatedString>,
): List<SharedText> {
    val beforeItems = before.mapIndexed { index, value ->
        MutableSharedText(index, value.value).apply { previousShared = value }
    }
    val afterItems = after.mapIndexed { index, value -> MutableSharedText(index, value) }

    // Whitespace participates in correspondence as well. Excluding it made unchanged caller-supplied
    // tokens acquire fresh shared-bounds keys on every transition and fragmented large unchanged runs.
    val beforeNonBlank = beforeItems
    val afterNonBlank = afterItems

    var prev: MutableSharedText? = null
    for (item in beforeNonBlank) {
        item.prev = prev
        prev?.next = item
        prev = item
    }

    prev = null
    for (item in afterNonBlank) {
        item.prev = prev
        prev?.next = item
        prev = item
    }

    findSharedInternal(beforeNonBlank, afterNonBlank)
    pruneUnanchoredProseMatches(beforeItems, afterItems)

    for (text in afterItems) {
        if (text.key == null) text.key = "stable:${key.addAndFetch(1)}"
    }

    // Adjacent unchanged tokens intentionally remain separate here: their existing shared-bounds keys
    // identify independently moving tokens. A paragraph-layout renderer can batch their drawing after
    // consuming the correspondence plan; v1 cannot merge them without changing transition geometry.
    return afterItems.map { it.toSharedItem() }
}

private const val PROSE_PUNCTUATION = ".,;:!?"

private val PROSE_STOP_WORDS = setOf(
    "a", "an", "the", "to", "of", "in", "on", "at", "by",
    "for", "is", "are", "was", "be", "or", "and", "as", "it",
)

private fun MutableSharedText.isBlankToken(): Boolean =
    value.text.isNotEmpty() && value.text != "\n" && value.text.all { it.isWhitespace() }

private fun MutableSharedText.isWordToken(): Boolean =
    value.text.any { it.isLetterOrDigit() }

private fun MutableSharedText.isProsePunctuation(): Boolean =
    value.text.isNotEmpty() && value.text.all { it in PROSE_PUNCTUATION }

private fun MutableSharedText.isProseStopWord(): Boolean =
    value.text.lowercase() in PROSE_STOP_WORDS

private fun MutableSharedText.adjacentWordOnLine(direction: Move): MutableSharedText? {
    var cursor = move(direction, stopOnLineBreaks = true)
    while (cursor != null) {
        if (cursor.isWordToken()) return cursor
        if (!cursor.isBlankToken() && !cursor.isProsePunctuation()) return cursor
        cursor = cursor.move(direction, stopOnLineBreaks = true)
    }
    return null
}

private fun MutableSharedText.unmatch(after: MutableSharedText) {
    previousShared?.crossFade = false
    key = null
    crossFade = false
    after.key = null
    after.crossFade = false
}

/**
 * Drops accidental matches that look broken in Magic Move typography:
 * 1. Detached sentence punctuation (`.` / `,` / `;` / `:` / `!` / `?`) whose attached word did not match
 *    (so periods never detach from `state.` and fly onto `model.` on another line).
 * 2. Isolated short prose function words (`a`, `to`, `is`, `the`, ...) outside the common prefix when
 *    neither neighboring word matched in a multi-word sentence.
 * 3. Orphan whitespace tokens left without any adjacent matched non-blank token.
 */
private fun pruneUnanchoredProseMatches(
    beforeItems: List<MutableSharedText>,
    afterItems: List<MutableSharedText>,
) {
    val hasWords = beforeItems.any { it.isWordToken() } || afterItems.any { it.isWordToken() }
    if (!hasWords) return

    val afterByKey = HashMap<String, MutableSharedText>(afterItems.size)
    for (item in afterItems) {
        val k = item.key ?: continue
        afterByKey[k] = item
    }

    var commonPrefix = 0
    while (
        commonPrefix < beforeItems.size &&
        commonPrefix < afterItems.size &&
        beforeItems[commonPrefix].key != null &&
        beforeItems[commonPrefix].key == afterItems[commonPrefix].key
    ) {
        commonPrefix++
    }

    for (b in beforeItems) {
        val k = b.key ?: continue
        if (b.index < commonPrefix || !b.isProsePunctuation()) continue
        val a = afterByKey[k] ?: continue
        val leftAnchored = b.prev != null && a.prev != null &&
            !b.prev!!.isBlankToken() && b.prev!!.key != null && b.prev!!.key == a.prev!!.key
        val rightAnchored = b.next != null && a.next != null &&
            !b.next!!.isBlankToken() && b.next!!.key != null && b.next!!.key == a.next!!.key
        if (!leftAnchored && !rightAnchored) {
            b.unmatch(a)
            afterByKey.remove(k)
        }
    }

    val beforeWordCount = beforeItems.count { it.isWordToken() }
    val afterWordCount = afterItems.count { it.isWordToken() }
    if (beforeWordCount >= 3 && afterWordCount >= 3) {
        for (b in beforeItems) {
            val k = b.key ?: continue
            if (b.index < commonPrefix || !b.isProseStopWord()) continue
            val a = afterByKey[k] ?: continue
            val bPrevWord = b.adjacentWordOnLine(Move.Previous)
            val aPrevWord = a.adjacentWordOnLine(Move.Previous)
            val prevAnchored = bPrevWord != null && aPrevWord != null &&
                bPrevWord.key != null && bPrevWord.key == aPrevWord.key
            val bNextWord = b.adjacentWordOnLine(Move.Next)
            val aNextWord = a.adjacentWordOnLine(Move.Next)
            val nextAnchored = bNextWord != null && aNextWord != null &&
                bNextWord.key != null && bNextWord.key == aNextWord.key
            if (!prevAnchored && !nextAnchored) {
                b.unmatch(a)
                afterByKey.remove(k)
            }
        }
    }

    for (b in beforeItems) {
        val k = b.key ?: continue
        if (b.index < commonPrefix || !b.isBlankToken()) continue
        val a = afterByKey[k] ?: continue
        val leftMatched = b.prev != null && a.prev != null &&
            !b.prev!!.isBlankToken() && b.prev!!.key != null && b.prev!!.key == a.prev!!.key
        val rightMatched = b.next != null && a.next != null &&
            !b.next!!.isBlankToken() && b.next!!.key != null && b.next!!.key == a.next!!.key
        if (!leftMatched && !rightMatched) {
            b.unmatch(a)
            afterByKey.remove(k)
        }
    }
}

private enum class Move {
    Next,
    Previous,
    ;
}

private data class MatchPair(
    val before: MutableSharedText,
    val after: MutableSharedText,
) : Comparable<MatchPair> {
    fun move(direction: Move, stopOnLineBreaks: Boolean): MatchPair? {
        return MatchPair(
            before = before.move(direction, stopOnLineBreaks) ?: return null,
            after = after.move(direction, stopOnLineBreaks) ?: return null,
        )
    }

    fun couldAssociate(): Boolean = unkeyed() && matches()

    fun unkeyed(): Boolean {
        return before.key == null && after.key == null
    }

    fun matches(): Boolean {
        return before.value.text == after.value.text
    }

    fun sequence(direction: Move, stopOnLineBreaks: Boolean): Sequence<MatchPair> =
        generateSequence(this) { it.move(direction, stopOnLineBreaks) }

    override fun compareTo(other: MatchPair): Int {
        return when {
            after < other.after && before < other.before -> -1
            after > other.after && before > other.before -> 1
            after == other.after && before == other.before -> 0
            else -> error("!")
        }
    }
}

@ConsistentCopyVisibility
private data class MatchRegion private constructor(
    val start: MatchPair,
    val end: MatchPair,
) {
    constructor(pair: MatchPair) : this(pair, pair)

    fun widen(pair: MatchPair): MatchRegion {
        return when {
            pair == start || pair == end -> this
            pair > end -> MatchRegion(start, pair)
            pair < start -> MatchRegion(pair, end)
            else -> error("!")
        }
    }
}

private fun findSharedInternal(
    beforeList: List<MutableSharedText>,
    afterList: List<MutableSharedText>,
) {
    val matches = mutableListOf<MatchRegion>()
    val ignoredKeys = mutableSetOf<String>()

    fun associate(pair: MatchPair) {
        val key = pair.before.previousShared!!.key!!
        pair.before.key = key
        pair.after.key = key

        if (pair.before.value != pair.after.value) {
            pair.before.previousShared!!.crossFade = true
            pair.before.crossFade = true
            pair.after.crossFade = true
        } else {
            pair.before.previousShared!!.crossFade = false
        }
    }

    fun associateDirection(
        pair: MatchPair,
        direction: Move,
        stopOnLineBreaks: Boolean,
    ): MatchPair {
        return pair.sequence(direction, stopOnLineBreaks)
            .drop(1)
            .takeWhile { it.couldAssociate() }
            .onEach { associate(it) }
            .lastOrNull() ?: pair
    }

    fun countDirection(
        pair: MatchPair,
        direction: Move,
        stopOnLineBreaks: Boolean,
    ): Int {
        return pair.sequence(direction, stopOnLineBreaks)
            .drop(1)
            .takeWhile { it.couldAssociate() }
            .count()
    }

    fun associateCenter(pair: MatchPair, stopOnLineBreaks: Boolean): MatchRegion {
        ignoredKeys.clear()

        var region = MatchRegion(pair)
        associate(pair)
        region = region.widen(associateDirection(pair, Move.Next, stopOnLineBreaks))
        region = region.widen(associateDirection(pair, Move.Previous, stopOnLineBreaks))
        return region
    }

    fun findMultiMatch(
        sharedKeys: Set<String>,
        groupedBefore: Map<String, List<MutableSharedText>>,
        groupedAfter: Map<String, List<MutableSharedText>>,
        ignoredKeys: MutableSet<String>,
        stopOnLineBreaks: Boolean,
        skipKeysWithoutBest: Boolean,
    ): MatchRegion? {
        for (key in sharedKeys) {
            val combinations = mutableListOf<Pair<Int, MatchPair>>()

            val beforeItems = groupedBefore.getValue(key)
            val afterItems = groupedAfter.getValue(key)
            for (before in beforeItems) {
                for (after in afterItems) {
                    val pair = MatchPair(before, after)
                    val strength = countDirection(pair, Move.Next, stopOnLineBreaks) +
                            countDirection(pair, Move.Previous, stopOnLineBreaks)
                    combinations.add(strength to pair)
                }
            }

            combinations.sortBy { -it.first }

            if (skipKeysWithoutBest && combinations[0].first == combinations[1].first) {
                ignoredKeys.add(key)
            } else {
                val pair = combinations[0].second
                return associateCenter(pair, stopOnLineBreaks)
            }
        }

        return null
    }

    fun matchUnique(stopOnLineBreaks: Boolean, skipKeysWithoutBest: Boolean) {
        ignoredKeys.clear()

        // TODO use priority queue of "key" to "value size" to avoid multiple loops
        outer@ while (true) {
            // TODO could optimize the management of these maps quite a bit i bet...
            var groupedBefore: Map<String, List<MutableSharedText>>
            var groupedAfter: Map<String, List<MutableSharedText>>

            unique@ while (true) {
                groupedBefore = beforeList.filter { it.key == null && it.value.text != "\n" }.groupBy { it.value.text }
                groupedAfter = afterList.filter { it.key == null && it.value.text != "\n" }.groupBy { it.value.text }

                val beforeKeys = groupedBefore.filter { it.value.size == 1 }.keys
                val afterKeys = groupedAfter.filter { it.value.size == 1 }.keys

                val sharedKeys = beforeKeys intersect afterKeys
                if (sharedKeys.isEmpty()) break@unique

                for (key in sharedKeys) {
                    val pair = MatchPair(groupedBefore.getValue(key)[0], groupedAfter.getValue(key)[0])
                    matches.add(associateCenter(pair, stopOnLineBreaks))
                }
            }

            // TODO improvements:
            //  - up to 3?
            //  - sort by count and take first?
            //  - this is exponential, so it should be limited... maybe?
            val beforeKeys = groupedBefore.filter { it.value.size <= 4 }.keys
            val afterKeys = groupedAfter.filter { it.value.size <= 4 }.keys

            val sharedKeys = (beforeKeys intersect afterKeys) - ignoredKeys
            if (sharedKeys.isEmpty()) break@outer

            val region = findMultiMatch(
                sharedKeys,
                groupedBefore,
                groupedAfter,
                ignoredKeys,
                stopOnLineBreaks,
                skipKeysWithoutBest,
            ) ?: break@outer
            matches.add(region)
        }
    }

    fun widenMatches() {
        for (region in matches.toList().also { matches.clear() }) {
            var wider = region
            wider = wider.widen(associateDirection(wider.end, Move.Next, stopOnLineBreaks = false))
            wider = wider.widen(associateDirection(wider.start, Move.Previous, stopOnLineBreaks = false))
            matches.add(wider)
        }
    }

    fun matchEdges() {
        // Match edges as much as possible as a last option.
        if (beforeList.isNotEmpty() && afterList.isNotEmpty()) {
            run {
                val pair = MatchPair(beforeList.first(), afterList.first())
                if (pair.matches() && pair.unkeyed()) {
                    associate(pair)
                    associateDirection(pair, Move.Next, stopOnLineBreaks = false)
                }
            }

            run {
                val pair = MatchPair(beforeList.last(), afterList.last())
                if (pair.matches() && pair.unkeyed()) {
                    associate(pair)
                    associateDirection(pair, Move.Previous, stopOnLineBreaks = false)
                }
            }
        }
    }

    matchUnique(stopOnLineBreaks = true, skipKeysWithoutBest = true)
    widenMatches()
    matchEdges()
    matchUnique(stopOnLineBreaks = false, skipKeysWithoutBest = false)
}
