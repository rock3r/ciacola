package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.review.ReviewClaims
import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.ReviewConfig
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
        store.releaseClaim("rc-held")

        assertTrue(store.claimedIds.isEmpty())
        assertTrue(ReviewClaims.readIds(claimsFile) {}.isEmpty())
    }
}
