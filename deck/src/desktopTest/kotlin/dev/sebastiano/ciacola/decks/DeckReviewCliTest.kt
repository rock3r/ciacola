package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.review.ReviewTargetHit
import dev.sebastiano.ciacola.review.SlideReviewComment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DeckReviewCliTest {

    private val json = Json { encodeDefaults = true }

    private fun comment(
        id: String,
        note: String = "note for $id",
        status: ReviewCommentStatus = ReviewCommentStatus.Queued,
        slideId: String? = "title",
        nodeId: String? = "title:role",
        snippet: String = "Senior Android Engineer",
    ) = SlideReviewComment(
        id = id,
        sceneIndex = 0,
        frameIndex = 0,
        sceneCount = 17,
        frameCount = 1,
        sceneTitle = "Title",
        stageX = 0f,
        stageY = 0f,
        target = ReviewTargetHit(kind = "Text", label = snippet, textSnippet = snippet),
        note = note,
        status = status,
        createdAt = "2026-11-24T10:00:00Z",
        slideId = slideId,
        nodeId = nodeId,
    )

    private fun writeComments(dir: Path, vararg comments: SlideReviewComment): Path {
        Files.createDirectories(dir)
        val file = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME)
        Files.writeString(file, json.encodeToString(comments.toList()))
        return file
    }

    @Test
    fun onlyQueuedCommentsThatHaveNotBeenTakenCountAsPending() {
        val all = listOf(
            comment("a"),
            comment("b", status = ReviewCommentStatus.Applying),
            comment("c", status = ReviewCommentStatus.Applied),
            comment("d", status = ReviewCommentStatus.Failed),
            comment("e"),
        )

        assertEquals(listOf("a", "e"), DeckReviewCli.pendingComments(all, emptySet()).map { it.id })
        assertEquals(listOf("e"), DeckReviewCli.pendingComments(all, setOf("a")).map { it.id })
        assertTrue(DeckReviewCli.pendingComments(all, setOf("a", "e")).isEmpty())
    }

    @Test
    fun aMissingOrUnreadableQueueReadsAsEmptyRatherThanThrowing() {
        val dir = Files.createTempDirectory("review-cli")
        val missing = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME)
        assertTrue(DeckReviewCli.readComments(missing).isEmpty())

        Files.writeString(missing, "")
        assertTrue(DeckReviewCli.readComments(missing).isEmpty())

        // The deck rewrites this file wholesale, so a read can land mid-write.
        Files.writeString(missing, "[{\"id\": \"half")
        assertTrue(DeckReviewCli.readComments(missing).isEmpty())
    }

    @Test
    fun waitReturnsAsSoonAsACommentIsQueuedAndRecordsItTaken() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir)
        val takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME)
        val printed = mutableListOf<String>()

        var now = 0L
        var ticks = 0
        val exit = DeckReviewCli.runWait(
            commentsFile = commentsFile,
            takenFile = takenFile,
            timeoutMillis = 10_000L,
            pollMillis = 1_000L,
            out = printed::add,
            nowMillis = { now },
            sleep = { elapsed ->
                now += elapsed
                ticks++
                // The author queues a comment on the third poll.
                if (ticks == 3) writeComments(dir, comment("rc-1", note = "make this bigger"))
            },
        )

        assertEquals(DeckReviewCli.EXIT_OK, exit)
        assertEquals(1, printed.size)
        assertTrue(printed.single().contains("rc-1"), printed.single())
        assertTrue(printed.single().contains("make this bigger"), printed.single())
        assertEquals(setOf("rc-1"), DeckReviewCli.readTaken(takenFile))
    }

    @Test
    fun waitExitsThreeOnTimeoutAndTakesNothing() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir, comment("done", status = ReviewCommentStatus.Applied))
        val takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME)
        val printed = mutableListOf<String>()

        var now = 0L
        val exit = DeckReviewCli.runWait(
            commentsFile = commentsFile,
            takenFile = takenFile,
            timeoutMillis = 5_000L,
            pollMillis = 1_000L,
            out = printed::add,
            nowMillis = { now },
            sleep = { now += it },
        )

        assertEquals(DeckReviewCli.EXIT_TIMEOUT, exit)
        assertTrue(printed.isEmpty())
        assertTrue(DeckReviewCli.readTaken(takenFile).isEmpty())
        assertFalse(Files.exists(takenFile))
    }

    @Test
    fun aSecondWaitDoesNotReplayWhatTheFirstOneAlreadyTook() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir, comment("rc-1"))
        val takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME)

        val first = mutableListOf<String>()
        var now = 0L
        assertEquals(
            DeckReviewCli.EXIT_OK,
            DeckReviewCli.runWait(
                commentsFile, takenFile, 5_000L, 1_000L, first::add, { now }, { now += it },
            ),
        )
        assertTrue(first.single().contains("rc-1"))

        // Same queue, nothing new: the second call must block and then time out.
        val second = mutableListOf<String>()
        now = 0L
        assertEquals(
            DeckReviewCli.EXIT_TIMEOUT,
            DeckReviewCli.runWait(
                commentsFile, takenFile, 5_000L, 1_000L, second::add, { now }, { now += it },
            ),
        )
        assertTrue(second.isEmpty())

        // A genuinely new comment still comes through.
        writeComments(dir, comment("rc-1"), comment("rc-2"))
        val third = mutableListOf<String>()
        now = 0L
        assertEquals(
            DeckReviewCli.EXIT_OK,
            DeckReviewCli.runWait(
                commentsFile, takenFile, 5_000L, 1_000L, third::add, { now }, { now += it },
            ),
        )
        assertTrue(third.single().contains("rc-2"))
        assertFalse(third.single().contains("rc-1"))
        assertEquals(setOf("rc-1", "rc-2"), DeckReviewCli.readTaken(takenFile))
    }

    @Test
    fun exportPrintsPendingCommentsWithoutTakingThem() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir, comment("rc-1"), comment("rc-2"))
        val takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME)
        val printed = mutableListOf<String>()

        val exit = DeckReviewCli.runExport(
            commentsFile = commentsFile,
            takenFile = takenFile,
            includeTaken = false,
            jsonOutput = false,
            out = printed::add,
        )

        assertEquals(DeckReviewCli.EXIT_OK, exit)
        assertTrue(printed.single().contains("rc-1"))
        assertTrue(printed.single().contains("rc-2"))
        // Export is a read: a later wait must still deliver them.
        assertTrue(DeckReviewCli.readTaken(takenFile).isEmpty())
    }

    @Test
    fun exportCanIncludeAlreadyTakenCommentsAndEmitJson() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir, comment("rc-1"), comment("rc-2"))
        val takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME)
        DeckReviewCli.writeTaken(takenFile, setOf("rc-1"))

        val pending = mutableListOf<String>()
        DeckReviewCli.runExport(commentsFile, takenFile, includeTaken = false, jsonOutput = false, out = pending::add)
        assertFalse(pending.single().contains("rc-1"))
        assertTrue(pending.single().contains("rc-2"))

        val everything = mutableListOf<String>()
        DeckReviewCli.runExport(commentsFile, takenFile, includeTaken = true, jsonOutput = true, out = everything::add)
        assertTrue(everything.single().contains("rc-1"))
        assertTrue(everything.single().contains("rc-2"))
        // --json must be parseable, not just printable.
        val parsed = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<SlideReviewComment>>(everything.single())
        assertEquals(listOf("rc-1", "rc-2"), parsed.map { it.id })
    }

    @Test
    fun aFormattedCommentCarriesEnoughToActOn() {
        val line = DeckReviewCli.formatComment(comment("rc-9", note = "wrong job title"))
        assertTrue(line.contains("rc-9"), line)
        assertTrue(line.contains("slide=title"), line)
        assertTrue(line.contains("node=title:role"), line)
        assertTrue(line.contains("Senior Android Engineer"), line)
        assertTrue(line.contains("wrong job title"), line)
    }
}
