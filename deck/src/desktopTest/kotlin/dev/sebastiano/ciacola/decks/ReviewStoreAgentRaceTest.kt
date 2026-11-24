package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.review.ReviewClaims
import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.ReviewConfig
import dev.sebastiano.ciacola.review.ReviewQueueFile
import dev.sebastiano.ciacola.review.ReviewTargetHit
import dev.sebastiano.ciacola.review.SlideReviewComment
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The two real writers of `review-comments.json`, run against each other.
 *
 * The earlier contention test drove a synthetic writer, which only ever exercised the lock. These
 * are the actual pairing: the deck's store queueing a comment while an agent's `--review-resolve`
 * commits a verdict. Between them they cover both orders, which is where comments used to vanish.
 */
class ReviewStoreAgentRaceTest {

    private fun comment(id: String) = SlideReviewComment(
        id = id,
        sceneIndex = 0,
        frameIndex = 0,
        sceneCount = 1,
        frameCount = 1,
        sceneTitle = "Slide",
        sceneHeader = "",
        themePreset = "KeynoteLight",
        stageX = 0f,
        stageY = 0f,
        target = ReviewTargetHit(kind = "text", label = "Title"),
        note = "note $id",
        createdAt = "2026-11-24T00:00:00Z",
    )

    @Test
    fun queueingWhileAnAgentResolvesLosesNeitherTheCommentNorTheVerdict() {
        repeat(15) { run ->
            val dir = Files.createTempDirectory("review-store-race")
            val store = ReviewCommentStore(
                storyboardDir = dir,
                workspaceRoot = dir,
                envProvider = { null },
                systemPropertyProvider = { null },
            )
            runBlocking { store.submitComment(comment("rc-existing"), dispatchImmediate = false) }

            val bothRunning = CyclicBarrier(2)
            val agent = Thread {
                bothRunning.await()
                DeckReviewCli.runResolve(
                    commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME),
                    commentId = "rc-existing",
                    resolution = "done",
                    out = {},
                )
            }
            agent.start()
            bothRunning.await()
            runBlocking { store.submitComment(comment("rc-new-$run"), dispatchImmediate = false) }
            agent.join()

            val onDisk = DeckReviewCli.readComments(dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME))
            assertEquals(
                setOf("rc-existing", "rc-new-$run"),
                onDisk.map { it.id }.toSet(),
                "lost a comment on run $run",
            )
            assertEquals(
                ReviewCommentStatus.Applied,
                onDisk.single { it.id == "rc-existing" }.status,
                "lost the agent's verdict on run $run",
            )
        }
    }

    @Test
    fun bulkSendSkipsWhatAnAgentAlreadyHolds() {
        val dir = Files.createTempDirectory("review-store-claims")
        val fakeAgent = dir.resolve("fake-agentapi.sh").apply {
            Files.writeString(this, "#!/bin/sh\nexit 0\n")
            toFile().setExecutable(true)
        }
        val store = ReviewCommentStore(
            storyboardDir = dir,
            workspaceRoot = dir,
            envProvider = { key ->
                if (key == "CIACOLA_AGENTAPI_BIN") fakeAgent.toAbsolutePath().toString() else null
            },
            systemPropertyProvider = { null },
        )
        store.saveConfig(ReviewConfig(conversationId = "conv-1"))
        runBlocking {
            store.submitComment(comment("rc-held"), dispatchImmediate = false)
            store.submitComment(comment("rc-free"), dispatchImmediate = false)
        }

        // An agent took rc-held off the queue; its status is still Queued, which is exactly why
        // selecting on status alone used to hand it to a second agent.
        ReviewClaims.write(dir.resolve(ReviewClaims.FILE_NAME), listOf(dev.sebastiano.ciacola.review.ReviewClaim("rc-held")))
        store.reloadClaims()

        val sent = runBlocking { store.flushQueued() }

        assertEquals(1, sent, "Send Queued must skip the claimed comment")
        val onDisk = DeckReviewCli.readComments(dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME))
        assertEquals(ReviewCommentStatus.Queued, onDisk.single { it.id == "rc-held" }.status)
        assertTrue(onDisk.single { it.id == "rc-free" }.status != ReviewCommentStatus.Queued)
    }

    @Test
    fun retrySkipsAClaimedCommentAndSaysSo() {
        val dir = Files.createTempDirectory("review-store-retry")
        val store = ReviewCommentStore(
            storyboardDir = dir,
            workspaceRoot = dir,
            envProvider = { null },
            systemPropertyProvider = { null },
        )
        runBlocking { store.submitComment(comment("rc-held"), dispatchImmediate = false) }
        ReviewClaims.write(dir.resolve(ReviewClaims.FILE_NAME), listOf(dev.sebastiano.ciacola.review.ReviewClaim("rc-held")))
        store.reloadClaims()

        runBlocking { store.retryComment("rc-held") }

        assertEquals(ReviewCommentStatus.Queued, store.comments.single().status)
        assertTrue(
            store.activityNotice?.title.orEmpty().contains("already with an agent"),
            "expected a notice, got ${store.activityNotice?.title}",
        )
    }

    @Test
    fun releasingAClaimPutsTheCommentBackInTheQueue() {
        val dir = Files.createTempDirectory("review-store-release")
        val store = ReviewCommentStore(
            storyboardDir = dir,
            workspaceRoot = dir,
            envProvider = { null },
            systemPropertyProvider = { null },
        )
        runBlocking { store.submitComment(comment("rc-held"), dispatchImmediate = false) }
        val claimsFile = dir.resolve(ReviewClaims.FILE_NAME)
        ReviewClaims.write(claimsFile, listOf(dev.sebastiano.ciacola.review.ReviewClaim("rc-held")))
        store.reloadClaims()
        assertTrue("rc-held" in store.claimedIds)

        // The author's escape hatch when the agent holding a comment is gone.
        runBlocking { store.releaseClaim("rc-held") }

        assertTrue(store.claimedIds.isEmpty())
        assertTrue(ReviewClaims.readIds(claimsFile) {}.isEmpty())
    }

    @Test
    fun resolvingAcommentGivesItsClaimBackSoTheHudStopsCountingIt() {
        val dir = Files.createTempDirectory("review-store-resolve-claim")
        val store = ReviewCommentStore(
            storyboardDir = dir,
            workspaceRoot = dir,
            envProvider = { null },
            systemPropertyProvider = { null },
        )
        runBlocking { store.submitComment(comment("rc-done"), dispatchImmediate = false) }
        val claimsFile = dir.resolve(ReviewClaims.FILE_NAME)
        val commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME)
        DeckReviewCli.claim(claimsFile, listOf("rc-done"))

        DeckReviewCli.runResolve(
            commentsFile = commentsFile,
            commentId = "rc-done",
            resolution = "done",
            takenFile = claimsFile,
            out = {},
        )

        // A claim held past the verdict leaves the deck counting a finished comment as being with
        // an agent, with no way back short of deleting it.
        assertTrue(ReviewClaims.readIds(claimsFile) {}.isEmpty(), "Applied must release the claim")

        // Failed is the one verdict that keeps it: handing that straight back would deliver the
        // same comment to the same agent on every later wait.
        DeckReviewCli.claim(claimsFile, listOf("rc-done"))
        DeckReviewCli.runResolve(
            commentsFile = commentsFile,
            commentId = "rc-done",
            resolution = "could not",
            status = ReviewCommentStatus.Failed,
            takenFile = claimsFile,
            out = {},
        )
        assertEquals(setOf("rc-done"), ReviewClaims.readIds(claimsFile) {})
    }

    @Test
    fun clearingAppliedCommentsDropsTheirClaims() {
        val dir = Files.createTempDirectory("review-store-clear-claims")
        val store = ReviewCommentStore(
            storyboardDir = dir,
            workspaceRoot = dir,
            envProvider = { null },
            systemPropertyProvider = { null },
        )
        runBlocking { store.submitComment(comment("rc-gone"), dispatchImmediate = false) }
        val claimsFile = dir.resolve(ReviewClaims.FILE_NAME)
        DeckReviewCli.claim(claimsFile, listOf("rc-gone"))
        store.reloadClaims()

        store.markCommentApplied("rc-gone")
        store.clearAppliedComments()

        assertTrue(ReviewClaims.readIds(claimsFile) {}.isEmpty())
        assertTrue(store.comments.isEmpty())
    }

    @Test
    fun aVerdictWrittenDuringAPushIsNotOverwrittenByTheDeliveryFailure() {
        val dir = Files.createTempDirectory("review-store-late-verdict")
        val commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME)
        // Stands in for an agent that resolves the comment and only then fails to answer the
        // push: the deck's in-memory copy still says applying while disk already says applied.
        val fakeAgent = dir.resolve("late-verdict-agentapi.sh").apply {
            Files.writeString(
                this,
                "#!/bin/sh\n" +
                    "sed 's/\"applying\"/\"applied\"/' '" + commentsFile.toAbsolutePath() + "' > '" +
                    commentsFile.toAbsolutePath() + ".tmp'\n" +
                    "mv '" + commentsFile.toAbsolutePath() + ".tmp' '" + commentsFile.toAbsolutePath() + "'\n" +
                    "exit 3\n",
            )
            toFile().setExecutable(true)
        }
        val store = ReviewCommentStore(
            storyboardDir = dir,
            workspaceRoot = dir,
            envProvider = { key ->
                if (key == "CIACOLA_AGENTAPI_BIN") fakeAgent.toAbsolutePath().toString() else null
            },
            systemPropertyProvider = { null },
        )
        store.saveConfig(ReviewConfig(conversationId = "conv-1"))

        runBlocking { store.submitComment(comment("rc-late"), dispatchImmediate = true) }

        val onDisk = DeckReviewCli.readComments(commentsFile).single()
        assertEquals(
            ReviewCommentStatus.Applied,
            onDisk.status,
            "the agent's verdict was replaced by the push's own failure",
        )
    }

    @Test
    fun aQueueHeldByAnotherProcessKeepsTheNoteInsteadOfLosingIt() {
        val dir = Files.createTempDirectory("review-store-busy")
        val store = ReviewCommentStore(
            storyboardDir = dir,
            workspaceRoot = dir,
            envProvider = { null },
            systemPropertyProvider = { null },
        )

        val locked = CyclicBarrier(2)
        val release = CyclicBarrier(2)
        val holder = Thread {
            ReviewQueueFile.withLock(dir) {
                locked.await()
                release.await()
            }
        }
        holder.start()
        locked.await()

        // The lock wait is bounded, so this returns rather than freezing: a note that could not be
        // written must come back as null, so the composer keeps the author's words.
        val saved = runBlocking { store.submitComment(comment("rc-busy"), dispatchImmediate = false) }
        release.await()
        holder.join()

        assertTrue(saved == null, "a busy queue must report failure, not a saved comment")
        assertTrue(
            store.activityNotice?.title.orEmpty().isNotEmpty(),
            "the author has to be told the queue was busy",
        )
    }

    @Test
    fun sendQueuedRightAfterAVerdictDoesNotPushTheFinishedCommentAgain() {
        val dir = Files.createTempDirectory("review-store-stale-send")
        val commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME)
        val pushLog = dir.resolve("pushed.txt")
        val fakeAgent = dir.resolve("logging-agentapi.sh").apply {
            Files.writeString(this, "#!/bin/sh\necho pushed >> '" + pushLog.toAbsolutePath() + "'\nexit 0\n")
            toFile().setExecutable(true)
        }
        val store = ReviewCommentStore(
            storyboardDir = dir,
            workspaceRoot = dir,
            envProvider = { key ->
                if (key == "CIACOLA_AGENTAPI_BIN") fakeAgent.toAbsolutePath().toString() else null
            },
            systemPropertyProvider = { null },
        )
        store.saveConfig(ReviewConfig(conversationId = "conv-1"))
        runBlocking { store.submitComment(comment("rc-just-done"), dispatchImmediate = false) }

        // The agent resolves it, which also hands the claim back. The deck has not polled yet, so
        // its in-memory copy still says queued and unclaimed — the window this used to lose work
        // in, because the push would commit that stale status over the verdict.
        DeckReviewCli.runResolve(
            commentsFile = commentsFile,
            commentId = "rc-just-done",
            resolution = "fixed the title",
            takenFile = dir.resolve(ReviewClaims.FILE_NAME),
            out = {},
        )

        val sent = runBlocking { store.flushQueued() }

        assertEquals(0, sent, "a resolved comment must not be pushed again")
        assertTrue(Files.notExists(pushLog), "nothing should have been dispatched")
        val onDisk = DeckReviewCli.readComments(commentsFile).single()
        assertEquals(ReviewCommentStatus.Applied, onDisk.status)
        assertEquals("fixed the title", onDisk.resolution)
    }

    @Test
    fun markingAppliedByHandLeavesAnAgentsVerdictAlone() {
        val dir = Files.createTempDirectory("review-store-stale-mark")
        val commentsFile = dir.resolve(DeckReviewCli.COMMENTS_FILE_NAME)
        val store = ReviewCommentStore(
            storyboardDir = dir,
            workspaceRoot = dir,
            envProvider = { null },
            systemPropertyProvider = { null },
        )
        runBlocking { store.submitComment(comment("rc-raced"), dispatchImmediate = false) }

        DeckReviewCli.runResolve(
            commentsFile = commentsFile,
            commentId = "rc-raced",
            resolution = "rotated the stamp",
            out = {},
        )

        store.markCommentApplied("rc-raced")

        assertEquals(
            "rotated the stamp",
            DeckReviewCli.readComments(commentsFile).single().resolution,
            "the placeholder replaced what the agent had written",
        )
    }
}
