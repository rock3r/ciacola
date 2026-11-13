package dev.sebastiano.ciacola

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.DesktopEasel
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.easel.resolveStoryboardWorkspaceRoot
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.presenter.PresenterWindow
import dev.sebastiano.ciacola.presenter.rememberPresenterWindow
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberSlideReviewWindow
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalAmbientLoopsPaused
import dev.sebastiano.ciacola.theme.ReducedMotionMode
import dev.sebastiano.ciacola.theme.parseReducedMotionEnv
import dev.sebastiano.ciacola.theme.rememberSystemReducedMotion
import dev.sebastiano.ciacola.theme.resolveEffectiveReducedMotion
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.MotionPathPreviewState
import dev.sebastiano.ciacola.timeline.TimelineOverridesStore
import dev.sebastiano.ciacola.timeline.TimelineStageSelection
import dev.sebastiano.ciacola.timeline.rememberTimelineStageDecorator
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioController
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioWindow
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.RadioButtonRow

fun main() = application {
    var preset by remember {
        val envPreset = System.getenv("CIACOLA_THEME")
        mutableStateOf(
            if (envPreset.equals("light", ignoreCase = true)) {
                DeckThemePreset.KeynoteLight
            } else {
                DeckThemePreset.StageDark
            }
        )
    }
    var motionMode by remember {
        mutableStateOf(parseReducedMotionEnv(System.getenv("CIACOLA_REDUCE_MOTION")))
    }
    var ambientLoopsPaused by remember { mutableStateOf(false) }
    val systemReducedMotion = rememberSystemReducedMotion()
    val effectiveReducedMotion = resolveEffectiveReducedMotion(motionMode, systemReducedMotion)

    val hostTheme = remember(preset) { DeckThemes.forPreset(preset) }
    val materialColors = remember(hostTheme) { hostTheme.toMaterialColors() }
    val materialTypography = remember(hostTheme) { hostTheme.toMaterialTypography() }

    CompositionLocalProvider(
        LocalAmbientLoopsPaused provides ambientLoopsPaused,
    ) {
    MaterialTheme(
        colors = materialColors,
        typography = materialTypography,
    ) {
        val smokeDirectory = System.getenv("DECK_VISUAL_SMOKE_DIR")
        if (smokeDirectory == null) {
            val workspaceRoot = remember { resolveStoryboardWorkspaceRoot() }
            val storyboardDir = remember(workspaceRoot) { workspaceRoot.resolve(".storyboard") }
            val reviewStore = remember(storyboardDir, workspaceRoot) {
                ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = workspaceRoot)
            }
            val timelineOverridesStore = remember(storyboardDir) {
                TimelineOverridesStore(storyboardDir = storyboardDir)
            }
            val deckLoopClock = remember { DeckLoopClock() }
            val motionPathPreview = remember { MotionPathPreviewState() }
            val stageSelection = remember { TimelineStageSelection() }
            val reviewStageCoordinator = rememberReviewStageCoordinator()
            // Plain stage clicks pick timeline tracks unless the review tools own the click.
            stageSelection.pickingSuppressed = {
                reviewStageCoordinator.inspectModeEnabled || reviewStageCoordinator.pendingSelection != null
            }
            val structuralRev = timelineOverridesStore.structuralRevision
            val paramRev = timelineOverridesStore.parameterRevision
            val currentOverrides = remember(structuralRev, paramRev) {
                timelineOverridesStore.data()
            }

            // Stage decorator provides loop clock + motion-path arc overlay + Ctrl-click review stage
            val presetRegistry = remember { StoryboardPresetRegistry() }
            val stageLoopAndArcDecorator = rememberTimelineStageDecorator(
                loopClock = deckLoopClock,
                motionPathPreview = motionPathPreview,
                overridesStore = timelineOverridesStore,
                stageSelection = stageSelection,
            )
            val storyboard = rememberHotReloadStoryboard(
                preset,
                effectiveReducedMotion,
                reviewStageCoordinator,
                structuralRev,
                paramRev,
            ) {
                createFoundationDeck(
                    theme = hostTheme,
                    bundledPreset = preset,
                    includeKeynoteEffectsLab = true,
                    stageDecorator = stageLoopAndArcDecorator + reviewStageCoordinator.stageDecorator,
                    timelineOverrides = currentOverrides,
                    reducedMotion = effectiveReducedMotion,
                ).also { built ->
                    presetRegistry.record(built, preset)
                }
            }
            val animatic = rememberAnimatic(
                fileName = "animatic",
                storyboard = { storyboard },
            )
            val timelineController = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = timelineOverridesStore,
                loopClock = deckLoopClock,
                bakeDispatcher = reviewStore.asBakeDispatcher(),
                bakeWorkspaceRoot = reviewStore.workspaceRoot,
                bakeGradleTaskHint = reviewStore.config.gradleTaskHint,
                motionPathPreview = motionPathPreview,
                stageSelection = stageSelection,
            )
            var presenterWindowHolder by remember { mutableStateOf<PresenterWindow?>(null) }
            val presenterWindow = rememberPresenterWindow(
                animatic = animatic,
                currentPresetProvider = {
                    presetRegistry.presetFor(presenterWindowHolder?.controller?.animatic?.storyboard, fallback = preset)
                },
                isStudioOpenProvider = { timelineController.authoringActive },
                isInspectOpenProvider = { reviewStageCoordinator.inspectModeEnabled },
                hasUnbakedOverridesProvider = {
                    timelineOverridesStore.structuralRevision
                    timelineOverridesStore.parameterRevision
                    timelineOverridesStore.data().hasOverrides
                },
                onEnterCleanShowMode = {
                    timelineController.dockedInStoryWindow = false
                    timelineController.detachedWindowVisible = false
                    reviewStageCoordinator.inspectModeEnabled = false
                    reviewStageCoordinator.dismissActivePopups()
                },
                isExternalTextInputActiveProvider = {
                    reviewStageCoordinator.pendingSelection != null ||
                        reviewStageCoordinator.selectedPinCommentId != null
                },
                onRestoreStageFocus = {
                    runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }.isSuccess
                },
            ).also { presenterWindowHolder = it }
            val timelineWindow = rememberTimelineStudioWindow(
                controller = timelineController,
                currentPresetProvider = {
                    presetRegistry.presetFor(timelineController.animatic.storyboard, fallback = preset)
                },
                suppressShortcutsProvider = {
                    reviewStageCoordinator.pendingSelection != null ||
                        reviewStageCoordinator.selectedPinCommentId != null ||
                        presenterWindow.controller.jumpDialogOpen
                },
                onReturnFocusToStage = {
                    runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }
                },
            )
            val themeWindow = rememberThemeSwitcherWindow(
                currentPreset = preset,
                currentMotionMode = motionMode,
                systemReducedMotion = systemReducedMotion,
                ambientLoopsPaused = ambientLoopsPaused,
                onSelectMotionMode = { motionMode = it },
                onToggleAmbientLoopsPaused = { ambientLoopsPaused = it },
                onSelectPreset = { preset = it },
            )
            var reviewWindowHolder by remember { mutableStateOf<dev.sebastiano.ciacola.review.SlideReviewWindow?>(null) }
            val reviewWindow = rememberSlideReviewWindow(
                animatic = animatic,
                store = reviewStore,
                stageCoordinator = reviewStageCoordinator,
                currentPresetProvider = {
                    val currentStoryboard = reviewWindowHolder?.animatic?.storyboard
                    presetRegistry.presetFor(currentStoryboard, fallback = preset)
                },
                timelineDockedProvider = { timelineController.dockedInStoryWindow },
                onToggleTimeline = {
                    timelineController.dockedInStoryWindow = !timelineController.dockedInStoryWindow
                },
            ).also { reviewWindowHolder = it }
            DesktopEasel(
                animatic = animatic,
                windows = listOf(
                    presenterWindow,
                    themeWindow,
                    reviewWindow,
                    timelineWindow,
                ),
            )
        } else {
            val storyboard = rememberHotReloadStoryboard(preset, effectiveReducedMotion) {
                createFoundationDeck(
                    theme = hostTheme,
                    bundledPreset = preset,
                    reducedMotion = effectiveReducedMotion,
                )
            }
            val animatic = rememberAnimatic(storyboard = { storyboard })
            Window(
                onCloseRequest = ::exitApplication,
                title = "Deck visual smoke",
                state = rememberWindowState(width = 960.dp, height = 540.dp),
            ) {
                Easel(animatic)
                LaunchedEffect(animatic, window) {
                    val directory = File(smokeDirectory).apply { mkdirs() }
                    val robot = Robot()

                    suspend fun capture(name: String) {
                        delay(150)
                        val location = window.locationOnScreen
                        val image = robot.createScreenCapture(
                            Rectangle(location.x, location.y, window.width, window.height)
                        )
                        ImageIO.write(image, "png", File(directory, "$name.png"))
                    }

                    delay(750)
                    capture("00-start")
                    var forward = 1
                    while (true) {
                        val movement = launch { animatic.advance(AdvanceDirection.Forward) }
                        delay(350)
                        capture("f${forward.toString().padStart(2, '0')}-transition")
                        movement.join()
                        capture("f${forward.toString().padStart(2, '0')}-settled")
                        if (animatic.currentIndex.sceneIndex == 2) {
                            capture("loop-a")
                            delay(1_000)
                            capture("loop-b")
                        }
                        forward++
                        if (animatic.currentIndex == animatic.storyboard.indices.last()) break
                    }

                    var backward = 1
                    while (animatic.currentIndex != animatic.storyboard.indices.first()) {
                        val movement = launch { animatic.advance(AdvanceDirection.Backward) }
                        delay(350)
                        capture("b${backward.toString().padStart(2, '0')}-transition")
                        movement.join()
                        capture("b${backward.toString().padStart(2, '0')}-settled")
                        backward++
                    }
                    window.dispose()
                    exitApplication()
                }
            }
        }
    }
    }
}

internal class StoryboardPresetRegistry {
    private val map = Collections.synchronizedMap(WeakHashMap<Storyboard, DeckThemePreset>())

    fun record(storyboard: Storyboard, preset: DeckThemePreset) {
        map[storyboard] = preset
    }

    fun presetFor(storyboard: Storyboard?, fallback: DeckThemePreset): DeckThemePreset =
        storyboard?.let { map[it] } ?: fallback
}

@Composable
internal fun rememberThemeSwitcherWindow(
    currentPreset: DeckThemePreset,
    currentMotionMode: ReducedMotionMode = ReducedMotionMode.System,
    systemReducedMotion: Boolean = false,
    ambientLoopsPaused: Boolean = false,
    onSelectMotionMode: (ReducedMotionMode) -> Unit = {},
    onToggleAmbientLoopsPaused: (Boolean) -> Unit = {},
    onSelectPreset: (DeckThemePreset) -> Unit,
): EaselWindow = remember {
    ThemeSwitcherWindow(
        initialPreset = currentPreset,
        initialMotionMode = currentMotionMode,
        initialSystemReducedMotion = systemReducedMotion,
        initialAmbientLoopsPaused = ambientLoopsPaused,
        initialOnSelectMotionMode = onSelectMotionMode,
        initialOnToggleAmbientLoopsPaused = onToggleAmbientLoopsPaused,
        initialOnSelectPreset = onSelectPreset,
    )
}.also {
    it.currentPreset = currentPreset
    it.currentMotionMode = currentMotionMode
    it.systemReducedMotion = systemReducedMotion
    it.ambientLoopsPaused = ambientLoopsPaused
    it.onSelectMotionMode = onSelectMotionMode
    it.onToggleAmbientLoopsPaused = onToggleAmbientLoopsPaused
    it.onSelectPreset = onSelectPreset
}

private class ThemeSwitcherWindow(
    initialPreset: DeckThemePreset,
    initialMotionMode: ReducedMotionMode,
    initialSystemReducedMotion: Boolean,
    initialAmbientLoopsPaused: Boolean,
    initialOnSelectMotionMode: (ReducedMotionMode) -> Unit,
    initialOnToggleAmbientLoopsPaused: (Boolean) -> Unit,
    initialOnSelectPreset: (DeckThemePreset) -> Unit,
) : EaselWindow {
    override val name: String = "Theme"
    override var visible: Boolean by mutableStateOf(false)

    // Two presets and a short description: keep the inspector compact instead of the roomy
    // tool-window default meant for the timeline and review panels.
    override val initialSize: DpSize get() = DpSize(420.dp, 360.dp)
    var currentPreset: DeckThemePreset by mutableStateOf(initialPreset)
    var currentMotionMode: ReducedMotionMode by mutableStateOf(initialMotionMode)
    var systemReducedMotion: Boolean by mutableStateOf(initialSystemReducedMotion)
    var ambientLoopsPaused: Boolean by mutableStateOf(initialAmbientLoopsPaused)
    var onSelectMotionMode: (ReducedMotionMode) -> Unit by mutableStateOf(initialOnSelectMotionMode)
    var onToggleAmbientLoopsPaused: (Boolean) -> Unit by mutableStateOf(initialOnToggleAmbientLoopsPaused)
    var onSelectPreset: (DeckThemePreset) -> Unit by mutableStateOf(initialOnSelectPreset)

    @Composable
    override fun MenuScope.Menu() {
        RadioButtonItem(
            text = "Stage Dark",
            selected = currentPreset == DeckThemePreset.StageDark,
            onClick = { onSelectPreset(DeckThemePreset.StageDark) },
        )
        RadioButtonItem(
            text = "Keynote Light",
            selected = currentPreset == DeckThemePreset.KeynoteLight,
            onClick = { onSelectPreset(DeckThemePreset.KeynoteLight) },
        )
        Separator()
        Menu("Motion") {
            val sysLabel = if (systemReducedMotion) "Reduced" else "Full"
            RadioButtonItem(
                text = "System ($sysLabel)",
                selected = currentMotionMode == ReducedMotionMode.System,
                onClick = { onSelectMotionMode(ReducedMotionMode.System) },
            )
            RadioButtonItem(
                text = "Reduced Motion",
                selected = currentMotionMode == ReducedMotionMode.Reduced,
                onClick = { onSelectMotionMode(ReducedMotionMode.Reduced) },
            )
            RadioButtonItem(
                text = "Full Motion",
                selected = currentMotionMode == ReducedMotionMode.Full,
                onClick = { onSelectMotionMode(ReducedMotionMode.Full) },
            )
        }
        CheckboxItem(
            text = "Pause Ambient Loops",
            checked = ambientLoopsPaused,
            onCheckedChange = onToggleAmbientLoopsPaused,
        )
        Separator()
        CheckboxItem(
            text = "Show Theme Inspector",
            checked = visible,
            onCheckedChange = { visible = it },
        )
    }

    @Composable
    override fun Content() {
        val isDark = currentPreset == DeckThemePreset.StageDark
        CiacolaJewelTheme(isDark = isDark) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(JewelTheme.globalColors.panelBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GroupHeader("Deck theme preset:")
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RadioButtonRow(
                        text = "Stage Dark",
                        selected = currentPreset == DeckThemePreset.StageDark,
                        onClick = { onSelectPreset(DeckThemePreset.StageDark) },
                    )
                    RadioButtonRow(
                        text = "Keynote Light",
                        selected = currentPreset == DeckThemePreset.KeynoteLight,
                        onClick = { onSelectPreset(DeckThemePreset.KeynoteLight) },
                    )
                }
                InfoText("Switches slide stage, code cards, and Jewel chrome live without losing slide position.")
                GroupHeader("Motion & accessibility:")
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val sysLabel = if (systemReducedMotion) "Reduced" else "Full"
                    RadioButtonRow(
                        text = "System ($sysLabel)",
                        selected = currentMotionMode == ReducedMotionMode.System,
                        onClick = { onSelectMotionMode(ReducedMotionMode.System) },
                    )
                    RadioButtonRow(
                        text = "Reduced Motion",
                        selected = currentMotionMode == ReducedMotionMode.Reduced,
                        onClick = { onSelectMotionMode(ReducedMotionMode.Reduced) },
                    )
                    RadioButtonRow(
                        text = "Full Motion",
                        selected = currentMotionMode == ReducedMotionMode.Full,
                        onClick = { onSelectMotionMode(ReducedMotionMode.Full) },
                    )
                }
                CheckboxRow(
                    text = "Pause ambient background loops",
                    checked = ambientLoopsPaused,
                    onCheckedChange = onToggleAmbientLoopsPaused,
                )
            }
        }
    }
}
