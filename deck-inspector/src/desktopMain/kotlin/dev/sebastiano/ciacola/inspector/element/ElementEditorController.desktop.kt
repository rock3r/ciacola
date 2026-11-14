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
import dev.sebastiano.ciacola.inspector.StageElementMetadata
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.inspector.beatElementId
import dev.sebastiano.ciacola.timeline.LocalTimelineOffscreen

internal const val MAX_ELEMENT_OFFSET_DP = 480f
private val BEAT_SCOPED_ELEMENT_ID_REGEX = Regex("""^(.+):b(\d+)$""")

/**
 * Coordinates direct stage element selection, inline stage text editing, keyboard nudging,
 * and synchronization with [ElementEditorPanel] and [ElementOverridesStore].
 */
@Stable
class ElementEditorController(
    val overridesStore: ElementOverridesStore,
    val inspectionRegistry: StageInspectionRegistry = StageInspectionRegistry(),
    isStageSettlingProvider: () -> Boolean = { false },
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
    var isStageSettlingProvider: () -> Boolean = isStageSettlingProvider
    var isDarkThemeProvider: () -> Boolean = { true }
    var onElementSelected: ((String?) -> Unit)? = null
    var onInlineEditOpened: ((String) -> Unit)? = null
    var onStageKeyHandler: ((KeyEvent) -> Boolean)? = null
    var onReturnFocusToStage: () -> Unit = {}

    private var lastSettledSlideNodes: List<StageNodeSnapshot> = emptyList()
    private var lastSettledSceneIndex: Int = -1
    private var lastSettledSelectionOnBeat: Boolean = false
    private val cachedMetadata = mutableMapOf<String, StageElementMetadata>()

    private fun computeCurrentSlideNodes(): List<StageNodeSnapshot> {
        val sceneIndex = currentSceneIndexProvider()
        val beatIndex = currentBeatIndexProvider()
        return inspectionRegistry.nodesForSlide(sceneIndex, beatIndex)
            .filter { it.isEditable && it.effectiveAlpha > 0.02f }
            .sortedWith(compareBy({ it.stageBounds.top }, { it.stageBounds.left }, { it.elementId }))
    }

    fun slidesNodesForCurrentSlide(): List<StageNodeSnapshot> {
        if (isStageSettlingProvider() && lastSettledSlideNodes.isNotEmpty()) {
            return lastSettledSlideNodes
        }
        val sceneIndex = currentSceneIndexProvider()
        val beatIndex = currentBeatIndexProvider()
        val live = computeCurrentSlideNodes()
        if (live.isEmpty() &&
            lastSettledSlideNodes.isNotEmpty() &&
            lastSettledSceneIndex == sceneIndex &&
            inspectionRegistry.nodes.values.any { it.sceneIndex == sceneIndex && it.beatIndex != beatIndex }
        ) {
            return lastSettledSlideNodes
        }
        lastSettledSlideNodes = live
        lastSettledSceneIndex = sceneIndex
        return live
    }

    private fun isNodeActiveOnBeat(elementId: String, sceneIndex: Int, beatIndex: Int): Boolean {
        if (slidesNodesForCurrentSlide().any { it.elementId == elementId }) return true
        return inspectionRegistry.nodes[elementId]?.let {
            it.sceneIndex == sceneIndex && it.beatIndex == beatIndex && it.effectiveAlpha > 0.02f
        } == true
    }

    /**
     * Resolves [selectedElementId] on the current beat, seamlessly following beat-scoped multi-beat
     * elements (e.g. `s1:prose:b0` -> `s1:prose:b1`) when advancing or scrubbing within the same slide.
     */
    fun resolveSelectedElementIdForCurrentBeat(): String? {
        val id = selectedElementId ?: return null
        if (isStageSettlingProvider()) return id
        val sceneIndex = currentSceneIndexProvider()
        val beatIndex = currentBeatIndexProvider()
        if (isNodeActiveOnBeat(id, sceneIndex, beatIndex)) return id
        val match = BEAT_SCOPED_ELEMENT_ID_REGEX.matchEntire(id) ?: return id
        val candidateId = beatElementId(match.groupValues[1], beatIndex)
        return if (isNodeActiveOnBeat(candidateId, sceneIndex, beatIndex)) candidateId else id
    }

    internal fun followBeatScopedSelection(targetElementId: String) {
        if (selectedElementId != targetElementId) {
            selectedElementId = targetElementId
            onElementSelected?.invoke(targetElementId)
        }
    }

    fun metadataFor(elementId: String): StageElementMetadata? {
        val live = inspectionRegistry.metadataFor(elementId)
        if (live != null) {
            cachedMetadata[elementId] = live
            return live
        }
        return if (isStageSettlingProvider()) cachedMetadata[elementId] else null
    }

    /**
     * Returns true when [selectedElementId] (or its same-slide beat-scoped sibling) is currently
     * visible and editable on the active slide/beat.
     */
    fun isSelectedElementOnCurrentBeat(): Boolean {
        val id = resolveSelectedElementIdForCurrentBeat() ?: return false
        if (isStageSettlingProvider()) {
            return lastSettledSelectionOnBeat || lastSettledSlideNodes.any { it.elementId == id }
        }
        val onBeat = isNodeActiveOnBeat(id, currentSceneIndexProvider(), currentBeatIndexProvider())
        lastSettledSelectionOnBeat = onBeat
        return onBeat
    }

    fun selectElement(elementId: String?) {
        if (inlineEditingElementId != null && inlineEditingElementId != elementId) {
            cancelInlineEdit(restoreStageFocus = false)
        }
        selectedElementId = elementId
        if (elementId == null) {
            lastSettledSelectionOnBeat = false
        } else if (!isStageSettlingProvider()) {
            lastSettledSelectionOnBeat = isNodeActiveOnBeat(
                elementId,
                currentSceneIndexProvider(),
                currentBeatIndexProvider(),
            )
        }
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
        val id = resolveSelectedElementIdForCurrentBeat() ?: return false
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
        val id = resolveSelectedElementIdForCurrentBeat() ?: return false
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
    isStageSettlingProvider: (() -> Boolean)? = null,
): ElementEditorController = remember(overridesStore, inspectionRegistry) {
    ElementEditorController(
        overridesStore = overridesStore,
        inspectionRegistry = inspectionRegistry,
        isStageSettlingProvider = isStageSettlingProvider ?: { false },
    )
}.also {
    if (isStageSettlingProvider != null) {
        it.isStageSettlingProvider = isStageSettlingProvider
    }
}
