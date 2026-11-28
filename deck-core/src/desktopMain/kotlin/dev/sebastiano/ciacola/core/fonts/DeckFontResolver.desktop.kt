package dev.sebastiano.ciacola.core.fonts

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFontFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.Typeface

/**
 * Loads raw font bytes for a bundled declaration's `src` path (e.g. `"fonts/Fredoka-VariableFont_wdth_wght.ttf"`).
 */
fun interface FontResourceLoader {
    fun load(src: String): ByteArray?
}

/**
 * Default resource loader reading bundled `.ttf` bytes from the JVM classpath (with fallback to
 * optional filesystem roots and `:deck-core`'s Compose resources path for `Inter` / `JetBrainsMono`).
 *
 * Immutable classpath font resources are memoized process-wide so multi-deck sessions and tests
 * never allocate duplicate multi-megabyte `.ttf` byte arrays.
 */
class ClasspathFontResourceLoader(
    private val classLoader: ClassLoader = ClasspathFontResourceLoader::class.java.classLoader,
    private val fallbackDirectories: List<File> = emptyList(),
) : FontResourceLoader {

    override fun load(src: String): ByteArray? {
        val clean = src.removePrefix("/")
        val fileName = clean.substringAfterLast('/')
        val candidates = linkedSetOf(
            clean,
            if (clean.startsWith("fonts/")) clean else "fonts/$clean",
            "composeResources/ciacola.deck.generated.resources/font/$fileName",
        )

        val defaultLoader = ClasspathFontResourceLoader::class.java.classLoader
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            classLoader,
        ).distinct()

        for (candidate in candidates) {
            if (classLoader === defaultLoader) {
                classpathBytesCache[candidate]?.let { return it }
            }
            for (loader in loaders) {
                val bytes = loader.getResourceAsStream(candidate)?.use { it.readBytes() }
                if (bytes != null) {
                    if (loader === defaultLoader) {
                        return classpathBytesCache.putIfAbsent(candidate, bytes) ?: bytes
                    }
                    return bytes
                }
            }
        }

        for (dir in fallbackDirectories) {
            val direct = File(dir, clean)
            if (direct.isFile) return direct.readBytes()
            val byFileName = File(dir, fileName)
            if (byFileName.isFile) return byFileName.readBytes()
        }

        return null
    }

    private companion object {
        private val classpathBytesCache = ConcurrentHashMap<String, ByteArray>()
    }
}

/**
 * Resolves a deck document's declared font families into Compose [FontFamily] instances, clamped
 * [TextStyle]/[SpanStyle] values, emoji fallback chains, and per-word character-safety verdicts.
 *
 * Implements:
 * - Variable `wght` axis inspection via [FontTableInspector] and multi-step `Font` instantiation
 *   with matching `weight = FontWeight(w)` and `FontVariation.weight(w)` so Compose Desktop's
 *   `PlatformFont.getCacheKey()` never collides across weights.
 * - Mandatory clamping of requested `FontWeight` on `TextStyle` / `SpanStyle` into the resolved
 *   family's `[minWeight, maxWeight]` range so Skia never synthesizes faux bold.
 * - Resolution of `system = true` families on macOS as non-character-safe with validated acyclic
 *   fallback chains terminating in a bundled family.
 * - `role = "emoji"` fallback chain lookup and per-word character-safety certification.
 */
class DeckFontResolver(
    declarations: List<DeckFontDeclaration>,
    private val resourceLoader: FontResourceLoader = ClasspathFontResourceLoader(),
    private val systemFontLookup: SystemFontLookup = MacOsSystemFontLookup(),
    private val variableWeightSteps: List<Int> = DEFAULT_VARIABLE_WEIGHT_STEPS,
    private val gsubParserLimits: GsubParserLimits = GsubParserLimits.Default,
) {
    private val mutableIssues: MutableList<FontValidationIssue> = mutableListOf()
    private val familiesByExactName: LinkedHashMap<String, ResolvedFontFamily> = linkedMapOf()
    private val familiesByNormalizedName: HashMap<String, ResolvedFontFamily> = hashMapOf()
    private val familiesByComposeFontFamily: HashMap<FontFamily, ResolvedFontFamily> = hashMapOf()
    private val rawBytesBySha256: HashMap<String, ByteArray> = hashMapOf()
    private val axisFamilyLock = Any()
    private val axisFamilies = object : LinkedHashMap<AxisFamilyKey, ResolvedFontFamily>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AxisFamilyKey, ResolvedFontFamily>?): Boolean =
            size > MAX_AXIS_FAMILIES
    }

    /**
     * Reverse lookup for axis-derived families that outlives [axisFamilies] eviction for as long
     * as the [FontFamily] is still referenced (weak keys). Records never reference their key, so
     * they do not keep it alive.
     */
    private val axisFamiliesByComposeFontFamily = java.util.WeakHashMap<FontFamily, AxisFamilyRecord>()

    /** All validation and load-time lint issues recorded while resolving [declarations]. */
    val issues: List<FontValidationIssue>
        get() = mutableIssues.toList()

    /** All resolved families in declaration order. */
    val families: Map<String, ResolvedFontFamily>
        get() = familiesByExactName.toMap()

    /** The resolved family declared with `role = "emoji"`, if any. */
    val emojiFamily: ResolvedFontFamily?

    /**
     * Deterministic content fingerprint of every resolved family and face (family name, role,
     * origin, weight/style, and font file SHA-256) used by renderer caches instead of
     * `System.identityHashCode` so distinct font configurations never collide.
     */
    val cacheFingerprint: String

    init {
        mutableIssues += validateFontDeclarations(declarations)

        // 1. Load all bundled families first so system fallback chains can bind to them.
        val bundledGroups = linkedMapOf<String, MutableList<DeckFontDeclaration>>()
        val systemDeclarations = linkedMapOf<String, DeckFontDeclaration>()

        for (decl in declarations) {
            if (decl.family.isBlank()) continue
            if (decl.system) {
                systemDeclarations.putIfAbsent(decl.family, decl)
            } else if (!decl.src.isNullOrBlank()) {
                bundledGroups.getOrPut(decl.family) { mutableListOf() }.add(decl)
            }
        }

        for ((familyName, group) in bundledGroups) {
            val resolved = loadBundledFamily(familyName, group)
            registerFamily(resolved)
        }

        // 2. Load system families with acyclic fallback resolution.
        for ((familyName, decl) in systemDeclarations) {
            if (familyName in familiesByExactName) continue
            val resolved = loadSystemFamily(decl, systemDeclarations)
            registerFamily(resolved)
        }

        emojiFamily = familiesByExactName.values.firstOrNull { it.role == DeckFontDeclaration.ROLE_EMOJI }
        cacheFingerprint = buildString {
            for ((name, family) in familiesByExactName) {
                append(name).append(':').append(family.role ?: "").append(':').append(family.origin.name).append('[')
                for (face in family.faces) {
                    append(face.weight).append('/').append(if (face.italic) 'i' else 'n').append('/')
                    append(face.origin.name).append('/').append(face.inspection?.contentSha256 ?: face.src ?: "").append(',')
                }
                append("];")
            }
        }
    }

    /** Looks up a resolved family by exact name, falling back to case/whitespace-normalized match. */
    fun resolveFamily(familyName: String): ResolvedFontFamily? =
        familiesByExactName[familyName]
            ?: familiesByNormalizedName[MacOsSystemFontLookup.normalizeFamilyName(familyName)]

    /** Looks up the [ResolvedFontFamily] previously produced by this resolver for [fontFamily]. */
    fun findResolvedFamily(fontFamily: FontFamily?): ResolvedFontFamily? =
        if (fontFamily == null) {
            null
        } else {
            familiesByComposeFontFamily[fontFamily]
                ?: synchronized(axisFamilyLock) { axisFamiliesByComposeFontFamily[fontFamily]?.familyFor(fontFamily) }
        }

    /**
     * Resolves [axes] against the `fvar` axes of [familyName]'s face for [italic], reporting
     * unsupported tags and clamped values. Returns `null` when [familyName] is unknown. A family
     * without inspectable bundled faces (system or default fallback) reports every valid tag as
     * unsupported.
     */
    fun resolveAxes(
        familyName: String,
        axes: Map<String, Double>,
        italic: Boolean = false,
    ): AxisResolution? {
        val family = resolveFamily(familyName) ?: return null
        val face = family.faces.firstOrNull { it.italic == italic && it.inspection != null }
            ?: family.faces.firstOrNull { it.inspection != null }
        return FontAxes.resolve(axes, face?.inspection?.variationAxes.orEmpty())
    }

    /**
     * Returns a family that renders [family]'s faces with static [axes] coordinates applied on
     * top of each face's `wght` instance. Tags a face does not declare are ignored and values are
     * clamped to the face's `fvar` range. Returns [family] itself when no coordinate applies.
     *
     * Derived families are memoized (bounded LRU) so equal requests share one [FontFamily]
     * instance and Compose's font cache, and [findResolvedFamily] recognizes them.
     */
    fun withAxes(family: ResolvedFontFamily, axes: Map<String, Double>): ResolvedFontFamily {
        if (axes.isEmpty()) return family
        val base = baseFamilyOf(family)
        val requestKey = axes.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        val key = AxisFamilyKey(base.familyName, requestKey)
        synchronized(axisFamilyLock) {
            axisFamilies[key]?.let { return it }
        }
        val derived = deriveAxisFamily(base, axes)
        synchronized(axisFamilyLock) {
            axisFamilies[key]?.let { return it }
            axisFamilies[key] = derived
            if (derived !== base) {
                axisFamiliesByComposeFontFamily[derived.fontFamily] = AxisFamilyRecord(base, derived.faces, derived)
            }
        }
        return derived
    }

    /**
     * Rebinds [style] to the axis-derived variant of the resolver-managed family it already uses.
     * Returns [style] unchanged when [axes] is empty or its family is not managed by this resolver.
     */
    fun applyAxes(style: TextStyle, axes: Map<String, Double>): TextStyle {
        if (axes.isEmpty()) return style
        val resolved = findResolvedFamily(style.fontFamily) ?: return style
        val derived = withAxes(resolved, axes)
        return if (derived.fontFamily == style.fontFamily) style else style.copy(fontFamily = derived.fontFamily)
    }

    /** Span-level counterpart of [applyAxes]. */
    fun applyAxes(style: SpanStyle, axes: Map<String, Double>): SpanStyle {
        if (axes.isEmpty()) return style
        val resolved = findResolvedFamily(style.fontFamily) ?: return style
        val derived = withAxes(resolved, axes)
        return if (derived.fontFamily == style.fontFamily) style else style.copy(fontFamily = derived.fontFamily)
    }

    /**
     * Clamps [requested] weight to the resolved family's supported weight range for [style].
     * If [familyName] is unknown, returns [requested] unchanged.
     */
    fun clampWeight(
        familyName: String,
        requested: FontWeight,
        style: FontStyle = FontStyle.Normal,
    ): FontWeight = resolveFamily(familyName)?.clampWeight(requested, style) ?: requested

    /**
     * Resolves a [TextStyle] for [familyName], binding its [FontFamily] and clamping `fontWeight`
     * to the family's loaded range so Skia never synthesizes bold.
     */
    fun resolveTextStyle(
        familyName: String,
        style: TextStyle = TextStyle.Default,
        disableOptionalLigatures: Boolean = false,
        axes: Map<String, Double> = emptyMap(),
    ): TextStyle {
        val resolved = resolveFamily(familyName)
            ?: return if (disableOptionalLigatures) {
                style.copy(fontFeatureSettings = mergeDisabledLigatureFeatures(style.fontFeatureSettings))
            } else {
                style
            }
        return withAxes(resolved, axes).applyTo(style, disableOptionalLigatures)
    }

    /**
     * Resolves a [SpanStyle] for [familyName], binding its [FontFamily] and clamping `fontWeight`
     * to the family's loaded range.
     */
    fun resolveSpanStyle(
        familyName: String,
        style: SpanStyle = SpanStyle(),
        fallbackFontStyle: FontStyle = FontStyle.Normal,
        disableOptionalLigatures: Boolean = false,
        axes: Map<String, Double> = emptyMap(),
    ): SpanStyle {
        val resolved = resolveFamily(familyName)
            ?: return if (disableOptionalLigatures) {
                style.copy(fontFeatureSettings = mergeDisabledLigatureFeatures(style.fontFeatureSettings))
            } else {
                style
            }
        return withAxes(resolved, axes).applyTo(style, fallbackFontStyle, disableOptionalLigatures)
    }

    /**
     * Clamps `style.fontWeight` if `style.fontFamily` belongs to a family managed by this resolver.
     */
    fun clampStyleWeights(style: TextStyle): TextStyle {
        val resolved = findResolvedFamily(style.fontFamily) ?: return style
        val effectiveStyle = style.fontStyle ?: FontStyle.Normal
        val effectiveWeight = style.fontWeight ?: FontWeight.Normal
        val clamped = resolved.clampWeight(effectiveWeight, effectiveStyle)
        return if (clamped == style.fontWeight) style else style.copy(fontWeight = clamped)
    }

    /**
     * Clamps `style.fontWeight` if `style.fontFamily` belongs to a family managed by this resolver.
     */
    fun clampStyleWeights(
        style: SpanStyle,
        fallbackFontStyle: FontStyle = FontStyle.Normal,
    ): SpanStyle {
        val resolved = findResolvedFamily(style.fontFamily) ?: return style
        val effectiveStyle = style.fontStyle ?: fallbackFontStyle
        val effectiveWeight = style.fontWeight ?: FontWeight.Normal
        val clamped = resolved.clampWeight(effectiveWeight, effectiveStyle)
        return if (clamped == style.fontWeight) style else style.copy(fontWeight = clamped)
    }

    /**
     * Resolves the exact loaded face for ([familyName], [weight], [style]) after clamping [weight].
     * Returns `null` if the family is unknown or lacks a loaded entry for that clamped weight/style.
     */
    fun resolveFace(
        familyName: String,
        weight: FontWeight = FontWeight.Normal,
        style: FontStyle = FontStyle.Normal,
    ): ResolvedFontFace? = resolveFamily(familyName)?.resolveFace(weight, style)

    /**
     * Returns the ordered fallback chain for [familyName]: the primary resolved family followed by
     * [emojiFamily] (if declared and distinct from the primary family).
     */
    fun resolveFallbackChain(familyName: String): List<ResolvedFontFamily> = buildList {
        val primary = resolveFamily(familyName)
        if (primary != null) add(primary)
        val emoji = emojiFamily
        if (emoji != null && emoji.familyName != primary?.familyName) {
            add(emoji)
        }
    }

    /**
     * Returns the rendering Skia [Typeface] built from the bundled bytes for [face], or `null` if
     * [face] is not a bundled face or cannot be loaded by Skia.
     */
    fun renderingTypeface(face: ResolvedFontFace): Typeface? {
        val sha = face.inspection?.contentSha256
        val bytes = if (sha != null) rawBytesBySha256[sha] else null
        return BundledTypefaceRegistry.typefaceFor(face, bytes)
    }

    /**
     * Resolves which face in the fallback chain (primary -> `role = "emoji"`) covers [codePoint]
     * using the rendering Skia [Typeface.getUTF32Glyph] lookup.
     */
    fun resolveFaceForCodePoint(
        familyName: String,
        codePoint: Int,
        weight: FontWeight = FontWeight.Normal,
        style: FontStyle = FontStyle.Normal,
    ): ResolvedFontFace? {
        for (family in resolveFallbackChain(familyName)) {
            val face = family.resolveFace(weight, style)
                ?: family.resolveFace(weight, FontStyle.Normal)
                ?: continue
            val tf = renderingTypeface(face)
            if (tf != null && (tf.getUTF32Glyph(codePoint).toInt() and 0xFFFF) != 0) {
                return face
            }
        }
        return null
    }

    /**
     * THE ONE shared face-assignment function: decides per Unicode scalar
     * which bundled face draws it and builds the routed [AnnotatedString] with clamped fallback
     * spans so drawing and certification always agree.
     */
    fun assignParagraphFaces(
        paragraph: AnnotatedString,
        baseStyle: TextStyle,
    ): ParagraphFaceAssignment = CharacterSafetyEngine.assignParagraphFaces(this, paragraph, baseStyle)

    /**
     * Builds an [AnnotatedString] keeping [text] verbatim (including U+FE0F variation selectors)
     * while attaching clamped [SpanStyle] spans targeting [emojiFamily] via [assignParagraphFaces].
     */
    fun applyEmojiFallback(
        text: String,
        primaryFamilyName: String,
        baseWeight: FontWeight = FontWeight.Normal,
        baseStyle: FontStyle = FontStyle.Normal,
    ): AnnotatedString {
        val primary = resolveFamily(primaryFamilyName) ?: return AnnotatedString(text)
        if (text.isEmpty() || emojiFamily == null || primary.familyName == emojiFamily.familyName) {
            return AnnotatedString(text)
        }
        val style = TextStyle(
            fontFamily = primary.fontFamily,
            fontWeight = primary.clampWeight(baseWeight, baseStyle),
            fontStyle = baseStyle,
        )
        return assignParagraphFaces(AnnotatedString(text), style).routedAnnotatedString
    }

    /**
     * THE ONE authoritative Condition 1 character-safety predicate for a paragraph.
     *
     * Requires layout-resolved LTR directionality ([isLayoutLtrAtOffset]) read from the measured
     * [androidx.compose.ui.text.TextLayoutResult] — never from a standalone `java.text.Bidi`
     * assumption. Under [EffectiveFontFeatureConfig.ShaperDefaults], returns an immediately refused
     * certifier before inspecting any font.
     */
    fun certifyParagraphCharacterSafety(
        paragraph: AnnotatedString,
        baseStyle: TextStyle,
        effectiveConfig: EffectiveFontFeatureConfig =
            EffectiveFontFeatureConfig.fromFeatureSettings(baseStyle.fontFeatureSettings),
        isLayoutLtrAtOffset: (Int) -> Boolean,
        existingAssignment: ParagraphFaceAssignment? = null,
        limits: GsubParserLimits = GsubParserLimits.Default,
    ): ParagraphCharacterSafetyCertifier = CharacterSafetyEngine.certifyParagraph(
        resolver = this,
        paragraph = paragraph,
        baseStyle = baseStyle,
        effectiveConfig = effectiveConfig,
        isLayoutLtrAtOffset = isLayoutLtrAtOffset,
        existingAssignment = existingAssignment,
        limits = limits,
    )

    private data class AxisFamilyKey(val familyName: String, val axes: String)

    /** Resolution metadata for one axis-derived family, holding no strong reference to its [FontFamily]. */
    private class AxisFamilyRecord(
        private val base: ResolvedFontFamily,
        private val faces: List<ResolvedFontFace>,
        derived: ResolvedFontFamily,
    ) {
        private var cached = java.lang.ref.WeakReference(derived)

        fun familyFor(fontFamily: FontFamily): ResolvedFontFamily =
            cached.get() ?: base.copy(fontFamily = fontFamily, faces = faces).also { cached = java.lang.ref.WeakReference(it) }
    }

    private fun baseFamilyOf(family: ResolvedFontFamily): ResolvedFontFamily {
        if (family.faces.none { it.axisCoordinates.isNotEmpty() }) return family
        return familiesByExactName[family.familyName] ?: family
    }

    private fun deriveAxisFamily(base: ResolvedFontFamily, axes: Map<String, Double>): ResolvedFontFamily {
        if (base.origin != FontOrigin.Bundled && base.origin != FontOrigin.FallbackToBundled) return base
        var changed = false
        val derivedFonts = mutableListOf<Font>()
        val derivedFaces = base.faces.map { face ->
            val loaded = face.composeFont as? androidx.compose.ui.text.platform.LoadedFont
            val applied = FontAxes.resolve(axes, face.inspection?.variationAxes.orEmpty()).applied
            if (loaded == null || applied.isEmpty()) {
                face.composeFont?.let { derivedFonts += it }
                return@map face
            }
            changed = true
            val merged = LinkedHashMap<String, FontVariation.Setting>()
            for (setting in loaded.variationSettings.settings) merged[setting.axisName] = setting
            for ((tag, value) in applied) merged[tag] = FontVariation.Setting(tag, value)
            val font = PlatformFontFactory(
                identity = "${loaded.identity}:axes[${FontAxes.cacheKey(applied)}]",
                data = loaded.data,
                weight = loaded.weight,
                style = loaded.style,
                variationSettings = FontVariation.Settings(*merged.values.toTypedArray()),
            )
            derivedFonts += font
            face.copy(composeFont = font, axisCoordinates = applied)
        }
        if (!changed || derivedFonts.isEmpty()) return base
        return base.copy(fontFamily = FontFamily(derivedFonts), faces = derivedFaces)
    }

    private fun registerFamily(resolved: ResolvedFontFamily) {
        familiesByExactName[resolved.familyName] = resolved
        familiesByNormalizedName[MacOsSystemFontLookup.normalizeFamilyName(resolved.familyName)] = resolved
        if (resolved.origin != FontOrigin.DefaultFallback) {
            familiesByComposeFontFamily.putIfAbsent(resolved.fontFamily, resolved)
        }
    }

    private fun loadBundledFamily(
        familyName: String,
        group: List<DeckFontDeclaration>,
    ): ResolvedFontFamily {
        val loadedFaces = mutableListOf<ResolvedFontFace>()
        val composeFonts = mutableListOf<Font>()
        val seenKeys = hashSetOf<FontFaceKey>()
        val role = group.firstNotNullOfOrNull { it.role }

        for (decl in group) {
            val src = decl.src ?: continue
            val bytes = resourceLoader.load(src)
            if (bytes == null) {
                mutableIssues += FontValidationIssue(
                    family = familyName,
                    severity = FontValidationSeverity.Error,
                    code = "FONT_RESOURCE_NOT_FOUND",
                    message = "Could not load bundled font resource '$src' for family '$familyName'.",
                )
                continue
            }

            val inspection = FontTableInspector.inspect(bytes, limits = gsubParserLimits)
            rawBytesBySha256.putIfAbsent(inspection.contentSha256, bytes)
            if (inspection.parseError != null) {
                mutableIssues += FontValidationIssue(
                    family = familyName,
                    severity = FontValidationSeverity.Warning,
                    code = "FONT_TABLE_PARSE_ERROR",
                    message = "Font '$src' for family '$familyName' failed table inspection: ${inspection.parseError}",
                )
            }

            val fontStyle = if (decl.italic) FontStyle.Italic else FontStyle.Normal
            val weightAxis = inspection.weightAxis

            if (decl.variable && weightAxis != null) {
                val steps = computeVariableWeightSteps(
                    minWeight = weightAxis.minWeightInt,
                    maxWeight = weightAxis.maxWeightInt,
                    candidateSteps = variableWeightSteps,
                )
                for (w in steps) {
                    val key = FontFaceKey(weight = w, italic = decl.italic)
                    if (!seenKeys.add(key)) continue
                    val font = PlatformFontFactory(
                        identity = "$familyName:$src:${if (decl.italic) "italic" else "normal"}:w$w:${inspection.contentSha256.take(12)}",
                        data = bytes,
                        weight = FontWeight(w),
                        style = fontStyle,
                        variationSettings = FontVariation.Settings(FontVariation.weight(w)),
                    )
                    composeFonts += font
                    loadedFaces += ResolvedFontFace(
                        familyName = familyName,
                        resolvedFamilyName = familyName,
                        weight = w,
                        italic = decl.italic,
                        origin = FontOrigin.Bundled,
                        src = src,
                        inspection = inspection,
                        composeFont = font,
                    )
                }
            } else {
                if (decl.variable && weightAxis == null) {
                    mutableIssues += FontValidationIssue(
                        family = familyName,
                        severity = FontValidationSeverity.Warning,
                        code = "FONT_VARIABLE_MISSING_WGHT",
                        message = "Font '$src' for family '$familyName' is declared variable=true but has no fvar 'wght' axis.",
                    )
                }
                val staticWeight = (decl.weight ?: inspection.os2WeightClass ?: 400).coerceIn(1, 1000)
                val key = FontFaceKey(weight = staticWeight, italic = decl.italic)
                if (seenKeys.add(key)) {
                    val font = PlatformFontFactory(
                        identity = "$familyName:$src:${if (decl.italic) "italic" else "normal"}:w$staticWeight:${inspection.contentSha256.take(12)}",
                        data = bytes,
                        weight = FontWeight(staticWeight),
                        style = fontStyle,
                    )
                    composeFonts += font
                    loadedFaces += ResolvedFontFace(
                        familyName = familyName,
                        resolvedFamilyName = familyName,
                        weight = staticWeight,
                        italic = decl.italic,
                        origin = FontOrigin.Bundled,
                        src = src,
                        inspection = inspection,
                        composeFont = font,
                    )
                }
            }
        }

        if (composeFonts.isEmpty()) {
            mutableIssues += FontValidationIssue(
                family = familyName,
                severity = FontValidationSeverity.Error,
                code = "FONT_NO_FACES_LOADED",
                message = "No font faces could be loaded for bundled family '$familyName'.",
            )
            return defaultFallbackFamily(familyName, role)
        }

        val normalWeights = loadedFaces.filter { !it.italic }.map { it.weight }
        val italicWeights = loadedFaces.filter { it.italic }.map { it.weight }
        val minW = loadedFaces.minOf { it.weight }
        val maxW = loadedFaces.maxOf { it.weight }

        return ResolvedFontFamily(
            familyName = familyName,
            resolvedFamilyName = familyName,
            fontFamily = FontFamily(composeFonts),
            minWeight = minW,
            maxWeight = maxW,
            origin = FontOrigin.Bundled,
            role = role,
            faces = loadedFaces,
            normalWeightRange = normalWeights.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
            italicWeightRange = italicWeights.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
        )
    }

    private fun loadSystemFamily(
        decl: DeckFontDeclaration,
        systemDeclarations: Map<String, DeckFontDeclaration>,
    ): ResolvedFontFamily {
        val fallback = resolveFallbackBundledFamily(decl, systemDeclarations)
        val systemMatch = systemFontLookup.findSystemFamily(decl.family)

        if (systemMatch != null && systemMatch.faces.isNotEmpty()) {
            val faces = systemMatch.faces.map { key ->
                ResolvedFontFace(
                    familyName = decl.family,
                    resolvedFamilyName = systemMatch.matchedFamilyName,
                    weight = key.weight,
                    italic = key.italic,
                    origin = FontOrigin.System,
                    src = null,
                    inspection = null,
                    composeFont = null,
                )
            }
            val normalWeights = faces.filter { !it.italic }.map { it.weight }
            val italicWeights = faces.filter { it.italic }.map { it.weight }
            return ResolvedFontFamily(
                familyName = decl.family,
                resolvedFamilyName = systemMatch.matchedFamilyName,
                fontFamily = systemMatch.fontFamily,
                minWeight = faces.minOf { it.weight },
                maxWeight = faces.maxOf { it.weight },
                origin = FontOrigin.System,
                role = decl.role,
                faces = faces,
                normalWeightRange = normalWeights.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
                italicWeightRange = italicWeights.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
            )
        }

        if (fallback != null && fallback.origin == FontOrigin.Bundled) {
            // System font absent on host machine: render with bundled fallback under a distinct
            // FontFamily instance (sharing the underlying byte arrays) so fallback runs remain
            // unambiguously non-character-safe in AnnotatedString inspection.
            val distinctComposeFonts = mutableListOf<Font>()
            val fallbackFaces = fallback.faces.map { face ->
                val clonedFont = cloneFallbackComposeFont(decl.family, face)
                if (clonedFont != null) distinctComposeFonts += clonedFont
                face.copy(
                    familyName = decl.family,
                    resolvedFamilyName = fallback.familyName,
                    origin = FontOrigin.FallbackToBundled,
                    composeFont = clonedFont ?: face.composeFont,
                )
            }
            val normalWeights = fallbackFaces.filter { !it.italic }.map { it.weight }
            val italicWeights = fallbackFaces.filter { it.italic }.map { it.weight }
            val distinctFamily = if (distinctComposeFonts.isNotEmpty()) {
                FontFamily(distinctComposeFonts)
            } else {
                fallback.fontFamily
            }
            return ResolvedFontFamily(
                familyName = decl.family,
                resolvedFamilyName = fallback.familyName,
                fontFamily = distinctFamily,
                minWeight = fallback.minWeight,
                maxWeight = fallback.maxWeight,
                origin = FontOrigin.FallbackToBundled,
                role = decl.role,
                faces = fallbackFaces,
                normalWeightRange = normalWeights.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
                italicWeightRange = italicWeights.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
            )
        }

        return defaultFallbackFamily(decl.family, decl.role)
    }

    private fun cloneFallbackComposeFont(systemFamilyName: String, face: ResolvedFontFace): Font? {
        val loaded = face.composeFont as? androidx.compose.ui.text.platform.LoadedFont ?: return null
        return PlatformFontFactory(
            identity = "fallback:$systemFamilyName->${loaded.identity}",
            data = loaded.data,
            weight = loaded.weight,
            style = loaded.style,
            variationSettings = loaded.variationSettings,
        )
    }

    private fun resolveFallbackBundledFamily(
        startDecl: DeckFontDeclaration,
        systemDeclarations: Map<String, DeckFontDeclaration>,
    ): ResolvedFontFamily? {
        val visited = hashSetOf(startDecl.family)
        var nextName = startDecl.fallback
        while (!nextName.isNullOrBlank()) {
            if (!visited.add(nextName)) {
                return null // Cycle already recorded by validateFontDeclarations.
            }
            val bundled = familiesByExactName[nextName]
            if (bundled != null && bundled.origin == FontOrigin.Bundled) {
                return bundled
            }
            nextName = systemDeclarations[nextName]?.fallback
        }
        return null
    }

    private fun defaultFallbackFamily(familyName: String, role: String?): ResolvedFontFamily =
        ResolvedFontFamily(
            familyName = familyName,
            resolvedFamilyName = "Default",
            fontFamily = FontFamily.Default,
            minWeight = 400,
            maxWeight = 400,
            origin = FontOrigin.DefaultFallback,
            role = role,
            faces = emptyList(),
            normalWeightRange = 400..400,
            italicWeightRange = null,
        )

    companion object {
        val DEFAULT_VARIABLE_WEIGHT_STEPS: List<Int> =
            listOf(100, 200, 300, 400, 500, 600, 700, 800, 900)

        /** Upper bound on memoized static-axis family variants per resolver. */
        internal const val MAX_AXIS_FAMILIES: Int = 64

        internal fun computeVariableWeightSteps(
            minWeight: Int,
            maxWeight: Int,
            candidateSteps: List<Int> = DEFAULT_VARIABLE_WEIGHT_STEPS,
        ): List<Int> {
            val lo = minWeight.coerceIn(1, 1000)
            val hi = maxWeight.coerceIn(lo, 1000)
            val result = sortedSetOf<Int>()
            result += lo
            result += hi
            for (step in candidateSteps) {
                if (step in lo..hi) {
                    result += step
                }
            }
            return result.toList()
        }
    }
}
