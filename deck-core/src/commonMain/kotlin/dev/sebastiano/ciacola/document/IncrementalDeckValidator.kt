package dev.sebastiano.ciacola.document

import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration

internal data class IncrementalValidationStats(
    val revalidatedSlideCount: Int = 0,
    val cachedSlideHits: Int = 0,
)

/**
 * Thread-safe incremental per-slide cache over [DeckDocumentCodec.validate].
 *
 * Document header checks and deck-wide slide-ID uniqueness checks run on every call; each
 * non-blank, unique-ID slide body is cached by exact [SlideSpec] reference together with every
 * external input that can change its diagnostics (`isFirstNonSkipped`, `isLastNonSkipped`,
 * palette existence, declared fonts, specimen/effect registries). Slides referencing image assets
 * bypass caching whenever a dynamic [assetExists] callback is supplied so filesystem changes are
 * always reflected immediately and issue ordering stays identical to [DeckDocumentCodec.validate].
 */
internal class IncrementalDeckValidator {

    private class CachedSlideValidation(
        val slide: SlideSpec,
        val isFirstNonSkipped: Boolean,
        val isLastNonSkipped: Boolean,
        val paletteKnown: Boolean,
        val fontsRef: List<DeckFontDeclaration>,
        val declaredFonts: Set<String>,
        val specimens: DeckSpecimenRegistry,
        val effects: BuildEffectRegistry,
        val hasImageRefs: Boolean,
        val hasLoadError: Boolean,
        val issues: List<DocumentIssue>,
    )

    private val cacheLock = Any()
    private val slideCache = LinkedHashMap<String, CachedSlideValidation>()
    private var lastStats = IncrementalValidationStats()

    fun clear() {
        withDocumentLock(cacheLock) {
            slideCache.clear()
            lastStats = IncrementalValidationStats()
        }
    }

    fun lastStatsForTesting(): IncrementalValidationStats = withDocumentLock(cacheLock) {
        lastStats
    }

    fun validate(
        document: DeckDocument,
        specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
        effects: BuildEffectRegistry = BuildEffectRegistry.Standard,
        assetExists: ((String) -> Boolean)? = null,
    ): List<DocumentIssue> {
        val issues = mutableListOf<DocumentIssue>()
        val declaredFonts = DeckDocumentCodec.validateDocumentHeader(document, issues)

        val slides = document.slides
        val seenSlideIds = HashSet<String>(slides.size * 2)
        var firstNonSkippedIdx: Int? = null
        var lastNonSkippedIdx: Int? = null
        for (idx in slides.indices) {
            if (!slides[idx].skipped) {
                if (firstNonSkippedIdx == null) firstNonSkippedIdx = idx
                lastNonSkippedIdx = idx
            }
        }

        // Snapshot relevant cache entries once under cacheLock so validation work runs lock-free.
        val cachedSnapshot: Map<String, CachedSlideValidation> = withDocumentLock(cacheLock) {
            if (slideCache.isEmpty()) emptyMap() else HashMap(slideCache)
        }

        var hasLoadError = issues.any { it.severity == IssueSeverity.LoadError }
        var revalidatedCount = 0
        var cachedHits = 0
        val newEntries = ArrayList<Pair<String, CachedSlideValidation>>(4)
        val staleIdsToRemove = ArrayList<String>()

        for ((slideIdx, slide) in slides.withIndex()) {
            val isBlankId = slide.id.isBlank()
            val slideAddr = if (isBlankId) "slide[$slideIdx]" else slide.id
            val isDuplicateId = if (isBlankId) {
                issues += DocumentIssue(
                    severity = IssueSeverity.LoadError,
                    code = "BLANK_SLIDE_ID",
                    address = slideAddr,
                    message = "Slide id must not be blank.",
                )
                hasLoadError = true
                false
            } else if (!seenSlideIds.add(slide.id)) {
                issues += DocumentIssue(
                    severity = IssueSeverity.LoadError,
                    code = "DUPLICATE_SLIDE_ID",
                    address = slideAddr,
                    message = "Duplicate slide id '${slide.id}'.",
                )
                hasLoadError = true
                true
            } else {
                false
            }

            val isFirstNonSkipped = slideIdx == firstNonSkippedIdx
            val isLastNonSkipped = slideIdx == lastNonSkippedIdx
            val paletteKnown = slide.palette == null || slide.palette in document.palettes

            val cacheable = !isBlankId && !isDuplicateId
            val cached = if (cacheable) cachedSnapshot[slide.id] else null

            if (cached != null &&
                cached.slide === slide &&
                cached.isFirstNonSkipped == isFirstNonSkipped &&
                cached.isLastNonSkipped == isLastNonSkipped &&
                cached.paletteKnown == paletteKnown &&
                (cached.fontsRef === document.fonts || cached.declaredFonts == declaredFonts) &&
                cached.specimens === specimens &&
                cached.effects === effects &&
                (assetExists == null || !cached.hasImageRefs)
            ) {
                cachedHits++
                if (cached.issues.isNotEmpty()) {
                    issues.addAll(cached.issues)
                    if (cached.hasLoadError) hasLoadError = true
                }
                continue
            }

            revalidatedCount++
            val slideIssues = mutableListOf<DocumentIssue>()
            val hasImageRefs = DeckDocumentCodec.validateSlideBody(
                slide = slide,
                slideAddr = slideAddr,
                isFirstNonSkipped = isFirstNonSkipped,
                isLastNonSkipped = isLastNonSkipped,
                paletteKnown = paletteKnown,
                declaredFonts = declaredFonts,
                specimens = specimens,
                effects = effects,
                assetExists = assetExists,
                issues = slideIssues,
            )
            val slideHasLoadError = slideIssues.any { it.severity == IssueSeverity.LoadError }
            if (slideHasLoadError) hasLoadError = true
            if (slideIssues.isNotEmpty()) {
                issues.addAll(slideIssues)
            }

            if (cacheable && !slideHasLoadError) {
                if (!hasImageRefs || assetExists == null) {
                    newEntries += slide.id to CachedSlideValidation(
                        slide = slide,
                        isFirstNonSkipped = isFirstNonSkipped,
                        isLastNonSkipped = isLastNonSkipped,
                        paletteKnown = paletteKnown,
                        fontsRef = document.fonts,
                        declaredFonts = declaredFonts,
                        specimens = specimens,
                        effects = effects,
                        hasImageRefs = hasImageRefs,
                        hasLoadError = false,
                        issues = if (slideIssues.isEmpty()) emptyList() else slideIssues,
                    )
                } else if (cached != null && cached.slide !== slide) {
                    // Don't retain an obsolete SlideSpec reference when asset-checked edits bypass caching.
                    staleIdsToRemove += slide.id
                }
            }
        }

        withDocumentLock(cacheLock) {
            for (id in staleIdsToRemove) {
                slideCache.remove(id)
            }
            for ((id, entry) in newEntries) {
                slideCache[id] = entry
            }
            if (!hasLoadError) {
                slideCache.keys.retainAll(seenSlideIds)
            } else {
                // Keep previously valid slides cached across rejected edits while bounding growth.
                val cap = maxOf(slides.size * 2, 512)
                while (slideCache.size > cap) {
                    val eldest = slideCache.keys.iterator().next()
                    slideCache.remove(eldest)
                }
            }
            lastStats = IncrementalValidationStats(
                revalidatedSlideCount = revalidatedCount,
                cachedSlideHits = cachedHits,
            )
        }

        return issues
    }
}
