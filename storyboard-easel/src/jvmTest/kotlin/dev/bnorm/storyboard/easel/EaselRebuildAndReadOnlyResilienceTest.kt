package dev.bnorm.storyboard.easel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.overview.EaselOverview
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class EaselRebuildAndReadOnlyResilienceTest {
    private fun buildDeck(gen: Int): Storyboard = Storyboard.build(title = "Resilience-$gen") {
        scene(frames = listOf("1a", "1b")) { Text("Slide 1 ($gen)") }
        scene(frames = listOf("2a")) { Text("Slide 2 ($gen)") }
        scene(frames = listOf("3a")) { Text("Slide 3 ($gen)") }
    }

    @Test
    fun rebuildingStoryboardPreservesOverviewSelectionAndPrunesSaveableStateHolder() = runComposeUiTest {
        var generation by mutableIntStateOf(0)
        var weakFirst: WeakReference<Storyboard>? = null
        lateinit var animatic: Animatic
        lateinit var uiScope: CoroutineScope

        setContent {
            uiScope = rememberCoroutineScope()
            val sb = remember(generation) {
                buildDeck(generation).also { built ->
                    if (generation == 0) weakFirst = WeakReference(built)
                }
            }
            animatic = rememberAnimatic(storyboard = { sb })
            Box(Modifier.size(800.dp, 600.dp)) {
                EaselOverview(animatic) {
                    Easel(animatic)
                }
            }
        }
        waitForIdle()

        runOnIdle {
            uiScope.launch { animatic.jumpTo(Storyboard.Index(2, 0)) }
        }
        waitUntil(timeoutMillis = 3_000) { animatic.currentIndex == Storyboard.Index(2, 0) }

        // Open Overview via Escape.
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        // Rebuild storyboard while Overview is open (e.g. theme switch or timeline override edit).
        runOnIdle { generation = 1 }
        waitForIdle()
        mainClock.advanceTimeBy(300)
        waitForIdle()

        assertEquals(
            Storyboard.Index(2, 0),
            animatic.currentIndex,
            "Overview rebuild while open must preserve current slide selection instead of resetting to 0,0",
        )

        // Close Overview via second Escape and rebuild again so generation 0 is completely superseded.
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        runOnIdle { generation = 2 }
        waitForIdle()

        repeat(4) {
            System.gc()
            Thread.sleep(30)
        }
        assertNull(
            weakFirst?.get(),
            "Superseded Storyboard instances must be pruned from SaveableStateHolder instead of leaking",
        )
    }

    @Test
    fun readOnlyParentDirectoryDoesNotCrashOrHangWindowStateOrAnimaticPersistence() = runComposeUiTest {
        val readOnlyParent = Files.createTempDirectory("ciacola-readonly-parent")
        Files.setPosixFilePermissions(readOnlyParent, PosixFilePermissions.fromString("r-xr-xr-x"))
        val unwritableStoryboardDir = readOnlyParent.resolve(".storyboard")
        try {
            var observedWindowState: WindowState? = null
            lateinit var animatic: Animatic
            lateinit var uiScope: CoroutineScope
            val deck = buildDeck(0)

            setContent {
                uiScope = rememberCoroutineScope()
                CompositionLocalProvider(LocalStoryboardDir provides unwritableStoryboardDir) {
                    observedWindowState = rememberWindowState(fileName = "Story")
                    animatic = rememberAnimatic(fileName = "animatic", storyboard = { deck })
                    Box(Modifier.size(400.dp, 300.dp)) {
                        Easel(animatic)
                    }
                }
            }

            waitUntil(timeoutMillis = 3_000) { observedWindowState != null }
            assertNotNull(
                observedWindowState,
                "rememberWindowState must fall back to default WindowState when .storyboard cannot be created",
            )

            runOnIdle {
                uiScope.launch { animatic.jumpTo(Storyboard.Index(1, 0)) }
            }
            waitUntil(timeoutMillis = 3_000) { animatic.currentIndex == Storyboard.Index(1, 0) }
            assertEquals(Storyboard.Index(1, 0), animatic.currentIndex)
        } finally {
            runCatching {
                Files.setPosixFilePermissions(readOnlyParent, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }
}
