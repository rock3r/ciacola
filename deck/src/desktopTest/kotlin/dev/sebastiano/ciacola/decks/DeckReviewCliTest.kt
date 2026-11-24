package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.review.ReviewTargetHit
import dev.sebastiano.ciacola.review.SlideReviewComment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun queuedAndFailedCommentsAreStillWaitingOnAnAgentButAppliedAndApplyingAreNot() {
        val all = listOf(
            comment("a"),
            comment("b", status = ReviewCommentStatus.Applying),
            comment("c", status = ReviewCommentStatus.Applied),
            comment("d", status = ReviewCommentStatus.Failed),
            comment("e"),
        )

        // 'd' failed to deliver: the author still wants it done, and the UI still shows it open.
        assertEquals(listOf("a", "d", "e"), DeckReviewCli.pendingComments(all, emptySet()).map { it.id })
        assertEquals(listOf("d", "e"), DeckReviewCli.pendingComments(all, setOf("a")).map { it.id })
        assertTrue(DeckReviewCli.pendingComments(all, setOf("a", "d", "e")).isEmpty())
    }

    @Test
    fun aCommentWhoseLivePushFailedIsHandedOverOnceNotOnEveryCall() {
        val dir = Files.createTempDirectory("review-cli")
        // Exactly the shape Send leaves behind with no backend installed.
        val commentsFile = writeComments(
            dir,
            comment("rc-failed", note = "delete this", status = ReviewCommentStatus.Failed),
        )
        val takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME)

        val first = mutableListOf<String>()
        var now = 0L
        assertEquals(
            DeckReviewCli.EXIT_OK,
            DeckReviewCli.runWait(commentsFile, takenFile, 5_000L, 1_000L, first::add, { now }, { now += it }),
        )
        assertTrue(first.single().contains("delete this"))

        val second = mutableListOf<String>()
        now = 0L
        assertEquals(
            DeckReviewCli.EXIT_TIMEOUT,
            DeckReviewCli.runWait(commentsFile, takenFile, 5_000L, 1_000L, second::add, { now }, { now += it }),
        )
        assertTrue(second.isEmpty())
    }

    @Test
    fun aQueueThatDoesNotExistYetIsSimplyEmpty() {
        val dir = Files.createTempDirectory("review-cli")
        val missing = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME)
        // An agent may start waiting before the author has written anything.
        assertTrue(DeckReviewCli.readComments(missing).isEmpty())

        Files.writeString(missing, "")
        assertTrue(DeckReviewCli.readComments(missing).isEmpty())
    }

    @Test
    fun aCorruptQueueIsReportedRatherThanPassedOffAsEmpty() {
        val dir = Files.createTempDirectory("review-cli")
        val file = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME)
        Files.writeString(file, "[{\"id\": \"half")

        // Reading it as "no comments" turns a corrupt file into a wait that never returns, an
        // export that prints [], and a resolve that blames the agent for an id the file holds.
        val failure = assertFailsWith<DeckReviewQueueFile.Unreadable> { DeckReviewCli.readComments(file) }
        assertTrue(failure.message.orEmpty().contains(file.toAbsolutePath().toString()), failure.message)
    }

    @Test
    fun requeueingAlsoHandsBackTheClaimSoTheCommentIsNotStrandedForEver() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir, comment("rc-1"))
        val takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME)
        var now = 0L

        assertEquals(
            DeckReviewCli.EXIT_OK,
            DeckReviewCli.runWait(commentsFile, takenFile, 1_000L, 10L, {}, { now }, { now += it }),
        )
        assertEquals(setOf("rc-1"), DeckReviewCli.readTaken(takenFile))

        // An agent that cannot act on a note puts it back. Keeping the claim would leave the
        // comment waiting in the UI while every future wait skips it as already taken.
        DeckReviewCli.runResolve(
            commentsFile = commentsFile,
            commentId = "rc-1",
            resolution = "needs a decision from the author",
            status = ReviewCommentStatus.Queued,
            takenFile = takenFile,
            out = {},
        )
        assertTrue(DeckReviewCli.readTaken(takenFile).isEmpty())

        val second = mutableListOf<String>()
        assertEquals(
            DeckReviewCli.EXIT_OK,
            DeckReviewCli.runWait(commentsFile, takenFile, 1_000L, 10L, second::add, { now }, { now += it }),
        )
        assertTrue(second.single().contains("rc-1"))
    }

    @Test
    fun resolvingDoesNotEraseACommentQueuedWhileItWasWorking() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir, comment("rc-1"))

        // The author queues rc-2 while the agent is resolving rc-1. Both are whole-file writers,
        // so without the shared lock the agent's stale copy replaces rc-2 and nothing says so.
        val queuedMeanwhile = Thread {
            DeckReviewQueueFile.withLock(dir) {
                val now = DeckReviewCli.readComments(commentsFile)
                DeckReviewQueueFile.writeAtomically(
                    commentsFile,
                    DeckReviewCli.encodeComments(now + comment("rc-2")),
                )
            }
        }
        queuedMeanwhile.start()
        queuedMeanwhile.join()

        DeckReviewCli.runResolve(commentsFile, "rc-1", "done", out = {})

        val after = DeckReviewCli.readComments(commentsFile)
        assertEquals(listOf("rc-1", "rc-2"), after.map { it.id })
        assertEquals(ReviewCommentStatus.Applied, after.single { it.id == "rc-1" }.status)
    }

    @Test
    fun aNoteWrittenAcrossLinesStaysOneComment() {
        val formatted = DeckReviewCli.formatComment(
            comment("rc-1").copy(note = "remove the bar\nand tighten the frame"),
        )
        assertEquals(1, formatted.lines().size, formatted)
        assertTrue(formatted.contains("remove the bar and tighten the frame"), formatted)
    }

    @Test
    fun waitingDoesNotSleepPastItsOwnDeadline() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir)
        val takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME)
        var now = 0L
        val slept = mutableListOf<Long>()

        DeckReviewCli.runWait(commentsFile, takenFile, 100L, 1_000L, {}, { now }, { slept.add(it); now += it })

        // --timeout=100ms must not block for a whole poll interval.
        assertTrue(slept.all { it <= 100L }, "slept for $slept")
        assertEquals(100L, slept.sum())
    }

    @Test
    fun aDeckPathPicksTheSameQueueTheAppWritesTo() {
        // The usage text allows --deck=<id|path>; storyboardDirForDeck sanitizes whatever it gets
        // into one directory name, so a path would otherwise select a queue nothing writes to.
        assertEquals("as-ide-talk", reviewDeckIdFor("/Talks/as-ide-talk/as-ide-talk.deck.json"))
        assertEquals("as-ide-talk", reviewDeckIdFor("as-ide-talk.deck.json"))
        assertEquals("as-ide-talk", reviewDeckIdFor("as-ide-talk"))
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
    fun resolvingWritesTheVerdictBackAndClearsAStaleDeliveryError() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(
            dir,
            comment("rc-1", status = ReviewCommentStatus.Failed),
            comment("rc-2"),
        )
        val printed = mutableListOf<String>()

        val exit = DeckReviewCli.runResolve(
            commentsFile = commentsFile,
            commentId = "rc-1",
            resolution = "removed the accent bar",
            out = printed::add,
        )

        assertEquals(DeckReviewCli.EXIT_OK, exit)
        val after = DeckReviewCli.readComments(commentsFile)
        val resolved = after.single { it.id == "rc-1" }
        assertEquals(ReviewCommentStatus.Applied, resolved.status)
        assertEquals("removed the accent bar", resolved.resolution)
        // The old failure is not the comment's state any more.
        assertEquals(null, resolved.deliveryError)
        // Everything else is left exactly as it was.
        assertEquals(ReviewCommentStatus.Queued, after.single { it.id == "rc-2" }.status)
        assertEquals(2, after.size)
        assertTrue(printed.single().contains("rc-1"), printed.single())
    }

    @Test
    fun resolvingAnUnknownIdReportsItInsteadOfRewritingTheQueue() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir, comment("rc-1"))
        val before = Files.readString(commentsFile)
        val printed = mutableListOf<String>()

        val exit = DeckReviewCli.runResolve(commentsFile, "rc-nope", "done", out = printed::add)

        assertEquals(DeckReviewCli.EXIT_NOT_FOUND, exit)
        assertEquals(before, Files.readString(commentsFile))
        assertTrue(printed.single().contains("rc-nope"), printed.single())
    }

    @Test
    fun resolvingCanAlsoRecordAFailureWithoutDiscardingItsError() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir, comment("rc-1"))

        DeckReviewCli.runResolve(
            commentsFile = commentsFile,
            commentId = "rc-1",
            resolution = "could not find the node",
            status = ReviewCommentStatus.Failed,
            out = {},
        )

        val resolved = DeckReviewCli.readComments(commentsFile).single()
        assertEquals(ReviewCommentStatus.Failed, resolved.status)
        assertEquals("could not find the node", resolved.resolution)
    }

    @Test
    fun aResolvedCommentIsNoLongerPendingForAnAgent() {
        val dir = Files.createTempDirectory("review-cli")
        val commentsFile = writeComments(dir, comment("rc-1"))
        val takenFile = dir.resolve(DeckReviewCli.TAKEN_FILE_NAME)

        DeckReviewCli.runResolve(commentsFile, "rc-1", "done", out = {})

        var now = 0L
        val printed = mutableListOf<String>()
        assertEquals(
            DeckReviewCli.EXIT_TIMEOUT,
            DeckReviewCli.runWait(commentsFile, takenFile, 3_000L, 1_000L, printed::add, { now }, { now += it }),
        )
        assertTrue(printed.isEmpty())
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
