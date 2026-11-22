package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.text.magic.MorphBy as MagicMorphBy
import dev.sebastiano.ciacola.LocalDeckTextRenderer
import dev.sebastiano.ciacola.core.fonts.ClasspathFontResourceLoader
import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.document.ConnectorPayload
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeliveryBy
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.HeadStyle
import dev.sebastiano.ciacola.document.LineHeads
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.PieceSchedule
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.document.ShapePayload
import dev.sebastiano.ciacola.document.Slot
import dev.sebastiano.ciacola.document.StrokeSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TextStyleOverride
import dev.sebastiano.ciacola.effects.ConnectorAnchor
import dev.sebastiano.ciacola.effects.ConnectorStyle
import dev.sebastiano.ciacola.effects.LocalOverlayStageAnchorRegistry
import dev.sebastiano.ciacola.effects.LocalStageAnchorRegistry
import dev.sebastiano.ciacola.effects.buildConnectorPath
import dev.sebastiano.ciacola.effects.resolveConnectorEndpoints
import dev.sebastiano.ciacola.effects.resolveStageBounds
import dev.sebastiano.ciacola.effects.v2.CurveV2
import dev.sebastiano.ciacola.effects.v2.EffectArgs
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind
import dev.sebastiano.ciacola.effects.v2.LayerState
import dev.sebastiano.ciacola.effects.v2.PathTrimState
import dev.sebastiano.ciacola.effects.v2.applyContentEffect
import dev.sebastiano.ciacola.effects.v2.applyLayerState
import dev.sebastiano.ciacola.effects.v2.drawLineDrawPath
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.text.v2.DeckTextRendererV2
import dev.sebastiano.ciacola.text.v2.EffectCallV2
import dev.sebastiano.ciacola.text.v2.MagicTextV2Config
import dev.sebastiano.ciacola.text.v2.MagicTextV2Renderer
import dev.sebastiano.ciacola.text.v2.PartitionGranularity
import dev.sebastiano.ciacola.text.v2.PieceScheduleV2
import dev.sebastiano.ciacola.text.v2.PieceSlotV2
import dev.sebastiano.ciacola.text.v2.StyledTextV2
import dev.sebastiano.ciacola.text.v2.TextContentV2
import dev.sebastiano.ciacola.theme.DeckTheme
import java.io.File
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.skia.Path as SkiaPath
import org.jetbrains.skia.PathMeasure as SkiaPathMeasure

private val parsedSvgPathCache: MutableMap<String, SkiaPath> = Collections.synchronizedMap(
    object : LinkedHashMap<String, SkiaPath>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SkiaPath>?): Boolean = size > 128
    },
)

private val sharedMagicTextRenderer: MagicTextV2Renderer = MagicTextV2Renderer()

/** Default `MagicText v2` renderer bound at the application [dev.sebastiano.ciacola.DeckTextRenderer] seam. */
val DefaultMagicTextV2Renderer: MagicTextV2Renderer
    get() = sharedMagicTextRenderer

actual fun sampleSvgPathOffset(pathData: String, fraction: Float): Offset {
    if (pathData.isBlank()) return Offset.Zero
    val f = fraction.coerceIn(0f, 1f)
    if (f <= 0f) return Offset.Zero
    val skiaPath = parsedSvgPathCache.getOrPut(pathData) {
        runCatching {
            PathParser().parsePathString(pathData).toPath().asSkiaPath()
        }.getOrElse { SkiaPath() }
    }
    if (skiaPath.isEmpty) return Offset.Zero

    return SkiaPathMeasure(skiaPath, false).use { measure ->
        val totalLen = measure.length
        if (totalLen <= 1e-4f) {
            Offset.Zero
        } else {
            val startPt = measure.getPosition(0f) ?: return@use Offset.Zero
            val curPt = measure.getPosition(totalLen * f) ?: return@use Offset.Zero
            Offset(curPt.x - startPt.x, curPt.y - startPt.y)
        }
    }
}

private data class FontResolverCacheKey(
    val declarations: List<DeckFontDeclaration>,
    val fallbackDirPaths: List<String>,
)

private object SharedDeckFontResolverCache {
    private const val MAX_ENTRIES = 16
    private val lock = Any()
    private val lru = object : LinkedHashMap<FontResolverCacheKey, DeckFontResolver>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<FontResolverCacheKey, DeckFontResolver>?,
        ): Boolean = size > MAX_ENTRIES
    }

    fun getOrCreate(
        declarations: List<DeckFontDeclaration>,
        fallbackDirs: List<File>,
    ): DeckFontResolver {
        val key = FontResolverCacheKey(
            declarations = declarations.toList(),
            fallbackDirPaths = fallbackDirs.map { it.absolutePath },
        )
        synchronized(lock) {
            lru[key]?.let { return it }
        }
        val created = DeckFontResolver(
            declarations = key.declarations,
            resourceLoader = ClasspathFontResourceLoader(fallbackDirectories = fallbackDirs),
        )
        synchronized(lock) {
            return lru.getOrPut(key) { created }
        }
    }
}

@Composable
actual fun rememberDocumentFontResolverHandle(
    document: DeckDocument,
    fontsDir: String?,
): Any? = remember(document.fonts, fontsDir) {
    createDocumentFontResolver(document, fontsDir)
}

fun createDocumentFontResolver(
    document: DeckDocument,
    fontsDir: String? = null,
): DeckFontResolver? = if (document.fonts.isEmpty()) {
    null
} else {
    val fallbackDirs = buildList {
        if (!fontsDir.isNullOrBlank()) add(File(fontsDir))
        val decksProp = System.getProperty("ciacola.decks.dir")
        if (!decksProp.isNullOrBlank()) add(File(decksProp))
    }
    SharedDeckFontResolverCache.getOrCreate(
        declarations = document.fonts,
        fallbackDirs = fallbackDirs,
    )
}

actual fun Modifier.applyDocumentLayerState(state: LayerState): Modifier =
    this.applyLayerState(state)

actual fun Modifier.applyDocumentContentEffect(
    effectId: String,
    kind: EffectBuildKind,
    progress: Float,
    args: EffectArgs,
    reducedMotion: Boolean,
): Modifier = this.applyContentEffect(
    effectId = effectId,
    kind = kind,
    progress = progress,
    args = args,
    reducedMotion = reducedMotion,
)

@Composable
actual fun DocumentShapePayloadView(
    payload: ShapePayload,
    colorResolver: DocumentColorResolver,
    density: Density,
    deckId: String,
    assetsRootDir: String?,
    pathTrim: PathTrimState?,
    modifier: Modifier,
    shapeReferenceSize: Size?,
) {
    val imageFill = payload.fill as? Fill.Image
    val imageBitmap = if (imageFill != null) {
        remember(deckId, imageFill.image.src, assetsRootDir) {
            DocumentAssetLoader.loadImageBitmap(deckId, imageFill.image.src, assetsRootDir)
        }
    } else {
        null
    }
    val customSvgPath = remember(payload.geometry) {
        (payload.geometry as? ShapeGeometry.Path)?.let { geom ->
            runCatching { PathParser().parsePathString(geom.path).toPath() }.getOrNull()
        }
    }

    val scratchPath = remember { Path() }
    val scratchMatrix = remember { Matrix() }

    Canvas(modifier = modifier) {
        val shapePath = buildShapePath(
            geometry = payload.geometry,
            customSvgPath = customSvgPath,
            cornerRadiusPx = (payload.cornerRadius ?: 0.0).toFloat().coerceAtLeast(0f),
            strokeWidthPx = (payload.stroke?.width ?: 2.0).toFloat(),
            size = size,
            referenceSize = shapeReferenceSize,
            outPath = scratchPath,
            scratchMatrix = scratchMatrix,
        )

        val stroke = payload.stroke
        val hasVisibleStroke = stroke != null && stroke.width > 0.0
        val fillAlpha = when {
            pathTrim == null -> 1f
            hasVisibleStroke -> {
                val t = ((pathTrim.visibleFraction - 0.75f) / 0.25f).coerceIn(0f, 1f)
                val smooth = t * t * (3f - 2f * t)
                (smooth * pathTrim.alpha).coerceIn(0f, 1f)
            }
            pathTrim.visibleFraction <= 1e-4f -> 0f
            else -> pathTrim.alpha.coerceIn(0f, 1f)
        }

        // 1. Fill (with late smoothstep fade when bordered, or progressive clip-reveal when stroke-less)
        if (payload.fill != null && fillAlpha > 0f) {
            val drawFillContent: DrawScope.() -> Unit = {
                when (val fill = payload.fill) {
                    is Fill.Solid -> {
                        val c = colorResolver.resolve(fill.color, Color.Transparent)
                        if (c.alpha > 0f) {
                            drawPath(path = shapePath, color = c, alpha = fillAlpha)
                        }
                    }
                    is Fill.Gradient -> {
                        val brush = fill.gradient.toComposeBrush(size, colorResolver)
                        drawPath(path = shapePath, brush = brush, alpha = fillAlpha)
                    }
                    is Fill.Image -> {
                        if (imageBitmap != null) {
                            val tintColor = fill.image.tint?.let { colorResolver.resolveOrNull(it) }
                            clipPath(shapePath) {
                                drawImage(
                                    image = imageBitmap,
                                    dstSize = IntSize(
                                        size.width.roundToInt().coerceAtLeast(1),
                                        size.height.roundToInt().coerceAtLeast(1),
                                    ),
                                    alpha = fillAlpha,
                                    colorFilter = tintColor?.let { ColorFilter.tint(it) },
                                )
                            }
                        }
                    }
                    null -> Unit
                }
            }

            val needsClipReveal = pathTrim != null && !hasVisibleStroke &&
                (pathTrim.startFraction > 1e-4f || pathTrim.endFraction < 1f - 1e-4f)
            if (needsClipReveal) {
                val startF = pathTrim!!.startFraction.coerceIn(0f, 1f)
                val endF = pathTrim.endFraction.coerceIn(startF, 1f)
                if (size.height > size.width * 1.5f) {
                    clipRect(
                        left = 0f,
                        top = startF * size.height,
                        right = size.width,
                        bottom = endF * size.height,
                    ) { drawFillContent() }
                } else {
                    clipRect(
                        left = startF * size.width,
                        top = 0f,
                        right = endF * size.width,
                        bottom = size.height,
                    ) { drawFillContent() }
                }
            } else {
                drawFillContent()
            }
        }

        // 2. Stroke (+ Path-tier lineDraw and LineHeads)
        if (hasVisibleStroke) {
            val strokeColor = colorResolver.resolve(stroke!!.color, Color.Transparent)
            if (strokeColor.alpha > 0f) {
                drawStrokedPathWithHeads(
                    source = shapePath,
                    strokeSpec = stroke,
                    strokeColor = strokeColor,
                    minStrokePx = 0.5f,
                    heads = payload.heads,
                    pathTrim = pathTrim,
                )
            }
        }
    }
}

@Composable
actual fun DocumentImagePayloadView(
    deckId: String,
    src: String,
    fit: String,
    tint: Color?,
    assetsRootDir: String?,
    modifier: Modifier,
) {
    val painter = remember(deckId, src, assetsRootDir) {
        DocumentAssetLoader.loadPainter(deckId, src, assetsRootDir)
    }
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = src,
            contentScale = DocumentAssetLoader.parseContentScale(fit),
            colorFilter = tint?.let { ColorFilter.tint(it) },
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier
                .background(Color(0x3322222A), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0x88FF9F1C), RoundedCornerShape(6.dp))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Missing image: $src", color = Color(0xFFFF9F1C))
        }
    }
}

@Composable
actual fun DocumentImageFillView(
    deckId: String,
    src: String,
    fit: String,
    tint: Color?,
    assetsRootDir: String?,
    modifier: Modifier,
) {
    val painter = remember(deckId, src, assetsRootDir) {
        DocumentAssetLoader.loadPainter(deckId, src, assetsRootDir)
    }
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = DocumentAssetLoader.parseContentScale(fit),
            colorFilter = tint?.let { ColorFilter.tint(it) },
            modifier = modifier,
        )
    }
}

@Composable
actual fun DocumentConnectorPayloadView(
    payload: ConnectorPayload,
    colorResolver: DocumentColorResolver,
    density: Density,
    pathTrim: PathTrimState?,
    modifier: Modifier,
) {
    val anchorRegistry = LocalStageAnchorRegistry.current
    val overlayAnchorRegistry = LocalOverlayStageAnchorRegistry.current
    val inspectionRegistry = LocalStageInspectionRegistry.current
    if (anchorRegistry == null && overlayAnchorRegistry == null && inspectionRegistry == null) return
    val rootHolder = LocalStageRootCoordinates.current
    val dragPreview = LocalStageDragPreview.current
    val canvasCoordsHolder = remember { object { var coords: LayoutCoordinates? = null } }
    val sourcePath = remember { Path() }

    val fromAnchor = parseConnectorAnchor(payload.fromAnchor)
    val toAnchor = parseConnectorAnchor(payload.toAnchor)
    val style = parseConnectorStyle(payload.style)
    val strokeSpec = payload.stroke ?: StrokeSpec(color = "@accent", width = 4.0)
    val strokeColor = colorResolver.resolve(payload.stroke?.color, colorResolver.baseTheme.colors.accent)
    val gapPx = (payload.gap ?: 0.0).toFloat().coerceAtLeast(0f)
    val effectiveHeads = payload.heads ?: if (style == ConnectorStyle.Line || style == ConnectorStyle.TreeEdge) {
        LineHeads(HeadStyle.None, HeadStyle.None)
    } else {
        LineHeads(HeadStyle.None, HeadStyle.Filled)
    }

    Canvas(modifier = modifier.fillMaxSize().onPlaced { canvasCoordsHolder.coords = it }) {
        val canvasCoords = canvasCoordsHolder.coords
        overlayAnchorRegistry?.versionOf(payload.from)
        overlayAnchorRegistry?.versionOf(payload.to)
        anchorRegistry?.versionOf(payload.from)
        anchorRegistry?.versionOf(payload.to)
        dragPreview?.offsetFor(payload.from)
        dragPreview?.offsetFor(payload.to)

        val origin by lazy(LazyThreadSafetyMode.NONE) {
            resolveStageBounds(canvasCoords, rootHolder)?.topLeft ?: Offset.Zero
        }
        val fromLocal = overlayAnchorRegistry?.boundsRelativeTo(payload.from, canvasCoords)
            ?: anchorRegistry?.boundsRelativeTo(payload.from, canvasCoords)
            ?: (inspectionRegistry?.liveStageBoundsOf(payload.from) ?: inspectionRegistry?.nodes?.get(payload.from)?.stageBounds)
                ?.translate(-origin.x, -origin.y)
            ?: return@Canvas
        val toLocal = overlayAnchorRegistry?.boundsRelativeTo(payload.to, canvasCoords)
            ?: anchorRegistry?.boundsRelativeTo(payload.to, canvasCoords)
            ?: (inspectionRegistry?.liveStageBoundsOf(payload.to) ?: inspectionRegistry?.nodes?.get(payload.to)?.stageBounds)
                ?.translate(-origin.x, -origin.y)
            ?: return@Canvas

        val endpoints = resolveConnectorEndpoints(
            fromRect = fromLocal,
            toRect = toLocal,
            fromAnchor = fromAnchor,
            toAnchor = toAnchor,
            gapPx = gapPx,
            layoutDirection = layoutDirection,
        )
        buildConnectorPath(endpoints, style, sourcePath)
        drawStrokedPathWithHeads(
            source = sourcePath,
            strokeSpec = strokeSpec,
            strokeColor = strokeColor,
            minStrokePx = 1f,
            heads = effectiveHeads,
            pathTrim = pathTrim,
        )
    }
}

private fun DrawScope.drawStrokedPathWithHeads(
    source: Path,
    strokeSpec: StrokeSpec,
    strokeColor: Color,
    minStrokePx: Float,
    heads: LineHeads?,
    pathTrim: PathTrimState?,
) {
    val strokePx = strokeSpec.width.toFloat().coerceAtLeast(minStrokePx)
    val cap = parseStrokeCap(strokeSpec.cap)
    val join = parseStrokeJoin(strokeSpec.join)
    val headLenPx = (strokePx * 4.2f).coerceIn(10f, 36f)
    val hasActiveHeads = heads != null && (heads.start != HeadStyle.None || heads.end != HeadStyle.None)
    if (pathTrim == null && !hasActiveHeads) {
        drawPath(
            path = source,
            color = strokeColor,
            style = Stroke(
                width = strokePx,
                cap = cap,
                join = join,
                pathEffect = buildDashPathEffect(strokeSpec.dash, cap),
            ),
        )
    } else {
        drawLineDrawPath(
            source = source,
            state = pathTrim ?: PathTrimState.Full,
            color = strokeColor,
            strokeWidthPx = strokePx,
            cap = cap,
            join = join,
            heads = heads,
            headLengthPx = headLenPx,
            headAngleDegrees = 26.565f,
            dash = strokeSpec.dash,
        )
    }
}

private fun buildShapePath(
    geometry: ShapeGeometry,
    customSvgPath: Path?,
    cornerRadiusPx: Float,
    strokeWidthPx: Float,
    size: Size,
    referenceSize: Size? = null,
    outPath: Path,
    scratchMatrix: Matrix,
): Path {
    val w = size.width.coerceAtLeast(1f)
    val h = size.height.coerceAtLeast(1f)
    return when (geometry) {
        ShapeGeometry.Rect -> outPath.apply {
            reset()
            if (cornerRadiusPx > 0f) {
                val clampedR = cornerRadiusPx.coerceAtMost(minOf(w, h) * 0.5f)
                addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(clampedR, clampedR)))
            } else {
                addRect(Rect(0f, 0f, w, h))
            }
        }
        ShapeGeometry.Ellipse -> outPath.apply {
            reset()
            addOval(Rect(0f, 0f, w, h))
        }
        ShapeGeometry.Line -> outPath.apply {
            reset()
            val threshold = maxOf(4f, strokeWidthPx * 2f)
            when {
                h <= threshold -> {
                    moveTo(0f, h / 2f)
                    lineTo(w, h / 2f)
                }
                w <= threshold -> {
                    moveTo(w / 2f, 0f)
                    lineTo(w / 2f, h)
                }
                else -> {
                    moveTo(0f, 0f)
                    lineTo(w, h)
                }
            }
        }
        is ShapeGeometry.Path -> {
            val src = customSvgPath ?: return outPath.apply { reset() }
            if (referenceSize != null &&
                referenceSize.width > 0.5f &&
                referenceSize.height > 0.5f &&
                (abs(w - referenceSize.width) > 0.5f || abs(h - referenceSize.height) > 0.5f)
            ) {
                val sx = w / referenceSize.width
                val sy = h / referenceSize.height
                scratchMatrix.reset()
                scratchMatrix.scale(sx, sy)
                outPath.apply {
                    reset()
                    addPath(src)
                    transform(scratchMatrix)
                }
            } else {
                src
            }
        }
    }
}

private fun parseConnectorAnchor(raw: String?): ConnectorAnchor = when (raw?.lowercase()) {
    "top" -> ConnectorAnchor.Top
    "bottom" -> ConnectorAnchor.Bottom
    "start", "left" -> ConnectorAnchor.Start
    "end", "right" -> ConnectorAnchor.End
    "center" -> ConnectorAnchor.Center
    else -> ConnectorAnchor.Auto
}

private fun parseConnectorStyle(raw: String?): ConnectorStyle = when (raw?.lowercase()) {
    "line" -> ConnectorStyle.Line
    "curvedarrow" -> ConnectorStyle.CurvedArrow
    "orthogonal" -> ConnectorStyle.Orthogonal
    "treeedge" -> ConnectorStyle.TreeEdge
    else -> ConnectorStyle.Arrow
}
