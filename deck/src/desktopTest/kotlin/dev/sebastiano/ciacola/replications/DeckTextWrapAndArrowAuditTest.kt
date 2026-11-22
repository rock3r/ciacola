package dev.sebastiano.ciacola.replications

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.decks.loadClasspathDeckDocument
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.render.DocumentColorResolver
import dev.sebastiano.ciacola.document.render.DocumentMotionEvaluator
import dev.sebastiano.ciacola.document.render.createDocumentFontResolver
import dev.sebastiano.ciacola.document.render.effectiveSlideTransition
import dev.sebastiano.ciacola.document.render.measureDocumentTextLayoutForAudit
import dev.sebastiano.ciacola.document.render.resolveActiveThemeSpec
import dev.sebastiano.ciacola.document.render.resolveDeckTheme
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DeckTextWrapAndArrowAuditTest {

    private data class WrapFinding(
        val deckId: String,
        val slideId: String,
        val slideTitle: String,
        val beat: Int,
        val nodeId: String,
        val text: String,
        val align: String?,
        val font: String?,
        val size: Double?,
        val frameX: Double?,
        val frameY: Double?,
        val frameW: Double?,
        val frameH: Double?,
        val maxWidthPx: Int,
        val naturalWidthPx: Float,
        val expectedLines: Int,
        val actualLines: Int,
        val lineTexts: List<String>,
    )

    @Test
    fun auditAllDecksForTextWrapsAndArrowGeometry() = runDesktopComposeUiTest(width = 1920, height = 1080) {
        lateinit var measurer: TextMeasurer
        setContent {
            measurer = rememberTextMeasurer(cacheSize = 0)
        }
        waitForIdle()

        val density = Density(1f, 1f)
        val findings = mutableListOf<WrapFinding>()
        val arrowFindings = mutableListOf<String>()
        val dottedCapFindings = mutableListOf<String>()

        for (entry in DeckCatalog.entries) {
            val doc: DeckDocument = loadClasspathDeckDocument(entry)
            val theme = resolveDeckTheme(doc, null)
            val themeSpec = resolveActiveThemeSpec(doc, null)
            val fontResolver = createDocumentFontResolver(doc)

            for (slide in doc.slides) {
                if (slide.skipped) continue
                val colorResolver = DocumentColorResolver(
                    themeSpec = themeSpec,
                    baseTheme = theme,
                    slidePaletteSpec = slide.palette?.let { doc.palettes[it] },
                )
                val beatCount = slide.beats.size.coerceAtLeast(1)
                val seenTextSigs = HashSet<String>()

                val slideTrans = effectiveSlideTransition(slide, themeSpec)
                for (beat in 0 until beatCount) {
                    fun visitNode(
                        rawNode: NodeSpec,
                        parentMaxWidthPx: Int,
                        insideFlexRow: Boolean,
                        weightedRowWidthPx: Int? = null,
                    ) {
                        if (!JsonMergePatch.isNodePresentAtBeat(rawNode, beat)) return
                        val node = JsonMergePatch.resolveNodeAtBeat(rawNode, beat)

                        if (node.kind == NodeKind.Shape) {
                            val shape = node.shape!!
                            val stroke = shape.stroke
                            if (stroke?.dash != null && stroke.dash!!.isNotEmpty() && stroke.dash!![0] <= 0.1 && stroke.cap == null) {
                                dottedCapFindings += "${doc.id}/${slide.id}/${node.id}"
                            }
                            val geom = shape.geometry
                            if (geom is ShapeGeometry.Path && shape.fill != null && stroke != null) {
                                // Split on M commands and check if any contour is a closed 3-vertex triangle alongside an open shaft
                                val contours = Regex("M[^M]+").findAll(geom.path.trim()).map { it.value.trim() }.toList()
                                val hasOpen = contours.any { !it.endsWith("Z", ignoreCase = true) }
                                val hasTri = contours.any { c ->
                                    c.endsWith("Z", ignoreCase = true) &&
                                        !c.contains("C", ignoreCase = true) &&
                                        Regex("[ML]").findAll(c).count() in 3..4
                                }
                                if (hasOpen && hasTri) {
                                    arrowFindings += "${doc.id}/${slide.id}/${node.id} contours=${contours.size} heads=${shape.heads}"
                                }
                            }
                        }

                        if (node.kind == NodeKind.Text && node.opacity > 0.001) {
                            val payload = node.text!!
                            val plain = payload.value.plainText
                            val wPx = node.frame?.w?.roundToInt()
                            val xPx = node.frame?.x?.roundToInt()
                            val hasExplicitPos = node.frame?.x != null || node.frame?.y != null
                            val nodeMaxW = when {
                                wPx != null -> wPx
                                weightedRowWidthPx != null -> weightedRowWidthPx
                                insideFlexRow -> Int.MAX_VALUE
                                hasExplicitPos -> (parentMaxWidthPx - (xPx ?: 0)).coerceAtLeast(0)
                                else -> parentMaxWidthPx
                            }
                            val padPx = payload.surface?.padding?.let { (it.left + it.right).roundToInt() } ?: 0
                            val innerMaxW = if (nodeMaxW == Int.MAX_VALUE) Int.MAX_VALUE else (nodeMaxW - padPx).coerceAtLeast(1)
                            val usesCharDelivery = DocumentMotionEvaluator.isCharacterDeliveryNode(node, slideTrans)
                            val ligatureVariants = if (usesCharDelivery) listOf(false, true) else listOf(false)

                            for (disableLig in ligatureVariants) {
                                val sig = "${node.id}|$plain|$innerMaxW|${payload.size}|${payload.font}|${payload.weight}|${payload.tracking}|lig=$disableLig"
                                if (seenTextSigs.add(sig) && plain.isNotEmpty()) {
                                    val layout = measureDocumentTextLayoutForAudit(
                                        payload = payload,
                                        document = doc,
                                        theme = theme,
                                        colorResolver = colorResolver,
                                        fontResolver = fontResolver,
                                        density = density,
                                        measurer = measurer,
                                        maxWidthPx = innerMaxW,
                                        disableLigatures = disableLig,
                                    )
                                    val cleanPlain = plain.trimEnd('\n')
                                    val explicitLineCount = if (cleanPlain.isEmpty()) 1 else cleanPlain.count { it == '\n' } + 1
                                    val actualLineCount = layout.lineCount
                                    if (actualLineCount > explicitLineCount) {
                                        val naturalLayout = measureDocumentTextLayoutForAudit(
                                            payload = payload,
                                            document = doc,
                                            theme = theme,
                                            colorResolver = colorResolver,
                                            fontResolver = fontResolver,
                                            density = density,
                                            measurer = measurer,
                                            maxWidthPx = Int.MAX_VALUE,
                                            disableLigatures = disableLig,
                                        )
                                        val natW = (0 until naturalLayout.lineCount).maxOfOrNull { naturalLayout.getLineRight(it) } ?: 0f
                                        val lines = (0 until actualLineCount).map { li ->
                                            val s = layout.getLineStart(li).coerceIn(0, plain.length)
                                            val e = layout.getLineEnd(li, visibleEnd = true).coerceIn(s, plain.length)
                                            plain.substring(s, e).replace("\n", "\\n")
                                        }
                                        findings += WrapFinding(
                                            deckId = doc.id,
                                            slideId = slide.id,
                                            slideTitle = slide.title ?: "",
                                            beat = beat,
                                            nodeId = node.id,
                                            text = plain.replace("\n", "\\n"),
                                            align = payload.align,
                                            font = payload.font,
                                            size = payload.size,
                                            frameX = node.frame?.x,
                                            frameY = node.frame?.y,
                                            frameW = node.frame?.w,
                                            frameH = node.frame?.h,
                                            maxWidthPx = innerMaxW,
                                            naturalWidthPx = natW,
                                            expectedLines = explicitLineCount,
                                            actualLines = actualLineCount,
                                            lineTexts = lines,
                                        )
                                    }

                                    // Diagnostic glyph boxes for shaders-quickie-2025 s16
                                    if (!disableLig && doc.id == "shaders-quickie-2025" && slide.id == "s16" &&
                                        plain in setOf("half4", "float2", "half", "float", "4", "2")
                                    ) {
                                        val boxes = (plain.indices).map { ci ->
                                            val b = layout.getBoundingBox(ci)
                                            "${plain[ci]}:[${b.left},${b.top},${b.right},${b.bottom}]"
                                        }
                                        println(
                                            "S16_DIAG beat=$beat id=${node.id} text='$plain' frame=(${node.frame?.x},${node.frame?.y},${node.frame?.w},${node.frame?.h}) " +
                                                "lines=$actualLineCount baseline=${layout.getLineBaseline(0)} size=${layout.size} boxes=$boxes",
                                        )
                                    }
                                }
                            }
                        }

                        val childParentW = node.frame?.w?.roundToInt() ?: parentMaxWidthPx
                        val childFlexRow = node.kind == NodeKind.Row
                        val pad = when (node.kind) {
                            NodeKind.Stack -> (node.stack?.padding?.let { it.left + it.right } ?: 0.0) + (node.stack?.surface?.padding?.let { it.left + it.right } ?: 0.0)
                            NodeKind.Row -> (node.row?.padding?.let { it.left + it.right } ?: 0.0) + (node.row?.surface?.padding?.let { it.left + it.right } ?: 0.0)
                            NodeKind.Column -> (node.column?.padding?.let { it.left + it.right } ?: 0.0) + (node.column?.surface?.padding?.let { it.left + it.right } ?: 0.0)
                            else -> 0.0
                        }
                        val availW = (childParentW - pad.roundToInt()).coerceAtLeast(1)
                        val weightedChildWidths: Map<String, Int> = if (childFlexRow) {
                            val presentChildren = node.children
                                .filter { JsonMergePatch.isNodePresentAtBeat(it, beat) }
                                .map { JsonMergePatch.resolveNodeAtBeat(it, beat) }
                            val gapTotal = (node.row?.gap ?: 0.0) * (presentChildren.size - 1).coerceAtLeast(0)
                            val fixedSum = presentChildren.sumOf { ch ->
                                if ((ch.weight ?: 0.0) <= 0.0) (ch.frame?.w ?: 0.0) else 0.0
                            }
                            val totalWeight = presentChildren.sumOf { (it.weight ?: 0.0).coerceAtLeast(0.0) }
                            val remaining = (availW.toDouble() - gapTotal - fixedSum).coerceAtLeast(1.0)
                            if (totalWeight > 0.0) {
                                presentChildren.mapNotNull { ch ->
                                    val w = ch.weight ?: 0.0
                                    if (w > 0.0) ch.id to ((remaining * w / totalWeight).roundToInt().coerceAtLeast(1)) else null
                                }.toMap()
                            } else {
                                emptyMap()
                            }
                        } else {
                            emptyMap()
                        }
                        for (child in node.children) {
                            visitNode(child, availW, childFlexRow, weightedChildWidths[child.id])
                        }
                    }

                    for (root in slide.nodes) {
                        visitNode(root, doc.stage.width, false)
                    }
                }
            }
        }

        println("AUDIT_SUMMARY: wrapFindings=${findings.size}, arrowCandidates=${arrowFindings.size}, dottedWithoutCap=${dottedCapFindings.size}")
        for (f in findings) {
            val neededW = ceil(f.naturalWidthPx).toInt()
            println(
                "WRAP: ${f.deckId} ${f.slideId}(${f.slideTitle}) b=${f.beat} ${f.nodeId} " +
                    "expected=${f.expectedLines} actual=${f.actualLines} frameW=${f.frameW} maxW=${f.maxWidthPx} natW=${f.naturalWidthPx} (need=$neededW) " +
                    "align=${f.align} font=${f.font} size=${f.size} text='${f.text}' lines=${f.lineTexts}",
            )
        }
        val uniqueArrows = arrowFindings.distinct()
        val uniqueCaps = dottedCapFindings.distinct()
        for (a in uniqueArrows) println("ARROW_CANDIDATE: $a")
        for (d in uniqueCaps) println("DOTTED_NO_CAP: $d")

        // Deliberately multi-line prose/headline blocks in the hand-authored `foundation` showcase deck.
        val intentionalMultiLineWraps = setOf(
            "foundation/beats/s1:prose",
            "foundation/keynote-effects-lab/s5:headline",
        )
        val unintendedWraps = findings.filter { "${it.deckId}/${it.slideId}/${it.nodeId}" !in intentionalMultiLineWraps }
        assertEquals(
            emptyList(),
            unintendedWraps.map { "${it.deckId}/${it.slideId}/${it.nodeId}: ${it.lineTexts}" },
            "All unintended Skia soft-wraps across all 264 slides must be eliminated",
        )
        assertEquals(
            emptyList(),
            uniqueArrows,
            "All concatenated OPEN+TRI arrow shapes across all 4 decks must be converted to heads",
        )
        assertEquals(
            emptyList(),
            uniqueCaps,
            "All dotted strokes (dash[0] <= 0.1) must specify cap",
        )

        // Permanent audit: zero accidental MagicMove ID collisions across all 4 decks
        val allowedCodeTextMorphs = setOf(
            "shaders-quickie-2025/s26->s27/return-half4-uv-x-uv-y",
            "shaders-quickie-2025/s38->s39/vec2-uv-fragcoord-xy-iresolu",
        )
        val mmCollisions = mutableListOf<String>()
        for (entry in DeckCatalog.entries) {
            val doc = loadClasspathDeckDocument(entry)
            val themeSpec = resolveActiveThemeSpec(doc, null)
            val nonSkipped = doc.slides.filterNot { it.skipped }
            for (i in 0 until nonSkipped.lastIndex) {
                val sa = nonSkipped[i]
                val sb = nonSkipped[i + 1]
                val trans = effectiveSlideTransition(sa, themeSpec)
                if (trans.type != TransitionType.MagicMove) continue
                val fromLastBeat = sa.beats.lastIndex.coerceAtLeast(0)
                val mapA = sa.nodes
                    .filter { JsonMergePatch.isNodePresentAtBeat(it, fromLastBeat) }
                    .map { JsonMergePatch.resolveNodeAtBeat(it, fromLastBeat) }
                    .associateBy { it.id }
                for (rawB in sb.nodes) {
                    if (!JsonMergePatch.isNodePresentAtStart(rawB)) continue
                    val nb = JsonMergePatch.resolveNodeAtBeat(rawB, 0)
                    val na = mapA[nb.id] ?: continue
                    if (na.kind != nb.kind) continue
                    when (na.kind) {
                        NodeKind.Text -> {
                            val ta = na.text!!.value.plainText.replace(Regex("\\s+"), " ").trim()
                            val tb = nb.text!!.value.plainText.replace(Regex("\\s+"), " ").trim()
                            val key = "${doc.id}/${sa.id}->${sb.id}/${nb.id}"
                            if (ta != tb && key !in allowedCodeTextMorphs) {
                                mmCollisions += "$key text='$ta' vs '$tb'"
                            }
                        }
                        NodeKind.Shape -> {
                            val fa = (na.shape?.fill as? Fill.Solid)?.color
                            val fb = (nb.shape?.fill as? Fill.Solid)?.color
                            val ga = na.shape?.geometry
                            val gb = nb.shape?.geometry
                            if (fa != fb || (ga != null && gb != null && ga::class != gb::class)) {
                                mmCollisions += "${doc.id}/${sa.id}->${sb.id}/${nb.id} shape fill=$fa/$fb geom=${ga?.let { it::class.simpleName }}/${gb?.let { it::class.simpleName }}"
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
        assertEquals(
            emptyList(),
            mmCollisions,
            "All cross-slide MagicMove ID matches must have compatible text and shape fill/geometry",
        )
    }
}
