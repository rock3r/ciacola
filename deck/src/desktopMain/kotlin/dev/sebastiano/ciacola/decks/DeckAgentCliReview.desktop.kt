package dev.sebastiano.ciacola.decks

import dev.bnorm.storyboard.easel.resolveDefaultStoryboardDir
import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.studio.storyboardDirForDeck
import java.nio.file.Path

/** Accepts the subset of statuses an agent may set on a comment it has acted on. */
internal fun parseReviewStatus(raw: String): ReviewCommentStatus =
    when (raw.trim().lowercase()) {
        "applied" -> ReviewCommentStatus.Applied
        "failed" -> ReviewCommentStatus.Failed
        "queued" -> ReviewCommentStatus.Queued
        else -> error("Invalid --review-status '$raw' (expected applied, failed or queued)")
    }

/**
 * Wiring for the review modes of [DeckAgentCli], kept out of that file so it stays under the
 * repository's god-file limit.
 */

/** Accepts `30m`, `45s`, `2h` or bare milliseconds, as `cav review wait --timeout` does. */
internal fun parseReviewTimeout(raw: String): Long {
    require(raw.isNotEmpty()) { "Empty --timeout value" }
    val (numberPart, multiplier) = when (raw.last()) {
        's', 'S' -> raw.dropLast(1) to 1_000L
        'm', 'M' -> raw.dropLast(1) to 60_000L
        'h', 'H' -> raw.dropLast(1) to 3_600_000L
        else -> raw to 1L
    }
    val value = numberPart.trim().toLongOrNull()
        ?: error("Invalid --timeout value '$raw' (expected e.g. 30m, 45s, 2h or milliseconds)")
    require(value > 0) { "--timeout must be positive, got '$raw'" }
    return value * multiplier
}

/**
 * `.storyboard/decks/<deckId>/` for the deck whose review queue the agent is reading.
 *
 * [storyboardDirArg] is the `.storyboard` root, not the directory holding `*.deck.json` files —
 * those are two different things and `--dir` means the latter.
 */
internal fun reviewDirFor(storyboardDirArg: String?, deckId: String): Path {
    val root = storyboardDirArg?.takeIf { it.isNotEmpty() }
        ?.let { Path.of(it).toAbsolutePath().normalize() }
        ?: resolveDefaultStoryboardDir()
    return storyboardDirForDeck(root, deckId)
}

internal fun runReviewWaitMode(storyboardDirArg: String?, deckId: String, timeoutMillis: Long): Int {
    val dir = reviewDirFor(storyboardDirArg, deckId)
    return DeckReviewCli.runWait(
        commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME),
        takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME),
        timeoutMillis = timeoutMillis,
    )
}

internal fun runReviewResolveMode(
    storyboardDirArg: String?,
    deckId: String,
    commentId: String,
    resolution: String,
    status: ReviewCommentStatus,
): Int {
    val dir = reviewDirFor(storyboardDirArg, deckId)
    return DeckReviewCli.runResolve(
        commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME),
        commentId = commentId,
        resolution = resolution,
        status = status,
    )
}

internal fun runReviewExportMode(
    storyboardDirArg: String?,
    deckId: String,
    includeTaken: Boolean,
    jsonOutput: Boolean,
): Int {
    val dir = reviewDirFor(storyboardDirArg, deckId)
    return DeckReviewCli.runExport(
        commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME),
        takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME),
        includeTaken = includeTaken,
        jsonOutput = jsonOutput,
    )
}
