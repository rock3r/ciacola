package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.review.ReviewClaims
import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.review.ReviewQueueFile
import dev.sebastiano.ciacola.review.SlideReviewComment
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Agent-facing half of the deck review loop.
 *
 * The deck pushes nothing: an agent runs `--review-wait` and blocks until the author queues a
 * comment, so the notes surface in whichever conversation made the call. That inversion is the
 * whole point — a session cannot be pushed into from outside, but it can wait.
 *
 * `--review-resolve` closes the loop from the other end. Writing `review-comments.json` while the
 * deck is running is safe: `ReviewWindow` polls `ReviewCommentStore.reloadFromDisk()` every 250 ms,
 * which reloads on an mtime or size change, so a resolved comment turns green in the open deck
 * without anyone clicking anything. Writes still go through a temporary file and an atomic move, so
 * that poll can never observe a half-written queue.
 *
 * What an agent has *taken* is a different question from what the author has *resolved*, and only
 * the first is the agent's own bookkeeping, so it lives in a sidecar the deck does not read.
 */
internal object DeckReviewCli {

    const val EXIT_OK = 0
    const val EXIT_TIMEOUT = 3
    const val EXIT_NOT_FOUND = 4
    const val EXIT_UNREADABLE = 5
    const val EXIT_IO = 6

    const val COMMENTS_FILE_NAME = "review-comments.json"
    const val TAKEN_FILE_NAME = ReviewClaims.FILE_NAME

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Reads the deck's queued comments.
     *
     * No file at all is an empty queue: an agent may legitimately start waiting before the author
     * has written anything. A file that exists and will not parse is an error, not an empty queue
     * — collapsing the two turns a corrupt file into a wait that never returns, an export that
     * prints `[]`, and a resolve that blames the agent for an id the file still contains.
     */
    fun readComments(commentsFile: Path): List<SlideReviewComment> {
        val text = ReviewQueueFile.readTextOrNull(commentsFile) ?: return emptyList()
        // A zero-byte file is a truncated write, not an empty queue: an empty queue is "[]".
        // Reading it as "no comments" is how a half-finished write becomes a silent data loss.
        if (text.isBlank()) throw ReviewQueueFile.Unreadable(commentsFile, null)
        return try {
            lenientJson.decodeFromString<List<SlideReviewComment>>(text)
        } catch (e: SerializationException) {
            throw ReviewQueueFile.Unreadable(commentsFile, e)
        }
    }

    /**
     * This agent's claims, in the format [ReviewClaims] defines.
     *
     * The deck reads the same file, so the format cannot live here: both halves dispatch, and a
     * claim only prevents double delivery if both of them understand it.
     */
    fun readTaken(takenFile: Path, warn: (String) -> Unit = System.err::println): Set<String> =
        ReviewClaims.read(takenFile, warn)

    fun writeTaken(takenFile: Path, ids: Set<String>) = ReviewClaims.write(takenFile, ids)

    /**
     * Comments the author is still waiting on that this agent has not taken yet, in file order.
     *
     * [ReviewCommentStatus.Failed] counts as pending: it means a live push could not be delivered,
     * not that the author changed their mind, and the note is still open in the UI with a Retry
     * Send button. Leaving those out strands a comment whenever Send is pressed without a backend
     * that can reach anyone — which is the normal case for an agent that only polls.
     *
     * [ReviewCommentStatus.Applying] is excluded because another agent already has it, and
     * [ReviewCommentStatus.Applied] is done. Taken IDs are recorded, so a failed comment is handed
     * over once rather than on every call.
     */
    fun pendingComments(
        comments: List<SlideReviewComment>,
        taken: Set<String>,
    ): List<SlideReviewComment> =
        comments.filter { isPendingStatus(it.status) && it.id !in taken }

    /**
     * Flattens a value onto one line.
     *
     * [formatComments] joins with newlines and the output is parsed a line at a time, so a note
     * written across two lines would otherwise arrive as two comments, the second of them
     * nonsense.
     */
    private fun singleLine(value: String): String =
        value.replace(Regex("\\R+"), " ").trim()

    /** One line per comment, enough for an agent to act without opening the file. */
    fun formatComment(comment: SlideReviewComment): String {
        val slide = comment.slideId?.takeIf { it.isNotBlank() }
            ?: comment.sceneTitle.takeIf { it.isNotBlank() }
            ?: "?"
        val node = comment.nodeId?.takeIf { it.isNotBlank() }
        val target = comment.target.textSnippet.takeIf { it.isNotBlank() }
        return buildString {
            append(comment.id)
            append(" | slide=").append(slide)
            if (node != null) append(" node=").append(node)
            if (target != null) append(" | target=").append(singleLine(target.take(80)))
            append(" | note=").append(singleLine(comment.note))
            comment.screenshotPath?.takeIf { it.isNotBlank() }?.let { append(" | capture=").append(it) }
        }
    }

    fun formatComments(comments: List<SlideReviewComment>): String =
        comments.joinToString("\n") { formatComment(it) }

    fun encodeComments(comments: List<SlideReviewComment>): String =
        prettyJson.encodeToString(comments)

    /**
     * Blocks until at least one untaken queued comment exists, prints them and records them taken.
     *
     * Returns [EXIT_TIMEOUT] when the deadline passes with nothing queued, so a caller can simply
     * run it again — the same contract `cav review wait` uses.
     *
     * [nowMillis] and [sleep] are injected so tests drive the clock instead of waiting.
     */
    fun runWait(
        commentsFile: Path,
        takenFile: Path,
        timeoutMillis: Long,
        pollMillis: Long = 1_000L,
        out: (String) -> Unit = ::println,
        // PrintStream swallows a broken pipe and only records it, so a batch can look delivered
        // when the consumer has gone. Asked separately, after the write.
        outputFailed: () -> Boolean = { System.out.checkError() },
        nowMillis: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Int {
        val dir = commentsFile.toAbsolutePath().parent
        val deadline = nowMillis() + timeoutMillis
        var first = true
        while (true) {
            // Reading the claims, choosing what is pending and recording the claim is one
            // transaction: two waiters that interleave here both hand the author's note to their
            // own conversation, and the author gets two agents editing the same slide.
            if (first || nowMillis() < deadline) {
                val claimed = ReviewQueueFile.withLock(dir) {
                    // Re-check inside the lock: acquiring it can itself take a while, and a
                    // waiter past its deadline must not take work it is about to abandon.
                    if (!first && nowMillis() >= deadline) {
                        null
                    } else {
                        val taken = readTaken(takenFile)
                        val pending = pendingComments(readComments(commentsFile), taken)
                        if (pending.isEmpty()) {
                            null
                        } else {
                            writeTaken(takenFile, taken + pending.map { it.id })
                            pending
                        }
                    }
                }
                if (claimed != null) {
                    // Printing happens outside the lock, deliberately. stdout is a pipe this
                    // process does not control; writing to one nobody is draining blocks, and the
                    // deck takes this same lock on its UI thread to queue or delete a comment. A
                    // slow consumer would freeze the author's running deck.
                    out(formatComments(claimed))
                    if (outputFailed()) {
                        // The consumer never got the batch, so the claim is a lie. Hand it back,
                        // or these comments are skipped by every later wait and nobody is told.
                        ReviewQueueFile.withLock(dir) {
                            writeTaken(takenFile, readTaken(takenFile) - claimed.map { it.id }.toSet())
                        }
                        return EXIT_IO
                    }
                    return EXIT_OK
                }
            }
            first = false
            val remaining = deadline - nowMillis()
            if (remaining <= 0) return EXIT_TIMEOUT
            // Never sleep past the deadline: --timeout=100ms must not block for a whole poll.
            sleep(minOf(pollMillis, remaining))
        }
    }

    /**
     * Writes a verdict back onto one comment, so the pin closes in the open deck.
     *
     * Returns [EXIT_NOT_FOUND] when no comment carries [commentId], rather than silently writing
     * the file back unchanged — an agent resolving an ID that does not exist has misread something
     * and should hear about it.
     */
    fun runResolve(
        commentsFile: Path,
        commentId: String,
        resolution: String,
        status: ReviewCommentStatus = ReviewCommentStatus.Applied,
        takenFile: Path? = null,
        out: (String) -> Unit = ::println,
    ): Int {
        val dir = commentsFile.toAbsolutePath().parent
        // Read and replace under the lock. Outside it, a comment the author queues between the
        // read and the write is erased by this stale copy, and nothing anywhere reports it.
        val found = ReviewQueueFile.withLock(dir) {
            val comments = readComments(commentsFile)
            if (comments.none { it.id == commentId }) {
                false
            } else {
                val updated = comments.map { comment ->
                    if (comment.id != commentId) {
                        comment
                    } else {
                        comment.copy(
                            status = status,
                            resolution = resolution,
                            // The old failure is no longer the comment's state; leaving it behind
                            // would keep showing the author a stale error next to a resolved pin.
                            deliveryError = if (status == ReviewCommentStatus.Applied) {
                                null
                            } else {
                                comment.deliveryError
                            },
                        )
                    }
                }
                // Hand the claim back before the status, not after. Killed between the two
                // writes, this order leaves a resolved comment unclaimed, so an agent sees it
                // again; the other order leaves it queued and claimed, which is stranded for ever.
                if (takenFile != null && isPendingStatus(status)) {
                    val taken = readTaken(takenFile)
                    if (commentId in taken) writeTaken(takenFile, taken - commentId)
                }
                ReviewQueueFile.writeAtomically(commentsFile, encodeComments(updated) + "\n")
                true
            }
        }
        if (!found) {
            out("No review comment with id '$commentId' in ${commentsFile.toAbsolutePath()}")
            return EXIT_NOT_FOUND
        }
        out("$commentId -> ${status.name.lowercase()}${if (resolution.isBlank()) "" else ": $resolution"}")
        return EXIT_OK
    }

    /** Statuses [pendingComments] hands to an agent, and so the ones a claim must not outlive. */
    fun isPendingStatus(status: ReviewCommentStatus): Boolean =
        status == ReviewCommentStatus.Queued || status == ReviewCommentStatus.Failed

    /** Prints the queued comments without blocking and without recording them taken. */
    fun runExport(
        commentsFile: Path,
        takenFile: Path,
        includeTaken: Boolean,
        jsonOutput: Boolean,
        out: (String) -> Unit = ::println,
    ): Int {
        val dir = commentsFile.toAbsolutePath().parent
        val selected = ReviewQueueFile.withLock(dir) {
            val comments = readComments(commentsFile)
            if (includeTaken) {
                comments.filter { isPendingStatus(it.status) }
            } else {
                pendingComments(comments, readTaken(takenFile))
            }
        }
        out(if (jsonOutput) encodeComments(selected) else formatComments(selected))
        return EXIT_OK
    }
}
