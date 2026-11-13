package dev.sebastiano.ciacola.inspector.element

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.timeline.LocalTimelineOffscreen

internal const val MAX_ELEMENT_OFFSET_DP = 480f

/**
 * Coordinates direct stage element selection, inline stage text editing, keyboard nudging,
 * and synchronization with [ElementEditorPanel] and [ElementOverridesStore].
 */
@Stable
class ElementEditorController(
    val overridesStore: ElementOverridesStore,
    val inspectionRegistry: StageInspectionRegistry = StageInspectionRegistry(),
) {
    /** True only when Studio/Authoring editing is active; strictly false in Show Mode. */
    var editorActive by mutableStateOf(false)

    /**
     * When true (default), primary stage clicks select elements under the pointer. Can be
     * toggled off when another studio tool (e.g. Timeline Motion tab) owns single-click picking
     * while still allowing double-click inline text editing when [editorActive] is true.
     */
    var elementPickingEnabled by mutableStateOf(true)

    var selectedElementId by mutableStateOf<String?>(null)
        private set

    var hoveredElementId by mutableStateOf<String?>(null)
        internal set

    var inlineEditingElementId by mutableStateOf<String?>(null)
        private set

    var inlineDraftText by mutableStateOf("")
    internal var inlineDraftTextProvider: (() -> String)? = null

    var suppressPicking: () -> Boolean = { false }
    var isExternalTextInputActive: () -> Boolean = { false }
    var isStagePointOverHud: (Offset) -> Boolean = { false }
    var currentSceneIndexProvider: () -> Int = { 0 }
    var currentBeatIndexProvider: () -> Int = { 0 }
    var isDarkThemeProvider: () -> Boolean = { true }
    var onElementSelected: ((String?) -> Unit)? = null
    var onInlineEditOpened: ((String) -> Unit)? = null
    var onStageKeyHandler: ((KeyEvent) -> Boolean)? = null
    var onReturnFocusToStage: () -> Unit = {}

    fun slidesNodesForCurrentSlide(): List<StageNodeSnapshot> {
        val sceneIndex = currentSceneIndexProvider()
        val beatIndex = currentBeatIndexProvider()
        return inspectionRegistry.nodesForSlide(sceneIndex, beatIndex)
            .filter { it.isEditable && it.effectiveAlpha > 0.02f }
            .sortedWith(compareBy({ it.stageBounds.top }, { it.stageBounds.left }, { it.elementId }))
    }

    /**
     * Returns true when [selectedElementId] is currently visible and editable on the active slide/beat.
     */
    fun isSelectedElementOnCurrentBeat(): Boolean {
        val id = selectedElementId ?: return false
        val sceneIndex = currentSceneIndexProvider()
        val beatIndex = currentBeatIndexProvider()
        return slidesNodesForCurrentSlide().any { it.elementId == id } ||
            inspectionRegistry.nodes[id]?.let {
                it.sceneIndex == sceneIndex && it.beatIndex == beatIndex && it.effectiveAlpha > 0.02f
            } == true
    }

    fun selectElement(elementId: String?) {
        if (inlineEditingElementId != null && inlineEditingElementId != elementId) {
            cancelInlineEdit(restoreStageFocus = false)
        }
        selectedElementId = elementId
        onElementSelected?.invoke(elementId)
    }

    fun canInlineEdit(elementId: String): Boolean {
        val meta = inspectionRegistry.metadataFor(elementId)
        val snapshot = inspectionRegistry.nodes[elementId]
        return meta?.supportsText == true ||
            snapshot?.resolvedText?.isNotEmpty() == true ||
            overridesStore.patchFor(elementId)?.text != null
    }

    fun startInlineEdit(elementId: String): Boolean {
        if (!editorActive || !canInlineEdit(elementId)) return false
        selectedElementId = elementId
        onElementSelected?.invoke(elementId)
        val currentText = overridesStore.patchFor(elementId)?.text
            ?: inspectionRegistry.nodes[elementId]?.resolvedText
            ?: inspectionRegistry.metadataFor(elementId)?.defaultText
            ?: ""
        inlineDraftText = currentText
        inlineEditingElementId = elementId
        onInlineEditOpened?.invoke(elementId)
        return true
    }

    fun commitInlineEdit(
        textOverride: String? = null,
        restoreStageFocus: Boolean = true,
    ): Boolean {
        val id = inlineEditingElementId ?: return false
        val committed = textOverride ?: inlineDraftTextProvider?.invoke() ?: inlineDraftText
        inlineDraftText = committed
        inlineDraftTextProvider = null
        overridesStore.updateElement(
            elementId = id,
            coalesce = false,
            coalesceKey = "inline-text:$id",
        ) { patch ->
            patch.copy(text = committed)
        }
        inlineEditingElementId = null
        if (restoreStageFocus) {
            onReturnFocusToStage()
        }
        return true
    }

    fun cancelInlineEdit(restoreStageFocus: Boolean = true) {
        if (inlineEditingElementId == null) return
        inlineEditingElementId = null
        inlineDraftTextProvider = null
        inlineDraftText = ""
        if (restoreStageFocus) {
            onReturnFocusToStage()
        }
    }

    fun nudgeSelected(deltaXDp: Float, deltaYDp: Float): Boolean {
        val id = selectedElementId ?: return false
        if (!editorActive || inlineEditingElementId != null || !isSelectedElementOnCurrentBeat()) return false
        overridesStore.updateElement(
            elementId = id,
            coalesce = true,
            coalesceKey = "nudge:$id",
        ) { patch ->
            patch.copy(
                offsetXDp = ((patch.offsetXDp ?: 0f) + deltaXDp).coerceIn(-MAX_ELEMENT_OFFSET_DP, MAX_ELEMENT_OFFSET_DP),
                offsetYDp = ((patch.offsetYDp ?: 0f) + deltaYDp).coerceIn(-MAX_ELEMENT_OFFSET_DP, MAX_ELEMENT_OFFSET_DP),
            )
        }
        return true
    }

    fun resetSelectedElement(): Boolean {
        val id = selectedElementId ?: return false
        if (!isSelectedElementOnCurrentBeat()) return false
        overridesStore.resetElement(id)
        return true
    }

    fun undo(): Boolean = overridesStore.undo()

    fun redo(): Boolean = overridesStore.redo()

    /**
     * Stage [ContentDecorator] that wires [StageInspectionRegistry] for the live Story stage
     * and mounts [StageDirectEditLayer] strictly when authoring is active.
     */
    val stageDecorator: ContentDecorator = ContentDecorator { content ->
        if (LocalSceneMode.current == SceneMode.Preview || LocalTimelineOffscreen.current || LocalOverviewOpen.current) {
            content()
            return@ContentDecorator
        }
        StageDirectEditLayer(controller = this, content = content)
    }
}

/**
 * Remembers an [ElementEditorController] wired to [overridesStore] and [inspectionRegistry].
 */
@Composable
fun rememberElementEditorController(
    overridesStore: ElementOverridesStore,
    inspectionRegistry: StageInspectionRegistry = remember { StageInspectionRegistry() },
): ElementEditorController = remember(overridesStore, inspectionRegistry) {
    ElementEditorController(
        overridesStore = overridesStore,
        inspectionRegistry = inspectionRegistry,
    )
}
