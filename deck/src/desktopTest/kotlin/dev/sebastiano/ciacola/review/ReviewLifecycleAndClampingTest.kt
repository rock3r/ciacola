package dev.sebastiano.ciacola.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.dragAndDrop
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.OverviewVisibility
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ReviewLifecycleAndClampingTest {

    @Test
    fun stageClampingAndDisplayNumberingHelpersEnforceBoundsAndGlobalOrdering() {
        val stageSize = IntSize(1920, 1080)
        val clampedPt = clampStagePoint(Offset(-40f, 1250f), stageSize)
        assertEquals(Offset(0f, 1080f), clampedPt)

        val clampedRect = clampStageRect(Rect(-100f, -20f, 2100f, 1200f), stageSize)
        assertEquals(Rect(0f, 0f, 1920f, 1080f), clampedRect)

        val c1 = SlideReviewComment(
            id = "c1", sceneIndex = 0, frameIndex = 0, sceneCount = 5, frameCount = 3,
            stageX = 100f, stageY = 100f, target = ReviewTargetHit("Text", "A"), note = "First",
            createdAt = "2026-11-13T10:00:00Z",
        )
        val c2 = SlideReviewComment(
            id = "c2", sceneIndex = 1, frameIndex = 0, sceneCount = 5, frameCount = 2,
            stageX = 200f, stageY = 200f, target = ReviewTargetHit("Text", "B"), note = "Second",
            createdAt = "2026-11-13T10:01:00Z",
        )
        val all = listOf(c1, c2)
        assertEquals(1, reviewDisplayNumber(all, "c1"))
        assertEquals(2, reviewDisplayNumber(all, "c2"))
    }

    @Test
    fun hudKeepsCtrlClickHintWhileApplyingHidesInOverviewAndIgnoresDragsFromHud() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-review-hud-overview")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
        val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)
        runBlocking {
            store.submitComment(
                comment = SlideReviewComment(
                    id = "c-applying",
                    sceneIndex = 0,
                    frameIndex = 0,
                    sceneCount = 5,
                    frameCount = 3,
                    stageX = 300f,
                    stageY = 200f,
                    target = ReviewTargetHit("Text", "Title"),
                    note = "Check title",
                    status = ReviewCommentStatus.Applying,
                    createdAt = "2026-11-13T10:00:00Z",
                ),
                dispatchImmediate = false,
            )
            // Submit leaves status Queued when dispatchImmediate=false; simulate Applying on disk.
            store.commentsFile.writeText(
                store.commentsFile.readText().replace("\"queued\"", "\"applying\""),
            )
            store.reloadFromDisk()
        }
        assertEquals(ReviewCommentStatus.Applying, store.comments.single().status)

        lateinit var coordinator: ReviewStageCoordinator
        lateinit var overviewVisibility: OverviewVisibility
        setContent {
            coordinator = rememberReviewStageCoordinator()
            overviewVisibility = rememberOverviewVisibility()
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
            CompositionLocalProvider(LocalOverviewOpen provides overviewVisibility.isVisible) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    reviewWindow.decorator.decorate {
                        EaselOverview(animatic, visibility = overviewVisibility) {
                            Easel(animatic)
                        }
                    }
                }
            }
        }
        waitForIdle()

        // 1. Ctrl+Click stage to comment hint stays visible even when 1 comment is applying!
        onNode(hasText("Ctrl+Click stage to comment")).assertIsDisplayed()
        onNodeWithTag("ciacola-hud-applying-badge").assertIsDisplayed()
        onNode(hasText("1 applying…")).assertIsDisplayed()

        // 2. Starting a drag on the HUD while Inspect mode is active must be ignored (no composer).
        runOnIdle { coordinator.inspectModeEnabled = true }
        waitForIdle()
        val hudBounds = onNodeWithTag("ciacola-hud-toolbar").fetchSemanticsNode().boundsInRoot
        onRoot().performMouseInput {
            dragAndDrop(
                start = Offset(hudBounds.right - 4f, hudBounds.top + 4f),
                end = Offset(hudBounds.left - 120f, hudBounds.bottom + 80f),
            )
        }
        waitForIdle()
        mainClock.advanceTimeBy(200)
        waitForIdle()
        assertNull(coordinator.pendingSelection, "Drags starting on HUD must not open review composer")
        runOnIdle { coordinator.inspectModeEnabled = false }
        waitForIdle()

        // 3. Opening Esc Overview must hide ReviewHudToolbar so it never occludes top-right thumbnail!
        // Pause autoAdvance while Overview thumbnails (which contain infinite loops) are mounted.
        mainClock.autoAdvance = false
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performKeyInput {
            pressKey(Key.Escape)
        }
        mainClock.advanceTimeBy(450)
        assertTrue(overviewVisibility.isVisible, "Escape must open Overview")
        assertEquals(
            0,
            onAllNodesWithTag("ciacola-hud-toolbar").fetchSemanticsNodes().size,
            "ReviewHudToolbar must hide while Esc Overview is open",
        )

        // Closing Esc Overview restores ReviewHudToolbar.
        runOnUiThread { overviewVisibility.isVisible = false }
        mainClock.advanceTimeBy(450)
        mainClock.autoAdvance = true
        waitForIdle()
        onNodeWithTag("ciacola-hud-toolbar").assertIsDisplayed()
    }

    @Test
    fun composerCapturesOpeningSlideCoordinatesTypedFirstCharAndPinNumbersMatchCards() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-review-slide-capture")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
        val fakeAgentApi = tempRoot.resolve("fake-agentapi.sh").apply {
            writeText("#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(this, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)
        store.saveConfig(ReviewConfig(conversationId = "conv-slide-cap", agentApiBin = fakeAgentApi.toAbsolutePath().toString()))

        lateinit var coordinator: ReviewStageCoordinator
        lateinit var animaticRef: dev.bnorm.storyboard.easel.Animatic
        lateinit var scopeRef: kotlinx.coroutines.CoroutineScope

        setContent {
            scopeRef = androidx.compose.runtime.rememberCoroutineScope()
            coordinator = rememberReviewStageCoordinator()
            val deck = createFoundationDeck(
                includeKeynoteEffectsLab = true,
                stageDecorator = coordinator.stageDecorator,
            )
            animaticRef = rememberAnimatic(storyboard = { deck })
            val reviewWindow = rememberSlideReviewWindow(
                animatic = animaticRef,
                store = store,
                stageCoordinator = coordinator,
            )
            Column {
                Box(Modifier.size(960.dp, 540.dp)) {
                    reviewWindow.decorator.decorate {
                        EaselOverview(animaticRef) {
                            Easel(animaticRef)
                        }
                    }
                }
                if (reviewWindow.visible) {
                    Box(Modifier.size(460.dp, 480.dp)) {
                        reviewWindow.Content()
                    }
                }
            }
        }
        waitForIdle()

        // Pre-seed comment #1 on Scene 0 so the next comment on Scene 1 gets global pin "2".
        runBlocking {
            store.submitComment(
                SlideReviewComment(
                    id = "rc-1", sceneIndex = 0, frameIndex = 0, sceneCount = 5, frameCount = 3,
                    sceneTitle = "Beats", stageX = 300f, stageY = 200f,
                    target = ReviewTargetHit("Text", "First"), note = "Slide 1 comment",
                    createdAt = "2026-11-13T10:00:00Z",
                ),
                dispatchImmediate = false,
            )
        }
        waitForIdle()

        // Advance to Scene 1 (Shared Move) and open composer.
        runOnIdle {
            scopeRef.launch {
                animaticRef.jumpTo(dev.bnorm.storyboard.Storyboard.Index(1, 0))
            }
        }
        waitUntil(timeoutMillis = 5_000) { animaticRef.currentIndex.sceneIndex == 1 }
        waitForIdle()

        runOnIdle { coordinator.inspectModeEnabled = true }
        waitForIdle()
        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(300f, 240f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(120)
        waitForIdle()

        val pending = assertNotNull(coordinator.pendingSelection)
        assertEquals(1, pending.sceneIndex)
        assertEquals("Shared Move", pending.sceneTitle)

        // Immediate typing must preserve the very first character!
        onNodeWithTag("ciacola-review-note-input").performTextInput("Keep card centered")
        waitForIdle()

        // Navigate forward to Scene 2 while the composer is still open!
        runOnIdle {
            scopeRef.launch { animaticRef.advance(AdvanceDirection.Forward) }
        }
        waitUntil(timeoutMillis = 5_000) { animaticRef.targetIndex.sceneIndex != 1 || animaticRef.targetIndex.frameIndex != 0 }
        waitForIdle()

        // Submitting must still file under captured Scene 1, Frame 0, "Shared Move"!
        onNodeWithTag("ciacola-review-queue-button").performClick()
        waitUntil(timeoutMillis = 5_000) { store.comments.size == 2 }

        val filed = store.comments[1]
        assertEquals(1, filed.sceneIndex, "Comment must be filed under opening sceneIndex=1, not navigated slide")
        assertEquals(0, filed.frameIndex)
        assertEquals("Shared Move", filed.sceneTitle)
        assertEquals("Keep card centered", filed.note)

        // Jump back to Scene 1: Stage pin must display "2" (matching Review card #2, not "1"!).
        runOnIdle {
            scopeRef.launch { animaticRef.jumpTo(dev.bnorm.storyboard.Storyboard.Index(1, 0)) }
        }
        waitUntil(timeoutMillis = 5_000) { animaticRef.currentIndex.sceneIndex == 1 }
        waitForIdle()
        onNodeWithTag("ciacola-review-pin-2").assertIsDisplayed()
    }

    @Test
    fun unsentCommentRequiresResolveConfirmationSupportsMarkAppliedAndUndoRestore() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-review-lifecycle")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
        val fakeAgentApi = tempRoot.resolve("fake-agentapi.sh").apply {
            writeText("#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(this, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)
        store.saveConfig(ReviewConfig(conversationId = "conv-life", agentApiBin = fakeAgentApi.toAbsolutePath().toString()))

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
            Row {
                Box(Modifier.size(540.dp, 360.dp)) {
                    reviewWindow.decorator.decorate {
                        EaselOverview(animatic) {
                            Easel(animatic)
                        }
                    }
                }
                Box(Modifier.size(460.dp, 680.dp)) {
                    reviewWindow.Content()
                }
            }
        }
        waitForIdle()

        // Submit an unapplied comment (Applying).
        runBlocking {
            store.submitComment(
                SlideReviewComment(
                    id = "rc-life-1", sceneIndex = 0, frameIndex = 0, sceneCount = 5, frameCount = 3,
                    sceneTitle = "Beats", stageX = 320f, stageY = 240f,
                    target = ReviewTargetHit("Text", "Headline"), note = "Never silently drop me",
                    createdAt = "2026-11-13T10:00:00Z",
                ),
                dispatchImmediate = true,
            )
        }
        waitForIdle()
        assertEquals(ReviewCommentStatus.Applying, store.comments.single().status)

        // 1. Clicking Resolve on unapplied comment inside ReviewWindow asks confirmation instead of vaporizing!
        onNodeWithTag("ciacola-review-resolve-rc-life-1", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, store.comments.size, "First click on Resolve for unapplied comment must require confirmation")
        onNodeWithTag("ciacola-review-card-discard-confirm-rc-life-1", useUnmergedTree = true).assertIsDisplayed()

        // Confirm discard -> moves to trash and surfaces Undo banner!
        onNodeWithTag("ciacola-review-card-confirm-discard-rc-life-1", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(store.comments.isEmpty())
        onNodeWithTag("ciacola-review-trash-restore-banner", useUnmergedTree = true).assertIsDisplayed()

        // Undo restores the comment intact!
        onNodeWithTag("ciacola-review-restore-trash-button", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, store.comments.size)
        assertEquals("Never silently drop me", store.comments.single().note)

        // 2. Stuck in Applying -> manual "Mark Applied" resolves status cleanly!
        onNodeWithTag("ciacola-review-mark-applied-rc-life-1", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(ReviewCommentStatus.Applied, store.comments.single().status)
    }
}
