package dev.sebastiano.ciacola.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReviewUnconfiguredConversationTest {
    @Test
    fun blankConversationIdValidatesInlineOnSendAndHudOpensReviewConfigurationPanel() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-review-unconfigured")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
        val store = ReviewCommentStore(
            storyboardDir = storyboardDir,
            workspaceRoot = tempRoot,
            envProvider = { null },
            systemPropertyProvider = { null },
        )
        assertEquals("", store.effectiveConversationId(), "Precondition: fresh store must have blank Conversation ID")

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
            Column {
                Box(Modifier.size(960.dp, 540.dp)) {
                    reviewWindow.decorator.decorate {
                        EaselOverview(animatic) {
                            Easel(animatic)
                        }
                    }
                }
                if (reviewWindow.visible) {
                    Box(Modifier.size(460.dp, 540.dp)) {
                        reviewWindow.Content()
                    }
                }
            }
        }

        waitForIdle()
        runOnIdle { coordinator.inspectModeEnabled = true }
        waitForIdle()

        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(300f, 240f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        assertNotNull(coordinator.pendingSelection, "Composer should open on Inspect click")
        onNodeWithTag("ciacola-review-note-input").performTextInput("Tighten headline spacing")
        waitForIdle()

        // Clicking primary Send without a Conversation ID must NOT silently dismiss and pretend to send.
        onNodeWithTag("ciacola-review-send-button").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        assertNotNull(
            coordinator.pendingSelection,
            "Composer must remain open with inline validation when Conversation ID is blank",
        )
        assertTrue(store.comments.isEmpty(), "Blank-ID Send must not silently persist a demoted comment")
        onNode(hasText("Enter a Target Agent Conversation ID", substring = true)).assertIsDisplayed()
        onNodeWithTag("ciacola-review-conv-id-input").assertIsFocused()

        // Clicking Queue explicitly saves the comment as Queued and closes the composer.
        onNodeWithTag("ciacola-review-queue-button").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        assertNull(coordinator.pendingSelection, "Queue should save for later and close composer")
        assertEquals(1, store.comments.size)
        assertEquals(ReviewCommentStatus.Queued, store.comments.single().status)

        // Clicking the HUD queued button when unconfigured must open the F4 Review configuration panel
        // instead of silently looping Queued -> Applying -> Queued.
        onNode(hasText("Set Agent (1)") or hasText("Send 1")).performClick()
        waitForIdle()

        assertTrue(reviewWindow.visible, "HUD queued button must open the F4 Review configuration panel when unconfigured")
        onNode(hasText("No Conversation ID configured", substring = true)).assertIsDisplayed()
        assertEquals(ReviewCommentStatus.Queued, store.comments.single().status)
    }
}
