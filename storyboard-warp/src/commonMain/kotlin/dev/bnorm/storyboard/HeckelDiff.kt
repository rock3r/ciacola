package dev.bnorm.storyboard

import androidx.collection.ScatterMap
import androidx.collection.mutableScatterMapOf


// This is an adaptation of the paper:
// P. Heckel, A technique for isolating differences between files
//Comm. ACM, 21, (4), 264–268 (1978).

// This can help compute structural similarities between 2 text sequences.
// Once we know this, we should be able to animate cleanly between these 2 sequences.

fun diff(
    previous: List<Token>,
    current: List<Token>,
    keys: Keys = Keys()
): List<State> {
    // Symbol tables
    val statesP: Array<State> = Array(previous.size) { State.Empty }
    val statesC: Array<State> = Array(current.size) { State.Empty }

    fun matchTokens(pIdx: Int, cIdx: Int) {
        val match = State.Match(
            previous = previous[pIdx],
            previousIdx = pIdx,
            current = current[cIdx],
            currentIdx = cIdx,
        )
        statesP[pIdx] = match
        statesC[cIdx] = match
    }

    fun matchAndWidenRange(pStart: Int, pEnd: Int, cStart: Int, cEnd: Int) {
        if (pStart >= pEnd || cStart >= cEnd) return
        val (freqP, contextP) = frequencyAndContextInRange(previous, pStart, pEnd)
        val (freqC, _) = frequencyAndContextInRange(current, cStart, cEnd)
        for (cIdx in cStart until cEnd) {
            if (statesC[cIdx] != State.Empty) continue
            val tokenC = current[cIdx]
            if (tokenC.content.isBlank()) continue
            val cCount = freqC[tokenC.content] ?: 0
            val pCount = freqP[tokenC.content] ?: 0
            val ctx = contextP[tokenC.content]
            if (cCount == 1 && pCount == 1 && ctx != null && statesP[ctx.index] == State.Empty) {
                matchTokens(ctx.index, cIdx)
            }
        }
        // Forward widening across matching lexical content (insensitive to TextMate rule-stack depth
        // shifts caused by wrapping a single-line signature across multiple lines).
        for (cIdx in cStart until (cEnd - 1)) {
            val state = statesC[cIdx]
            if (state is State.Match && statesC[cIdx + 1] == State.Empty) {
                val nextPIdx = state.previousIdx + 1
                if (
                    nextPIdx in pStart until pEnd &&
                    statesP[nextPIdx] == State.Empty &&
                    current[cIdx + 1].content == previous[nextPIdx].content
                ) {
                    matchTokens(nextPIdx, cIdx + 1)
                }
            }
        }
        // Backward widening across matching lexical content.
        for (cIdx in (cEnd - 1) downTo (cStart + 1)) {
            val state = statesC[cIdx]
            if (state is State.Match && statesC[cIdx - 1] == State.Empty) {
                val priorPIdx = state.previousIdx - 1
                if (
                    priorPIdx in pStart until pEnd &&
                    statesP[priorPIdx] == State.Empty &&
                    current[cIdx - 1].content == previous[priorPIdx].content
                ) {
                    matchTokens(priorPIdx, cIdx - 1)
                }
            }
        }
    }

    // Global pass: unique non-blank anchors + bidirectional neighborhood widening.
    matchAndWidenRange(0, previous.size, 0, current.size)

    // Local pass inside each monotonic gap between already-matched anchors so repeated identifiers
    // or delimiters (e.g. `s` or `(`) inside an enclosed parameter/argument list still pair cleanly.
    var prevAnchorP = -1
    var prevAnchorC = -1
    for (cIdx in 0..statesC.size) {
        val m = if (cIdx < statesC.size) statesC[cIdx] as? State.Match else null
        if (m != null) {
            if (m.previousIdx > prevAnchorP) {
                matchAndWidenRange(prevAnchorP + 1, m.previousIdx, prevAnchorC + 1, cIdx)
                prevAnchorP = m.previousIdx
                prevAnchorC = cIdx
            }
        } else if (cIdx == statesC.size) {
            matchAndWidenRange(prevAnchorP + 1, previous.size, prevAnchorC + 1, current.size)
        }
    }

    // Phase 4: Final pass.
    // Anything that did not match in:
    // - statesP = Delete
    // - statesC = Insert
    for (i in statesP.indices) {
        if (statesP[i] == State.Empty) {
            statesP[i] = State.Delete(token = previous[i], index = i)
        }
    }
    val size = maxOf(statesP.size, statesC.size)
    // Keeps track of the last known deleted index in statesP
    var deleteIdx = 0
    // This is the final edit script.
    val edits = ArrayList<State>(size)
    for (i in 0 until size) {
        if (i < statesP.size && deleteIdx <= i && statesP[i] is State.Delete) {
            // We want to cluster all the deletes that occur next to each other.
            var j = i
            while (j < statesP.size && statesP[j] is State.Delete) {
                // Add deletions to the list of edits.
                edits += statesP[j]
                j += 1
            }
            deleteIdx = j
        }
        if (i < statesC.size) {
            val state = statesC[i]
            if (state is State.Match) {
                if (state.previous.hasKey()) {
                    // Propagate keys for matched tokens
                    state.current.assignKey(newKey = state.previous.key())
                }
            }
            if (statesC[i] == State.Empty) {
                // Assign a new key for newly inserted tokens
                keys.assignKey(current[i])
                statesC[i] = State.Insert(token = current[i], index = i)
            }
            edits += statesC[i]
        }
    }
    return edits
}

internal data class TokenContext(val token: Token, val index: Int)

internal fun frequencyAndContextInRange(
    tokens: List<Token>,
    start: Int,
    end: Int,
): Pair<ScatterMap<String, Int>, ScatterMap<String, TokenContext?>> {
    val freq = mutableScatterMapOf<String, Int>()
    val indexes = mutableScatterMapOf<String, TokenContext?>()
    for (index in start until end) {
        val token = tokens[index]
        if (token.content.isBlank()) continue
        val key = token.content
        val count = freq.getOrDefault(key, 0)
        if (count == 0) {
            indexes[key] = TokenContext(token = token, index = index)
        } else {
            indexes[key] = null
        }
        freq[key] = count + 1
    }
    return freq to indexes
}
