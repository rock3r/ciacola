package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Measures real wall-clock deck switch latency (decode + lock off the UI thread, then the first
 * fully composed and drawn frame of the new deck, including SkSL compilation for shader slides and
 * Studio controller/shell initialization).
 *
 * Timings are printed so they can be quoted; the assertion guards the `< 1 s` budget.
 */
class DeckSwitchLatencyTest {

    private data class Measured(val deckId: String, val preOpenMs: Long, val firstFrameMs: Long)

    private fun measureSwitch(scene: ImageComposeScene, setDeck: (ActiveDeckSession) -> Unit, target: DeckEntry, token: Int): Measured {
        val recovery = Files.createTempDirectory("ciacola-switch-latency-recovery-")
        val start = System.nanoTime()
        val session = runBlocking { preOpenDeckSession(target, recovery, token, decksDirProperty = null) }
        val preOpenDone = System.nanoTime()
        setDeck(session)
        // One render composes the swapped deck, Studio chrome, lays it out, and draws it (Skia compiles SkSL here).
        scene.render(nanoTime = preOpenDone)
        val firstFrame = System.nanoTime()
        // A second frame catches any work deferred to the frame after composition (effects, shaders).
        scene.render(nanoTime = firstFrame + 16_000_000L)
        return Measured(
            deckId = target.id,
            preOpenMs = (preOpenDone - start) / 1_000_000L,
            firstFrameMs = (System.nanoTime() - start) / 1_000_000L,
        )
    }

    @Test
    fun switchingToEveryDeckIncludingShadersQuickieDrawsWithinOneSecond() {
        val recovery = Files.createTempDirectory("ciacola-switch-latency-initial-")
        val initial = runBlocking { preOpenDeckSession(DeckCatalog.foundation, recovery, 0, decksDirProperty = null) }
        var active by mutableStateOf(initial)
        val scene = ImageComposeScene(width = 1440, height = 810, density = Density(1f)) {
            val session = active
            val scope = rememberCoroutineScope()
            CiacolaJewelTheme(isDark = true) {
                key(session.entry.id, session.loadToken) {
                    val render = remember(session) { RenderSession.forPresent(store = session.store, freeClockMillis = { 0L }) }
                    val config = remember(session) {
                        DocumentRenderConfig(themeName = session.store.document.defaultTheme, specimens = session.entry.specimens)
                    }
                    val storyboard = rememberDocumentStoryboard(session = render, config = config)
                    val animatic = rememberAnimatic(storyboard = { storyboard })
                    val timelineController = remember(session) {
                        DocumentTimelineController(
                            store = session.store,
                            specimens = session.entry.specimens,
                        )
                    }
                    LaunchedEffect(timelineController) {
                        timelineController.refreshSnapshot()
                    }
                    val elementController = remember(session) {
                        DocumentElementInspectorController(
                            store = session.store,
                            specimens = session.entry.specimens,
                        )
                    }
                    val themeController = remember(session) {
                        DocumentThemeInspectorController(store = session.store)
                    }
                    val lintController = remember(session) {
                        DocumentDeckLintController(
                            store = session.store,
                            specimens = session.entry.specimens,
                        )
                    }
                    DisposableEffect(lintController) {
                        onDispose { lintController.close() }
                    }
                    val workspace = remember(session) {
                        StudioWorkspaceController(
                            store = session.store,
                            timelineController = timelineController,
                            elementController = elementController,
                            themeController = themeController,
                            lintController = lintController,
                        )
                    }
                    StudioShell(
                        workspace = workspace,
                        appBar = {
                            StudioAppBarHost(
                                workspace = workspace,
                                breadcrumb = session.entry.title,
                                presenterOpen = false,
                                onTogglePresenter = {},
                                deckSwitcher = {},
                            )
                        },
                        themeSheet = { StudioThemeSheet(workspace, onPickDeckFileToCopyThemes = null) },
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                Easel(animatic)
                            }
                            StudioUnifiedInspectorPane(
                                workspace = workspace,
                                animatic = animatic,
                                scope = scope,
                            )
                        }
                    }
                }
            }
        }
        try {
            scene.render(nanoTime = 0L)
            val order = listOf(
                DeckCatalog.shadersQuickie2025,
                DeckCatalog.foundation,
                DeckCatalog.jewelTalk2024,
                DeckCatalog.notJustPlainText2024,
                DeckCatalog.shadersQuickie2025,
            )
            val results = order.mapIndexed { i, entry -> measureSwitch(scene, { active = it }, entry, token = i + 1) }
            results.forEach { println("deck-switch-latency ${it.deckId}: preOpen=${it.preOpenMs}ms firstFrame=${it.firstFrameMs}ms") }
            for (result in results) {
                assertTrue(
                    result.firstFrameMs < 1_000L,
                    "Switching to ${result.deckId} must draw its first frame within 1 s (took ${result.firstFrameMs} ms)",
                )
            }
        } finally {
            scene.close()
        }
    }
}
