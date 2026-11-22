package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.bnorm.storyboard.text.magic.MagicTextDiff
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.EffectiveFontFeatureConfig
import dev.sebastiano.ciacola.core.fonts.ParagraphCharacterSafetyCertifier
import dev.sebastiano.ciacola.core.fonts.effectiveFeatureConfigForTextNode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Surface

private const val MAX_CONDITION2_COARSENING_ROUNDS = 3
private const val CONDITION2_CHANNEL_TOLERANCE = 2
private const val MAX_CONDITION2_BITMAP_PIXELS = 16_000_000

/**
 * Computes the 1-line [LineHeightStyle] matching line [line] of a [lineCount]-line paragraph so
 * standalone single-line pieces match SkParagraph's multi-line `Trim` behavior bit-for-bit.
 */
fun lineMatchedLineHeightStyle(
    base: LineHeightStyle?,
    line: Int,
    lineCount: Int,
): LineHeightStyle {
    val effective = base ?: LineHeightStyle.Default
    if (lineCount <= 1) return effective
    val trimsFirstTop = effective.trim == LineHeightStyle.Trim.Both ||
        effective.trim == LineHeightStyle.Trim.FirstLineTop
    val trimsLastBottom = effective.trim == LineHeightStyle.Trim.Both ||
        effective.trim == LineHeightStyle.Trim.LastLineBottom
    val pieceTrim = when {
        line == 0 && trimsFirstTop -> LineHeightStyle.Trim.FirstLineTop
        line == lineCount - 1 && trimsLastBottom -> LineHeightStyle.Trim.LastLineBottom
        else -> LineHeightStyle.Trim.None
    }
    return LineHeightStyle(alignment = effective.alignment, trim = pieceTrim)
}

/**
 * One structurally eligible, visually verified single-line piece of an [AdmittedPartition].
 */
data class AdmittedPiece(
    val index: Int,
    val start: Int,
    val end: Int,
    val line: Int,
    val level: PartitionGranularity,
    val wordGroupId: Int,
    val isFullLine: Boolean,
    val isRtl: Boolean,
    val layout: TextLayoutResult,
    val originX: Float,
    val originY: Float,
    val paintedBaselineY: Float,
    val dominantFontFamily: FontFamily?,
    val dominantFontWeight: FontWeight,
    val dominantFontStyle: FontStyle,
    val dominantFontSizeSp: Float,
    /**
     * Empirically verified ink bounding box in piece-local coordinates (measured once from the
     * opacity-normalized piece during Condition 2 admission + 4 px safety halo), or `null` to use
     * an unbounded `saveLayer(null, paint)`. Guarantees zero clipping on fades.
     */
    val verifiedInkBounds: SkiaRect? = null,
) {
    val boundingBox: Rect = Rect(
        left = originX,
        top = originY,
        right = originX + layout.size.width.toFloat(),
        bottom = originY + layout.size.height.toFloat(),
    )
}

/**
 * Result of running the Two-Condition Partitioner on one text endpoint.
 *
 * When [admitted] is `false`, the endpoint is NOT divided; rendering draws [wholeLayout] natively
 * (unclipped whole-paragraph cross-fade).
 */
data class AdmittedPartition(
    val admitted: Boolean,
    val requestedGranularity: PartitionGranularity,
    val effectiveGranularity: PartitionGranularity,
    val wholeText: AnnotatedString,
    val effectiveStyle: TextStyle,
    val wholeLayout: TextLayoutResult,
    val pieces: List<AdmittedPiece>,
    val coarseningRounds: Int,
    val maxChannelDelta: Int,
    val outerRingInkDetected: Boolean,
    val refusalReason: String? = null,
    val effectiveFeatureConfig: EffectiveFontFeatureConfig = EffectiveFontFeatureConfig.ShaperDefaults,
    val condition1ParagraphRefusalReason: String? = null,
)

/**
 * Implements Two-Condition Admission + Region Coarsening (up to 3 rounds).
 */
internal object TwoConditionPartitioner {

    fun partition(
        styled: StyledTextV2,
        requestedGranularity: PartitionGranularity,
        characterDeliveryNode: Boolean,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): AdmittedPartition {
        val effectiveConfig = effectiveFeatureConfigForTextNode(
            characterDeliveryNode = characterDeliveryNode || requestedGranularity == PartitionGranularity.Character,
        )
        val (prepared, faceAssignment) = styled.prepareWithSharedFaceAssignment(
            fontResolver = config.fontResolver,
            effectiveConfig = effectiveConfig,
        )
        val wholeLayout = measureWholeParagraph(prepared, measurer)

        fun paragraphResult(
            admitted: Boolean,
            rounds: Int = 0,
            maxDelta: Int = 0,
            outerRing: Boolean = false,
            refusal: String? = null,
            c1Refusal: String? = null,
        ): AdmittedPartition = AdmittedPartition(
            admitted = admitted,
            requestedGranularity = requestedGranularity,
            effectiveGranularity = PartitionGranularity.Paragraph,
            wholeText = prepared.text,
            effectiveStyle = prepared.style,
            wholeLayout = wholeLayout,
            pieces = emptyList(),
            coarseningRounds = rounds,
            maxChannelDelta = maxDelta,
            outerRingInkDetected = outerRing,
            refusalReason = refusal,
            effectiveFeatureConfig = effectiveConfig,
            condition1ParagraphRefusalReason = c1Refusal,
        )

        if (prepared.text.isEmpty() || wholeLayout.lineCount == 0 || requestedGranularity == PartitionGranularity.Paragraph) {
            return paragraphResult(admitted = true)
        }
        if (config.forceCondition2Failure) {
            return paragraphResult(
                admitted = false,
                rounds = MAX_CONDITION2_COARSENING_ROUNDS,
                maxDelta = 255,
                refusal = "Forced Condition 2 failure",
            )
        }

        // Condition 1: ONE authoritative character-safety predicate sharing the exact face
        // assignment that produced wholeLayout. Fail closed when fontResolver is absent.
        val certifier: ParagraphCharacterSafetyCertifier? =
            if (requestedGranularity == PartitionGranularity.Character) {
                config.fontResolver?.certifyParagraphCharacterSafety(
                    paragraph = prepared.text,
                    baseStyle = prepared.style,
                    effectiveConfig = effectiveConfig,
                    isLayoutLtrAtOffset = { offset ->
                        val clamped = offset.coerceIn(0, (prepared.text.length - 1).coerceAtLeast(0))
                        wholeLayout.getParagraphDirection(clamped) == ResolvedTextDirection.Ltr &&
                            wholeLayout.getBidiRunDirection(clamped) == ResolvedTextDirection.Ltr
                    },
                    existingAssignment = faceAssignment,
                    limits = config.condition1Limits,
                )
            } else {
                null
            }

        var candidates = proposeStructuralCandidates(
            text = prepared.text,
            style = prepared.style,
            wholeLayout = wholeLayout,
            requestedGranularity = requestedGranularity,
            certifier = certifier,
        )
        val c1Refusal = certifier?.paragraphRefusedReason
            ?: if (requestedGranularity == PartitionGranularity.Character && config.fontResolver == null) {
                "missing-font-resolver"
            } else if (certifier?.budgetExhausted == true) {
                "tier-b-fixed-point-budget-exhausted"
            } else {
                null
            }
        if (candidates.isEmpty()) {
            return paragraphResult(admitted = true, c1Refusal = c1Refusal)
        }

        // Condition 2: opacity-normalized offscreen bitmap verification + up to 3 coarsening rounds.
        val normalized = normalizeOpacityForComparison(prepared)
        val normWholeLayout = measureWholeParagraph(normalized, measurer)

        val maxLineHeight = (0 until normWholeLayout.lineCount)
            .maxOfOrNull { normWholeLayout.getLineBottom(it) - normWholeLayout.getLineTop(it) }
            ?: 32f
        val pad = ceil(2.0 * maxLineHeight.toDouble()).toInt().coerceAtLeast(32)
        val bmpW = ceil(normWholeLayout.size.width.toDouble()).toInt().coerceAtLeast(1) + pad * 2
        val bmpH = ceil(normWholeLayout.size.height.toDouble()).toInt().coerceAtLeast(1) + pad * 2

        if (bmpW.toLong() * bmpH.toLong() > MAX_CONDITION2_BITMAP_PIXELS) {
            return paragraphResult(
                admitted = false,
                maxDelta = 255,
                refusal = "Comparison bitmap exceeds safety cap (${bmpW}x$bmpH)",
                c1Refusal = c1Refusal,
            )
        }

        val wholePixels = renderNormalizedWhole(normWholeLayout, bmpW, bmpH, pad, density, layoutDirection, config)
            ?: return paragraphResult(
                admitted = false,
                maxDelta = 255,
                refusal = "Condition 2 offscreen whole-paragraph pixel readback failed",
                c1Refusal = c1Refusal,
            )
        if (hasInkInOuterRing(wholePixels, bmpW, bmpH)) {
            return paragraphResult(
                admitted = false,
                outerRing = true,
                refusal = "Whole paragraph ink reached outermost 1px border ring beyond 2*lineHeight",
                c1Refusal = c1Refusal,
            )
        }

        var lastDelta = 0
        var lastOuterRing = false

        for (round in 0..MAX_CONDITION2_COARSENING_ROUNDS) {
            val currentGran = computeCandidateGranularity(candidates)
            if (config.forceCondition2FailureForEndpoint?.invoke(styled, currentGran) == true) {
                lastDelta = 255
                if (round == MAX_CONDITION2_COARSENING_ROUNDS) break
                candidates = coarsenAllCandidatesOneLevel(candidates, prepared.text.length) ?: break
                continue
            }

            var normPieces = buildPieces(
                candidates = candidates,
                text = normalized.text,
                style = normalized.style,
                wholeLayout = normWholeLayout,
                measurer = measurer,
                density = density,
                fontResolver = config.fontResolver,
                padPx = pad,
            )
            var originXOverrides: FloatArray? = null
            val piecesPixels = renderNormalizedPieces(normPieces, bmpW, bmpH, pad, density, layoutDirection, config)
                ?: return paragraphResult(
                    admitted = false,
                    rounds = round,
                    maxDelta = 255,
                    refusal = "Condition 2 offscreen pieces pixel readback failed",
                    c1Refusal = c1Refusal,
                )
            var comparison = compareBitmaps(wholePixels, piecesPixels, bmpW, bmpH, pad)
            if (!comparison.outerRingInkDetected && comparison.maxChannelDelta > CONDITION2_CHANNEL_TOLERANCE) {
                val resolved = PieceInkBounds.resolveSubpixelBinEdgeOverrides(
                    normPieces = normPieces,
                    wholeLayout = normWholeLayout,
                    wholePixels = wholePixels,
                    bmpW = bmpW,
                    bmpH = bmpH,
                    pad = pad,
                    diffPoints = comparison.diffPoints,
                    tolerance = CONDITION2_CHANNEL_TOLERANCE,
                    density = density,
                    layoutDirection = layoutDirection,
                )
                if (resolved != null) {
                    originXOverrides = resolved
                    normPieces = normPieces.mapIndexed { idx, p ->
                        val ov = resolved[idx]
                        if (!ov.isNaN()) p.copy(originX = ov) else p
                    }
                    val retryPixels = renderNormalizedPieces(normPieces, bmpW, bmpH, pad, density, layoutDirection, config)
                        ?: return paragraphResult(
                            admitted = false,
                            rounds = round,
                            maxDelta = 255,
                            refusal = "Condition 2 offscreen pieces pixel readback failed",
                            c1Refusal = c1Refusal,
                        )
                    comparison = compareBitmaps(wholePixels, retryPixels, bmpW, bmpH, pad)
                }
            }
            lastDelta = comparison.maxChannelDelta
            lastOuterRing = comparison.outerRingInkDetected

            if (!comparison.outerRingInkDetected && comparison.maxChannelDelta <= CONDITION2_CHANNEL_TOLERANCE) {
                val verifiedBounds = PieceInkBounds.measureNormalizedPieces(normPieces, pad, config)
                val realPieces = buildPieces(
                    candidates = candidates,
                    text = prepared.text,
                    style = prepared.style,
                    wholeLayout = wholeLayout,
                    measurer = measurer,
                    density = density,
                    fontResolver = config.fontResolver,
                    verifiedBounds = verifiedBounds,
                    padPx = pad,
                    originXOverrides = originXOverrides,
                )
                val effectiveGran = computeEffectiveGranularity(realPieces)
                return AdmittedPartition(
                    admitted = true,
                    requestedGranularity = requestedGranularity,
                    effectiveGranularity = effectiveGran,
                    wholeText = prepared.text,
                    effectiveStyle = prepared.style,
                    wholeLayout = wholeLayout,
                    pieces = realPieces,
                    coarseningRounds = round,
                    maxChannelDelta = comparison.maxChannelDelta,
                    outerRingInkDetected = false,
                    effectiveFeatureConfig = effectiveConfig,
                    condition1ParagraphRefusalReason = c1Refusal,
                )
            }

            if (comparison.outerRingInkDetected || round == MAX_CONDITION2_COARSENING_ROUNDS) {
                break
            }

            val coarsened = coarsenFailingCandidates(
                candidates = candidates,
                normPieces = normPieces,
                diffPoints = comparison.diffPoints,
                lineHeightPx = maxLineHeight,
                wholeLayout = normWholeLayout,
                textLength = prepared.text.length,
            )
            if (coarsened == null || coarsened == candidates) {
                break
            }
            candidates = coarsened
        }

        return AdmittedPartition(
            admitted = false,
            requestedGranularity = requestedGranularity,
            effectiveGranularity = PartitionGranularity.Paragraph,
            wholeText = prepared.text,
            effectiveStyle = prepared.style,
            wholeLayout = wholeLayout,
            pieces = emptyList(),
            coarseningRounds = MAX_CONDITION2_COARSENING_ROUNDS,
            maxChannelDelta = lastDelta,
            outerRingInkDetected = lastOuterRing,
            refusalReason = if (lastOuterRing) {
                "Piece ink reached outermost 1px border ring beyond 2*lineHeight"
            } else {
                "Condition 2 visual equivalence exceeded 2/255 after coarsening (maxDelta=$lastDelta)"
            },
            effectiveFeatureConfig = effectiveConfig,
            condition1ParagraphRefusalReason = c1Refusal,
        )
    }

    internal fun prepareStyledText(
        styled: StyledTextV2,
        characterDeliveryNode: Boolean,
        fontResolver: DeckFontResolver?,
    ): StyledTextV2 = styled.prepareWithSharedFaceAssignment(
        fontResolver = fontResolver,
        effectiveConfig = effectiveFeatureConfigForTextNode(characterDeliveryNode),
    ).first

    internal fun measureWholeParagraph(
        styled: StyledTextV2,
        measurer: TextMeasurer,
    ): TextLayoutResult {
        val maxW = styled.maxWidthPx.coerceAtLeast(1)
        val minW = styled.minWidthPx.coerceIn(0, maxW)
        return measurer.measure(
            text = styled.text,
            style = styled.style,
            overflow = styled.overflow,
            softWrap = styled.softWrap,
            maxLines = styled.maxLines,
            constraints = Constraints(minWidth = minW, maxWidth = maxW),
        )
    }

    private data class WordCandidateInfo(
        val start: Int,
        val end: Int,
        val line: Int,
        val wordGroupId: Int,
        val isFullLine: Boolean,
    )

    private fun proposeStructuralCandidates(
        text: AnnotatedString,
        style: TextStyle,
        wholeLayout: TextLayoutResult,
        requestedGranularity: PartitionGranularity,
        certifier: ParagraphCharacterSafetyCertifier?,
    ): List<CandidateSpan> {
        val raw = text.text
        val result = mutableListOf<CandidateSpan>()
        val allWordInfos = mutableListOf<WordCandidateInfo>()
        var nextWordGroupId = 0

        for (line in 0 until wholeLayout.lineCount) {
            val lineStart = wholeLayout.getLineStart(line).coerceIn(0, raw.length)
            var lineEnd = wholeLayout.getLineEnd(line, visibleEnd = false).coerceIn(lineStart, raw.length)
            while (lineEnd > lineStart && (raw[lineEnd - 1] == '\n' || raw[lineEnd - 1] == '\r')) {
                lineEnd--
            }
            // Trim trailing plain spaces only if they carry no visible background/decoration.
            while (lineEnd > lineStart && raw[lineEnd - 1].isWhitespace() && !hasSpaceVisibleDecoration(text, style, lineEnd - 1)) {
                lineEnd--
            }
            if (lineStart >= lineEnd) continue

            if (requestedGranularity == PartitionGranularity.Line) {
                result += CandidateSpan(
                    start = lineStart,
                    end = lineEnd,
                    line = line,
                    level = PartitionGranularity.Line,
                    wordGroupId = nextWordGroupId++,
                    isFullLine = true,
                )
                continue
            }

            // Split line into word runs separated by plain undecorated whitespace.
            // Spaces carrying Underline, LineThrough, or non-transparent background stay attached.
            var cursor = lineStart
            while (cursor < lineEnd) {
                while (cursor < lineEnd && raw[cursor].isWhitespace() && !hasSpaceVisibleDecoration(text, style, cursor)) {
                    cursor++
                }
                if (cursor >= lineEnd) break
                val runStart = cursor
                while (cursor < lineEnd) {
                    val ch = raw[cursor]
                    if (ch.isWhitespace() && !hasSpaceVisibleDecoration(text, style, cursor)) {
                        break
                    }
                    cursor++
                }
                if (cursor > runStart) {
                    allWordInfos += WordCandidateInfo(
                        start = runStart,
                        end = cursor,
                        line = line,
                        wordGroupId = nextWordGroupId++,
                        isFullLine = runStart == lineStart && cursor == lineEnd,
                    )
                }
            }
        }

        if (requestedGranularity == PartitionGranularity.Line) return result

        // Batch-certify ALL words of the paragraph together via the single safety certifier so
        // if any word's W fixed-point exhausts its budget, NO word of the paragraph is certified.
        val wordSafety: BooleanArray? = if (requestedGranularity == PartitionGranularity.Character && certifier != null) {
            certifier.certifyHalfOpenWordRanges(allWordInfos.map { it.start to it.end })
        } else {
            null
        }

        for (i in allWordInfos.indices) {
            val info = allWordInfos[i]
            val canSplitChars = requestedGranularity == PartitionGranularity.Character && wordSafety != null && wordSafety[i]
            if (canSplitChars) {
                val wordText = raw.substring(info.start, info.end)
                val clusters = MagicTextDiff.splitGraphemeClusters(wordText)
                if (clusters.size > 1) {
                    for (cluster in clusters) {
                        result += CandidateSpan(
                            start = info.start + cluster.start,
                            end = info.start + cluster.end,
                            line = info.line,
                            level = PartitionGranularity.Character,
                            wordGroupId = info.wordGroupId,
                            isFullLine = false,
                        )
                    }
                } else {
                    result += CandidateSpan(
                        start = info.start,
                        end = info.end,
                        line = info.line,
                        level = PartitionGranularity.Character,
                        wordGroupId = info.wordGroupId,
                        isFullLine = info.isFullLine,
                    )
                }
            } else {
                result += CandidateSpan(
                    start = info.start,
                    end = info.end,
                    line = info.line,
                    level = PartitionGranularity.Word,
                    wordGroupId = info.wordGroupId,
                    isFullLine = info.isFullLine,
                )
            }
        }
        return result
    }

    private fun hasSpaceVisibleDecoration(
        text: AnnotatedString,
        baseStyle: TextStyle,
        offset: Int,
    ): Boolean {
        var decoration = baseStyle.textDecoration
        var bg = baseStyle.background
        for (span in text.spanStyles) {
            if (offset in span.start until span.end) {
                span.item.textDecoration?.let { decoration = it }
                if (span.item.background != Color.Unspecified) {
                    bg = span.item.background
                }
            }
        }
        val hasLineDeco = decoration != null && (
            decoration!!.contains(TextDecoration.Underline) ||
                decoration!!.contains(TextDecoration.LineThrough)
            )
        val hasBg = bg != Color.Unspecified && bg.alpha > 0f
        return hasLineDeco || hasBg
    }

    private fun buildPieces(
        candidates: List<CandidateSpan>,
        text: AnnotatedString,
        style: TextStyle,
        wholeLayout: TextLayoutResult,
        measurer: TextMeasurer,
        density: Density,
        fontResolver: DeckFontResolver?,
        verifiedBounds: List<SkiaRect?>? = null,
        padPx: Int = 0,
        originXOverrides: FloatArray? = null,
    ): List<AdmittedPiece> {
        val trimsFirstTop = (style.lineHeightStyle ?: LineHeightStyle.Default).trim.let {
            it == LineHeightStyle.Trim.Both || it == LineHeightStyle.Trim.FirstLineTop
        }
        val lineHeightPx = style.lineHeight.takeIf { it.isSp }?.let { it.value * density.density * density.fontScale }

        return candidates.mapIndexed { idx, cand ->
            val sub = text.subSequence(cand.start, cand.end)
            val firstDir = wholeLayout.getBidiRunDirection(cand.start)
            var mixedBidi = false
            for (i in cand.start + 1 until cand.end) {
                if (wholeLayout.getBidiRunDirection(i) != firstDir) {
                    mixedBidi = true
                    break
                }
            }
            val isRtl = !mixedBidi && firstDir == ResolvedTextDirection.Rtl
            val pieceTextDirection = when {
                mixedBidi -> style.textDirection
                isRtl -> TextDirection.Rtl
                else -> TextDirection.Ltr
            }
            val pieceStyle = style.copy(
                textAlign = TextAlign.Start,
                textDirection = pieceTextDirection,
                lineHeightStyle = lineMatchedLineHeightStyle(style.lineHeightStyle, cand.line, wholeLayout.lineCount),
            )
            val pieceLayout = measurer.measure(
                text = sub,
                style = pieceStyle,
                overflow = TextOverflow.Visible,
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(maxWidth = Int.MAX_VALUE),
            )

            val baseOriginX = when {
                cand.isFullLine && mixedBidi -> wholeLayout.getLineLeft(cand.line) - pieceLayout.getLineLeft(0)
                isRtl -> wholeLayout.getHorizontalPosition(cand.start, usePrimaryDirection = true) - pieceLayout.getLineRight(0)
                else -> wholeLayout.getHorizontalPosition(cand.start, usePrimaryDirection = true)
            }
            val overrideOx = originXOverrides?.getOrNull(idx)?.takeIf { !it.isNaN() }
            val originX = overrideOx ?: PieceInkBounds.refineFloat32UlpOriginX(
                baseOriginX = baseOriginX,
                wholeLayout = wholeLayout,
                pieceLayout = pieceLayout,
                candStart = cand.start,
                candEnd = cand.end,
                isMixedBidi = mixedBidi,
                padPx = padPx,
            )
            val wholeBaseline = wholeLayout.getLineBaseline(cand.line)
            val originY = wholeBaseline - pieceLayout.firstBaseline

            val dom = dominantRunStyle(text, style, cand.start, cand.end)
            val domSizePx = dom.fontSizeSp * density.density * density.fontScale
            val typeface = SkiaTypefaceCache.resolve(
                fontFamily = dom.fontFamily,
                weight = dom.fontWeight,
                style = dom.fontStyle,
                fontResolver = fontResolver,
            )
            val paintedBaselineY = if (typeface != null) {
                wholeLinePaintedBaselinePx(
                    wholeLineBaseline = wholeBaseline,
                    pieceFirstBaseline = pieceLayout.firstBaseline,
                    line = cand.line,
                    typeface = typeface,
                    fontSizePx = domSizePx,
                    lineHeightPx = lineHeightPx,
                    trimsFirstTop = trimsFirstTop,
                )
            } else {
                wholeBaseline
            }

            AdmittedPiece(
                index = idx,
                start = cand.start,
                end = cand.end,
                line = cand.line,
                level = cand.level,
                wordGroupId = cand.wordGroupId,
                isFullLine = cand.isFullLine,
                isRtl = isRtl,
                layout = pieceLayout,
                originX = originX,
                originY = originY,
                paintedBaselineY = paintedBaselineY,
                dominantFontFamily = dom.fontFamily,
                dominantFontWeight = dom.fontWeight,
                dominantFontStyle = dom.fontStyle,
                dominantFontSizeSp = dom.fontSizeSp,
                verifiedInkBounds = verifiedBounds?.getOrNull(idx),
            )
        }
    }

    private data class DominantStyle(
        val fontFamily: FontFamily?,
        val fontWeight: FontWeight,
        val fontStyle: FontStyle,
        val fontSizeSp: Float,
    )

    private fun dominantRunStyle(
        text: AnnotatedString,
        baseStyle: TextStyle,
        start: Int,
        end: Int,
    ): DominantStyle {
        var family = baseStyle.fontFamily
        var weight = baseStyle.fontWeight ?: FontWeight.Normal
        var fontStyle = baseStyle.fontStyle ?: FontStyle.Normal
        var sizeSp = if (baseStyle.fontSize.isSp) baseStyle.fontSize.value else 16f

        for (span in text.spanStyles) {
            if (span.start <= start && span.end >= end) {
                span.item.fontFamily?.let { family = it }
                span.item.fontWeight?.let { weight = it }
                span.item.fontStyle?.let { fontStyle = it }
                if (span.item.fontSize.isSp) sizeSp = span.item.fontSize.value
            }
        }
        return DominantStyle(family, weight, fontStyle, sizeSp)
    }

    private fun normalizeOpacityForComparison(styled: StyledTextV2): StyledTextV2 {
        val colorMap = LinkedHashMap<Color, Color>()
        var nextPaletteIdx = 0

        fun normalizeColor(c: Color, treatTransparentAsKeep: Boolean): Color {
            if (c == Color.Unspecified) return c
            if (treatTransparentAsKeep && c.alpha <= 0f) return Color.Transparent
            colorMap[c]?.let { return it }
            // Deterministic high-contrast opaque palette keeping distinct original colors distinct.
            val candidate = if (c.alpha >= 0.999f && c !in colorMap.values) {
                c
            } else {
                var picked: Color
                do {
                    picked = OPAQUE_NORMALIZATION_PALETTE[nextPaletteIdx % OPAQUE_NORMALIZATION_PALETTE.size]
                    nextPaletteIdx++
                } while (picked in colorMap.values && nextPaletteIdx < OPAQUE_NORMALIZATION_PALETTE.size * 2)
                picked
            }
            colorMap[c] = candidate
            return candidate
        }

        val baseFg = if (styled.style.color == Color.Unspecified) Color.White else normalizeColor(styled.style.color, false)
        val baseBg = normalizeColor(styled.style.background, true)
        val baseShadow = styled.style.shadow?.let { sh ->
            sh.copy(color = normalizeColor(sh.color, false))
        }
        val normStyle = styled.style.copy(
            color = baseFg,
            background = baseBg,
            shadow = baseShadow,
        )

        val normText = buildAnnotatedString {
            append(styled.text.text)
            for (span in styled.text.spanStyles) {
                val item = span.item
                val normSpan = item.copy(
                    color = if (item.color == Color.Unspecified) Color.Unspecified else normalizeColor(item.color, false),
                    background = normalizeColor(item.background, true),
                    shadow = item.shadow?.let { sh -> sh.copy(color = normalizeColor(sh.color, false)) },
                )
                addStyle(normSpan, span.start, span.end)
            }
            for (para in styled.text.paragraphStyles) {
                addStyle(para.item, para.start, para.end)
            }
        }
        return styled.copy(text = normText, style = normStyle)
    }

    private fun renderNormalizedWhole(
        layout: TextLayoutResult,
        bmpW: Int,
        bmpH: Int,
        pad: Int,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): ByteArray? {
        val surface = Surface.makeRasterN32Premul(bmpW, bmpH)
        try {
            surface.canvas.clear(0)
            val drawScope = CanvasDrawScope()
            drawScope.draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = surface.canvas.asComposeCanvas(),
                size = androidx.compose.ui.geometry.Size(bmpW.toFloat(), bmpH.toFloat()),
            ) {
                withTransform({ translate(pad.toFloat(), pad.toFloat()) }) {
                    drawText(layout)
                }
            }
            return readSurfaceRgba(surface, bmpW, bmpH, config)
        } finally {
            surface.close()
        }
    }

    private fun renderNormalizedPieces(
        pieces: List<AdmittedPiece>,
        bmpW: Int,
        bmpH: Int,
        pad: Int,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): ByteArray? {
        val surface = Surface.makeRasterN32Premul(bmpW, bmpH)
        try {
            surface.canvas.clear(0)
            val drawScope = CanvasDrawScope()
            drawScope.draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = surface.canvas.asComposeCanvas(),
                size = androidx.compose.ui.geometry.Size(bmpW.toFloat(), bmpH.toFloat()),
            ) {
                for (piece in pieces) {
                    withTransform({
                        translate(pad.toFloat() + piece.originX, pad.toFloat() + piece.originY)
                    }) {
                        drawText(piece.layout)
                    }
                }
            }
            return readSurfaceRgba(surface, bmpW, bmpH, config)
        } finally {
            surface.close()
        }
    }

    private fun readSurfaceRgba(surface: Surface, w: Int, h: Int, config: MagicTextV2Config): ByteArray? {
        config.condition2ReadbackOverrideForTest?.let { return it.invoke() }
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bmp = org.jetbrains.skia.Bitmap()
        return try {
            if (!bmp.allocPixels(info)) return null
            if (!surface.readPixels(bmp, 0, 0)) return null
            val raw = bmp.readPixels(info, w * 4, 0, 0)
            if (raw == null || raw.size != w * h * 4) null else raw
        } catch (_: Throwable) {
            null
        } finally {
            bmp.close()
        }
    }

    private fun hasInkInOuterRing(pixels: ByteArray, w: Int, h: Int): Boolean {
        val rowBytes = w * 4
        // Top and bottom rows
        for (x in 0 until w) {
            if ((pixels[x * 4 + 3].toInt() and 0xFF) != 0) return true
            if ((pixels[(h - 1) * rowBytes + x * 4 + 3].toInt() and 0xFF) != 0) return true
        }
        // Left and right columns
        for (y in 1 until h - 1) {
            if ((pixels[y * rowBytes + 3].toInt() and 0xFF) != 0) return true
            if ((pixels[y * rowBytes + (w - 1) * 4 + 3].toInt() and 0xFF) != 0) return true
        }
        return false
    }

    private data class BitmapComparison(
        val maxChannelDelta: Int,
        val outerRingInkDetected: Boolean,
        val diffPoints: List<Pair<Float, Float>>,
    )

    private fun compareBitmaps(
        whole: ByteArray,
        pieces: ByteArray,
        w: Int,
        h: Int,
        pad: Int,
    ): BitmapComparison {
        var maxDelta = 0
        var outerRingInk = false
        val diffPoints = mutableListOf<Pair<Float, Float>>()

        for (y in 0 until h) {
            val isOuterY = y == 0 || y == h - 1
            val rowOffset = y * w * 4
            for (x in 0 until w) {
                val base = rowOffset + x * 4
                val pa = pieces[base + 3].toInt() and 0xFF
                if ((isOuterY || x == 0 || x == w - 1) && pa != 0) {
                    outerRingInk = true
                }
                val d0 = abs((whole[base].toInt() and 0xFF) - (pieces[base].toInt() and 0xFF))
                val d1 = abs((whole[base + 1].toInt() and 0xFF) - (pieces[base + 1].toInt() and 0xFF))
                val d2 = abs((whole[base + 2].toInt() and 0xFF) - (pieces[base + 2].toInt() and 0xFF))
                val d3 = abs((whole[base + 3].toInt() and 0xFF) - pa)
                val pixDelta = max(max(d0, d1), max(d2, d3))
                if (pixDelta > maxDelta) maxDelta = pixDelta
                if (pixDelta > CONDITION2_CHANNEL_TOLERANCE && diffPoints.size < 2048) {
                    diffPoints += (x - pad).toFloat() to (y - pad).toFloat()
                }
            }
        }
        return BitmapComparison(maxDelta, outerRingInk, diffPoints)
    }

    private fun coarsenFailingCandidates(
        candidates: List<CandidateSpan>,
        normPieces: List<AdmittedPiece>,
        diffPoints: List<Pair<Float, Float>>,
        lineHeightPx: Float,
        wholeLayout: TextLayoutResult,
        textLength: Int,
    ): List<CandidateSpan>? {
        val margin = max(lineHeightPx * 0.45f, 12f)
        val failingPieceIndices = hashSetOf<Int>()
        val failingLines = hashSetOf<Int>()

        for ((dx, dy) in diffPoints) {
            var matched = false
            for (i in normPieces.indices) {
                val box = normPieces[i].boundingBox
                if (dx >= box.left - margin && dx <= box.right + margin &&
                    dy >= box.top - margin && dy <= box.bottom + margin
                ) {
                    failingPieceIndices += i
                    matched = true
                }
            }
            if (!matched) {
                // Find the visual line closest to dy (e.g. gap between words on a line).
                var bestLine = 0
                var bestDist = Float.POSITIVE_INFINITY
                for (l in 0 until wholeLayout.lineCount) {
                    val midY = (wholeLayout.getLineTop(l) + wholeLayout.getLineBottom(l)) * 0.5f
                    val d = abs(dy - midY)
                    if (d < bestDist) {
                        bestDist = d
                        bestLine = l
                    }
                }
                failingLines += bestLine
            }
        }

        val wordsToMerge = hashSetOf<Int>()
        for (idx in failingPieceIndices) {
            val cand = candidates[idx]
            when (cand.level) {
                PartitionGranularity.Character -> wordsToMerge += cand.wordGroupId
                PartitionGranularity.Word -> failingLines += cand.line
                PartitionGranularity.Line, PartitionGranularity.Paragraph -> return null
            }
        }

        val next = mutableListOf<CandidateSpan>()
        val mergedWordsDone = hashSetOf<Int>()
        val mergedLinesDone = hashSetOf<Int>()

        for (cand in candidates) {
            if (cand.line in failingLines) {
                if (mergedLinesDone.add(cand.line)) {
                    val lineCands = candidates.filter { it.line == cand.line }
                    val minStart = lineCands.minOf { it.start }
                    val maxEnd = lineCands.maxOf { it.end }
                    val isSingleLineAlready = lineCands.size == 1 && lineCands[0].level == PartitionGranularity.Line
                    if (isSingleLineAlready) return null
                    next += CandidateSpan(
                        start = minStart.coerceIn(0, textLength),
                        end = maxEnd.coerceIn(minStart, textLength),
                        line = cand.line,
                        level = PartitionGranularity.Line,
                        wordGroupId = lineCands.first().wordGroupId,
                        isFullLine = true,
                    )
                }
            } else if (cand.wordGroupId in wordsToMerge) {
                if (mergedWordsDone.add(cand.wordGroupId)) {
                    val wordCands = candidates.filter { it.wordGroupId == cand.wordGroupId }
                    val minStart = wordCands.minOf { it.start }
                    val maxEnd = wordCands.maxOf { it.end }
                    val isSingleWordAlready = wordCands.size == 1 && wordCands[0].level == PartitionGranularity.Word
                    if (isSingleWordAlready) {
                        // Promote that line if word was already unsplit.
                        failingLines += cand.line
                        return coarsenWholeLine(candidates, cand.line, textLength)
                    }
                    next += CandidateSpan(
                        start = minStart,
                        end = maxEnd,
                        line = cand.line,
                        level = PartitionGranularity.Word,
                        wordGroupId = cand.wordGroupId,
                        isFullLine = false,
                    )
                }
            } else {
                next += cand
            }
        }
        return next
    }

    private fun coarsenWholeLine(
        candidates: List<CandidateSpan>,
        line: Int,
        textLength: Int,
    ): List<CandidateSpan>? {
        val next = mutableListOf<CandidateSpan>()
        var addedLine = false
        for (cand in candidates) {
            if (cand.line == line) {
                if (!addedLine) {
                    val lineCands = candidates.filter { it.line == line }
                    if (lineCands.size == 1 && lineCands[0].level == PartitionGranularity.Line) return null
                    next += CandidateSpan(
                        start = lineCands.minOf { it.start }.coerceIn(0, textLength),
                        end = lineCands.maxOf { it.end }.coerceIn(0, textLength),
                        line = line,
                        level = PartitionGranularity.Line,
                        wordGroupId = lineCands.first().wordGroupId,
                        isFullLine = true,
                    )
                    addedLine = true
                }
            } else {
                next += cand
            }
        }
        return next
    }
}
