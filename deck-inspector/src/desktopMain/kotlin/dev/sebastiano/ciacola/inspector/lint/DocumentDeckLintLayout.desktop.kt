package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlidePaletteSpec
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.theme.contrastRatio
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal const val OPAQUE_ALPHA_EPSILON = 0.999f

@Immutable
internal data class LaidOutNode(
    val node: NodeSpec,
    val bounds: Rect,
    val paintOrder: Int,
    val ancestorIds: Set<String>,
    val effectiveOpacity: Double,
    val hasActiveEffectAtBeat: Boolean,
    val ancestorOpaqueBackground: Color?,
    val ancestorsAreOpaqueSolidOrTransparent: Boolean,
    val surfaceOpaqueFill: Color?,
    val hasNonSolidSurfaceOrFill: Boolean,
    val hasApproximateFlowLayout: Boolean = false,
)

@Immutable
internal sealed interface StaticContrastDecision {
    @Immutable
    data class Eligible(
        val foreground: Color,
        val background: Color,
        val ratio: Float,
        val requiredRatio: Float,
        val fontSizeUnits: Double,
        val isBold: Boolean,
    ) : StaticContrastDecision

    @Immutable
    data class RequiresPixelRender(
        val reason: String,
        val foreground: Color?,
        val requiredRatio: Float,
        val fontSizeUnits: Double,
        val isBold: Boolean,
    ) : StaticContrastDecision

    @Immutable
    data class Unknown(
        val reason: String,
    ) : StaticContrastDecision
}

/**
 * Pure resting-state analytical layout pass, static contrast eligibility checker,
 * code natural-size estimator, and resolved-style theme hasher.
 */
internal object DocumentDeckLintLayout {

    /**
     * Resolves all visible nodes on [slide] at resting [beat] and computes their stage-space
     * rectangles in paint order without mounting Compose trees.
     */
    fun layoutSlideAtBeat(
        slide: SlideSpec,
        beat: Int,
        document: DeckDocument,
        themeSpec: DeckThemeSpec,
    ): List<LaidOutNode> {
        val stageW = document.stage.width.toFloat().coerceAtLeast(1f)
        val stageH = document.stage.height.toFloat().coerceAtLeast(1f)
        val palette = slide.palette?.let { document.palettes[it] }

        val (slideBgColor, slideBgSolid) = resolveOpaqueSolidSlideBackground(slide, document, palette, themeSpec)

        val out = mutableListOf<LaidOutNode>()
        var nextPaintOrder = 0

        fun visitNode(
            rawNode: NodeSpec,
            parentContentRect: Rect,
            ancestorIds: Set<String>,
            inheritedOpacity: Double,
            inheritedEffect: Boolean,
            inheritedBg: Color?,
            inheritedSolidChain: Boolean,
            inheritedApproxFlow: Boolean = false,
            assignedRect: Rect? = null,
        ) {
            if (!isNodePresentAtBeat(rawNode, beat)) return
            val resolved = JsonMergePatch.resolveNodeAtBeat(rawNode, beat)
            val effectiveOpacity = inheritedOpacity * resolved.opacity.coerceIn(0.0, 1.0)
            if (effectiveOpacity <= INVISIBLE_ALPHA_THRESHOLD) return

            val nodeHasEffect = inheritedEffect || hasActiveVisualEffectAtBeat(rawNode, beat)
            val bounds = assignedRect ?: computeRectInStack(resolved, parentContentRect, themeSpec)
            val (surfaceColor, surfaceNonSolid, surfaceTransparent) = inspectNodeSurfaceFill(resolved, palette, themeSpec)

            val thisSolidChain = inheritedSolidChain && !surfaceNonSolid &&
                (surfaceTransparent || surfaceColor != null)
            val effectiveBgForChildren = when {
                surfaceNonSolid -> null
                surfaceColor != null -> surfaceColor
                else -> inheritedBg
            }

            val order = nextPaintOrder++
            out += LaidOutNode(
                node = resolved,
                bounds = bounds,
                paintOrder = order,
                ancestorIds = ancestorIds,
                effectiveOpacity = effectiveOpacity,
                hasActiveEffectAtBeat = nodeHasEffect,
                ancestorOpaqueBackground = effectiveBgForChildren,
                ancestorsAreOpaqueSolidOrTransparent = thisSolidChain,
                surfaceOpaqueFill = surfaceColor,
                hasNonSolidSurfaceOrFill = surfaceNonSolid,
                hasApproximateFlowLayout = inheritedApproxFlow,
            )

            val childAncestors = ancestorIds + resolved.id
            when {
                resolved.stack != null -> {
                    val contentRect = insetRect(bounds, resolved.stack!!.padding, resolved.stack!!.surface?.padding)
                    for (child in resolved.stack!!.children) {
                        visitNode(
                            rawNode = child,
                            parentContentRect = contentRect,
                            ancestorIds = childAncestors,
                            inheritedOpacity = effectiveOpacity,
                            inheritedEffect = nodeHasEffect,
                            inheritedBg = effectiveBgForChildren,
                            inheritedSolidChain = thisSolidChain,
                            inheritedApproxFlow = inheritedApproxFlow,
                        )
                    }
                }

                resolved.row != null -> {
                    val (childrenWithRects, hasApproxCodeChild) = layoutLinearContainer(
                        container = resolved.row!!,
                        bounds = bounds,
                        horizontal = true,
                        beat = beat,
                        themeSpec = themeSpec,
                    )
                    childrenWithRects.forEach { (child, childRect) ->
                        visitNode(
                            rawNode = child,
                            parentContentRect = childRect,
                            ancestorIds = childAncestors,
                            inheritedOpacity = effectiveOpacity,
                            inheritedEffect = nodeHasEffect,
                            inheritedBg = effectiveBgForChildren,
                            inheritedSolidChain = thisSolidChain,
                            inheritedApproxFlow = inheritedApproxFlow || hasApproxCodeChild,
                            assignedRect = childRect,
                        )
                    }
                }

                resolved.column != null -> {
                    val (childrenWithRects, hasApproxCodeChild) = layoutLinearContainer(
                        container = resolved.column!!,
                        bounds = bounds,
                        horizontal = false,
                        beat = beat,
                        themeSpec = themeSpec,
                    )
                    childrenWithRects.forEach { (child, childRect) ->
                        visitNode(
                            rawNode = child,
                            parentContentRect = childRect,
                            ancestorIds = childAncestors,
                            inheritedOpacity = effectiveOpacity,
                            inheritedEffect = nodeHasEffect,
                            inheritedBg = effectiveBgForChildren,
                            inheritedSolidChain = thisSolidChain,
                            inheritedApproxFlow = inheritedApproxFlow || hasApproxCodeChild,
                            assignedRect = childRect,
                        )
                    }
                }

                resolved.loupe != null -> {
                    // Loupe applies magnification over descendants; mark children as non-solid chain so pixel audit checks them.
                    for (child in resolved.loupe!!.children) {
                        visitNode(
                            rawNode = child,
                            parentContentRect = bounds,
                            ancestorIds = childAncestors,
                            inheritedOpacity = effectiveOpacity,
                            inheritedEffect = true,
                            inheritedBg = null,
                            inheritedSolidChain = false,
                            inheritedApproxFlow = inheritedApproxFlow,
                        )
                    }
                }
            }
        }

        val rootRect = Rect(0f, 0f, stageW, stageH)
        for (rootNode in slide.nodes) {
            visitNode(
                rawNode = rootNode,
                parentContentRect = rootRect,
                ancestorIds = emptySet(),
                inheritedOpacity = 1.0,
                inheritedEffect = false,
                inheritedBg = slideBgColor,
                inheritedSolidChain = slideBgSolid,
            )
        }
        return out
    }

    /**
     * Evaluates static contrast eligibility for a text [entry].
     *
     * Eligible iff:
     * 1. Every ancestor is a container with no surface or an opaque solid fill, and either the
     *    slide background or an ancestor/node surface supplies an opaque solid color.
     * 2. The node and every ancestor have opacity 1 and no active visual effect at this beat.
     * 3. No non-ancestor node painted before this text overlaps its resting rectangle.
     */
    fun evaluateStaticContrast(
        entry: LaidOutNode,
        allNodes: List<LaidOutNode>,
        slide: SlideSpec,
        document: DeckDocument,
        themeSpec: DeckThemeSpec,
    ): StaticContrastDecision {
        val textPayload = entry.node.text
            ?: return StaticContrastDecision.Unknown("Not a text node.")
        if (textPayload.value.plainText.isBlank()) {
            return StaticContrastDecision.Unknown("Blank text.")
        }

        val palette = slide.palette?.let { document.palettes[it] }
        val resolvedStyle = DocumentThemeResolver.resolveTypographyRole(textPayload.style, themeSpec)
        val fontSizeUnits = textPayload.size ?: resolvedStyle.size
        val weight = textPayload.weight ?: resolvedStyle.weight
        val isBold = weight >= 700
        // Document units are 2x Compose dp/sp, so convert to sp for WCAG large-text threshold.
        val fontSizeSp = (fontSizeUnits / 2.0).toFloat()
        val requiredRatio = if (
            fontSizeSp >= WCAG_LARGE_TEXT_SP || (isBold && fontSizeSp >= WCAG_LARGE_BOLD_TEXT_SP)
        ) {
            WCAG_LARGE_TEXT_RATIO
        } else {
            WCAG_NORMAL_TEXT_RATIO
        }

        val foreground = resolveWorstTextForeground(textPayload, resolvedStyle.color, slide, document, palette, themeSpec)
        if (foreground == null) {
            return StaticContrastDecision.Unknown("Text color could not be resolved.")
        }

        // Check condition 2: opacity 1 and no active effect on node or ancestors.
        if (entry.effectiveOpacity < OPAQUE_ALPHA_EPSILON) {
            return StaticContrastDecision.RequiresPixelRender(
                reason = "Partial opacity (${entry.effectiveOpacity})",
                foreground = foreground,
                requiredRatio = requiredRatio,
                fontSizeUnits = fontSizeUnits,
                isBold = isBold,
            )
        }
        if (foreground.alpha < OPAQUE_ALPHA_EPSILON) {
            return StaticContrastDecision.RequiresPixelRender(
                reason = "Semi-transparent foreground color",
                foreground = foreground,
                requiredRatio = requiredRatio,
                fontSizeUnits = fontSizeUnits,
                isBold = isBold,
            )
        }
        if (entry.hasActiveEffectAtBeat) {
            return StaticContrastDecision.RequiresPixelRender(
                reason = "Active visual effect on node or ancestor at beat",
                foreground = foreground,
                requiredRatio = requiredRatio,
                fontSizeUnits = fontSizeUnits,
                isBold = isBold,
            )
        }

        if (entry.hasApproximateFlowLayout) {
            return StaticContrastDecision.RequiresPixelRender(
                reason = "Flow container includes unframed code node with estimated bounds",
                foreground = foreground,
                requiredRatio = requiredRatio,
                fontSizeUnits = fontSizeUnits,
                isBold = isBold,
            )
        }

        // Check condition 1: opaque solid ancestor/surface/slide background chain.
        if (!entry.ancestorsAreOpaqueSolidOrTransparent || entry.hasNonSolidSurfaceOrFill) {
            return StaticContrastDecision.RequiresPixelRender(
                reason = "Non-solid or translucent background/surface (gradient, image, or alpha fill)",
                foreground = foreground,
                requiredRatio = requiredRatio,
                fontSizeUnits = fontSizeUnits,
                isBold = isBold,
            )
        }
        val bg = entry.ancestorOpaqueBackground
            ?: return StaticContrastDecision.RequiresPixelRender(
                reason = "No opaque solid background beneath text",
                foreground = foreground,
                requiredRatio = requiredRatio,
                fontSizeUnits = fontSizeUnits,
                isBold = isBold,
            )

        // Check condition 3: no non-ancestor node paints underneath the text rectangle.
        // Note: if the text node itself has its own opaque solid surface, siblings painted BEFORE
        // the text node are occluded by the text's own opaque surface — otherwise any overlapping
        // earlier non-ancestor node (or any earlier unframed code node with estimated bounds)
        // disqualifies the fast path.
        if (entry.surfaceOpaqueFill == null) {
            for (other in allNodes) {
                if (other.paintOrder >= entry.paintOrder) break
                if (other.node.id in entry.ancestorIds) continue
                val otherIsUnframedCode = other.node.kind == NodeKind.Code &&
                    (other.node.frame?.w == null || other.node.frame?.h == null)
                if (otherIsUnframedCode || rectsIntersectStrictly(other.bounds, entry.bounds)) {
                    return StaticContrastDecision.RequiresPixelRender(
                        reason = if (otherIsUnframedCode) {
                            "Preceded by unframed code node '${other.node.id}' with estimated bounds"
                        } else {
                            "Overlapped underneath by sibling '${other.node.id}' (${other.node.kind?.payloadKey})"
                        },
                        foreground = foreground,
                        requiredRatio = requiredRatio,
                        fontSizeUnits = fontSizeUnits,
                        isBold = isBold,
                    )
                }
            }
        }

        // If RichTextValue.Runs specifies a per-run background, check eligibility per run.
        val runs = (textPayload.value as? RichTextValue.Runs)?.runs
        if (runs != null) {
            var worstRatio = Float.POSITIVE_INFINITY
            var worstFg: Color = foreground
            var worstBg: Color = bg
            for (run in runs) {
                if (run.text.isBlank()) continue
                val runFg = DocumentThemeResolver.resolveColor(run.color, palette, themeSpec) ?: foreground
                if (runFg.alpha < OPAQUE_ALPHA_EPSILON) {
                    return StaticContrastDecision.RequiresPixelRender(
                        reason = "Semi-transparent text run",
                        foreground = runFg,
                        requiredRatio = requiredRatio,
                        fontSizeUnits = fontSizeUnits,
                        isBold = isBold,
                    )
                }
                val runBg = if (run.background != null) {
                    val parsed = DocumentThemeResolver.resolveColor(run.background, palette, themeSpec)
                    if (parsed == null || parsed.alpha < OPAQUE_ALPHA_EPSILON) {
                        return StaticContrastDecision.RequiresPixelRender(
                            reason = "Translucent text run background",
                            foreground = runFg,
                            requiredRatio = requiredRatio,
                            fontSizeUnits = fontSizeUnits,
                            isBold = isBold,
                        )
                    }
                    parsed
                } else {
                    bg
                }
                val ratio = contrastRatio(runFg, runBg)
                if (ratio < worstRatio) {
                    worstRatio = ratio
                    worstFg = runFg
                    worstBg = runBg
                }
            }
            if (worstRatio.isFinite()) {
                return StaticContrastDecision.Eligible(
                    foreground = worstFg,
                    background = worstBg,
                    ratio = worstRatio,
                    requiredRatio = requiredRatio,
                    fontSizeUnits = fontSizeUnits,
                    isBold = isBold,
                )
            }
        }

        val ratio = contrastRatio(foreground, bg)
        return StaticContrastDecision.Eligible(
            foreground = foreground,
            background = bg,
            ratio = ratio,
            requiredRatio = requiredRatio,
            fontSizeUnits = fontSizeUnits,
            isBold = isBold,
        )
    }

    /**
     * Estimates natural width & height (in document units) for a `code` node to detect when its
     * code content overflows a fixed `frame.w` × `frame.h` box.
     */
    fun estimateCodeNaturalSize(node: NodeSpec, themeSpec: DeckThemeSpec): Pair<Double, Double> {
        val code = node.code ?: return 0.0 to 0.0
        val style = DocumentThemeResolver.resolveTypographyRole("code", themeSpec)
        val lines = code.value.split('\n')
        val maxCols = lines.maxOfOrNull { it.length } ?: 0
        val pad = code.surface?.padding ?: PaddingSpec(24.0)
        // Monospace average advance ~0.62 * fontSize; lineHeight ~1.35 * fontSize.
        val charAdvance = style.size * 0.62
        val lineHeight = max(style.lineHeight, style.size * 1.3)
        val naturalW = maxCols * charAdvance + pad.left + pad.right + 16.0
        val naturalH = lines.size.coerceAtLeast(1) * lineHeight + pad.top + pad.bottom + 16.0
        return naturalW to naturalH
    }

    /**
     * Computes a SHA-256 hash of [slide]'s **fully resolved node styles** under [themeName].
     *
     * A slide is deduplicated across themes ONLY when this hash matches — never merely because
     * `slide.palette != null`, because code cards, typography roles, and specimens still depend on
     * the active theme.
     */
    fun computeResolvedSlideStyleHash(
        slide: SlideSpec,
        document: DeckDocument,
        themeName: String,
    ): String {
        val themeSpec = DocumentThemeResolver.resolveThemeSpec(document, themeName)
        val themeColors = DocumentThemeResolver.resolveThemeColorMap(themeSpec)
        val palette = slide.palette?.let { document.palettes[it] }

        val sb = StringBuilder(512)
        sb.append("slide=").append(slide.id).append(';')
        val bgHex = resolveFillCanonical(slide.background, palette, themeSpec)
            ?: colorHex(DocumentThemeResolver.resolveSlideCanvasColor(slide, document, themeSpec))
        sb.append("bg=").append(bgHex).append(';')

        val beatCount = slide.beats.size.coerceAtLeast(1)
        for (beat in 0 until beatCount) {
            sb.append("b").append(beat).append('{')
            fun appendNodes(nodes: List<NodeSpec>) {
                for (raw in nodes) {
                    if (!isNodePresentAtBeat(raw, beat)) continue
                    val n = JsonMergePatch.resolveNodeAtBeat(raw, beat)
                    sb.append(n.id).append(':').append(n.kind?.payloadKey).append(':')
                        .append(n.opacity).append(':')
                    n.text?.let { t ->
                        val role = DocumentThemeResolver.resolveTypographyRole(t.style, themeSpec)
                        val fg = resolveTextColor(t.color ?: role.color, slide, document, palette, themeSpec)
                        sb.append("font=").append(t.font ?: role.font)
                            .append(",sz=").append(t.size ?: role.size)
                            .append(",w=").append(t.weight ?: role.weight)
                            .append(",lh=").append(t.lineHeight ?: role.lineHeight)
                            .append(",fg=").append(fg?.let(::colorHex))
                            .append(",surf=").append(resolveSurfaceCanonical(t.surface, palette, themeSpec))
                        (t.value as? RichTextValue.Runs)?.runs?.forEach { r ->
                            sb.append(",r=").append(r.color?.let { DocumentThemeResolver.resolveColor(it, palette, themeSpec)?.let(::colorHex) })
                                .append('/').append(r.background?.let { DocumentThemeResolver.resolveColor(it, palette, themeSpec)?.let(::colorHex) })
                        }
                    }
                    n.code?.let { c ->
                        val codeStyle = DocumentThemeResolver.resolveTypographyRole("code", themeSpec)
                        sb.append("code=").append(codeStyle.font).append('/').append(codeStyle.size)
                            .append(",cbg=").append(themeColors["code.background"]?.let(::colorHex))
                            .append(",cfg=").append(themeColors["code.foreground"]?.let(::colorHex))
                            .append(",ckw=").append(themeColors["code.keyword"]?.let(::colorHex))
                            .append(",surf=").append(resolveSurfaceCanonical(c.surface, palette, themeSpec))
                    }
                    n.shape?.let { s ->
                        sb.append("fill=").append(resolveFillCanonical(s.fill, palette, themeSpec))
                            .append(",str=").append(s.stroke?.color?.let { DocumentThemeResolver.resolveColor(it, palette, themeSpec)?.let(::colorHex) })
                    }
                    n.specimen?.let { sp ->
                        // Specimens read theme directly (preset + full theme colors), so include resolved theme palette!
                        sb.append("spec=").append(sp.ref)
                            .append(",preset=").append(themeSpec.preset)
                            .append(",tbg=").append(themeColors["background"]?.let(::colorHex))
                            .append(",tfg=").append(themeColors["foreground"]?.let(::colorHex))
                            .append(",tacc=").append(themeColors["accent"]?.let(::colorHex))
                            .append(",tsurf=").append(themeColors["surface"]?.let(::colorHex))
                    }
                    n.stack?.surface?.let { sb.append(",stSurf=").append(resolveSurfaceCanonical(it, palette, themeSpec)) }
                    n.row?.surface?.let { sb.append(",rSurf=").append(resolveSurfaceCanonical(it, palette, themeSpec)) }
                    n.column?.surface?.let { sb.append(",cSurf=").append(resolveSurfaceCanonical(it, palette, themeSpec)) }
                    sb.append(';')
                    if (n.children.isNotEmpty()) appendNodes(n.children)
                }
            }
            appendNodes(slide.nodes)
            sb.append('}')
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sb.toString().toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { b -> "%02x".format(b) }
    }

    internal fun isNodePresentAtBeat(node: NodeSpec, beat: Int): Boolean {
        val enterBeat = node.builds.filterIsInstance<Build.Enter>().minOfOrNull { it.beat }
        if (enterBeat != null && beat < enterBeat) return false
        val exitBeat = node.builds.filterIsInstance<Build.Exit>().minOfOrNull { it.beat }
        if (exitBeat != null && beat >= exitBeat) return false
        return true
    }

    private fun hasActiveVisualEffectAtBeat(node: NodeSpec, beat: Int): Boolean =
        node.builds.any { build ->
            build.beat == beat && build is Build.Emphasis
        }

    private fun resolveOpaqueSolidSlideBackground(
        slide: SlideSpec,
        document: DeckDocument,
        palette: SlidePaletteSpec?,
        themeSpec: DeckThemeSpec,
    ): Pair<Color?, Boolean> {
        return when (val bg = slide.background) {
            null -> {
                val c = DocumentThemeResolver.resolveSlideCanvasColor(slide, document, themeSpec)
                if (c.alpha >= OPAQUE_ALPHA_EPSILON) c to true else null to false
            }

            is Fill.Solid -> {
                val c = DocumentThemeResolver.resolveColor(bg.color, palette, themeSpec)
                if (c != null && c.alpha >= OPAQUE_ALPHA_EPSILON) c to true else null to false
            }

            is Fill.Gradient, is Fill.Image -> null to false
        }
    }

    private data class SurfaceFillInspection(
        val opaqueColor: Color?,
        val isNonSolidOrTranslucent: Boolean,
        val isTransparent: Boolean,
    )

    private fun inspectNodeSurfaceFill(
        node: NodeSpec,
        palette: SlidePaletteSpec?,
        themeSpec: DeckThemeSpec,
    ): SurfaceFillInspection {
        val fill: Fill? = when {
            node.text != null -> node.text!!.surface?.fill
            node.code != null -> node.code!!.surface?.fill
            node.shape != null -> node.shape!!.fill
            node.stack != null -> node.stack!!.surface?.fill
            node.row != null -> node.row!!.surface?.fill
            node.column != null -> node.column!!.surface?.fill
            else -> null
        }
        return when (fill) {
            null -> SurfaceFillInspection(opaqueColor = null, isNonSolidOrTranslucent = false, isTransparent = true)
            is Fill.Solid -> {
                val c = DocumentThemeResolver.resolveColor(fill.color, palette, themeSpec)
                if (c != null && c.alpha >= OPAQUE_ALPHA_EPSILON) {
                    SurfaceFillInspection(opaqueColor = c, isNonSolidOrTranslucent = false, isTransparent = false)
                } else {
                    SurfaceFillInspection(opaqueColor = null, isNonSolidOrTranslucent = true, isTransparent = false)
                }
            }

            is Fill.Gradient, is Fill.Image ->
                SurfaceFillInspection(opaqueColor = null, isNonSolidOrTranslucent = true, isTransparent = false)
        }
    }

    private fun computeRectInStack(
        node: NodeSpec,
        parentContentRect: Rect,
        themeSpec: DeckThemeSpec,
    ): Rect {
        val f = node.frame
        val estimated = estimateIntrinsicSize(node, parentContentRect, themeSpec)
        val w = (f?.w?.toFloat() ?: estimated.first).coerceAtLeast(1f)
        val h = (f?.h?.toFloat() ?: estimated.second).coerceAtLeast(1f)
        val offX = node.offset?.x?.toFloat() ?: 0f
        val offY = node.offset?.y?.toFloat() ?: 0f

        val left = if (f?.x != null) {
            parentContentRect.left + f.x!!.toFloat()
        } else {
            when (node.align?.lowercase()) {
                "center", "topcenter", "bottomcenter" -> parentContentRect.left + (parentContentRect.width - w) / 2f
                "topright", "centerright", "bottomright", "end" -> parentContentRect.right - w
                else -> parentContentRect.left
            }
        }
        val top = if (f?.y != null) {
            parentContentRect.top + f.y!!.toFloat()
        } else {
            when (node.align?.lowercase()) {
                "center", "centerleft", "centerright" -> parentContentRect.top + (parentContentRect.height - h) / 2f
                "bottomleft", "bottomcenter", "bottomright", "bottom" -> parentContentRect.bottom - h
                else -> parentContentRect.top
            }
        }
        return Rect(left + offX, top + offY, left + offX + w, top + offY + h)
    }

    private fun layoutLinearContainer(
        container: ContainerPayload,
        bounds: Rect,
        horizontal: Boolean,
        beat: Int,
        themeSpec: DeckThemeSpec,
    ): Pair<List<Pair<NodeSpec, Rect>>, Boolean> {
        val content = insetRect(bounds, container.padding, container.surface?.padding)
        val visibleChildren = container.children.filter { isNodePresentAtBeat(it, beat) }
        if (visibleChildren.isEmpty()) return emptyList<Pair<NodeSpec, Rect>>() to false
        val gap = (container.gap ?: 0.0).toFloat()
        val totalGap = gap * (visibleChildren.size - 1).coerceAtLeast(0)

        val resolvedPairs = visibleChildren.map { raw -> raw to JsonMergePatch.resolveNodeAtBeat(raw, beat) }
        val hasApproxCodeChild = resolvedPairs.any { (_, r) ->
            r.kind == NodeKind.Code && (r.frame?.w == null || r.frame?.h == null)
        }
        val totalWeight = resolvedPairs.sumOf { (_, r) -> (r.weight ?: 0.0).coerceAtLeast(0.0) }
        val intrinsic = resolvedPairs.map { (_, r) -> estimateIntrinsicSize(r, content, themeSpec) }
        val fixedMain = resolvedPairs.indices.sumOf { i ->
            val r = resolvedPairs[i].second
            if ((r.weight ?: 0.0) > 0.0) {
                0.0
            } else {
                if (horizontal) (r.frame?.w ?: intrinsic[i].first.toDouble()) else (r.frame?.h ?: intrinsic[i].second.toDouble())
            }
        }.toFloat()
        val remainingMain = ((if (horizontal) content.width else content.height) - totalGap - fixedMain).coerceAtLeast(0f)

        val out = mutableListOf<Pair<NodeSpec, Rect>>()
        var cursor = if (horizontal) content.left else content.top
        for (i in resolvedPairs.indices) {
            val (raw, r) = resolvedPairs[i]
            val weight = (r.weight ?: 0.0).coerceAtLeast(0.0)
            val main = if (weight > 0.0 && totalWeight > 0.0) {
                (remainingMain * (weight / totalWeight)).toFloat().coerceAtLeast(1f)
            } else if (horizontal) {
                (r.frame?.w?.toFloat() ?: intrinsic[i].first).coerceAtLeast(1f)
            } else {
                (r.frame?.h?.toFloat() ?: intrinsic[i].second).coerceAtLeast(1f)
            }
            val cross = if (horizontal) {
                (r.frame?.h?.toFloat() ?: intrinsic[i].second.coerceAtMost(content.height)).coerceAtLeast(1f)
            } else {
                (r.frame?.w?.toFloat() ?: intrinsic[i].first.coerceAtMost(content.width)).coerceAtLeast(1f)
            }
            val offX = r.offset?.x?.toFloat() ?: 0f
            val offY = r.offset?.y?.toFloat() ?: 0f
            val rect = if (horizontal) {
                Rect(cursor + offX, content.top + offY, cursor + offX + main, content.top + offY + cross)
            } else {
                Rect(content.left + offX, cursor + offY, content.left + offX + cross, cursor + offY + main)
            }
            out += raw to rect
            cursor += main + gap
        }
        return out to hasApproxCodeChild
    }

    private fun estimateIntrinsicSize(
        node: NodeSpec,
        parentRect: Rect,
        themeSpec: DeckThemeSpec,
    ): Pair<Float, Float> {
        val f = node.frame
        if (f?.w != null && f.h != null) return f.w!!.toFloat() to f.h!!.toFloat()

        return when (node.kind) {
            NodeKind.Text -> {
                val t = node.text!!
                val style = DocumentThemeResolver.resolveTypographyRole(t.style, themeSpec)
                val fontSize = (t.size ?: style.size).toFloat()
                val lineHeight = max((t.lineHeight ?: style.lineHeight).toFloat(), fontSize * 1.25f)
                val pad = t.surface?.padding ?: PaddingSpec()
                val maxW = (f?.w?.toFloat() ?: parentRect.width.coerceAtLeast(64f)) - (pad.left + pad.right).toFloat()
                val rawLines = t.value.plainText.split('\n')
                val avgCharW = fontSize * 0.56f
                var wrappedLines = 0
                var widest = 0f
                for (line in rawLines) {
                    val lineW = line.length * avgCharW
                    widest = max(widest, min(lineW, maxW))
                    wrappedLines += max(1, ceil((lineW / maxW.coerceAtLeast(1f)).toDouble()).toInt())
                }
                val w = f?.w?.toFloat() ?: (widest + (pad.left + pad.right).toFloat()).coerceIn(8f, parentRect.width.coerceAtLeast(8f))
                val h = f?.h?.toFloat() ?: (wrappedLines * lineHeight + (pad.top + pad.bottom).toFloat()).coerceAtLeast(lineHeight)
                w to h
            }

            NodeKind.Code -> {
                val (nw, nh) = estimateCodeNaturalSize(node, themeSpec)
                (f?.w?.toFloat() ?: nw.toFloat()) to (f?.h?.toFloat() ?: nh.toFloat())
            }

            NodeKind.Stack, NodeKind.Row, NodeKind.Column, NodeKind.Loupe -> {
                (f?.w?.toFloat() ?: parentRect.width) to (f?.h?.toFloat() ?: parentRect.height)
            }

            else -> {
                (f?.w?.toFloat() ?: 240f) to (f?.h?.toFloat() ?: 120f)
            }
        }
    }

    private fun insetRect(rect: Rect, padding: PaddingSpec?, surfacePadding: PaddingSpec?): Rect {
        val l = ((padding?.left ?: 0.0) + (surfacePadding?.left ?: 0.0)).toFloat()
        val t = ((padding?.top ?: 0.0) + (surfacePadding?.top ?: 0.0)).toFloat()
        val r = ((padding?.right ?: 0.0) + (surfacePadding?.right ?: 0.0)).toFloat()
        val b = ((padding?.bottom ?: 0.0) + (surfacePadding?.bottom ?: 0.0)).toFloat()
        val left = rect.left + l
        val top = rect.top + t
        val right = (rect.right - r).coerceAtLeast(left)
        val bottom = (rect.bottom - b).coerceAtLeast(top)
        return Rect(left, top, right, bottom)
    }

    private fun resolveWorstTextForeground(
        text: dev.sebastiano.ciacola.document.TextPayload,
        roleColor: String?,
        slide: SlideSpec,
        document: DeckDocument,
        palette: SlidePaletteSpec?,
        themeSpec: DeckThemeSpec,
    ): Color? = resolveTextColor(text.color ?: roleColor, slide, document, palette, themeSpec)

    private fun resolveTextColor(
        rawColor: String?,
        slide: SlideSpec,
        document: DeckDocument,
        palette: SlidePaletteSpec?,
        themeSpec: DeckThemeSpec,
    ): Color? {
        if (!rawColor.isNullOrBlank()) {
            DocumentThemeResolver.resolveColor(rawColor, palette, themeSpec)?.let { return it }
        }
        return DocumentThemeResolver.resolveDefaultInkColor(slide, document, themeSpec)
    }

    /** Returns true when at least one non-blank run resolves to a visible foreground alpha. */
    fun hasVisibleTextColor(
        text: dev.sebastiano.ciacola.document.TextPayload,
        slide: SlideSpec,
        document: DeckDocument,
        themeSpec: DeckThemeSpec,
    ): Boolean {
        val palette = slide.palette?.let { document.palettes[it] }
        val roleColor = DocumentThemeResolver.resolveTypographyRole(text.style, themeSpec).color
        val baseFg = DocumentThemeResolver.resolveColor(text.color ?: roleColor, palette, themeSpec)
        return when (val value = text.value) {
            is dev.sebastiano.ciacola.document.RichTextValue.Plain ->
                value.text.isNotBlank() && (baseFg == null || baseFg.alpha > 0.05f)
            is dev.sebastiano.ciacola.document.RichTextValue.Runs -> value.runs.any { run ->
                if (run.text.isBlank()) {
                    false
                } else {
                    val runFg = if (run.color != null) {
                        DocumentThemeResolver.resolveColor(run.color, palette, themeSpec)
                    } else {
                        baseFg
                    }
                    runFg == null || runFg.alpha > 0.05f
                }
            }
        }
    }

    private fun resolveFillCanonical(fill: Fill?, palette: SlidePaletteSpec?, themeSpec: DeckThemeSpec): String? =
        when (fill) {
            null -> null
            is Fill.Solid -> DocumentThemeResolver.resolveColor(fill.color, palette, themeSpec)?.let(::colorHex) ?: fill.color
            is Fill.Gradient -> buildString {
                append("grad(").append(fill.gradient.type.name).append(',')
                for (stop in fill.gradient.stops) {
                    append(stop.at).append(':')
                    append(DocumentThemeResolver.resolveColor(stop.color, palette, themeSpec)?.let(::colorHex) ?: stop.color)
                    append(',')
                }
                append(')')
            }

            is Fill.Image -> "img(${fill.image.src},${fill.image.tint?.let { DocumentThemeResolver.resolveColor(it, palette, themeSpec)?.let(::colorHex) }})"
        }

    private fun resolveSurfaceCanonical(surface: SurfaceSpec?, palette: SlidePaletteSpec?, themeSpec: DeckThemeSpec): String? {
        if (surface == null) return null
        val f = resolveFillCanonical(surface.fill, palette, themeSpec)
        val s = surface.stroke?.color?.let { DocumentThemeResolver.resolveColor(it, palette, themeSpec)?.let(::colorHex) ?: it }
        return "$f|$s|${surface.cornerRadius}"
    }

    private fun colorHex(color: Color): String = "#%08X".format(color.toArgb())

    private fun rectsIntersectStrictly(a: Rect, b: Rect): Boolean =
        a.right > b.left + 0.5f &&
            a.left < b.right - 0.5f &&
            a.bottom > b.top + 0.5f &&
            a.top < b.bottom - 0.5f
}
