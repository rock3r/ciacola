package dev.bnorm.storyboard.easel

import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.bnorm.storyboard.Storyboard
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTestApi::class)
class AnimaticHotReloadPersistenceTest {
    @Test
    fun rebuildingStoryboardRetainsAndClampsCurrentIndexWithoutClobberingSavedFile() = runComposeUiTest {
        val fileKey = "hot-reload-test-${UUID.randomUUID()}"
        val savedPath = Paths.get(".storyboard", "${fileKey.lowercase()}.json")
        try {
            var reloadVersion by mutableIntStateOf(0)
            lateinit var latestAnimatic: Animatic
            lateinit var uiScope: CoroutineScope

            setContent {
                uiScope = rememberCoroutineScope()
                val deck = remember(reloadVersion) {
                    val secondSceneFrames = when (reloadVersion) {
                        0 -> listOf("B0", "B1", "B2")
                        1 -> listOf("B0", "B1", "B2-edited", "B3")
                        else -> listOf("B0", "B1") // shrank from 4 frames down to 2
                    }
                    Storyboard.build(title = "ReloadDeck-$reloadVersion") {
                        scene(frames = listOf("S0-0", "S0-1")) { Text("Scene 0") }
                        scene(frames = secondSceneFrames) { Text("Scene 1") }
                    }
                }
                latestAnimatic = rememberAnimatic(fileName = fileKey, storyboard = { deck })
            }

            suspend fun awaitSavedFileCondition(predicate: () -> Boolean) {
                val deadline = System.nanoTime() + 4_000_000_000L
                while (System.nanoTime() < deadline) {
                    waitForIdle()
                    if (predicate()) return
                    withContext(Dispatchers.Default) { delay(15) }
                }
                waitForIdle()
                assertTrue(predicate(), "Timed out waiting for saved index condition at $savedPath")
            }

            waitForIdle()
            awaitSavedFileCondition { true }
            // Give initial LaunchedEffect(fileName) IO load a moment to mark loaded=true.
            withContext(Dispatchers.Default) { delay(40) }
            waitForIdle()

            runOnIdle {
                uiScope.launch {
                    latestAnimatic.jumpTo(Storyboard.Index(sceneIndex = 1, frameIndex = 2))
                }
            }
            awaitSavedFileCondition {
                savedPath.exists() && savedPath.readText().contains("\"frameIndex\":2")
            }
            assertEquals(Storyboard.Index(sceneIndex = 1, frameIndex = 2), latestAnimatic.currentIndex)

            // Simulate a hot reload that edits frame content and adds B3 to Scene 1.
            runOnIdle { reloadVersion = 1 }
            waitForIdle()
            withContext(Dispatchers.Default) { delay(50) }
            waitForIdle()

            assertEquals(
                Storyboard.Index(sceneIndex = 1, frameIndex = 2),
                latestAnimatic.currentIndex,
                "Rebuilding Storyboard during hot reload must keep the active slide and build step",
            )
            assertTrue(
                savedPath.readText().contains("\"sceneIndex\":1") &&
                    savedPath.readText().contains("\"frameIndex\":2"),
                "Saved index file must stay at (1, 2), got: ${savedPath.readText()}",
            )

            // Simulate a second hot reload that removes B2/B3 from Scene 1; index must clamp to (1, 1).
            runOnIdle { reloadVersion = 2 }
            awaitSavedFileCondition {
                latestAnimatic.currentIndex == Storyboard.Index(sceneIndex = 1, frameIndex = 1) &&
                    savedPath.readText().contains("\"frameIndex\":1")
            }
            assertEquals(Storyboard.Index(sceneIndex = 1, frameIndex = 1), latestAnimatic.currentIndex)
        } finally {
            savedPath.deleteIfExists()
        }
    }
}
