package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.graphics.Color

internal data class CandidateSpan(
    val start: Int,
    val end: Int,
    val line: Int,
    val level: PartitionGranularity,
    val wordGroupId: Int,
    val isFullLine: Boolean,
)

internal fun computeCandidateGranularity(candidates: List<CandidateSpan>): PartitionGranularity = when {
    candidates.any { it.level == PartitionGranularity.Character } -> PartitionGranularity.Character
    candidates.any { it.level == PartitionGranularity.Word } -> PartitionGranularity.Word
    candidates.any { it.level == PartitionGranularity.Line } -> PartitionGranularity.Line
    else -> PartitionGranularity.Paragraph
}

internal fun coarsenAllCandidatesOneLevel(
    candidates: List<CandidateSpan>,
    textLength: Int,
): List<CandidateSpan>? {
    val finest = computeCandidateGranularity(candidates)
    return when (finest) {
        PartitionGranularity.Character -> {
            val merged = mutableListOf<CandidateSpan>()
            val doneWords = hashSetOf<Int>()
            for (cand in candidates) {
                if (cand.level == PartitionGranularity.Character) {
                    if (doneWords.add(cand.wordGroupId)) {
                        val wordCands = candidates.filter { it.wordGroupId == cand.wordGroupId }
                        merged += CandidateSpan(
                            start = wordCands.minOf { it.start },
                            end = wordCands.maxOf { it.end },
                            line = cand.line,
                            level = PartitionGranularity.Word,
                            wordGroupId = cand.wordGroupId,
                            isFullLine = false,
                        )
                    }
                } else {
                    merged += cand
                }
            }
            merged
        }
        PartitionGranularity.Word -> {
            val merged = mutableListOf<CandidateSpan>()
            val doneLines = hashSetOf<Int>()
            for (cand in candidates) {
                if (doneLines.add(cand.line)) {
                    val lineCands = candidates.filter { it.line == cand.line }
                    merged += CandidateSpan(
                        start = lineCands.minOf { it.start }.coerceIn(0, textLength),
                        end = lineCands.maxOf { it.end }.coerceIn(0, textLength),
                        line = cand.line,
                        level = PartitionGranularity.Line,
                        wordGroupId = lineCands.first().wordGroupId,
                        isFullLine = true,
                    )
                }
            }
            merged
        }
        else -> null
    }
}

internal fun computeEffectiveGranularity(pieces: List<AdmittedPiece>): PartitionGranularity {
    if (pieces.isEmpty()) return PartitionGranularity.Paragraph
    return when {
        pieces.any { it.level == PartitionGranularity.Character } -> PartitionGranularity.Character
        pieces.any { it.level == PartitionGranularity.Word } -> PartitionGranularity.Word
        pieces.any { it.level == PartitionGranularity.Line } -> PartitionGranularity.Line
        else -> PartitionGranularity.Paragraph
    }
}

internal val OPAQUE_NORMALIZATION_PALETTE: List<Color> = listOf(
    Color(0xFFFF3366),
    Color(0xFF33CCFF),
    Color(0xFF66FF66),
    Color(0xFFFFCC33),
    Color(0xFFCC66FF),
    Color(0xFFFF8833),
    Color(0xFF33FFCC),
    Color(0xFFFF66CC),
    Color(0xFF99FF33),
    Color(0xFF6699FF),
    Color(0xFFFFFFFF),
    Color(0xFFCCCCCC),
)
