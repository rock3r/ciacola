package dev.sebastiano.ciacola

import androidx.compose.material.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.easel.resolveDefaultStoryboardDir
import dev.bnorm.storyboard.easel.resolveStoryboardWorkspaceRoot
import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.ReviewTargetHit
import dev.sebastiano.ciacola.review.SlideReviewComment
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RepoRootWorkingDirTest {
    @Test
    fun animaticPersistenceWalksUpFromModuleSubdirectoryToRepositoryRootStoryboardDir() = runComposeUiTest {
        val fakeRepoRoot = Files.createTempDirectory("ciacola-repo-root-test").toRealPath()
        fakeRepoRoot.resolve("settings.gradle.kts").writeText("rootProject.name = \"ciacola\"\n")
        fakeRepoRoot.resolve("gradlew").writeText("#!/bin/sh\n")
        val fakeDeckModuleDir = Files.createDirectories(fakeRepoRoot.resolve("deck"))
        val rootStoryboardDir = Files.createDirectories(fakeRepoRoot.resolve(".storyboard"))
        rootStoryboardDir.resolve("animatic.json").writeText("""{"sceneIndex":1,"frameIndex":0}""")

        val deck = Storyboard.build(title = "RepoRootWalkTest") {
            scene(frames = listOf("a")) { Text("Slide 1") }
            scene(frames = listOf("b")) { Text("Slide 2") }
        }

        val previousUserDir = System.getProperty("user.dir")
        try {
            System.setProperty("user.dir", fakeDeckModuleDir.toAbsolutePath().toString())
            lateinit var animatic: Animatic
            setContent {
                animatic = rememberAnimatic(fileName = "animatic", storyboard = { deck })
            }
            waitUntil(timeoutMillis = 3_000) {
                animatic.currentIndex == Storyboard.Index(1, 0)
            }
            assertEquals(Storyboard.Index(1, 0), animatic.currentIndex)
            assertFalse(
                Files.exists(fakeDeckModuleDir.resolve(".storyboard")),
                "Module subdirectory must not get a split deck/.storyboard folder",
            )
        } finally {
            if (previousUserDir != null) {
                System.setProperty("user.dir", previousUserDir)
            }
        }
    }

    @Test
    fun workspaceRootResolverAnchorsReviewStoreAndGradlewCommandAtRepoRoot() {
        val fakeRepoRoot = Files.createTempDirectory("ciacola-workspace-root-test").toRealPath()
        fakeRepoRoot.resolve("gradlew").writeText("#!/bin/sh\n")
        Files.createDirectories(fakeRepoRoot.resolve(".gradle-agent-home"))
        val fakeDeckSubdir = Files.createDirectories(fakeRepoRoot.resolve("deck/src/desktopMain"))

        val resolvedRoot = resolveStoryboardWorkspaceRoot(fakeDeckSubdir)
        val resolvedStoryboard = resolveDefaultStoryboardDir(fakeDeckSubdir)
        assertEquals(fakeRepoRoot, resolvedRoot)
        assertEquals(fakeRepoRoot.resolve(".storyboard"), resolvedStoryboard)

        val store = ReviewCommentStore(
            storyboardDir = resolvedStoryboard,
            workspaceRoot = resolvedRoot,
        )
        val msg = store.formatAgentMessage(
            listOf(
                SlideReviewComment(
                    id = "rc-root-1",
                    sceneIndex = 0,
                    frameIndex = 0,
                    sceneCount = 5,
                    frameCount = 3,
                    sceneHeader = "FOUNDATION",
                    stageX = 100f,
                    stageY = 100f,
                    target = ReviewTargetHit(kind = "Text", label = "Title", textSnippet = "Title"),
                    note = "Test root path",
                    status = ReviewCommentStatus.Queued,
                    createdAt = "2026-11-12T00:00:00Z",
                )
            )
        )
        assertTrue(msg.contains("in `$fakeRepoRoot`"), "Expected repo root in prompt, got:\n$msg")
        assertTrue(
            msg.contains("--gradle-user-home \"${fakeRepoRoot.resolve(".gradle-agent-home")}\""),
            "Expected root .gradle-agent-home in reload command, got:\n$msg",
        )
        assertFalse(msg.contains("$fakeRepoRoot/deck"), "Must not point agentapi at <repo>/deck:\n$msg")

        val standalone = Files.createTempDirectory("ciacola-standalone-app").toRealPath()
        assertEquals(standalone, resolveStoryboardWorkspaceRoot(standalone))
    }
}
