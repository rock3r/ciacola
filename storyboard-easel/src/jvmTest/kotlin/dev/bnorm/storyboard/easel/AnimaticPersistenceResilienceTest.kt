package dev.bnorm.storyboard.easel

import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.bnorm.storyboard.Storyboard
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class AnimaticPersistenceResilienceTest {

    private val deck = Storyboard.build(title = "PersistDeck") {
        scene(frames = listOf("A0", "A1")) { Text("Scene 0") }
        scene(frames = listOf("B0", "B1", "B2")) { Text("Scene 1") }
    }

    private fun restore(savedJson: String): Pair<Storyboard.Index, String> {
        val dir = Files.createTempDirectory("animatic-persist")
        val file = dir.resolve("animatic.json")
        file.writeText(savedJson)
        try {
            var result: Storyboard.Index? = null
            runComposeUiTest {
                lateinit var animatic: Animatic
                setContent {
                    CompositionLocalProvider(LocalStoryboardDir provides dir) {
                        animatic = rememberAnimatic(fileName = "animatic", storyboard = { deck })
                    }
                }
                settle()
                result = animatic.currentIndex
            }
            return checkNotNull(result) to file.readText()
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun ComposeUiTest.settle() {
        repeat(20) {
            waitForIdle()
            withContext(Dispatchers.Default) { delay(15) }
        }
        waitForIdle()
    }

    @Test
    fun unknownKeysInAnimaticJsonDoNotResetTheSavedPosition() {
        val (index, saved) = restore("""{"schemaVersion":2,"sceneIndex":1,"frameIndex":1}""")
        assertEquals(Storyboard.Index(1, 1), index, "A newer animatic.json with extra keys must restore its position")
        assertTrue("\"sceneIndex\":1" in saved && "\"frameIndex\":1" in saved, "Saved position must not be clobbered: $saved")
    }

    @Test
    fun outOfRangePositionClampsIntoTheStoryboard() {
        assertEquals(Storyboard.Index(1, 2), restore("""{"sceneIndex":7,"frameIndex":9}""").first)
        assertEquals(Storyboard.Index(1, 2), restore("""{"sceneIndex":1,"frameIndex":42}""").first)
        assertEquals(Storyboard.Index(0, 0), restore("""{"sceneIndex":-3,"frameIndex":-1}""").first)
    }

    @Test
    fun corruptAnimaticJsonStartsAtTheBeginningWithoutThrowing() {
        assertEquals(Storyboard.Index(0, 0), restore("{not json").first)
    }
}
