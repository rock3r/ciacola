package dev.sebastiano.ciacola.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
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
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SlideReviewSystemTest {

    @Test
    fun resolveBestReviewHitPrefersLeafTargetAndMergesClickedTokenWithContainerContext() {
        val frameTarget = RegisteredTargetSnapshot(
            id = "frame",
            kind = "Frame",
            label = "FOUNDATION / PROVISIONAL",
            textSnippet = "",
            isContainer = true,
            stageBounds = Rect(0f, 0f, 1920f, 1080f),
        )
        val magicTextTarget = RegisteredTargetSnapshot(
            id = "prose",
            kind = "MagicText",
            label = "Slide prose",
            textSnippet = "The same path is reversible without inventing a second navigation model.",
            isContainer = false,
            stageBounds = Rect(120f, 380f, 1600f, 560f),
        )
        val wordSemantics = ReviewTargetHit(
            kind = "Text",
            label = "reversible",
            textSnippet = "reversible",
            bounds = StageRectDto(540f, 410f, 720f, 470f),
        )

        val pointHit = resolveBestReviewHit(
            stagePoint = Offset(600f, 440f),
            dragRect = null,
            registeredTargets = listOf(frameTarget, magicTextTarget),
            semanticsHits = listOf(wordSemantics),
        )
        assertEquals("MagicText", pointHit.kind)
        assertEquals(
            "The same path is reversible without inventing a second navigation model.",
            pointHit.textSnippet,
        )
        assertEquals("reversible", pointHit.clickedToken)

        val regionHit = resolveBestReviewHit(
            stagePoint = Offset(800f, 500f),
            dragRect = Rect(100f, 350f, 900f, 600f),
            registeredTargets = listOf(frameTarget, magicTextTarget),
            semanticsHits = emptyList(),
        )
        assertEquals("Region", regionHit.kind)
        assertTrue(regionHit.textSnippet.contains("reversible"))
    }

    @Test
    fun ctrlSecondaryClickOnSlideOpensComposerPersistsCommentAndInvokesAgentApiWithoutSourceMetadata() =
        runComposeUiTest {
            val tempRoot = Files.createTempDirectory("ciacola-review-test")
            val storyboardDir = tempRoot.resolve(".storyboard").also { Files.createDirectories(it) }
            val dispatchLog = tempRoot.resolve("agentapi-dispatch.log")
            val fakeAgentApi = tempRoot.resolve("fake-agentapi.sh")
            fakeAgentApi.writeText(
                """
                #!/bin/sh
                printf 'META=%s\nARGS=%s|%s|%s|%s\n' "${'$'}{ANTIGRAVITY_SOURCE_METADATA:-UNSET}" "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4" > "${dispatchLog.toAbsolutePath()}"
                """.trimIndent() + "\n"
            )
            Files.setPosixFilePermissions(fakeAgentApi, PosixFilePermissions.fromString("rwxr-xr-x"))

            val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)
            store.saveConfig(
                ReviewConfig(
                    conversationId = "conv-test-42",
                    agentApiBin = fakeAgentApi.toAbsolutePath().toString(),
                )
            )

            setContent {
                val stageCoordinator = rememberReviewStageCoordinator()
                val deck = createFoundationDeck(
                    includeKeynoteEffectsLab = true,
                    stageDecorator = stageCoordinator.stageDecorator,
                )
                val animatic = rememberAnimatic(storyboard = { deck })
                val reviewWindow = rememberSlideReviewWindow(
                    animatic = animatic,
                    store = store,
                    stageCoordinator = stageCoordinator,
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

            // Simulate macOS Ctrl + Click (which delivers Ctrl modifier + Secondary mouse button!)
            onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performKeyInput {
                keyDown(Key.CtrlLeft)
            }
            onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
                click(
                    position = Offset(300f, 240f),
                    button = MouseButton.Secondary,
                )
            }
            onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performKeyInput {
                keyUp(Key.CtrlLeft)
            }
            waitForIdle()
            mainClock.advanceTimeBy(300)
            waitForIdle()

            // Prove keyboard focus genuinely stays on Jewel's TextArea inside EaselOverview (no focus theft!).
            onNodeWithTag("ciacola-review-note-input").assertIsFocused()
            onNodeWithTag("ciacola-review-note-input").performTextInput("Make this headline punchier and warm coral")

            // Submit via real Ctrl+Enter keyboard shortcut!
            onNodeWithTag("ciacola-review-note-input").performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.Enter)
                keyUp(Key.CtrlLeft)
            }

            waitUntil(timeoutMillis = 5_000) {
                store.comments.isNotEmpty() && dispatchLog.exists()
            }

            val saved = store.comments.single()
            assertEquals(0, saved.sceneIndex)
            assertEquals(0, saved.frameIndex)
            assertEquals("Make this headline punchier and warm coral", saved.note)
            assertEquals(ReviewCommentStatus.Applying, saved.status)
            assertNotNull(saved.screenshotPath)
            assertTrue(Files.exists(java.nio.file.Path.of(saved.screenshotPath!!)))

            val logText = dispatchLog.readText()
            assertTrue(logText.contains("META=UNSET"), "Expected ANTIGRAVITY_SOURCE_METADATA unset, got: $logText")
            assertTrue(logText.contains("send-message"))
            assertTrue(logText.contains("conv-test-42"))
            assertTrue(logText.contains("Make this headline punchier and warm coral"))
        }

    @Test
    fun pressingEscapeClosesOpenCommentPopupWithoutTriggeringEaselOverviewGrid() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-review-esc")
        val storyboardDir = tempRoot.resolve(".storyboard").also { Files.createDirectories(it) }
        val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)

        setContent {
            val stageCoordinator = rememberReviewStageCoordinator()
            val deck = createFoundationDeck(
                includeKeynoteEffectsLab = true,
                stageDecorator = stageCoordinator.stageDecorator,
            )
            val animatic = rememberAnimatic(storyboard = { deck })
            val reviewWindow = rememberSlideReviewWindow(
                animatic = animatic,
                store = store,
                stageCoordinator = stageCoordinator,
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
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performKeyInput { keyDown(Key.CtrlLeft) }
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(320f, 240f), button = MouseButton.Secondary)
        }
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performKeyInput { keyUp(Key.CtrlLeft) }
        waitForIdle()
        mainClock.advanceTimeBy(300)
        waitForIdle()

        onNodeWithTag("ciacola-review-note-input").assertIsFocused()

        // Press Escape (KeyDown + KeyUp) while the comment box is open: must close popup AND keep Story stage!
        onNodeWithTag("ciacola-review-note-input").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()
        mainClock.advanceTimeBy(350)
        waitForIdle()

        assertEquals(0, onAllNodesWithTag("ciacola-review-note-input").fetchSemanticsNodes().size)
        onNodeWithTag("ciacola-review-stage").assertIsDisplayed()
    }

    @Test
    fun storeReloadsAgentAppliedResolutionFromDiskWithoutLosingComments() = runBlocking {
        val tempRoot = Files.createTempDirectory("ciacola-review-sync")
        val storyboardDir = tempRoot.resolve(".storyboard").also { Files.createDirectories(it) }
        val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)

        val queued = store.submitComment(
            comment = SlideReviewComment(
                id = "rc-test-1",
                sceneIndex = 4,
                frameIndex = 2,
                sceneCount = 5,
                frameCount = 5,
                sceneHeader = "KEYNOTE EFFECTS LAB / PROVISIONAL",
                stageX = 960f,
                stageY = 540f,
                target = ReviewTargetHit(kind = "Stamp", label = "IMMUTABLE", textSnippet = "IMMUTABLE"),
                note = "Tilt stamp to -6deg",
                status = ReviewCommentStatus.Queued,
                createdAt = "2026-11-11T16:50:00Z",
            ),
            dispatchImmediate = false,
        )
        assertEquals(ReviewCommentStatus.Queued, queued.status)

        val commentsJsonPath = storyboardDir.resolve("review-comments.json")
        assertTrue(commentsJsonPath.exists())

        // Simulate external agent marking the comment applied in review-comments.json after :deck:reload.
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
        val updatedOnDisk = listOf(
            queued.copy(
                status = ReviewCommentStatus.Applied,
                resolution = "Rotated KeynoteStamp to -6deg and triggered :deck:reload",
            )
        )
        commentsJsonPath.writeText(json.encodeToString(updatedOnDisk))

        val changed = store.reloadFromDisk()
        assertTrue(changed)
        assertEquals(ReviewCommentStatus.Applied, store.comments.single().status)
        assertEquals(
            "Rotated KeynoteStamp to -6deg and triggered :deck:reload",
            store.comments.single().resolution,
        )
    }
}
