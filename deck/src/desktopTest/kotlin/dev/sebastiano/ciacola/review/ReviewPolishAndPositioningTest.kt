package dev.sebastiano.ciacola.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReviewPolishAndPositioningTest {

    @Test
    fun composerAnchorsOutsideTargetRectBelowAboveOrSideClampedToWindow() {
        val window = Size(1280f, 800f)
        val popup = Size(336f, 280f)
        val gap = 12f
        val margin = 8f

        // 1. Target in upper half -> prefers below with 12px gap.
        val upperTarget = Rect(200f, 120f, 520f, 180f)
        val belowPos = computePopupOffsetOutsideTarget(upperTarget, upperTarget.center, popup, window, gap, margin)
        val belowRect = Rect(belowPos, popup)
        assertFalse(belowRect.overlaps(upperTarget), "Popup placed below must not overlap target")
        assertEquals(upperTarget.bottom + gap, belowPos.y, absoluteTolerance = 0.5f)

        // 2. Target near bottom -> flips above with 12px gap.
        val bottomTarget = Rect(200f, 640f, 520f, 710f)
        val abovePos = computePopupOffsetOutsideTarget(bottomTarget, bottomTarget.center, popup, window, gap, margin)
        val aboveRect = Rect(abovePos, popup)
        assertFalse(aboveRect.overlaps(bottomTarget), "Popup placed above must not overlap target")
        assertEquals(bottomTarget.top - gap - popup.height, abovePos.y, absoluteTolerance = 0.5f)

        // 3. Tall target filling vertical span on left -> flips to right side with 12px gap.
        val tallLeftTarget = Rect(120f, 140f, 440f, 680f)
        val sidePos = computePopupOffsetOutsideTarget(tallLeftTarget, tallLeftTarget.center, popup, window, gap, margin)
        val sideRect = Rect(sidePos, popup)
        assertFalse(sideRect.overlaps(tallLeftTarget), "Popup placed to the side must not overlap target")
        assertEquals(tallLeftTarget.right + gap, sidePos.x, absoluteTolerance = 0.5f)
    }

    @Test
    fun stagePinsOffsetToTopRightCornerOfTargetBoundsAndClampInsideStage() {
        val stageSize = IntSize(1920, 1080)
        val stampBounds = StageRectDto(left = 300f, top = 400f, right = 540f, bottom = 460f)
        val comment = SlideReviewComment(
            id = "c-stamp",
            sceneIndex = 4,
            frameIndex = 2,
            sceneCount = 5,
            frameCount = 5,
            stageX = 420f,
            stageY = 430f,
            selectionBounds = stampBounds,
            target = ReviewTargetHit("KeynoteStamp", "IMMUTABLE", bounds = stampBounds),
            note = "Test",
            createdAt = "2026-11-13T10:00:00Z",
        )
        val center = computeStagePinCenter(comment, stageSize, pinRadiusPx = 12f)
        assertTrue(center.x >= stampBounds.right, "Pin center x=${center.x} must be outside/on right edge ${stampBounds.right}")
        assertTrue(center.y <= stampBounds.top, "Pin center y=${center.y} must be outside/on top edge ${stampBounds.top}")

        // Corner edge clamping within [4..1916, 4..1076].
        val cornerBounds = StageRectDto(left = 1800f, top = 2f, right = 1918f, bottom = 40f)
        val clampedCenter = computeStagePinCenter(
            comment.copy(selectionBounds = cornerBounds, target = ReviewTargetHit("Text", "Corner", bounds = cornerBounds)),
            stageSize,
            pinRadiusPx = 12f,
        )
        assertTrue(clampedCenter.x in 4f..1916f, "Expected x in 4..1916, got ${clampedCenter.x}")
        assertTrue(clampedCenter.y in 4f..1076f, "Expected y in 4..1076, got ${clampedCenter.y}")
    }

    @Test
    fun emptyReviewWindowCollapsesAgentConfigBehindDisclosureAndHidesDisabledChrome() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-review-empty-state")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
        val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)
        assertTrue(store.comments.isEmpty())

        setContent {
            val coordinator = rememberReviewStageCoordinator()
            val deck = createFoundationDeck(includeKeynoteEffectsLab = false, stageDecorator = coordinator.stageDecorator)
            val animatic = rememberAnimatic(storyboard = { deck })
            val reviewWindow = rememberSlideReviewWindow(
                animatic = animatic,
                store = store,
                stageCoordinator = coordinator,
            )
            reviewWindow.visible = true
            Box(Modifier.size(440.dp, 520.dp)) {
                reviewWindow.Content()
            }
        }
        waitForIdle()

        // 0 comments: Agent Config details are collapsed behind "Agent Configuration" toggle,
        // and disabled "Send Queued (0)" / "Clear Applied" buttons are not shown.
        onNodeWithTag("ciacola-review-agent-config-toggle").assertIsDisplayed()
        onNodeWithText("Agent Configuration").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag("ciacola-review-header-bar").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag("ciacola-review-panel-conv-id-input").fetchSemanticsNodes().size)
        assertTrue(onAllNodes(hasText("Send Queued (0)")).fetchSemanticsNodes().isEmpty())

        // Clicking "Agent Configuration" expands the Target Agent Conversation ID form.
        onNodeWithTag("ciacola-review-agent-config-toggle").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-review-header-bar").assertIsDisplayed()
        onNodeWithTag("ciacola-review-panel-conv-id-input").assertIsDisplayed()
    }
}
