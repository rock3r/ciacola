package dev.sebastiano.ciacola.inspector

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.DpOffset

/**
 * Identifier of the enclosing editable container (`DeckSurfaceCard`, `EditableBox`), enabling
 * container-first selection in the stage inspector without area heuristics.
 */
val LocalStageParentElementId = compositionLocalOf<String?> { null }

/**
 * Snapshot of a registered live interactive `@Composable` region on the slide stage.
 */
@Immutable
data class StageInteractiveEntry(
    val id: String,
    val label: String,
    val stageBounds: Rect,
    val parentElementId: String? = null,
)

/**
 * Tracks live interactive specimen bounds in stage coordinates so Studio overlays can yield pointer
 * input to interactive children while still supporting explicit container selection (`Alt+Click`).
 */
@Stable
class StageInteractiveRegistry {
    private val entryMap: SnapshotStateMap<String, StageInteractiveEntry> = mutableStateMapOf()
    private val owners: MutableMap<String, Any> = mutableMapOf()

    val entries: Map<String, Rect>
        get() = entryMap.mapValues { (_, entry) -> entry.stageBounds }

    val descriptors: Map<String, StageInteractiveEntry>
        get() = entryMap

    val labels: Map<String, String>
        get() = entryMap.mapValues { (_, entry) -> entry.label }

    fun register(
        id: String,
        label: String = id,
        stageBounds: Rect,
        parentElementId: String? = null,
        owner: Any? = null,
    ) {
        if (owner != null) {
            owners[id] = owner
        }
        val next = StageInteractiveEntry(
            id = id,
            label = label,
            stageBounds = stageBounds,
            parentElementId = parentElementId,
        )
        if (entryMap[id] != next) {
            entryMap[id] = next
        }
    }

    fun unregister(id: String) {
        owners.remove(id)
        entryMap.remove(id)
    }

    fun unregister(id: String, owner: Any) {
        if (owners[id] === owner) {
            owners.remove(id)
            entryMap.remove(id)
        }
    }

    fun entryFor(id: String): StageInteractiveEntry? = entryMap[id]

    fun labelFor(id: String): String? = entryMap[id]?.label

    fun hitTestEntry(stagePoint: Offset): StageInteractiveEntry? =
        entryMap.values
            .filter { it.stageBounds.contains(stagePoint) }
            .minByOrNull { it.stageBounds.width * it.stageBounds.height }

    fun hitTest(stagePoint: Offset): String? = hitTestEntry(stagePoint)?.id

    fun clear() {
        owners.clear()
        entryMap.clear()
    }
}

val LocalStageInteractiveRegistry = staticCompositionLocalOf<StageInteractiveRegistry?> { null }

/**
 * Marks a live interactive `@Composable` region on the slide stage so Studio overlays display
 * interactive affordances and support container selection.
 */
fun Modifier.stageInteractive(id: String, label: String = id): Modifier = composed {
    val registry = LocalStageInteractiveRegistry.current
    val stageRootHolder = LocalStageRootCoordinates.current
    val parentElementId = LocalStageParentElementId.current?.takeIf { it != id }
    if (registry == null) {
        this
    } else {
        val ownerToken = remember { Any() }
        DisposableEffect(registry, id) {
            onDispose { registry.unregister(id, ownerToken) }
        }
        this.onPlaced { coords ->
            if (!coords.isAttached) return@onPlaced
            val root = stageRootHolder?.coordinates
            val rect = if (root != null && root.isAttached) {
                runCatching { root.localBoundingBoxOf(coords, clipBounds = false) }.getOrNull()
            } else if (stageRootHolder == null) {
                runCatching { coords.boundsInRoot() }.getOrNull()
            } else {
                null
            }
            if (rect != null && rect.width > 0f && rect.height > 0f) {
                registry.register(
                    id = id,
                    label = label,
                    stageBounds = rect,
                    parentElementId = parentElementId,
                    owner = ownerToken,
                )
            }
        }
    }
}

/**
 * Ephemeral placement-phase `(dx, dy)` drag deltas written during active stage drags so moving
 * an element never writes to [LocalElementOverrides] or recomposes the slide on every frame.
 */
@Stable
class StageDragPreview {
    val offsets: SnapshotStateMap<String, DpOffset> = mutableStateMapOf()

    fun offsetFor(elementId: String): DpOffset = offsets[elementId] ?: DpOffset.Zero

    fun set(elementId: String, offset: DpOffset) {
        offsets[elementId] = offset
    }

    fun clear(elementId: String) {
        offsets.remove(elementId)
    }

    fun clear() {
        offsets.clear()
    }
}

val LocalStageDragPreview = staticCompositionLocalOf<StageDragPreview?> { null }
