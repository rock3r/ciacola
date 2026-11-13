package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

private val INSPECTOR_WIDTH = 304.dp

/**
 * Right-hand After Effects Keyframe, Bezier Curve, & Spatial Motion-Path Inspector.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelineBlockInspector(
    controller: TimelineStudioController,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val activeLoopId = controller.loopClock.activeSubCompLoopId
    if (activeLoopId != null) {
        LoopSubCompInspector(controller, activeLoopId, modifier, fillWidth = fillWidth)
        return
    }

    val selectedId = controller.selectedTrackId
    val selectedRow = controller.snapshot.tracks.firstOrNull { it.trackId == selectedId }
    val primaryBlock = selectedRow?.blocks?.firstOrNull()

    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(INSPECTOR_WIDTH))
            .fillMaxHeight()
            .background(globalColors.panelBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectedRow == null || primaryBlock == null) {
                GroupHeader("Block Inspector:")
                InfoText("Click any animation block or layer row in the timeline to tweak its beat, timing, Bezier curve, or spatial trajectory live.")
                return@Column
            }

        // Read the revisions so every edit recomposes the Inspector right away: the indexed
        // snapshot is refreshed only after a debounce, and steppers compute from these values.
        controller.overridesStore.parameterRevision
        controller.overridesStore.structuralRevision
        val overridesData = controller.overridesStore.data()
        val hasTrackOverride = if (selectedRow.category == TrackCategory.LoopSubComp && primaryBlock.loopId != null) {
            primaryBlock.loopId in overridesData.loopPeriodsMs
        } else {
            selectedRow.trackId in overridesData.tracks
        }
        val currentOverride = overridesData.tracks[selectedRow.trackId]
            ?: TrackOverride(trackId = selectedRow.trackId)
        val palette = JewelTheme.colorPalette
        val isDark = JewelTheme.isDark
        val overrideAccent = palette.yellowOrNull(if (isDark) 6 else 5) ?: Color(0xFFE0A82E)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedRow.label,
                style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.SemiBold),
                color = globalColors.text.normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasTrackOverride) {
                Tooltip(tooltip = { Text("This track has live timeline overrides") }) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .background(overrideAccent.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                            .border(1.dp, overrideAccent.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .testTag("ciacola-inspector-override-badge"),
                    ) {
                        Text(
                            text = "● Overridden",
                            style = JewelTheme.typography.small,
                            color = globalColors.text.normal,
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selectedRow.category == TrackCategory.LoopSubComp && primaryBlock.loopId != null) {
                Tooltip(tooltip = { Text("Inspect harmonic wave channels for this loop") }) {
                    DefaultButton(
                        onClick = {
                            controller.enterLoopSubComp(scope, primaryBlock.loopId, primaryBlock.sceneIndex)
                        },
                        modifier = Modifier
                            .studioActivatableFocus(focusRingColor)
                            .testTag("ciacola-inspector-enter-subcomp"),
                    ) {
                        Text("Enter Sub-Comp ▸", style = JewelTheme.typography.small)
                    }
                }
                Tooltip(tooltip = { Text("Reset cycle period override for this loop") }) {
                    OutlinedButton(
                        onClick = { controller.resetLoopPeriod(primaryBlock.loopId) },
                        enabled = hasTrackOverride,
                        modifier = Modifier
                            .studioActivatableFocus(focusRingColor)
                            .testTag("ciacola-inspector-reset-track"),
                    ) {
                        Text("Reset Track", style = JewelTheme.typography.small)
                    }
                }
            } else {
                Tooltip(tooltip = { Text("Preview only the selected animation block") }) {
                    DefaultButton(
                        onClick = { controller.previewSelectedBlock(scope) },
                        modifier = Modifier
                            .studioActivatableFocus(focusRingColor)
                            .testTag("ciacola-inspector-preview"),
                    ) {
                        Text("Preview ▶", style = JewelTheme.typography.small)
                    }
                }
                Tooltip(tooltip = { Text("Reset all overrides for this track") }) {
                    OutlinedButton(
                        onClick = { controller.overridesStore.resetTrack(selectedRow.trackId) },
                        enabled = hasTrackOverride,
                        modifier = Modifier
                            .studioActivatableFocus(focusRingColor)
                            .testTag("ciacola-inspector-reset-track"),
                    ) {
                        Text("Reset Track", style = JewelTheme.typography.small)
                    }
                }
            }
        }

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        if (selectedRow.category == TrackCategory.LoopSubComp && primaryBlock.loopId != null) {
            val loopId = primaryBlock.loopId
            val desc = controller.loopClock.registeredLoops[loopId]
            val defaultPeriod = desc?.defaultPeriodMillis ?: primaryBlock.loopPeriodMs ?: 6_000L
            val currentPeriod = overridesData.loopPeriodsMs[loopId]
                ?: controller.loopClock.effectivePeriodMillis(loopId, defaultPeriod)
            GroupHeader(if (hasTrackOverride) "● Loop Sub-Composition:" else "Loop Sub-Composition:")
            NumericStepperRow(
                label = "Cycle Period",
                valueText = "${currentPeriod}ms",
                decTag = "ciacola-loop-period-dec",
                incTag = "ciacola-loop-period-inc",
                onDec = {
                    controller.updateLoopPeriod(loopId, (currentPeriod - 500L).coerceAtLeast(500L))
                },
                onInc = {
                    controller.updateLoopPeriod(loopId, (currentPeriod + 500L).coerceAtMost(30_000L))
                },
            )
            InfoText("Enter the loop sub-composition to scrub harmonic channels directly on the slide canvas.")
            return@Column
        }

            // 1. Timing Controls
            val timingOverridden = currentOverride.enterBeat != null ||
                currentOverride.durationMs != null ||
                currentOverride.delayMs != null ||
                currentOverride.staggerStepMs != null
            GroupHeader(if (timingOverridden) "● Timing:" else "Timing:")
            val maxBeatInScene = ((controller.animatic.storyboard.scenes
                .getOrNull(primaryBlock.sceneIndex)?.frames?.size ?: 1) - 1).coerceAtLeast(0)
            val effectiveBeat = (currentOverride.enterBeat ?: primaryBlock.triggerBeatInScene).coerceIn(0, maxBeatInScene)
            val staggerCount = (primaryBlock.staggerCount ?: 1).coerceAtLeast(1)
            val effectiveStaggerStep = currentOverride.staggerStepMs ?: primaryBlock.staggerStepMs ?: 55
            val staggerSpreadMs = if (selectedRow.category == TrackCategory.StaggerGroup && staggerCount > 1) {
                (staggerCount - 1) * effectiveStaggerStep
            } else {
                0
            }
            val baseDuration = primaryBlock.morphDurationMs ?: primaryBlock.durationMs
            val overrideDurationMs = currentOverride.durationMs
            val effectiveDuration = when {
                selectedRow.category == TrackCategory.StaggerGroup && overrideDurationMs != null ->
                    overrideDurationMs + staggerSpreadMs
                else -> overrideDurationMs ?: baseDuration
            }
            val effectiveDelay = currentOverride.delayMs ?: primaryBlock.delayMs

            // Shared elements, code morphs, and MagicText animate on their own state change, so a
            // free trigger beat would only desynchronise the bar from what the stage plays.
            val supportsTriggerBeat = selectedRow.category != TrackCategory.SharedElement &&
                selectedRow.category != TrackCategory.WarpCode &&
                selectedRow.category != TrackCategory.MagicText
            if (supportsTriggerBeat) {
                NumericStepperRow(
                    label = "Trigger Beat",
                    valueText = "B$effectiveBeat",
                    decTag = "ciacola-inspector-beat-dec",
                    incTag = "ciacola-inspector-beat-inc",
                    onDec = {
                        controller.overridesStore.updateTrack(selectedRow.trackId) {
                            it.copy(enterBeat = (effectiveBeat - 1).coerceAtLeast(0))
                        }
                    },
                    onInc = {
                        controller.overridesStore.updateTrack(selectedRow.trackId) {
                            it.copy(enterBeat = (effectiveBeat + 1).coerceIn(0, maxBeatInScene))
                        }
                    },
                )
            }
            NumericStepperRow(
                label = "Duration",
                valueText = "${effectiveDuration}ms",
                decTag = "ciacola-inspector-dur-dec",
                incTag = "ciacola-inspector-dur-inc",
                onDec = {
                    val nextTotal = (effectiveDuration - 50).coerceAtLeast(60 + staggerSpreadMs)
                    val stored = (nextTotal - staggerSpreadMs).coerceAtLeast(60)
                    controller.overridesStore.updateTrack(selectedRow.trackId) {
                        it.copy(durationMs = stored)
                    }
                },
                onInc = {
                    val nextTotal = (effectiveDuration + 50).coerceAtMost(4_000 + staggerSpreadMs)
                    val stored = (nextTotal - staggerSpreadMs).coerceIn(60, 4_000)
                    controller.overridesStore.updateTrack(selectedRow.trackId) {
                        it.copy(durationMs = stored)
                    }
                },
            )
        NumericStepperRow(
            label = "Delay",
            valueText = "${effectiveDelay}ms",
            decTag = "ciacola-inspector-delay-dec",
            incTag = "ciacola-inspector-delay-inc",
            onDec = {
                controller.overridesStore.updateTrack(selectedRow.trackId) {
                    it.copy(delayMs = (effectiveDelay - 25).coerceAtLeast(0))
                }
            },
            onInc = {
                controller.overridesStore.updateTrack(selectedRow.trackId) {
                    it.copy(delayMs = (effectiveDelay + 25).coerceAtMost(2_000))
                }
            },
        )

        if (selectedRow.category == TrackCategory.StaggerGroup) {
            val effStep = currentOverride.staggerStepMs ?: 55
            NumericStepperRow(
                label = "Stagger Step",
                valueText = "${effStep}ms",
                decTag = "ciacola-inspector-stagger-dec",
                incTag = "ciacola-inspector-stagger-inc",
                onDec = {
                    controller.overridesStore.updateTrack(selectedRow.trackId) {
                        it.copy(staggerStepMs = (effStep - 15).coerceAtLeast(0))
                    }
                },
                onInc = {
                    controller.overridesStore.updateTrack(selectedRow.trackId) {
                        it.copy(staggerStepMs = (effStep + 15).coerceAtMost(600))
                    }
                },
            )
        }

        // 2. Temporal Curve Editor (Interactive 2D Cubic-Bezier Graph + Expanded Presets + Numeric Inputs)
        TimeInterpolationInspectorSection(
            controller = controller,
            trackId = selectedRow.trackId,
            currentOverride = currentOverride,
        )

        // 3. Spatial Interpolation Editor (only shown for categories that consume spatial overrides)
        val supportsSlideOffset = selectedRow.category == TrackCategory.Build ||
            selectedRow.category == TrackCategory.StaggerGroup ||
            selectedRow.category == TrackCategory.StaggerItem
        val spatialHeader = if (currentOverride.spatial != null) "● Spatial interpolation:" else "Spatial interpolation:"
        if (selectedRow.category == TrackCategory.SharedElement) {
            GroupHeader(spatialHeader)
            val spatial = currentOverride.spatial ?: TimelineSpatialSpec(offsetXDp = 0f, offsetYDp = 0f)
            Text(
                text = "Arc bend: ${(spatial.arcBend * 100).roundToInt()}%",
                style = JewelTheme.typography.small,
                color = globalColors.text.info,
            )
            Slider(
                value = spatial.arcBend,
                onValueChange = { newBend ->
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                        it.copy(spatial = (it.spatial ?: TimelineSpatialSpec(offsetXDp = 0f, offsetYDp = 0f)).copy(arcBend = newBend))
                    }
                },
                valueRange = -1f..1f,
                modifier = Modifier.fillMaxWidth().testTag("ciacola-inspector-arc-slider"),
            )
        } else if (supportsSlideOffset) {
            GroupHeader(spatialHeader)
            val defaultSpatial = TimelineSpatialSpec(
                offsetXDp = primaryBlock.defaultOffsetXDp,
                offsetYDp = primaryBlock.defaultOffsetYDp,
            )
            val spatial = currentOverride.spatial ?: defaultSpatial
            Text(
                text = "Offset X: ${spatial.offsetXDp.roundToInt()}dp",
                style = JewelTheme.typography.small,
                color = globalColors.text.info,
            )
            Slider(
                value = spatial.offsetXDp,
                onValueChange = { newX ->
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                        it.copy(spatial = (it.spatial ?: defaultSpatial).copy(offsetXDp = newX))
                    }
                },
                valueRange = -96f..96f,
                modifier = Modifier.fillMaxWidth().testTag("ciacola-inspector-offset-x-slider"),
            )
            Text(
                text = "Offset Y: ${spatial.offsetYDp.roundToInt()}dp",
                style = JewelTheme.typography.small,
                color = globalColors.text.info,
            )
            Slider(
                value = spatial.offsetYDp,
                onValueChange = { newY ->
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                        it.copy(spatial = (it.spatial ?: defaultSpatial).copy(offsetYDp = newY))
                    }
                },
                valueRange = -96f..96f,
                modifier = Modifier.fillMaxWidth().testTag("ciacola-inspector-offset-y-slider"),
            )
        }
        }
        VerticalScrollbar(
            scrollState = scroll,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .testTag("ciacola-inspector-vscrollbar"),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LoopSubCompInspector(
    controller: TimelineStudioController,
    loopId: String,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    controller.overridesStore.parameterRevision
    val overridesData = controller.overridesStore.data()
    val isOverridden = loopId in overridesData.loopPeriodsMs
    val desc = controller.loopClock.registeredLoops[loopId]
    val defaultPeriod = desc?.defaultPeriodMillis ?: 6_000L
    val currentPeriod = overridesData.loopPeriodsMs[loopId]
        ?: controller.loopClock.effectivePeriodMillis(loopId, defaultPeriod)

    Column(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(INSPECTOR_WIDTH))
            .fillMaxHeight()
            .background(globalColors.panelBackground)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GroupHeader(if (isOverridden) "● Loop Sub-Composition:" else "Loop Sub-Composition:")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = desc?.label ?: loopId,
                style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.SemiBold),
                color = globalColors.text.normal,
                modifier = Modifier.weight(1f),
            )
            Tooltip(tooltip = { Text("Reset cycle period override for this loop") }) {
                OutlinedButton(
                    onClick = { controller.resetLoopPeriod(loopId) },
                    enabled = isOverridden,
                    modifier = Modifier
                        .studioActivatableFocus(focusRingColor)
                        .testTag("ciacola-inspector-reset-track"),
                ) {
                    Text("Reset Track", style = JewelTheme.typography.small)
                }
            }
        }
        NumericStepperRow(
            label = "Cycle Period",
            valueText = "${currentPeriod}ms",
            decTag = "ciacola-loop-period-dec",
            incTag = "ciacola-loop-period-inc",
            onDec = {
                controller.updateLoopPeriod(loopId, (currentPeriod - 500L).coerceAtLeast(500L))
            },
            onInc = {
                controller.updateLoopPeriod(loopId, (currentPeriod + 500L).coerceAtMost(30_000L))
            },
        )
        InfoText("Scrubbing the sub-comp playhead drives rememberDeckLoopMillis directly on the live slide canvas.")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NumericStepperRow(
    label: String,
    valueText: String,
    decTag: String,
    incTag: String,
    onDec: () -> Unit,
    onInc: () -> Unit,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = JewelTheme.typography.small, color = JewelTheme.globalColors.text.info)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tooltip(tooltip = { Text("Decrease $label") }) {
                IconButton(
                    onClick = onDec,
                    modifier = Modifier
                        .size(22.dp)
                        .studioActivatableFocus(focusRingColor)
                        .testTag(decTag),
                ) {
                    Icon(AllIconsKeys.General.Remove, contentDescription = "Decrease $label", modifier = Modifier.size(12.dp))
                }
            }
            Text(
                text = valueText,
                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                color = JewelTheme.globalColors.text.normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(58.dp).testTag("$decTag-value"),
            )
            Tooltip(tooltip = { Text("Increase $label") }) {
                IconButton(
                    onClick = onInc,
                    modifier = Modifier
                        .size(22.dp)
                        .studioActivatableFocus(focusRingColor)
                        .testTag(incTag),
                ) {
                    Icon(AllIconsKeys.General.Add, contentDescription = "Increase $label", modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}
