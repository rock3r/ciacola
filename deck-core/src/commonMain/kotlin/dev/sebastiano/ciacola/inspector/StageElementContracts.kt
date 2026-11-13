package dev.sebastiano.ciacola.inspector

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.modifier.modifierLocalProvider
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
    val effectiveAlpha: Float = 1f,
)

/**
 * ModifierLocal carrying a lazy stage visibility/spotlight alpha provider (`0f..1f`) down layout
 * subtrees without triggering per-frame recomposition.
 */
val ModifierLocalStageAlpha = modifierLocalOf<() -> Float> { { 1f } }

/**
 * Multiplies the enclosing [ModifierLocalStageAlpha] by [localAlpha] for all descendant stage
 * elements so hidden build items (`alpha == 0f`) and spotlight-dimmed items report true alpha.
 */
fun Modifier.provideStageVisibilityAlpha(localAlpha: () -> Float): Modifier = composed {
    var parentAlpha by remember { mutableStateOf<() -> Float>({ 1f }) }
    this
        .modifierLocalConsumer { parentAlpha = ModifierLocalStageAlpha.current }
        .modifierLocalProvider(ModifierLocalStageAlpha) {
            { (parentAlpha() * localAlpha()).coerceIn(0f, 1f) }
        }
}

/**
 * Editor metadata and default styling values recorded alongside [StageNodeSnapshot] so the
 * Element Inspector can display accurate default values and capabilities.
 */
@Immutable
data class StageElementMetadata(
    val elementId: String,
    val supportsText: Boolean = false,
    val supportsBoxStyle: Boolean = false,
    val defaultText: String = "",
    val defaultTextColor: Color? = null,
    val defaultFontSizeSp: Float? = null,
    val defaultFontWeight: Int? = null,
    val defaultFillColor: Color? = null,
    val defaultBorderColor: Color? = null,
    val defaultBorderWidthDp: Float? = null,
    val defaultCornerRadiusDp: Float? = null,
    val defaultPaddingDp: Float? = null,
)

/**
 * Returns the beat-scoped override identifier for multi-beat elements such as MagicText.
 */
fun beatElementId(baseId: String, beatIndex: Int): String = "$baseId:b$beatIndex"

/**
 * Live registry of rendered stage element snapshots for the active composition.
 */
@Stable
class StageInspectionRegistry {
    val nodes = mutableStateMapOf<String, StageNodeSnapshot>()
    val metadata = mutableStateMapOf<String, StageElementMetadata>()

    private val nodeOwners = mutableMapOf<String, Any>()
    private val metadataOwners = mutableMapOf<String, Any>()
    private val alphaProviders = mutableMapOf<String, () -> Float>()
    private val coordinatesProviders = mutableMapOf<String, () -> LayoutCoordinates?>()
    private val stageRootProviders = mutableMapOf<String, () -> LayoutCoordinates?>()

    fun record(
        snapshot: StageNodeSnapshot,
        owner: Any? = null,
        alphaProvider: (() -> Float)? = null,
        coordinatesProvider: (() -> LayoutCoordinates?)? = null,
        stageRootProvider: (() -> LayoutCoordinates?)? = null,
    ) {
        if (owner != null) {
            nodeOwners[snapshot.elementId] = owner
        }
        if (alphaProvider != null) {
            alphaProviders[snapshot.elementId] = alphaProvider
        }
        if (coordinatesProvider != null) {
            coordinatesProviders[snapshot.elementId] = coordinatesProvider
        }
        if (stageRootProvider != null) {
            stageRootProviders[snapshot.elementId] = stageRootProvider
        }
        if (nodes[snapshot.elementId] != snapshot) {
            nodes[snapshot.elementId] = snapshot
        }
    }

    fun effectiveAlphaOf(elementId: String): Float =
        alphaProviders[elementId]?.invoke()?.coerceIn(0f, 1f) ?: nodes[elementId]?.effectiveAlpha ?: 1f

    fun liveStageBoundsOf(elementId: String): Rect? {
        val c = coordinatesProviders[elementId]?.invoke()?.takeIf { it.isAttached }
        val root = stageRootProviders[elementId]?.invoke()?.takeIf { it.isAttached }
        if (c != null && root != null) {
            runCatching { root.localBoundingBoxOf(c, clipBounds = false) }.getOrNull()?.let { return it }
        }
        return nodes[elementId]?.stageBounds
    }

    fun recordMetadata(meta: StageElementMetadata, owner: Any? = null) {
        if (owner != null) {
            metadataOwners[meta.elementId] = owner
        }
        if (metadata[meta.elementId] != meta) {
            metadata[meta.elementId] = meta
        }
    }

    fun metadataFor(elementId: String): StageElementMetadata? = metadata[elementId]

    fun remove(elementId: String) {
        nodeOwners.remove(elementId)
        metadataOwners.remove(elementId)
        alphaProviders.remove(elementId)
        coordinatesProviders.remove(elementId)
        stageRootProviders.remove(elementId)
        nodes.remove(elementId)
        metadata.remove(elementId)
    }

    fun remove(elementId: String, owner: Any) {
        if (nodeOwners[elementId] === owner) {
            nodeOwners.remove(elementId)
            alphaProviders.remove(elementId)
            coordinatesProviders.remove(elementId)
            stageRootProviders.remove(elementId)
            nodes.remove(elementId)
        }
        if (metadataOwners[elementId] === owner) {
            metadataOwners.remove(elementId)
            metadata.remove(elementId)
        }
    }

    fun nodesForSlide(sceneIndex: Int, beatIndex: Int): List<StageNodeSnapshot> =
        nodes.values
            .filter { it.sceneIndex == sceneIndex && it.beatIndex == beatIndex }
            .map { snap ->
                val liveAlpha = effectiveAlphaOf(snap.elementId)
                val liveBounds = liveStageBoundsOf(snap.elementId) ?: snap.stageBounds
                if (liveAlpha == snap.effectiveAlpha && liveBounds == snap.stageBounds) {
                    snap
                } else {
                    snap.copy(effectiveAlpha = liveAlpha, stageBounds = liveBounds)
                }
            }

    fun clear() {
        nodeOwners.clear()
        metadataOwners.clear()
        alphaProviders.clear()
        coordinatesProviders.clear()
        stageRootProviders.clear()
        nodes.clear()
        metadata.clear()
    }
}

val LocalStageInspectionRegistry = staticCompositionLocalOf<StageInspectionRegistry?> { null }

/**
 * Effective composited background colour behind the current subtree, updated by surface
 * containers so child text nodes know their true background colour for contrast checks.
 */
val LocalEffectiveBackgroundColor = compositionLocalOf { Color.Unspecified }

/**
 * Current active beat index within the enclosing choreographed scene.
 */
val LocalActiveBeatIndex = compositionLocalOf { 0 }

/**
 * Holds the root 1920x1080 stage [LayoutCoordinates] so child elements compute stage-space
 * bounds regardless of outer window scaling or letterboxing.
 */
@Stable
class StageRootCoordinatesHolder {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
}

val LocalStageRootCoordinates = staticCompositionLocalOf<StageRootCoordinatesHolder?> { null }
