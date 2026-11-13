package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.theme.contrastRatio
import dev.sebastiano.ciacola.theme.relativeLuminance
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure, deterministic rule evaluator for slide beats in 1920x1080 stage coordinates.
 */
object DeckLintRules {

    /**
     * Converts [StageNodeSnapshot]s and optional semantics-derived text candidates into a
     * deduplicated [LintSlideInput] ready for [evaluateSlide].
     */
    fun buildSlideInput(
        sceneIndex: Int,
        beatIndex: Int,
        sceneTitle: String = "",
        stageSnapshots: List<StageNodeSnapshot>,
        semanticsTextNodes: List<LintTextNodeCandidate> = emptyList(),
        stageWidth: Float = STAGE_WIDTH_PX,
        stageHeight: Float = STAGE_HEIGHT_PX,
    ): LintSlideInput {
        val surfaces = mutableListOf<LintSurfaceCandidate>()
        val textSnapshots = mutableListOf<StageNodeSnapshot>()

        for (snapshot in stageSnapshots) {
            val isFullBleed = isFullBleedBounds(snapshot.stageBounds, stageWidth, stageHeight)
            val isSurfaceKind = snapshot.kind.equals("Surface", ignoreCase = true) ||
                snapshot.kind.equals("Frame", ignoreCase = true) ||
                snapshot.kind.equals("Backdrop", ignoreCase = true) ||
                snapshot.kind.equals("Canvas", ignoreCase = true) ||
                snapshot.kind.equals("Card", ignoreCase = true) ||
                snapshot.kind.equals("WarpCodeCard", ignoreCase = true)

            if (snapshot.resolvedText.isBlank() || isSurfaceKind) {
                surfaces += LintSurfaceCandidate(
                    elementId = snapshot.elementId,
                    kind = snapshot.kind,
                    label = snapshot.label.ifBlank { snapshot.kind },
                    stageBounds = snapshot.stageBounds,
                    effectiveBackground = snapshot.effectiveBackground,
                    effectiveAlpha = 1f,
                    isInteractive = !isSurfaceKind && snapshot.isEditable,
                    isEditable = snapshot.isEditable,
                    isFullBleedSurface = isFullBleed,
                )
            }
            if (snapshot.resolvedText.isNotBlank() && !isSurfaceKind) {
                textSnapshots += snapshot
            }
        }

        val mergedText = mergeTextCandidates(
            stageSnapshots = textSnapshots,
            semanticsNodes = semanticsTextNodes,
            surfaces = surfaces,
            stageWidth = stageWidth,
            stageHeight = stageHeight,
        )

        return LintSlideInput(
            sceneIndex = sceneIndex,
            beatIndex = beatIndex,
            sceneTitle = sceneTitle,
            stageWidth = stageWidth,
            stageHeight = stageHeight,
            textNodes = mergedText,
            surfaces = surfaces,
        )
    }

    /**
     * Evaluates all 5 lint rules against [input] and returns deduplicated issues sorted by
     * severity (errors first) and top-to-bottom stage position.
     */
    fun evaluateSlide(input: LintSlideInput): List<DeckLintIssue> {
        val issues = mutableListOf<DeckLintIssue>()
        val seenIds = HashSet<String>()

        fun addUnique(issue: DeckLintIssue) {
            if (seenIds.add(issue.id)) {
                issues += issue
            }
        }

        for ((idx, node) in input.textNodes.withIndex()) {
            val visibleAlpha = node.effectiveAlpha
            if (visibleAlpha <= INVISIBLE_ALPHA_THRESHOLD) continue

            val resolvedBg = node.effectiveBackground
                ?: findEnclosingSurfaceBackground(node.stageBounds, input.surfaces)

            evaluateOverflowRule(input, idx, node)?.let(::addUnique)
            evaluateBoundsRule(input, idx, node.elementId, node.label, node.text, node.unclippedStageBounds, node.isFullBleedSurface, node.isEditable)
                ?.let(::addUnique)
            evaluateContrastRule(input, idx, node, resolvedBg)?.let(::addUnique)
            evaluateTinyTextRule(input, idx, node)?.let(::addUnique)
        }

        for ((idx, surface) in input.surfaces.withIndex()) {
            if (surface.effectiveAlpha <= INVISIBLE_ALPHA_THRESHOLD) continue
            if (surface.isFullBleedSurface) continue
            if (!surface.isInteractive && !surface.isEditable) continue
            evaluateBoundsRule(
                input = input,
                idx = idx + input.textNodes.size,
                elementId = surface.elementId,
                label = surface.label,
                snippet = "",
                bounds = surface.stageBounds,
                isFullBleed = surface.isFullBleedSurface,
                isEditable = surface.isEditable,
            )?.let(::addUnique)
        }

        evaluateOverlapRule(input, ::addUnique)

        return issues.sortedWith(
            compareBy<DeckLintIssue> { it.severity != LintSeverity.Error }
                .thenBy { it.stageBounds.top }
                .thenBy { it.stageBounds.left }
                .thenBy { it.ruleId.ordinal }
                .thenBy { it.id },
        )
    }

    private fun evaluateOverflowRule(
        input: LintSlideInput,
        idx: Int,
        node: LintTextNodeCandidate,
    ): DeckLintIssue? {
        val isNarrowWrap = node.lineCount >= NARROW_WRAP_MIN_LINES &&
            node.fontSizePx > 0f &&
            node.maxLineWidthPx > 0f &&
            node.maxLineWidthPx < NARROW_WRAP_WIDTH_MULTIPLIER * node.fontSizePx &&
            node.text.count { !it.isWhitespace() } >= NARROW_WRAP_MIN_LINES

        if (!node.hasVisualOverflow && !node.hasEllipsis && !isNarrowWrap) return null

        val detail = when {
            isNarrowWrap ->
                "Narrow wrap across ${node.lineCount} lines (max width ${node.maxLineWidthPx.roundToInt()} px < ${(NARROW_WRAP_WIDTH_MULTIPLIER * node.fontSizePx).roundToInt()} px)"
            node.hasEllipsis ->
                "Slide text truncated with ellipsis (\"${abbreviate(node.text, 36)}\")"
            node.overflowPx > 0.5f ->
                "Text overflows box by ${node.overflowPx.roundToInt()} px"
            else ->
                "Text visually overflows its layout bounds"
        }

        val fixes = buildList {
            if (node.elementId != null && node.isEditable) {
                if (node.fontSizeSp > TINY_TEXT_MIN_SP) {
                    val shrunkSp = (node.fontSizeSp - 2f).coerceAtLeast(TINY_TEXT_MIN_SP)
                    add(
                        LintQuickFix(
                            label = "Shrink Font -2sp",
                            tooltip = "Reduce font size to ${shrunkSp.roundToInt()} sp on ${node.label}",
                            patch = ElementPatch(elementId = node.elementId, fontSizeSp = shrunkSp),
                        ),
                    )
                }
                add(editElementFix(node.elementId, node.label))
            }
        }

        return DeckLintIssue(
            id = issueKey(LintRuleId.TEXT_CLIPPED_OR_OVERFLOW, input.sceneIndex, input.beatIndex, node.elementId, node.label, idx),
            ruleId = LintRuleId.TEXT_CLIPPED_OR_OVERFLOW,
            severity = LintSeverity.Error,
            sceneIndex = input.sceneIndex,
            beatIndex = input.beatIndex,
            sceneTitle = input.sceneTitle,
            elementId = node.elementId,
            elementLabel = node.label,
            textSnippet = abbreviate(node.text, 64),
            stageBounds = node.stageBounds,
            title = if (isNarrowWrap) "Pathological narrow text wrap" else "Text clipped or overflowing",
            detail = detail,
            quickFixes = fixes,
        )
    }

    private fun evaluateBoundsRule(
        input: LintSlideInput,
        idx: Int,
        elementId: String?,
        label: String,
        snippet: String,
        bounds: Rect,
        isFullBleed: Boolean,
        isEditable: Boolean,
    ): DeckLintIssue? {
        if (isFullBleed || bounds.width <= 1f || bounds.height <= 1f) return null
        if (isFullBleedBounds(bounds, input.stageWidth, input.stageHeight)) return null

        val outLeft = (-bounds.left).coerceAtLeast(0f)
        val outTop = (-bounds.top).coerceAtLeast(0f)
        val outRight = (bounds.right - input.stageWidth).coerceAtLeast(0f)
        val outBottom = (bounds.bottom - input.stageHeight).coerceAtLeast(0f)
        val maxOut = maxOf(outLeft, outTop, outRight, outBottom)

        if (maxOut > 0.5f) {
            val edges = buildList {
                if (outLeft > 0.5f) add("left ${outLeft.roundToInt()} px")
                if (outTop > 0.5f) add("top ${outTop.roundToInt()} px")
                if (outRight > 0.5f) add("right ${outRight.roundToInt()} px")
                if (outBottom > 0.5f) add("bottom ${outBottom.roundToInt()} px")
            }.joinToString(", ")

            val fixes = if (elementId != null && isEditable) listOf(editElementFix(elementId, label)) else emptyList()
            return DeckLintIssue(
                id = issueKey(LintRuleId.STAGE_OUT_OF_BOUNDS, input.sceneIndex, input.beatIndex, elementId, label, idx),
                ruleId = LintRuleId.STAGE_OUT_OF_BOUNDS,
                severity = LintSeverity.Error,
                sceneIndex = input.sceneIndex,
                beatIndex = input.beatIndex,
                sceneTitle = input.sceneTitle,
                elementId = elementId,
                elementLabel = label,
                textSnippet = abbreviate(snippet, 64),
                stageBounds = bounds,
                title = "Element extends outside 1920×1080 stage",
                detail = "Bounds [${bounds.left.roundToInt()}, ${bounds.top.roundToInt()} .. ${bounds.right.roundToInt()}, ${bounds.bottom.roundToInt()}] exceed stage ($edges)",
                quickFixes = fixes,
            )
        }

        val margin = SAFE_AREA_MARGIN_PX
        val nearLeft = bounds.left < margin
        val nearTop = bounds.top < margin
        val nearRight = bounds.right > input.stageWidth - margin
        val nearBottom = bounds.bottom > input.stageHeight - margin
        if (nearLeft || nearTop || nearRight || nearBottom) {
            val minClearance = minOf(
                bounds.left,
                bounds.top,
                input.stageWidth - bounds.right,
                input.stageHeight - bounds.bottom,
            ).coerceAtLeast(0f)
            val fixes = if (elementId != null && isEditable) listOf(editElementFix(elementId, label)) else emptyList()
            return DeckLintIssue(
                id = issueKey(LintRuleId.SAFE_AREA_BLEED, input.sceneIndex, input.beatIndex, elementId, label, idx),
                ruleId = LintRuleId.SAFE_AREA_BLEED,
                severity = LintSeverity.Warning,
                sceneIndex = input.sceneIndex,
                beatIndex = input.beatIndex,
                sceneTitle = input.sceneTitle,
                elementId = elementId,
                elementLabel = label,
                textSnippet = abbreviate(snippet, 64),
                stageBounds = bounds,
                title = "Element intrudes on ${margin.roundToInt()} px stage safe margin",
                detail = "Edge clearance ${minClearance.roundToInt()} px < ${margin.roundToInt()} px safe margin",
                quickFixes = fixes,
            )
        }
        return null
    }

    private fun evaluateContrastRule(
        input: LintSlideInput,
        idx: Int,
        node: LintTextNodeCandidate,
        resolvedBg: Color?,
    ): DeckLintIssue? {
        if (!node.isTextNode || resolvedBg == null) return null
        val fg = node.effectiveForeground ?: return null
        val bgOpaque = resolvedBg.copy(alpha = 1f)

        val isLargeText = node.fontSizeSp >= WCAG_LARGE_TEXT_SP ||
            (node.isBold && node.fontSizeSp >= WCAG_LARGE_BOLD_TEXT_SP)
        val requiredRatio = if (isLargeText) WCAG_LARGE_TEXT_RATIO else WCAG_NORMAL_TEXT_RATIO

        // Skip nodes intentionally dimmed by spotlight (< 0.45 alpha) unless their opaque color itself fails.
        val opaqueFg = (node.opaqueForeground ?: fg).copy(alpha = 1f)
        val compositeAlpha = (node.effectiveAlpha * fg.alpha).coerceIn(0f, 1f)
        if (node.effectiveAlpha < SPOTLIGHT_SKIP_ALPHA) {
            val opaqueRatio = contrastRatio(opaqueFg, bgOpaque)
            if (opaqueRatio >= requiredRatio) {
                return null
            }
        }

        val blendedFg = if (compositeAlpha < 0.99f && node.effectiveAlpha >= SPOTLIGHT_SKIP_ALPHA) {
            compositeOver(fg.copy(alpha = compositeAlpha), bgOpaque)
        } else {
            opaqueFg
        }

        val ratio = contrastRatio(blendedFg, bgOpaque)
        if (ratio >= requiredRatio) return null

        val severity = if (ratio < WCAG_LARGE_TEXT_RATIO) LintSeverity.Error else LintSeverity.Warning
        val fixes = buildList {
            if (node.elementId != null && node.isEditable) {
                val targetRatio = requiredRatio + 0.15f
                val compliantColor = computeCompliantForeground(
                    foreground = opaqueFg,
                    background = bgOpaque,
                    targetRatio = targetRatio,
                )
                add(
                    LintQuickFix(
                        label = "Fix Contrast",
                        tooltip = "Set text color to ${formatHexRgb(compliantColor)} (${formatRatio(contrastRatio(compliantColor, bgOpaque))}:1 on ${formatHexRgb(bgOpaque)})",
                        patch = ElementPatch(
                            elementId = node.elementId,
                            textColorArgb = compliantColor.toArgbLong(),
                        ),
                    ),
                )
                add(editElementFix(node.elementId, node.label))
            }
        }

        return DeckLintIssue(
            id = issueKey(LintRuleId.LOW_CONTRAST, input.sceneIndex, input.beatIndex, node.elementId, node.label, idx),
            ruleId = LintRuleId.LOW_CONTRAST,
            severity = severity,
            sceneIndex = input.sceneIndex,
            beatIndex = input.beatIndex,
            sceneTitle = input.sceneTitle,
            elementId = node.elementId,
            elementLabel = node.label,
            textSnippet = abbreviate(node.text, 64),
            stageBounds = node.stageBounds,
            title = if (isLargeText) "Low contrast (large text)" else "Low text contrast",
            detail = "Contrast ${formatRatio(ratio)}:1 < ${formatRatio(requiredRatio)}:1 (${formatHexRgb(blendedFg)} on ${formatHexRgb(bgOpaque)})",
            quickFixes = fixes,
        )
    }

    private fun evaluateTinyTextRule(
        input: LintSlideInput,
        idx: Int,
        node: LintTextNodeCandidate,
    ): DeckLintIssue? {
        if (!node.isTextNode) return null
        if (node.fontSizeSp <= 0f || node.fontSizeSp >= TINY_TEXT_MIN_SP) return null

        val fixes = buildList {
            if (node.elementId != null && node.isEditable) {
                add(
                    LintQuickFix(
                        label = "Bump to 16sp",
                        tooltip = "Increase font size from ${formatSp(node.fontSizeSp)} sp to 16 sp",
                        patch = ElementPatch(elementId = node.elementId, fontSizeSp = 16f),
                    ),
                )
                add(editElementFix(node.elementId, node.label))
            }
        }

        return DeckLintIssue(
            id = issueKey(LintRuleId.TINY_STAGE_TEXT, input.sceneIndex, input.beatIndex, node.elementId, node.label, idx),
            ruleId = LintRuleId.TINY_STAGE_TEXT,
            severity = LintSeverity.Warning,
            sceneIndex = input.sceneIndex,
            beatIndex = input.beatIndex,
            sceneTitle = input.sceneTitle,
            elementId = node.elementId,
            elementLabel = node.label,
            textSnippet = abbreviate(node.text, 64),
            stageBounds = node.stageBounds,
            title = "Text too small for stage (< ${TINY_TEXT_MIN_SP.roundToInt()} sp)",
            detail = "Effective font size ${formatSp(node.fontSizeSp)} sp < ${TINY_TEXT_MIN_SP.roundToInt()} sp minimum",
            quickFixes = fixes,
        )
    }

    private fun evaluateOverlapRule(
        input: LintSlideInput,
        addUnique: (DeckLintIssue) -> Unit,
    ) {
        val visibleNodes = input.textNodes.filter {
            it.isTextNode &&
                it.effectiveAlpha > 0.05f &&
                it.text.isNotBlank() &&
                it.stageBounds.width > 1f &&
                it.stageBounds.height > 1f
        }
        for (i in visibleNodes.indices) {
            val a = visibleNodes[i]
            val areaA = a.stageBounds.width * a.stageBounds.height
            for (j in i + 1 until visibleNodes.size) {
                val b = visibleNodes[j]
                if (areRelatedSemanticsNodes(a, b)) continue
                val intersection = a.stageBounds.intersect(b.stageBounds)
                if (intersection.width <= 0f || intersection.height <= 0f) continue
                val overlapArea = intersection.width * intersection.height
                val smallerArea = minOf(areaA, b.stageBounds.width * b.stageBounds.height)
                if (smallerArea <= 0f) continue
                val fraction = overlapArea / smallerArea
                if (fraction <= OVERLAP_THRESHOLD_FRACTION) continue

                val targetNode = if (b.isEditable || a.elementId == null) b else a
                val fixes = if (targetNode.elementId != null && targetNode.isEditable) {
                    listOf(editElementFix(targetNode.elementId, targetNode.label))
                } else {
                    emptyList()
                }
                val pct = (fraction * 100f).roundToInt()
                addUnique(
                    DeckLintIssue(
                        id = issueKey(
                            LintRuleId.TEXT_OVERLAP,
                            input.sceneIndex,
                            input.beatIndex,
                            "${a.elementId ?: i}:${b.elementId ?: j}",
                            "${a.label}×${b.label}",
                            i * 1000 + j,
                        ),
                        ruleId = LintRuleId.TEXT_OVERLAP,
                        severity = LintSeverity.Warning,
                        sceneIndex = input.sceneIndex,
                        beatIndex = input.beatIndex,
                        sceneTitle = input.sceneTitle,
                        elementId = targetNode.elementId,
                        elementLabel = "${abbreviate(a.label, 24)} / ${abbreviate(b.label, 24)}",
                        textSnippet = "\"${abbreviate(a.text, 24)}\" overlaps \"${abbreviate(b.text, 24)}\"",
                        stageBounds = intersection,
                        title = "Overlapping text elements",
                        detail = "Bounding boxes overlap by $pct% of smaller element (> ${(OVERLAP_THRESHOLD_FRACTION * 100).roundToInt()}%)",
                        quickFixes = fixes,
                    ),
                )
            }
        }
    }

    /**
     * Computes a color with at least [targetRatio] WCAG 2.1 contrast against [background],
     * preserving the hue of [foreground] where possible before falling back to dark/light ink.
     */
    fun computeCompliantForeground(
        foreground: Color,
        background: Color,
        targetRatio: Float = 4.6f,
    ): Color {
        val bgOpaque = background.copy(alpha = 1f)
        val fgOpaque = foreground.copy(alpha = 1f)
        if (contrastRatio(fgOpaque, bgOpaque) >= targetRatio) return fgOpaque

        val bgLum = relativeLuminance(bgOpaque)
        val darkTarget = Color(0xFF0B0D12)
        val lightTarget = Color.White

        val primaryTarget = if (bgLum < 0.18f) lightTarget else darkTarget
        val secondaryTarget = if (bgLum < 0.18f) darkTarget else lightTarget

        findSmallestPassingBlend(fgOpaque, primaryTarget, bgOpaque, targetRatio)?.let { return it }
        if (contrastRatio(primaryTarget, bgOpaque) >= targetRatio) return primaryTarget
        findSmallestPassingBlend(fgOpaque, secondaryTarget, bgOpaque, targetRatio)?.let { return it }

        return if (contrastRatio(lightTarget, bgOpaque) >= contrastRatio(darkTarget, bgOpaque)) {
            lightTarget
        } else {
            darkTarget
        }
    }

    private fun findSmallestPassingBlend(
        from: Color,
        to: Color,
        background: Color,
        targetRatio: Float,
    ): Color? {
        if (contrastRatio(to, background) < targetRatio) return null
        var low = 0f
        var high = 1f
        var best = to
        repeat(20) {
            val mid = (low + high) / 2f
            val candidate = lerp(from, to, mid)
            if (contrastRatio(candidate, background) >= targetRatio) {
                best = candidate
                high = mid
            } else {
                low = mid
            }
        }
        return best
    }

    private fun mergeTextCandidates(
        stageSnapshots: List<StageNodeSnapshot>,
        semanticsNodes: List<LintTextNodeCandidate>,
        surfaces: List<LintSurfaceCandidate>,
        stageWidth: Float,
        stageHeight: Float,
    ): List<LintTextNodeCandidate> {
        if (stageSnapshots.isEmpty()) return semanticsNodes
        if (semanticsNodes.isEmpty()) {
            return stageSnapshots.map { snap ->
                val bg = snap.effectiveBackground ?: findEnclosingSurfaceBackground(snap.stageBounds, surfaces)
                LintTextNodeCandidate(
                    elementId = snap.elementId,
                    kind = snap.kind,
                    label = snap.label.ifBlank { snap.elementId },
                    text = snap.resolvedText,
                    stageBounds = snap.stageBounds,
                    unclippedStageBounds = snap.stageBounds,
                    hasVisualOverflow = snap.hasVisualOverflow,
                    hasEllipsis = snap.hasEllipsis,
                    fontSizeSp = snap.effectiveFontSizeSp ?: 0f,
                    effectiveForeground = snap.effectiveForeground,
                    opaqueForeground = snap.effectiveForeground,
                    effectiveBackground = bg,
                    isEditable = snap.isEditable,
                    isFullBleedSurface = isFullBleedBounds(snap.stageBounds, stageWidth, stageHeight),
                )
            }
        }

        val usedSemanticsIndices = HashSet<Int>()
        val merged = mutableListOf<LintTextNodeCandidate>()

        for (snap in stageSnapshots) {
            val matchIdx = findMatchingSemanticsIndex(snap, semanticsNodes, usedSemanticsIndices)
            val snapBg = snap.effectiveBackground ?: findEnclosingSurfaceBackground(snap.stageBounds, surfaces)
            if (matchIdx != null) {
                usedSemanticsIndices += matchIdx
                val sem = semanticsNodes[matchIdx]
                merged += sem.copy(
                    elementId = snap.elementId,
                    kind = snap.kind,
                    label = snap.label.ifBlank { sem.label },
                    text = snap.resolvedText.ifBlank { sem.text },
                    stageBounds = if (snap.stageBounds.width > 1f && snap.stageBounds.height > 1f) snap.stageBounds else sem.stageBounds,
                    unclippedStageBounds = if (sem.unclippedStageBounds != sem.stageBounds) sem.unclippedStageBounds else snap.stageBounds,
                    hasVisualOverflow = sem.hasVisualOverflow || snap.hasVisualOverflow,
                    hasEllipsis = sem.hasEllipsis || snap.hasEllipsis,
                    fontSizeSp = snap.effectiveFontSizeSp ?: sem.fontSizeSp,
                    effectiveForeground = snap.effectiveForeground ?: sem.effectiveForeground,
                    opaqueForeground = snap.effectiveForeground ?: sem.opaqueForeground,
                    effectiveBackground = snap.effectiveBackground ?: sem.effectiveBackground ?: snapBg,
                    isEditable = snap.isEditable,
                )
            } else {
                merged += LintTextNodeCandidate(
                    elementId = snap.elementId,
                    kind = snap.kind,
                    label = snap.label.ifBlank { snap.elementId },
                    text = snap.resolvedText,
                    stageBounds = snap.stageBounds,
                    unclippedStageBounds = snap.stageBounds,
                    hasVisualOverflow = snap.hasVisualOverflow,
                    hasEllipsis = snap.hasEllipsis,
                    fontSizeSp = snap.effectiveFontSizeSp ?: 0f,
                    effectiveForeground = snap.effectiveForeground,
                    opaqueForeground = snap.effectiveForeground,
                    effectiveBackground = snapBg,
                    isEditable = snap.isEditable,
                    isFullBleedSurface = isFullBleedBounds(snap.stageBounds, stageWidth, stageHeight),
                )
            }
        }

        for ((idx, sem) in semanticsNodes.withIndex()) {
            if (idx !in usedSemanticsIndices) {
                merged += sem
            }
        }
        return merged
    }

    private fun findMatchingSemanticsIndex(
        snap: StageNodeSnapshot,
        semanticsNodes: List<LintTextNodeCandidate>,
        used: Set<Int>,
    ): Int? {
        var bestIdx: Int? = null
        var bestScore = 0f
        for ((idx, sem) in semanticsNodes.withIndex()) {
            if (idx in used) continue
            val iou = rectIoU(snap.stageBounds, sem.stageBounds)
            val textMatch = snap.resolvedText.trim() == sem.text.trim()
            val overlapsOrNear = snap.stageBounds.overlaps(sem.stageBounds) ||
                snap.stageBounds.contains(sem.stageBounds.center) ||
                centerDistance(snap.stageBounds, sem.stageBounds) <= 48f
            val score = when {
                iou >= 0.45f && textMatch -> 3f + iou
                textMatch && overlapsOrNear -> 2f + iou
                iou >= 0.65f -> 1f + iou
                else -> 0f
            }
            if (score > bestScore) {
                bestScore = score
                bestIdx = idx
            }
        }
        return bestIdx
    }

    private fun findEnclosingSurfaceBackground(
        bounds: Rect,
        surfaces: List<LintSurfaceCandidate>,
    ): Color? {
        val center = bounds.center
        var bestSurface: LintSurfaceCandidate? = null
        var smallestArea = Float.MAX_VALUE
        for (surface in surfaces) {
            if (surface.effectiveBackground == null) continue
            if (surface.effectiveAlpha <= INVISIBLE_ALPHA_THRESHOLD) continue
            if (surface.stageBounds.contains(center)) {
                val area = surface.stageBounds.width * surface.stageBounds.height
                if (area < smallestArea) {
                    smallestArea = area
                    bestSurface = surface
                }
            }
        }
        return bestSurface?.effectiveBackground
    }

    private fun areRelatedSemanticsNodes(a: LintTextNodeCandidate, b: LintTextNodeCandidate): Boolean {
        val aId = a.semanticsNodeId
        val bId = b.semanticsNodeId
        if (aId != null && bId != null && (aId in b.ancestorSemanticsIds || bId in a.ancestorSemanticsIds)) {
            return true
        }
        if (a.elementId != null && a.elementId == b.elementId) return true
        return false
    }

    private fun isFullBleedBounds(bounds: Rect, stageWidth: Float, stageHeight: Float): Boolean =
        bounds.width >= stageWidth * 0.98f &&
            bounds.height >= stageHeight * 0.98f &&
            bounds.left <= 2f &&
            bounds.top <= 2f

    private fun rectIoU(a: Rect, b: Rect): Float {
        val inter = a.intersect(b)
        if (inter.width <= 0f || inter.height <= 0f) return 0f
        val interArea = inter.width * inter.height
        val unionArea = (a.width * a.height) + (b.width * b.height) - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    private fun centerDistance(a: Rect, b: Rect): Float {
        val dx = a.center.x - b.center.x
        val dy = a.center.y - b.center.y
        return kotlin.math.hypot(dx, dy)
    }

    private fun compositeOver(fg: Color, bg: Color): Color {
        val a = fg.alpha.coerceIn(0f, 1f)
        val inv = 1f - a
        return Color(
            red = fg.red * a + bg.red * inv,
            green = fg.green * a + bg.green * inv,
            blue = fg.blue * a + bg.blue * inv,
            alpha = 1f,
        )
    }

    private fun editElementFix(elementId: String, label: String): LintQuickFix = LintQuickFix(
        label = "Edit Element…",
        tooltip = "Open Direct Element Editor for $label",
        patch = null,
        opensElementEditor = true,
    )

    private fun issueKey(
        ruleId: LintRuleId,
        sceneIndex: Int,
        beatIndex: Int,
        elementId: String?,
        label: String,
        idx: Int,
    ): String = "${ruleId.name}@s$sceneIndex:b$beatIndex:${elementId ?: "$label#$idx"}"

    private fun abbreviate(text: String, maxLen: Int): String {
        val singleLine = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= maxLen) singleLine else singleLine.take(maxLen - 1) + "…"
    }

    private fun formatRatio(ratio: Float): String {
        val rounded = (ratio * 100f).roundToInt()
        val whole = rounded / 100
        val frac = (rounded % 100).toString().padStart(2, '0')
        return "$whole.$frac"
    }

    private fun formatSp(sp: Float): String {
        val rounded = (sp * 10f).roundToInt()
        val whole = rounded / 10
        val tenths = rounded % 10
        return if (tenths == 0) "$whole" else "$whole.$tenths"
    }

    private fun formatHexRgb(color: Color): String {
        val r = (color.red.coerceIn(0f, 1f) * 255f).roundToInt()
        val g = (color.green.coerceIn(0f, 1f) * 255f).roundToInt()
        val b = (color.blue.coerceIn(0f, 1f) * 255f).roundToInt()
        return "#" + r.toHex2() + g.toHex2() + b.toHex2()
    }

    private fun Int.toHex2(): String = toString(16).uppercase().padStart(2, '0')

    private fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
}
