package dev.sebastiano.ciacola.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.rememberAnimatic
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalTestApi::class)
class ReviewPollingWhilePresentingTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun comment(id: String, note: String = "Check contrast"): SlideReviewComment = SlideReviewComment(
        id = id,
        sceneIndex = 0,
        frameIndex = 0,
        sceneCount = 1,
        frameCount = 1,
        sceneTitle = "Slide 1",
        sceneHeader = "Slide 1",
        themePreset = "StageDark",
        stageX = 160f,
        stageY = 120f,
        target = ReviewTargetHit(kind = "Text", label = "Title", textSnippet = "Title"),
        note = note,
        status = ReviewCommentStatus.Queued,
        createdAt = "2026-11-27T00:00:00Z",
    )

    private fun writeCommentsExternally(file: Path, comments: List<SlideReviewComment>, mtimeBumpMs: Long) {
        Files.createDirectories(file.parent)
        file.writeText(json.encodeToString(comments))
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + mtimeBumpMs))
    }

    private fun writeClaimsExternally(file: Path, ids: List<String>, mtimeBumpMs: Long) {
        Files.createDirectories(file.parent)
        ReviewClaims.write(file, ids.map { ReviewClaim(id = it) })
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + mtimeBumpMs))
    }

    @Test
    fun diskAndClaimsPollingPausesDuringFullscreenAndRefreshesImmediatelyOnExit() = runComposeUiTest {
        mainClock.autoAdvance = false
        val tempDir = Files.createTempDirectory("ciacola-review-poll-fullscreen-")
        try {
            val storyboardDir = tempDir.resolve(".storyboard")
            val store = ReviewCommentStore(
                storyboardDir = storyboardDir,
                workspaceRoot = tempDir,
                envProvider = { null },
                systemPropertyProvider = { null },
            )
            var fullscreen by mutableStateOf(false)

            setContent {
                val coordinator = rememberReviewStageCoordinator()
                val deck = remember {
                    Storyboard.build(title = "ReviewPolling") {
                        scene(frames = listOf(0)) { Text("Slide 1") }
                    }
                }
                val animatic = rememberAnimatic(storyboard = { deck })
                val window = rememberSlideReviewWindow(
                    animatic = animatic,
                    store = store,
                    stageCoordinator = coordinator,
                )
                CompositionLocalProvider(LocalPresentingFullscreen provides fullscreen) {
                    window.decorator.decorate {
                        Box(Modifier.fillMaxSize()) { Easel(animatic) }
                    }
                }
            }
            mainClock.advanceTimeByFrame()

            // Baseline: normal editing polls every 250ms and loads new comments.
            writeCommentsExternally(store.commentsFile, listOf(comment("rc-1")), mtimeBumpMs = 2_000L)
            mainClock.advanceTimeBy(300L)
            mainClock.advanceTimeByFrame()
            assertEquals(listOf("rc-1"), store.comments.map { it.id })

            // Enter fullscreen presenting: polling stops.
            runOnIdle { fullscreen = true }
            mainClock.advanceTimeByFrame()

            writeCommentsExternally(
                store.commentsFile,
                listOf(comment("rc-1"), comment("rc-2", "Added while presenting")),
                mtimeBumpMs = 4_000L,
            )
            writeClaimsExternally(store.claimsFile, listOf("rc-1"), mtimeBumpMs = 4_000L)

            mainClock.advanceTimeBy(1_000L)
            mainClock.advanceTimeByFrame()
            assertEquals(listOf("rc-1"), store.comments.map { it.id }, "Comments must not poll while fullscreen presenting")
            assertTrue(store.claimedIds.isEmpty(), "Claims must not poll while fullscreen presenting")

            // Exit fullscreen presenting: one immediate refresh catches up before the next 250ms tick.
            runOnIdle { fullscreen = false }
            mainClock.advanceTimeByFrame()
            assertEquals(
                listOf("rc-1", "rc-2"),
                store.comments.map { it.id },
                "Exiting fullscreen presenting must immediately refresh comments from disk",
            )
            assertEquals(
                listOf("rc-1"),
                store.claimedIds.toList(),
                "Exiting fullscreen presenting must immediately refresh claims from disk",
            )

            // Ongoing 250ms polling resumes normally after exiting presenting.
            writeCommentsExternally(
                store.commentsFile,
                listOf(comment("rc-1"), comment("rc-2"), comment("rc-3")),
                mtimeBumpMs = 6_000L,
            )
            mainClock.advanceTimeBy(300L)
            mainClock.advanceTimeByFrame()
            assertEquals(listOf("rc-1", "rc-2", "rc-3"), store.comments.map { it.id })
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun diskPollingPausesForWindowedPresentLocalAndCoordinatorAudienceProvider() = runComposeUiTest {
        mainClock.autoAdvance = false
        val tempDir = Files.createTempDirectory("ciacola-review-poll-audience-")
        try {
            val storyboardDir = tempDir.resolve(".storyboard")
            val store = ReviewCommentStore(
                storyboardDir = storyboardDir,
                workspaceRoot = tempDir,
                envProvider = { null },
                systemPropertyProvider = { null },
            )
            var audienceLocal by mutableStateOf(false)
            var presenterOpen by mutableStateOf(false)
            lateinit var coordinator: ReviewStageCoordinator

            setContent {
                coordinator = rememberReviewStageCoordinator().apply {
                    isAudiencePresentingProvider = { presenterOpen }
                }
                val deck = remember {
                    Storyboard.build(title = "ReviewPollingAudience") {
                        scene(frames = listOf(0)) { Text("Slide 1") }
                    }
                }
                val animatic = rememberAnimatic(storyboard = { deck })
                val window = rememberSlideReviewWindow(
                    animatic = animatic,
                    store = store,
                    stageCoordinator = coordinator,
                )
                CompositionLocalProvider(LocalAudiencePresenting provides audienceLocal) {
                    window.decorator.decorate {
                        Box(Modifier.fillMaxSize()) { Easel(animatic) }
                    }
                }
            }
            mainClock.advanceTimeByFrame()

            // 1. Windowed Present mode (LocalAudiencePresenting = true) pauses polling.
            runOnIdle { audienceLocal = true }
            mainClock.advanceTimeByFrame()
            writeCommentsExternally(store.commentsFile, listOf(comment("rc-windowed")), mtimeBumpMs = 2_000L)
            mainClock.advanceTimeBy(750L)
            mainClock.advanceTimeByFrame()
            assertTrue(store.comments.isEmpty(), "Polling must stay paused while LocalAudiencePresenting is true")

            runOnIdle { audienceLocal = false }
            mainClock.advanceTimeByFrame()
            assertEquals(listOf("rc-windowed"), store.comments.map { it.id })

            // 2. Presenter View open (coordinator.isAudiencePresentingProvider = true) also pauses polling.
            runOnIdle { presenterOpen = true }
            mainClock.advanceTimeByFrame()
            writeCommentsExternally(
                store.commentsFile,
                listOf(comment("rc-windowed"), comment("rc-presenter")),
                mtimeBumpMs = 4_000L,
            )
            writeClaimsExternally(store.claimsFile, listOf("rc-presenter"), mtimeBumpMs = 4_000L)
            mainClock.advanceTimeBy(750L)
            mainClock.advanceTimeByFrame()
            assertEquals(listOf("rc-windowed"), store.comments.map { it.id }, "Polling must stay paused while Presenter View is open")
            assertTrue(store.claimedIds.isEmpty())

            // Closing Presenter View triggers an immediate refresh.
            runOnIdle { presenterOpen = false }
            mainClock.advanceTimeByFrame()
            assertEquals(listOf("rc-windowed", "rc-presenter"), store.comments.map { it.id })
            assertEquals(listOf("rc-presenter"), store.claimedIds.toList())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
