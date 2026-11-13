package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.design.DesignSystemStore
import dev.sebastiano.ciacola.inspector.element.ElementEditorController
import dev.sebastiano.ciacola.inspector.element.ElementOverridesStore
import dev.sebastiano.ciacola.inspector.element.rememberElementEditorController
import dev.sebastiano.ciacola.inspector.lint.DeckLintController
import dev.sebastiano.ciacola.inspector.lint.DeckLintIssue
import dev.sebastiano.ciacola.inspector.lint.LintRuleId
import dev.sebastiano.ciacola.inspector.lint.LintSeverity
import dev.sebastiano.ciacola.inspector.lint.rememberDeckLintController
import dev.sebastiano.ciacola.inspector.lint.rememberDeckLintStageDecorator
import dev.sebastiano.ciacola.presenter.PresenterController
import dev.sebastiano.ciacola.presenter.PresenterWindow
import dev.sebastiano.ciacola.presenter.StageCurtain
import dev.sebastiano.ciacola.rememberHotReloadStoryboard
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.TextStyleTokenOverride
import dev.sebastiano.ciacola.theme.withOverrides
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.MotionPathPreviewState
import dev.sebastiano.ciacola.timeline.TimelineOverridesStore
import dev.sebastiano.ciacola.timeline.TimelineStageSelection
import dev.sebastiano.ciacola.timeline.TimelineStudioWindow
import dev.sebastiano.ciacola.timeline.rememberTimelineStageDecorator
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioController
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioWindow
import dev.sebastiano.ciacola.ui.AuthoringTextInputFocusTracker
import dev.sebastiano.ciacola.ui.LocalAuthoringTextInputTracker
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class EditorOverhaulIntegrationTest {

    @Test
    fun foundationDeckPassesFullDeckLintAuditWithZeroErrorsInStageDarkAndKeynoteLight() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-lint-foundation-")
        val storyboardDir = tempDir.resolve(".storyboard")
        val designStore = DesignSystemStore(storyboardDir = storyboardDir)
        val elementStore = ElementOverridesStore(storyboardDir = storyboardDir)
        val timelineStore = TimelineOverridesStore(storyboardDir = storyboardDir)

        lateinit var lintController: DeckLintController
        lateinit var uiScope: CoroutineScope
        lateinit var storyboardForPreset: (DeckThemePreset) -> Storyboard

        setContent {
            uiScope = rememberCoroutineScope()
            lintController = rememberDeckLintController()
            storyboardForPreset = remember {
                { targetPreset ->
                    val designOverrides = designStore.overridesFor(targetPreset)
                    createFoundationDeck(
                        theme = DeckThemes.forPreset(targetPreset).withOverrides(designOverrides),
                        bundledPreset = targetPreset,
                        includeKeynoteEffectsLab = true,
                        timelineOverrides = timelineStore.data(),
                        elementOverrides = elementStore.data(),
                        designOverrides = designOverrides,
                    )
                }
            }
            Box(Modifier.size(64.dp))
        }
        waitForIdle()

        var auditedIssues: List<DeckLintIssue>? = null
        runOnIdle {
            uiScope.launch {
                auditedIssues = lintController.runFullDeckAudit(
                    storyboard = storyboardForPreset(DeckThemePreset.StageDark),
                    presetsToCheck = listOf(DeckThemePreset.StageDark, DeckThemePreset.KeynoteLight),
                    storyboardForPreset = storyboardForPreset,
                )
            }
        }
        waitUntil(conditionDescription = "Full deck audit completes", timeoutMillis = 10_000) {
            auditedIssues != null && !lintController.isAuditing
        }

        val issues = checkNotNull(auditedIssues)
        val errors = issues.filter { it.severity == LintSeverity.Error }
        assertTrue(
            errors.isEmpty(),
            "Expected 0 lint errors across StageDark + KeynoteLight FoundationDeck, found:\n" +
                errors.joinToString("\n") { "${it.id} (${it.locationBadge}): ${it.title} — ${it.detail}" },
        )
    }

    @Test
    fun unifiedStudioWorkspacePropagatesThemeAndElementOverridesToStageAndLinterWithContextUndo() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-studio-unified-")
        val storyboardDir = tempDir.resolve(".storyboard")
        val reviewStore = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempDir)
        val timelineStore = TimelineOverridesStore(storyboardDir = storyboardDir)
        val designStore = DesignSystemStore(storyboardDir = storyboardDir)
        val elementStore = ElementOverridesStore(storyboardDir = storyboardDir)
        val textTracker = AuthoringTextInputFocusTracker()

        var preset by mutableStateOf(DeckThemePreset.StageDark)
        var fullscreen by mutableStateOf(false)

        lateinit var workspace: StudioWorkspaceController
        lateinit var lintController: DeckLintController
        lateinit var elementController: ElementEditorController
        lateinit var studioWindow: TimelineStudioWindow
        lateinit var presenterController: PresenterController
        lateinit var registry: StageInspectionRegistry
        lateinit var uiScope: CoroutineScope
        lateinit var storyboardForPreset: (DeckThemePreset) -> Storyboard

        setContent {
            uiScope = rememberCoroutineScope()
            registry = remember { StageInspectionRegistry() }
            elementController = rememberElementEditorController(
                overridesStore = elementStore,
                inspectionRegistry = registry,
            )
            lintController = rememberDeckLintController()

            val designRev = designStore.revision
            val elementRev = elementStore.revision
            val structuralRev = timelineStore.structuralRevision
            val paramRev = timelineStore.parameterRevision

            val designOverrides = remember(preset, designRev) { designStore.overridesFor(preset) }
            val hostTheme = remember(preset, designOverrides) {
                DeckThemes.forPreset(preset).withOverrides(designOverrides)
            }
            val elementOverrides = remember(elementRev) { elementStore.data() }
            val timelineOverrides = remember(structuralRev, paramRev) { timelineStore.data() }

            val loopClock = remember { DeckLoopClock() }
            val motionPath = remember { MotionPathPreviewState() }
            val stageSelection = remember { TimelineStageSelection() }
            val reviewCoordinator = rememberReviewStageCoordinator()
            var wsHolder by remember { mutableStateOf<StudioWorkspaceController?>(null) }

            val stageLoopDecorator = rememberTimelineStageDecorator(
                loopClock = loopClock,
                motionPathPreview = motionPath,
                overridesStore = timelineStore,
                stageSelection = stageSelection,
            )
            val lintDecorator = rememberDeckLintStageDecorator(
                controller = lintController,
                isInspectionVisible = { wsHolder?.isLinterVisible == true },
            )

            val sb = rememberHotReloadStoryboard(
                preset,
                structuralRev,
                paramRev,
                designRev,
                elementRev,
            ) {
                createFoundationDeck(
                    theme = hostTheme,
                    bundledPreset = preset,
                    includeKeynoteEffectsLab = true,
                    stageDecorator = stageLoopDecorator +
                        reviewCoordinator.stageDecorator +
                        elementController.stageDecorator +
                        lintDecorator,
                    timelineOverrides = timelineOverrides,
                    elementOverrides = elementOverrides,
                    designOverrides = designOverrides,
                )
            }

            storyboardForPreset = remember(timelineStore, elementStore, designStore) {
                { targetPreset ->
                    val targetDesign = designStore.overridesFor(targetPreset)
                    createFoundationDeck(
                        theme = DeckThemes.forPreset(targetPreset).withOverrides(targetDesign),
                        bundledPreset = targetPreset,
                        includeKeynoteEffectsLab = true,
                        timelineOverrides = timelineStore.data(),
                        elementOverrides = elementStore.data(),
                        designOverrides = targetDesign,
                    )
                }
            }

            val animatic = rememberAnimatic(storyboard = { sb })
            val timelineController = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = timelineStore,
                loopClock = loopClock,
                bakeDispatcher = reviewStore.asBakeDispatcher(),
                bakeWorkspaceRoot = tempDir,
                motionPathPreview = motionPath,
                stageSelection = stageSelection,
            )

            workspace = remember(timelineController) {
                StudioWorkspaceController(
                    timelineController = timelineController,
                    timelineOverridesStore = timelineStore,
                    designSystemStore = designStore,
                    elementOverridesStore = elementStore,
                    elementController = elementController,
                    lintController = lintController,
                    stageInspectionRegistry = registry,
                    isTextInputActiveProvider = { textTracker.hasFocusedTextInput },
                )
            }.also { wsHolder = it }

            SideEffect {
                elementController.currentSceneIndexProvider = { animatic.targetIndex.sceneIndex }
                elementController.currentBeatIndexProvider = { animatic.targetIndex.frameIndex }
                stageSelection.pickingSuppressed = {
                    workspace.isStudioOpen && workspace.activeTab == StudioInspectorTab.Element
                }
            }

            StudioWorkspaceSyncEffects(workspace)

            presenterController = remember(animatic) {
                PresenterController(
                    initialAnimatic = animatic,
                    isStudioOpenProvider = { workspace.isAnyAuthoringToolOpen },
                    isExternalTextInputActiveProvider = {
                        textTracker.hasFocusedTextInput || elementController.inlineEditingElementId != null
                    },
                    onEnterCleanShowModeAction = {
                        timelineController.dockedInStoryWindow = false
                        elementController.cancelInlineEdit(false)
                        elementController.selectElement(null)
                    },
                )
            }

            studioWindow = rememberTimelineStudioWindow(
                controller = timelineController,
                currentPresetProvider = { preset },
                suppressShortcutsProvider = { textTracker.hasFocusedTextInput },
                inspectorSlot = {
                    StudioUnifiedInspectorPane(
                        workspace = workspace,
                        animatic = animatic,
                        currentPreset = preset,
                        onSelectPreset = { preset = it },
                        baseThemeProvider = { DeckThemes.forPreset(it) },
                        storyboardForPreset = storyboardForPreset,
                        scope = uiScope,
                    )
                },
                extraPreviewKeyHandler = { ev, _ -> workspace.handleStudioPreviewKey(ev) },
            )

            val presenterWindow = remember(presenterController) {
                PresenterWindow(
                    controller = presenterController,
                    currentPresetProvider = { preset },
                )
            }

            CompositionLocalProvider(
                LocalAuthoringTextInputTracker provides textTracker,
                LocalPresentingFullscreen provides fullscreen,
            ) {
                MaterialTheme(colors = hostTheme.toMaterialColors()) {
                    Box(Modifier.size(1280.dp, 820.dp)) {
                        (presenterWindow.decorator + studioWindow.decorator).decorate {
                            Easel(animatic)
                        }
                    }
                }
            }
        }
        waitForIdle()

        // 1. Show Mode zero-leak guarantee: no editor or lint overlays when Studio is closed.
        assertTrue(onAllNodesWithTag("ciacola-direct-edit-overlay").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag("ciacola-lint-overlay").fetchSemanticsNodes().isEmpty())

        // 2. Dock Studio (F5) and verify unified Motion | Element | Theme | Lint tabs mount cleanly.
        runOnIdle { workspace.timelineController.dockedInStoryWindow = true }
        waitForIdle()
        onNodeWithTag("ciacola-studio-tab-motion").assertExists()
        onNodeWithTag("ciacola-studio-tab-element").assertExists()
        onNodeWithTag("ciacola-studio-tab-theme").assertExists()
        onNodeWithTag("ciacola-studio-tab-lint").assertExists()

        // 3. Switch to Theme tab, apply live Design System background & eyebrow typography override,
        //    and verify BOTH live stage snapshot AND offscreen Storyboard see the exact same override!
        onNodeWithTag("ciacola-studio-tab-theme").performClick()
        waitForIdle()
        assertEquals(StudioInspectorTab.Theme, workspace.activeTab)

        val customDarkBg = Color(0xFF101928)
        val customBgArgb = customDarkBg.toArgb().toLong() and 0xFFFFFFFFL
        runOnIdle {
            designStore.updateColors(DeckThemePreset.StageDark) {
                it.copy(backgroundArgb = customBgArgb)
            }
            designStore.updateTypography(DeckThemePreset.StageDark) {
                it.copy(eyebrow = TextStyleTokenOverride(fontSizeSp = 22f))
            }
        }
        waitForIdle()

        val liveEyebrow = registry.nodesForSlide(0, 0).firstOrNull { it.elementId == "s1:eyebrow" }
        assertNotNull(liveEyebrow, "Live StageInspectionRegistry should capture s1:eyebrow")
        assertEquals(customDarkBg, liveEyebrow.effectiveBackground)
        assertEquals(22f, liveEyebrow.effectiveFontSizeSp)

        // Context-aware Undo on Theme tab undoes the typography override, then the color override.
        runOnIdle { assertTrue(workspace.undoInActiveContext()) }
        waitForIdle()
        assertEquals(16f, registry.nodesForSlide(0, 0).first { it.elementId == "s1:eyebrow" }.effectiveFontSizeSp)
        runOnIdle { assertTrue(workspace.undoInActiveContext()) }
        waitForIdle()

        // 4. Switch to Element tab, apply direct text copy + low-contrast color override, verify live update.
        onNodeWithTag("ciacola-studio-tab-element").performClick()
        waitForIdle()
        assertEquals(StudioInspectorTab.Element, workspace.activeTab)

        val lowContrastTextArgb = Color(0xFF181C26).toArgb().toLong() and 0xFFFFFFFFL
        runOnIdle {
            elementController.selectElement("s1:eyebrow")
            elementStore.updateElement("s1:eyebrow") {
                it.copy(
                    text = "CUSTOM EYEBROW COPY",
                    textColorArgb = lowContrastTextArgb,
                )
            }
        }
        waitForIdle()

        val editedEyebrow = registry.nodesForSlide(0, 0).first { it.elementId == "s1:eyebrow" }
        assertEquals("CUSTOM EYEBROW COPY", editedEyebrow.resolvedText)
        assertEquals(Color(0xFF181C26), editedEyebrow.effectiveForeground)

        // 5. Full-deck offscreen lint audit sees the exact same Element override, flags LOW_CONTRAST,
        //    attaches a deterministic Fix Contrast patch, and applying that patch clears the error!
        var brokenAudit: List<DeckLintIssue>? = null
        runOnIdle {
            uiScope.launch {
                brokenAudit = lintController.runFullDeckAudit(
                    storyboard = storyboardForPreset(DeckThemePreset.StageDark),
                    presetsToCheck = listOf(DeckThemePreset.StageDark),
                    storyboardForPreset = storyboardForPreset,
                )
            }
        }
        waitUntil(conditionDescription = "Audit detects injected contrast error", timeoutMillis = 10_000) {
            brokenAudit != null && !lintController.isAuditing
        }

        val contrastIssue = brokenAudit!!.firstOrNull {
            it.ruleId == LintRuleId.LOW_CONTRAST && it.elementId == "s1:eyebrow"
        }
        assertNotNull(contrastIssue, "Offscreen audit must flag LOW_CONTRAST on overridden s1:eyebrow")
        val fixPatch = contrastIssue.quickFixes.firstOrNull { it.patch != null }?.patch
        assertNotNull(fixPatch, "Contrast issue on s1:eyebrow must carry a Fix Contrast ElementPatch")

        // Apply Fix Contrast via StudioWorkspaceController (must preserve custom text copy!).
        runOnIdle { workspace.applyElementPatch(fixPatch) }
        waitForIdle()
        assertEquals("CUSTOM EYEBROW COPY", elementStore.patchFor("s1:eyebrow")?.text)

        var fixedAudit: List<DeckLintIssue>? = null
        runOnIdle {
            uiScope.launch {
                fixedAudit = lintController.runFullDeckAudit(
                    storyboard = storyboardForPreset(DeckThemePreset.StageDark),
                    presetsToCheck = listOf(DeckThemePreset.StageDark),
                    storyboardForPreset = storyboardForPreset,
                )
            }
        }
        waitUntil(conditionDescription = "Re-audit completes after quick-fix", timeoutMillis = 10_000) {
            fixedAudit != null && !lintController.isAuditing
        }
        assertTrue(
            fixedAudit!!.none { it.ruleId == LintRuleId.LOW_CONTRAST && it.elementId == "s1:eyebrow" },
            "Fix Contrast quick-fix must clear LOW_CONTRAST on re-audit",
        )

        // 6. Lint 'Edit Element…' routes cleanly to Element tab with s1:eyebrow selected.
        runOnIdle {
            workspace.activeTab = StudioInspectorTab.Lint
            workspace.openElementInInspector(
                elementId = "s1:eyebrow",
                animatic = workspace.timelineController.animatic,
                scope = uiScope,
                sceneIndex = 0,
                beatIndex = 0,
            )
        }
        waitForIdle()
        assertEquals(StudioInspectorTab.Element, workspace.activeTab)
        assertEquals("s1:eyebrow", elementController.selectedElementId)

        // Undo twice on Element tab restores both Fix Contrast and the initial element edit!
        runOnIdle {
            assertTrue(workspace.undoInActiveContext())
            assertTrue(workspace.undoInActiveContext())
        }
        waitForIdle()
        assertFalse(elementStore.data().hasOverrides)

        // 7. Fullscreen presenting hides ALL stage authoring overlays even when Studio was open,
        //    and focused authoring text inputs prevent B/W blackout curtains from firing!
        runOnIdle {
            workspace.activeTab = StudioInspectorTab.Element
            fullscreen = true
        }
        waitForIdle()
        assertTrue(onAllNodesWithTag("ciacola-direct-edit-overlay").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag("ciacola-lint-overlay").fetchSemanticsNodes().isEmpty())

        runOnIdle {
            fullscreen = false
            presenterController.enterCleanShowMode()
            textTracker.onFocusAcquired()
        }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.B) }
        waitForIdle()
        assertEquals(StageCurtain.None, presenterController.curtain)

        runOnIdle { textTracker.onFocusReleased() }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.B) }
        waitForIdle()
        assertEquals(StageCurtain.Blackout, presenterController.curtain)
    }
}
