package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.authoring.DeckHostParams
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.StageDragPreview
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.design.DesignSystemStore
import dev.sebastiano.ciacola.inspector.element.ElementEditorController
import dev.sebastiano.ciacola.inspector.element.ElementOverridesStore
import dev.sebastiano.ciacola.inspector.element.rememberElementEditorController
import dev.sebastiano.ciacola.inspector.lint.DeckLintController
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.LocalDeckLoopClock
import dev.sebastiano.ciacola.timeline.TimelineOverridesStore
import dev.sebastiano.ciacola.timeline.TimelineStudioController
import dev.sebastiano.ciacola.timeline.TimelineTrackFilter
import dev.sebastiano.ciacola.timeline.TrackCategory
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioController
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioWindow
import dev.sebastiano.ciacola.ui.AuthoringTextInputFocusTracker
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
 * Verifies the D-12 / UX-04 / UX-05 / UX-14 / D-14 / D-17 Studio workspace geometry & controls:
 * - Hoisted right Studio Inspector spans full window height (>= 90% of container height) at 388.dp default.
 * - Inspector width persists to `.storyboard/studio-layout.properties` and clamps to `320..520.dp`.
 * - `All | Current slide` track filter toggle filters rows to the active slide and restores all rows.
 * - `NumericStepperRow` accepts typed numeric input via click-to-edit + Enter.
 * - `LoopSubCompInspector`'s `+` button clears the right scrollbar gutter (`>= 12.dp` inset).
 * - `elementController.editorActive` is active ONLY when `activeTab == Element` (stops blue selection chrome
 *   from sticking over `Theme` or `Lint`).
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
        val overridesStore = TimelineOverridesStore(storyboardDir = tmpDir)
        val elementStore = ElementOverridesStore(storyboardDir = tmpDir)
        val designStore = DesignSystemStore(storyboardDir = tmpDir)

        lateinit var animatic: Animatic
        lateinit var controller: TimelineStudioController
        lateinit var workspace: StudioWorkspaceController
        lateinit var elementController: ElementEditorController
        lateinit var scope: CoroutineScope

        try {
            setContent {
                scope = rememberCoroutineScope()
                val loopClock = remember { DeckLoopClock() }
                val inspectionRegistry = remember { StageInspectionRegistry() }
                val dragPreview = remember { StageDragPreview() }
                val interactiveRegistry = remember { StageInteractiveRegistry() }
                val textInputTracker = remember { AuthoringTextInputFocusTracker() }
                elementController = rememberElementEditorController(
                    overridesStore = elementStore,
                    inspectionRegistry = inspectionRegistry,
                )
                val lintController = remember { DeckLintController() }

                val storyboard = remember {
                    DeckCatalog.foundation.create(
                        DeckHostParams(
                            theme = DeckThemes.stageDark(),
                            bundledPreset = DeckThemePreset.StageDark,
                            timelineOverridesProvider = {
                                overridesStore.structuralRevision
                                overridesStore.parameterRevision
                                overridesStore.data()
                            },
                            elementOverridesProvider = {
                                elementStore.revision
                                elementStore.data()
                            },
                        ),
                    )
                }
                animatic = rememberAnimatic(
                    initialIndex = Storyboard.Index(0, 0),
                    storyboard = { storyboard },
                )
                controller = rememberTimelineStudioController(
                    animatic = animatic,
                    overridesStore = overridesStore,
                    loopClock = loopClock,
                ).also {
                    it.dockedInStoryWindow = true
                }
                workspace = remember(controller) {
                    StudioWorkspaceController(
                        timelineController = controller,
                        timelineOverridesStore = overridesStore,
                        designSystemStore = designStore,
                        elementOverridesStore = elementStore,
                        elementController = elementController,
                        lintController = lintController,
                        stageInspectionRegistry = inspectionRegistry,
                        initialInspectorWidthDp = loadPersistedInspectorWidthDp(tmpDir),
                    )
                }
                StudioWorkspaceSyncEffects(workspace)

                val studioWindow = rememberTimelineStudioWindow(
                    controller = controller,
                    trailingPane = {
                        StudioUnifiedInspectorPane(
                            workspace = workspace,
                            animatic = animatic,
                            currentPreset = DeckThemePreset.StageDark,
                            onSelectPreset = {},
                            baseThemeProvider = { DeckThemes.forPreset(it) },
                            storyboardForPreset = { storyboard },
                            scope = scope,
                        )
                    },
                )

                DisposableEffect(Unit) {
                    onDispose {
                        elementStore.close()
                        designStore.close()
                    }
                }

                CompositionLocalProvider(
                    LocalSceneMode provides SceneMode.Story,
                    LocalDeckLoopClock provides loopClock,
                    LocalStageDragPreview provides dragPreview,
                    LocalStageInteractiveRegistry provides interactiveRegistry,
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
            mainClock.advanceTimeBy(400)
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

            // 2. All | Current slide filter toggles visibleTracks() cleanly.
            val totalTracks = controller.snapshot.tracks.size
            assertTrue(totalTracks > 3, "Expected multiple tracks across Foundation Deck scenes")
            assertEquals(TimelineTrackFilter.All, controller.trackFilter)
            assertEquals(totalTracks, controller.visibleTracks().size)

            onNodeWithTag("ciacola-timeline-filter-current", useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(TimelineTrackFilter.CurrentSlide, controller.trackFilter)
            val currentSlideTracks = controller.visibleTracks()
            assertTrue(currentSlideTracks.isNotEmpty(), "Slide 1 must have at least one track")
            assertTrue(
                currentSlideTracks.size < totalTracks,
                "Current slide filter should narrow track list (${currentSlideTracks.size} vs $totalTracks)",
            )
            assertTrue(
                currentSlideTracks.all { row -> row.blocks.any { it.sceneIndex == 0 } },
                "Every track in CurrentSlide mode must belong to the active scene (0)",
            )

            onNodeWithTag("ciacola-timeline-filter-all", useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(TimelineTrackFilter.All, controller.trackFilter)
            assertEquals(totalTracks, controller.visibleTracks().size)

            // 3. NumericStepperRow typed-value input commits duration override cleanly.
            val nonLoopTrack = controller.snapshot.tracks.firstOrNull {
                it.category != TrackCategory.LoopSubComp && it.category != TrackCategory.StaggerGroup
            }
            assertNotNull(nonLoopTrack, "Expected a non-loop track in Foundation Deck")
            runOnIdle {
                controller.selectTrack(nonLoopTrack.trackId)
                workspace.activeTab = StudioInspectorTab.Motion
            }
            waitForIdle()

            onNodeWithTag("ciacola-inspector-dur-dec-value").performClick()
            waitForIdle()
            onNodeWithTag("ciacola-inspector-dur-dec-input").performTextReplacement("450")
            onNodeWithTag("ciacola-inspector-dur-dec-input").performKeyInput {
                pressKey(Key.Enter)
            }
            waitForIdle()
            assertEquals(
                450,
                overridesStore.data().tracks[nonLoopTrack.trackId]?.durationMs,
                "Typed duration value in NumericStepperRow must commit to TimelineOverridesStore",
            )

            // 4. LoopSubCompInspector '+' button has >= 12.dp right gutter clearance (end = 16.dp).
            val loopTrack = controller.snapshot.tracks.firstOrNull { it.category == TrackCategory.LoopSubComp }
            assertNotNull(loopTrack, "Expected LoopSubComp track in Foundation Deck")
            val loopBlock = loopTrack.blocks.first()
            val loopId = assertNotNull(loopBlock.loopId)
            runOnIdle {
                controller.enterLoopSubComp(scope, loopId, loopBlock.sceneIndex)
            }
            waitForIdle()
            val incBounds = onNodeWithTag("ciacola-loop-period-inc").getUnclippedBoundsInRoot()
            val rightClearanceDp = (inspectorBounds.right - incBounds.right).value
            assertTrue(
                rightClearanceDp >= 12f,
                "Expected LoopSubCompInspector '+' button to clear scrollbar gutter (>=12dp), got ${rightClearanceDp}dp",
            )
            runOnIdle { controller.exitLoopSubComp() }
            waitForIdle()

            // 5. Element editor chrome is active ONLY on the Element tab (D-14).
            runOnIdle { workspace.activeTab = StudioInspectorTab.Element }
            waitForIdle()
            assertTrue(elementController.editorActive, "editorActive must be true on Element tab")

            runOnIdle { workspace.activeTab = StudioInspectorTab.Theme }
            waitForIdle()
            assertFalse(elementController.editorActive, "editorActive must be false on Theme tab")

            runOnIdle { workspace.activeTab = StudioInspectorTab.Lint }
            waitForIdle()
            assertFalse(elementController.editorActive, "editorActive must be false on Lint tab")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
