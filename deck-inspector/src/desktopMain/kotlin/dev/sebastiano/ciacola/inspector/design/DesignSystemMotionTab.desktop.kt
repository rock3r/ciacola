package dev.sebastiano.ciacola.inspector.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.effects.keynotePopAlpha
import dev.sebastiano.ciacola.effects.keynotePopScale
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.theme.LocalReducedMotion
import dev.sebastiano.ciacola.theme.MotionTokenOverrides
import dev.sebastiano.ciacola.theme.REDUCED_MOTION_FADE_DURATION_MS
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.typography

private data class IntMotionTokenSpec(
    val id: String,
    val label: String,
    val unitSuffix: String,
    val range: IntRange,
    val step: Int,
    val getResolved: (DeckTheme) -> Int,
    val isOverridden: (MotionTokenOverrides) -> Boolean,
    val applyOverride: (MotionTokenOverrides, Int?) -> MotionTokenOverrides,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignSystemMotionTab(
    store: DesignSystemStore,
    preset: DeckThemePreset,
    overrides: DesignSystemOverrides,
    resolvedTheme: DeckTheme,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    val durationSpecs = remember {
        listOf(
            IntMotionTokenSpec("fadeMove", "Fade Move", "ms", 80..1500, 25, { it.motion.fadeMoveDurationMs }, { it.fadeMoveDurationMs != null }) { m, v -> m.copy(fadeMoveDurationMs = v) },
            IntMotionTokenSpec("dissolve", "Dissolve", "ms", 80..1500, 25, { it.motion.dissolveDurationMs }, { it.dissolveDurationMs != null }) { m, v -> m.copy(dissolveDurationMs = v) },
            IntMotionTokenSpec("lineDraw", "Line Draw", "ms", 80..1500, 25, { it.motion.lineDrawDurationMs }, { it.lineDrawDurationMs != null }) { m, v -> m.copy(lineDrawDurationMs = v) },
            IntMotionTokenSpec("loupe", "Loupe", "ms", 80..1500, 25, { it.motion.loupeDurationMs }, { it.loupeDurationMs != null }) { m, v -> m.copy(loupeDurationMs = v) },
            IntMotionTokenSpec("sharedBounds", "Shared Bounds", "ms", 100..2000, 25, { it.motion.sharedBoundsDurationMs }, { it.sharedBoundsDurationMs != null }) { m, v -> m.copy(sharedBoundsDurationMs = v) },
            IntMotionTokenSpec("warpToken", "Warp Token", "ms", 80..1500, 25, { it.motion.warpTokenDurationMs }, { it.warpTokenDurationMs != null }) { m, v -> m.copy(warpTokenDurationMs = v) },
        )
    }

    val motionModified = overrides.motion != MotionTokenOverrides()
    val motion = resolvedTheme.motion

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupHeader("Motion Preview:")
            Tooltip(tooltip = { Text("Reset all motion token overrides for ${preset.name}") }) {
                OutlinedButton(
                    onClick = { store.resetMotion(preset) },
                    enabled = motionModified,
                    modifier = Modifier
                        .designActivatableFocus(focusRingColor)
                        .testTag("design-motion-reset-section"),
                ) {
                    Text("Reset Motion", style = JewelTheme.typography.small)
                }
            }
        }

        MotionPreviewBox(resolvedTheme = resolvedTheme)

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        DesignSectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GroupHeader("Default Durations:")
                durationSpecs.forEach { spec ->
                    val current = spec.getResolved(resolvedTheme)
                    DesignStepperRow(
                        label = spec.label,
                        valueText = "${current}${spec.unitSuffix}",
                        testTagPrefix = "design-motion-${spec.id}",
                        isOverridden = spec.isOverridden(overrides.motion),
                        canDecrease = current > spec.range.first,
                        canIncrease = current < spec.range.last,
                        onDecrease = {
                            val next = (current - spec.step).coerceIn(spec.range)
                            store.updateMotion(preset) { m -> spec.applyOverride(m, next) }
                        },
                        onIncrease = {
                            val next = (current + spec.step).coerceIn(spec.range)
                            store.updateMotion(preset) { m -> spec.applyOverride(m, next) }
                        },
                        onReset = {
                            store.updateMotion(preset) { m -> spec.applyOverride(m, null) }
                        },
                    )
                }
            }
        }

        DesignSectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GroupHeader("Distance & Stagger:")
                val fadeDist = motion.fadeMoveDistance.value
                DesignStepperRow(
                    label = "Fade Move Distance",
                    valueText = "${fadeDist.roundToInt()}dp",
                    testTagPrefix = "design-motion-fadeMoveDistance",
                    isOverridden = overrides.motion.fadeMoveDistanceDp != null,
                    canDecrease = fadeDist > 0f,
                    canIncrease = fadeDist < 96f,
                    onDecrease = {
                        store.updateMotion(preset) { it.copy(fadeMoveDistanceDp = (fadeDist - 4f).coerceIn(0f, 96f)) }
                    },
                    onIncrease = {
                        store.updateMotion(preset) { it.copy(fadeMoveDistanceDp = (fadeDist + 4f).coerceIn(0f, 96f)) }
                    },
                    onReset = { store.updateMotion(preset) { it.copy(fadeMoveDistanceDp = null) } },
                )
                DesignStepperRow(
                    label = "Stagger Step",
                    valueText = "${motion.staggerStepMs}ms",
                    testTagPrefix = "design-motion-staggerStep",
                    isOverridden = overrides.motion.staggerStepMs != null,
                    canDecrease = motion.staggerStepMs > 0,
                    canIncrease = motion.staggerStepMs < 400,
                    onDecrease = {
                        store.updateMotion(preset) { it.copy(staggerStepMs = (motion.staggerStepMs - 5).coerceIn(0, 400)) }
                    },
                    onIncrease = {
                        store.updateMotion(preset) { it.copy(staggerStepMs = (motion.staggerStepMs + 5).coerceIn(0, 400)) }
                    },
                    onReset = { store.updateMotion(preset) { it.copy(staggerStepMs = null) } },
                )
                val darkScale = motion.fromDarknessInitialScale
                DesignStepperRow(
                    label = "From Darkness Scale",
                    valueText = "%.2f×".format(Locale.US, darkScale),
                    testTagPrefix = "design-motion-fromDarknessScale",
                    isOverridden = overrides.motion.fromDarknessInitialScale != null,
                    canDecrease = darkScale > 0.50f,
                    canIncrease = darkScale < 1.00f,
                    onDecrease = {
                        val next = (((darkScale - 0.02f) * 100f).roundToInt() / 100f).coerceIn(0.50f, 1.00f)
                        store.updateMotion(preset) { it.copy(fromDarknessInitialScale = next) }
                    },
                    onIncrease = {
                        val next = (((darkScale + 0.02f) * 100f).roundToInt() / 100f).coerceIn(0.50f, 1.00f)
                        store.updateMotion(preset) { it.copy(fromDarknessInitialScale = next) }
                    },
                    onReset = { store.updateMotion(preset) { it.copy(fromDarknessInitialScale = null) } },
                )
            }
        }

        DesignSectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupHeader("Pop Curve:")
                DesignStepperRow(
                    label = "Initial Scale",
                    valueText = "%.2f×".format(Locale.US, motion.popInitialScale),
                    testTagPrefix = "design-motion-popInitialScale",
                    isOverridden = overrides.motion.popInitialScale != null,
                    canDecrease = motion.popInitialScale > 0.20f,
                    canIncrease = motion.popInitialScale < 1.00f,
                    onDecrease = {
                        val next = (((motion.popInitialScale - 0.04f) * 100f).roundToInt() / 100f).coerceIn(0.20f, 1.00f)
                        store.updateMotion(preset) { it.copy(popInitialScale = next) }
                    },
                    onIncrease = {
                        val next = (((motion.popInitialScale + 0.04f) * 100f).roundToInt() / 100f).coerceIn(0.20f, 1.00f)
                        store.updateMotion(preset) { it.copy(popInitialScale = next) }
                    },
                    onReset = { store.updateMotion(preset) { it.copy(popInitialScale = null) } },
                )
                DesignStepperRow(
                    label = "Overshoot Scale",
                    valueText = "%.2f×".format(Locale.US, motion.popOvershootScale),
                    testTagPrefix = "design-motion-popOvershootScale",
                    isOverridden = overrides.motion.popOvershootScale != null,
                    canDecrease = motion.popOvershootScale > 1.00f,
                    canIncrease = motion.popOvershootScale < 1.35f,
                    onDecrease = {
                        val next = (((motion.popOvershootScale - 0.02f) * 100f).roundToInt() / 100f).coerceIn(1.00f, 1.35f)
                        store.updateMotion(preset) { it.copy(popOvershootScale = next) }
                    },
                    onIncrease = {
                        val next = (((motion.popOvershootScale + 0.02f) * 100f).roundToInt() / 100f).coerceIn(1.00f, 1.35f)
                        store.updateMotion(preset) { it.copy(popOvershootScale = next) }
                    },
                    onReset = { store.updateMotion(preset) { it.copy(popOvershootScale = null) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MotionPreviewBox(resolvedTheme: DeckTheme) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val reducedMotion = LocalReducedMotion.current
    val motion = resolvedTheme.motion
    var playCounter by remember { mutableIntStateOf(0) }

    val slideProgress = remember { Animatable(1f) }
    val popProgress = remember { Animatable(1f) }
    val staggerDot1 = remember { Animatable(1f) }
    val staggerDot2 = remember { Animatable(1f) }

    LaunchedEffect(playCounter) {
        if (playCounter == 0) return@LaunchedEffect
        if (reducedMotion) {
            slideProgress.snapTo(0f)
            popProgress.snapTo(0f)
            staggerDot1.snapTo(0f)
            staggerDot2.snapTo(0f)
            val fadeSpec = tween<Float>(durationMillis = REDUCED_MOTION_FADE_DURATION_MS)
            launch { slideProgress.animateTo(1f, fadeSpec) }
            launch { popProgress.animateTo(1f, fadeSpec) }
            launch { staggerDot1.animateTo(1f, fadeSpec) }
            launch { staggerDot2.animateTo(1f, fadeSpec) }
            return@LaunchedEffect
        }

        slideProgress.snapTo(0f)
        popProgress.snapTo(0f)
        staggerDot1.snapTo(0f)
        staggerDot2.snapTo(0f)

        val buildSpec = tween<Float>(durationMillis = motion.fadeMoveDurationMs, easing = motion.standardEasing)
        launch { slideProgress.animateTo(1f, buildSpec) }
        launch { popProgress.animateTo(1f, buildSpec) }
        launch { staggerDot1.animateTo(1f, buildSpec) }
        launch {
            delay(motion.staggerStepMs.toLong())
            staggerDot2.animateTo(1f, buildSpec)
        }
    }

    val boxShape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(resolvedTheme.colors.background, boxShape)
            .border(1.dp, globalColors.borders.normal, boxShape)
            .padding(10.dp)
            .testTag("design-motion-preview-box"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val p = slideProgress.value
                        alpha = p
                        translationY = (1f - p) * motion.fadeMoveDistance.toPx() * 0.5f
                    }
                    .background(resolvedTheme.colors.surface, RoundedCornerShape(6.dp))
                    .border(1.dp, resolvedTheme.colors.border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "FadeMove",
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                    color = resolvedTheme.colors.foreground,
                )
            }

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val p = popProgress.value
                        if (reducedMotion) {
                            alpha = p.coerceIn(0f, 1f)
                            scaleX = 1f
                            scaleY = 1f
                        } else {
                            val scale = keynotePopScale(p, motion.popInitialScale, motion.popOvershootScale)
                            alpha = keynotePopAlpha(p)
                            scaleX = scale
                            scaleY = scale
                        }
                    }
                    .background(resolvedTheme.colors.accent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "Pop",
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Bold),
                    color = resolvedTheme.colors.onAccent,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer {
                            alpha = staggerDot1.value
                            scaleX = 0.6f + 0.4f * staggerDot1.value
                            scaleY = 0.6f + 0.4f * staggerDot1.value
                        }
                        .background(resolvedTheme.colors.secondary, CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer {
                            alpha = staggerDot2.value
                            scaleX = 0.6f + 0.4f * staggerDot2.value
                            scaleY = 0.6f + 0.4f * staggerDot2.value
                        }
                        .background(resolvedTheme.colors.secondary, CircleShape),
                )
            }
        }

        Tooltip(tooltip = { Text("Trigger live preview using FadeMove, Stagger, and Pop tokens") }) {
            DefaultButton(
                onClick = { playCounter++ },
                modifier = Modifier
                    .height(26.dp)
                    .designActivatableFocus(focusRingColor)
                    .testTag("design-motion-preview-play"),
            ) {
                Text("Preview Motion ▶", style = JewelTheme.typography.small, maxLines = 1)
            }
        }
    }
}
