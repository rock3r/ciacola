package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
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
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

private val INSPECTOR_WIDTH = 304.dp

/**
 * Right-hand After Effects Keyframe, Bezier Curve, & Spatial Motion-Path Inspector.
 */
@Composable
internal fun TimelineBlockInspector(
    controller: TimelineStudioController,
    modifier: Modifier = Modifier,
) {
    val globalColors = JewelTheme.globalColors
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val activeLoopId = controller.loopClock.activeSubCompLoopId
    if (activeLoopId != null) {
        LoopSubCompInspector(controller, activeLoopId, modifier)
        return
    }

    val selectedId = controller.selectedTrackId
    val selectedRow = controller.snapshot.tracks.firstOrNull { it.trackId == selectedId }
    val primaryBlock = selectedRow?.blocks?.firstOrNull()

    Box(
        modifier = modifier
            .width(INSPECTOR_WIDTH)
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

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selectedRow.category == TrackCategory.LoopSubComp && primaryBlock.loopId != null) {
                DefaultButton(
                    onClick = {
                        controller.enterLoopSubComp(scope, primaryBlock.loopId, primaryBlock.sceneIndex)
                    },
                    modifier = Modifier.testTag("ciacola-inspector-enter-subcomp"),
                ) {
                    Text("Enter Sub-Comp ▸", style = JewelTheme.typography.small)
                }
                OutlinedButton(
                    onClick = { controller.resetLoopPeriod(primaryBlock.loopId) },
                    enabled = hasTrackOverride,
                    modifier = Modifier.testTag("ciacola-inspector-reset-track"),
                ) {
                    Text("Reset Track", style = JewelTheme.typography.small)
                }
            } else {
                DefaultButton(
                    onClick = { controller.previewSelectedBlock(scope) },
                    modifier = Modifier.testTag("ciacola-inspector-preview"),
                ) {
                    Text("Preview ▶", style = JewelTheme.typography.small)
                }
                OutlinedButton(
                    onClick = { controller.overridesStore.resetTrack(selectedRow.trackId) },
                    enabled = hasTrackOverride,
                    modifier = Modifier.testTag("ciacola-inspector-reset-track"),
                ) {
                    Text("Reset Track", style = JewelTheme.typography.small)
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
            val effectiveDuration = when {
                selectedRow.category == TrackCategory.StaggerGroup && currentOverride.durationMs != null ->
                    currentOverride.durationMs + staggerSpreadMs
                else -> currentOverride.durationMs ?: baseDuration
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

        // 2. Temporal Curve Editor (Interactive 2D Cubic-Bezier Graph + Presets)
        GroupHeader(if (currentOverride.curve != null) "● Time interpolation:" else "Time interpolation:")
        val curve = currentOverride.curve ?: TimelineCurveSpec()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CurvePresetButton(
                label = "Ease",
                selected = !curve.isSpring && curve.preset == "KeynoteEase",
                modifier = Modifier.weight(1f),
            ) {
                controller.overridesStore.updateTrack(selectedRow.trackId) {
                    it.copy(curve = TimelineCurveSpec("KeynoteEase", 0.25f, 0.1f, 0.25f, 1.0f, isSpring = false))
                }
            }
            CurvePresetButton(
                label = "Linear",
                selected = !curve.isSpring && curve.preset == "Linear",
                modifier = Modifier.weight(1f),
            ) {
                controller.overridesStore.updateTrack(selectedRow.trackId) {
                    it.copy(curve = TimelineCurveSpec("Linear", 0f, 0f, 1f, 1f, isSpring = false))
                }
            }
            CurvePresetButton(
                label = "Pop",
                selected = !curve.isSpring && curve.preset == "Overshoot",
                modifier = Modifier.weight(1f),
            ) {
                controller.overridesStore.updateTrack(selectedRow.trackId) {
                    it.copy(curve = TimelineCurveSpec("Overshoot", 0.2f, 1.25f, 0.35f, 1.0f, isSpring = false))
                }
            }
            CurvePresetButton(
                label = "Spring",
                selected = curve.isSpring,
                modifier = Modifier.weight(1f),
            ) {
                controller.overridesStore.updateTrack(selectedRow.trackId) {
                    it.copy(curve = curve.copy(preset = "Spring", isSpring = true))
                }
            }
        }

        if (curve.isSpring) {
            val clampedDamping = curve.dampingRatio.coerceIn(0.20f, 1.50f)
            val clampedStiffness = curve.stiffness.coerceIn(50f, 4_000f)
            NumericStepperRow(
                label = "Damping",
                valueText = "%.2f".format(clampedDamping),
                decTag = "ciacola-inspector-damping-dec",
                incTag = "ciacola-inspector-damping-inc",
                onDec = {
                    val next = ((clampedDamping - 0.05f) * 100f).roundToInt() / 100f
                    controller.overridesStore.updateTrack(selectedRow.trackId) {
                        it.copy(curve = curve.copy(isSpring = true, dampingRatio = next.coerceIn(0.20f, 1.50f)))
                    }
                },
                onInc = {
                    val next = ((clampedDamping + 0.05f) * 100f).roundToInt() / 100f
                    controller.overridesStore.updateTrack(selectedRow.trackId) {
                        it.copy(curve = curve.copy(isSpring = true, dampingRatio = next.coerceIn(0.20f, 1.50f)))
                    }
                },
            )
            Slider(
                value = clampedDamping,
                onValueChange = { next ->
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                        it.copy(curve = curve.copy(isSpring = true, dampingRatio = next.coerceIn(0.20f, 1.50f)))
                    }
                },
                valueRange = 0.20f..1.50f,
                modifier = Modifier.fillMaxWidth().testTag("ciacola-inspector-damping-slider"),
            )
            NumericStepperRow(
                label = "Stiffness",
                valueText = "${clampedStiffness.roundToInt()}",
                decTag = "ciacola-inspector-stiffness-dec",
                incTag = "ciacola-inspector-stiffness-inc",
                onDec = {
                    controller.overridesStore.updateTrack(selectedRow.trackId) {
                        it.copy(curve = curve.copy(isSpring = true, stiffness = (clampedStiffness - 100f).coerceIn(50f, 4_000f)))
                    }
                },
                onInc = {
                    controller.overridesStore.updateTrack(selectedRow.trackId) {
                        it.copy(curve = curve.copy(isSpring = true, stiffness = (clampedStiffness + 100f).coerceIn(50f, 4_000f)))
                    }
                },
            )
            Slider(
                value = clampedStiffness,
                onValueChange = { next ->
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                        it.copy(curve = curve.copy(isSpring = true, stiffness = next.coerceIn(50f, 4_000f)))
                    }
                },
                valueRange = 50f..4_000f,
                modifier = Modifier.fillMaxWidth().testTag("ciacola-inspector-stiffness-slider"),
            )
            InfoText("Estimated settle: ~${curve.estimatedSpringDurationMs()}ms")
        } else {
            BezierCurveEditorBox(
                curve = curve,
                onGestureStart = { controller.overridesStore.beginEditGesture() },
                onGestureEnd = { controller.overridesStore.endEditGesture() },
                onCurveChanged = { updatedCurve ->
                    controller.overridesStore.updateTrack(selectedRow.trackId, coalesce = true) {
                        it.copy(curve = updatedCurve)
                    }
                },
            )
        }

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

@Composable
private fun LoopSubCompInspector(
    controller: TimelineStudioController,
    loopId: String,
    modifier: Modifier = Modifier,
) {
    val globalColors = JewelTheme.globalColors
    controller.overridesStore.parameterRevision
    val overridesData = controller.overridesStore.data()
    val isOverridden = loopId in overridesData.loopPeriodsMs
    val desc = controller.loopClock.registeredLoops[loopId]
    val defaultPeriod = desc?.defaultPeriodMillis ?: 6_000L
    val currentPeriod = overridesData.loopPeriodsMs[loopId]
        ?: controller.loopClock.effectivePeriodMillis(loopId, defaultPeriod)

    Column(
        modifier = modifier
            .width(INSPECTOR_WIDTH)
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
            OutlinedButton(
                onClick = { controller.resetLoopPeriod(loopId) },
                enabled = isOverridden,
                modifier = Modifier.testTag("ciacola-inspector-reset-track"),
            ) {
                Text("Reset Track", style = JewelTheme.typography.small)
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

@Composable
private fun NumericStepperRow(
    label: String,
    valueText: String,
    decTag: String,
    incTag: String,
    onDec: () -> Unit,
    onInc: () -> Unit,
) {
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
            IconButton(onClick = onDec, modifier = Modifier.size(22.dp).testTag(decTag)) {
                Icon(AllIconsKeys.General.Remove, contentDescription = "Decrease $label", modifier = Modifier.size(12.dp))
            }
            Text(
                text = valueText,
                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                color = JewelTheme.globalColors.text.normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(58.dp).testTag("$decTag-value"),
            )
            IconButton(onClick = onInc, modifier = Modifier.size(22.dp).testTag(incTag)) {
                Icon(AllIconsKeys.General.Add, contentDescription = "Increase $label", modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun CurvePresetButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        DefaultButton(onClick = onClick, modifier = modifier) {
            Text(label, style = JewelTheme.typography.small, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, style = JewelTheme.typography.small, maxLines = 1)
        }
    }
}

@Composable
private fun BezierCurveEditorBox(
    curve: TimelineCurveSpec,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onCurveChanged: (TimelineCurveSpec) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val p1Color = palette.tealOrNull(if (isDark) 6 else 5) ?: Color(0xFF35B5AC)
    val p2Color = palette.orangeOrNull(if (isDark) 6 else 5) ?: Color(0xFFE57C4E)
    val latestCurve by rememberUpdatedState(curve)
    val latestOnGestureStart by rememberUpdatedState(onGestureStart)
    val latestOnGestureEnd by rememberUpdatedState(onGestureEnd)
    val latestOnCurveChanged by rememberUpdatedState(onCurveChanged)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .border(1.dp, globalColors.borders.normal, RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .testTag("ciacola-bezier-editor")
                .semantics {
                    contentDescription = "preset=${latestCurve.preset} " +
                        "p1=${"%.2f".format(latestCurve.x1)},${"%.2f".format(latestCurve.y1)} " +
                        "p2=${"%.2f".format(latestCurve.x2)},${"%.2f".format(latestCurve.y2)}"
                }
                // Keyed on Unit (reading latestCurve) so dragging a control handle is not cancelled
                // when the first move updates the override and recomposes the Inspector.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startCurve = latestCurve
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val p1Px = Offset(startCurve.x1 * w, (1f - startCurve.y1.coerceIn(0f, 1f)) * h)
                        val p2Px = Offset(startCurve.x2 * w, (1f - startCurve.y2.coerceIn(0f, 1f)) * h)
                        val dragP1 = hypot(down.position.x - p1Px.x, down.position.y - p1Px.y) <=
                            hypot(down.position.x - p2Px.x, down.position.y - p2Px.y)

                        var latest = startCurve
                        latestOnGestureStart()
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                val nx = (ch.position.x / w).coerceIn(0f, 1f)
                                val ny = (1f - ch.position.y / h).coerceIn(-0.4f, 1.4f)
                                latest = if (dragP1) {
                                    latest.copy(preset = "Custom", x1 = nx, y1 = ny, isSpring = false)
                                } else {
                                    latest.copy(preset = "Custom", x2 = nx, y2 = ny, isSpring = false)
                                }
                                latestOnCurveChanged(latest)
                            }
                        } finally {
                            latestOnGestureEnd()
                        }
                    }
                },
        ) {
            val activeCurve = latestCurve
            val w = size.width
            val h = size.height
            val origin = Offset(0f, h)
            val dest = Offset(w, 0f)
            val p1 = Offset(activeCurve.x1.coerceIn(0f, 1f) * w, (1f - activeCurve.y1.coerceIn(-0.2f, 1.2f)) * h)
            val p2 = Offset(activeCurve.x2.coerceIn(0f, 1f) * w, (1f - activeCurve.y2.coerceIn(-0.2f, 1.2f)) * h)

            drawLine(globalColors.borders.normal.copy(alpha = 0.45f), origin, dest, strokeWidth = 1f)
            drawLine(p1Color.copy(alpha = 0.65f), origin, p1, strokeWidth = 1.5f)
            drawLine(p2Color.copy(alpha = 0.65f), dest, p2, strokeWidth = 1.5f)

            val path = Path().apply {
                moveTo(origin.x, origin.y)
                cubicTo(p1.x, p1.y, p2.x, p2.y, dest.x, dest.y)
            }
            drawPath(path, color = globalColors.text.normal, style = Stroke(width = 2.2f))
            drawCircle(color = p1Color, radius = 5.5f, center = p1)
            drawCircle(color = p2Color, radius = 5.5f, center = p2)
        }
    }
}
