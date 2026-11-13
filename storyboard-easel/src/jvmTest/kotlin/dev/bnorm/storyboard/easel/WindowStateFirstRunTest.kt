package dev.bnorm.storyboard.easel

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class WindowStateFirstRunTest {

    private suspend fun ComposeUiTest.settle() {
        repeat(20) {
            waitForIdle()
            withContext(Dispatchers.Default) { delay(15) }
        }
        waitForIdle()
    }

    @Test
    fun firstRunStoryWindowIs16By9() {
        val dir = Files.createTempDirectory("window-first-run")
        try {
            runComposeUiTest {
                var state: WindowState? = null
                setContent {
                    CompositionLocalProvider(LocalStoryboardDir provides dir) {
                        state = rememberWindowState(fileName = "Story", defaultSize = DefaultStoryWindowSize)
                    }
                }
                settle()
                assertEquals(DpSize(1280.dp, 720.dp), state?.size, "First-run Story window must be 16:9")
                assertTrue(dir.resolve("story.json").exists(), "The Story window persists its state")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun neverOpenedSecondaryWindowDoesNotWriteItsStateFile() {
        val dir = Files.createTempDirectory("window-lazy-persist")
        try {
            runComposeUiTest {
                var opened by mutableStateOf(false)
                var state: WindowState? = null
                setContent {
                    CompositionLocalProvider(LocalStoryboardDir provides dir) {
                        state = rememberWindowState(
                            fileName = "Timeline",
                            defaultSize = DpSize(1280.dp, 800.dp),
                            persist = opened,
                        )
                    }
                }
                settle()
                assertEquals(DpSize(1280.dp, 800.dp), state?.size)
                assertFalse(dir.resolve("timeline.json").exists(), "A never-opened window must not write its state")

                opened = true
                settle()
                assertTrue(dir.resolve("timeline.json").exists(), "An opened window persists its state")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
