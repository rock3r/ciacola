package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.review.SlideReviewComment
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Agent-facing half of the deck review loop.
 *
 * The deck pushes nothing: an agent runs `--review-wait` and blocks until the author queues a
 * comment, so the notes surface in whichever conversation made the call. That inversion is the
 * whole point — a session cannot be pushed into from outside, but it can wait.
 *
 * Nothing here writes `review-comments.json`. The running deck reads that file once at startup and
 * rewrites it wholesale when it next saves, so an outside writer loses the race. Which comments an
 * agent has already taken is therefore kept in a sidecar the deck does not know about.
 */
internal object DeckReviewCli {

    const val EXIT_OK = 0
    const val EXIT_TIMEOUT = 3

    const val COMMENTS_FILE_NAME = "review-comments.json"
    const val TAKEN_FILE_NAME = "review-agent-taken.json"

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    internal data class TakenState(val takenIds: List<String> = emptyList())

    /** Reads the deck's queued comments, tolerating a missing file or a half-written one. */
    fun readComments(commentsFile: Path): List<SlideReviewComment> {
        if (!Files.isRegularFile(commentsFile)) return emptyList()
        val text = runCatching { Files.readString(commentsFile) }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return runCatching { lenientJson.decodeFromString<List<SlideReviewComment>>(text) }
            .getOrDefault(emptyList())
    }

    fun readTaken(takenFile: Path): Set<String> {
        if (!Files.isRegularFile(takenFile)) return emptySet()
        val text = runCatching { Files.readString(takenFile) }.getOrNull() ?: return emptySet()
        if (text.isBlank()) return emptySet()
        return runCatching { lenientJson.decodeFromString<TakenState>(text).takenIds.toSet() }
            .getOrDefault(emptySet())
    }

    fun writeTaken(takenFile: Path, ids: Set<String>) {
        takenFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(takenFile, prettyJson.encodeToString(TakenState(ids.toList().sorted())) + "\n")
    }

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
        comments.filter {
            (it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed) &&
                it.id !in taken
        }

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
            if (target != null) append(" | target=").append(target.take(80))
            append(" | note=").append(comment.note)
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
        nowMillis: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Int {
        val deadline = nowMillis() + timeoutMillis
        while (true) {
            val taken = readTaken(takenFile)
            val pending = pendingComments(readComments(commentsFile), taken)
            if (pending.isNotEmpty()) {
                out(formatComments(pending))
                writeTaken(takenFile, taken + pending.map { it.id })
                return EXIT_OK
            }
            if (nowMillis() >= deadline) return EXIT_TIMEOUT
            sleep(pollMillis)
        }
    }

    /** Prints the queued comments without blocking and without recording them taken. */
    fun runExport(
        commentsFile: Path,
        takenFile: Path,
        includeTaken: Boolean,
        jsonOutput: Boolean,
        out: (String) -> Unit = ::println,
    ): Int {
        val comments = readComments(commentsFile)
        val selected = if (includeTaken) {
            comments.filter {
                it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed
            }
        } else {
            pendingComments(comments, readTaken(takenFile))
        }
        out(if (jsonOutput) encodeComments(selected) else formatComments(selected))
        return EXIT_OK
    }
}
