package dev.sebastiano.ciacola.decks

import dev.bnorm.storyboard.easel.resolveDefaultStoryboardDir
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.forExternalFile
import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.review.ReviewQueueFile
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

/** Accepts `30m`, `45s`, `2h`, `100ms` or bare milliseconds, as `cav review wait --timeout` does. */
internal fun parseReviewTimeout(raw: String): Long {
    require(raw.isNotEmpty()) { "Empty --timeout value" }
    // "ms" first: tested on the last character alone, 100ms leaves "100m", which parses as
    // nothing at all even though the usage text and the docs both offer it.
    val (numberPart, multiplier) = when {
        raw.endsWith("ms", ignoreCase = true) -> raw.dropLast(2) to 1L
        raw.last() in "sS" -> raw.dropLast(1) to 1_000L
        raw.last() in "mM" -> raw.dropLast(1) to 60_000L
        raw.last() in "hH" -> raw.dropLast(1) to 3_600_000L
        else -> raw to 1L
    }
    val value = numberPart.trim().toLongOrNull()
        ?: error("Invalid --timeout value '$raw' (expected e.g. 30m, 45s, 2h, 100ms or milliseconds)")
    require(value > 0) { "--timeout must be positive, got '$raw'" }
    // A deadline is now + this, so an overflowed product lands in the past and the wait returns
    // at once — the opposite of what an absurdly large timeout asked for.
    require(value <= Long.MAX_VALUE / multiplier) { "--timeout is too large: '$raw'" }
    return value * multiplier
}

/**
 * The deck id behind a `--deck` argument, which the usage text allows to be an id or a path.
 *
 * `storyboardDirForDeck` sanitizes whatever it is given into a single directory name, so handing
 * it `/Talks/as-ide-talk.deck.json` yields a directory no running deck ever writes to: the agent
 * then waits on an empty queue for ever and nobody is told why. The app names that deck's
 * directory after the file's stem, so the stem is what the agent must ask for too.
 */
internal fun reviewDeckIdFor(deckArg: String): String {
    val trimmed = deckArg.trim()
    val looksLikeAPath = trimmed.endsWith(".deck.json") || trimmed.contains('/') || trimmed.contains('\\')
    if (!looksLikeAPath) return trimmed
    val file = Path.of(trimmed).toAbsolutePath().normalize()
    // The id the app writes its queue under is the one declared inside the file, which need not
    // match the file's name: DeckEntry.forExternalFile reads conference-keynote.deck.json as
    // "conf-2026". The stem is only the fallback for a file we cannot read.
    val fromFile = runCatching { DeckEntry.forExternalFile(file).id }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
    return fromFile
        ?: file.fileName?.toString()?.removeSuffix(".deck.json")?.takeIf { it.isNotEmpty() }
        ?: error("Cannot work out a deck id from --deck='$deckArg'")
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
    return storyboardDirForDeck(root, reviewDeckIdFor(deckId))
}

/**
 * Turns an unreadable queue into the dedicated exit code and one sentence naming the file.
 *
 * Lives here rather than in `DeckAgentCli` only because that file is within a few lines of the
 * repository's god-file limit.
 */
private fun guardQueue(block: () -> Int): Int =
    try {
        block()
    } catch (e: ReviewQueueFile.Unreadable) {
        System.err.println(e.message)
        DeckReviewCli.EXIT_UNREADABLE
    }

internal fun runReviewWaitMode(storyboardDirArg: String?, deckId: String, timeoutMillis: Long): Int {
    val dir = reviewDirFor(storyboardDirArg, deckId)
    return guardQueue {
        DeckReviewCli.runWait(
            commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME),
            takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME),
            timeoutMillis = timeoutMillis,
        )
    }
}

internal fun runReviewResolveMode(
    storyboardDirArg: String?,
    deckId: String,
    commentId: String,
    resolution: String,
    status: ReviewCommentStatus,
): Int {
    val dir = reviewDirFor(storyboardDirArg, deckId)
    return guardQueue {
        DeckReviewCli.runResolve(
            commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME),
            commentId = commentId,
            resolution = resolution,
            status = status,
            takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME),
        )
    }
}

internal fun runReviewExportMode(
    storyboardDirArg: String?,
    deckId: String,
    includeTaken: Boolean,
    jsonOutput: Boolean,
): Int {
    val dir = reviewDirFor(storyboardDirArg, deckId)
    return guardQueue {
        DeckReviewCli.runExport(
            commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME),
            takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME),
            includeTaken = includeTaken,
            jsonOutput = jsonOutput,
        )
    }
}
