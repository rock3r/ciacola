package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode

enum class TrackCategory {
    SharedElement,
    MagicText,
    WarpCode,
    StaggerGroup,
    StaggerItem,
    Build,
    LineDraw,
    LoopSubComp,
}

/** A stage element registered as animated by one or more timeline tracks. */
class TimelineTargetEntry(
    val trackIds: Set<String>,
    val coordinates: LayoutCoordinates,
)

/**
 * Two-way bridge between the timeline selection and the slide stage: elements tagged with
 * [timelineTarget] register here so the stage can highlight what a selected track animates, and
 * clicking the stage can resolve the track(s) under the pointer.
 */
@Stable
class TimelineStageSelection {
    /** Exact selected track id (stagger items keep their `#itemN` suffix). */
    var selectedTrackId by mutableStateOf<String?>(null)
    var selectedLabel by mutableStateOf<String?>(null)
    var pickingEnabled by mutableStateOf(false)
    var pickingSuppressed: () -> Boolean = { false }
    var onPick: (String?) -> Unit = {}

    var storyStageMountCount by mutableIntStateOf(0)
    var overviewPreviewMountCount by mutableIntStateOf(0)
    var storyStageFillsViewport by mutableStateOf(true)

    val isStoryStageMounted: Boolean
        get() = storyStageMountCount > 0 && overviewPreviewMountCount == 0 && storyStageFillsViewport

    val targets = mutableStateMapOf<Any, TimelineTargetEntry>()

    /** Entries highlighted for [trackId]: exact matches, or every item of a selected group. */
    fun entriesFor(trackId: String): List<TimelineTargetEntry> = targets.values.filter { entry ->
        trackId in entry.trackIds
    }

    /** The most specific track id registered on [entry] (stagger item over its group). */
    fun mostSpecificTrackId(entry: TimelineTargetEntry): String =
        entry.trackIds.firstOrNull { '#' in it } ?: entry.trackIds.first()
}

val LocalTimelineStageSelection = staticCompositionLocalOf<TimelineStageSelection?> { null }

/**
 * Registers this element as animated by [trackIds] so selecting any of those tracks in the
 * timeline highlights it on the slide, and clicking it on the slide selects the track.
 */
fun Modifier.timelineTarget(vararg trackIds: String): Modifier = composed {
    val selection = LocalTimelineStageSelection.current ?: return@composed Modifier
    // Only the presented stage registers (not overview thumbnails or other previews).
    if (LocalSceneMode.current != SceneMode.Story) return@composed Modifier
    val key = remember { Any() }
    val ids = remember(*trackIds) { trackIds.toSet() }
    DisposableEffect(selection, key) {
        onDispose { selection.targets.remove(key) }
    }
    Modifier.onGloballyPositioned { coords ->
        val existing = selection.targets[key]
        if (existing == null || existing.coordinates !== coords || existing.trackIds != ids) {
            selection.targets[key] = TimelineTargetEntry(ids, coords)
        }
    }
}
