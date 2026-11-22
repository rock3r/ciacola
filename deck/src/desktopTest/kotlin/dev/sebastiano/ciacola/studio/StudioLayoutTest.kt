package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.DocumentTimelineTrackRef
import dev.sebastiano.ciacola.timeline.rememberDocumentTimelineWindow
import dev.sebastiano.ciacola.ui.AuthoringTextInputFocusTracker
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.LocalAuthoringTextInputTracker
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope

/**
 * Verifies the unified document-backed Studio workspace geometry & controls:
 * - Hoisted right Studio Inspector spans full window height (>= 90% of container height) at 388.dp default.
 * - Inspector width persists to `.storyboard/session.json` via [StudioSessionStore] and clamps to `320..520.dp`.
 * - Track search query narrows `visibleTracks()` cleanly and restores all rows when cleared.
 * - Duration updates commit cleanly onto [DeckDocumentStore] with unified undo.
 * - `workspace.isElementPickingEnabled` is active ONLY when `activeTab == Element`.
 */
@OptIn(ExperimentalTestApi::class)
class StudioLayoutTest {

    @Test
    fun inspectorWidthPersistenceRoundTripsAndClamps() {
        val tmpDir = Files.createTempDirectory("ciacola-studio-layout-prefs-")
        try {
            assertEquals(DEFAULT_STUDIO_INSPECTOR_WIDTH_DP, loadPersistedInspectorWidthDp(tmpDir))

            persistInspectorWidthDp(tmpDir, 442.5f)
            assertEquals(442.5f, loadPersistedInspectorWidthDp(tmpDir))

            persistInspectorWidthDp(tmpDir, 120f)
            assertEquals(MIN_STUDIO_INSPECTOR_WIDTH_DP, loadPersistedInspectorWidthDp(tmpDir))

            persistInspectorWidthDp(tmpDir, 900f)
            assertEquals(MAX_STUDIO_INSPECTOR_WIDTH_DP, loadPersistedInspectorWidthDp(tmpDir))
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun hoistedSidebarSpansFullWindowHeightAndControlsWorkEndToEnd() = runComposeUiTest {
        val tmpDir = Files.createTempDirectory("ciacola-studio-layout-ui-")
        val store = createLoadedTestDeckStore()

        lateinit var animatic: Animatic
        lateinit var timelineController: DocumentTimelineController
        lateinit var workspace: StudioWorkspaceController
        lateinit var scope: CoroutineScope

        try {
            setContent {
                scope = rememberCoroutineScope()
                val textInputTracker = remember { AuthoringTextInputFocusTracker() }
                val session = remember { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
                val storyboard = rememberDocumentStoryboard(
                    session = session,
                    config = DocumentRenderConfig(
                        themeName = "dark",
                        specimens = AllDocumentDecks.foundation.specimens,
                    ),
                )
                animatic = rememberAnimatic(
                    initialIndex = Storyboard.Index(0, 0),
                    storyboard = { storyboard },
                )
                timelineController = remember {
                    DocumentTimelineController(
                        store = store,
                        specimens = AllDocumentDecks.foundation.specimens,
                    )
                }
                val elementController = remember {
                    DocumentElementInspectorController(
                        store = store,
                        specimens = AllDocumentDecks.foundation.specimens,
                    )
                }
                val themeController = remember { DocumentThemeInspectorController(store = store) }
                val lintController = remember {
                    DocumentDeckLintController(
                        store = store,
                        specimens = AllDocumentDecks.foundation.specimens,
                    )
                }
                workspace = remember(timelineController) {
                    StudioWorkspaceController(
                        store = store,
                        timelineController = timelineController,
                        elementController = elementController,
                        themeController = themeController,
                        lintController = lintController,
                        isTextInputActiveProvider = { textInputTracker.hasFocusedTextInput },
                        initialInspectorWidthDp = loadPersistedInspectorWidthDp(tmpDir),
                    )
                }
                val studioWindow = rememberDocumentTimelineWindow(
                    controller = timelineController,
                    initialDocked = true,
                    trailingPane = {
                        CiacolaJewelTheme(isDark = true) {
                            StudioUnifiedInspectorPane(
                                workspace = workspace,
                                animatic = animatic,
                                scope = scope,
                            )
                        }
                    },
                )
                workspace.timelineWindow = studioWindow
                StudioWorkspaceSyncEffects(
                    workspace = workspace,
                    activeSlideId = store.document.slides.filterNot { it.skipped }.getOrNull(animatic.presentedIndex.sceneIndex)?.id,
                    activeBeatIndex = animatic.presentedIndex.frameIndex.coerceAtLeast(0),
                )

                CompositionLocalProvider(
                    LocalSceneMode provides SceneMode.Story,
                    LocalAuthoringTextInputTracker provides textInputTracker,
                ) {
                    Box(Modifier.size(1360.dp, 840.dp)) {
                        studioWindow.decorator.decorate {
                            Easel(animatic)
                        }
                    }
                }
            }
            waitForIdle()
            mainClock.advanceTimeBy(200)
            waitForIdle()

            // 1. Hoisted right inspector spans full window height (>= 90% of 840.dp) at 388.dp width.
            val inspectorBounds = onNodeWithTag("ciacola-studio-unified-inspector").getUnclippedBoundsInRoot()
            val inspectorHeightDp = (inspectorBounds.bottom - inspectorBounds.top).value
            val inspectorWidthDp = (inspectorBounds.right - inspectorBounds.left).value
            assertTrue(
                inspectorHeightDp >= 840f * 0.9f,
                "Expected hoisted right inspector to span >=90% of 840.dp window height, got ${inspectorHeightDp}dp",
            )
            assertTrue(
                abs(inspectorWidthDp - DEFAULT_STUDIO_INSPECTOR_WIDTH_DP) <= 2f,
                "Expected default inspector width ~${DEFAULT_STUDIO_INSPECTOR_WIDTH_DP}dp, got ${inspectorWidthDp}dp",
            )

            // 2. Track search query filters tracks cleanly and restores all rows when cleared.
            fun currentFilteredTracks() = timelineController.snapshot.snapshot.tracks.filter { row ->
                val q = timelineController.trackSearchQuery.trim()
                q.isEmpty() || row.label.contains(q, ignoreCase = true) || row.trackId.contains(q, ignoreCase = true)
            }
            val totalTracks = timelineController.snapshot.snapshot.tracks.size
            assertTrue(totalTracks > 3, "Expected multiple tracks across Foundation Deck scenes")
            assertEquals(totalTracks, currentFilteredTracks().size)

            runOnIdle { timelineController.trackSearchQuery = "s5:stamp" }
            waitForIdle()
            val filtered = currentFilteredTracks()
            assertTrue(filtered.isNotEmpty() && filtered.size < totalTracks)

            runOnIdle { timelineController.trackSearchQuery = "" }
            waitForIdle()
            assertEquals(totalTracks, currentFilteredTracks().size)

            // 3. Selecting a build track mounts duration slider; committing a duration edit updates DeckDocumentStore.
            val buildRef = timelineController.snapshot.trackRefsByTrackId.values
                .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
                .firstOrNull()
            assertNotNull(buildRef, "Expected at least one SlideBuildTrack in Foundation Deck")
            runOnIdle {
                timelineController.selectTrack(buildRef.trackId)
                workspace.activeTab = StudioInspectorTab.Animate
            }
            waitForIdle()
            onNodeWithTag("ciacola-scrub-field-track-duration").assertIsDisplayed()

            val revBefore = store.revision
            runOnIdle {
                timelineController.resizeTrackBarDuration(buildRef, 620)
            }
            waitForIdle()
            assertTrue(store.revision > revBefore, "Duration edit must commit onto DeckDocumentStore")
            runOnIdle { assertTrue(workspace.undo()) }
            waitForIdle()

            // 4. Loop period slider mounts cleanly when selecting the Foundation ambient mesh loop track.
            val loopRef = timelineController.snapshot.trackRefsByTrackId.values
                .filterIsInstance<DocumentTimelineTrackRef.LoopTrackBar>()
                .firstOrNull()
            assertNotNull(loopRef, "Expected LoopTrackBar in Foundation Deck")
            runOnIdle {
                timelineController.selectLoop(loopRef.loopId)
                workspace.activeTab = StudioInspectorTab.Animate
            }
            waitForIdle()
            onNodeWithTag("ciacola-loop-period-slider-${loopRef.loopId}").assertIsDisplayed()

            // 5. Stage picking follows the selection on both tabs in Edit mode, and never while reviewing.
            onNodeWithTag("ciacola-studio-tab-design").performClick()
            waitForIdle()
            assertTrue(workspace.isElementPickingEnabled, "isElementPickingEnabled must be true on Design tab")

            onNodeWithTag("ciacola-studio-tab-animate").performClick()
            waitForIdle()
            assertTrue(workspace.isElementPickingEnabled, "Stage selection drives Animate too (selection sync)")

            runOnIdle { workspace.switchMode(StudioMode.Review) }
            waitForIdle()
            assertFalse(workspace.isElementPickingEnabled, "isElementPickingEnabled must be false in Review mode")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
