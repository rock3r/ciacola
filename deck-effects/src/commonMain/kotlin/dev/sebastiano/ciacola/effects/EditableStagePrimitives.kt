package dev.sebastiano.ciacola.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.LocalActiveBeatIndex
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.ModifierLocalStageAlpha
import dev.sebastiano.ciacola.inspector.StageElementMetadata
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.inspector.StageRootCoordinatesHolder
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline

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

internal fun resolveBoxEffectiveBackground(
    patch: ElementPatch?,
    defaultFill: Color,
    parentBackground: Color,
): Color {
    val effectiveFill = patch?.fillColorArgb?.let(::Color) ?: defaultFill
    return compositeBackgroundOver(effectiveFill, parentBackground)
}

internal fun resolveOverriddenTextStyle(
    baseStyle: TextStyle,
    resolvedColor: Color,
    patch: ElementPatch?,
): Pair<TextStyle, Float> {
    val baseSizeSp = if (baseStyle.fontSize.isSp) baseStyle.fontSize.value else 16f
    val targetSizeSp = (patch?.fontSizeSp ?: baseSizeSp).coerceAtLeast(6f)
    val targetWeight = patch?.fontWeight?.let { FontWeight(it.coerceIn(100, 900)) } ?: baseStyle.fontWeight
    val targetLineHeight: TextUnit = if (
        patch?.fontSizeSp != null &&
        baseStyle.fontSize.isSp &&
        baseStyle.fontSize.value > 0f &&
        baseStyle.lineHeight.isSp
    ) {
        (targetSizeSp * (baseStyle.lineHeight.value / baseStyle.fontSize.value)).sp
    } else {
        baseStyle.lineHeight
    }
    val resolvedStyle = baseStyle.copy(
        color = resolvedColor,
        fontSize = targetSizeSp.sp,
        lineHeight = targetLineHeight,
        fontWeight = targetWeight,
    )
    return resolvedStyle to targetSizeSp
}

/**
 * Text primitive wired to [LocalElementOverrides] and [LocalStageInspectionRegistry].
 *
 * Applies text copy, colour, font size, weight, X/Y offset, and optional container fill/border/
 * corner/padding overrides from [ElementPatch] while keeping 100% identical default rendering when
 * no override is present.
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
    val patch = LocalElementOverrides.current.patchFor(elementId)
    val registry = LocalStageInspectionRegistry.current
    val sceneCtx = LocalActiveSceneTimeline.current
    val beatIndex = LocalActiveBeatIndex.current
    val overviewOpen = LocalOverviewOpen.current
    val rootHolder = LocalStageRootCoordinates.current
    val parentBg = effectiveBackgroundColor()

    val resolvedText = patch?.text ?: text
    val baseColor = color.takeOrElse { style.color }.takeOrElse { LocalContentColor.current }.takeOrElse { theme.colors.foreground }
    val resolvedColor = patch?.textColorArgb?.let(::Color) ?: baseColor
    val (resolvedStyle, resolvedFontSizeSp) = resolveOverriddenTextStyle(style, resolvedColor, patch)

    val boxFill = patch?.fillColorArgb?.let(::Color) ?: Color.Unspecified
    val boxBorderColor = patch?.borderColorArgb?.let(::Color) ?: Color.Unspecified
    val boxBorderWidth = (patch?.borderWidthDp ?: 0f).coerceAtLeast(0f).dp
    val boxCornerRadius = (patch?.cornerRadiusDp ?: 0f).coerceAtLeast(0f).dp
    val boxPadding = (patch?.paddingDp ?: 0f).coerceAtLeast(0f).dp
    val hasBoxDecoration = !mergeWithExistingBox && (
        patch?.fillColorArgb != null ||
            patch?.borderColorArgb != null ||
            patch?.borderWidthDp != null ||
            patch?.cornerRadiusDp != null ||
            patch?.paddingDp != null
        )
    val effectiveBg = if (hasBoxDecoration) {
        compositeBackgroundOver(boxFill, parentBg)
    } else {
        parentBg
    }

    val shouldInspect = recordSnapshot && registry != null && !overviewOpen
    val ownerToken = remember { Any() }
    var lastCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var lastLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var stageAlphaProvider by remember { mutableStateOf<() -> Float>({ 1f }) }

    if (shouldInspect && !mergeWithExistingBox) {
        DisposableEffect(registry, elementId, shouldInspect) {
            onDispose { registry?.remove(elementId, ownerToken) }
        }
    }

    fun publishSnapshot(coords: LayoutCoordinates?, layout: TextLayoutResult?) {
        if (!shouldInspect || registry == null) return
        val sceneIndex = sceneCtx?.sceneIndex ?: 0
        val defaultWeight = style.fontWeight?.weight ?: 400
        val defaultSizeSp = if (style.fontSize.isSp) style.fontSize.value else 16f

        val existingMeta = if (mergeWithExistingBox) registry.metadataFor(elementId) else null
        registry.recordMetadata(
            meta = (existingMeta ?: StageElementMetadata(elementId = elementId)).copy(
                supportsText = true,
                defaultText = text,
                defaultTextColor = baseColor,
                defaultFontSizeSp = defaultSizeSp,
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
            label = if (mergeWithExistingBox && existingNode != null) resolvedText else label,
            sceneIndex = sceneIndex,
            beatIndex = beatIndex,
            stageBounds = stageBounds,
            resolvedText = resolvedText,
            hasVisualOverflow = layout?.hasVisualOverflow ?: false,
            hasEllipsis = hasEllipsis,
            effectiveForeground = resolvedColor,
            effectiveBackground = existingNode?.effectiveBackground ?: effectiveBg,
            effectiveFontSizeSp = resolvedFontSizeSp,
            isEditable = true,
        )
        if (mergeWithExistingBox) {
            registry.record(
                snapshot = snapshot,
                alphaProvider = { stageAlphaProvider() },
            )
        } else {
            registry.record(
                snapshot = snapshot,
                owner = ownerToken,
                alphaProvider = { stageAlphaProvider() },
                coordinatesProvider = { lastCoords },
                stageRootProvider = { rootHolder?.coordinates },
            )
        }
    }

    if (shouldInspect) {
        val coordsNow = lastCoords
        val layoutNow = lastLayoutResult
        val stageRootNow = rootHolder?.coordinates
        SideEffect {
            stageRootNow
            publishSnapshot(coordsNow, layoutNow)
        }
    }

    val offsetModifier = if (!mergeWithExistingBox && (patch?.offsetXDp != null || patch?.offsetYDp != null)) {
        Modifier.offset(
            x = (patch.offsetXDp ?: 0f).dp,
            y = (patch.offsetYDp ?: 0f).dp,
        )
    } else {
        Modifier
    }

    val boxDecoModifier = if (hasBoxDecoration) {
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

    val reviewMod = if (mergeWithExistingBox) {
        Modifier
    } else {
        Modifier.reviewTarget(kind = kind, label = resolvedText.take(48).ifBlank { label }, textSnippet = resolvedText)
    }

    Text(
        text = resolvedText,
        style = resolvedStyle,
        color = resolvedColor,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        softWrap = softWrap,
        onTextLayout = { result ->
            lastLayoutResult = result
            publishSnapshot(lastCoords, result)
        },
        modifier = modifier
            .modifierLocalConsumer { stageAlphaProvider = ModifierLocalStageAlpha.current }
            .then(offsetModifier)
            .then(boxDecoModifier)
            .onPlaced { coords ->
                lastCoords = coords
                publishSnapshot(coords, lastLayoutResult)
            }
            .then(reviewMod),
    )
}

/**
 * Box styling modifier wired to [LocalElementOverrides] and [LocalStageInspectionRegistry].
 *
 * Applies background fill, border (`width` + `color`), [RoundedCornerShape], inner padding, and
 * `(offsetXDp, offsetYDp)` from [ElementPatch], while recording a container [StageNodeSnapshot].
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
): Modifier = composed {
    val theme = LocalDeckTheme.current
    val patch = LocalElementOverrides.current.patchFor(elementId)
    val registry = LocalStageInspectionRegistry.current
    val sceneCtx = LocalActiveSceneTimeline.current
    val beatIndex = LocalActiveBeatIndex.current
    val overviewOpen = LocalOverviewOpen.current
    val rootHolder = LocalStageRootCoordinates.current
    val parentBg = LocalEffectiveBackgroundColor.current.takeOrElse { theme.colors.background }

    val fillColor = patch?.fillColorArgb?.let(::Color) ?: defaultFill
    val borderColor = patch?.borderColorArgb?.let(::Color) ?: defaultBorderColor
    val borderWidth = (patch?.borderWidthDp ?: defaultBorderWidth.value).coerceAtLeast(0f).dp
    val cornerRadius = (patch?.cornerRadiusDp ?: defaultCornerRadius.value).coerceAtLeast(0f).dp
    val padding = (patch?.paddingDp ?: defaultPadding.value).coerceAtLeast(0f).dp
    val offsetX = (patch?.offsetXDp ?: 0f).dp
    val offsetY = (patch?.offsetYDp ?: 0f).dp

    val effectiveBg = compositeBackgroundOver(fillColor, parentBg)
    val shouldInspect = recordSnapshot && registry != null && !overviewOpen
    val ownerToken = remember { Any() }
    var lastCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var stageAlphaProvider by remember { mutableStateOf<() -> Float>({ 1f }) }

    if (shouldInspect) {
        DisposableEffect(registry, elementId, shouldInspect) {
            onDispose { registry?.remove(elementId, ownerToken) }
        }
    }

    fun publishBoxSnapshot(coords: LayoutCoordinates?) {
        if (!shouldInspect || registry == null) return
        val sceneIndex = sceneCtx?.sceneIndex ?: 0
        val existingMeta = registry.metadataFor(elementId)
        registry.recordMetadata(
            meta = (existingMeta ?: StageElementMetadata(elementId = elementId)).copy(
                supportsBoxStyle = true,
                defaultFillColor = defaultFill.takeIf { it != Color.Unspecified },
                defaultBorderColor = defaultBorderColor.takeIf { it != Color.Unspecified },
                defaultBorderWidthDp = defaultBorderWidth.value,
                defaultCornerRadiusDp = defaultCornerRadius.value,
                defaultPaddingDp = defaultPadding.value,
            ),
            owner = ownerToken,
        )

        val stageBounds = resolveStageBounds(coords, rootHolder) ?: return
        val existing = registry.nodes[elementId]
        val snapshot = StageNodeSnapshot(
            elementId = elementId,
            kind = kind,
            label = label,
            sceneIndex = sceneIndex,
            beatIndex = beatIndex,
            stageBounds = stageBounds,
            resolvedText = existing?.resolvedText.orEmpty(),
            hasVisualOverflow = existing?.hasVisualOverflow ?: false,
            hasEllipsis = existing?.hasEllipsis ?: false,
            effectiveForeground = existing?.effectiveForeground,
            effectiveBackground = effectiveBg,
            effectiveFontSizeSp = existing?.effectiveFontSizeSp,
            isEditable = true,
        )
        registry.record(
            snapshot = snapshot,
            owner = ownerToken,
            alphaProvider = { stageAlphaProvider() },
            coordinatesProvider = { lastCoords },
            stageRootProvider = { rootHolder?.coordinates },
        )
    }

    if (shouldInspect) {
        val coordsNow = lastCoords
        val stageRootNow = rootHolder?.coordinates
        SideEffect {
            stageRootNow
            publishBoxSnapshot(coordsNow)
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    val hasShape = cornerRadius > 0.dp ||
        (fillColor != Color.Unspecified && fillColor.alpha > 0f) ||
        (borderWidth > 0.dp && borderColor != Color.Unspecified && borderColor.alpha > 0f)

    this
        .modifierLocalConsumer { stageAlphaProvider = ModifierLocalStageAlpha.current }
        .let { if (offsetX != 0.dp || offsetY != 0.dp) it.offset(x = offsetX, y = offsetY) else it }
        .onPlaced { coords ->
            lastCoords = coords
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
        .let { if (padding > 0.dp) it.padding(padding) else it }
}

/**
 * Container composable applying [Modifier.editableBox] and updating [LocalEffectiveBackgroundColor]
 * for all descendants so nested text nodes report accurate contrast backgrounds.
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
    content: @Composable BoxScope.() -> Unit,
) {
    val patch = LocalElementOverrides.current.patchFor(elementId)
    val effectiveBg = resolveBoxEffectiveBackground(patch, defaultFill, effectiveBackgroundColor())
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
        ),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalEffectiveBackgroundColor provides effectiveBg) {
            content()
        }
    }
}

/**
 * Standard deck surface card with rounded shape, optional border/padding, automatic
 * [LocalEffectiveBackgroundColor] propagation, and optional [elementId] editing support.
 */
@Composable
fun DeckSurfaceCard(
    modifier: Modifier = Modifier,
    elementId: String? = null,
    label: String = elementId ?: "Surface Card",
    kind: String = "SurfaceCard",
    fillColor: Color = LocalDeckTheme.current.colors.surface,
    borderColor: Color = LocalDeckTheme.current.colors.border,
    borderWidth: Dp = LocalDeckTheme.current.styles.codeCard.borderWidth,
    cornerRadius: Dp = LocalDeckTheme.current.styles.codeCard.cornerRadius,
    contentPadding: Dp = 0.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    if (elementId != null) {
        EditableBox(
            elementId = elementId,
            modifier = modifier,
            label = label,
            kind = kind,
            defaultFill = fillColor,
            defaultBorderColor = borderColor,
            defaultBorderWidth = borderWidth,
            defaultCornerRadius = cornerRadius,
            defaultPadding = contentPadding,
            contentAlignment = contentAlignment,
            content = content,
        )
    } else {
        val parentBg = effectiveBackgroundColor()
        val effectiveBg = compositeBackgroundOver(fillColor, parentBg)
        val shape = RoundedCornerShape(cornerRadius)
        Box(
            modifier = modifier
                .clip(shape)
                .let { if (fillColor != Color.Unspecified && fillColor.alpha > 0f) it.background(fillColor, shape) else it }
                .let {
                    if (borderWidth > 0.dp && borderColor != Color.Unspecified && borderColor.alpha > 0f) {
                        it.border(borderWidth, borderColor, shape)
                    } else {
                        it
                    }
                }
                .let { if (contentPadding > 0.dp) it.padding(contentPadding) else it },
            contentAlignment = contentAlignment,
        ) {
            CompositionLocalProvider(LocalEffectiveBackgroundColor provides effectiveBg) {
                content()
            }
        }
    }
}

/**
 * Records a non-[DeckEditableText] text element (such as `DottedLeaderRow` trailing `BasicText` or
 * `DeckMagicText`) into [StageInspectionRegistry] and [StageElementMetadata].
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
    val registry = LocalStageInspectionRegistry.current ?: return
    val overviewOpen = LocalOverviewOpen.current
    if (overviewOpen) return

    val sceneCtx = LocalActiveSceneTimeline.current
    val beatIndex = LocalActiveBeatIndex.current
    val rootHolder = LocalStageRootCoordinates.current
    val stageRootNow = rootHolder?.coordinates
    val ownerToken = remember { Any() }

    DisposableEffect(registry, elementId) {
        onDispose { registry.remove(elementId, ownerToken) }
    }

    SideEffect {
        stageRootNow
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
                sceneIndex = sceneCtx?.sceneIndex ?: 0,
                beatIndex = beatIndex,
                stageBounds = stageBounds,
                resolvedText = resolvedText,
                hasVisualOverflow = layoutResult?.hasVisualOverflow ?: false,
                hasEllipsis = hasEllipsis,
                effectiveForeground = effectiveForeground,
                effectiveBackground = effectiveBackground,
                effectiveFontSizeSp = effectiveFontSizeSp,
                isEditable = true,
            ),
            owner = ownerToken,
            alphaProvider = alphaProvider,
            coordinatesProvider = { coords },
            stageRootProvider = { rootHolder?.coordinates },
        )
    }
}
