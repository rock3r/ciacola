package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.awt.Cursor
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
                onCommitTypedValue = { raw ->
                    parseNumericStepperInt(raw)?.let { parsed ->
                        controller.updateLoopPeriod(loopId, parsed.toLong().coerceIn(500L, 30_000L))
                    }
                },
                onDragStart = { controller.overridesStore.beginEditGesture() },
                onDragEnd = { controller.overridesStore.endEditGesture() },
                onDragSteps = { steps ->
                    val next = (currentPeriod + steps * 100L).coerceIn(500L, 30_000L)
                    controller.updateLoopPeriod(loopId, next)
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
                    onCommitTypedValue = { raw ->
                        parseNumericStepperInt(raw)?.let { parsed ->
                            controller.overridesStore.updateTrack(selectedRow.trackId) {
                                it.copy(enterBeat = parsed.coerceIn(0, maxBeatInScene))
                            }
                        }
                    },
                    onDragStart = { controller.overridesStore.beginEditGesture() },
                    onDragEnd = { controller.overridesStore.endEditGesture() },
                    onDragSteps = { steps ->
                        controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                            it.copy(enterBeat = (effectiveBeat + steps).coerceIn(0, maxBeatInScene))
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
                onCommitTypedValue = { raw ->
                    parseNumericStepperInt(raw)?.let { parsed ->
                        val nextTotal = parsed.coerceIn(60 + staggerSpreadMs, 4_000 + staggerSpreadMs)
                        val stored = (nextTotal - staggerSpreadMs).coerceIn(60, 4_000)
                        controller.overridesStore.updateTrack(selectedRow.trackId) {
                            it.copy(durationMs = stored)
                        }
                    }
                },
                onDragStart = { controller.overridesStore.beginEditGesture() },
                onDragEnd = { controller.overridesStore.endEditGesture() },
                onDragSteps = { steps ->
                    val nextTotal = (effectiveDuration + steps * 25).coerceIn(60 + staggerSpreadMs, 4_000 + staggerSpreadMs)
                    val stored = (nextTotal - staggerSpreadMs).coerceIn(60, 4_000)
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
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
            onCommitTypedValue = { raw ->
                parseNumericStepperInt(raw)?.let { parsed ->
                    controller.overridesStore.updateTrack(selectedRow.trackId) {
                        it.copy(delayMs = parsed.coerceIn(0, 2_000))
                    }
                }
            },
            onDragStart = { controller.overridesStore.beginEditGesture() },
            onDragEnd = { controller.overridesStore.endEditGesture() },
            onDragSteps = { steps ->
                controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                    it.copy(delayMs = (effectiveDelay + steps * 15).coerceIn(0, 2_000))
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
                onCommitTypedValue = { raw ->
                    parseNumericStepperInt(raw)?.let { parsed ->
                        controller.overridesStore.updateTrack(selectedRow.trackId) {
                            it.copy(staggerStepMs = parsed.coerceIn(0, 600))
                        }
                    }
                },
                onDragStart = { controller.overridesStore.beginEditGesture() },
                onDragEnd = { controller.overridesStore.endEditGesture() },
                onDragSteps = { steps ->
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                        it.copy(staggerStepMs = (effStep + steps * 10).coerceIn(0, 600))
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
            TimelineSlider(
                value = spatial.arcBend,
                valueRange = -1f..1f,
                gestureKey = "arc:${selectedRow.trackId}",
                onGestureStart = { controller.overridesStore.beginEditGesture() },
                onGestureEnd = { controller.overridesStore.endEditGesture() },
                onValueChange = { newBend ->
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                        it.copy(spatial = (it.spatial ?: TimelineSpatialSpec(offsetXDp = 0f, offsetYDp = 0f)).copy(arcBend = newBend))
                    }
                },
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
            TimelineSlider(
                value = spatial.offsetXDp,
                valueRange = -96f..96f,
                gestureKey = "offsetX:${selectedRow.trackId}",
                onGestureStart = { controller.overridesStore.beginEditGesture() },
                onGestureEnd = { controller.overridesStore.endEditGesture() },
                onValueChange = { newX ->
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                        it.copy(spatial = (it.spatial ?: defaultSpatial).copy(offsetXDp = newX))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("ciacola-inspector-offset-x-slider"),
            )
            Text(
                text = "Offset Y: ${spatial.offsetYDp.roundToInt()}dp",
                style = JewelTheme.typography.small,
                color = globalColors.text.info,
            )
            TimelineSlider(
                value = spatial.offsetYDp,
                valueRange = -96f..96f,
                gestureKey = "offsetY:${selectedRow.trackId}",
                onGestureStart = { controller.overridesStore.beginEditGesture() },
                onGestureEnd = { controller.overridesStore.endEditGesture() },
                onValueChange = { newY ->
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                        it.copy(spatial = (it.spatial ?: defaultSpatial).copy(offsetYDp = newY))
                    }
                },
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
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 16.dp),
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
            onCommitTypedValue = { raw ->
                parseNumericStepperInt(raw)?.let { parsed ->
                    controller.updateLoopPeriod(loopId, parsed.toLong().coerceIn(500L, 30_000L))
                }
            },
            onDragStart = { controller.overridesStore.beginEditGesture() },
            onDragEnd = { controller.overridesStore.endEditGesture() },
            onDragSteps = { steps ->
                controller.updateLoopPeriod(loopId, (currentPeriod + steps * 100L).coerceIn(500L, 30_000L))
            },
        )
        InfoText("Scrubbing the sub-comp playhead drives rememberDeckLoopMillis directly on the live slide canvas.")
    }
}

private fun parseNumericStepperInt(raw: String): Int? {
    val cleaned = raw.trim()
        .removePrefix("B")
        .removePrefix("b")
        .removeSuffix("ms")
        .removeSuffix("MS")
        .trim()
    return cleaned.toIntOrNull()
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
    onCommitTypedValue: ((String) -> Unit)? = null,
    onDragSteps: ((Int) -> Unit)? = null,
    onDragStart: (() -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    val globalColors = JewelTheme.globalColors
    var editing by remember(decTag) { mutableStateOf(false) }
    var draft by remember(decTag, valueText) { mutableStateOf(valueText) }
    var inputHadFocus by remember(decTag) { mutableStateOf(false) }
    val inputFocusRequester = remember(decTag) { FocusRequester() }
    val latestOnDragSteps by rememberUpdatedState(onDragSteps)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    LaunchedEffect(editing) {
        if (editing) {
            inputHadFocus = false
            runCatching { inputFocusRequester.requestFocus() }
        }
    }

    val labelModifier = if (onDragSteps != null) {
        Modifier
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(decTag) {
                var accumPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        accumPx = 0f
                        latestOnDragStart?.invoke()
                    },
                    onDragEnd = { latestOnDragEnd?.invoke() },
                    onDragCancel = { latestOnDragEnd?.invoke() },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        accumPx += dragAmount
                        val stepPx = 10f
                        val steps = (accumPx / stepPx).toInt()
                        if (steps != 0) {
                            accumPx -= steps * stepPx
                            latestOnDragSteps?.invoke(steps)
                        }
                    },
                )
            }
            .testTag("$decTag-label")
    } else {
        Modifier.testTag("$decTag-label")
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = JewelTheme.typography.small,
            color = globalColors.text.info,
            modifier = labelModifier,
        )
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
            if (editing && onCommitTypedValue != null) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = JewelTheme.typography.small.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = globalColors.text.normal,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(globalColors.outlines.focused),
                    modifier = Modifier
                        .width(58.dp)
                        .border(1.dp, globalColors.outlines.focused, RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                        .focusRequester(inputFocusRequester)
                        .studioTextInputFocus()
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                inputHadFocus = true
                            } else if (inputHadFocus && editing) {
                                inputHadFocus = false
                                editing = false
                                onCommitTypedValue(draft)
                            }
                        }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown) {
                                when (ev.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        editing = false
                                        onCommitTypedValue(draft)
                                        true
                                    }
                                    Key.Escape -> {
                                        editing = false
                                        draft = valueText
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                        .onKeyEvent { true }
                        .testTag("$decTag-input"),
                )
            } else {
                Text(
                    text = valueText,
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                    color = globalColors.text.normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(58.dp)
                        .then(
                            if (onCommitTypedValue != null) {
                                Modifier
                                    .pointerHoverIcon(PointerIcon.Text)
                                    .clickable {
                                        draft = valueText.filter { it.isDigit() || it == '-' }
                                        editing = true
                                    }
                            } else {
                                Modifier
                            }
                        )
                        .testTag("$decTag-value"),
                )
            }
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

/**
 * Slider with local drag state so store quantization or deferred re-indexing never fights the
 * active thumb drag, bracketed cleanly by [onGestureStart] and [onGestureEnd].
 */
@Composable
internal fun TimelineSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    gestureKey: String,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clampedExternal = value.coerceIn(valueRange.start, valueRange.endInclusive)
    var sliderDragging by remember(gestureKey) { mutableStateOf(false) }
    var sliderHadFocus by remember(gestureKey) { mutableStateOf(false) }
    var localDragValue by remember(gestureKey) { mutableFloatStateOf(clampedExternal) }
    val latestOnGestureEnd by rememberUpdatedState(onGestureEnd)

    LaunchedEffect(clampedExternal, sliderDragging) {
        if (!sliderDragging) {
            localDragValue = clampedExternal
        }
    }

    DisposableEffect(gestureKey) {
        onDispose {
            if (sliderDragging) {
                sliderDragging = false
                latestOnGestureEnd()
            }
        }
    }

    Slider(
        value = if (sliderDragging) localDragValue else clampedExternal,
        onValueChange = { next ->
            if (!sliderDragging) {
                sliderDragging = true
                onGestureStart()
            }
            val clamped = next.coerceIn(valueRange.start, valueRange.endInclusive)
            localDragValue = clamped
            onValueChange(clamped)
        },
        onValueChangeFinished = {
            if (sliderDragging) {
                sliderDragging = false
                onGestureEnd()
            }
        },
        valueRange = valueRange,
        modifier = modifier.onFocusChanged { focusState ->
            if (focusState.hasFocus) {
                sliderHadFocus = true
            } else if (sliderHadFocus) {
                sliderHadFocus = false
                if (sliderDragging) {
                    sliderDragging = false
                    latestOnGestureEnd()
                }
            }
        },
    )
}
