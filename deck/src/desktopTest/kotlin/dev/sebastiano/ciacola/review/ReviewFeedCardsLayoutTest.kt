package dev.sebastiano.ciacola.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class ReviewFeedCardsLayoutTest {

    private fun createFakeAgentApi(dir: java.nio.file.Path, exitCode: Int = 0): java.nio.file.Path =
        dir.resolve("fake-agentapi-$exitCode.sh").apply {
            writeText("#!/bin/sh\nexit $exitCode\n")
            Files.setPosixFilePermissions(this, PosixFilePermissions.fromString("rwxr-xr-x"))
        }

    @Test
    fun actionButtonsIncludingResolveStayOnSingleRowAt460DpWidth() = runComposeUiTest {
        val tempDirQueued = Files.createTempDirectory("ciacola-review-card-queued-460-")
        val tempDirFailed = Files.createTempDirectory("ciacola-review-card-failed-460-")
        try {
            val fakeAgentSuccess = createFakeAgentApi(tempDirQueued, exitCode = 0)
            val fakeAgentFail = createFakeAgentApi(tempDirFailed, exitCode = 1)
            val queuedStore = ReviewCommentStore(
                storyboardDir = Files.createDirectories(tempDirQueued.resolve(".storyboard")),
                workspaceRoot = tempDirQueued,
                defaultDeckId = "test-deck",
                envProvider = { key ->
                    when (key) {
                        "CIACOLA_AGENTAPI_BIN" -> fakeAgentSuccess.toAbsolutePath().toString()
                        "CIACOLA_CONVERSATION_ID" -> "conv-460"
                        else -> null
                    }
                },
                systemPropertyProvider = { null },
            ).apply { saveConfig(config.copy(conversationId = "conv-460")) }
            val failedStore = ReviewCommentStore(
                storyboardDir = Files.createDirectories(tempDirFailed.resolve(".storyboard")),
                workspaceRoot = tempDirFailed,
                defaultDeckId = "test-deck",
                envProvider = { key ->
                    when (key) {
                        "CIACOLA_AGENTAPI_BIN" -> fakeAgentFail.toAbsolutePath().toString()
                        "CIACOLA_CONVERSATION_ID" -> "conv-460"
                        else -> null
                    }
                },
                systemPropertyProvider = { null },
            ).apply { saveConfig(config.copy(conversationId = "conv-460")) }

            val queued = SlideReviewComment(
                id = "rc-460-queued",
                sceneIndex = 0,
                frameIndex = 0,
                sceneCount = 1,
                frameCount = 1,
                sceneTitle = "Slide One",
                stageX = 120f,
                stageY = 140f,
                target = ReviewTargetHit(kind = "Text", label = "Headline", textSnippet = "Headline copy", bounds = null),
                note = "Check Send Now wrapping at 460dp review feed width",
                status = ReviewCommentStatus.Queued,
                createdAt = "2026-11-26T10:00:00Z",
            )
            val failed = SlideReviewComment(
                id = "rc-460-failed",
                sceneIndex = 0,
                frameIndex = 0,
                sceneCount = 1,
                frameCount = 1,
                sceneTitle = "Slide One",
                stageX = 320f,
                stageY = 240f,
                target = ReviewTargetHit(kind = "Shape", label = "Card", textSnippet = "", bounds = null),
                note = "Retry send button must also fit on the same row as Resolve",
                status = ReviewCommentStatus.Queued,
                createdAt = "2026-11-26T10:00:01Z",
            )
            runBlocking {
                queuedStore.submitComment(queued, dispatchImmediate = false)
                failedStore.submitComment(failed, dispatchImmediate = true)
            }
            assertEquals(ReviewCommentStatus.Queued, queuedStore.comments.first { it.id == queued.id }.status)
            assertEquals(
                ReviewCommentStatus.Failed,
                failedStore.comments.first { it.id == failed.id }.status,
                "Failed dispatch must persist ReviewCommentStatus.Failed so Retry Send renders",
            )

            setContent {
                val c1 = rememberReviewStageCoordinator()
                val c2 = rememberReviewStageCoordinator()
                val storyboard = Storyboard.build(title = "ReviewLayout") {
                    scene(frames = listOf(0)) { Box(Modifier) }
                }
                val animatic = rememberAnimatic(storyboard = { storyboard })
                val w1 = rememberSlideReviewWindow(animatic = animatic, store = queuedStore, stageCoordinator = c1).also {
                    it.visible = true
                }
                val w2 = rememberSlideReviewWindow(animatic = animatic, store = failedStore, stageCoordinator = c2).also {
                    it.visible = true
                }
                CiacolaJewelTheme(isDark = true) {
                    androidx.compose.foundation.layout.Column(Modifier.size(460.dp, 900.dp)) {
                        Box(Modifier.weight(1f)) { w1.Content() }
                        Box(Modifier.weight(1f)) { w2.Content() }
                    }
                }
            }
            waitForIdle()

            for ((item, expectedLabel) in listOf(queued to "Send Now", failed to "Retry Send")) {
                val actionsRowBounds = onNodeWithTag("ciacola-review-card-actions-${item.id}", useUnmergedTree = true)
                    .getBoundsInRoot()
                val copyBounds = onNodeWithTag("ciacola-review-copy-prompt-${item.id}", useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getBoundsInRoot()
                val appliedBounds = onNodeWithTag("ciacola-review-mark-applied-${item.id}", useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getBoundsInRoot()
                val dispatchBounds = onNodeWithTag("ciacola-review-retry-${item.id}")
                    .assertIsDisplayed()
                    .assertTextContains(expectedLabel, substring = true)
                    .getBoundsInRoot()
                val resolveBounds = onNodeWithTag("ciacola-review-resolve-${item.id}", useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getBoundsInRoot()

                for (b in listOf(copyBounds, appliedBounds, dispatchBounds, resolveBounds)) {
                    assertTrue(
                        abs(b.top.value - copyBounds.top.value) <= 1f,
                        "Expected single action row at 460dp for ${item.id} ($expectedLabel); got $b vs $copyBounds",
                    )
                    assertTrue(
                        b.left.value >= actionsRowBounds.left.value - 0.5f &&
                            b.right.value <= actionsRowBounds.right.value + 0.5f,
                        "Action button $b clipped outside $actionsRowBounds",
                    )
                }
            }

            // Clicking Resolve still opens inline discard confirmation cleanly.
            onNodeWithTag("ciacola-review-resolve-${queued.id}", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag("ciacola-review-card-discard-confirm-${queued.id}", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            tempDirQueued.toFile().deleteRecursively()
            tempDirFailed.toFile().deleteRecursively()
        }
    }

    @Test
    fun unconfiguredAgentSetAgentAndResolveFourButtonRowFitsAt460DpWidth() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-review-set-agent-460-")
        try {
            val storyboardDir = Files.createDirectories(tempDir.resolve(".storyboard"))
            val fakeAgentApi = createFakeAgentApi(tempDir)
            val store = ReviewCommentStore(
                storyboardDir = storyboardDir,
                workspaceRoot = tempDir,
                defaultDeckId = "test-deck",
                envProvider = { key ->
                    if (key == "CIACOLA_AGENTAPI_BIN") fakeAgentApi.toAbsolutePath().toString() else null
                },
                systemPropertyProvider = { null },
            )
            val queued = SlideReviewComment(
                id = "rc-460-unconfigured",
                sceneIndex = 0,
                frameIndex = 0,
                sceneCount = 1,
                frameCount = 1,
                sceneTitle = "Slide One",
                stageX = 120f,
                stageY = 140f,
                target = ReviewTargetHit(kind = "Text", label = "Headline", textSnippet = "Headline copy", bounds = null),
                note = "Set Agent button and Resolve must stay on one row at 460dp",
                status = ReviewCommentStatus.Queued,
                createdAt = "2026-11-26T10:00:00Z",
            )
            runBlocking { store.submitComment(queued, dispatchImmediate = false) }

            setContent {
                val coordinator = rememberReviewStageCoordinator()
                val storyboard = Storyboard.build(title = "ReviewLayout") {
                    scene(frames = listOf(0)) { Box(Modifier) }
                }
                val animatic = rememberAnimatic(storyboard = { storyboard })
                val window = rememberSlideReviewWindow(
                    animatic = animatic,
                    store = store,
                    stageCoordinator = coordinator,
                )
                window.visible = true
                CiacolaJewelTheme(isDark = true) {
                    Box(Modifier.size(460.dp, 640.dp)) {
                        window.Content()
                    }
                }
            }
            waitForIdle()

            val actionsRowBounds = onNodeWithTag("ciacola-review-card-actions-${queued.id}", useUnmergedTree = true)
                .getBoundsInRoot()
            val copyBounds = onNodeWithTag("ciacola-review-copy-prompt-${queued.id}", useUnmergedTree = true)
                .assertIsDisplayed().getBoundsInRoot()
            val appliedBounds = onNodeWithTag("ciacola-review-mark-applied-${queued.id}", useUnmergedTree = true)
                .assertIsDisplayed().getBoundsInRoot()
            val setAgentBounds = onNodeWithTag("ciacola-review-set-agent-${queued.id}", useUnmergedTree = true)
                .assertIsDisplayed().getBoundsInRoot()
            val resolveBounds = onNodeWithTag("ciacola-review-resolve-${queued.id}", useUnmergedTree = true)
                .assertIsDisplayed().getBoundsInRoot()
            for (b in listOf(copyBounds, appliedBounds, setAgentBounds, resolveBounds)) {
                assertTrue(
                    abs(b.top.value - copyBounds.top.value) <= 1f,
                    "Expected Set Agent + Resolve on a single row at 460dp; got $b vs $copyBounds",
                )
                assertTrue(
                    b.left.value >= actionsRowBounds.left.value - 0.5f &&
                        b.right.value <= actionsRowBounds.right.value + 0.5f,
                    "Action button $b clipped outside $actionsRowBounds",
                )
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
