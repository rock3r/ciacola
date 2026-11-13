package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import dev.sebastiano.ciacola.theme.contrastRatio
import java.awt.Window as AwtWindow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.jetbrains.skia.Bitmap

/**
 * Abstraction over a rendered 1920x1080 stage frame buffer used for ground-truth pixel sampling.
 */
internal interface StagePixelBuffer {
    val width: Int
    val height: Int
    fun getArgb(x: Int, y: Int): Int
}

internal class SkiaBitmapPixelBuffer(
    private val bitmap: Bitmap,
) : StagePixelBuffer {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override fun getArgb(x: Int, y: Int): Int = bitmap.getColor(x, y)
}

internal class IntArrayPixelBuffer(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
) : StagePixelBuffer {
    override fun getArgb(x: Int, y: Int): Int = pixels[y * width + x]
}

internal fun ImageBitmap.toStagePixelBuffer(): StagePixelBuffer? {
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    return runCatching {
        readPixels(buffer = pixels, startX = 0, startY = 0, width = width, height = height, bufferOffset = 0, stride = width)
        IntArrayPixelBuffer(width = width, height = height, pixels = pixels)
    }.getOrNull()
}

/**
 * Scans Compose [SemanticsOwner]s (from an offscreen [ImageComposeScene], live [ComposeWindow], or
 * test tree) and extracts [LintTextNodeCandidate]s mapped to 1920x1080 stage coordinates.
 */
internal object SemanticsSlideScanner {

    private val ignoredTagPrefixes = listOf(
        "ciacola-lint-overlay",
        "ciacola-review-",
        "ciacola-hud-",
        "ciacola-timeline-",
        "ciacola-inspector-",
        "ciacola-element-",
        "ciacola-design-",
    )

    private val ignoredExactTags = setOf(
        "ciacola-stage-selection-overlay",
        "ciacola-preview-zoom-reset",
        "ciacola-inspector-vscrollbar",
    )

    @OptIn(ExperimentalComposeUiApi::class)
    fun scanScene(
        scene: ImageComposeScene,
        pixelBuffer: StagePixelBuffer?,
        stageWidth: Float = STAGE_WIDTH_PX,
        stageHeight: Float = STAGE_HEIGHT_PX,
        fallbackBackground: Color? = null,
    ): List<LintTextNodeCandidate> = scanOwners(
        owners = scene.semanticsOwners,
        pixelBuffer = pixelBuffer,
        explicitStageRootBox = Rect(0f, 0f, stageWidth, stageHeight),
        stageWidth = stageWidth,
        stageHeight = stageHeight,
        fallbackBackground = fallbackBackground,
    )

    fun scanLiveWindows(
        stageBoundsInRoot: Rect?,
        pixelBuffer: StagePixelBuffer?,
        stageWidth: Float = STAGE_WIDTH_PX,
        stageHeight: Float = STAGE_HEIGHT_PX,
        fallbackBackground: Color? = null,
    ): List<LintTextNodeCandidate> {
        val windows = runCatching {
            AwtWindow.getWindows().filterIsInstance<ComposeWindow>().filter { it.isShowing }
        }.getOrDefault(emptyList())
        if (windows.isEmpty()) return emptyList()
        val storyWindow = windows.firstOrNull { it.title.endsWith("- Story") } ?: windows.first()
        val owners = runCatching {
            @Suppress("UNCHECKED_CAST")
            ComposeWindow::class.java
                .getMethod("getSemanticsOwners")
                .invoke(storyWindow) as? Collection<SemanticsOwner>
        }.getOrNull().orEmpty()
        if (owners.isEmpty()) return emptyList()
        return scanOwners(
            owners = owners,
            pixelBuffer = pixelBuffer,
            explicitStageRootBox = stageBoundsInRoot,
            stageWidth = stageWidth,
            stageHeight = stageHeight,
            fallbackBackground = fallbackBackground,
        )
    }

    fun scanOwners(
        owners: Collection<SemanticsOwner>,
        pixelBuffer: StagePixelBuffer?,
        explicitStageRootBox: Rect? = null,
        stageWidth: Float = STAGE_WIDTH_PX,
        stageHeight: Float = STAGE_HEIGHT_PX,
        fallbackBackground: Color? = null,
    ): List<LintTextNodeCandidate> {
        val out = mutableListOf<LintTextNodeCandidate>()
        val textLayouts = mutableListOf<TextLayoutResult>()

        for (owner in owners) {
            val rootNode = owner.unmergedRootSemanticsNode
            val stageRootNode = findStageRoot(rootNode)
            val rootBox = explicitStageRootBox
                ?: stageRootNode?.boundsInRoot?.takeIf { it.width > 1f && it.height > 1f }
                ?: rootNode.boundsInRoot.takeIf { it.width > 1f && it.height > 1f }
                ?: Rect(0f, 0f, stageWidth, stageHeight)

            val scaleX = stageWidth / rootBox.width.coerceAtLeast(1f)
            val scaleY = stageHeight / rootBox.height.coerceAtLeast(1f)
            val startNode = stageRootNode ?: rootNode

            walkNode(
                node = startNode,
                ancestors = emptySet(),
                rootBox = rootBox,
                scaleX = scaleX,
                scaleY = scaleY,
                stageWidth = stageWidth,
                stageHeight = stageHeight,
                pixelBuffer = pixelBuffer,
                fallbackBackground = fallbackBackground,
                textLayouts = textLayouts,
                out = out,
            )
        }
        return out
    }

    private fun findStageRoot(node: SemanticsNode): SemanticsNode? {
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        if (tag == "ciacola-lint-stage" || tag == "ciacola-review-stage") {
            return node
        }
        for (child in node.children) {
            findStageRoot(child)?.let { return it }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun walkNode(
        node: SemanticsNode,
        ancestors: Set<Int>,
        rootBox: Rect,
        scaleX: Float,
        scaleY: Float,
        stageWidth: Float,
        stageHeight: Float,
        pixelBuffer: StagePixelBuffer?,
        fallbackBackground: Color?,
        textLayouts: MutableList<TextLayoutResult>,
        out: MutableList<LintTextNodeCandidate>,
    ) {
        val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
        if (tag != "ciacola-review-stage" && tag != "ciacola-lint-stage") {
            if (tag in ignoredExactTags || ignoredTagPrefixes.any { tag.startsWith(it) }) {
                return
            }
        }

        val hidden = node.config.contains(SemanticsProperties.HideFromAccessibility) ||
            node.config.contains(SemanticsProperties.InvisibleToUser)
        val effectiveAlpha = if (hidden) 0f else runCatching { node.computeEffectiveAlpha() }.getOrDefault(1f)

        if (effectiveAlpha > INVISIBLE_ALPHA_THRESHOLD) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
            if (action != null) {
                textLayouts.clear()
                action.action?.invoke(textLayouts)
                for (layout in textLayouts) {
                    extractCandidate(
                        node = node,
                        ancestors = ancestors,
                        layout = layout,
                        effectiveAlpha = effectiveAlpha,
                        rootBox = rootBox,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        stageWidth = stageWidth,
                        stageHeight = stageHeight,
                        pixelBuffer = pixelBuffer,
                        fallbackBackground = fallbackBackground,
                    )?.let(out::add)
                }
            }
        }

        val nextAncestors = ancestors + node.id
        for (child in node.children) {
            walkNode(
                node = child,
                ancestors = nextAncestors,
                rootBox = rootBox,
                scaleX = scaleX,
                scaleY = scaleY,
                stageWidth = stageWidth,
                stageHeight = stageHeight,
                pixelBuffer = pixelBuffer,
                fallbackBackground = fallbackBackground,
                textLayouts = textLayouts,
                out = out,
            )
        }
    }

    private fun extractCandidate(
        node: SemanticsNode,
        ancestors: Set<Int>,
        layout: TextLayoutResult,
        effectiveAlpha: Float,
        rootBox: Rect,
        scaleX: Float,
        scaleY: Float,
        stageWidth: Float,
        stageHeight: Float,
        pixelBuffer: StagePixelBuffer?,
        fallbackBackground: Color?,
    ): LintTextNodeCandidate? {
        val rawText = layout.layoutInput.text.text
        if (rawText.isBlank()) return null

        val posInRoot = node.positionInRoot
        val rawSize = Size(node.size.width.toFloat(), node.size.height.toFloat())
        val unclippedRoot = Rect(posInRoot, rawSize)
        val clippedRoot = node.boundsInRoot

        val unclippedStage = mapRectToStage(unclippedRoot, rootBox, scaleX, scaleY)
        val clippedStage = if (clippedRoot.width > 0.5f && clippedRoot.height > 0.5f) {
            mapRectToStage(clippedRoot, rootBox, scaleX, scaleY)
        } else {
            unclippedStage
        }
        val stageBounds = clippedStage

        // Compute tight glyph bounding box for pixel sampling around actual drawn text lines.
        val lineCount = layout.lineCount
        var glyphLeft = Float.POSITIVE_INFINITY
        var glyphTop = Float.POSITIVE_INFINITY
        var glyphRight = Float.NEGATIVE_INFINITY
        var glyphBottom = Float.NEGATIVE_INFINITY
        var maxLineWidthPx = 0f
        var hasEllipsis = false
        for (lineIdx in 0 until lineCount) {
            val l = layout.getLineLeft(lineIdx)
            val r = layout.getLineRight(lineIdx)
            val t = layout.getLineTop(lineIdx)
            val b = layout.getLineBottom(lineIdx)
            val w = (r - l).coerceAtLeast(0f) * scaleX
            if (w > maxLineWidthPx) maxLineWidthPx = w
            glyphLeft = minOf(glyphLeft, (posInRoot.x + l - rootBox.left) * scaleX)
            glyphTop = minOf(glyphTop, (posInRoot.y + t - rootBox.top) * scaleY)
            glyphRight = maxOf(glyphRight, (posInRoot.x + r - rootBox.left) * scaleX)
            glyphBottom = maxOf(glyphBottom, (posInRoot.y + b - rootBox.top) * scaleY)
            if (layout.isLineEllipsized(lineIdx)) {
                hasEllipsis = true
            }
        }
        val tightGlyphRect = if (glyphLeft.isFinite() && glyphRight > glyphLeft && glyphBottom > glyphTop) {
            Rect(glyphLeft, glyphTop, glyphRight, glyphBottom)
        } else {
            stageBounds
        }

        val horizOverflowPx = if (layout.didOverflowWidth) {
            (maxLineWidthPx - layout.size.width.toFloat() * scaleX).coerceAtLeast(0f)
        } else {
            0f
        }
        val vertOverflowPx = if (layout.didOverflowHeight && lineCount > 0) {
            ((layout.getLineBottom(lineCount - 1) - layout.size.height.toFloat()) * scaleY).coerceAtLeast(0f)
        } else {
            0f
        }
        val overflowPx = maxOf(horizOverflowPx, vertOverflowPx)
        val effectiveHasVisualOverflow = hasEllipsis || overflowPx > 1.5f ||
            (layout.didOverflowHeight && layout.layoutInput.maxLines < Int.MAX_VALUE && lineCount >= layout.layoutInput.maxLines)

        val density = layout.layoutInput.density.density.coerceAtLeast(1f)
        val styleFontSize = layout.layoutInput.style.fontSize
        val spanFontSize = layout.layoutInput.text.spanStyles.firstOrNull { it.item.fontSize.isSp }?.item?.fontSize
        val effectiveSpUnit = when {
            styleFontSize.isSp -> styleFontSize
            spanFontSize != null -> spanFontSize
            else -> TextUnit.Unspecified
        }
        val fontSizeSp = if (effectiveSpUnit.isSp) {
            effectiveSpUnit.value
        } else if (lineCount > 0) {
            ((layout.getLineBottom(0) - layout.getLineTop(0)) * scaleY * 0.78f / density).coerceAtLeast(0f)
        } else {
            0f
        }
        val fontSizePx = if (fontSizeSp > 0f) fontSizeSp * density else maxOf(1f, (tightGlyphRect.height / maxOf(1, lineCount)) * 0.78f)
        val isBold = (layout.layoutInput.style.fontWeight?.weight ?: 400) >= 700 ||
            layout.layoutInput.text.spanStyles.any { (it.item.fontWeight?.weight ?: 0) >= 700 }

        val sampledBg = if (pixelBuffer != null) {
            sampleBackgroundColor(pixelBuffer, tightGlyphRect, stageWidth, stageHeight)
        } else {
            fallbackBackground
        }

        val (resolvedFg, resolvedOpaqueFg) = resolveForegroundColors(
            layout = layout,
            tightGlyphRect = tightGlyphRect,
            effectiveAlpha = effectiveAlpha,
            sampledBackground = sampledBg,
            pixelBuffer = pixelBuffer,
        )

        return LintTextNodeCandidate(
            elementId = null,
            kind = "Text",
            label = rawText.trim().replace('\n', ' ').take(40).ifBlank { "Text" },
            text = rawText,
            stageBounds = stageBounds,
            unclippedStageBounds = unclippedStage,
            hasVisualOverflow = effectiveHasVisualOverflow,
            overflowPx = overflowPx,
            hasEllipsis = hasEllipsis,
            lineCount = lineCount,
            maxLineWidthPx = maxLineWidthPx,
            fontSizePx = fontSizePx,
            fontSizeSp = fontSizeSp,
            isBold = isBold,
            effectiveAlpha = effectiveAlpha,
            effectiveForeground = resolvedFg,
            opaqueForeground = resolvedOpaqueFg,
            effectiveBackground = sampledBg,
            isEditable = false,
            isFullBleedSurface = false,
            isTextNode = true,
            semanticsNodeId = node.id,
            ancestorSemanticsIds = ancestors,
        )
    }

    private fun resolveForegroundColors(
        layout: TextLayoutResult,
        tightGlyphRect: Rect,
        effectiveAlpha: Float,
        sampledBackground: Color?,
        pixelBuffer: StagePixelBuffer?,
    ): Pair<Color?, Color?> {
        val styleColor = layout.layoutInput.style.color
        if (styleColor.isSpecified) {
            return styleColor to styleColor.copy(alpha = 1f)
        }
        val spanColors = layout.layoutInput.text.spanStyles
            .map { it.item.color }
            .filter { it.isSpecified }
        if (spanColors.isNotEmpty()) {
            val picked = if (sampledBackground != null) {
                spanColors.minByOrNull { contrastRatio(it.copy(alpha = 1f), sampledBackground.copy(alpha = 1f)) }
                    ?: spanColors.first()
            } else {
                spanColors.first()
            }
            return picked to picked.copy(alpha = 1f)
        }
        if (pixelBuffer != null && sampledBackground != null) {
            val sampled = sampleForegroundColor(pixelBuffer, tightGlyphRect, sampledBackground)
            if (sampled != null) {
                val opaque = if (effectiveAlpha in 0.05f..0.98f) {
                    unblendOver(sampled, sampledBackground, effectiveAlpha)
                } else {
                    sampled
                }
                return sampled to opaque
            }
        }
        return null to null
    }

    /**
     * Samples pixels inside and immediately around [glyphRect] and picks the quantized color mode,
     * which reliably identifies the enclosing card/stage background even for dark code cards on
     * light slides.
     */
    internal fun sampleBackgroundColor(
        buffer: StagePixelBuffer,
        glyphRect: Rect,
        stageWidth: Float,
        stageHeight: Float,
    ): Color? {
        val bw = buffer.width
        val bh = buffer.height
        if (bw <= 0 || bh <= 0) return null

        val sx = bw / stageWidth.coerceAtLeast(1f)
        val sy = bh / stageHeight.coerceAtLeast(1f)
        val left = (glyphRect.left * sx).roundToInt()
        val top = (glyphRect.top * sy).roundToInt()
        val right = (glyphRect.right * sx).roundToInt()
        val bottom = (glyphRect.bottom * sy).roundToInt()
        if (right < 0 || bottom < 0 || left >= bw || top >= bh) return null

        val padX = 4
        val padY = 4
        val minX = (left - padX).coerceIn(0, bw - 1)
        val maxX = (right + padX).coerceIn(0, bw - 1)
        val minY = (top - padY).coerceIn(0, bh - 1)
        val maxY = (bottom + padY).coerceIn(0, bh - 1)

        val buckets = HashMap<Int, ColorAccumulator>(32)
        fun addSample(x: Int, y: Int) {
            val argb = buffer.getArgb(x.coerceIn(0, bw - 1), y.coerceIn(0, bh - 1))
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val key = ((r ushr 3) shl 10) or ((g ushr 3) shl 5) or (b ushr 3)
            val acc = buckets.getOrPut(key) { ColorAccumulator() }
            acc.count++
            acc.sumR += r
            acc.sumG += g
            acc.sumB += b
        }

        val cols = 9
        val rows = 5
        for (iy in 0 until rows) {
            val y = if (rows == 1) minY else minY + (maxY - minY) * iy / (rows - 1)
            for (ix in 0 until cols) {
                val x = if (cols == 1) minX else minX + (maxX - minX) * ix / (cols - 1)
                addSample(x, y)
            }
        }
        // Extra perimeter points just outside the text box to anchor card/stage fills.
        val outerTop = (top - 3).coerceIn(0, bh - 1)
        val outerBottom = (bottom + 3).coerceIn(0, bh - 1)
        for (ix in 0 until 7) {
            val x = minX + (maxX - minX) * ix / 6
            addSample(x, outerTop)
            addSample(x, outerBottom)
        }

        val best = buckets.values.maxByOrNull { it.count } ?: return null
        return Color(
            red = (best.sumR / best.count) / 255f,
            green = (best.sumG / best.count) / 255f,
            blue = (best.sumB / best.count) / 255f,
            alpha = 1f,
        )
    }

    private fun sampleForegroundColor(
        buffer: StagePixelBuffer,
        glyphRect: Rect,
        background: Color,
    ): Color? {
        val bw = buffer.width
        val bh = buffer.height
        val minX = glyphRect.left.roundToInt().coerceIn(0, bw - 1)
        val maxX = glyphRect.right.roundToInt().coerceIn(0, bw - 1)
        val minY = glyphRect.top.roundToInt().coerceIn(0, bh - 1)
        val maxY = glyphRect.bottom.roundToInt().coerceIn(0, bh - 1)
        if (maxX <= minX || maxY <= minY) return null

        val bgR = background.red * 255f
        val bgG = background.green * 255f
        val bgB = background.blue * 255f

        var maxDist = -1f
        var bestColor = background
        val stepX = maxOf(1, (maxX - minX) / 24)
        val stepY = maxOf(1, (maxY - minY) / 14)
        var y = minY
        while (y <= maxY) {
            var x = minX
            while (x <= maxX) {
                val argb = buffer.getArgb(x, y)
                val r = ((argb ushr 16) and 0xFF).toFloat()
                val g = ((argb ushr 8) and 0xFF).toFloat()
                val b = (argb and 0xFF).toFloat()
                val dr = r - bgR
                val dg = g - bgG
                val db = b - bgB
                val dist = sqrt(dr * dr + dg * dg + db * db)
                if (dist > maxDist) {
                    maxDist = dist
                    bestColor = Color(r / 255f, g / 255f, b / 255f, 1f)
                }
                x += stepX
            }
            y += stepY
        }
        return bestColor
    }

    private fun unblendOver(composited: Color, bg: Color, alpha: Float): Color {
        val a = alpha.coerceIn(0.05f, 1f)
        val inv = 1f - a
        return Color(
            red = ((composited.red - bg.red * inv) / a).coerceIn(0f, 1f),
            green = ((composited.green - bg.green * inv) / a).coerceIn(0f, 1f),
            blue = ((composited.blue - bg.blue * inv) / a).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }

    private fun mapRectToStage(rect: Rect, rootBox: Rect, scaleX: Float, scaleY: Float): Rect = Rect(
        left = (rect.left - rootBox.left) * scaleX,
        top = (rect.top - rootBox.top) * scaleY,
        right = (rect.right - rootBox.left) * scaleX,
        bottom = (rect.bottom - rootBox.top) * scaleY,
    )

    private class ColorAccumulator {
        var count: Int = 0
        var sumR: Int = 0
        var sumG: Int = 0
        var sumB: Int = 0
    }
}
