package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Immutable
import dev.sebastiano.ciacola.core.fonts.FontAxes
import dev.sebastiano.ciacola.core.fonts.FontValidationSeverity
import dev.sebastiano.ciacola.core.fonts.validateFontDeclarations
import dev.sebastiano.ciacola.syntax.theme.SyntaxColor
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs

enum class IssueSeverity {
    LoadError,
    LintError,
    LintWarning,
}

@Immutable
data class DocumentIssue(
    val severity: IssueSeverity,
    val code: String,
    val address: String,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
)

/**
 * Result of decoding and validating a `<deckId>.deck.json` text.
 */
sealed interface DeckDecodeResult {
    data class Success(
        val document: DeckDocument,
        val rawJson: JsonObject,
        val migratedFromVersion: Int? = null,
        val lintIssues: List<DocumentIssue> = emptyList(),
    ) : DeckDecodeResult

    /**
     * The file declares a `format` greater than [supportedVersion]. It never enters the strict
     * decoder and must open read-only without rewriting.
     */
    data class ReadOnlyNewerFormat(
        val foundVersion: Int,
        val supportedVersion: Int,
        val rawText: String,
        val message: String,
    ) : DeckDecodeResult

    data class Failure(
        val errors: List<DocumentIssue>,
    ) : DeckDecodeResult
}

/**
 * Pure `JsonObject -> JsonObject` migration step from [fromVersion] to `fromVersion + 1`.
 */
fun interface DeckMigrationStep {
    fun migrate(raw: JsonObject): JsonObject
}

/**
 * Ordered migration registry for older format versions (`format < supportedFormatVersion`).
 */
class DeckMigrations(
    val supportedFormatVersion: Int = CURRENT_DECK_FORMAT_VERSION,
    private val steps: Map<Int, DeckMigrationStep> = emptyMap(),
) {
    fun migrateInMemory(raw: JsonObject, fromVersion: Int): Result<JsonObject> {
        var current = raw
        var v = fromVersion
        while (v < supportedFormatVersion) {
            val step = steps[v]
                ?: return Result.failure(
                    IllegalStateException("No migration registered from deck format $v to ${v + 1}."),
                )
            current = step.migrate(current)
            v++
        }
        return Result.success(current)
    }

    companion object {
        val Default: DeckMigrations = DeckMigrations(supportedFormatVersion = CURRENT_DECK_FORMAT_VERSION)
    }
}

/**
 * Pre-parse JSON duplicate-key scanner, version-guarded decoder, migration runner, and full schema
 * & lint validator for `<deckId>.deck.json`.
 */
object DeckDocumentCodec {

    private val FORBIDDEN_SET_KEYS: Set<String> get() = DeckJsonScanner.FORBIDDEN_SET_KEYS
    private val ALLOWED_INTERACTIVE_VALUES: Set<String> get() = DeckJsonScanner.ALLOWED_INTERACTIVE_VALUES

    /**
     * Decodes and validates [rawText] with:
     * 1. Pre-parse duplicate JSON key scan (with exact 1-based line & column).
     * 2. JSON syntax parse (reporting line & column on syntax errors).
     * 3. `format` version guard: `> supported` returns [DeckDecodeResult.ReadOnlyNewerFormat]
     *    without touching the strict decoder; `< supported` runs pure in-memory migrations.
     * 4. Strict `kotlinx.serialization` decoding (`ignoreUnknownKeys = false`).
     * 5. Full semantic & lint validation ([validate]).
     */
    fun decode(
        rawText: String,
        specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
        effects: BuildEffectRegistry = BuildEffectRegistry.Standard,
        migrations: DeckMigrations = DeckMigrations.Default,
        assetExists: ((String) -> Boolean)? = null,
    ): DeckDecodeResult {
        val dupIssues = scanDuplicateJsonKeys(rawText)
        if (dupIssues.isNotEmpty()) {
            return DeckDecodeResult.Failure(dupIssues)
        }

        val parsedElement = try {
            JsonMergePatch.strictJson.parseToJsonElement(rawText)
        } catch (ex: Exception) {
            val (line, col) = extractLineColumn(ex.message, rawText)
            return DeckDecodeResult.Failure(
                listOf(
                    DocumentIssue(
                        severity = IssueSeverity.LoadError,
                        code = "MALFORMED_JSON",
                        address = "<root>",
                        message = ex.message ?: "Malformed JSON.",
                        line = line,
                        column = col,
                    ),
                ),
            )
        }

        if (parsedElement !is JsonObject) {
            return DeckDecodeResult.Failure(
                listOf(
                    DocumentIssue(
                        severity = IssueSeverity.LoadError,
                        code = "ROOT_NOT_OBJECT",
                        address = "<root>",
                        message = "Deck document root must be a JSON object.",
                        line = 1,
                        column = 1,
                    ),
                ),
            )
        }

        val formatEl = parsedElement["format"]
        val formatVersion = (formatEl as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (formatVersion == null) {
            return DeckDecodeResult.Failure(
                listOf(
                    DocumentIssue(
                        severity = IssueSeverity.LoadError,
                        code = "MISSING_OR_INVALID_FORMAT",
                        address = "<root>",
                        message = "Deck document must declare an integer 'format' version.",
                        line = 1,
                        column = 1,
                    ),
                ),
            )
        }

        if (formatVersion > migrations.supportedFormatVersion) {
            return DeckDecodeResult.ReadOnlyNewerFormat(
                foundVersion = formatVersion,
                supportedVersion = migrations.supportedFormatVersion,
                rawText = rawText,
                message = "Deck format version $formatVersion is newer than supported version " +
                    "${migrations.supportedFormatVersion}; opened read-only without modifying the file.",
            )
        }

        var migratedFrom: Int? = null
        val effectiveJson: JsonObject = if (formatVersion < migrations.supportedFormatVersion) {
            val migrated = migrations.migrateInMemory(parsedElement, formatVersion)
            if (migrated.isFailure) {
                return DeckDecodeResult.Failure(
                    listOf(
                        DocumentIssue(
                            severity = IssueSeverity.LoadError,
                            code = "MIGRATION_FAILED",
                            address = "<root>",
                            message = migrated.exceptionOrNull()?.message ?: "Deck migration failed.",
                        ),
                    ),
                )
            }
            migratedFrom = formatVersion
            migrated.getOrThrow()
        } else {
            parsedElement
        }

        val document = try {
            JsonMergePatch.strictJson.decodeFromJsonElement(DeckDocument.serializer(), effectiveJson)
        } catch (ex: SerializationException) {
            return DeckDecodeResult.Failure(DeckValidationHelpers.diagnoseDecodeFailure(effectiveJson, ex))
        } catch (ex: IllegalArgumentException) {
            return DeckDecodeResult.Failure(DeckValidationHelpers.diagnoseDecodeFailure(effectiveJson, ex))
        }

        val allIssues = validate(
            document = document,
            specimens = specimens,
            effects = effects,
            assetExists = assetExists,
        )
        val loadErrors = allIssues.filter { it.severity == IssueSeverity.LoadError }
        if (loadErrors.isNotEmpty()) {
            return DeckDecodeResult.Failure(loadErrors)
        }
        return DeckDecodeResult.Success(
            document = document,
            rawJson = effectiveJson,
            migratedFromVersion = migratedFrom,
            lintIssues = allIssues.filter { it.severity != IssueSeverity.LoadError },
        )
    }

    /**
     * Validates an already-decoded [DeckDocument], returning all [IssueSeverity.LoadError],
     * [IssueSeverity.LintError], and [IssueSeverity.LintWarning] issues with exact addresses.
     */
    fun validate(
        document: DeckDocument,
        specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
        effects: BuildEffectRegistry = BuildEffectRegistry.Standard,
        assetExists: ((String) -> Boolean)? = null,
    ): List<DocumentIssue> {
        val issues = mutableListOf<DocumentIssue>()
        val declaredFonts = validateDocumentHeader(document, issues)

        val seenSlideIds = HashSet<String>(document.slides.size * 2)
        var firstNonSkippedIdx: Int? = null
        var lastNonSkippedIdx: Int? = null
        for (idx in document.slides.indices) {
            if (!document.slides[idx].skipped) {
                if (firstNonSkippedIdx == null) firstNonSkippedIdx = idx
                lastNonSkippedIdx = idx
            }
        }

        for ((slideIdx, slide) in document.slides.withIndex()) {
            val slideAddr = slide.id.ifBlank { "slide[$slideIdx]" }
            if (slide.id.isBlank()) {
                issues += loadError("BLANK_SLIDE_ID", slideAddr, "Slide id must not be blank.")
            } else if (!seenSlideIds.add(slide.id)) {
                issues += loadError("DUPLICATE_SLIDE_ID", slideAddr, "Duplicate slide id '${slide.id}'.")
            }

            validateSlideBody(
                slide = slide,
                slideAddr = slideAddr,
                isFirstNonSkipped = slideIdx == firstNonSkippedIdx,
                isLastNonSkipped = slideIdx == lastNonSkippedIdx,
                paletteKnown = slide.palette == null || slide.palette in document.palettes,
                declaredFonts = declaredFonts,
                specimens = specimens,
                effects = effects,
                assetExists = assetExists,
                issues = issues,
            )
        }

        return issues
    }

    internal fun validateDocumentHeader(
        document: DeckDocument,
        issues: MutableList<DocumentIssue>,
    ): Set<String> {
        if (document.id.isBlank()) {
            issues += loadError("BLANK_DECK_ID", "<root>", "Deck 'id' must not be blank.")
        }
        if (document.stage.width <= 0 || document.stage.height <= 0) {
            issues += loadError(
                "INVALID_STAGE_SIZE",
                "<root>",
                "Stage size must be positive, got ${document.stage.width}x${document.stage.height}.",
            )
        }
        if (document.defaultTheme != null && document.themes.isNotEmpty() && document.defaultTheme !in document.themes) {
            issues += lintError(
                "UNKNOWN_DEFAULT_THEME",
                "<root>",
                "defaultTheme '${document.defaultTheme}' is not defined in 'themes'.",
            )
        }
        for ((loopId, period) in document.loopPeriodsMs) {
            if (period <= 0L) {
                issues += loadError(
                    "INVALID_LOOP_PERIOD",
                    "<root>/loopPeriodsMs/$loopId",
                    "Loop period for '$loopId' must be > 0, got $period.",
                )
            }
        }

        // Validate fonts
        for (fontIssue in validateFontDeclarations(document.fonts)) {
            issues += DocumentIssue(
                severity = if (fontIssue.severity == FontValidationSeverity.Error) IssueSeverity.LintError else IssueSeverity.LintWarning,
                code = fontIssue.code,
                address = "<root>/fonts/${fontIssue.family}",
                message = fontIssue.message,
            )
        }
        val declaredFonts: Set<String> = if (document.fonts.isEmpty()) {
            emptySet()
        } else {
            document.fonts.mapTo(hashSetOf()) { it.family }
        }

        // Validate theme code schemes, typography font references, and motion curves
        for ((themeName, themeSpec) in document.themes) {
            if (themeSpec.codeScheme != null &&
                themeSpec.customCodeScheme == null &&
                SyntaxColorSchemes.findById(themeSpec.codeScheme) == null
            ) {
                issues += lintError(
                    "UNKNOWN_CODE_SCHEME",
                    "<root>/themes/$themeName/codeScheme",
                    "Theme '$themeName' references unknown codeScheme '${themeSpec.codeScheme}'.",
                )
            }
            themeSpec.customCodeScheme?.let { custom ->
                validateCustomCodeScheme(
                    scheme = custom,
                    address = "<root>/themes/$themeName/customCodeScheme",
                    issues = issues,
                )
            }
            for ((role, style) in themeSpec.typography) {
                if (style.font != null && declaredFonts.isNotEmpty() && style.font !in declaredFonts) {
                    issues += lintError(
                        "UNKNOWN_FONT",
                        "<root>/themes/$themeName/$role",
                        "Theme typography role '$role' references undeclared font '${style.font}'.",
                    )
                }
                validateAxisTags(style.axes, "<root>/themes/$themeName/$role", issues)
            }
            themeSpec.motion?.let { motion ->
                validateTimingFields(
                    delayMs = null,
                    durationMs = motion.durationMs,
                    curve = motion.curve,
                    address = "<root>/themes/$themeName/motion",
                    issues = issues,
                )
            }
        }
        return declaredFonts
    }

    /**
     * Validates a single slide's body (excluding deck-wide blank/duplicate slide-ID checks).
     * Returns `true` if the slide references any image asset (background or node image) whose
     * validation depends on [assetExists].
     */
    internal fun validateSlideBody(
        slide: SlideSpec,
        slideAddr: String,
        isFirstNonSkipped: Boolean,
        isLastNonSkipped: Boolean,
        paletteKnown: Boolean,
        declaredFonts: Set<String>,
        specimens: DeckSpecimenRegistry,
        effects: BuildEffectRegistry,
        assetExists: ((String) -> Boolean)?,
        issues: MutableList<DocumentIssue>,
    ): Boolean {
        var hasImageRefs = false
        if (slide.palette != null && !paletteKnown) {
            issues += lintError("UNKNOWN_PALETTE", slideAddr, "Slide references unknown palette '${slide.palette}'.")
        }
        if (slide.background is Fill.Image) {
            hasImageRefs = true
            if (assetExists != null && !assetExists(slide.background.image.src)) {
                issues += lintError("MISSING_ASSET", slideAddr, "Background image asset '${slide.background.image.src}' not found.")
            }
        }

        if (slide.beats.isEmpty()) {
            issues += loadError("EMPTY_BEATS", slideAddr, "Slide '${slide.id}' must have at least one beat.")
            return hasImageRefs
        }

        for ((beatIdx, beat) in slide.beats.withIndex()) {
            val beatAddr = "$slideAddr@beat$beatIdx"
            if (beat.autoAdvanceMs != null && beat.autoAdvanceMs < 0) {
                issues += loadError("NEGATIVE_AUTO_ADVANCE", beatAddr, "autoAdvanceMs must be >= 0, got ${beat.autoAdvanceMs}.")
            }
            validatePhases(beat.phases, beatAddr, issues)
            validateStagger(beat.stagger, beatAddr, isTextContext = false, issues = issues)
        }

        if (slide.transition != null) {
            if (isLastNonSkipped) {
                issues += lintWarning(
                    "TRANSITION_ON_LAST_SLIDE",
                    slideAddr,
                    "Last non-skipped slide '${slide.id}' declares a transition, which has no target slide.",
                )
            }
            validateTransition(slide.transition, slideAddr, issues)
        }

        val allNodesById = LinkedHashMap<String, NodeSpec>()
        if (collectAndValidateNodes(
                nodes = slide.nodes,
                slide = slide,
                isFirstNonSkipped = isFirstNonSkipped,
                declaredFonts = declaredFonts,
                specimens = specimens,
                effects = effects,
                assetExists = assetExists,
                allNodesById = allNodesById,
                issues = issues,
            )
        ) {
            hasImageRefs = true
        }

        // Post-pass over all nodes on the slide for cross-node references (connectors, after, transition.text)
        validateSlideReferences(
            slide = slide,
            allNodesById = allNodesById,
            issues = issues,
        )
        return hasImageRefs
    }

    private fun collectAndValidateNodes(
        nodes: List<NodeSpec>,
        slide: SlideSpec,
        isFirstNonSkipped: Boolean,
        declaredFonts: Set<String>,
        specimens: DeckSpecimenRegistry,
        effects: BuildEffectRegistry,
        assetExists: ((String) -> Boolean)?,
        allNodesById: LinkedHashMap<String, NodeSpec>,
        issues: MutableList<DocumentIssue>,
    ): Boolean {
        var hasImageRefs = false
        for (node in nodes) {
            val addr = "${slide.id}/${node.id}"
            if (node.id.isBlank()) {
                issues += loadError("BLANK_NODE_ID", addr, "Node id must not be blank.")
            } else if (allNodesById.put(node.id, node) != null) {
                issues += loadError("DUPLICATE_NODE_ID", addr, "Duplicate node id '${node.id}' in slide '${slide.id}'.")
            }

            val payloadKinds = node.payloadKinds
            if (payloadKinds.size != 1) {
                issues += loadError(
                    "INVALID_PAYLOAD_COUNT",
                    addr,
                    "Node '${node.id}' must declare exactly one payload key, found ${payloadKinds.size}: ${payloadKinds.map { it.payloadKey }}.",
                )
            }
            val kind = payloadKinds.singleOrNull()

            if (node.opacity !in 0.0..1.0) {
                issues += loadError("OPACITY_OUT_OF_RANGE", addr, "Opacity ${node.opacity} must be in 0..1.")
            }
            if (node.interactive != null) {
                if (kind != NodeKind.Specimen) {
                    issues += loadError("INTERACTIVE_NON_SPECIMEN", addr, "'interactive' is only valid on specimen nodes.")
                } else if (node.interactive !in ALLOWED_INTERACTIVE_VALUES) {
                    issues += loadError(
                        "INVALID_INTERACTIVE_MODE",
                        addr,
                        "Invalid interactive mode '${node.interactive}' (expected 'pointer' or 'keyboard').",
                    )
                }
            }

            // Validate payload-specific rules
            node.text?.let { text ->
                if (text.font != null && declaredFonts.isNotEmpty() && text.font !in declaredFonts) {
                    issues += lintError("UNKNOWN_FONT", addr, "Text node references undeclared font '${text.font}'.")
                }
                validateAxisTags(text.axes, addr, issues)
                if (text.value is RichTextValue.Runs) {
                    for (run in text.value.runs) {
                        if (run.font != null && declaredFonts.isNotEmpty() && run.font !in declaredFonts) {
                            issues += lintError("UNKNOWN_FONT", addr, "Text run references undeclared font '${run.font}'.")
                        }
                        validateAxisTags(run.axes, addr, issues)
                    }
                }
                text.morph?.let { morph ->
                    validatePhases(morph.phases, "$addr/morph", issues)
                    validateStagger(morph.stagger, "$addr/morph", isTextContext = true, issues = issues)
                }
            }

            node.image?.let { img ->
                hasImageRefs = true
                if (assetExists != null && !assetExists(img.src)) {
                    issues += lintError("MISSING_ASSET", addr, "Image asset '${img.src}' not found.")
                }
                validateImagePlaybackFields(img, addr, issues)
            }

            node.specimen?.let { spec ->
                validateSpecimenNode(spec, slide, addr, specimens, issues)
            }

            node.loupe?.let { loupe ->
                validateLoupePayload(node, loupe, addr, issues)
            }

            // Validate builds
            validateNodeBuilds(
                node = node,
                kind = kind,
                slide = slide,
                isFirstNonSkipped = isFirstNonSkipped,
                specimens = specimens,
                effects = effects,
                addr = addr,
                issues = issues,
            )

            // Recurse into children
            if (node.children.isNotEmpty()) {
                if (collectAndValidateNodes(
                        nodes = node.children,
                        slide = slide,
                        isFirstNonSkipped = isFirstNonSkipped,
                        declaredFonts = declaredFonts,
                        specimens = specimens,
                        effects = effects,
                        assetExists = assetExists,
                        allNodesById = allNodesById,
                        issues = issues,
                    )
                ) {
                    hasImageRefs = true
                }
            }
        }
        return hasImageRefs
    }

    private fun validateSpecimenNode(
        spec: SpecimenPayload,
        slide: SlideSpec,
        addr: String,
        specimens: DeckSpecimenRegistry,
        issues: MutableList<DocumentIssue>,
    ) {
        val decl = specimens.find(spec.ref)
        if (decl == null) {
            issues += lintError("UNKNOWN_SPECIMEN_REF", addr, "Unknown specimen ref '${spec.ref}'.")
        } else {
            for (diag in ParamResolver.validateMap(decl.params, spec.params)) {
                issues += DocumentIssue(
                    severity = if (diag.isWarningOnly) IssueSeverity.LintWarning else IssueSeverity.LintError,
                    code = "SPECIMEN_PARAM_${diag.kind.name.uppercase()}",
                    address = "$addr/params/${diag.key}",
                    message = diag.message,
                )
            }
            for ((trackName, trackOverride) in spec.tracks) {
                if (decl.track(trackName) == null) {
                    issues += lintError("UNKNOWN_SPECIMEN_TRACK", "$addr/tracks/$trackName", "Specimen '${spec.ref}' declares no track '$trackName'.")
                }
                if (trackOverride.beat != null && trackOverride.beat !in slide.beats.indices) {
                    issues += loadError(
                        "BEAT_OUT_OF_RANGE",
                        "$addr/tracks/$trackName",
                        "Track beat ${trackOverride.beat} out of range 0..${slide.beats.lastIndex}.",
                    )
                }
                validateTimingFields(
                    delayMs = trackOverride.delayMs,
                    durationMs = trackOverride.durationMs,
                    curve = trackOverride.curve,
                    address = "$addr/tracks/$trackName",
                    issues = issues,
                )
            }
        }
    }

    private fun validateLoupePayload(
        loupeNode: NodeSpec,
        loupe: LoupePayload,
        addr: String,
        issues: MutableList<DocumentIssue>,
    ) {
        if (loupe.zoom <= 0.0) {
            issues += loadError("INVALID_LOUPE_ZOOM", addr, "Loupe zoom must be > 0, got ${loupe.zoom}.")
        }
        if (loupe.target == loupeNode.id) {
            issues += loadError("LOUPE_TARGET_SELF", addr, "Loupe '${loupeNode.id}' cannot target itself.")
            return
        }
        val strictDescendants = hashSetOf<String>()
        fun collectDescendants(children: List<NodeSpec>) {
            for (child in children) {
                strictDescendants += child.id
                collectDescendants(child.children)
            }
        }
        collectDescendants(loupe.children)
        if (loupe.target !in strictDescendants) {
            issues += loadError(
                "LOUPE_TARGET_NOT_DESCENDANT",
                addr,
                "Loupe '${loupeNode.id}' target '${loupe.target}' must be a strict descendant.",
            )
        }
    }

    private fun validateImagePlaybackFields(img: ImagePayload, addr: String, issues: MutableList<DocumentIssue>) {
        if (!(img.playbackRate > 0.0) || img.playbackRate.isInfinite()) {
            issues += lintError("INVALID_PLAYBACK_RATE", addr, "playbackRate must be a finite number > 0, got ${img.playbackRate}.")
        }
        for ((name, value) in listOf("startMs" to img.startMs, "endMs" to img.endMs, "posterMs" to img.posterMs)) {
            if (value != null && value < 0) {
                issues += lintError("NEGATIVE_CLIP_TIME", addr, "$name must be >= 0, got $value.")
            }
        }
        if (img.endMs != null && img.endMs <= (img.startMs ?: 0)) {
            issues += lintError("EMPTY_CLIP_TRIM", addr, "endMs (${img.endMs}) must be greater than startMs (${img.startMs ?: 0}).")
        }
        val poster = img.posterMs
        if (poster != null && ((img.startMs != null && poster < img.startMs) || (img.endMs != null && poster > img.endMs))) {
            issues += lintWarning("POSTER_OUTSIDE_TRIM", addr, "posterMs ($poster) lies outside the startMs..endMs trim.")
        }
        if (img.maxPixels != null && img.maxPixels <= 0L) {
            issues += lintError("INVALID_MAX_PIXELS", addr, "maxPixels must be > 0, got ${img.maxPixels}.")
        }
    }

    private fun validatePlaybackBuild(
        build: Build.Playback,
        kind: NodeKind?,
        buildAddr: String,
        issues: MutableList<DocumentIssue>,
    ) {
        if (kind != NodeKind.Image) {
            issues += lintError("PLAYBACK_ON_NON_IMAGE", buildAddr, "Playback builds only apply to image nodes.")
        }
        if (build.atMs != null && build.atMs < 0) {
            issues += lintError("NEGATIVE_CLIP_TIME", buildAddr, "atMs must be >= 0, got ${build.atMs}.")
        }
        if (build.action == PlaybackAction.Rate && build.rate == null) {
            issues += lintError("PLAYBACK_RATE_MISSING", buildAddr, "A 'rate' playback build needs a 'rate' value.")
        }
        if (build.action == PlaybackAction.Rate && build.atMs != null) {
            issues += lintWarning("PLAYBACK_AT_IGNORED", buildAddr, "atMs is ignored by a 'rate' playback build.")
        }
        val rate = build.rate
        if (rate != null && (!(rate > 0.0) || rate.isInfinite())) {
            issues += lintError("INVALID_PLAYBACK_RATE", buildAddr, "rate must be a finite number > 0, got $rate.")
        }
    }

    private fun validateNodeBuilds(
        node: NodeSpec,
        kind: NodeKind?,
        slide: SlideSpec,
        isFirstNonSkipped: Boolean,
        specimens: DeckSpecimenRegistry,
        effects: BuildEffectRegistry,
        addr: String,
        issues: MutableList<DocumentIssue>,
    ) {
        val seenBuildIds = hashSetOf<String>()
        var codeValueChangedAnyBeat = false
        val codeValueChangeCountByBeat = hashMapOf<Int, Int>()

        for ((buildIdx, build) in node.builds.withIndex()) {
            val buildAddr = build.id?.let { "$addr#$it" } ?: "$addr#builds[$buildIdx]"
            if (build.id != null) {
                if (build.id!!.isBlank()) {
                    issues += loadError("BLANK_BUILD_ID", buildAddr, "Build id must not be blank.")
                } else if (!seenBuildIds.add(build.id!!)) {
                    issues += loadError("DUPLICATE_BUILD_ID", buildAddr, "Duplicate build id '${build.id}' on node '${node.id}'.")
                }
            }

            if (build.beat !in slide.beats.indices) {
                issues += loadError(
                    "BEAT_OUT_OF_RANGE",
                    buildAddr,
                    "Build beat ${build.beat} out of range 0..${slide.beats.lastIndex}.",
                )
            }

            validateTimingFields(
                delayMs = build.delayMs,
                durationMs = build.durationMs,
                curve = build.curve,
                address = buildAddr,
                issues = issues,
            )

            when (build) {
                is Build.Enter -> {
                    if (isFirstNonSkipped && build.beat == 0) {
                        issues += lintWarning(
                            "FIRST_SLIDE_BEAT0_ENTER",
                            buildAddr,
                            "First non-skipped slide has no Start state; beat-0 enter build will render complete.",
                        )
                    }
                    validateEffectBuild(build.effect, build.direction, build.args, build.by, build.stagger, kind, node, buildAddr, effects, issues)
                    DeckValidationHelpers.validateMotionChannels(build.channels, buildAddr, issues)
                }
                is Build.Exit -> {
                    validateEffectBuild(build.effect, build.direction, build.args, build.by, build.stagger, kind, node, buildAddr, effects, issues)
                    DeckValidationHelpers.validateMotionChannels(build.channels, buildAddr, issues)
                }
                is Build.Emphasis -> {
                    validateEffectBuild(build.effect, build.direction, build.args, build.by, build.stagger, kind, node, buildAddr, effects, issues)
                    DeckValidationHelpers.validateMotionChannels(build.channels, buildAddr, issues)
                }
                is Build.Change -> {
                    build.morph?.let { morph ->
                        validatePhases(morph.phases, "$buildAddr/morph", issues)
                        validateStagger(morph.stagger, "$buildAddr/morph", isTextContext = true, issues = issues)
                    }
                    validateChangeSet(
                        node = node,
                        kind = kind,
                        build = build,
                        buildIdx = buildIdx,
                        buildAddr = buildAddr,
                        specimens = specimens,
                        issues = issues,
                    )
                    if (kind == NodeKind.Code) {
                        val codePatch = build.set["code"] as? JsonObject
                        if (codePatch != null && "value" in codePatch) {
                            codeValueChangedAnyBeat = true
                            val count = (codeValueChangeCountByBeat[build.beat] ?: 0) + 1
                            codeValueChangeCountByBeat[build.beat] = count
                            if (count > 1) {
                                issues += loadError(
                                    "CODE_MULTIPLE_VALUE_CHANGES_ON_BEAT",
                                    buildAddr,
                                    "Code node '${node.id}' cannot have more than one value change on beat ${build.beat}.",
                                )
                            }
                        }
                    }
                }
                is Build.Playback -> validatePlaybackBuild(build, kind, buildAddr, issues)
            }
        }

        // Changing code node requires explicit w & h.
        if (kind == NodeKind.Code && codeValueChangedAnyBeat) {
            val w = node.frame?.w
            val h = node.frame?.h
            if (w == null || h == null || w <= 0.0 || h <= 0.0) {
                issues += loadError(
                    "CODE_CHANGING_REQUIRES_EXPLICIT_SIZE",
                    addr,
                    "Code node '${node.id}' changes value across beats and must declare explicit positive frame.w and frame.h.",
                )
            }
        }
    }

    private fun validateEffectBuild(
        effectId: String,
        direction: String?,
        args: Map<String, JsonElement>,
        by: DeliveryBy,
        stagger: Stagger?,
        nodeKind: NodeKind?,
        node: NodeSpec,
        buildAddr: String,
        effects: BuildEffectRegistry,
        issues: MutableList<DocumentIssue>,
    ) {
        val isTextDelivery = by == DeliveryBy.Paragraph || by == DeliveryBy.Word || by == DeliveryBy.Character
        validateStagger(stagger, buildAddr, isTextContext = isTextDelivery, issues = issues)

        val decl = effects.find(effectId)
        if (decl == null) {
            issues += lintError("UNKNOWN_EFFECT", buildAddr, "Unknown build effect '$effectId'.")
            return
        }
        if (nodeKind != null && nodeKind !in decl.supportedNodeKinds) {
            issues += lintError(
                "EFFECT_UNSUPPORTED_NODE_KIND",
                buildAddr,
                "Effect '$effectId' is not supported on node kind '${nodeKind.payloadKey}'.",
            )
        }
        if (direction != null && decl.supportedDirections.isNotEmpty() && direction !in decl.supportedDirections) {
            issues += lintError(
                "EFFECT_UNSUPPORTED_DIRECTION",
                buildAddr,
                "Direction '$direction' is not valid for effect '$effectId'.",
            )
        }
        for (diag in ParamResolver.validateMap(decl.args, args)) {
            issues += DocumentIssue(
                severity = if (diag.isWarningOnly) IssueSeverity.LintWarning else IssueSeverity.LintError,
                code = "EFFECT_ARG_${diag.kind.name.uppercase()}",
                address = "$buildAddr/args/${diag.key}",
                message = diag.message,
            )
        }
        if (isTextDelivery && node.text != null && stagger != null && stagger.manual.isNotEmpty()) {
            val tokens = TextTokenizer.tokenizeForDelivery(node.text.value.plainText, by)
            val validAddresses = tokens.mapTo(hashSetOf()) { it.address }
            for (key in stagger.manual.keys) {
                if (key !in validAddresses) {
                    issues += lintWarning(
                        "MANUAL_DELAY_DANGLING_FRAGMENT",
                        buildAddr,
                        "Manual stagger entry '$key' addresses no fragment in text.",
                    )
                }
            }
        }
    }

    private fun validateChangeSet(
        node: NodeSpec,
        kind: NodeKind?,
        build: Build.Change,
        buildIdx: Int,
        buildAddr: String,
        specimens: DeckSpecimenRegistry,
        issues: MutableList<DocumentIssue>,
    ) {
        for (key in build.set.keys) {
            if (key in FORBIDDEN_SET_KEYS) {
                issues += loadError("SET_FORBIDDEN_KEY", "$buildAddr/set/$key", "'set' may not modify '$key'.")
            } else if (key in NodeKind.ALL_PAYLOAD_KEYS && kind != null && key != kind.payloadKey) {
                issues += loadError(
                    "SET_PAYLOAD_SWAP",
                    "$buildAddr/set/$key",
                    "'set' on '${kind.payloadKey}' node cannot introduce payload '$key'.",
                )
            } else if (key !in ALLOWED_NODE_SET_KEYS) {
                issues += loadError("SET_UNKNOWN_KEY", "$buildAddr/set/$key", "Unknown node property '$key' inside 'set'.")
            }
        }

        // Validate nested keys inside `set` even for JsonNull removals (which MergePatch removes before decode).
        validateSetSchemaKeys(build.set, kind, buildAddr, issues)

        // Check `children` forbidden inside container payload patch.
        if (kind in setOf(NodeKind.Stack, NodeKind.Row, NodeKind.Column, NodeKind.Loupe)) {
            val payloadObj = build.set[kind!!.payloadKey] as? JsonObject
            if (payloadObj != null && "children" in payloadObj) {
                issues += loadError("SET_FORBIDDEN_CHILDREN", "$buildAddr/set/${kind.payloadKey}/children", "'set' may not modify container 'children'.")
            }
        }

        // Check post-patch node decodes strictly (catches wrong types & invalid sub-keys on non-null leaves).
        val beforeNode = try {
            JsonMergePatch.resolveNodeBeforeBuild(node, buildIdx)
        } catch (_: Exception) {
            node
        }
        val afterNode = try {
            val beforeJson = JsonMergePatch.strictJson.encodeToJsonElement(NodeSpec.serializer(), beforeNode).jsonObject
            val patchedJson = JsonMergePatch.apply(beforeJson, build.set).jsonObject
            JsonMergePatch.strictJson.decodeFromJsonElement(NodeSpec.serializer(), patchedJson)
        } catch (ex: Exception) {
            issues += loadError(
                "SET_INVALID_PATCH",
                buildAddr,
                "Applying 'set' produced an invalid node: ${ex.message}",
            )
            return
        }

        if (afterNode.opacity !in 0.0..1.0) {
            issues += loadError("OPACITY_OUT_OF_RANGE", buildAddr, "Patched opacity ${afterNode.opacity} must be in 0..1.")
        }
        if (build.set["image"] is JsonObject) afterNode.image?.let { validateImagePlaybackFields(it, "$buildAddr/set/image", issues) }

        // Validate patched specimen params if present
        if (afterNode.specimen != null) {
            val decl = specimens.find(afterNode.specimen.ref)
            if (decl != null) {
                val specPatch = build.set["specimen"] as? JsonObject
                val paramsPatch = specPatch?.get("params") as? JsonObject
                if (paramsPatch != null) {
                    val nonNullParams = paramsPatch.filterValues { it !is JsonNull }
                    for (diag in ParamResolver.validateMap(decl.params, nonNullParams)) {
                        issues += DocumentIssue(
                            severity = if (diag.isWarningOnly) IssueSeverity.LintWarning else IssueSeverity.LintError,
                            code = "SPECIMEN_PARAM_${diag.kind.name.uppercase()}",
                            address = "$buildAddr/set/specimen/params/${diag.key}",
                            message = diag.message,
                        )
                    }
                }
            }
        }

        // Validate manual delays on text morph change
        val effectiveMorph = build.morph ?: afterNode.text?.morph
        val fromText = beforeNode.text?.value?.plainText
        val toText = afterNode.text?.value?.plainText
        if (effectiveMorph != null && fromText != null && toText != null && effectiveMorph.stagger?.manual?.isNotEmpty() == true) {
            val diff = TextTokenizer.diff(fromText, toText, effectiveMorph.by, effectiveMorph.pruneStopwords)
            val validAddresses = diff.fragments.mapTo(hashSetOf()) { it.address }
            for (manualKey in effectiveMorph.stagger.manual.keys) {
                if (manualKey !in validAddresses) {
                    issues += lintWarning(
                        "MANUAL_DELAY_DANGLING_FRAGMENT",
                        buildAddr,
                        "Manual delay '$manualKey' addresses no fragment in text morph.",
                    )
                }
            }
        }

        // Validate SVG motion path endpoint against frame delta
        if (build.path != null) {
            val endPoint = parseSvgPathEndpoint(build.path)
            if (endPoint == null) {
                issues += lintError("INVALID_MOTION_PATH", buildAddr, "Could not parse SVG motion path '${build.path}'.")
            } else {
                val dx = (afterNode.frame?.x ?: beforeNode.frame?.x ?: 0.0) - (beforeNode.frame?.x ?: 0.0)
                val dy = (afterNode.frame?.y ?: beforeNode.frame?.y ?: 0.0) - (beforeNode.frame?.y ?: 0.0)
                if (abs(endPoint.first - dx) > 0.5 || abs(endPoint.second - dy) > 0.5) {
                    issues += lintError(
                        "MOTION_PATH_ENDPOINT_MISMATCH",
                        buildAddr,
                        "Motion path ends at (${endPoint.first}, ${endPoint.second}) but frame delta is ($dx, $dy).",
                    )
                }
            }
        }
    }

    private fun validateCustomCodeScheme(
        scheme: CodeSchemeSpec,
        address: String,
        issues: MutableList<DocumentIssue>,
    ) {
        if (scheme.id.isBlank()) {
            issues += lintError("INVALID_CUSTOM_CODE_SCHEME", "$address/id", "Custom code scheme 'id' must not be blank.")
        }
        if (scheme.name.isBlank()) {
            issues += lintError("INVALID_CUSTOM_CODE_SCHEME", "$address/name", "Custom code scheme 'name' must not be blank.")
        }
        if (parseSpecAppearanceOrNull(scheme.appearance) == null) {
            issues += lintError(
                "INVALID_CUSTOM_CODE_SCHEME",
                "$address/appearance",
                "Unknown customCodeScheme appearance '${scheme.appearance}'.",
            )
        }
        fun checkCssColor(value: String?, field: String) {
            if (value != null && SyntaxColor.parseCssHexOrNull(value) == null) {
                issues += lintError(
                    "INVALID_CUSTOM_CODE_SCHEME",
                    "$address/$field",
                    "Invalid CSS hex color '$value' in customCodeScheme.$field.",
                )
            }
        }
        checkCssColor(scheme.background, "background")
        checkCssColor(scheme.foreground, "foreground")
        checkCssColor(scheme.selectionBackground, "selectionBackground")
        checkCssColor(scheme.lineHighlightBackground, "lineHighlightBackground")
        checkCssColor(scheme.lineNumberForeground, "lineNumberForeground")
        checkCssColor(scheme.diffInserted, "diffInserted")
        checkCssColor(scheme.diffDeleted, "diffDeleted")
        checkCssColor(scheme.diffChanged, "diffChanged")
        for ((key, style) in scheme.tokens) {
            if (key != "functionDeclaration" && key !in CODE_SCHEME_TOKEN_KEYS) {
                issues += lintWarning(
                    "UNKNOWN_CODE_SCHEME_TOKEN",
                    "$address/tokens/$key",
                    "Unknown token key '$key' in customCodeScheme.tokens.",
                )
            }
            checkCssColor(style.fg, "tokens/$key/fg")
            checkCssColor(style.bg, "tokens/$key/bg")
        }
    }

    private fun validateSetSchemaKeys(
        setObj: JsonObject,
        kind: NodeKind?,
        buildAddr: String,
        issues: MutableList<DocumentIssue>,
    ) = DeckValidationHelpers.validateSetSchemaKeys(setObj, kind, buildAddr, issues)

    private fun validateSlideReferences(
        slide: SlideSpec,
        allNodesById: Map<String, NodeSpec>,
        issues: MutableList<DocumentIssue>,
    ) = DeckValidationHelpers.validateSlideReferences(slide, allNodesById, issues)

    private fun validateTransition(
        transition: TransitionSpec,
        slideAddr: String,
        issues: MutableList<DocumentIssue>,
    ) = DeckValidationHelpers.validateTransition(transition, slideAddr, issues)

    private fun validatePhases(phases: Phases?, address: String, issues: MutableList<DocumentIssue>) =
        DeckValidationHelpers.validatePhases(phases, address, issues)

    private fun validateStagger(
        stagger: Stagger?,
        address: String,
        isTextContext: Boolean,
        issues: MutableList<DocumentIssue>,
    ) = DeckValidationHelpers.validateStagger(stagger, address, isTextContext, issues)

    private fun validateTimingFields(
        delayMs: Int?,
        durationMs: Int?,
        curve: Curve?,
        address: String,
        issues: MutableList<DocumentIssue>,
    ) = DeckValidationHelpers.validateTimingFields(delayMs, durationMs, curve, address, issues)

    /**
     * Scans raw JSON text before `kotlinx.serialization` parsing to detect any duplicate member
     * keys within the same JSON object, reporting exact 1-based line and column numbers.
     */
    fun scanDuplicateJsonKeys(text: String): List<DocumentIssue> =
        DeckJsonScanner.scanDuplicateJsonKeys(text)

    internal fun parseSvgPathEndpoint(pathData: String): Pair<Double, Double>? =
        DeckJsonScanner.parseSvgPathEndpoint(pathData)

    private fun extractLineColumn(message: String?, text: String): Pair<Int?, Int?> =
        DeckJsonScanner.extractLineColumn(message, text)

    /**
     * Font-independent `axes` checks: every key must be a well-formed 4-character OpenType tag.
     * Range and support checks need the resolved face and run in the deck linter.
     */
    private fun validateAxisTags(axes: Map<String, Double>, address: String, issues: MutableList<DocumentIssue>) {
        for ((tag, value) in axes) {
            if (!FontAxes.isValidTag(tag)) {
                issues += lintError(
                    "INVALID_FONT_AXIS_TAG",
                    address,
                    "Font axis tag '$tag' must be exactly 4 printable ASCII characters (e.g. 'wdth', 'GRAD').",
                )
            } else if (!value.isFinite()) {
                issues += lintError("INVALID_FONT_AXIS_VALUE", address, "Font axis '$tag' value must be a finite number.")
            }
        }
    }

    private fun loadError(code: String, address: String, message: String) =
        DocumentIssue(IssueSeverity.LoadError, code, address, message)

    private fun lintError(code: String, address: String, message: String) =
        DocumentIssue(IssueSeverity.LintError, code, address, message)

    private fun lintWarning(code: String, address: String, message: String) =
        DocumentIssue(IssueSeverity.LintWarning, code, address, message)

    private val ALLOWED_NODE_SET_KEYS: Set<String> get() = DeckJsonScanner.ALLOWED_NODE_SET_KEYS

    internal fun isValidHexColor(value: String): Boolean = DeckJsonScanner.isValidHexColor(value)
}
