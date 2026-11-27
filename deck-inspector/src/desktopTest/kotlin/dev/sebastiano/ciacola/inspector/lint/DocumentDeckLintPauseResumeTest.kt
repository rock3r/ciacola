package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

@OptIn(ExperimentalTestApi::class)
class DocumentDeckLintPauseResumeTest {

    private fun syntheticDeck(slideCount: Int = 8): DeckDocument = DeckDocument(
        id = "pause-resume-deck",
        title = "Pause Resume Deck",
        defaultTheme = "dark",
        themes = mapOf(
            "dark" to DeckThemeSpec(
                preset = "stageDark",
                colors = mapOf("background" to "#111827", "foreground" to "#F8FAFC"),
            ),
            "light" to DeckThemeSpec(
                preset = "stageLight",
                colors = mapOf("background" to "#F8FAFC", "foreground" to "#111827"),
            ),
        ),
        slides = List(slideCount) { idx ->
            SlideSpec(
                id = "s${idx + 1}",
                title = "Slide ${idx + 1}",
                background = Fill.Solid("#111827"),
                nodes = listOf(
                    NodeSpec(
                        id = "s${idx + 1}:body",
                        frame = FrameSpec(x = 120.0, y = 160.0, w = 720.0, h = 90.0),
                        text = TextPayload(
                            value = RichTextValue.Plain("Distinct copy on slide ${idx + 1}"),
                            color = if (idx == 0 || idx == slideCount - 1) "#182030" else "#F8FAFC",
                            size = 40.0,
                        ),
                    ),
                ),
            )
        },
    )

    @Test
    fun pauseDuringInFlightAuditCancelsWithoutErrorAndResumesUsingCache() = runBlocking {
        val store = DeckDocumentStore(syntheticDeck(slideCount = 8), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentDeckLintController(store)
        try {
            val initialJob = controller.startFullDeckAudit(
                scope = this,
                themeNames = listOf("dark", "light"),
                sliceBudgetMs = 0.0,
            )

            var steps = 0
            while (controller.auditedSlides < 2 && controller.isAuditing && steps < 200) {
                yield()
                steps++
            }
            assertTrue(controller.isAuditing, "Expected audit to still be in-flight before pause")
            assertTrue(controller.auditedSlides in 2 until 8, "Expected partial progress, got ${controller.auditedSlides}")
            val cachedBeforePause = controller.engine.cacheEntryCount()
            assertTrue(cachedBeforePause >= 2, "Expected already-audited slides to be cached before pause")

            controller.pauseForPresenting()
            assertTrue(controller.isPaused)
            assertFalse(controller.isAuditing)
            assertTrue(controller.hasPausedAudit)
            assertNull(controller.lastError)

            repeat(10) { yield() }
            assertTrue(initialJob.isCancelled || initialJob.isCompleted)
            assertFalse(controller.isAuditing)
            assertTrue(controller.hasPausedAudit)
            assertNull(controller.lastError)
            assertEquals(cachedBeforePause, controller.engine.cacheEntryCount(), "No additional slides should audit while paused")

            // Repeated pause while already paused must be idempotent and retain pending resume state.
            controller.pauseForPresenting()
            assertTrue(controller.isPaused)
            assertTrue(controller.hasPausedAudit)

            val resumedJob = controller.resumeAfterPresenting(this)
            assertNotNull(resumedJob, "Resuming after pausing an in-flight audit must restart the audit")
            assertFalse(controller.isPaused)
            assertFalse(controller.hasPausedAudit)
            assertTrue(controller.isAuditing)

            resumedJob.join()
            assertFalse(controller.isAuditing)
            assertNull(controller.lastError)
            assertEquals(LintAuditScope.Deck, controller.auditScope)
            assertEquals(1f, controller.progress)
            assertEquals(8, controller.auditedSlides)
            assertTrue(
                controller.lastStats.cacheHits >= cachedBeforePause && controller.lastStats.cacheHits > 0,
                "Resumed audit should reuse cached slides (cacheHits=${controller.lastStats.cacheHits}, cachedBeforePause=$cachedBeforePause)",
            )
            assertTrue(controller.issues.any { it.slideId == "s1" && it.code == "LOW_CONTRAST" })
            assertTrue(controller.issues.any { it.slideId == "s8" && it.code == "LOW_CONTRAST" })
        } finally {
            controller.close()
        }
    }

    @Test
    fun pauseAndResumeWhenIdleDoesNotStartUnrequestedAudit() = runBlocking {
        val store = DeckDocumentStore(syntheticDeck(slideCount = 4), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentDeckLintController(store)
        try {
            assertNull(controller.setPaused(true, this))
            assertTrue(controller.isPaused)
            assertFalse(controller.hasPausedAudit)
            assertFalse(controller.isAuditing)

            val resumed = controller.setPaused(false, this)
            assertNull(resumed, "Unpausing an idle controller must not start an unrequested audit")
            assertFalse(controller.isPaused)
            assertFalse(controller.isAuditing)
            assertEquals(LintAuditScope.None, controller.auditScope)
            assertEquals(0, controller.engine.cacheEntryCount())
        } finally {
            controller.close()
        }
    }

    @Test
    fun explicitCancelWhilePausedClearsPendingResume() = runBlocking {
        val store = DeckDocumentStore(syntheticDeck(slideCount = 6), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentDeckLintController(store)
        try {
            controller.startFullDeckAudit(scope = this, themeNames = listOf("dark"), sliceBudgetMs = 0.0)
            var steps = 0
            while (controller.auditedSlides < 1 && controller.isAuditing && steps < 100) {
                yield()
                steps++
            }
            assertTrue(controller.isAuditing)

            controller.pauseForPresenting()
            assertTrue(controller.hasPausedAudit)

            controller.cancelAudit()
            assertFalse(controller.hasPausedAudit, "Explicit cancel must clear pending resume")
            assertFalse(controller.isAuditing)

            val resumed = controller.resumeAfterPresenting(this)
            assertNull(resumed, "Cancelled paused audit must not resume on exit")
            assertFalse(controller.isPaused)
            assertFalse(controller.isAuditing)
            assertEquals(LintAuditScope.None, controller.auditScope)
        } finally {
            controller.close()
        }
    }

    @Test
    fun lintSlideNowAndStartFullDeckAuditAreGatedWhilePaused() = runBlocking {
        val store = DeckDocumentStore(syntheticDeck(slideCount = 4), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentDeckLintController(store)
        try {
            controller.pauseForPresenting()

            val skippedSlide = controller.lintSlideNow(slideIndex = 0, themeNames = listOf("dark"))
            assertTrue(skippedSlide.issues.isEmpty())
            assertEquals(0, skippedSlide.stats.slidesAudited)
            assertEquals(LintAuditScope.None, controller.auditScope)
            assertEquals(0, controller.engine.cacheEntryCount())

            val skippedDeckJob = controller.startFullDeckAudit(scope = this, themeNames = listOf("dark"))
            assertTrue(skippedDeckJob.isCompleted)
            assertFalse(controller.isAuditing)
            assertFalse(controller.hasPausedAudit)

            assertNull(controller.resumeAfterPresenting(this))
            assertFalse(controller.isPaused)

            val liveSlide = controller.lintSlideNow(slideIndex = 0, themeNames = listOf("dark"))
            assertEquals(1, liveSlide.stats.slidesAudited)
            assertEquals(LintAuditScope.Slide, controller.auditScope)
            assertTrue(liveSlide.issues.any { it.code == "LOW_CONTRAST" })
        } finally {
            controller.close()
        }
    }

    @Test
    fun closeClearsPendingResumeAndBlocksSubsequentRuns() = runBlocking {
        val store = DeckDocumentStore(syntheticDeck(slideCount = 6), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentDeckLintController(store)
        controller.startFullDeckAudit(scope = this, themeNames = listOf("dark"), sliceBudgetMs = 0.0)
        var steps = 0
        while (controller.auditedSlides < 1 && controller.isAuditing && steps < 100) {
            yield()
            steps++
        }
        controller.pauseForPresenting()
        assertTrue(controller.hasPausedAudit)

        controller.close()
        assertFalse(controller.isAuditing)
        assertFalse(controller.isPaused)
        assertFalse(controller.hasPausedAudit)

        assertNull(controller.resumeAfterPresenting(this))
        val afterCloseJob = controller.startFullDeckAudit(scope = this, themeNames = listOf("dark"))
        assertTrue(afterCloseJob.isCompleted)
        assertFalse(controller.isAuditing)
        val afterCloseSlide = controller.lintSlideNow(slideIndex = 0, themeNames = listOf("dark"))
        assertEquals(0, afterCloseSlide.stats.slidesAudited)
    }

    @Test
    fun lintPanelGatesAutoAuditShowsPausedStatusAndAllowsCancellingPausedAudit() = runComposeUiTest {
        mainClock.autoAdvance = false
        val store = DeckDocumentStore(syntheticDeck(slideCount = 6), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentDeckLintController(store)
        controller.pauseForPresenting()
        lateinit var panelScope: CoroutineScope

        try {
            setContent {
                panelScope = rememberCoroutineScope()
                CiacolaJewelTheme(isDark = true) {
                    Box(Modifier.size(960.dp, 320.dp)) {
                        DocumentDeckLintPanel(
                            controller = controller,
                            currentSlideIndex = 0,
                            autoAuditCurrentSlide = true,
                            auditScope = panelScope,
                        )
                    }
                }
            }
            repeat(3) { mainClock.advanceTimeByFrame() }

            // Auto-audit must not run while paused; actions are disabled and status banner is visible.
            assertEquals(LintAuditScope.None, controller.auditScope)
            assertEquals(0, controller.engine.cacheEntryCount())
            onNodeWithTag("ciacola-doc-lint-paused-banner", useUnmergedTree = true).assertExists()
            onNodeWithTag("ciacola-doc-lint-audit-slide", useUnmergedTree = true).assertIsNotEnabled()
            onNodeWithTag("ciacola-doc-lint-audit-all", useUnmergedTree = true).assertIsNotEnabled()

            // Unpausing enables auto-audit of the current slide and clears the paused banner.
            runOnIdle { controller.resumeAfterPresenting(panelScope) }
            repeat(4) { mainClock.advanceTimeByFrame() }
            assertEquals(LintAuditScope.Slide, controller.auditScope)
            onNodeWithTag("ciacola-doc-lint-paused-banner", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("ciacola-doc-lint-audit-slide", useUnmergedTree = true).assertIsEnabled()

            // Starting a full-deck audit then pausing lets the user cancel the paused audit from the toolbar.
            runOnIdle {
                controller.startFullDeckAudit(scope = panelScope, themeNames = listOf("dark"), sliceBudgetMs = 0.0)
            }
            repeat(2) { mainClock.advanceTimeByFrame() }
            assertTrue(controller.isAuditing)

            runOnIdle { controller.pauseForPresenting() }
            repeat(2) { mainClock.advanceTimeByFrame() }
            assertTrue(controller.hasPausedAudit)
            onNodeWithTag("ciacola-doc-lint-paused-banner", useUnmergedTree = true).assertExists()
            onNodeWithTag("ciacola-doc-lint-audit-all", useUnmergedTree = true).assertIsEnabled()
            onNodeWithTag("ciacola-doc-lint-audit-all", useUnmergedTree = true).performClick()
            repeat(2) { mainClock.advanceTimeByFrame() }
            assertFalse(controller.hasPausedAudit)
            onNodeWithTag("ciacola-doc-lint-audit-all", useUnmergedTree = true).assertIsNotEnabled()
        } finally {
            controller.close()
        }
    }
}
