package dev.sebastiano.ciacola.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ReviewP1ErgonomicsTest {

    @Test
    fun composerRetainsTypedDraftAcrossEscapeAndOutsideClickUntilClearedOrSubmitted() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-review-draft-retention")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
        val fakeAgentApi = tempRoot.resolve("fake-agentapi.sh").apply {
            writeText("#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(this, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)
        store.saveConfig(
            ReviewConfig(
                conversationId = "conv-draft-retention",
                agentApiBin = fakeAgentApi.toAbsolutePath().toString(),
            )
        )

        lateinit var coordinator: ReviewStageCoordinator

        setContent {
            coordinator = rememberReviewStageCoordinator()
            val deck = createFoundationDeck(
                includeKeynoteEffectsLab = true,
                stageDecorator = coordinator.stageDecorator,
            )
            val animatic = rememberAnimatic(storyboard = { deck })
            val reviewWindow = rememberSlideReviewWindow(
                animatic = animatic,
                store = store,
                stageCoordinator = coordinator,
            )
            Box(Modifier.size(960.dp, 540.dp)) {
                reviewWindow.decorator.decorate {
                    EaselOverview(animatic) {
                        Easel(animatic)
                    }
                }
            }
        }

        waitForIdle()
        runOnIdle { coordinator.inspectModeEnabled = true }
        waitForIdle()

        // 1. Open composer on Slide 1 and type a multi-sentence draft.
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(300f, 240f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        assertNotNull(coordinator.pendingSelection, "Precondition: composer opened")
        assertEquals(
            0,
            onAllNodesWithTag("ciacola-review-draft-restored-hint").fetchSemanticsNodes().size,
            "Fresh composer open must not show Draft restored hint",
        )

        val draftSentence = "Tighten headline kerning by 2%. Also soften the card border alpha."
        onNodeWithTag("ciacola-review-note-input").performTextInput(draftSentence)
        waitForIdle()

        // 2. Dismiss via Escape: composer closes, but unsent draft must survive on reopen.
        onNodeWithTag("ciacola-review-note-input").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()
        assertNull(coordinator.pendingSelection, "Escape should close composer")

        // Reopen composer at another point on the slide.
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(420f, 300f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        assertNotNull(coordinator.pendingSelection, "Composer should reopen")
        onNode(hasText(draftSentence, substring = true)).assertIsDisplayed()
        onNodeWithTag("ciacola-review-draft-restored-hint").assertIsDisplayed()
        onNode(hasText("Draft restored", substring = true)).assertIsDisplayed()

        // 3. Dismiss via accidental outside click on the stage: draft must also survive.
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(24f, 24f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()
        assertNull(coordinator.pendingSelection, "Outside click should dismiss open composer")

        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(360f, 260f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        assertNotNull(coordinator.pendingSelection, "Composer should reopen after outside-click dismissal")
        onNode(hasText(draftSentence, substring = true)).assertIsDisplayed()
        onNodeWithTag("ciacola-review-draft-restored-hint").assertIsDisplayed()

        // 4. Explicit Clear/Discard button wipes the restored draft and hides the hint.
        onNodeWithTag("ciacola-review-clear-draft-button").performClick()
        waitForIdle()
        assertEquals(
            0,
            onAllNodesWithTag("ciacola-review-draft-restored-hint").fetchSemanticsNodes().size,
            "Clearing draft must hide the Draft restored hint",
        )
        assertEquals(
            0,
            onAllNodes(hasText(draftSentence, substring = true)).fetchSemanticsNodes().size,
            "Clearing draft must wipe the text field",
        )

        // 5. Type a new draft, dismiss via Escape, reopen, then Send — submission must clear draft!
        val finalNote = "Increase subtitle contrast in Stage Dark."
        onNodeWithTag("ciacola-review-note-input").performTextInput(finalNote)
        waitForIdle()
        onNodeWithTag("ciacola-review-note-input").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(300f, 240f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()
        onNode(hasText(finalNote, substring = true)).assertIsDisplayed()

        onNodeWithTag("ciacola-review-send-button").performClick()
        waitUntil(timeoutMillis = 5_000) { store.comments.size == 1 }
        assertEquals(finalNote, store.comments.single().note)

        // Reopen after Send (away from Pin #1 at 300,240): composer must be empty with no Draft restored hint.
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(520f, 360f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()
        assertNotNull(coordinator.pendingSelection, "After Send, clicking empty stage must reopen composer")
        assertEquals(
            0,
            onAllNodesWithTag("ciacola-review-draft-restored-hint").fetchSemanticsNodes().size,
            "After Send, next composer open must not show Draft restored",
        )
        assertEquals(
            0,
            onAllNodes(hasText(finalNote, substring = true)).fetchSemanticsNodes().size,
            "After Send, next composer open must have empty note input",
        )
    }

    @Test
    fun clickingPinBadgeWhileInspectModeActiveOpensPinPopupInsteadOfNewComposer() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-review-pin-inspect")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
        val fakeAgentApi = tempRoot.resolve("fake-agentapi.sh").apply {
            writeText("#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(this, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)
        store.saveConfig(
            ReviewConfig(
                conversationId = "conv-pin-inspect",
                agentApiBin = fakeAgentApi.toAbsolutePath().toString(),
            )
        )

        lateinit var coordinator: ReviewStageCoordinator

        setContent {
            coordinator = rememberReviewStageCoordinator()
            val deck = createFoundationDeck(
                includeKeynoteEffectsLab = true,
                stageDecorator = coordinator.stageDecorator,
            )
            val animatic = rememberAnimatic(storyboard = { deck })
            val reviewWindow = rememberSlideReviewWindow(
                animatic = animatic,
                store = store,
                stageCoordinator = coordinator,
            )
            Box(Modifier.size(960.dp, 540.dp)) {
                reviewWindow.decorator.decorate {
                    EaselOverview(animatic) {
                        Easel(animatic)
                    }
                }
            }
        }

        waitForIdle()
        runOnIdle { coordinator.inspectModeEnabled = true }
        waitForIdle()

        // 1. Create a queued comment on Slide 1 so numbered pin "1" renders on the stage.
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(320f, 240f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        val pinNote = "Check contrast on this headline pin"
        onNodeWithTag("ciacola-review-note-input").performTextInput(pinNote)
        waitForIdle()
        onNodeWithTag("ciacola-review-queue-button").performClick()
        waitUntil(timeoutMillis = 5_000) { store.comments.size == 1 && coordinator.pendingSelection == null }
        waitForIdle()

        val queuedComment = store.comments.single()
        assertTrue(coordinator.inspectModeEnabled, "Precondition: Inspect mode remains active")
        onNodeWithTag("ciacola-review-pin-1").assertIsDisplayed()

        // 2. Click pin 1 while Inspect mode is still enabled: must open the pin's thread popup, NOT a new composer!
        onNodeWithTag("ciacola-review-pin-1").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        assertNull(
            coordinator.pendingSelection,
            "Clicking a visible pin badge while Inspect mode is active must not open a new comment composer",
        )
        assertEquals(
            queuedComment.id,
            coordinator.selectedPinCommentId,
            "Clicking a visible pin badge while Inspect mode is active must open that pin's popup",
        )
        onNode(hasText(pinNote)).assertIsDisplayed()
        onNode(hasText("Resolve")).assertIsDisplayed()
        onNode(hasText("Send Now")).assertIsDisplayed()

        // 3. Dismiss the pin popup via Escape, then click away from the pin — normal Inspect hit-capture still works!
        onNodeWithTag("ciacola-review-pin-popup").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()
        assertNull(coordinator.selectedPinCommentId, "Escape should close the pin popup")

        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(720f, 400f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()
        assertNotNull(
            coordinator.pendingSelection,
            "Clicking away from existing pins in Inspect mode must still open the comment composer",
        )
    }

    @Test
    fun f4ReviewWindowPromotesConversationIdHeaderBarAtTopAndAutoDetectsEnvironment() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-review-header-convid")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
        val fakeEnv = mapOf(
            "ANTIGRAVITY_CONVERSATION_ID" to "conv-autodetected-ag-77",
        )
        val store = ReviewCommentStore(
            storyboardDir = storyboardDir,
            workspaceRoot = tempRoot,
            envProvider = fakeEnv::get,
            systemPropertyProvider = { null },
        )

        assertEquals("", store.config.conversationId, "Precondition: review-config.json has blank conversationId")
        val resolved = store.resolveConversationId()
        assertTrue(resolved.isAutoDetected, "Expected Conversation ID to be auto-detected from environment")
        assertEquals("conv-autodetected-ag-77", resolved.id)
        assertEquals("ANTIGRAVITY_CONVERSATION_ID", resolved.sourceName)

        // Pre-populate 18 queued comments so the comment feed overflows and requires scrolling.
        kotlinx.coroutines.runBlocking {
            repeat(18) { idx ->
                store.submitComment(
                    comment = SlideReviewComment(
                        id = "rc-scroll-$idx",
                        sceneIndex = 0,
                        frameIndex = 0,
                        sceneCount = 5,
                        frameCount = 3,
                        sceneHeader = "FOUNDATION / PROVISIONAL",
                        stageX = 200f + idx * 10f,
                        stageY = 200f,
                        target = ReviewTargetHit(kind = "Text", label = "Item $idx", textSnippet = "Item $idx"),
                        note = "Review feedback item #$idx",
                        status = ReviewCommentStatus.Queued,
                        createdAt = "2026-11-13T08:00:00Z",
                    ),
                    dispatchImmediate = false,
                )
            }
        }

        lateinit var coordinator: ReviewStageCoordinator
        lateinit var reviewWindow: SlideReviewWindow

        setContent {
            coordinator = rememberReviewStageCoordinator()
            val deck = createFoundationDeck(
                includeKeynoteEffectsLab = true,
                stageDecorator = coordinator.stageDecorator,
            )
            val animatic = rememberAnimatic(storyboard = { deck })
            reviewWindow = rememberSlideReviewWindow(
                animatic = animatic,
                store = store,
                stageCoordinator = coordinator,
            )
            reviewWindow.visible = true
            Box(Modifier.size(460.dp, 420.dp)) {
                reviewWindow.Content()
            }
        }

        waitForIdle()

        // 1. Target Agent Conversation ID lives in a compact header bar pinned at the VERY TOP of F4.
        val headerBounds = onNodeWithTag("ciacola-review-header-bar").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val convInputBounds = onNodeWithTag("ciacola-review-panel-conv-id-input").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val firstCardBounds = onNode(hasText("Review feedback item #0")).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            headerBounds.top <= 16f && convInputBounds.bottom <= firstCardBounds.top,
            "Target Agent Conversation ID header bar ($headerBounds) must be pinned at the top of F4 above comments ($firstCardBounds)",
        )

        // 2. Auto-detected Conversation ID is pre-populated and displays a clear indicator badge.
        onNode(hasText("conv-autodetected-ag-77", substring = true)).assertIsDisplayed()
        onNodeWithTag("ciacola-review-conv-autodetected-badge").assertIsDisplayed()
        onNode(hasText("Auto-detected (ANTIGRAVITY_CONVERSATION_ID)")).assertIsDisplayed()

        // 3. Scrolling to the bottom of the 18-comment feed leaves the top header bar pinned and visible!
        onNodeWithTag("ciacola-review-comments-list").performScrollToIndex(17)
        waitForIdle()
        onNode(hasText("Review feedback item #17")).assertIsDisplayed()
        onNodeWithTag("ciacola-review-header-bar").assertIsDisplayed()
        onNodeWithTag("ciacola-review-panel-conv-id-input").assertIsDisplayed()
        onNodeWithTag("ciacola-review-conv-autodetected-badge").assertIsDisplayed()
    }

    @Test
    fun conversationIdAutoDiscoveryHonoursEnvPrecedenceAndConfigOverride() {
        val tempRoot = Files.createTempDirectory("ciacola-review-conv-precedence")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))

        // 1. ANTIGRAVITY_CONVERSATION_ID fallback when others are absent.
        val agOnlyStore = ReviewCommentStore(
            storyboardDir = storyboardDir,
            workspaceRoot = tempRoot,
            envProvider = mapOf("ANTIGRAVITY_CONVERSATION_ID" to "ag-id-3")::get,
            systemPropertyProvider = { null },
        )
        assertEquals("ag-id-3", agOnlyStore.effectiveConversationId())
        assertTrue(agOnlyStore.resolveConversationId().isAutoDetected)
        assertEquals("ANTIGRAVITY_CONVERSATION_ID", agOnlyStore.resolveConversationId().sourceName)

        // 2. AGENT_CONVERSATION_ID takes precedence over ANTIGRAVITY_CONVERSATION_ID.
        val agentAndAgStore = ReviewCommentStore(
            storyboardDir = storyboardDir,
            workspaceRoot = tempRoot,
            envProvider = mapOf(
                "AGENT_CONVERSATION_ID" to "agent-id-2",
                "ANTIGRAVITY_CONVERSATION_ID" to "ag-id-3",
            )::get,
            systemPropertyProvider = { null },
        )
        assertEquals("agent-id-2", agentAndAgStore.effectiveConversationId())
        assertEquals("AGENT_CONVERSATION_ID", agentAndAgStore.resolveConversationId().sourceName)

        // 3. CIACOLA_CONVERSATION_ID takes precedence over AGENT_CONVERSATION_ID and ANTIGRAVITY_CONVERSATION_ID.
        val allEnvStore = ReviewCommentStore(
            storyboardDir = storyboardDir,
            workspaceRoot = tempRoot,
            envProvider = mapOf(
                "CIACOLA_CONVERSATION_ID" to "ciacola-id-1",
                "AGENT_CONVERSATION_ID" to "agent-id-2",
                "ANTIGRAVITY_CONVERSATION_ID" to "ag-id-3",
            )::get,
            systemPropertyProvider = { null },
        )
        assertEquals("ciacola-id-1", allEnvStore.effectiveConversationId())
        assertEquals("CIACOLA_CONVERSATION_ID", allEnvStore.resolveConversationId().sourceName)

        // 4. Explicit conversationId saved in .storyboard/review-config.json wins over all env vars
        // and is reported as Config (not auto-detected).
        allEnvStore.saveConfig(ReviewConfig(conversationId = "saved-config-id"))
        assertEquals("saved-config-id", allEnvStore.effectiveConversationId())
        assertEquals(ConversationIdSource.Config, allEnvStore.resolveConversationId().source)
        assertEquals(false, allEnvStore.resolveConversationId().isAutoDetected)
    }
}
