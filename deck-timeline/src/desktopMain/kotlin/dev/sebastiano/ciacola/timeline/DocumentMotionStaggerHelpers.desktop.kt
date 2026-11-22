package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.CurveEvaluator
import dev.sebastiano.ciacola.document.Slot
import dev.sebastiano.ciacola.document.SlotPhase
import dev.sebastiano.ciacola.document.Timing
import kotlin.math.max
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/**
 * Human-readable presentation model for a `PieceSchedule` fragment address (`-begins#0` -> `"begins" (exit)`).
 * Raw tokenizer syntax (`address`) is retained strictly for `testTag` and secondary hover metadata.
 */
@Immutable
data class FormattedFragmentLabel(
    val rawAddress: String,
    val cleanToken: String,
    val hasLeadingSpace: Boolean,
    val occurrenceIndex: Int,
    val occurrenceBadge: String?,
    val phaseRole: String,
    val displayLabel: String,
) {
    fun tooltipText(startMs: Int, endMs: Int): String =
        "$displayLabel · ${startMs}–${endMs} ms · id: $rawAddress"
}

/** Extracts the un-prefixed base token from a raw `PieceSchedule` fragment address. */
fun extractFragmentBaseToken(address: String): String {
    val hashIdx = address.lastIndexOf('#')
    val rawToken = if (hashIdx > 0 && address.substring(hashIdx + 1).all { it.isDigit() }) {
        address.substring(0, hashIdx)
    } else {
        address
    }
    return if (rawToken.startsWith("-") && rawToken.length > 1) rawToken.substring(1) else rawToken
}

/** Counts occurrences per base token across a track's fragment slots so `#1`/`#2` only appear on duplicates. */
fun computeFragmentBaseTokenCounts(slots: List<Slot>): Map<String, Int> {
    if (slots.isEmpty()) return emptyMap()
    val counts = LinkedHashMap<String, Int>(slots.size)
    for (slot in slots) {
        val base = extractFragmentBaseToken(slot.address)
        counts[base] = (counts[base] ?: 0) + 1
    }
    return counts
}

/**
 * Canonical formatter for intra-text fragment labels:
 * - Strips `#<index>` suffix and leading `-` space marker (for multi-char tokens).
 * - Wraps the clean token in typographic quotes (`"begins"`).
 * - Adds a 1-based `#1`/`#2` occurrence badge only when [duplicateCountForBaseToken] > 1.
 * - Appends explicit lowercase phase role (`(exit)`, `(move)`, `(enter)`).
 */
fun formatHumanFragmentLabel(
    address: String,
    phase: SlotPhase,
    duplicateCountForBaseToken: Int = 1,
): FormattedFragmentLabel {
    val hashIdx = address.lastIndexOf('#')
    val (rawToken, occIndex) = if (hashIdx > 0 && address.substring(hashIdx + 1).all { it.isDigit() }) {
        address.substring(0, hashIdx) to (address.substring(hashIdx + 1).toIntOrNull() ?: 0)
    } else {
        address to 0
    }
    val hasLeadingSpace = rawToken.startsWith("-") && rawToken.length > 1
    val cleanToken = if (hasLeadingSpace) rawToken.substring(1) else rawToken
    val occurrenceBadge = if (duplicateCountForBaseToken > 1) "#${occIndex + 1}" else null
    val phaseRole = when (phase) {
        SlotPhase.Exit -> "exit"
        SlotPhase.Move -> "move"
        SlotPhase.Enter -> "enter"
    }
    val displayLabel = buildString {
        append('"').append(cleanToken).append('"')
        if (occurrenceBadge != null) append(' ').append(occurrenceBadge)
        append(" (").append(phaseRole).append(')')
    }
    return FormattedFragmentLabel(
        rawAddress = address,
        cleanToken = cleanToken,
        hasLeadingSpace = hasLeadingSpace,
        occurrenceIndex = occIndex,
        occurrenceBadge = occurrenceBadge,
        phaseRole = phaseRole,
        displayLabel = displayLabel,
    )
}

internal fun phaseGridStepMs(maxMs: Int): Int = when {
    maxMs <= 1_200 -> 250
    maxMs <= 2_500 -> 500
    else -> 1_000
}

internal fun effectiveTimingDurationMs(timing: Timing): Int {
    val curve = timing.curve
    return if (curve is Curve.Spring) {
        CurveEvaluator.springSettleDurationMs(curve.damping, curve.stiffness)
    } else {
        (timing.durationMs ?: 500).coerceAtLeast(0)
    }
}

/**
 * Unified 3-lane **Phase Choreography Card** for `Exit / Move / Enter`:
 * - Shared vertical time gridlines (`0 ms`, `250 ms`, `500 ms`, ...) and live `Total Span: N ms` readout.
 * - Always seeds single-axis drags with resolved `(delayMs, durationMs, curve)` so untouched fields
 *   never flicker to `null` / `Default`.
 * - Floats a live drag ms readout `6.dp` **above** the active bar so the cursor never covers it.
 */
@Composable
internal fun InteractivePhaseBarsEditor(
    resolvedPhases: ResolvedPhaseTimings,
    tagPrefix: String,
    onBeginGesture: (SlotPhase) -> Unit,
    onCommitGesture: () -> Unit,
    onCancelGesture: () -> Unit,
    onPhaseUpdated: (phase: SlotPhase, delayMs: Int?, durationMs: Int?, curve: Curve?, inGesture: Boolean) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val density = LocalDensity.current

    val exitT = resolvedPhases.exit
    val moveT = resolvedPhases.move
    val enterT = resolvedPhases.enter
    val totalSpanMs = listOf(exitT, moveT, enterT).maxOf { (it.delayMs ?: 0) + effectiveTimingDurationMs(it) }
    val maxMs = max(1_000, totalSpanMs + 100)
    val gridStepMs = phaseGridStepMs(maxMs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ciacola-$tagPrefix-phase-bars"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Exit · Move · Enter", style = studioLabelStyle(), color = globalColors.text.normal, modifier = Modifier.weight(1f))
            Text(
                text = "Total $totalSpanMs ms",
                style = studioCaptionStyle().copy(fontFeatureSettings = "tnum"),
                color = globalColors.text.normal.copy(alpha = 0.78f),
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .testTag("ciacola-$tagPrefix-phase-total-span"),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Shared time tick ruler across the top of the choreography card.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp),
            ) {
                val widthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
                var tick = 0
                while (tick <= maxMs) {
                    val xPx = ((tick.toFloat() / maxMs.toFloat()) * widthPx).roundToInt()
                    if (xPx <= (widthPx - 28f).roundToInt() || tick == 0) {
                        Text(
                            text = formatTimelineRulerLabel(tick, gridStepMs, maxVisibleMs = maxMs),
                            style = studioCaptionStyle().copy(fontSize = 10.sp, fontFeatureSettings = "tnum"),
                            color = globalColors.text.normal.copy(alpha = 0.7f),
                            maxLines = 1,
                            modifier = Modifier.offset { IntOffset(xPx, 0) },
                        )
                    }
                    tick += gridStepMs
                }
            }

            for (phase in listOf(SlotPhase.Exit, SlotPhase.Move, SlotPhase.Enter)) {
                val timing = when (phase) {
                    SlotPhase.Exit -> exitT
                    SlotPhase.Move -> moveT
                    SlotPhase.Enter -> enterT
                }
                val delayMs = timing.delayMs ?: 0
                val durationMs = effectiveTimingDurationMs(timing)
                val resolvedCurve = timing.curve
                val accent = phaseAccentColor(phase)

                var activeDragLabel by remember(tagPrefix, phase) { mutableStateOf<String?>(null) }
                var activeDragAnchorPx by remember(tagPrefix, phase) { mutableIntStateOf(0) }

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${phase.name}  +${delayMs} ms · ${durationMs} ms",
                            style = studioLabelStyle().copy(fontFeatureSettings = "tnum"),
                            color = globalColors.text.normal,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("ciacola-$tagPrefix-phase-${phase.name.lowercase()}-readout"),
                        )
                        Dropdown(
                            modifier = Modifier
                                .width(120.dp)
                                .testTag("ciacola-$tagPrefix-phase-${phase.name.lowercase()}-curve"),
                            menuContent = {
                                for ((label, curve) in TIMELINE_CURVE_CHOICES) {
                                    selectableItem(
                                        selected = resolvedCurve == curve,
                                        onClick = { onPhaseUpdated(phase, delayMs, durationMs, curve, false) },
                                    ) {
                                        Text(label, style = studioLabelStyle())
                                    }
                                }
                            },
                        ) {
                            Text(
                                text = curveShortLabel(resolvedCurve),
                                style = studioLabelStyle(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(globalColors.borders.normal.copy(alpha = 0.16f))
                            .border(1.dp, globalColors.borders.normal.copy(alpha = 0.7f), RoundedCornerShape(4.dp)),
                    ) {
                        val trackWidthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
                        val msPerPx = maxMs.toFloat() / trackWidthPx
                        val barLeftPx = ((delayMs.toFloat() / maxMs.toFloat()) * trackWidthPx).roundToInt()
                        val barWidthPx = ((durationMs.toFloat() / maxMs.toFloat()) * trackWidthPx)
                            .roundToInt()
                            .coerceAtLeast(18)

                        // Shared vertical time gridlines behind the lane bar.
                        val gridColor = globalColors.borders.normal.copy(alpha = 0.45f)
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            var t = gridStepMs
                            while (t < maxMs) {
                                val gx = (t.toFloat() / maxMs.toFloat()) * size.width
                                drawLine(
                                    color = gridColor,
                                    start = Offset(gx, 0f),
                                    end = Offset(gx, size.height),
                                    strokeWidth = 1f,
                                )
                                t += gridStepMs
                            }
                        }

                        val latestDelayMs by rememberUpdatedState(delayMs)
                        val latestDurationMs by rememberUpdatedState(durationMs)
                        val latestCurve by rememberUpdatedState(resolvedCurve)
                        val latestMsPerPx by rememberUpdatedState(msPerPx)
                        val latestBarLeftPx by rememberUpdatedState(barLeftPx)
                        val latestBarWidthPx by rememberUpdatedState(barWidthPx)
                        val latestOnBegin by rememberUpdatedState(onBeginGesture)
                        val latestOnCommit by rememberUpdatedState(onCommitGesture)
                        val latestOnCancel by rememberUpdatedState(onCancelGesture)
                        val latestOnPhaseUpdated by rememberUpdatedState(onPhaseUpdated)

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(barLeftPx, 0) }
                                .width(with(density) { barWidthPx.toDp() })
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(accent.copy(alpha = 0.80f))
                                // Keyed ONLY on stable phase identity so growing maxMs never cancels drag.
                                .pointerInput(tagPrefix, phase) {
                                    var dragBaseDelay = 0
                                    var dragSeedDuration = 500
                                    var dragSeedCurve: Curve? = null
                                    var dragAccumPx = 0f
                                    var gestureOpen = false
                                    try {
                                        detectHorizontalDragGestures(
                                            onDragStart = {
                                                dragBaseDelay = latestDelayMs
                                                dragSeedDuration = latestDurationMs
                                                dragSeedCurve = latestCurve
                                                dragAccumPx = 0f
                                                activeDragAnchorPx = latestBarLeftPx
                                                activeDragLabel = "+${dragBaseDelay}ms"
                                                gestureOpen = true
                                                latestOnBegin(phase)
                                            },
                                            onDragEnd = {
                                                activeDragLabel = null
                                                gestureOpen = false
                                                latestOnCommit()
                                            },
                                            onDragCancel = {
                                                activeDragLabel = null
                                                gestureOpen = false
                                                latestOnCancel()
                                            },
                                            onHorizontalDrag = { change, dragAmount ->
                                                change.consume()
                                                dragAccumPx += dragAmount
                                                val nextDelay = (((dragBaseDelay + (dragAccumPx * latestMsPerPx).roundToInt()) / 5) * 5)
                                                    .coerceAtLeast(0)
                                                activeDragAnchorPx = latestBarLeftPx
                                                activeDragLabel = "+${nextDelay}ms"
                                                latestOnPhaseUpdated(phase, nextDelay, dragSeedDuration, dragSeedCurve, true)
                                            },
                                        )
                                    } finally {
                                        if (gestureOpen) {
                                            activeDragLabel = null
                                            gestureOpen = false
                                            latestOnCancel()
                                        }
                                    }
                                }
                                .testTag("ciacola-$tagPrefix-phase-${phase.name.lowercase()}-bar"),
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(8.dp)
                                    .fillMaxHeight()
                                    .background(Color.White.copy(alpha = 0.35f))
                                    .pointerInput(tagPrefix, phase) {
                                        var resizeSeedDelay = 0
                                        var resizeBaseDur = 0
                                        var resizeSeedCurve: Curve? = null
                                        var resizeAccumPx = 0f
                                        var gestureOpen = false
                                        try {
                                            detectHorizontalDragGestures(
                                                onDragStart = {
                                                    resizeSeedDelay = latestDelayMs
                                                    resizeBaseDur = latestDurationMs
                                                    resizeSeedCurve = latestCurve
                                                    resizeAccumPx = 0f
                                                    activeDragAnchorPx = latestBarLeftPx + latestBarWidthPx
                                                    activeDragLabel = "${resizeBaseDur}ms"
                                                    gestureOpen = true
                                                    latestOnBegin(phase)
                                                },
                                                onDragEnd = {
                                                    activeDragLabel = null
                                                    gestureOpen = false
                                                    latestOnCommit()
                                                },
                                                onDragCancel = {
                                                    activeDragLabel = null
                                                    gestureOpen = false
                                                    latestOnCancel()
                                                },
                                                onHorizontalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    resizeAccumPx += dragAmount
                                                    val nextDur = (((resizeBaseDur + (resizeAccumPx * latestMsPerPx).roundToInt()) / 5) * 5)
                                                        .coerceAtLeast(0)
                                                    activeDragAnchorPx = latestBarLeftPx + latestBarWidthPx
                                                    activeDragLabel = "${nextDur}ms"
                                                    latestOnPhaseUpdated(phase, resizeSeedDelay, nextDur, resizeSeedCurve, true)
                                                },
                                            )
                                        } finally {
                                            if (gestureOpen) {
                                                activeDragLabel = null
                                                gestureOpen = false
                                                latestOnCancel()
                                            }
                                        }
                                    }
                                    .testTag("ciacola-$tagPrefix-phase-${phase.name.lowercase()}-resize"),
                            )
                        }

                        // Floating ms readout anchored 6.dp ABOVE the dragged bar so the cursor never covers it.
                        val liveLabel = activeDragLabel
                        if (liveLabel != null) {
                            val liftPx = with(density) { 26.dp.roundToPx() }
                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(activeDragAnchorPx.coerceAtLeast(0), -liftPx),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xEE181A20))
                                        .border(1.dp, accent, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "${phase.name} $liveLabel",
                                        style = JewelTheme.typography.small.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = accent,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
