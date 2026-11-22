package dev.sebastiano.ciacola.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LocalContentColor
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageParentElementId
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.ModifierLocalStageAlpha
import dev.sebastiano.ciacola.inspector.StageElementMetadata
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.inspector.StageRootCoordinatesHolder
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.LocalDeckTheme

/**
 * Non-snapshot placement holder so placement-phase drags update coordinates and registry bounds
 * without triggering slide recompositions.
 */
private class StagePlacementRef {
    var coords: LayoutCoordinates? = null
    var layoutResult: TextLayoutResult? = null
    var stageAlphaProvider: () -> Float = { 1f }
}

/**
 * Resolves the effective background colour for the current composition subtree, falling back to the
 * active [LocalDeckTheme] background when [LocalEffectiveBackgroundColor] is unspecified.
 */
@Composable
fun effectiveBackgroundColor(): Color {
    val theme = LocalDeckTheme.current
    return LocalEffectiveBackgroundColor.current.takeOrElse { theme.colors.background }
}

internal fun resolveStageBounds(
    coords: LayoutCoordinates?,
    rootHolder: StageRootCoordinatesHolder?,
): Rect? {
    val c = coords?.takeIf { it.isAttached } ?: return null
    if (rootHolder != null) {
        val stageRoot = rootHolder.coordinates?.takeIf { it.isAttached } ?: return null
        return runCatching { stageRoot.localBoundingBoxOf(c, clipBounds = false) }.getOrNull()
    }
    return runCatching { c.boundsInRoot() }.getOrNull()
}

internal fun compositeBackgroundOver(fill: Color, parentBackground: Color): Color = when {
    fill == Color.Unspecified || fill.alpha <= 0f -> parentBackground
    fill.alpha >= 0.999f -> fill
    else -> fill.compositeOver(parentBackground)
}

/**
 * Stage text primitive wired to [LocalStageInspectionRegistry] and [LocalStageAnchorRegistry].
 */
@Composable
fun DeckEditableText(
    elementId: String,
    text: String,
    modifier: Modifier = Modifier,
    label: String = text.take(48).ifBlank { elementId },
    kind: String = "Text",
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
    recordSnapshot: Boolean = true,
    mergeWithExistingBox: Boolean = false,
) {
    val theme = LocalDeckTheme.current
    val registry = LocalStageInspectionRegistry.current
    val anchorRegistry = LocalStageAnchorRegistry.current
    val overviewOpen = LocalOverviewOpen.current
    val rootHolder = LocalStageRootCoordinates.current
    val dragPreview = LocalStageDragPreview.current
    val parentElementId = LocalStageParentElementId.current?.takeIf { it != elementId }
    val effectiveBg = effectiveBackgroundColor()

    val resolvedColor = color.takeOrElse { style.color }.takeOrElse { LocalContentColor.current }.takeOrElse { theme.colors.foreground }
    val resolvedFontSizeSp = if (style.fontSize.isSp) style.fontSize.value.coerceAtLeast(6f) else 16f
    val resolvedStyle = style.copy(color = resolvedColor)

    val shouldInspect = recordSnapshot && registry != null && !overviewOpen
    val ownerToken = remember { Any() }
    val placementRef = remember { StagePlacementRef() }

    if (!mergeWithExistingBox && anchorRegistry != null) {
        DisposableEffect(anchorRegistry, elementId) {
            onDispose { anchorRegistry.remove(elementId, ownerToken) }
        }
    }

    if (shouldInspect && !mergeWithExistingBox) {
        DisposableEffect(registry, elementId, shouldInspect) {
            onDispose { registry.remove(elementId, ownerToken) }
        }
    }

    fun publishSnapshot(coords: LayoutCoordinates?, layout: TextLayoutResult?) {
        if (!shouldInspect) return
        val defaultWeight = style.fontWeight?.weight ?: 400

        val existingMeta = if (mergeWithExistingBox) registry.metadataFor(elementId) else null
        registry.recordMetadata(
            meta = (existingMeta ?: StageElementMetadata(elementId = elementId)).copy(
                supportsText = true,
                defaultText = text,
                defaultTextColor = resolvedColor,
                defaultFontSizeSp = resolvedFontSizeSp,
                defaultFontWeight = defaultWeight,
            ),
            owner = if (mergeWithExistingBox) null else ownerToken,
        )

        val stageBounds = if (mergeWithExistingBox) {
            registry.nodes[elementId]?.stageBounds ?: resolveStageBounds(coords, rootHolder)
        } else {
            resolveStageBounds(coords, rootHolder)
        } ?: return

        val hasEllipsis = layout != null && (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
        val existingNode = if (mergeWithExistingBox) registry.nodes[elementId] else null
        val snapshot = StageNodeSnapshot(
            elementId = elementId,
            kind = existingNode?.kind ?: kind,
            label = if (mergeWithExistingBox && existingNode != null) text else label,
            sceneIndex = 0,
            beatIndex = 0,
            stageBounds = stageBounds,
            resolvedText = text,
            hasVisualOverflow = layout?.hasVisualOverflow ?: false,
            hasEllipsis = hasEllipsis,
            effectiveForeground = resolvedColor,
            effectiveBackground = existingNode?.effectiveBackground ?: effectiveBg,
            effectiveFontSizeSp = resolvedFontSizeSp,
            isEditable = true,
            parentElementId = existingNode?.parentElementId ?: parentElementId,
            shaderUniforms = existingNode?.shaderUniforms ?: emptyList(),
        )
        if (mergeWithExistingBox) {
            registry.record(
                snapshot = snapshot,
                alphaProvider = { placementRef.stageAlphaProvider() },
            )
        } else {
            registry.record(
                snapshot = snapshot,
                owner = ownerToken,
                alphaProvider = { placementRef.stageAlphaProvider() },
                coordinatesProvider = { placementRef.coords },
                stageRootProvider = { rootHolder?.coordinates },
            )
        }
    }

    if (shouldInspect) {
        observeStageRootCoordinates(rootHolder)
        SideEffect {
            publishSnapshot(placementRef.coords, placementRef.layoutResult)
        }
    }

    val offsetModifier = if (!mergeWithExistingBox) {
        Modifier.offset {
            val preview = dragPreview?.offsets?.get(elementId)
            IntOffset((preview?.x ?: 0.dp).roundToPx(), (preview?.y ?: 0.dp).roundToPx())
        }
    } else {
        Modifier
    }

    val reviewMod = if (mergeWithExistingBox) {
        Modifier
    } else {
        Modifier.reviewTarget(kind = kind, label = text.take(48).ifBlank { label }, textSnippet = text)
    }

    Text(
        text = text,
        style = resolvedStyle,
        color = resolvedColor,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        softWrap = softWrap,
        onTextLayout = { result ->
            placementRef.layoutResult = result
            publishSnapshot(placementRef.coords, result)
        },
        modifier = modifier
            .modifierLocalConsumer { placementRef.stageAlphaProvider = ModifierLocalStageAlpha.current }
            .then(offsetModifier)
            .onPlaced { coords ->
                placementRef.coords = coords
                if (!mergeWithExistingBox) {
                    anchorRegistry?.record(elementId, coords, ownerToken)
                }
                publishSnapshot(coords, placementRef.layoutResult)
            }
            .then(reviewMod),
    )
}

/**
 * Box styling modifier wired to [LocalStageInspectionRegistry] and [LocalStageAnchorRegistry].
 */
fun Modifier.editableBox(
    elementId: String,
    label: String = elementId,
    kind: String = "Box",
    defaultFill: Color = Color.Unspecified,
    defaultBorderColor: Color = Color.Unspecified,
    defaultBorderWidth: Dp = 0.dp,
    defaultCornerRadius: Dp = 0.dp,
    defaultPadding: Dp = 0.dp,
    recordSnapshot: Boolean = true,
    defaultPaddingValues: PaddingValues? = null,
): Modifier = composed {
    val theme = LocalDeckTheme.current
    val registry = LocalStageInspectionRegistry.current
    val anchorRegistry = LocalStageAnchorRegistry.current
    val overviewOpen = LocalOverviewOpen.current
    val rootHolder = LocalStageRootCoordinates.current
    val dragPreview = LocalStageDragPreview.current
    val parentElementId = LocalStageParentElementId.current?.takeIf { it != elementId }
    val parentBg = LocalEffectiveBackgroundColor.current.takeOrElse { theme.colors.background }

    val fillColor = defaultFill
    val borderColor = defaultBorderColor
    val borderWidth = defaultBorderWidth.coerceAtLeast(0.dp)
    val cornerRadius = defaultCornerRadius.coerceAtLeast(0.dp)
    val defaultPaddingDpValue = defaultPaddingValues?.calculateTopPadding()?.value ?: defaultPadding.value

    val effectiveBg = compositeBackgroundOver(fillColor, parentBg)
    val shouldInspect = recordSnapshot && registry != null && !overviewOpen
    val ownerToken = remember { Any() }
    val placementRef = remember { StagePlacementRef() }

    if (anchorRegistry != null) {
        DisposableEffect(anchorRegistry, elementId) {
            onDispose { anchorRegistry.remove(elementId, ownerToken) }
        }
    }

    if (shouldInspect) {
        DisposableEffect(registry, elementId, shouldInspect) {
            onDispose { registry.remove(elementId, ownerToken) }
        }
    }

    fun publishBoxSnapshot(coords: LayoutCoordinates?) {
        if (!shouldInspect) return
        val existingMeta = registry.metadataFor(elementId)
        registry.recordMetadata(
            meta = (existingMeta ?: StageElementMetadata(elementId = elementId)).copy(
                supportsBoxStyle = true,
                defaultFillColor = defaultFill.takeIf { it != Color.Unspecified },
                defaultBorderColor = defaultBorderColor.takeIf { it != Color.Unspecified },
                defaultBorderWidthDp = defaultBorderWidth.value,
                defaultCornerRadiusDp = defaultCornerRadius.value,
                defaultPaddingDp = defaultPaddingDpValue,
            ),
            owner = ownerToken,
        )

        val stageBounds = resolveStageBounds(coords, rootHolder) ?: return
        val existing = registry.nodes[elementId]
        val snapshot = StageNodeSnapshot(
            elementId = elementId,
            kind = kind,
            label = label,
            sceneIndex = 0,
            beatIndex = 0,
            stageBounds = stageBounds,
            resolvedText = existing?.resolvedText.orEmpty(),
            hasVisualOverflow = existing?.hasVisualOverflow ?: false,
            hasEllipsis = existing?.hasEllipsis ?: false,
            effectiveForeground = existing?.effectiveForeground,
            effectiveBackground = effectiveBg,
            effectiveFontSizeSp = existing?.effectiveFontSizeSp,
            isEditable = true,
            parentElementId = parentElementId,
            shaderUniforms = existing?.shaderUniforms ?: emptyList(),
        )
        registry.record(
            snapshot = snapshot,
            owner = ownerToken,
            alphaProvider = { placementRef.stageAlphaProvider() },
            coordinatesProvider = { placementRef.coords },
            stageRootProvider = { rootHolder?.coordinates },
        )
    }

    if (shouldInspect) {
        observeStageRootCoordinates(rootHolder)
        SideEffect {
            publishBoxSnapshot(placementRef.coords)
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    val hasShape = cornerRadius > 0.dp ||
        (fillColor != Color.Unspecified && fillColor.alpha > 0f) ||
        (borderWidth > 0.dp && borderColor != Color.Unspecified && borderColor.alpha > 0f)

    this
        .modifierLocalConsumer { placementRef.stageAlphaProvider = ModifierLocalStageAlpha.current }
        .offset {
            val preview = dragPreview?.offsets?.get(elementId)
            IntOffset((preview?.x ?: 0.dp).roundToPx(), (preview?.y ?: 0.dp).roundToPx())
        }
        .onPlaced { coords ->
            placementRef.coords = coords
            anchorRegistry?.record(elementId, coords, ownerToken)
            publishBoxSnapshot(coords)
        }
        .reviewTarget(kind = kind, label = label, isContainer = true)
        .let { if (hasShape && cornerRadius > 0.dp) it.clip(shape) else it }
        .let { if (fillColor != Color.Unspecified && fillColor.alpha > 0f) it.background(fillColor, shape) else it }
        .let {
            if (borderWidth > 0.dp && borderColor != Color.Unspecified && borderColor.alpha > 0f) {
                it.border(borderWidth, borderColor, shape)
            } else {
                it
            }
        }
        .let {
            when {
                defaultPaddingValues != null -> it.padding(defaultPaddingValues)
                defaultPadding > 0.dp -> it.padding(defaultPadding)
                else -> it
            }
        }
}

/**
 * Container composable applying [Modifier.editableBox] and updating [LocalEffectiveBackgroundColor]
 * and [LocalStageParentElementId] for all descendants.
 */
@Composable
fun EditableBox(
    elementId: String,
    modifier: Modifier = Modifier,
    label: String = elementId,
    kind: String = "Box",
    defaultFill: Color = Color.Unspecified,
    defaultBorderColor: Color = Color.Unspecified,
    defaultBorderWidth: Dp = 0.dp,
    defaultCornerRadius: Dp = 0.dp,
    defaultPadding: Dp = 0.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    defaultPaddingValues: PaddingValues? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val effectiveBg = compositeBackgroundOver(defaultFill, effectiveBackgroundColor())
    Box(
        modifier = modifier.editableBox(
            elementId = elementId,
            label = label,
            kind = kind,
            defaultFill = defaultFill,
            defaultBorderColor = defaultBorderColor,
            defaultBorderWidth = defaultBorderWidth,
            defaultCornerRadius = defaultCornerRadius,
            defaultPadding = defaultPadding,
            defaultPaddingValues = defaultPaddingValues,
        ),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(
            LocalEffectiveBackgroundColor provides effectiveBg,
            LocalStageParentElementId provides elementId,
        ) {
            content()
        }
    }
}

/**
 * Records a non-[DeckEditableText] text element into [StageInspectionRegistry] and [StageElementMetadata].
 */
@Composable
internal fun RecordStageTextInspection(
    elementId: String?,
    kind: String,
    label: String,
    defaultText: String,
    resolvedText: String,
    defaultTextColor: Color,
    effectiveForeground: Color,
    effectiveBackground: Color,
    defaultFontSizeSp: Float,
    effectiveFontSizeSp: Float,
    defaultFontWeight: Int,
    coords: LayoutCoordinates?,
    layoutResult: TextLayoutResult?,
    alphaProvider: (() -> Float)? = null,
) {
    if (elementId == null) return
    val anchorRegistry = LocalStageAnchorRegistry.current
    val ownerToken = remember { Any() }
    if (anchorRegistry != null) {
        DisposableEffect(anchorRegistry, elementId) {
            onDispose { anchorRegistry.remove(elementId, ownerToken) }
        }
        SideEffect {
            anchorRegistry.record(elementId, coords, ownerToken)
        }
    }
    val registry = LocalStageInspectionRegistry.current ?: return
    val overviewOpen = LocalOverviewOpen.current
    if (overviewOpen) return

    val rootHolder = LocalStageRootCoordinates.current
    val parentElementId = LocalStageParentElementId.current?.takeIf { it != elementId }
    observeStageRootCoordinates(rootHolder)

    DisposableEffect(registry, elementId) {
        onDispose { registry.remove(elementId, ownerToken) }
    }

    SideEffect {
        registry.recordMetadata(
            meta = StageElementMetadata(
                elementId = elementId,
                supportsText = true,
                defaultText = defaultText,
                defaultTextColor = defaultTextColor,
                defaultFontSizeSp = defaultFontSizeSp,
                defaultFontWeight = defaultFontWeight,
            ),
            owner = ownerToken,
        )
        val stageBounds = resolveStageBounds(coords, rootHolder) ?: return@SideEffect
        val hasEllipsis = layoutResult != null && (0 until layoutResult.lineCount).any { layoutResult.isLineEllipsized(it) }
        registry.record(
            snapshot = StageNodeSnapshot(
                elementId = elementId,
                kind = kind,
                label = label,
                sceneIndex = 0,
                beatIndex = 0,
                stageBounds = stageBounds,
                resolvedText = resolvedText,
                hasVisualOverflow = layoutResult?.hasVisualOverflow ?: false,
                hasEllipsis = hasEllipsis,
                effectiveForeground = effectiveForeground,
                effectiveBackground = effectiveBackground,
                effectiveFontSizeSp = effectiveFontSizeSp,
                isEditable = true,
                parentElementId = parentElementId,
            ),
            owner = ownerToken,
            alphaProvider = alphaProvider,
            coordinatesProvider = { coords },
            stageRootProvider = { rootHolder?.coordinates },
        )
    }
}

internal fun observeStageRootCoordinates(holder: StageRootCoordinatesHolder?) {
    if (holder?.coordinates != null) return
}
