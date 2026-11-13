package dev.sebastiano.ciacola.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import java.nio.file.Files
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ReviewChromeWhilePresentingTest {

    /** Fullscreen presenting is audience-facing: the review HUD (and pins) stay off it unless reviewing. */
    @Test
    fun reviewHudHidesWhilePresentingFullscreenUnlessInspecting() = runComposeUiTest {
        val dir = Files.createTempDirectory("ciacola-review-presenting")
        val store = ReviewCommentStore(storyboardDir = dir.resolve(".storyboard"), workspaceRoot = dir)
        var fullscreen by mutableStateOf(false)
        lateinit var coordinator: ReviewStageCoordinator
        setContent {
            coordinator = rememberReviewStageCoordinator()
            val deck = remember { createFoundationDeck(stageDecorator = coordinator.stageDecorator) }
            val animatic = rememberAnimatic(storyboard = { deck })
            val window = rememberSlideReviewWindow(animatic = animatic, store = store, stageCoordinator = coordinator)
            CompositionLocalProvider(LocalPresentingFullscreen provides fullscreen) {
                window.decorator.decorate {
                    Box(Modifier.fillMaxSize()) { Easel(animatic) }
                }
            }
        }
        fun hudShown() = onAllNodesWithTag("ciacola-hud-toggle-inspect").fetchSemanticsNodes().isNotEmpty()

        waitUntil(conditionDescription = "HUD shown while authoring windowed", timeoutMillis = 5_000) { hudShown() }
        runOnIdle { fullscreen = true }
        waitUntil(conditionDescription = "HUD hidden while presenting fullscreen", timeoutMillis = 5_000) { !hudShown() }
        runOnIdle { coordinator.inspectModeEnabled = true }
        waitUntil(conditionDescription = "HUD back while inspecting in fullscreen", timeoutMillis = 5_000) { hudShown() }
    }
}
