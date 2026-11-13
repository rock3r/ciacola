package dev.sebastiano.ciacola.inspector

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/**
 * Per-element live visual and copy override patch keyed by stable [elementId].
 */
@Serializable
data class ElementPatch(
    val elementId: String,
    val text: String? = null,
    val textColorArgb: Long? = null,
    val fontSizeSp: Float? = null,
    val fontWeight: Int? = null,
    val fillColorArgb: Long? = null,
    val borderColorArgb: Long? = null,
    val borderWidthDp: Float? = null,
    val cornerRadiusDp: Float? = null,
    val paddingDp: Float? = null,
    val offsetXDp: Float? = null,
    val offsetYDp: Float? = null,
) {
    val isEmpty: Boolean
        get() = text == null &&
            textColorArgb == null &&
            fontSizeSp == null &&
            fontWeight == null &&
            fillColorArgb == null &&
            borderColorArgb == null &&
            borderWidthDp == null &&
            cornerRadiusDp == null &&
            paddingDp == null &&
            offsetXDp == null &&
            offsetYDp == null
}

/**
 * Persisted collection of per-element overrides keyed by [ElementPatch.elementId].
 */
@Serializable
data class DeckElementOverridesFile(
    val elements: Map<String, ElementPatch> = emptyMap(),
) {
    val hasOverrides: Boolean
        get() = elements.values.any { !it.isEmpty }

    fun patchFor(elementId: String): ElementPatch? = elements[elementId]
}

val LocalElementOverrides = staticCompositionLocalOf { DeckElementOverridesFile() }

/**
 * Lightweight snapshot of a rendered stage element in 1920x1080 stage coordinates, used by the
 * Direct Element Editor and Deck Linter without coupling scenes to `:deck-inspector`.
 */
@Immutable
data class StageNodeSnapshot(
    val elementId: String,
    val kind: String,
    val label: String,
    val sceneIndex: Int,
    val beatIndex: Int,
    val stageBounds: Rect,
    val resolvedText: String = "",
    val hasVisualOverflow: Boolean = false,
    val hasEllipsis: Boolean = false,
    val effectiveForeground: Color? = null,
    val effectiveBackground: Color? = null,
    val effectiveFontSizeSp: Float? = null,
    val isEditable: Boolean = true,
)

/**
 * Live registry of rendered stage element snapshots for the active composition.
 */
@Stable
class StageInspectionRegistry {
    val nodes = mutableStateMapOf<String, StageNodeSnapshot>()

    fun record(snapshot: StageNodeSnapshot) {
        if (nodes[snapshot.elementId] != snapshot) {
            nodes[snapshot.elementId] = snapshot
        }
    }

    fun remove(elementId: String) {
        nodes.remove(elementId)
    }

    fun nodesForSlide(sceneIndex: Int, beatIndex: Int): List<StageNodeSnapshot> =
        nodes.values.filter { it.sceneIndex == sceneIndex && it.beatIndex == beatIndex }

    fun clear() {
        nodes.clear()
    }
}

val LocalStageInspectionRegistry = staticCompositionLocalOf<StageInspectionRegistry?> { null }
