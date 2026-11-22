package dev.sebastiano.ciacola.decks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.buildFoundationTestStoryboard
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.SlideReviewWindow
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberSlideReviewWindow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReviewPromptUiIntegrationTest {

    @Test
    fun copyPromptButtonsInComposerFeedAndCardCopyDeckAwareAgentPromptWithCopiedFeedback() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-review-copy-ui-")
        try {
            val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
            val deckFile = tempRoot.resolve("custom-talk.deck.json").also {
                Files.writeString(it, "{}")
            }
            val copiedPrompts = mutableListOf<String>()
            val store = ReviewCommentStore(
                storyboardDir = storyboardDir,
                workspaceRoot = tempRoot,
                defaultDeckId = "custom-talk",
                defaultDeckFilePath = "$deckFile",
                clipboardWriter = { copiedPrompts += it },
                envProvider = { null },
                systemPropertyProvider = { null },
            )

            lateinit var reviewWindow: SlideReviewWindow
            setContent {
                val coordinator = rememberReviewStageCoordinator().apply {
                    inspectModeEnabled = true
                }
                val deck = buildFoundationTestStoryboard(stageDecorator = coordinator.stageDecorator)
                val animatic = rememberAnimatic(storyboard = { deck })
                reviewWindow = rememberSlideReviewWindow(
                    animatic = animatic,
                    store = store,
                    stageCoordinator = coordinator,
                    deckIdProvider = { "custom-talk" },
                    deckFilePathProvider = { "${tempRoot.resolve("custom-talk.deck.json")}" },
                    slideIdProvider = { idx -> "s0${idx + 1}" },
                ).apply {
                    visible = true
                }

                Row {
                    Box(Modifier.size(520.dp, 540.dp)) {
                        reviewWindow.decorator.decorate {
                            EaselOverview(animatic) {
                                Easel(animatic)
                            }
                        }
                    }
                    Box(Modifier.size(480.dp, 540.dp)) {
                        reviewWindow.Content()
                    }
                }
            }

            waitForIdle()
            onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
                click(position = Offset(300f, 240f))
            }
            waitForIdle()
            mainClock.advanceTimeBy(250)
            waitForIdle()

            onNodeWithTag("ciacola-review-note-input").performTextInput("Align headline with left grid")
            waitForIdle()

            // 1. Copy Prompt from composer popup before submitting.
            onNodeWithTag("ciacola-review-composer-copy-prompt").performClick()
            waitForIdle()
            assertEquals(1, copiedPrompts.size)
            assertTrue(copiedPrompts.last().contains("Deck ID: `custom-talk`"))
            assertTrue(copiedPrompts.last().contains("slideId=s01"))
            val expectedDeckFile = tempRoot.resolve("custom-talk.deck.json").toString()
            assertTrue(copiedPrompts.last().contains("Edit `$expectedDeckFile`"))
            assertTrue(copiedPrompts.last().contains("./gradlew :deck:lintDeck -Pciacola.deck=$expectedDeckFile"))
            onNode(hasText("Copied")).assertIsDisplayed()

            // 2. Queue the comment so it appears in the Review Feed header + card.
            onNodeWithTag("ciacola-review-queue-button").performClick()
            waitForIdle()
            mainClock.advanceTimeBy(250)
            waitForIdle()
            assertEquals(1, store.comments.size)
            val savedId = store.comments.single().id

            // 3. Copy Prompt from Review Feed header.
            onNodeWithTag("ciacola-review-copy-all-prompt").performClick()
            waitForIdle()
            assertEquals(2, copiedPrompts.size)
            assertTrue(copiedPrompts.last().contains(savedId))
            assertTrue(copiedPrompts.last().contains("Align headline with left grid"))

            // 4. Copy Prompt from individual comment card.
            onNodeWithTag("ciacola-review-copy-prompt-$savedId")
                .performScrollTo()
                .performClick()
            waitForIdle()
            assertEquals(3, copiedPrompts.size)
            assertTrue(copiedPrompts.last().contains("DeckFileSync"))
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}
