package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.EaselWindow
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlinx.coroutines.launch

/**
 * Storyboard [EaselWindow] exposing the `Linter` menu (`Show Deck Linter (F8)`, `Audit Entire Deck`,
 * and `Show Stage Diagnostic Overlay`) and hosting a clean sidecar window when Timeline Studio is
 * not docked or detached.
 */
class DeckLintWindow(
    val controller: DeckLintController,
    initialAnimatic: Animatic,
    var currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    var storyboardProvider: (() -> Storyboard)? = null,
    var presetsToCheck: List<DeckThemePreset> = DeckThemePreset.entries,
    var storyboardForPreset: ((DeckThemePreset) -> Storyboard)? = null,
    var stageInspectionRegistry: StageInspectionRegistry? = null,
    var onApplyElementPatch: ((ElementPatch) -> Unit)? = null,
    var onEditElement: ((elementId: String, sceneIndex: Int, beatIndex: Int) -> Unit)? = null,
    var onOpenThemeEditor: (() -> Unit)? = null,
    var isLinterShownProvider: (() -> Boolean)? = null,
    var onToggleLinter: (() -> Unit)? = null,
    var onPreviewKeyHandler: ((KeyEvent) -> Boolean)? = null,
    override var decorator: ContentDecorator = ContentDecorator.None,
) : EaselWindow {
    var animatic: Animatic by mutableStateOf(initialAnimatic)
        internal set

    override val name: String = "Linter"
    override val initialSize: DpSize get() = DpSize(500.dp, 620.dp)
    override val minimumSize: DpSize get() = DpSize(460.dp, 520.dp)
    override var visible: Boolean by mutableStateOf(false)

    @Composable
    override fun MenuScope.Menu() {
        val scope = rememberCoroutineScope()
        val customToggle = onToggleLinter
        if (customToggle != null) {
            CheckboxItem(
                text = "Show Deck Linter (F8)",
                checked = isLinterShownProvider?.invoke() ?: visible,
                onCheckedChange = { customToggle() },
            )
        } else {
            CheckboxItem(
                text = "Show Deck Linter",
                checked = visible,
                shortcut = KeyShortcut(Key.F8),
                onCheckedChange = { visible = it },
            )
        }
        CheckboxItem(
            text = "Show Stage Diagnostic Overlay",
            checked = controller.showStageOverlay,
            onCheckedChange = { controller.showStageOverlay = it },
        )
        Separator()
        Item(
            text = if (controller.isAuditing) "Auditing Deck…" else "Audit Entire Deck",
            enabled = !controller.isAuditing,
            onClick = {
                if (!(isLinterShownProvider?.invoke() ?: visible)) {
                    if (customToggle != null) customToggle() else visible = true
                }
                controller.scope = LintScope.AllSlides
                scope.launch {
                    val sb = storyboardProvider?.invoke() ?: animatic.storyboard
                    controller.runFullDeckAudit(
                        storyboard = sb,
                        presetsToCheck = presetsToCheck,
                        storyboardForPreset = storyboardForPreset,
                    )
                }
            },
        )
    }

    @Composable
    override fun Content() {
        val isDark = currentPresetProvider() == DeckThemePreset.StageDark
        CiacolaJewelTheme(isDark = isDark) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        onPreviewKeyHandler?.invoke(ev) == true
                    }
                    .onDeckLintShortcut {
                        val customToggle = onToggleLinter
                        if (customToggle != null) customToggle() else visible = false
                    },
            ) {
                DeckLintPanel(
                    controller = controller,
                    animatic = animatic,
                    storyboardProvider = storyboardProvider,
                    presetsToCheck = presetsToCheck,
                    storyboardForPreset = storyboardForPreset,
                    stageInspectionRegistry = stageInspectionRegistry,
                    onApplyElementPatch = onApplyElementPatch,
                    onEditElement = onEditElement,
                    onOpenThemeEditor = onOpenThemeEditor,
                    isActive = visible,
                )
            }
        }
    }
}

@Composable
fun rememberDeckLintWindow(
    controller: DeckLintController,
    animatic: Animatic,
    currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    storyboardProvider: (() -> Storyboard)? = null,
    presetsToCheck: List<DeckThemePreset> = DeckThemePreset.entries,
    storyboardForPreset: ((DeckThemePreset) -> Storyboard)? = null,
    stageInspectionRegistry: StageInspectionRegistry? = null,
    onApplyElementPatch: ((ElementPatch) -> Unit)? = null,
    onEditElement: ((elementId: String, sceneIndex: Int, beatIndex: Int) -> Unit)? = null,
    onOpenThemeEditor: (() -> Unit)? = null,
    isLinterShownProvider: (() -> Boolean)? = null,
    onToggleLinter: (() -> Unit)? = null,
    onPreviewKeyHandler: ((KeyEvent) -> Boolean)? = null,
    decorator: ContentDecorator = ContentDecorator.None,
): DeckLintWindow = remember(controller) {
    DeckLintWindow(
        controller = controller,
        initialAnimatic = animatic,
        currentPresetProvider = currentPresetProvider,
        storyboardProvider = storyboardProvider,
        presetsToCheck = presetsToCheck,
        storyboardForPreset = storyboardForPreset,
        stageInspectionRegistry = stageInspectionRegistry,
        onApplyElementPatch = onApplyElementPatch,
        onEditElement = onEditElement,
        onOpenThemeEditor = onOpenThemeEditor,
        isLinterShownProvider = isLinterShownProvider,
        onToggleLinter = onToggleLinter,
        onPreviewKeyHandler = onPreviewKeyHandler,
        decorator = decorator,
    )
}.also {
    it.animatic = animatic
    it.currentPresetProvider = currentPresetProvider
    it.storyboardProvider = storyboardProvider
    it.presetsToCheck = presetsToCheck
    it.storyboardForPreset = storyboardForPreset
    it.stageInspectionRegistry = stageInspectionRegistry
    it.onApplyElementPatch = onApplyElementPatch
    it.onEditElement = onEditElement
    it.onOpenThemeEditor = onOpenThemeEditor
    it.isLinterShownProvider = isLinterShownProvider
    it.onToggleLinter = onToggleLinter
    it.onPreviewKeyHandler = onPreviewKeyHandler
    it.decorator = decorator
}
