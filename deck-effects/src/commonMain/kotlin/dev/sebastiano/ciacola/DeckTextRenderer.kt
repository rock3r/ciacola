package dev.sebastiano.ciacola

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LocalContentColor
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.SceneScope
import dev.bnorm.storyboard.easel.DeterministicStateHost
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.text.magic.DefaultDelayDurationMillis
import dev.bnorm.storyboard.text.magic.DefaultFadeDurationMillis
import dev.bnorm.storyboard.text.magic.DefaultMoveDurationMillis
import dev.bnorm.storyboard.text.magic.MagicText
import dev.bnorm.storyboard.toValue
import dev.sebastiano.ciacola.effects.compositeBackgroundOver
import dev.sebastiano.ciacola.effects.effectiveBackgroundColor
import dev.sebastiano.ciacola.effects.resolveOverriddenTextStyle
import dev.sebastiano.ciacola.effects.resolveStageBounds
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.LocalActiveBeatIndex
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.ModifierLocalStageAlpha
import dev.sebastiano.ciacola.inspector.StageElementMetadata
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.inspector.beatElementId
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.LocalReducedMotion
import dev.sebastiano.ciacola.theme.REDUCED_MOTION_FADE_DURATION_MS
import dev.sebastiano.ciacola.timeline.HarvestedTrackRecord
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline
import dev.sebastiano.ciacola.timeline.LocalTimelineHarvestCollector
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides
import dev.sebastiano.ciacola.timeline.TrackCategory
import dev.sebastiano.ciacola.timeline.TrackOverride
import dev.sebastiano.ciacola.timeline.VirtualizedFrame
import dev.sebastiano.ciacola.timeline.timelineTarget

interface DeckTextRenderer {
    @Composable
    fun AnimatedText(
        transition: Transition<AnnotatedString>,
        modifier: Modifier,
        style: TextStyle,
    )
}

/** How a MagicText track override maps onto [MagicText]'s phase length, start delay, and easing. */
data class MagicTextOverrideTiming(val phaseMs: Int, val delayMs: Int, val easing: Easing?)

fun magicTextOverrideTiming(override: TrackOverride?): MagicTextOverrideTiming {
    val totalDefault = DefaultFadeDurationMillis * 2 + DefaultMoveDurationMillis
    val effectiveTotal = (override?.durationMs ?: totalDefault).coerceAtLeast(90)
    return MagicTextOverrideTiming(
        phaseMs = (effectiveTotal / 3).coerceAtLeast(30),
        delayMs = (override?.delayMs ?: DefaultDelayDurationMillis).coerceAtLeast(0),
        easing = override?.curve?.toEasing(),
    )
}

object MagicTextV1Renderer : DeckTextRenderer {
    @Composable
    override fun AnimatedText(
        transition: Transition<AnnotatedString>,
        modifier: Modifier,
        style: TextStyle,
    ) {
        val sceneCtx = LocalActiveSceneTimeline.current
        val trackId = if (sceneCtx != null) "s${sceneCtx.sceneIndex}:magic-text" else "magic-text"
        val override = LocalTimelineOverrides.current.tracks[trackId]
        val timing = magicTextOverrideTiming(override)

        ProvideTextStyle(style) {
            if (LocalReducedMotion.current) {
                val fadeSpec = tween<Float>(durationMillis = REDUCED_MOTION_FADE_DURATION_MS)
                transition.DeterministicStateHost(
                    modifier = modifier,
                    enter = { _, _ -> fadeIn(fadeSpec) },
                    exit = { _, _ -> fadeOut(fadeSpec) },
                ) { text ->
                    Text(text = text)
                }
            } else {
                MagicText(
                    transition = transition,
                    modifier = modifier,
                    moveDurationMillis = timing.phaseMs,
                    fadeDurationMillis = timing.phaseMs,
                    delayDurationMillis = DefaultDelayDurationMillis,
                    delayMillis = timing.delayMs,
                    easing = timing.easing,
                )
            }
        }
    }
}

private val LocalDeckTextRenderer = staticCompositionLocalOf<DeckTextRenderer> {
    error("DeckTextRenderer is not installed at the application boundary")
}

@Composable
fun DeckTextRendererProvider(
    renderer: DeckTextRenderer,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDeckTextRenderer provides renderer, content = content)
}

/**
 * Maps a scene [Transition] of [VirtualizedFrame]s into an [AnnotatedString] transition that honors
 * per-beat (`$elementId:b$beatIndex`) and base (`$elementId`) text overrides in [LocalElementOverrides].
 */
@Composable
context(_: SceneScope<VirtualizedFrame<T>>)
fun <T> Transition<out Frame<VirtualizedFrame<T>>>.createEditableTextTransition(
    elementId: String,
    label: String,
    formatOverride: (String) -> AnnotatedString = ::AnnotatedString,
    extractText: (T) -> AnnotatedString,
): Transition<AnnotatedString> {
    val overrides = LocalElementOverrides.current
    val basePatch = overrides.patchFor(elementId)
    return createChildTransition(label) { frame ->
        val vf = frame.toValue()
        val beatPatch = overrides.patchFor(beatElementId(elementId, vf.virtualBeatIndex))
        val overrideText = beatPatch?.text ?: basePatch?.text
        if (overrideText != null) formatOverride(overrideText) else extractText(vf.value)
    }
}

private fun mergeElementPatch(base: ElementPatch?, beat: ElementPatch?): ElementPatch? = when {
    beat == null -> base
    base == null -> beat
    else -> ElementPatch(
        elementId = beat.elementId,
        text = beat.text ?: base.text,
        textColorArgb = beat.textColorArgb ?: base.textColorArgb,
        fontSizeSp = beat.fontSizeSp ?: base.fontSizeSp,
        fontWeight = beat.fontWeight ?: base.fontWeight,
        fillColorArgb = beat.fillColorArgb ?: base.fillColorArgb,
        borderColorArgb = beat.borderColorArgb ?: base.borderColorArgb,
        borderWidthDp = beat.borderWidthDp ?: base.borderWidthDp,
        cornerRadiusDp = beat.cornerRadiusDp ?: base.cornerRadiusDp,
        paddingDp = beat.paddingDp ?: base.paddingDp,
        offsetXDp = beat.offsetXDp ?: base.offsetXDp,
        offsetYDp = beat.offsetYDp ?: base.offsetYDp,
    )
}

@Composable
fun DeckMagicText(
    transition: Transition<AnnotatedString>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    style: TextStyle = DeckDesign.body,
    elementId: String? = null,
    label: String? = null,
    kind: String = "MagicText",
) {
    val sceneCtx = LocalActiveSceneTimeline.current
    val beatIndex = LocalActiveBeatIndex.current
    val collector = LocalTimelineHarvestCollector.current
    val trackId = if (sceneCtx != null) "s${sceneCtx.sceneIndex}:magic-text" else "magic-text"
    val override = LocalTimelineOverrides.current.tracks[trackId]
    val defaultTotal = DefaultFadeDurationMillis * 2 + DefaultMoveDurationMillis

    SideEffect {
        if (sceneCtx != null && sceneCtx.totalBeats > 1) {
            // MagicText animates on every beat change of its scene, so it harvests one block per beat.
            // The trigger beat always stays the beat that drives the stage: an `enterBeat` override would
            // collapse every block onto one key while the stage kept animating on each beat.
            for (beat in 1 until sceneCtx.totalBeats) {
                collector?.record(
                    HarvestedTrackRecord(
                        trackId = trackId,
                        label = "MagicText Diff",
                        category = TrackCategory.MagicText,
                        sceneIndex = sceneCtx.sceneIndex,
                        triggerBeat = beat,
                        durationMs = override?.durationMs ?: defaultTotal,
                        delayMs = override?.delayMs ?: 0,
                    )
                )
            }
        }
    }

    val theme = LocalDeckTheme.current
    val overrides = LocalElementOverrides.current
    val snapshotElementId = if (elementId != null && sceneCtx != null && sceneCtx.totalBeats > 1) {
        beatElementId(elementId, beatIndex)
    } else {
        elementId
    }
    val basePatch = elementId?.let { overrides.patchFor(it) }
    val beatPatch = snapshotElementId?.takeIf { it != elementId }?.let { overrides.patchFor(it) }
    val effectivePatch = mergeElementPatch(basePatch, beatPatch)

    val baseTextColor = style.color
        .takeOrElse { LocalContentColor.current }
        .takeOrElse { theme.colors.foreground }
    val resolvedColor = effectivePatch?.textColorArgb?.let(::Color) ?: baseTextColor
    val (effectiveStyle, effectiveFontSizeSp) = resolveOverriddenTextStyle(
        baseStyle = style,
        resolvedColor = if (effectivePatch?.textColorArgb != null) resolvedColor else style.color,
        patch = effectivePatch,
    )

    val overrideText = effectivePatch?.text
    val effectiveTransition = if (overrideText != null && transition.targetState.text != overrideText) {
        val targetRef = transition.targetState
        transition.createChildTransition("element-text-override") { state ->
            if (state == targetRef) AnnotatedString(overrideText) else state
        }
    } else {
        transition
    }

    val currentText = effectiveTransition.targetState.text
    val parentBg = effectiveBackgroundColor()
    val boxFill = effectivePatch?.fillColorArgb?.let(::Color) ?: Color.Unspecified
    val boxBorderColor = effectivePatch?.borderColorArgb?.let(::Color) ?: Color.Unspecified
    val boxBorderWidth = (effectivePatch?.borderWidthDp ?: 0f).coerceAtLeast(0f).dp
    val boxCornerRadius = (effectivePatch?.cornerRadiusDp ?: 0f).coerceAtLeast(0f).dp
    val boxPadding = (effectivePatch?.paddingDp ?: 0f).coerceAtLeast(0f).dp
    val hasBoxDeco = effectivePatch != null && (
        effectivePatch.fillColorArgb != null ||
            effectivePatch.borderColorArgb != null ||
            effectivePatch.borderWidthDp != null ||
            effectivePatch.cornerRadiusDp != null ||
            effectivePatch.paddingDp != null
        )
    val effectiveBg = if (hasBoxDeco) compositeBackgroundOver(boxFill, parentBg) else parentBg

    val registry = LocalStageInspectionRegistry.current
    val overviewOpen = LocalOverviewOpen.current
    val rootHolder = LocalStageRootCoordinates.current
    val shouldInspect = snapshotElementId != null && registry != null && !overviewOpen
    val ownerToken = remember { Any() }
    var outerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var innerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var stageAlphaProvider by remember { mutableStateOf<() -> Float>({ 1f }) }

    if (shouldInspect && snapshotElementId != null) {
        DisposableEffect(registry, snapshotElementId) {
            onDispose { registry?.remove(snapshotElementId, ownerToken) }
        }
        val coordsNow = innerCoords
        val outerNow = outerCoords
        val stageRootNow = rootHolder?.coordinates
        SideEffect {
            stageRootNow
            val baseFontSizeSp = if (style.fontSize.isSp) style.fontSize.value else 16f
            registry?.recordMetadata(
                meta = StageElementMetadata(
                    elementId = snapshotElementId,
                    supportsText = true,
                    defaultText = transition.targetState.text,
                    defaultTextColor = baseTextColor,
                    defaultFontSizeSp = baseFontSizeSp,
                    defaultFontWeight = style.fontWeight?.weight ?: 400,
                ),
                owner = ownerToken,
            )
            val bounds = resolveStageBounds(coordsNow, rootHolder)
            if (bounds != null) {
                val overflowed = outerNow != null && coordsNow != null &&
                    outerNow.isAttached && coordsNow.isAttached &&
                    (coordsNow.size.width > outerNow.size.width + 2 || coordsNow.size.height > outerNow.size.height + 2)
                registry?.record(
                    snapshot = StageNodeSnapshot(
                        elementId = snapshotElementId,
                        kind = kind,
                        label = label ?: currentText.take(48).ifBlank { snapshotElementId },
                        sceneIndex = sceneCtx?.sceneIndex ?: 0,
                        beatIndex = beatIndex,
                        stageBounds = bounds,
                        resolvedText = currentText,
                        hasVisualOverflow = overflowed,
                        effectiveForeground = resolvedColor,
                        effectiveBackground = effectiveBg,
                        effectiveFontSizeSp = effectiveFontSizeSp,
                        isEditable = true,
                    ),
                    owner = ownerToken,
                    alphaProvider = { stageAlphaProvider() },
                    coordinatesProvider = { innerCoords },
                    stageRootProvider = { rootHolder?.coordinates },
                )
            }
        }
    }

    val offsetMod = if (effectivePatch?.offsetXDp != null || effectivePatch?.offsetYDp != null) {
        Modifier.offset(
            x = (effectivePatch.offsetXDp ?: 0f).dp,
            y = (effectivePatch.offsetYDp ?: 0f).dp,
        )
    } else {
        Modifier
    }
    val boxDecoMod = if (hasBoxDeco) {
        val shape = RoundedCornerShape(boxCornerRadius)
        Modifier
            .let { if (boxCornerRadius > 0.dp) it.clip(shape) else it }
            .let { if (boxFill != Color.Unspecified && boxFill.alpha > 0f) it.background(boxFill, shape) else it }
            .let {
                if (boxBorderWidth > 0.dp && boxBorderColor != Color.Unspecified && boxBorderColor.alpha > 0f) {
                    it.border(boxBorderWidth, boxBorderColor, shape)
                } else {
                    it
                }
            }
            .let { if (boxPadding > 0.dp) it.padding(boxPadding) else it }
    } else {
        Modifier
    }

    val taggedModifier = modifier
        .modifierLocalConsumer { stageAlphaProvider = ModifierLocalStageAlpha.current }
        .onPlaced { outerCoords = it }
        .reviewTarget(
            kind = kind,
            label = currentText.take(48).ifBlank { label ?: "MagicText" },
            textSnippet = currentText,
        )
    // The timeline target wraps the text's own bounds (not the filled slot) so stage picking and
    // the selection highlight hug the visible prose.
    Box(taggedModifier) {
        LocalDeckTextRenderer.current.AnimatedText(
            effectiveTransition,
            Modifier
                .wrapContentSize(Alignment.TopStart)
                .then(offsetMod)
                .then(boxDecoMod)
                .onPlaced { innerCoords = it }
                .timelineTarget(trackId),
            effectiveStyle,
        )
    }
}
