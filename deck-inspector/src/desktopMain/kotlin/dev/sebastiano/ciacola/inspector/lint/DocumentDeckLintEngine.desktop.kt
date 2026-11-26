package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import dev.sebastiano.ciacola.animation.AnimationBudget
import dev.sebastiano.ciacola.animation.probeAnimation
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.BuildEffectRegistry
import dev.sebastiano.ciacola.document.CrossSlideGeometry
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.DocumentIssue
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.ImagePayload
import dev.sebastiano.ciacola.document.IssueSeverity
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.SegmentPlanner
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Reduced resolution for pixel-fallback contrast audits (16x fewer pixels than 1920x1080). */
const val LINT_AUDIT_WIDTH: Int = 480
const val LINT_AUDIT_HEIGHT: Int = 270
const val DEFAULT_LINT_SLICE_BUDGET_MS: Double = 8.0

enum class DocumentLintSource {
    StaticValidation,
    StaticContrast,
    PixelContrast480x270,
    Layout,
    CrossSlide,
    Provenance,
    TodoVideo,
}

@Immutable
data class DocumentLintIssue(
    val id: String,
    val code: String,
    val severity: LintSeverity,
    val source: DocumentLintSource,
    val slideIndex: Int,
    val slideId: String,
    val beatIndex: Int = 0,
    val nodeId: String? = null,
    val themeName: String? = null,
    val title: String,
    val detail: String,
    val stageBounds: Rect = Rect.Zero,
)

@Immutable
data class DocumentDeckLintStats(
    val slidesAudited: Int = 0,
    val beatsAudited: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val staticContrastChecks: Int = 0,
    val pixelContrastRenders: Int = 0,
    val unknownContrastCount: Int = 0,
    val themeDeduplicatedSlides: Int = 0,
    val sliceDurationsMs: List<Double> = emptyList(),
    val totalDurationMs: Double = 0.0,
    val estimatedCacheMemoryBytes: Long = 0L,
) {
    val p95UiSliceMs: Double
        get() {
            if (sliceDurationsMs.isEmpty()) return 0.0
            val sorted = sliceDurationsMs.sorted()
            val idx = ((sorted.size - 1) * 0.95).roundToInt().coerceIn(0, sorted.lastIndex)
            return sorted[idx]
        }

    val maxUiSliceMs: Double
        get() = sliceDurationsMs.maxOrNull() ?: 0.0
}

@Immutable
data class DocumentLintReport(
    val issues: List<DocumentLintIssue>,
    val stats: DocumentDeckLintStats,
)

/**
 * Fast, cached, time-sliced, cancellable DeckLint engine.
 *
 * Key properties:
 * - Static validation + `todo.video` + cross-slide flow-ancestor checks + external-edit provenance +
 *   code natural-size overflow checks.
 * - **Static contrast fast-path:** computes WCAG 2.1 contrast analytically in `< 0.1 ms`
 *   with ZERO pixel readback for eligible text nodes.
 * - **Real `480x270` UI-thread pixel fallback:** renders the actual slide through
 *   [buildDocumentStoryboard] in an isolated [RenderSession.forStill] inside a single reused
 *   `ImageComposeScene(480, 270, Density(0.5f))` with text glyphs made transparent so real images,
 *   gradients, shapes, surfaces, and live SkSL/Jewel specimens are sampled accurately — reporting
 *   `CONTRAST_UNKNOWN` whenever a backdrop cannot be rendered or resolved (never inventing colors).
 * - **Smart theme deduplication:** skips redundant theme audits ONLY when the slide's fully
 *   resolved node styles hash identically under both themes.
 * - **Per-slide content-addressed cache:** keyed by `SegmentPlanner.slideLintCacheKey(...)`
 *   including referenced asset content fingerprints (`size:mtime:sha256`) and font resolver key.
 */
class DocumentDeckLintEngine(
    private val specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
    private val effects: BuildEffectRegistry = BuildEffectRegistry.Standard,
    private val fontResolverTag: String = "default",
    private val maxCacheEntries: Int = 1_024,
) : AutoCloseable {

    private data class CachedSlideResult(
        val issues: List<DocumentLintIssue>,
        val staticContrastChecks: Int,
        val pixelContrastRenders: Int,
        val unknownContrastCount: Int,
    )

    private data class AssetStatCacheEntry(
        val size: Long,
        val mtimeMs: Long,
        val fingerprint: String,
    )

    private val slideCache = LinkedHashMap<String, CachedSlideResult>(256, 0.75f, true)
    private val assetStatCache = HashMap<Path, AssetStatCacheEntry>()
    private val backdropRenderer = DocumentDeckLintBackdropRenderer(specimens)

    fun clearCache() {
        slideCache.clear()
        assetStatCache.clear()
    }

    fun cacheEntryCount(): Int = slideCache.size

    fun estimateCacheMemoryBytes(): Long {
        var bytes = 64L * slideCache.size
        for ((key, value) in slideCache) {
            bytes += key.length * 2L + 48L
            for (issue in value.issues) {
                bytes += 160L + (issue.id.length + issue.title.length + issue.detail.length) * 2L
            }
        }
        // Account for 480x270 bitmap if allocated (~518 KB).
        if (backdropRenderer.hasAllocatedBitmap) {
            bytes += LINT_AUDIT_WIDTH.toLong() * LINT_AUDIT_HEIGHT.toLong() * 4L
        }
        return bytes
    }

    /**
     * Audits a single slide across [themeNames] (defaults to the document's active theme).
     * Designed to complete well within a `<= 300 ms` interactive budget.
     */
    suspend fun lintSingleSlide(
        document: DeckDocument,
        slideIndex: Int,
        themeNames: List<String> = listOf(DocumentThemeResolver.resolveActiveThemeName(document)),
        deckDir: Path? = null,
        assetsDir: Path? = null,
        externalEditWarnings: List<DocumentIssue> = emptyList(),
    ): DocumentLintReport {
        val startNs = System.nanoTime()
        if (slideIndex !in document.slides.indices) {
            return DocumentLintReport(emptyList(), DocumentDeckLintStats())
        }
        val slide = document.slides[slideIndex]
        val resolvedThemes = themeNames.ifEmpty { listOf(DocumentThemeResolver.resolveActiveThemeName(document)) }.distinct()

        val allIssues = LinkedHashMap<String, DocumentLintIssue>()
        var cacheHits = 0
        var cacheMisses = 0
        var staticChecks = 0
        var pixelRenders = 0
        var unknownCount = 0
        var dedupCount = 0

        val assetExistsFn = buildAssetExistsFn(document.id, deckDir, assetsDir)
        val staticIssues = DeckDocumentCodec.validate(document, specimens, effects, assetExistsFn)
        for (docIssue in staticIssues) {
            if (docIssue.address.contains(slide.id) || docIssue.address == "<root>") {
                val mapped = mapDocumentIssue(docIssue, document, defaultSlideIndex = slideIndex)
                allIssues.putIfAbsent(mapped.id, mapped)
            }
        }
        collectTodoVideoIssues(slide, slideIndex, allIssues)
        collectClipIssues(document.id, slide, slideIndex, deckDir, assetsDir, allIssues)
        collectCrossSlideIssuesForNeighbor(document, slideIndex, allIssues)
        for (ext in externalEditWarnings) {
            if (ext.address.contains(slide.id)) {
                val mapped = mapDocumentIssue(ext, document, defaultSlideIndex = slideIndex, source = DocumentLintSource.Provenance)
                allIssues.putIfAbsent(mapped.id, mapped)
            }
        }

        val fingerprints = computeAssetFingerprints(deckDir, document, slideIndex, assetsDir = assetsDir)
        val seenResolvedHashes = HashSet<String>()

        for (themeName in resolvedThemes) {
            currentCoroutineContext().ensureActive()
            val styleHash = DocumentDeckLintLayout.computeResolvedSlideStyleHash(slide, document, themeName)
            if (!seenResolvedHashes.add(styleHash)) {
                dedupCount++
                continue
            }
            val cacheKey = SegmentPlanner.slideLintCacheKey(
                document = document,
                slideIndex = slideIndex,
                themeName = themeName,
                specimens = specimens,
                assetFingerprints = fingerprints,
                fontResolverTag = fontResolverTag,
            )
            val cached = slideCache[cacheKey]
            val result = if (cached != null) {
                cacheHits++
                cached
            } else {
                cacheMisses++
                val computed = computeSlideVisualAndLayoutLint(
                    document = document,
                    slide = slide,
                    slideIndex = slideIndex,
                    themeName = themeName,
                    deckDir = deckDir,
                    assetsDir = assetsDir,
                )
                putCachedSlide(cacheKey, computed)
                computed
            }
            staticChecks += result.staticContrastChecks
            pixelRenders += result.pixelContrastRenders
            unknownCount += result.unknownContrastCount
            for (issue in result.issues) {
                allIssues.putIfAbsent(issue.id, issue)
            }
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        return DocumentLintReport(
            issues = sortIssues(allIssues.values),
            stats = DocumentDeckLintStats(
                slidesAudited = 1,
                beatsAudited = slide.beats.size.coerceAtLeast(1),
                cacheHits = cacheHits,
                cacheMisses = cacheMisses,
                staticContrastChecks = staticChecks,
                pixelContrastRenders = pixelRenders,
                unknownContrastCount = unknownCount,
                themeDeduplicatedSlides = dedupCount,
                sliceDurationsMs = listOf(elapsedMs),
                totalDurationMs = elapsedMs,
                estimatedCacheMemoryBytes = estimateCacheMemoryBytes(),
            ),
        )
    }

    /**
     * Audits the full deck on the UI thread in time-sliced, cancellable chunks (`sliceBudgetMs`
     * target per frame).
     */
    suspend fun lintFullDeck(
        document: DeckDocument,
        themeNames: List<String> = document.themes.keys.toList()
            .ifEmpty { listOf(DocumentThemeResolver.resolveActiveThemeName(document)) },
        deckDir: Path? = null,
        assetsDir: Path? = null,
        externalEditWarnings: List<DocumentIssue> = emptyList(),
        sliceBudgetMs: Double = DEFAULT_LINT_SLICE_BUDGET_MS,
        onProgress: (Float) -> Unit = {},
    ): DocumentLintReport {
        val totalStartNs = System.nanoTime()
        val resolvedThemes = themeNames.ifEmpty { listOf(DocumentThemeResolver.resolveActiveThemeName(document)) }.distinct()
        val sliceDurations = mutableListOf<Double>()
        var sliceStartNs = System.nanoTime()
        // Pace UI-thread full-deck audits with Compose frames when a MonotonicFrameClock is active,
        // while keeping headless coroutine tests non-blocking via yield().
        val hasFrameClock = currentCoroutineContext()[MonotonicFrameClock] != null

        suspend fun maybeYieldSlice(force: Boolean = false) {
            val elapsedMs = (System.nanoTime() - sliceStartNs) / 1_000_000.0
            if (force || elapsedMs >= sliceBudgetMs) {
                sliceDurations += elapsedMs
                if (hasFrameClock) withFrameNanos {} else yield()
                currentCoroutineContext().ensureActive()
                sliceStartNs = System.nanoTime()
            }
        }

        val allIssues = LinkedHashMap<String, DocumentLintIssue>()
        var cacheHits = 0
        var cacheMisses = 0
        var staticChecks = 0
        var pixelRenders = 0
        var unknownCount = 0
        var dedupCount = 0
        var beatsAudited = 0

        currentCoroutineContext().ensureActive()

        // 1. Document-wide static validation (fast pure check).
        val assetExistsFn = buildAssetExistsFn(document.id, deckDir, assetsDir)
        val validationIssues = DeckDocumentCodec.validate(document, specimens, effects, assetExistsFn)
        for (docIssue in validationIssues) {
            val mapped = mapDocumentIssue(docIssue, document, defaultSlideIndex = 0)
            allIssues.putIfAbsent(mapped.id, mapped)
        }
        for (ext in externalEditWarnings) {
            val mapped = mapDocumentIssue(ext, document, defaultSlideIndex = 0, source = DocumentLintSource.Provenance)
            allIssues.putIfAbsent(mapped.id, mapped)
        }
        maybeYieldSlice()

        // 2. Cross-slide pairing lint across consecutive non-skipped slides.
        val activeIndices = document.slides.indices.filter { !document.slides[it].skipped }
        for (k in 0 until activeIndices.lastIndex) {
            currentCoroutineContext().ensureActive()
            val idxA = activeIndices[k]
            val idxB = activeIndices[k + 1]
            val match = CrossSlideGeometry.matchCrossSlidePairs(
                fromSlide = document.slides[idxA],
                toSlide = document.slides[idxB],
                stage = document.stage,
            )
            for (issue in match.lintIssues) {
                val mapped = mapDocumentIssue(issue, document, defaultSlideIndex = idxA, source = DocumentLintSource.CrossSlide)
                allIssues.putIfAbsent(mapped.id, mapped)
            }
            maybeYieldSlice()
        }

        // 3. Per-slide layout + static contrast + real 480x270 pixel fallback, with per-slide caching
        // and smart resolved-style theme deduplication.
        val totalSlides = document.slides.size.coerceAtLeast(1)
        for (slideIndex in document.slides.indices) {
            currentCoroutineContext().ensureActive()
            val slide = document.slides[slideIndex]
            if (slide.skipped) {
                onProgress((slideIndex + 1).toFloat() / totalSlides.toFloat())
                continue
            }

            collectTodoVideoIssues(slide, slideIndex, allIssues)
            collectClipIssues(document.id, slide, slideIndex, deckDir, assetsDir, allIssues)
            val fingerprints = computeAssetFingerprints(deckDir, document, slideIndex, assetsDir = assetsDir)
            val seenStyleHashes = HashSet<String>()

            for (themeName in resolvedThemes) {
                currentCoroutineContext().ensureActive()
                val styleHash = DocumentDeckLintLayout.computeResolvedSlideStyleHash(slide, document, themeName)
                if (!seenStyleHashes.add(styleHash)) {
                    dedupCount++
                    continue
                }

                val cacheKey = SegmentPlanner.slideLintCacheKey(
                    document = document,
                    slideIndex = slideIndex,
                    themeName = themeName,
                    specimens = specimens,
                    assetFingerprints = fingerprints,
                    fontResolverTag = fontResolverTag,
                )
                val cached = slideCache[cacheKey]
                val result = if (cached != null) {
                    cacheHits++
                    cached
                } else {
                    cacheMisses++
                    val computed = computeSlideVisualAndLayoutLint(
                        document = document,
                        slide = slide,
                        slideIndex = slideIndex,
                        themeName = themeName,
                        deckDir = deckDir,
                        assetsDir = assetsDir,
                        onBeforePixelRender = { maybeYieldSlice() },
                        onAfterPixelRender = { maybeYieldSlice(force = true) },
                    )
                    putCachedSlide(cacheKey, computed)
                    computed
                }
                staticChecks += result.staticContrastChecks
                pixelRenders += result.pixelContrastRenders
                unknownCount += result.unknownContrastCount
                beatsAudited += slide.beats.size.coerceAtLeast(1)
                for (issue in result.issues) {
                    allIssues.putIfAbsent(issue.id, issue)
                }
                maybeYieldSlice()
            }

            onProgress((slideIndex + 1).toFloat() / totalSlides.toFloat())
            maybeYieldSlice(force = hasFrameClock)
        }

        val tailSliceMs = (System.nanoTime() - sliceStartNs) / 1_000_000.0
        if (tailSliceMs > 0.0) sliceDurations += tailSliceMs
        val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000.0

        return DocumentLintReport(
            issues = sortIssues(allIssues.values),
            stats = DocumentDeckLintStats(
                slidesAudited = document.slides.count { !it.skipped },
                beatsAudited = beatsAudited,
                cacheHits = cacheHits,
                cacheMisses = cacheMisses,
                staticContrastChecks = staticChecks,
                pixelContrastRenders = pixelRenders,
                unknownContrastCount = unknownCount,
                themeDeduplicatedSlides = dedupCount,
                sliceDurationsMs = sliceDurations,
                totalDurationMs = totalMs,
                estimatedCacheMemoryBytes = estimateCacheMemoryBytes(),
            ),
        )
    }

    /**
     * Computes content fingerprints (`size:mtimeMs:sha256`) for every asset referenced by
     * `document.slides[slideIndex]` (and declared document fonts).
     */
    fun computeAssetFingerprints(
        deckDir: Path?,
        document: DeckDocument,
        slideIndex: Int,
        assetsDir: Path? = null,
    ): Map<String, String> {
        if ((deckDir == null && assetsDir == null) || slideIndex !in document.slides.indices) return emptyMap()
        val slide = document.slides[slideIndex]
        val refs = LinkedHashSet<String>()

        fun collectFill(fill: Fill?) {
            if (fill is Fill.Image) refs += fill.image.src
        }
        fun collectNode(node: NodeSpec) {
            node.image?.let { refs += it.src }
            collectFill(node.text?.surface?.fill)
            collectFill(node.code?.surface?.fill)
            collectFill(node.shape?.fill)
            collectFill(node.stack?.surface?.fill)
            collectFill(node.row?.surface?.fill)
            collectFill(node.column?.surface?.fill)
            collectFill(node.loupe?.lensFill)
            for (c in node.children) collectNode(c)
        }
        collectFill(slide.background)
        slide.nodes.forEach(::collectNode)
        document.fonts.forEach { f -> f.src?.let { refs += it } }

        val out = LinkedHashMap<String, String>()
        for (rel in refs) {
            val file = resolveAssetPathOnDisk(document.id, rel, deckDir, assetsDir)
            if (file == null || !Files.isRegularFile(file)) {
                out[rel] = if (hasClasspathAsset(document.id, rel)) "classpath" else "missing"
                continue
            }
            val size = runCatching { Files.size(file) }.getOrDefault(-1L)
            val mtime = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L)
            val cached = assetStatCache[file]
            if (cached != null && cached.size == size && cached.mtimeMs == mtime) {
                out[rel] = cached.fingerprint
            } else {
                val hash = runCatching {
                    val bytes = Files.readAllBytes(file)
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString(separator = "") { b -> "%02x".format(b) }
                }.getOrDefault("err")
                val fp = "$size:$mtime:$hash"
                assetStatCache[file] = AssetStatCacheEntry(size, mtime, fp)
                out[rel] = fp
            }
        }
        return out
    }

    override fun close() {
        backdropRenderer.close()
    }

    // ── Per-slide visual + layout lint ──────────────────────────────────────────────────────────

    private suspend fun computeSlideVisualAndLayoutLint(
        document: DeckDocument,
        slide: SlideSpec,
        slideIndex: Int,
        themeName: String,
        deckDir: Path? = null,
        assetsDir: Path? = null,
        onBeforePixelRender: suspend () -> Unit = {},
        onAfterPixelRender: suspend () -> Unit = {},
    ): CachedSlideResult {
        val themeSpec = DocumentThemeResolver.resolveThemeSpec(document, themeName)
        val stageW = document.stage.width.toFloat().coerceAtLeast(1f)
        val stageH = document.stage.height.toFloat().coerceAtLeast(1f)
        val beatCount = slide.beats.size.coerceAtLeast(1)

        val issues = mutableListOf<DocumentLintIssue>()
        var staticChecks = 0
        var pixelRenders = 0
        var unknownCount = 0

        for (beat in 0 until beatCount) {
            currentCoroutineContext().ensureActive()
            val laidOut = DocumentDeckLintLayout.layoutSlideAtBeat(slide, beat, document, themeSpec)
            val textEntries = laidOut.filter { entry ->
                val text = entry.node.text
                text != null &&
                    text.value.plainText.isNotBlank() &&
                    entry.effectiveOpacity > 0.05 &&
                    DocumentDeckLintLayout.hasVisibleTextColor(text, slide, document, themeSpec)
            }
            val pendingPixelEntries = mutableListOf<Pair<LaidOutNode, StaticContrastDecision.RequiresPixelRender>>()

            // Check layout rules for every present node.
            for (entry in laidOut) {
                val n = entry.node
                // 1. Out-of-bounds & safe-area checks for non-full-bleed leaf content.
                val isFullBleed = entry.bounds.width >= stageW * 0.96f && entry.bounds.height >= stageH * 0.96f
                if (!isFullBleed && (n.text != null || n.code != null)) {
                    checkBounds(slide, slideIndex, beat, entry, stageW, stageH, issues)
                }

                // 2. Code node natural size exceeding fixed box.
                if (n.code != null && n.frame?.w != null && n.frame?.h != null) {
                    val (natW, natH) = DocumentDeckLintLayout.estimateCodeNaturalSize(n, themeSpec)
                    if (natW > n.frame!!.w!! + 4.0 || natH > n.frame!!.h!! + 4.0) {
                        issues += DocumentLintIssue(
                            id = "code-overflow:${slide.id}:$beat:${n.id}",
                            code = "CODE_NATURAL_SIZE_EXCEEDS_BOX",
                            severity = LintSeverity.Warning,
                            source = DocumentLintSource.Layout,
                            slideIndex = slideIndex,
                            slideId = slide.id,
                            beatIndex = beat,
                            nodeId = n.id,
                            themeName = themeName,
                            title = "Code content exceeds fixed box",
                            detail = "Natural size ${natW.roundToInt()}×${natH.roundToInt()} exceeds fixed frame " +
                                "${n.frame!!.w!!.roundToInt()}×${n.frame!!.h!!.roundToInt()}.",
                            stageBounds = entry.bounds,
                        )
                    }
                }

                // 3. Text tiny-size + static contrast fast-path.
                if (n.text != null) {
                    when (val decision = DocumentDeckLintLayout.evaluateStaticContrast(entry, laidOut, slide, document, themeSpec)) {
                        is StaticContrastDecision.Eligible -> {
                            staticChecks++
                            checkTinyText(slide, slideIndex, beat, entry, decision.fontSizeUnits, issues)
                            if (decision.ratio < decision.requiredRatio) {
                                issues += lowContrastIssue(
                                    slide = slide,
                                    slideIndex = slideIndex,
                                    beat = beat,
                                    entry = entry,
                                    themeName = themeName,
                                    source = DocumentLintSource.StaticContrast,
                                    ratio = decision.ratio,
                                    requiredRatio = decision.requiredRatio,
                                    note = "static analytical contrast",
                                )
                            }
                        }

                        is StaticContrastDecision.RequiresPixelRender -> {
                            checkTinyText(slide, slideIndex, beat, entry, decision.fontSizeUnits, issues)
                            pendingPixelEntries += entry to decision
                        }

                        is StaticContrastDecision.Unknown -> {
                            unknownCount++
                        }
                    }
                }
            }

            // 4. Text-to-text overlap check.
            for (i in 0 until textEntries.size) {
                for (j in i + 1 until textEntries.size) {
                    val a = textEntries[i]
                    val b = textEntries[j]
                    if (a.node.id in b.ancestorIds || b.node.id in a.ancestorIds) continue
                    val inter = a.bounds.intersect(b.bounds)
                    if (inter.width > 1f && inter.height > 1f) {
                        val area = inter.width * inter.height
                        val minArea = min(a.bounds.width * a.bounds.height, b.bounds.width * b.bounds.height).coerceAtLeast(1f)
                        if (area / minArea >= OVERLAP_THRESHOLD_FRACTION) {
                            issues += DocumentLintIssue(
                                id = "overlap:${slide.id}:$beat:${a.node.id}:${b.node.id}",
                                code = "TEXT_OVERLAP",
                                severity = LintSeverity.Warning,
                                source = DocumentLintSource.Layout,
                                slideIndex = slideIndex,
                                slideId = slide.id,
                                beatIndex = beat,
                                nodeId = b.node.id,
                                themeName = themeName,
                                title = "Overlapping text nodes",
                                detail = "'${a.node.id}' and '${b.node.id}' overlap by ${((area / minArea) * 100f).roundToInt()}%.",
                                stageBounds = inter,
                            )
                        }
                    }
                }
            }

            // 5. Real 480x270 slide render via DocumentStoryboardHost ONLY for ineligible text nodes.
            if (pendingPixelEntries.isNotEmpty()) {
                onBeforePixelRender()
                currentCoroutineContext().ensureActive()

                val rendered = backdropRenderer.renderRealSlideBackdropBuffer(
                    document = document,
                    slide = slide,
                    beat = beat,
                    themeName = themeName,
                    deckDir = deckDir,
                    assetsDir = assetsDir,
                )
                pixelRenders++
                onAfterPixelRender()

                data class DeferredOverlaySample(
                    val entry: LaidOutNode,
                    val req: StaticContrastDecision.RequiresPixelRender,
                    val fg: androidx.compose.ui.graphics.Color,
                    val sampleRect: Rect,
                    val overlayIds: Set<String>,
                )

                val deferredOverlaySamples = mutableListOf<DeferredOverlaySample>()

                fun recordSampledContrast(
                    entry: LaidOutNode,
                    req: StaticContrastDecision.RequiresPixelRender,
                    sampleRect: Rect,
                    sampledRatio: Float?,
                ) {
                    if (sampledRatio != null) {
                        if (sampledRatio < req.requiredRatio) {
                            issues += lowContrastIssue(
                                slide = slide,
                                slideIndex = slideIndex,
                                beat = beat,
                                entry = entry.copy(bounds = sampleRect),
                                themeName = themeName,
                                source = DocumentLintSource.PixelContrast480x270,
                                ratio = sampledRatio,
                                requiredRatio = req.requiredRatio,
                                note = "real 480x270 render (${req.reason})",
                            )
                        }
                    } else {
                        unknownCount++
                        issues += contrastUnknownIssue(
                            slide = slide,
                            slideIndex = slideIndex,
                            beat = beat,
                            entry = entry,
                            themeName = themeName,
                            reason = "Sample region outside 480x270 framebuffer (${req.reason})",
                        )
                    }
                }

                for ((entry, req) in pendingPixelEntries) {
                    val fg = req.foreground
                    val missingBackdropReason = findMissingBackdropAssetReason(
                        entry = entry,
                        allNodes = laidOut,
                        slide = slide,
                        deckId = document.id,
                        deckDir = deckDir,
                        assetsDir = assetsDir,
                    )
                    if (rendered == null || fg == null || missingBackdropReason != null) {
                        unknownCount++
                        issues += contrastUnknownIssue(
                            slide = slide,
                            slideIndex = slideIndex,
                            beat = beat,
                            entry = entry,
                            themeName = themeName,
                            reason = missingBackdropReason ?: if (rendered == null) {
                                "Real 480x270 slide render failed (${req.reason})"
                            } else {
                                "Foreground color unresolved (${req.reason})"
                            },
                        )
                        continue
                    }

                    val sampleRect = rendered.glyphBoundsByNodeId[entry.node.id]
                        ?.takeIf { it.width > 1f && it.height > 1f }
                        ?: entry.bounds
                    val overlayIds = laidOut.asSequence()
                        .filter { other ->
                            other.paintOrder > entry.paintOrder &&
                                other.node.id != entry.node.id &&
                                other.node.id !in entry.ancestorIds &&
                                entry.node.id !in other.ancestorIds &&
                                other.node.text == null &&
                                rectsIntersectStrictly(other.bounds, sampleRect)
                        }
                        .map { it.node.id }
                        .toSet()

                    if (overlayIds.isNotEmpty()) {
                        deferredOverlaySamples += DeferredOverlaySample(entry, req, fg, sampleRect, overlayIds)
                        continue
                    }

                    val effectiveAlpha = (fg.alpha * entry.effectiveOpacity.toFloat()).coerceIn(0f, 1f)
                    val sampledRatio = backdropRenderer.sampleWorstBackdropContrastRatio(
                        buffer = rendered.buffer,
                        sampleRect = sampleRect,
                        stageWidth = stageW,
                        stageHeight = stageH,
                        foreground = fg.copy(alpha = effectiveAlpha),
                    )
                    recordSampledContrast(entry, req, sampleRect, sampledRatio)
                }

                // Re-render backdrop excluding higher-z foreground overlays (e.g. strikeout strokes painted over text).
                for (deferred in deferredOverlaySamples) {
                    currentCoroutineContext().ensureActive()
                    val unoccluded = backdropRenderer.renderRealSlideBackdropBuffer(
                        document = document,
                        slide = slide,
                        beat = beat,
                        themeName = themeName,
                        deckDir = deckDir,
                        assetsDir = assetsDir,
                        excludedOverlayNodeIds = deferred.overlayIds,
                    )
                    if (unoccluded == null) {
                        unknownCount++
                        issues += contrastUnknownIssue(
                            slide = slide,
                            slideIndex = slideIndex,
                            beat = beat,
                            entry = deferred.entry,
                            themeName = themeName,
                            reason = "Real 480x270 backdrop render failed (${deferred.req.reason})",
                        )
                        continue
                    }
                    val effectiveAlpha = (deferred.fg.alpha * deferred.entry.effectiveOpacity.toFloat()).coerceIn(0f, 1f)
                    val sampledRatio = backdropRenderer.sampleWorstBackdropContrastRatio(
                        buffer = unoccluded.buffer,
                        sampleRect = deferred.sampleRect,
                        stageWidth = stageW,
                        stageHeight = stageH,
                        foreground = deferred.fg.copy(alpha = effectiveAlpha),
                    )
                    recordSampledContrast(deferred.entry, deferred.req, deferred.sampleRect, sampledRatio)
                }
            }
        }

        return CachedSlideResult(
            issues = issues,
            staticContrastChecks = staticChecks,
            pixelContrastRenders = pixelRenders,
            unknownContrastCount = unknownCount,
        )
    }

    /**
     * Returns a reason string if [entry] sits over an image fill/node whose asset does not exist
     * on disk or classpath (so we report `CONTRAST_UNKNOWN` instead of contrasting against empty canvas).
     */
    private fun findMissingBackdropAssetReason(
        entry: LaidOutNode,
        allNodes: List<LaidOutNode>,
        slide: SlideSpec,
        deckId: String,
        deckDir: Path?,
        assetsDir: Path?,
    ): String? {
        fun assetMissing(src: String): Boolean =
            resolveAssetPathOnDisk(deckId, src, deckDir, assetsDir) == null &&
                !hasClasspathAsset(deckId, src)

        val ownSurfaceImg = entry.node.text?.surface?.fill as? Fill.Image
        if (ownSurfaceImg != null && assetMissing(ownSurfaceImg.image.src)) {
            return "Missing surface image asset '${ownSurfaceImg.image.src}'"
        }
        if (entry.surfaceOpaqueFill != null) return null

        val slideBgImg = slide.background as? Fill.Image
        if (slideBgImg != null && assetMissing(slideBgImg.image.src)) {
            return "Missing slide background image asset '${slideBgImg.image.src}'"
        }

        for (other in allNodes) {
            if (other.paintOrder >= entry.paintOrder) break
            val imgSrc = other.node.image?.src
                ?: (other.node.shape?.fill as? Fill.Image)?.image?.src
                ?: (other.node.stack?.surface?.fill as? Fill.Image)?.image?.src
                ?: (other.node.row?.surface?.fill as? Fill.Image)?.image?.src
                ?: (other.node.column?.surface?.fill as? Fill.Image)?.image?.src
            if (imgSrc != null && rectsIntersectStrictly(other.bounds, entry.bounds) && assetMissing(imgSrc)) {
                return "Missing overlapping image asset '$imgSrc' on '${other.node.id}'"
            }
        }
        return null
    }

    // ── Asset resolution helpers (deckDir, assetsDir, and classpath) ────────────────────────────

    private fun buildAssetExistsFn(
        deckId: String,
        deckDir: Path?,
        assetsDir: Path?,
    ): ((String) -> Boolean)? {
        if (deckDir == null && assetsDir == null) return null
        return { rel ->
            resolveAssetPathOnDisk(deckId, rel, deckDir, assetsDir) != null ||
                hasClasspathAsset(deckId, rel)
        }
    }

    private fun resolveAssetPathOnDisk(
        deckId: String,
        src: String,
        deckDir: Path?,
        assetsDir: Path?,
    ): Path? {
        val clean = src.trim().removePrefix("/")
        if (clean.isEmpty()) return null
        val candidates = buildList {
            if (assetsDir != null) {
                add(assetsDir.resolve(clean).normalize())
            }
            if (deckDir != null) {
                add(deckDir.resolve("$deckId.assets").resolve(clean).normalize())
                add(deckDir.resolve(clean).normalize())
            }
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    private fun hasClasspathAsset(deckId: String, src: String): Boolean {
        val clean = src.trim().removePrefix("/")
        if (clean.isEmpty()) return false
        val cpCandidates = listOf(
            "decks/$deckId.assets/$clean",
            "$deckId.assets/$clean",
            clean,
        )
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            DocumentDeckLintEngine::class.java.classLoader,
        ).distinct()
        for (cp in cpCandidates) {
            for (loader in loaders) {
                if (loader.getResource(cp) != null) return true
            }
        }
        return false
    }

    private fun rectsIntersectStrictly(a: Rect, b: Rect): Boolean =
        a.left < b.right - 0.5f &&
            a.right > b.left + 0.5f &&
            a.top < b.bottom - 0.5f &&
            a.bottom > b.top + 0.5f

    private fun checkBounds(
        slide: SlideSpec,
        slideIndex: Int,
        beat: Int,
        entry: LaidOutNode,
        stageW: Float,
        stageH: Float,
        out: MutableList<DocumentLintIssue>,
    ) {
        val b = entry.bounds
        if (b.left < -0.5f || b.top < -0.5f || b.right > stageW + 0.5f || b.bottom > stageH + 0.5f) {
            out += DocumentLintIssue(
                id = "oob:${slide.id}:$beat:${entry.node.id}",
                code = "STAGE_OUT_OF_BOUNDS",
                severity = LintSeverity.Error,
                source = DocumentLintSource.Layout,
                slideIndex = slideIndex,
                slideId = slide.id,
                beatIndex = beat,
                nodeId = entry.node.id,
                title = "Node extends outside stage",
                detail = "'${entry.node.id}' rect [${b.left.roundToInt()},${b.top.roundToInt()}..${b.right.roundToInt()},${b.bottom.roundToInt()}] exceeds ${stageW.roundToInt()}×${stageH.roundToInt()}.",
                stageBounds = b,
            )
        } else if (
            b.left < SAFE_AREA_MARGIN_PX ||
            b.top < SAFE_AREA_MARGIN_PX ||
            b.right > stageW - SAFE_AREA_MARGIN_PX ||
            b.bottom > stageH - SAFE_AREA_MARGIN_PX
        ) {
            out += DocumentLintIssue(
                id = "safe-area:${slide.id}:$beat:${entry.node.id}",
                code = "SAFE_AREA_BLEED",
                severity = LintSeverity.Warning,
                source = DocumentLintSource.Layout,
                slideIndex = slideIndex,
                slideId = slide.id,
                beatIndex = beat,
                nodeId = entry.node.id,
                title = "Node enters stage safe-area margin",
                detail = "'${entry.node.id}' lies within ${SAFE_AREA_MARGIN_PX.roundToInt()} units of the stage edge.",
                stageBounds = b,
            )
        }
    }

    private fun checkTinyText(
        slide: SlideSpec,
        slideIndex: Int,
        beat: Int,
        entry: LaidOutNode,
        fontSizeUnits: Double,
        out: MutableList<DocumentLintIssue>,
    ) {
        val fontSizeSp = (fontSizeUnits / 2.0).toFloat()
        if (fontSizeSp > 0f && fontSizeSp < TINY_TEXT_MIN_SP) {
            out += DocumentLintIssue(
                id = "tiny-text:${slide.id}:$beat:${entry.node.id}",
                code = "TINY_STAGE_TEXT",
                severity = LintSeverity.Warning,
                source = DocumentLintSource.Layout,
                slideIndex = slideIndex,
                slideId = slide.id,
                beatIndex = beat,
                nodeId = entry.node.id,
                title = "Text smaller than stage minimum",
                detail = "'${entry.node.id}' font size ${fontSizeUnits.roundToInt()}u (${fontSizeSp.roundToInt()}sp) is below ${TINY_TEXT_MIN_SP.roundToInt()}sp.",
                stageBounds = entry.bounds,
            )
        }
    }

    private fun lowContrastIssue(
        slide: SlideSpec,
        slideIndex: Int,
        beat: Int,
        entry: LaidOutNode,
        themeName: String,
        source: DocumentLintSource,
        ratio: Float,
        requiredRatio: Float,
        note: String,
    ): DocumentLintIssue = DocumentLintIssue(
        id = "contrast:${slide.id}:$beat:${entry.node.id}:$themeName",
        code = "LOW_CONTRAST",
        severity = if (ratio < WCAG_LARGE_TEXT_RATIO) LintSeverity.Error else LintSeverity.Warning,
        source = source,
        slideIndex = slideIndex,
        slideId = slide.id,
        beatIndex = beat,
        nodeId = entry.node.id,
        themeName = themeName,
        title = "Low text contrast (${"%.2f".format(Locale.US, ratio)}:1)",
        detail = "[$themeName] '${entry.node.id}' contrast ${"%.2f".format(Locale.US, ratio)}:1 < WCAG ${"%.1f".format(Locale.US, requiredRatio)}:1 ($note).",
        stageBounds = entry.bounds,
    )

    private fun contrastUnknownIssue(
        slide: SlideSpec,
        slideIndex: Int,
        beat: Int,
        entry: LaidOutNode,
        themeName: String,
        reason: String,
    ): DocumentLintIssue = DocumentLintIssue(
        id = "contrast-unknown:${slide.id}:$beat:${entry.node.id}:$themeName",
        code = "CONTRAST_UNKNOWN",
        severity = LintSeverity.Warning,
        source = DocumentLintSource.PixelContrast480x270,
        slideIndex = slideIndex,
        slideId = slide.id,
        beatIndex = beat,
        nodeId = entry.node.id,
        themeName = themeName,
        title = "Contrast could not be determined",
        detail = "[$themeName] '${entry.node.id}': $reason.",
        stageBounds = entry.bounds,
    )

    private fun collectTodoVideoIssues(
        slide: SlideSpec,
        slideIndex: Int,
        out: MutableMap<String, DocumentLintIssue>,
    ) {
        for (node in DocumentThemeResolver.flattenNodes(slide.nodes)) {
            val sp = node.specimen ?: continue
            if (sp.ref == "todo.video") {
                val src = sp.params["src"]?.toString() ?: "(unspecified asset)"
                val issue = DocumentLintIssue(
                    id = "todo-video:${slide.id}:${node.id}",
                    code = "TODO_VIDEO",
                    severity = LintSeverity.Warning,
                    source = DocumentLintSource.TodoVideo,
                    slideIndex = slideIndex,
                    slideId = slide.id,
                    nodeId = node.id,
                    title = "Unconverted video specimen placeholder (todo.video)",
                    detail = "Node '${node.id}' references todo.video for $src; replace with a live specimen.",
                )
                out.putIfAbsent(issue.id, issue)
            }
        }
    }

    /**
     * Animated image checks that need the asset bytes: playback builds or clip fields on a missing
     * or non-animated source, and animations whose decode budget would leave only the first frame.
     */
    private fun collectClipIssues(
        deckId: String,
        slide: SlideSpec,
        slideIndex: Int,
        deckDir: Path?,
        assetsDir: Path?,
        out: MutableMap<String, DocumentLintIssue>,
    ) {
        if (deckDir == null && assetsDir == null) return
        for (node in DocumentThemeResolver.flattenNodes(slide.nodes)) {
            val image = node.image ?: continue
            val hasPlaybackBuilds = node.builds.any { it is Build.Playback }
            val usesClipFields = hasPlaybackBuilds || image.copy(fit = null, tint = null) != ImagePayload(image.src)
            fun warn(code: String, title: String, detail: String) {
                val issue = DocumentLintIssue(
                    id = "clip:${code.lowercase(Locale.US)}:${slide.id}:${node.id}",
                    code = code,
                    severity = LintSeverity.Warning,
                    source = DocumentLintSource.StaticValidation,
                    slideIndex = slideIndex,
                    slideId = slide.id,
                    nodeId = node.id,
                    title = title,
                    detail = detail,
                )
                out.putIfAbsent(issue.id, issue)
            }
            val bytes = resolveAssetPathOnDisk(deckId, image.src, deckDir, assetsDir)
                ?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }
                ?: readClasspathAsset(deckId, image.src)
            if (bytes == null) {
                if (hasPlaybackBuilds) {
                    warn(
                        "CLIP_SOURCE_MISSING",
                        "Playback builds target a missing image",
                        "Node '${node.id}' has playback builds but '${image.src}' was not found.",
                    )
                }
                continue
            }
            val info = probeAnimation(bytes)
            if (info == null) {
                if (usesClipFields) {
                    warn(
                        "CLIP_SOURCE_NOT_ANIMATED",
                        "Playback settings on a still image",
                        "Node '${node.id}' sets playback builds or clip fields, but '${image.src}' is not an animated GIF/WebP.",
                    )
                }
                continue
            }
            val budget = AnimationBudget(maxPixels = image.maxPixels?.takeIf { it > 0 } ?: AnimationBudget().maxPixels)
            if (info.totalPixels > budget.maxPixels || info.width > budget.maxSidePx || info.height > budget.maxSidePx) {
                warn(
                    "CLIP_OVER_BUDGET",
                    "Animated image exceeds its decode budget",
                    "'${image.src}' is ${info.width}x${info.height} with ${info.frameCount} frames (${info.totalPixels} pixels, " +
                        "budget ${budget.maxPixels}); it will show only its first frame. Set image.maxPixels to at least ${info.totalPixels}.",
                )
            }
        }
    }

    private fun readClasspathAsset(deckId: String, src: String): ByteArray? {
        val clean = src.trim().removePrefix("/")
        if (clean.isEmpty()) return null
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            DocumentDeckLintEngine::class.java.classLoader,
        ).distinct()
        for (cp in listOf("decks/$deckId.assets/$clean", "$deckId.assets/$clean", clean)) {
            for (loader in loaders) {
                loader.getResourceAsStream(cp)?.use { return it.readBytes() }
            }
        }
        return null
    }

    private fun collectCrossSlideIssuesForNeighbor(
        document: DeckDocument,
        slideIndex: Int,
        out: MutableMap<String, DocumentLintIssue>,
    ) {
        val activeIndices = document.slides.indices.filter { !document.slides[it].skipped }
        val pos = activeIndices.indexOf(slideIndex)
        if (pos < 0) return
        if (pos > 0) {
            val prevIdx = activeIndices[pos - 1]
            CrossSlideGeometry.matchCrossSlidePairs(document.slides[prevIdx], document.slides[slideIndex], document.stage)
                .lintIssues.forEach {
                    val m = mapDocumentIssue(it, document, defaultSlideIndex = prevIdx, source = DocumentLintSource.CrossSlide)
                    out.putIfAbsent(m.id, m)
                }
        }
        if (pos < activeIndices.lastIndex) {
            val nextIdx = activeIndices[pos + 1]
            CrossSlideGeometry.matchCrossSlidePairs(document.slides[slideIndex], document.slides[nextIdx], document.stage)
                .lintIssues.forEach {
                    val m = mapDocumentIssue(it, document, defaultSlideIndex = slideIndex, source = DocumentLintSource.CrossSlide)
                    out.putIfAbsent(m.id, m)
                }
        }
    }

    private fun mapDocumentIssue(
        issue: DocumentIssue,
        document: DeckDocument,
        defaultSlideIndex: Int,
        source: DocumentLintSource = DocumentLintSource.StaticValidation,
    ): DocumentLintIssue {
        val matchedSlideIndex = document.slides.indexOfFirst { s -> issue.address.contains(s.id) }
            .takeIf { it >= 0 } ?: defaultSlideIndex
        val slideId = document.slides.getOrNull(matchedSlideIndex)?.id ?: "<deck>"
        val severity = when (issue.severity) {
            IssueSeverity.LoadError, IssueSeverity.LintError -> LintSeverity.Error
            IssueSeverity.LintWarning -> LintSeverity.Warning
        }
        return DocumentLintIssue(
            id = "doc:${issue.code}:${issue.address}:${issue.message.hashCode()}",
            code = issue.code,
            severity = severity,
            source = source,
            slideIndex = matchedSlideIndex,
            slideId = slideId,
            title = issue.code,
            detail = "${issue.address}: ${issue.message}",
        )
    }

    private fun putCachedSlide(key: String, value: CachedSlideResult) {
        slideCache[key] = value
        while (slideCache.size > maxCacheEntries) {
            val oldest = slideCache.keys.firstOrNull() ?: break
            slideCache.remove(oldest)
        }
    }

    private fun sortIssues(issues: Collection<DocumentLintIssue>): List<DocumentLintIssue> =
        issues.sortedWith(
            compareBy<DocumentLintIssue> { it.severity != LintSeverity.Error }
                .thenBy { it.slideIndex }
                .thenBy { it.beatIndex }
                .thenBy { it.code }
                .thenBy { it.id },
        )
}
