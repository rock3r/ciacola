package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.Stagger
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** Body of the `Animate` tab: follows the timeline selection (beat, transition, track, loop). */
@Composable
internal fun DocumentTimelineRightInspector(
    controller: DocumentTimelineController,
) {
    when (val sel = controller.selection) {
        DocumentTimelineSelection.None -> {
            // A node selected on stage with no specific track: show that node's builds.
            val nodeRef = stageNodeTrackRef(controller)
            when {
                nodeRef != null -> SelectedTrackInspectorSection(controller = controller, ref = nodeRef)
                controller.highlightedNodeId != null -> Text(
                    text = "This element has no builds yet. Add a build in the timeline to animate it.",
                    style = studioLabelStyle(),
                    color = JewelTheme.globalColors.text.normal,
                    modifier = Modifier.testTag("ciacola-animate-node-no-builds"),
                )
                else -> Text(
                    text = "Select an element on stage, or a build, transition or track in the timeline, to edit its motion.",
                    style = studioLabelStyle(),
                    color = JewelTheme.globalColors.text.normal,
                    modifier = Modifier.testTag("ciacola-animate-empty"),
                )
            }
        }
        is DocumentTimelineSelection.BeatHeader,
        is DocumentTimelineSelection.TransitionHeader,
        -> DocumentMotionStaggerInspector(controller = controller, selection = sel)
        is DocumentTimelineSelection.Loop -> {
            val ref = controller.snapshot.trackRefsByTrackId["doc:loop:${sel.loopId}"]
                as? DocumentTimelineTrackRef.LoopTrackBar ?: return
            LoopPeriodInspectorSection(controller = controller, ref = ref)
        }
        is DocumentTimelineSelection.Track -> {
            val ref = controller.snapshot.trackRefsByTrackId[sel.trackId] ?: return
            SelectedTrackInspectorSection(controller = controller, ref = ref)
        }
    }
}

/** First timeline track of the stage-selected (highlighted) node on the presented slide, if any. */
internal fun stageNodeTrackRef(controller: DocumentTimelineController): DocumentTimelineTrackRef? {
    val nodeId = controller.highlightedNodeId ?: return null
    val slideId = controller.currentPresentedSlideId ?: return null
    return DocumentTimelineSelectionSupport
        .trackIdsForNode(controller.snapshot, slideId, nodeId, controller.currentPresentedBeatIndex)
        .firstNotNullOfOrNull { controller.snapshot.trackRefsByTrackId[it] }
}

/** Title + caption header for the `Animate` tab, with the raw id only in the tooltip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnimateInspectorHeader(title: String, caption: String?, rawId: String, tag: String) {
    Tooltip(tooltip = { Text(rawId) }) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag(tag)) {
            Text(title, style = studioTitleStyle(), color = JewelTheme.globalColors.text.normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (caption != null) {
                Text(caption, style = studioCaptionStyle(), color = JewelTheme.globalColors.text.normal.copy(alpha = 0.78f), maxLines = 1)
            }
        }
    }
}

/** Section overflow `…` menu (destructive actions live here, never next to the curve fields). */
@Composable
internal fun SectionOverflowMenu(tag: String, items: List<Triple<String, String, () -> Unit>>) {
    if (items.isEmpty()) return
    var open by remember(tag) { mutableStateOf(false) }
    Box {
        CompactTextButton("…", { open = !open }, "ciacola-animate-overflow-$tag")
        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.End,
                popupProperties = PopupProperties(focusable = true),
            ) {
                for ((label, itemTag, action) in items) {
                    selectableItem(selected = false, onClick = { open = false; action() }) {
                        Text(label, style = studioLabelStyle(), modifier = Modifier.testTag(itemTag))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoopPeriodInspectorSection(
    controller: DocumentTimelineController,
    ref: DocumentTimelineTrackRef.LoopTrackBar,
) {
    var dragging by remember(ref.loopId) { mutableStateOf(false) }
    val overridden = ref.loopId in controller.store.document.loopPeriodsMs
    val slideList = ref.sceneIndices.sorted().joinToString(", ") { "${it + 1}" }
    AnimateInspectorHeader(
        title = "Loop · ${ref.label}",
        caption = if (ref.sceneIndices.size > 1) "Ambient loop on slides $slideList" else "Ambient loop on slide $slideList",
        rawId = ref.loopId,
        tag = "ciacola-animate-loop-header",
    )
    TimelineFlatSection(title = "Timing", tag = "loop-timing") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Period", style = studioLabelStyle(), color = JewelTheme.globalColors.text.normal)
            Slider(
                value = ref.effectivePeriodMs.toFloat().coerceIn(200f, 20_000f),
                valueRange = 200f..20_000f,
                onValueChange = { raw ->
                    if (!dragging) {
                        dragging = true
                        controller.beginGesture("Adjust loop period ${ref.label}")
                    }
                    val snapped = ((raw / 50f).roundToInt() * 50L).coerceAtLeast(200L)
                    controller.setLoopPeriodMs(ref.loopId, snapped, useActiveGesture = true)
                },
                onValueChangeFinished = {
                    if (dragging) {
                        dragging = false
                        controller.commitActiveGesture()
                    }
                },
                modifier = Modifier.weight(1f).testTag("ciacola-loop-period-slider-${ref.loopId}"),
            )
            ScrubbableMsField(
                label = "",
                labelWidth = 0.dp,
                value = ref.effectivePeriodMs.toInt(),
                step = 50,
                range = 200..20_000,
                tag = "loop-period",
                onValueChange = { v, inGesture -> controller.setLoopPeriodMs(ref.loopId, v.toLong(), useActiveGesture = inGesture) },
                onBeginGesture = { controller.beginGesture("Adjust loop period ${ref.label}") },
                onCommitGesture = { controller.commitActiveGesture() },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (controller.activeSubCompLoopId != ref.loopId) {
                CompactTextButton("Open loop timeline", { controller.enterLoopSubComp(ref.loopId) }, "ciacola-loop-open-subcomp-${ref.loopId}")
            } else {
                CompactTextButton("Back to deck", { controller.exitLoopSubComp() }, "ciacola-loop-exit-subcomp-${ref.loopId}")
            }
            if (overridden) {
                CompactTextButton(
                    "Reset to ${ref.defaultPeriodMs} ms",
                    { controller.resetLoopPeriodMs(ref.loopId) },
                    "ciacola-loop-period-reset-${ref.loopId}",
                )
            }
        }
    }
}

@Composable
private fun SelectedTrackInspectorSection(
    controller: DocumentTimelineController,
    ref: DocumentTimelineTrackRef,
) {
    val plan = when (ref) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> ref.trackPlan
        is DocumentTimelineTrackRef.SpecimenTrackBar -> ref.trackPlan
        is DocumentTimelineTrackRef.TransitionTrackBar -> ref.trackPlan
        is DocumentTimelineTrackRef.LoopTrackBar -> null
    } ?: return
    val row = controller.snapshot.snapshot.tracks.firstOrNull { it.trackId == ref.trackId }
    val isManualNode = hasManualStaggerOverride(controller.store.document, ref)
    val nodeName = row?.label ?: TimelineDisplayNames.humanizeId(plan.nodeId)

    AnimateInspectorHeader(
        title = nodeName,
        caption = row?.blocks?.firstOrNull()?.label,
        rawId = "${ref.trackId}\nnode: ${plan.nodeId}",
        tag = "ciacola-animate-track-header",
    )

    val resetManual: () -> Unit = {
        when (ref) {
            is DocumentTimelineTrackRef.SlideBuildTrack -> controller.resetBeatNodeManualDelay(ref.slideId, ref.beatIndex, ref.nodeId)
            is DocumentTimelineTrackRef.TransitionTrackBar -> controller.resetTransitionNodeManualDelay(ref.fromSlideId, ref.nodeId)
            else -> Unit
        }
    }
    val overflow = buildList {
        if (isManualNode) add(Triple("Reset manual offset", "ciacola-track-overflow-reset", resetManual))
        add(Triple("Copy ID", "ciacola-track-overflow-copy-id") { controller.clipboardWriter(plan.nodeId) })
        if (ref is DocumentTimelineTrackRef.SlideBuildTrack && !controller.store.isReadOnly) {
            add(
                Triple("Delete build", "ciacola-doc-track-delete-build") {
                    controller.deleteBuild(ref.slideId, ref.nodeId, ref.buildIndex)
                    controller.clearSelection()
                },
            )
        }
    }

    TimelineFlatSection(
        title = "Timing",
        tag = "track-timing",
        trailing = { SectionOverflowMenu("track", overflow) },
    ) {
        ScrubbableMsField(
            label = "Start",
            value = plan.startMs,
            tag = "track-start",
            onValueChange = { v, g -> controller.dragTrackBarStart(ref, v, useActiveGesture = g) },
            onBeginGesture = { controller.beginGesture("Move start of $nodeName") },
            onCommitGesture = { controller.commitActiveGesture() },
        )
        ScrubbableMsField(
            label = "Duration",
            value = plan.durationMs,
            tag = "track-duration",
            onValueChange = { v, g -> controller.resizeTrackBarDuration(ref, v, useActiveGesture = g) },
            onBeginGesture = { controller.beginGesture("Resize duration of $nodeName") },
            onCommitGesture = { controller.commitActiveGesture() },
        )
        if (ref is DocumentTimelineTrackRef.TransitionTrackBar) {
            val sharedPhase = DocumentTimelineMutationSupport.phaseForTransitionKind(ref.trackPlan.kind)
            if (sharedPhase != null) {
                val sharedCount = controller.snapshot.trackRefsByTrackId.values
                    .filterIsInstance<DocumentTimelineTrackRef.TransitionTrackBar>()
                    .count { other ->
                        other.fromSlideId == ref.fromSlideId && other.toSlideId == ref.toSlideId &&
                            DocumentTimelineMutationSupport.phaseForTransitionKind(other.trackPlan.kind) == sharedPhase
                    }
                    .coerceAtLeast(1)
                Text(
                    text = "${sharedPhase.name} duration is shared by $sharedCount ${if (sharedCount == 1) "element" else "elements"}",
                    style = studioCaptionStyle(),
                    color = JewelTheme.globalColors.text.normal.copy(alpha = 0.78f),
                )
            }
        }
        if (isManualNode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Manual start offset", style = studioLabelStyle(), color = JewelTheme.globalColors.text.normal)
                CompactTextButton("Reset", resetManual, "ciacola-track-manual-reset")
            }
        }
    }

    TimelineFlatSection(title = "Curve", tag = "track-curve") {
        DocumentTimelineCurveEditor(
            targetKey = ref.trackId,
            curve = plan.curve,
            onBeginGesture = { controller.beginGesture("Edit curve of $nodeName") },
            onCommitGesture = { controller.commitActiveGesture() },
            onCancelGesture = { controller.cancelActiveGesture() },
            onCurveChanged = { curve, inGesture -> controller.updateTrackCurve(ref, curve, useActiveGesture = inGesture) },
            clipboardWriter = controller.clipboardWriter,
        )
    }

    val fragmentSlots = controller.fragmentSlotsFor(ref)
    if (fragmentSlots.isNotEmpty() || controller.currentTextMorphSpec(ref) != null) {
        IntraTextFragmentStaggerEditor(controller = controller, trackRef = ref)
    }
}

/** `12 words · 45ms cascade` summary for a text track's fragments (bars live in timeline sub-rows). */
internal fun fragmentSummaryLabel(count: Int, by: MorphBy?, stagger: Stagger?): String {
    val unit = when (by) {
        MorphBy.Character -> if (count == 1) "character" else "characters"
        MorphBy.Line -> if (count == 1) "line" else "lines"
        MorphBy.Paragraph -> if (count == 1) "paragraph" else "paragraphs"
        MorphBy.Word, null -> if (count == 1) "word" else "words"
    }
    val ms = stagger?.ms ?: 0
    return if (ms > 0) "$count $unit · ${ms}ms cascade" else "$count $unit · together"
}
