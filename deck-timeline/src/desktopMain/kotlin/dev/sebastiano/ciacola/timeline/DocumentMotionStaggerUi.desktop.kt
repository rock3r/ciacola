package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.SlotPhase
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.StaggerOrder
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

private val PHASE_EXIT_COLOR = Color(0xFFE06C75)
private val PHASE_MOVE_COLOR = Color(0xFF4FB3D9)
private val PHASE_ENTER_COLOR = Color(0xFF66C285)

internal fun phaseAccentColor(phase: SlotPhase): Color = when (phase) {
    SlotPhase.Exit -> PHASE_EXIT_COLOR
    SlotPhase.Move -> PHASE_MOVE_COLOR
    SlotPhase.Enter -> PHASE_ENTER_COLOR
}

internal fun staggerOrderLabel(order: StaggerOrder): String = when (order) {
    StaggerOrder.Document -> "Layer order (as listed on slide)"
    StaggerOrder.TopToBottom -> "Top to bottom"
    StaggerOrder.LeftToRight -> "Left to right"
    StaggerOrder.ReadingOrder -> "Reading order (row by row, left to right)"
}

internal fun staggerOrderChipLabel(order: StaggerOrder): String = when (order) {
    StaggerOrder.Document -> "Layer"
    StaggerOrder.TopToBottom -> "↓ Top"
    StaggerOrder.LeftToRight -> "→ Left"
    StaggerOrder.ReadingOrder -> "Reading"
}

/** Human curve preset name (`Standard`, `Ease in-out`, `Spring`, `Custom`), never a serialized tuple. */
internal fun curveShortLabel(curve: Curve?): String = TimelineDisplayNames.curveName(curve)

private fun findNode(doc: DeckDocument, slideIds: List<String>, nodeId: String): NodeSpec? {
    fun search(nodes: List<NodeSpec>): NodeSpec? {
        for (n in nodes) {
            if (n.id == nodeId) return n
            search(n.children)?.let { return it }
        }
        return null
    }
    for (id in slideIds) {
        val slide = doc.slides.firstOrNull { it.id == id } ?: continue
        search(slide.nodes)?.let { return it }
    }
    return null
}

/**
 * `Animate` inspector for a selected beat (`beats[b]`) or slide transition (`slide.transition`):
 * flat collapsible sections for stagger, phases and the participating elements table.
 */
@Composable
fun DocumentMotionStaggerInspector(
    controller: DocumentTimelineController,
    selection: DocumentTimelineSelection,
    modifier: Modifier = Modifier,
) {
    val doc = controller.store.document
    val focusRingColor = rememberAccessibleFocusRingColor()

    when (selection) {
        is DocumentTimelineSelection.BeatHeader -> {
            val sceneIdx = doc.slides.filter { !it.skipped }.indexOfFirst { it.id == selection.slideId }
            val slide = doc.slides.firstOrNull { it.id == selection.slideId } ?: return
            val beatSpec = slide.beats.getOrNull(selection.beatIndex) ?: return
            val headerInfo = controller.snapshot.beatHeader(selection.slideId, selection.beatIndex)
            val stagger = beatSpec.stagger ?: Stagger()
            val resolvedPhases = controller.resolvedBeatPhases(selection.slideId, selection.beatIndex)

            Column(
                modifier = modifier.fillMaxWidth().testTag("ciacola-motion-stagger-inspector"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnimateInspectorHeader(
                    title = "${TimelineDisplayNames.beatLabel(selection.beatIndex)} · ${TimelineDisplayNames.slideTitle(slide, sceneIdx)}",
                    caption = "Slide ${sceneIdx + 1} · ${slide.beats.size} ${if (slide.beats.size == 1) "build" else "builds"}",
                    rawId = "${selection.slideId} · beats[${selection.beatIndex}]",
                    tag = "ciacola-animate-beat-header",
                )
                TimelineFlatSection(title = "Stagger", tag = "beat-stagger") {
                    StaggerControlsSection(
                        stagger = stagger,
                        focusRingColor = focusRingColor,
                        tagPrefix = "beat",
                        onBeginGesture = { controller.beginGesture("Adjust beat stagger") },
                        onCommitGesture = { controller.commitActiveGesture() },
                        onStaggerMsChanged = { ms, inGesture ->
                            controller.updateBeatStagger(selection.slideId, selection.beatIndex, ms = ms, useActiveGesture = inGesture)
                        },
                        onOrderSelected = { order -> controller.updateBeatStagger(selection.slideId, selection.beatIndex, order = order) },
                        onReverseChanged = { rev -> controller.updateBeatStagger(selection.slideId, selection.beatIndex, reverse = rev) },
                    )
                }
                TimelineFlatSection(title = "Phases", tag = "beat-phases") {
                    InteractivePhaseBarsEditor(
                        resolvedPhases = resolvedPhases,
                        tagPrefix = "beat",
                        onBeginGesture = { phase -> controller.beginGesture("Adjust beat ${phase.name.lowercase()} phase") },
                        onCommitGesture = { controller.commitActiveGesture() },
                        onCancelGesture = { controller.cancelActiveGesture() },
                        onPhaseUpdated = { phase, delayMs, durationMs, curve, inGesture ->
                            controller.updateBeatPhaseTiming(
                                slideId = selection.slideId,
                                beatIndex = selection.beatIndex,
                                phase = phase,
                                delayMs = delayMs,
                                durationMs = durationMs,
                                curve = curve,
                                useActiveGesture = inGesture,
                            )
                        },
                    )
                }
                val participating = headerInfo?.participatingNodeIds.orEmpty()
                if (participating.isNotEmpty() || stagger.manual.isNotEmpty()) {
                    ParticipatingNodeManualDelaysSection(
                        nodeIds = (participating + stagger.manual.keys).distinct(),
                        manualMap = stagger.manual,
                        displayName = { id -> TimelineDisplayNames.nodeName(findNode(doc, listOf(selection.slideId), id), id, controller.specimens) },
                        plannedDelayForNode = { nodeId -> controller.plannedNodeDelayMs(selection.slideId, selection.beatIndex, nodeId) },
                        onSetManual = { nodeId, ms -> controller.setBeatNodeManualDelay(selection.slideId, selection.beatIndex, nodeId, ms) },
                        onResetManual = { nodeId -> controller.resetBeatNodeManualDelay(selection.slideId, selection.beatIndex, nodeId) },
                    )
                }
            }
        }

        is DocumentTimelineSelection.TransitionHeader -> {
            val headerInfo = controller.snapshot.transitionHeader(selection.fromSlideId, selection.toSlideId)
            val transition = controller.effectiveTransitionSpec(selection.fromSlideId)
            val stagger = transition.stagger ?: Stagger()
            val resolvedPhases = controller.resolvedTransitionPhases(selection.fromSlideId)
            val activeSlides = doc.slides.filter { !it.skipped }
            val toIdx = activeSlides.indexOfFirst { it.id == selection.toSlideId }
            val toTitle = TimelineDisplayNames.slideTitle(activeSlides.getOrNull(toIdx), toIdx)

            Column(
                modifier = modifier.fillMaxWidth().testTag("ciacola-motion-stagger-inspector"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnimateInspectorHeader(
                    title = "Transition → $toTitle",
                    caption = TimelineDisplayNames.transitionTypeName(transition.type),
                    rawId = "${selection.fromSlideId} → ${selection.toSlideId}",
                    tag = "ciacola-animate-transition-header",
                )
                TimelineFlatSection(title = "Transition", tag = "transition-type") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Type", style = studioLabelStyle(), color = JewelTheme.globalColors.text.normal)
                        Dropdown(
                            modifier = Modifier
                                .width(160.dp)
                                .studioActivatableFocus(focusRingColor)
                                .testTag("ciacola-transition-type-dropdown"),
                            menuContent = {
                                for (type in TransitionType.entries) {
                                    selectableItem(
                                        selected = transition.type == type,
                                        onClick = { controller.updateTransitionType(selection.fromSlideId, type) },
                                    ) { Text(TimelineDisplayNames.transitionTypeName(type), style = studioLabelStyle()) }
                                }
                            },
                        ) { Text(TimelineDisplayNames.transitionTypeName(transition.type), style = studioLabelStyle()) }
                    }
                    if (transition.type != TransitionType.MagicMove) {
                        // Never silently clobber a Dissolve/Push/None transition into Magic Move.
                        Text(
                            text = "Stagger and Exit / Move / Enter phases apply to Magic Move transitions.",
                            style = studioCaptionStyle(),
                            color = JewelTheme.globalColors.text.normal.copy(alpha = 0.8f),
                        )
                        CompactTextButton(
                            label = "Switch to Magic Move",
                            onClick = { controller.updateTransitionType(selection.fromSlideId, TransitionType.MagicMove) },
                            tag = "ciacola-transition-switch-magic-move",
                            enabled = !controller.store.isReadOnly,
                        )
                    }
                }

                if (transition.type != TransitionType.MagicMove) {
                    if (transition.type != TransitionType.None) {
                        val trTrackRef = controller.snapshot.trackRefsByTrackId.values
                            .filterIsInstance<DocumentTimelineTrackRef.TransitionTrackBar>()
                            .firstOrNull { it.fromSlideId == selection.fromSlideId && it.toSlideId == selection.toSlideId }
                        if (trTrackRef != null) {
                            TimelineFlatSection(title = "Curve", tag = "transition-curve") {
                                DocumentTimelineCurveEditor(
                                    targetKey = "transition:${selection.fromSlideId}",
                                    curve = transition.curve ?: Curve.EaseInOut,
                                    onBeginGesture = { controller.beginGesture("Adjust transition curve") },
                                    onCommitGesture = { controller.commitActiveGesture() },
                                    onCancelGesture = { controller.cancelActiveGesture() },
                                    onCurveChanged = { nextCurve, inGesture ->
                                        controller.updateTrackCurve(trTrackRef, nextCurve, useActiveGesture = inGesture)
                                    },
                                    clipboardWriter = controller.clipboardWriter,
                                )
                            }
                        }
                    }
                } else {
                    TimelineFlatSection(title = "Stagger", tag = "transition-stagger") {
                        StaggerControlsSection(
                            stagger = stagger,
                            focusRingColor = focusRingColor,
                            tagPrefix = "transition",
                            onBeginGesture = { controller.beginGesture("Adjust transition stagger") },
                            onCommitGesture = { controller.commitActiveGesture() },
                            onStaggerMsChanged = { ms, inGesture ->
                                controller.updateTransitionStagger(selection.fromSlideId, ms = ms, useActiveGesture = inGesture)
                            },
                            onOrderSelected = { order -> controller.updateTransitionStagger(selection.fromSlideId, order = order) },
                            onReverseChanged = { rev -> controller.updateTransitionStagger(selection.fromSlideId, reverse = rev) },
                        )
                    }
                    TimelineFlatSection(title = "Phases", tag = "transition-phases") {
                        InteractivePhaseBarsEditor(
                            resolvedPhases = resolvedPhases,
                            tagPrefix = "transition",
                            onBeginGesture = { phase -> controller.beginGesture("Adjust transition ${phase.name.lowercase()} phase") },
                            onCommitGesture = { controller.commitActiveGesture() },
                            onCancelGesture = { controller.cancelActiveGesture() },
                            onPhaseUpdated = { phase, delayMs, durationMs, curve, inGesture ->
                                controller.updateTransitionPhaseTiming(
                                    fromSlideId = selection.fromSlideId,
                                    phase = phase,
                                    delayMs = delayMs,
                                    durationMs = durationMs,
                                    curve = curve,
                                    useActiveGesture = inGesture,
                                )
                            },
                        )
                    }
                    val participating = headerInfo?.participatingNodeIds.orEmpty()
                    if (participating.isNotEmpty() || stagger.manual.isNotEmpty()) {
                        val slideIds = listOf(selection.toSlideId, selection.fromSlideId)
                        ParticipatingNodeManualDelaysSection(
                            nodeIds = (participating + stagger.manual.keys).distinct(),
                            manualMap = stagger.manual,
                            displayName = { id -> TimelineDisplayNames.nodeName(findNode(doc, slideIds, id), id, controller.specimens) },
                            plannedDelayForNode = { nodeId ->
                                controller.plannedTransitionNodeDelayMs(selection.fromSlideId, selection.toSlideId, nodeId)
                            },
                            onSetManual = { nodeId, ms -> controller.setTransitionNodeManualDelay(selection.fromSlideId, nodeId, ms) },
                            onResetManual = { nodeId -> controller.resetTransitionNodeManualDelay(selection.fromSlideId, nodeId) },
                        )
                    }
                }
            }
        }

        else -> Unit
    }
}

/** Stagger rule: scrubbable `Stagger` ms field, order chips and reverse toggle. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StaggerControlsSection(
    stagger: Stagger,
    focusRingColor: Color,
    tagPrefix: String,
    onBeginGesture: () -> Unit,
    onCommitGesture: () -> Unit,
    onStaggerMsChanged: (ms: Int, inGesture: Boolean) -> Unit,
    onOrderSelected: (StaggerOrder) -> Unit,
    onReverseChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("ciacola-$tagPrefix-stagger-controls")) {
        ScrubbableMsField(
            label = "Stagger",
            value = stagger.ms,
            step = 5,
            range = 0..1_000,
            tag = "$tagPrefix-stagger",
            onValueChange = onStaggerMsChanged,
            onBeginGesture = onBeginGesture,
            onCommitGesture = onCommitGesture,
        )
        Text("Order", style = studioLabelStyle(), color = JewelTheme.globalColors.text.normal)
        Row(
            modifier = Modifier.fillMaxWidth().height(STUDIO_CONTROL_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val above = remember {
                TooltipPlacement.ComponentRect(anchor = Alignment.TopCenter, alignment = Alignment.TopCenter, offset = DpOffset(0.dp, (-6).dp))
            }
            for (order in StaggerOrder.entries) {
                Tooltip(tooltip = { Text(staggerOrderLabel(order)) }, tooltipPlacement = above, modifier = Modifier.weight(1f)) {
                    CompactToggleChip(
                        label = staggerOrderChipLabel(order),
                        selected = stagger.order == order,
                        onClick = { onOrderSelected(order) },
                        tag = "ciacola-$tagPrefix-order-${order.name}",
                        modifier = Modifier.fillMaxWidth().studioActivatableFocus(focusRingColor),
                    )
                }
            }
        }
        CheckboxRow(
            text = "Reverse order",
            checked = stagger.reverse,
            onCheckedChange = onReverseChanged,
            textStyle = studioLabelStyle(),
            modifier = Modifier.testTag("ciacola-$tagPrefix-stagger-reverse"),
        )
    }
}

/** Compact `Element | Start` table: auto offsets read `Auto`, typing a value makes it manual. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParticipatingNodeManualDelaysSection(
    nodeIds: List<String>,
    manualMap: Map<String, Int>,
    displayName: (String) -> String,
    plannedDelayForNode: (String) -> Int,
    onSetManual: (nodeId: String, delayMs: Int) -> Unit,
    onResetManual: (nodeId: String) -> Unit,
) {
    val colors = JewelTheme.globalColors
    TimelineFlatSection(title = "Elements · ${nodeIds.size}", tag = "participants") {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Element", style = studioCaptionStyle(), color = colors.text.normal.copy(alpha = 0.78f), modifier = Modifier.weight(1f))
            Text("Start", style = studioCaptionStyle(), color = colors.text.normal.copy(alpha = 0.78f))
        }
        for (nodeId in nodeIds) {
            val manualMs = manualMap[nodeId]
            val effectiveMs = manualMs ?: plannedDelayForNode(nodeId)
            Row(
                modifier = Modifier.fillMaxWidth().height(STUDIO_CONTROL_HEIGHT).testTag("ciacola-stagger-node-row-$nodeId"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tooltip(tooltip = { Text(nodeId) }, modifier = Modifier.weight(1f)) {
                    Text(displayName(nodeId), style = studioLabelStyle(), color = colors.text.normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (manualMs != null) {
                    CompactToggleChip(
                        label = "Manual",
                        selected = true,
                        onClick = { onResetManual(nodeId) },
                        tag = "ciacola-stagger-reset-node-$nodeId",
                    )
                } else {
                    Text("Auto", style = studioCaptionStyle(), color = colors.text.normal.copy(alpha = 0.78f), modifier = Modifier.testTag("ciacola-stagger-auto-$nodeId"))
                }
                ScrubbableMsField(
                    label = "",
                    labelWidth = 0.dp,
                    value = effectiveMs,
                    tag = "node-$nodeId",
                    onValueChange = { v, _ -> onSetManual(nodeId, v) },
                )
            }
        }
    }
}

/**
 * Text fragment stagger for a text morph / delivery track. Per-fragment bars live in the timeline
 * sub-rows, so this shows the rule (stagger, order, phases) and a count — never a duplicate list.
 */
@Composable
fun IntraTextFragmentStaggerEditor(
    controller: DocumentTimelineController,
    trackRef: DocumentTimelineTrackRef,
    modifier: Modifier = Modifier,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    val slots = controller.fragmentSlotsFor(trackRef)
    val manualMap = controller.currentFragmentManualDelays(trackRef)
    val morphSpec = controller.currentTextMorphSpec(trackRef)
    val resolvedMorphPhases = controller.resolvedTextMorphPhases(trackRef)

    Column(modifier = modifier.fillMaxWidth().testTag("ciacola-intra-text-stagger-editor")) {
        TimelineFlatSection(title = "Text fragments", tag = "text-fragments") {
            Text(
                text = fragmentSummaryLabel(slots.size, morphSpec?.by, morphSpec?.stagger),
                style = studioLabelStyle(),
                color = JewelTheme.globalColors.text.normal,
                modifier = Modifier.testTag("ciacola-fragment-summary"),
            )
            if (morphSpec != null) {
                StaggerControlsSection(
                    stagger = morphSpec.stagger ?: Stagger(),
                    focusRingColor = focusRingColor,
                    tagPrefix = "text-morph",
                    onBeginGesture = { controller.beginGesture("Adjust text fragment stagger") },
                    onCommitGesture = { controller.commitActiveGesture() },
                    onStaggerMsChanged = { ms, inGesture -> controller.updateTextMorphStagger(trackRef, ms = ms, useActiveGesture = inGesture) },
                    onOrderSelected = { order -> controller.updateTextMorphStagger(trackRef, order = order) },
                    onReverseChanged = { rev -> controller.updateTextMorphStagger(trackRef, reverse = rev) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val expanded = controller.expandedTextTrackId == trackRef.trackId
                CompactTextButton(
                    label = if (expanded) "Hide fragment bars" else "Show fragment bars",
                    onClick = { controller.toggleExpandTextTrack(trackRef.trackId) },
                    tag = "ciacola-fragment-toggle-subrows",
                )
                if (manualMap.isNotEmpty()) {
                    CompactTextButton(
                        label = "Reset ${manualMap.size} manual",
                        onClick = { manualMap.keys.toList().forEach { controller.resetFragmentManualDelay(trackRef, it) } },
                        tag = "ciacola-fragment-reset-all",
                    )
                }
            }
        }
        if (morphSpec != null) {
            TimelineFlatSection(title = "Morph phases", tag = "text-morph-phases") {
                InteractivePhaseBarsEditor(
                    resolvedPhases = resolvedMorphPhases,
                    tagPrefix = "text-morph",
                    onBeginGesture = { phase -> controller.beginGesture("Adjust text morph ${phase.name.lowercase()} phase") },
                    onCommitGesture = { controller.commitActiveGesture() },
                    onCancelGesture = { controller.cancelActiveGesture() },
                    onPhaseUpdated = { phase, delayMs, durationMs, curve, inGesture ->
                        controller.updateTextMorphPhaseTiming(
                            ref = trackRef,
                            phase = phase,
                            delayMs = delayMs,
                            durationMs = durationMs,
                            curve = curve,
                            useActiveGesture = inGesture,
                        )
                    },
                )
            }
        }
    }
}
