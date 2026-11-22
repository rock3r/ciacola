package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.text.magic.MagicTextDiff
import dev.bnorm.storyboard.text.magic.MorphBy
import dev.bnorm.storyboard.text.magic.TokenMatch
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.document.DeliveryBy
import dev.sebastiano.ciacola.document.TextTokenizer
import dev.sebastiano.ciacola.effects.v2.CurveV2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import org.jetbrains.skia.Rect as SkiaRect

/**
 * Drawable group kind decided by symmetric `piece–match–piece` connected components.
 */
enum class DrawableGroupKind {
    /** Exactly 1 source piece + 1 target piece covering the exact same matched tokens and nothing else. */
    Move,

    /** Source pieces only. */
    Exit,

    /** Target pieces only. */
    Enter,

    /** Multi-piece or mixed matched/unmatched component: source fades out in place, target fades in place. */
    CrossFadeInPlace,
}

/**
 * Pre-measured vector scaling layout for a size-changing [DrawableGroupKind.Move] group:
 * laid out once at `max(fromSp, toSp)` and drawn purely as vectors scaled down in `(0, 1]` on the
 * interpolated baseline so glyphs stay vector-sharp at arbitrary stage or Loupe zoom.
 */
data class SizeMorphAsset(
    val largeSizeSp: Float,
    val fromLargeLayout: TextLayoutResult,
    val fromVectorRuns: List<VectorGlyphRun>?,
    val fromLargePaintedBaselinePx: Float,
    val toLargeLayout: TextLayoutResult?,
    val toVectorRuns: List<VectorGlyphRun>?,
    val toLargePaintedBaselinePx: Float,
    val fromCalibratedX: Float,
    val toCalibratedX: Float,
    val fromCalibratedBaselineY: Float,
    val toCalibratedBaselineY: Float,
    val fromScaleX: Float,
    val toScaleX: Float,
    val fromScaleY: Float,
    val toScaleY: Float,
    val additiveLayerBounds: SkiaRect? = null,
)

/**
 * One owned drawable group inside a [MorphPlanV2]. Every source and target piece belongs to
 * strictly one group, and timing is the union of all member slots.
 */
data class DrawableGroup(
    val id: Int,
    val kind: DrawableGroupKind,
    val sourcePieces: List<AdmittedPiece>,
    val targetPieces: List<AdmittedPiece>,
    val styleChanged: Boolean,
    val sizeChanged: Boolean,
    val startMs: Int,
    val endMs: Int,
    val curve: CurveV2,
    val sizeMorphAsset: SizeMorphAsset? = null,
    val additiveLayerBounds: SkiaRect? = null,
) {
    fun rawProgressAt(elapsedMs: Float): Float {
        val dur = endMs - startMs
        if (dur <= 0) return if (elapsedMs >= startMs) 1f else 0f
        if (elapsedMs <= startMs) return 0f
        if (elapsedMs >= endMs) return 1f
        return ((elapsedMs - startMs) / dur.toFloat()).coerceIn(0f, 1f)
    }

    fun easedProgressAt(elapsedMs: Float): Float {
        val raw = rawProgressAt(elapsedMs)
        if (raw <= 0f) return 0f
        if (raw >= 1f) return 1f
        return curve.sample(raw, (endMs - startMs).toFloat())
    }
}

/**
 * Complete cached morph plan between two endpoints (`from`, `to`).
 *
 * When [wholeParagraphCrossFade] is `true` (because either endpoint was refused by Condition 2 or
 * reduced motion is enabled), rendering cross-fades the two native whole paragraphs unclipped.
 */
data class MorphPlanV2(
    val fromPartition: AdmittedPartition,
    val toPartition: AdmittedPartition,
    val matches: List<TokenMatch>,
    val groups: List<DrawableGroup>,
    val wholeParagraphCrossFade: Boolean,
)

/**
 * One admitted piece paired with its resolved planner timing window (`[startMs, endMs]` and
 * [curve]), joined from [PieceScheduleV2] by UTF-16 range intersection (union of member slots,
 * first member's curve, matching morph slot timing).
 */
data class BuildPieceTiming(
    val piece: AdmittedPiece,
    val startMs: Int,
    val endMs: Int,
    val curve: CurveV2,
) {
    fun rawProgressAt(elapsedMs: Float): Float {
        val dur = endMs - startMs
        if (dur <= 0) return if (elapsedMs >= startMs) 1f else 0f
        if (elapsedMs <= startMs) return 0f
        if (elapsedMs >= endMs) return 1f
        return ((elapsedMs - startMs) / dur.toFloat()).coerceIn(0f, 1f)
    }

    fun easedProgressAt(elapsedMs: Float): Float {
        val raw = rawProgressAt(elapsedMs)
        if (raw <= 0f) return 0f
        if (raw >= 1f) return 1f
        return curve.sample(raw, (endMs - startMs).toFloat())
    }
}

/**
 * Cached piece-level build plan pairing an [AdmittedPartition] with planner-resolved [pieceTimings].
 */
data class BuildPlanV2(
    val partition: AdmittedPartition,
    val pieceTimings: List<BuildPieceTiming>,
    val totalDurationMs: Int,
    val allPiecesPairwiseDisjoint: Boolean = false,
)

/**
 * Builds symmetric `piece–match–piece` connected components and pre-measures large-size
 * layouts for size-changing [DrawableGroupKind.Move] groups.
 */
internal object JointGroupingPlanner {

    fun buildPlan(
        fromPartition: AdmittedPartition,
        toPartition: AdmittedPartition,
        by: MorphBy,
        pruneStopwords: Boolean,
        schedule: PieceScheduleV2,
        defaultMoveCurve: CurveV2,
        measurer: TextMeasurer,
        density: Density,
        fontResolver: DeckFontResolver?,
    ): MorphPlanV2 {
        val matches = MagicTextDiff.plan(
            from = fromPartition.wholeText,
            to = toPartition.wholeText,
            by = by,
            pruneStopwords = pruneStopwords,
        )

        if (!fromPartition.admitted || !toPartition.admitted ||
            (fromPartition.pieces.isEmpty() && toPartition.pieces.isEmpty())
        ) {
            return MorphPlanV2(
                fromPartition = fromPartition,
                toPartition = toPartition,
                matches = matches,
                groups = emptyList(),
                wholeParagraphCrossFade = true,
            )
        }

        val srcPieces = fromPartition.pieces
        val dstPieces = toPartition.pieces
        val srcCount = srcPieces.size
        val totalNodes = srcCount + dstPieces.size
        if (totalNodes == 0) {
            return MorphPlanV2(fromPartition, toPartition, matches, emptyList(), wholeParagraphCrossFade = true)
        }

        val uf = UnionFind(totalNodes)

        // Track which matches touch each piece so we can distinguish clean Move vs mixed CrossFadeInPlace.
        val srcIntersectingMatches = Array(srcCount) { mutableListOf<TokenMatch>() }
        val dstIntersectingMatches = Array(dstPieces.size) { mutableListOf<TokenMatch>() }

        for (match in matches) {
            val before = match.before
            val after = match.after

            val hitSrc = mutableListOf<Int>()
            if (before != null && !before.collapsed) {
                for (si in srcPieces.indices) {
                    if (rangesIntersect(srcPieces[si].start, srcPieces[si].end, before.start, before.end)) {
                        hitSrc += si
                        srcIntersectingMatches[si] += match
                    }
                }
            }

            val hitDst = mutableListOf<Int>()
            if (after != null && !after.collapsed) {
                for (di in dstPieces.indices) {
                    if (rangesIntersect(dstPieces[di].start, dstPieces[di].end, after.start, after.end)) {
                        hitDst += di
                        dstIntersectingMatches[di] += match
                    }
                }
            }

            // Symmetric piece–match–piece union: connect every intersecting source piece with every
            // intersecting target piece (and pieces on the same side spanned by the same match).
            var anchor = -1
            for (si in hitSrc) {
                if (anchor < 0) anchor = si else uf.union(anchor, si)
            }
            for (di in hitDst) {
                val nodeId = srcCount + di
                if (anchor < 0) anchor = nodeId else uf.union(anchor, nodeId)
            }
        }

        // Collect connected components preserving deterministic reading order.
        val components = LinkedHashMap<Int, Pair<MutableList<Int>, MutableList<Int>>>()
        for (si in srcPieces.indices) {
            val root = uf.find(si)
            components.getOrPut(root) { mutableListOf<Int>() to mutableListOf() }.first += si
        }
        for (di in dstPieces.indices) {
            val root = uf.find(srcCount + di)
            components.getOrPut(root) { mutableListOf<Int>() to mutableListOf() }.second += di
        }

        val matchAddresses = MagicTextDiff.fragmentAddresses(
            from = fromPartition.wholeText,
            to = toPartition.wholeText,
            matches = matches,
        )
        val srcAddresses = mutableListOf<Pair<TextRange, String>>()
        val dstAddresses = mutableListOf<Pair<TextRange, String>>()
        for (k in matches.indices) {
            val m = matches[k]
            val addr = matchAddresses[k]
            m.before?.let { srcAddresses += it to addr }
            m.after?.let { dstAddresses += it to addr }
        }

        val groups = ArrayList<DrawableGroup>(components.size)
        var groupId = 0
        for ((_, pair) in components) {
            val (sIndices, dIndices) = pair
            val sList = sIndices.map { srcPieces[it] }
            val dList = dIndices.map { dstPieces[it] }

            val kind = classifyComponent(
                sIndices = sIndices,
                dIndices = dIndices,
                srcPieces = srcPieces,
                dstPieces = dstPieces,
                srcIntersectingMatches = srcIntersectingMatches,
                dstIntersectingMatches = dstIntersectingMatches,
            )

            val memberSlots = collectMemberSlots(
                sList = sList,
                dList = dList,
                srcAddresses = srcAddresses,
                dstAddresses = dstAddresses,
                schedule = schedule,
            )
            val startMs = memberSlots.minOfOrNull { it.startMs } ?: 0
            val endMs = memberSlots.maxOfOrNull { it.endMs } ?: schedule.totalDurationMs.coerceAtLeast(0)
            val curve = memberSlots.firstOrNull()?.curve ?: defaultMoveCurve

            var styleChanged = false
            var sizeChanged = false
            var sizeAsset: SizeMorphAsset? = null

            if (kind == DrawableGroupKind.Move) {
                val sp = sList[0]
                val dp = dList[0]
                val touchingMatches = srcIntersectingMatches[sIndices[0]]
                sizeChanged = abs(sp.dominantFontSizeSp - dp.dominantFontSizeSp) > 0.05f
                val baseColorChanged = fromPartition.effectiveStyle.color != toPartition.effectiveStyle.color
                val baseWeightChanged = (fromPartition.effectiveStyle.fontWeight ?: androidx.compose.ui.text.font.FontWeight.Normal) !=
                    (toPartition.effectiveStyle.fontWeight ?: androidx.compose.ui.text.font.FontWeight.Normal)
                val baseStyleChanged = (fromPartition.effectiveStyle.fontStyle ?: androidx.compose.ui.text.font.FontStyle.Normal) !=
                    (toPartition.effectiveStyle.fontStyle ?: androidx.compose.ui.text.font.FontStyle.Normal)
                val baseDecoChanged = fromPartition.effectiveStyle.textDecoration != toPartition.effectiveStyle.textDecoration
                val baseBgChanged = fromPartition.effectiveStyle.background != toPartition.effectiveStyle.background
                styleChanged = touchingMatches.any { it.styleChanged } ||
                    baseColorChanged || baseWeightChanged || baseStyleChanged || baseDecoChanged || baseBgChanged ||
                    sp.dominantFontWeight != dp.dominantFontWeight ||
                    sp.dominantFontStyle != dp.dominantFontStyle ||
                    sp.dominantFontFamily != dp.dominantFontFamily

                if (sizeChanged) {
                    sizeAsset = buildSizeMorphAsset(
                        fromPiece = sp,
                        toPiece = dp,
                        fromPartition = fromPartition,
                        toPartition = toPartition,
                        styleChanged = styleChanged,
                        measurer = measurer,
                        density = density,
                        fontResolver = fontResolver,
                    )
                }
            }

            val additiveBounds = when {
                kind == DrawableGroupKind.Move && styleChanged && !sizeChanged -> {
                    val sp = sList[0]
                    val dp = dList[0]
                    PieceInkBounds.unionShifted(
                        listOf(
                            Triple(sp.verifiedInkBounds, 0f, -(sp.paintedBaselineY - sp.originY)),
                            Triple(dp.verifiedInkBounds, 0f, -(dp.paintedBaselineY - dp.originY)),
                        ),
                    )
                }
                kind == DrawableGroupKind.CrossFadeInPlace -> {
                    val allRects = ArrayList<Triple<SkiaRect?, Float, Float>>(sList.size + dList.size)
                    for (p in sList) allRects += Triple(p.verifiedInkBounds, p.originX, p.originY)
                    for (p in dList) allRects += Triple(p.verifiedInkBounds, p.originX, p.originY)
                    PieceInkBounds.unionShifted(allRects)
                }
                else -> null
            }

            groups += DrawableGroup(
                id = groupId++,
                kind = kind,
                sourcePieces = sList,
                targetPieces = dList,
                styleChanged = styleChanged,
                sizeChanged = sizeChanged,
                startMs = startMs,
                endMs = endMs,
                curve = curve,
                sizeMorphAsset = sizeAsset,
                additiveLayerBounds = additiveBounds,
            )
        }

        return MorphPlanV2(
            fromPartition = fromPartition,
            toPartition = toPartition,
            matches = matches,
            groups = groups,
            wholeParagraphCrossFade = false,
        )
    }

    private fun classifyComponent(
        sIndices: List<Int>,
        dIndices: List<Int>,
        srcPieces: List<AdmittedPiece>,
        dstPieces: List<AdmittedPiece>,
        srcIntersectingMatches: Array<MutableList<TokenMatch>>,
        dstIntersectingMatches: Array<MutableList<TokenMatch>>,
    ): DrawableGroupKind {
        if (sIndices.isNotEmpty() && dIndices.isEmpty()) return DrawableGroupKind.Exit
        if (sIndices.isEmpty() && dIndices.isNotEmpty()) return DrawableGroupKind.Enter

        if (sIndices.size == 1 && dIndices.size == 1) {
            val si = sIndices[0]
            val di = dIndices[0]
            val sPiece = srcPieces[si]
            val dPiece = dstPieces[di]
            val sMatches = srcIntersectingMatches[si]
            val dMatches = dstIntersectingMatches[di]
            // Must contain ONLY kept matches (both before & after non-null) and identical match sets
            // covering the same tokens with no unmatched extra tokens on either side.
            val allKeptSrc = sMatches.isNotEmpty() && sMatches.all { it.before != null && it.after != null }
            val allKeptDst = dMatches.isNotEmpty() && dMatches.all { it.before != null && it.after != null }
            if (allKeptSrc && allKeptDst && sMatches.size == dMatches.size) {
                val srcText = sPiece.layout.layoutInput.text.text
                val dstText = dPiece.layout.layoutInput.text.text
                if (srcText == dstText) {
                    return DrawableGroupKind.Move
                }
            }
        }
        return DrawableGroupKind.CrossFadeInPlace
    }

    private fun buildSizeMorphAsset(
        fromPiece: AdmittedPiece,
        toPiece: AdmittedPiece,
        fromPartition: AdmittedPartition,
        toPartition: AdmittedPartition,
        styleChanged: Boolean,
        measurer: TextMeasurer,
        density: Density,
        fontResolver: DeckFontResolver?,
    ): SizeMorphAsset {
        val largeSp = maxOf(fromPiece.dominantFontSizeSp, toPiece.dominantFontSizeSp)
        val largePx = largeSp * density.density * density.fontScale

        val fromLargeStyle = fromPartition.effectiveStyle.copy(
            fontSize = largeSp.sp,
            lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
            textAlign = TextAlign.Start,
            textDirection = if (fromPiece.isRtl) TextDirection.Rtl else TextDirection.Ltr,
            lineHeightStyle = LineHeightStyle.Default,
        )
        val fromSub = scaleSpanFontSizesTo(
            fromPartition.wholeText.subSequence(fromPiece.start, fromPiece.end),
            fromPiece.dominantFontSizeSp,
            largeSp,
        )
        val fromLargeLayout = measurer.measure(
            text = fromSub,
            style = fromLargeStyle,
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = Int.MAX_VALUE),
        )
        val fromTypeface = SkiaTypefaceCache.resolve(
            fontFamily = fromPiece.dominantFontFamily,
            weight = fromPiece.dominantFontWeight,
            style = fromPiece.dominantFontStyle,
            fontResolver = fontResolver,
            fontFamilyResolver = fromLargeLayout.layoutInput.fontFamilyResolver,
        )
        val fromLargeBaseline = if (fromTypeface != null) {
            truePaintedBaselinePx(fromTypeface, largePx)
        } else {
            fromLargeLayout.firstBaseline
        }

        val nomFromScale = (fromPiece.dominantFontSizeSp / largeSp).coerceAtLeast(1e-4f)
        val nomToScale = (toPiece.dominantFontSizeSp / largeSp).coerceAtLeast(1e-4f)
        val fromVectorRuns = VectorGlyphOutlineBuilder.buildRuns(
            text = fromSub,
            baseStyle = fromLargeStyle,
            largeLayout = fromLargeLayout,
            isRtl = fromPiece.isRtl,
            density = density,
            fontResolver = fontResolver,
        )
        val fromFracX = fromPiece.originX - floor(fromPiece.originX)
        val fromFracY = fromPiece.originY - floor(fromPiece.originY)
        val fromFracBaseY = fromPiece.paintedBaselineY - floor(fromPiece.paintedBaselineY)
        val toFracX = toPiece.originX - floor(toPiece.originX)
        val toFracY = toPiece.originY - floor(toPiece.originY)
        val toFracBaseY = toPiece.paintedBaselineY - floor(toPiece.paintedBaselineY)

        val fromSmallBox = PieceInkBounds.measureLayoutSubpixelInkBox(fromPiece.layout, fromFracX, fromFracY)
        val toSmallBox = PieceInkBounds.measureLayoutSubpixelInkBox(toPiece.layout, toFracX, toFracY)
        val fromVecAtFromScale = if (fromVectorRuns != null) {
            PieceInkBounds.measureVectorRunsSubpixelInkBox(
                runs = fromVectorRuns,
                layoutWidth = fromLargeLayout.size.width,
                layoutHeight = fromLargeLayout.size.height,
                baselinePx = fromLargeBaseline,
                scale = nomFromScale,
                fracX = fromFracX,
                fracBaselineY = fromFracBaseY,
            )
        } else {
            null
        }

        val (calFromX, calFromY, fitFromSx, fitFromSy) = calibrateEndpointTransformAtScale(
            piece = fromPiece,
            pieceBox = fromSmallBox,
            scaledVectorBox = fromVecAtFromScale,
            nominalScale = nomFromScale,
        )

        if (!styleChanged) {
            val fromVecAtToScale = if (fromVectorRuns != null) {
                PieceInkBounds.measureVectorRunsSubpixelInkBox(
                    runs = fromVectorRuns,
                    layoutWidth = fromLargeLayout.size.width,
                    layoutHeight = fromLargeLayout.size.height,
                    baselinePx = fromLargeBaseline,
                    scale = nomToScale,
                    fracX = toFracX,
                    fracBaselineY = toFracBaseY,
                )
            } else {
                null
            }
            val (calToX, calToY, fitToSx, fitToSy) = calibrateEndpointTransformAtScale(
                piece = toPiece,
                pieceBox = toSmallBox,
                scaledVectorBox = fromVecAtToScale,
                nominalScale = nomToScale,
            )
            return SizeMorphAsset(
                largeSizeSp = largeSp,
                fromLargeLayout = fromLargeLayout,
                fromVectorRuns = fromVectorRuns,
                fromLargePaintedBaselinePx = fromLargeBaseline,
                toLargeLayout = null,
                toVectorRuns = null,
                toLargePaintedBaselinePx = fromLargeBaseline,
                fromCalibratedX = calFromX,
                toCalibratedX = calToX,
                fromCalibratedBaselineY = calFromY,
                toCalibratedBaselineY = calToY,
                fromScaleX = fitFromSx,
                toScaleX = fitToSx,
                fromScaleY = fitFromSy,
                toScaleY = fitToSy,
                additiveLayerBounds = null,
            )
        }

        val toLargeStyle = toPartition.effectiveStyle.copy(
            fontSize = largeSp.sp,
            lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
            textAlign = TextAlign.Start,
            textDirection = if (toPiece.isRtl) TextDirection.Rtl else TextDirection.Ltr,
            lineHeightStyle = LineHeightStyle.Default,
        )
        val toSub = scaleSpanFontSizesTo(
            toPartition.wholeText.subSequence(toPiece.start, toPiece.end),
            toPiece.dominantFontSizeSp,
            largeSp,
        )
        val toLargeLayout = measurer.measure(
            text = toSub,
            style = toLargeStyle,
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = Int.MAX_VALUE),
        )
        val toTypeface = SkiaTypefaceCache.resolve(
            fontFamily = toPiece.dominantFontFamily,
            weight = toPiece.dominantFontWeight,
            style = toPiece.dominantFontStyle,
            fontResolver = fontResolver,
            fontFamilyResolver = toLargeLayout.layoutInput.fontFamilyResolver,
        )
        val toLargeBaseline = if (toTypeface != null) {
            truePaintedBaselinePx(toTypeface, largePx)
        } else {
            toLargeLayout.firstBaseline
        }
        val toVectorRuns = VectorGlyphOutlineBuilder.buildRuns(
            text = toSub,
            baseStyle = toLargeStyle,
            largeLayout = toLargeLayout,
            isRtl = toPiece.isRtl,
            density = density,
            fontResolver = fontResolver,
        )
        val toVecAtToScale = if (toVectorRuns != null) {
            PieceInkBounds.measureVectorRunsSubpixelInkBox(
                runs = toVectorRuns,
                layoutWidth = toLargeLayout.size.width,
                layoutHeight = toLargeLayout.size.height,
                baselinePx = toLargeBaseline,
                scale = nomToScale,
                fracX = toFracX,
                fracBaselineY = toFracBaseY,
            )
        } else {
            null
        }
        val (calToX, calToY, fitToSx, fitToSy) = calibrateEndpointTransformAtScale(
            piece = toPiece,
            pieceBox = toSmallBox,
            scaledVectorBox = toVecAtToScale,
            nominalScale = nomToScale,
        )

        val pad = ceil(largePx * 0.4f).toInt().coerceIn(32, 256)
        val fromBounds = PieceInkBounds.measureSingleLayoutInkBounds(fromLargeLayout, pad)
        val toBounds = PieceInkBounds.measureSingleLayoutInkBounds(toLargeLayout, pad)
        val sizeAdditiveBounds = PieceInkBounds.unionShifted(
            listOf(
                Triple(fromBounds, 0f, -fromLargeBaseline),
                Triple(toBounds, 0f, -toLargeBaseline),
            ),
        )

        return SizeMorphAsset(
            largeSizeSp = largeSp,
            fromLargeLayout = fromLargeLayout,
            fromVectorRuns = fromVectorRuns,
            fromLargePaintedBaselinePx = fromLargeBaseline,
            toLargeLayout = toLargeLayout,
            toVectorRuns = toVectorRuns,
            toLargePaintedBaselinePx = toLargeBaseline,
            fromCalibratedX = calFromX,
            toCalibratedX = calToX,
            fromCalibratedBaselineY = calFromY,
            toCalibratedBaselineY = calToY,
            fromScaleX = fitFromSx,
            toScaleX = fitToSx,
            fromScaleY = fitFromSy,
            toScaleY = fitToSy,
            additiveLayerBounds = sizeAdditiveBounds,
        )
    }

    private data class EndpointTransform(
        val originX: Float,
        val baselineY: Float,
        val scaleX: Float,
        val scaleY: Float,
    )

    private fun calibrateEndpointTransformAtScale(
        piece: AdmittedPiece,
        pieceBox: SubpixelInkBox?,
        scaledVectorBox: SubpixelInkBox?,
        nominalScale: Float,
    ): EndpointTransform {
        if (pieceBox == null || scaledVectorBox == null) {
            return EndpointTransform(
                originX = piece.originX,
                baselineY = piece.paintedBaselineY,
                scaleX = nominalScale,
                scaleY = nominalScale,
            )
        }
        val ratioX = if (scaledVectorBox.width >= 2f && pieceBox.width >= 1.5f) {
            (pieceBox.width / scaledVectorBox.width).coerceIn(0.94f, 1.06f)
        } else {
            1f
        }
        val ratioY = if (scaledVectorBox.height >= 2f && pieceBox.height >= 1.5f) {
            (pieceBox.height / scaledVectorBox.height).coerceIn(0.94f, 1.06f)
        } else {
            1f
        }
        val calX = (piece.originX + pieceBox.midX) - ratioX * scaledVectorBox.midX
        val calY = (piece.originY + pieceBox.midY) - ratioY * scaledVectorBox.midY
        return EndpointTransform(
            originX = calX,
            baselineY = calY,
            scaleX = nominalScale * ratioX,
            scaleY = nominalScale * ratioY,
        )
    }

    private fun scaleSpanFontSizesTo(
        sub: androidx.compose.ui.text.AnnotatedString,
        baseSp: Float,
        targetSp: Float,
    ): androidx.compose.ui.text.AnnotatedString {
        if (sub.spanStyles.none { it.item.fontSize.isSp } || baseSp <= 0f) return sub
        val ratio = targetSp / baseSp
        return androidx.compose.ui.text.buildAnnotatedString {
            append(sub.text)
            for (span in sub.spanStyles) {
                val updated = if (span.item.fontSize.isSp) {
                    span.item.copy(fontSize = (span.item.fontSize.value * ratio).sp)
                } else {
                    span.item
                }
                addStyle(updated, span.start, span.end)
            }
            for (para in sub.paragraphStyles) {
                addStyle(para.item, para.start, para.end)
            }
        }
    }

    /**
     * Joins [partition]'s admitted pieces to planner-resolved [schedule] slots strictly by UTF-16
     * range intersection against `TextTokenizer.tokenizeForDelivery`, taking the union
     * `[minStart..maxEnd]` and the first member slot's curve (matching morph slot timing).
     * Never invents stagger or keyboard timing on the renderer side.
     */
    fun buildBuildPlan(
        partition: AdmittedPartition,
        schedule: PieceScheduleV2,
    ): BuildPlanV2 {
        val pieces = partition.pieces
        if (!partition.admitted || pieces.isEmpty()) {
            return BuildPlanV2(
                partition = partition,
                pieceTimings = emptyList(),
                totalDurationMs = schedule.totalDurationMs.coerceAtLeast(0),
            )
        }
        val deliveryBy = when (partition.requestedGranularity) {
            PartitionGranularity.Character -> DeliveryBy.Character
            PartitionGranularity.Word -> DeliveryBy.Word
            PartitionGranularity.Line, PartitionGranularity.Paragraph -> DeliveryBy.Paragraph
        }
        val tokens = TextTokenizer.tokenizeForDelivery(partition.wholeText.text, deliveryBy)
        val timings = ArrayList<BuildPieceTiming>(pieces.size)
        val fallbackEnd = schedule.totalDurationMs.coerceAtLeast(1)

        for (piece in pieces) {
            var minStart = Int.MAX_VALUE
            var maxEnd = Int.MIN_VALUE
            var firstCurve: CurveV2? = null

            for (tok in tokens) {
                if (rangesIntersect(piece.start, piece.end, tok.start, tok.endExclusive)) {
                    val slot = schedule.slotFor(tok.address) ?: schedule.slotFor(tok.exitAddress)
                    if (slot != null) {
                        if (slot.startMs < minStart) minStart = slot.startMs
                        if (slot.endMs > maxEnd) maxEnd = slot.endMs
                        if (firstCurve == null) firstCurve = slot.curve
                    }
                }
            }

            if (firstCurve != null) {
                timings += BuildPieceTiming(
                    piece = piece,
                    startMs = minStart,
                    endMs = maxEnd.coerceAtLeast(minStart),
                    curve = firstCurve,
                )
            } else {
                timings += BuildPieceTiming(
                    piece = piece,
                    startMs = 0,
                    endMs = fallbackEnd,
                    curve = schedule.defaultCurve,
                )
            }
        }

        return BuildPlanV2(
            partition = partition,
            pieceTimings = timings,
            totalDurationMs = schedule.totalDurationMs.coerceAtLeast(0),
            allPiecesPairwiseDisjoint = PieceInkBounds.areAllPiecesPairwiseDisjoint(pieces),
        )
    }

    private fun collectMemberSlots(
        sList: List<AdmittedPiece>,
        dList: List<AdmittedPiece>,
        srcAddresses: List<Pair<TextRange, String>>,
        dstAddresses: List<Pair<TextRange, String>>,
        schedule: PieceScheduleV2,
    ): List<PieceSlotV2> {
        val found = mutableListOf<PieceSlotV2>()
        for (piece in sList) {
            for ((range, addr) in srcAddresses) {
                if (rangesIntersect(piece.start, piece.end, range.start, range.end)) {
                    schedule.slotFor(addr)?.let { found += it }
                }
            }
        }
        for (piece in dList) {
            for ((range, addr) in dstAddresses) {
                if (rangesIntersect(piece.start, piece.end, range.start, range.end)) {
                    schedule.slotFor(addr)?.let { found += it }
                }
            }
        }
        return found
    }

    private fun rangesIntersect(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean =
        aStart < bEnd && bStart < aEnd

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var cur = x
            while (parent[cur] != r) {
                val next = parent[cur]
                parent[cur] = r
                cur = next
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            when {
                rank[ra] < rank[rb] -> parent[ra] = rb
                rank[ra] > rank[rb] -> parent[rb] = ra
                else -> {
                    parent[rb] = ra
                    rank[ra]++
                }
            }
        }
    }
}
