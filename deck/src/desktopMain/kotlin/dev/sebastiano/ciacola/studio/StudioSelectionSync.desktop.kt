package dev.sebastiano.ciacola.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.SyncState
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.DocumentTimelineTrackRef
import kotlinx.coroutines.launch

/** Maps [DeckDocumentStore.sync] onto the plain-language App Bar save indicator. */
fun studioSaveStateFor(store: DeckDocumentStore): StudioSaveState = when (store.sync) {
    is SyncState.ReadOnly -> StudioSaveState.ReadOnly
    is SyncState.Conflict -> StudioSaveState.Conflict
    is SyncState.WriteFailed -> StudioSaveState.Failed
    is SyncState.ExternalInvalid -> StudioSaveState.Invalid
    SyncState.Dirty -> StudioSaveState.Saving
    SyncState.Saved -> if (store.isDirty) StudioSaveState.Saving else StudioSaveState.Saved
}

/** Node id targeted by a timeline track, when the track animates a single stage node. */
internal fun DocumentTimelineTrackRef.targetNodeId(): Pair<String, String>? = when (this) {
    is DocumentTimelineTrackRef.SlideBuildTrack -> slideId to nodeId
    is DocumentTimelineTrackRef.SpecimenTrackBar -> slideId to nodeId
    is DocumentTimelineTrackRef.LoopTrackBar -> slideId to nodeId
    else -> null
}

/**
 * Picks the timeline track that best represents [nodeId] on [slideId]: the build on the presented
 * beat first, then the node's earliest build, then any specimen/loop track for the node.
 */
internal fun findTrackIdForNode(
    trackRefs: Collection<DocumentTimelineTrackRef>,
    slideId: String,
    nodeId: String,
    beatIndex: Int,
): String? {
    val builds = trackRefs.filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
        .filter { it.slideId == slideId && it.nodeId == nodeId }
    builds.firstOrNull { it.beatIndex == beatIndex }?.let { return it.trackId }
    builds.minByOrNull { it.beatIndex }?.let { return it.trackId }
    trackRefs.filterIsInstance<DocumentTimelineTrackRef.SpecimenTrackBar>()
        .firstOrNull { it.slideId == slideId && it.nodeId == nodeId }
        ?.let { return it.trackId }
    return trackRefs.filterIsInstance<DocumentTimelineTrackRef.LoopTrackBar>()
        .firstOrNull { it.slideId == slideId && it.nodeId == nodeId }
        ?.trackId
}

/** Selects the timeline track for [nodeId] via [DocumentTimelineController.selectTracksForNode]. */
internal fun DocumentTimelineController.selectTrackForNode(slideId: String, nodeId: String, beatIndex: Int): Boolean {
    if (currentPresentedSlideId != slideId || currentPresentedBeatIndex != beatIndex) {
        updatePresentedLocation(slideId = slideId, sceneIndex = currentPresentedSceneIndex, beatIndex = beatIndex)
    }
    selectTracksForNode(slideId, nodeId)
    return snapshot.trackRefsByTrackId.values.any { it.targetNodeId() == (slideId to nodeId) }
}

private class SelectionSyncGuard {
    var applyingFromStage: Boolean = false
    var applyingFromTimeline: Boolean = false
}

/**
 * Bidirectional stage ⇄ timeline selection sync + `Go to Build N` beat navigation:
 * - selecting a node on the stage or in `Design` invokes [DocumentTimelineController.selectTracksForNode];
 * - selecting a node's track in the timeline invokes `elementController.selectNode(nodeId)`;
 * - clicking `Go to Build N` on a dimmed/not-present node jumps [animatic] to `(currentScene, beat)`.
 */
@Composable
internal fun StudioSelectionSyncEffect(
    workspace: StudioWorkspaceController,
    activeSlideId: String?,
    activeBeatIndex: Int,
    animatic: Animatic? = null,
) {
    val guard = remember(workspace) { SelectionSyncGuard() }
    val element = workspace.elementController
    val timeline = workspace.timelineController
    val latestSlideId by rememberUpdatedState(activeSlideId)
    val latestBeatIndex by rememberUpdatedState(activeBeatIndex)
    val latestAnimatic by rememberUpdatedState(animatic)
    val scope = rememberCoroutineScope()

    DisposableEffect(element, timeline, guard) {
        element.onSelectionChanged = { nodeId ->
            if (!guard.applyingFromTimeline) {
                val slideId = latestSlideId ?: element.activeSlideId.takeIf { it.isNotBlank() }
                if (slideId != null) {
                    guard.applyingFromStage = true
                    try {
                        if (timeline.currentPresentedSlideId != slideId) {
                            timeline.updatePresentedLocation(
                                slideId = slideId,
                                sceneIndex = timeline.currentPresentedSceneIndex,
                                beatIndex = latestBeatIndex,
                            )
                        }
                        timeline.selectTracksForNode(slideId, nodeId)
                    } finally {
                        guard.applyingFromStage = false
                    }
                }
            }
        }

        timeline.onSelectedNodeChanged = { nodeId ->
            if (!guard.applyingFromStage) {
                val slide = element.activeSlide()
                val validOnSlide = nodeId == null ||
                    (slide != null && DocumentThemeResolver.findNode(slide.nodes, nodeId) != null)
                if (validOnSlide && element.selectedNodeId != nodeId) {
                    guard.applyingFromTimeline = true
                    try {
                        element.selectNode(nodeId)
                    } finally {
                        guard.applyingFromTimeline = false
                    }
                }
            }
        }

        element.onNavigateToBeat = { targetBeat ->
            val targetAnimatic = latestAnimatic
            if (targetAnimatic != null) {
                val currentScene = targetAnimatic.targetIndex.sceneIndex.coerceAtLeast(0)
                val maxFrame = (targetAnimatic.storyboard.scenes.getOrNull(currentScene)?.frames?.lastIndex ?: 0)
                    .coerceAtLeast(0)
                val target = Storyboard.Index(
                    sceneIndex = currentScene,
                    frameIndex = targetBeat.coerceIn(0, maxFrame),
                )
                if (target in targetAnimatic.storyboard.indices) {
                    scope.launch { targetAnimatic.jumpTo(target) }
                }
            } else {
                val slideId = latestSlideId ?: element.activeSlideId
                if (slideId.isNotBlank()) {
                    element.selectLocation(slideId, targetBeat)
                }
            }
        }

        onDispose {
            if (element.onSelectionChanged != null) element.onSelectionChanged = null
            if (timeline.onSelectedNodeChanged != null) timeline.onSelectedNodeChanged = null
            if (element.onNavigateToBeat != null) element.onNavigateToBeat = null
        }
    }
}
