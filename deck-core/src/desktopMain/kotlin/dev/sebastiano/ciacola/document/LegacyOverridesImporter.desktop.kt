package dev.sebastiano.ciacola.document

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Result of importing legacy `.storyboard/` override files into a [DeckDocument].
 */
data class LegacyImportReport(
    val document: DeckDocument,
    val appliedItems: List<String>,
    val unplacedItems: List<String>,
    val archivedFiles: List<Path>,
)

/**
 * One-time importer migrating legacy `.storyboard/` element, timeline, and design-system override
 * JSON files onto a [DeckDocument] and archiving (never deleting) the original files.
 */
object LegacyOverridesImporter {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
    }

    fun importFromFiles(
        document: DeckDocument,
        elementOverridesFile: Path? = null,
        timelineOverridesFile: Path? = null,
        designSystemOverridesByTheme: Map<String, Path> = emptyMap(),
        archiveDir: Path? = null,
    ): LegacyImportReport {
        var current = document
        val applied = mutableListOf<String>()
        val unplaced = mutableListOf<String>()
        val toArchive = mutableListOf<Path>()

        if (elementOverridesFile != null && Files.isRegularFile(elementOverridesFile)) {
            val root = lenientJson.parseToJsonElement(
                Files.readString(elementOverridesFile, StandardCharsets.UTF_8),
            ).jsonObject
            current = applyElementOverrides(current, root, applied, unplaced)
            toArchive.add(elementOverridesFile)
        }

        if (timelineOverridesFile != null && Files.isRegularFile(timelineOverridesFile)) {
            val root = lenientJson.parseToJsonElement(
                Files.readString(timelineOverridesFile, StandardCharsets.UTF_8),
            ).jsonObject
            current = applyTimelineOverrides(current, root, applied, unplaced)
            toArchive.add(timelineOverridesFile)
        }

        for ((themeName, path) in designSystemOverridesByTheme.entries.sortedBy { it.key }) {
            if (!Files.isRegularFile(path)) continue
            val root = lenientJson.parseToJsonElement(
                Files.readString(path, StandardCharsets.UTF_8),
            ).jsonObject
            current = applyDesignSystemOverrides(current, themeName, root, applied, unplaced)
            toArchive.add(path)
        }

        val archived = mutableListOf<Path>()
        val uniqueToArchive = toArchive.distinctBy { it.toAbsolutePath().normalize() }
        if (archiveDir != null && uniqueToArchive.isNotEmpty()) {
            Files.createDirectories(archiveDir)
            for (src in uniqueToArchive) {
                if (!Files.exists(src)) continue
                val dst = uniqueArchiveDestination(archiveDir, src.fileName.toString())
                Files.move(src, dst)
                archived.add(dst)
            }
        }

        return LegacyImportReport(
            document = current,
            appliedItems = applied,
            unplacedItems = unplaced,
            archivedFiles = archived,
        )
    }

    private val archiveTimestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

    private fun uniqueArchiveDestination(archiveDir: Path, fileName: String): Path {
        val direct = archiveDir.resolve(fileName)
        if (!Files.exists(direct)) return direct
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val stamp = archiveTimestampFormatter.format(Instant.now())
        val stamped = archiveDir.resolve("$stem-$stamp$ext")
        if (!Files.exists(stamped)) return stamped
        var suffix = 1
        while (true) {
            val candidate = archiveDir.resolve("$stem-$stamp-$suffix$ext")
            if (!Files.exists(candidate)) return candidate
            suffix++
        }
    }

    private fun applyElementOverrides(
        document: DeckDocument,
        root: JsonObject,
        applied: MutableList<String>,
        unplaced: MutableList<String>,
    ): DeckDocument {
        val elements = root["elements"] as? JsonObject ?: return document
        var doc = document
        for ((elementId, rawPatch) in elements.entries.sortedBy { it.key }) {
            val patch = rawPatch as? JsonObject ?: continue
            val mergePatch = legacyElementObjToJsonPatch(patch)
            if (mergePatch.isEmpty()) continue
            val (baseId, beat) = parseBeatElementId(elementId)
            val located = findSlideAndNode(doc, baseId)
            if (located == null) {
                unplaced += "element:$elementId (no matching node '$baseId')"
                continue
            }
            val (slideId, _) = located
            val targetBeat = beat ?: 0
            doc = doc.copy(
                slides = doc.slides.map { slide ->
                    if (slide.id != slideId) {
                        slide
                    } else {
                        slide.copy(
                            nodes = updateNodeTree(slide.nodes, baseId) { node ->
                                JsonMergePatch.applyEditAtBeat(node, targetBeat, mergePatch)
                            },
                        )
                    }
                },
            )
            applied += "element:$elementId -> $slideId/$baseId@b$targetBeat"
        }
        return doc
    }

    private fun applyTimelineOverrides(
        document: DeckDocument,
        root: JsonObject,
        applied: MutableList<String>,
        unplaced: MutableList<String>,
    ): DeckDocument {
        var doc = document

        val loopPeriods = root["loopPeriodsMs"] as? JsonObject
        if (loopPeriods != null && loopPeriods.isNotEmpty()) {
            val mergedLoops = LinkedHashMap(doc.loopPeriodsMs)
            for ((loopId, rawPeriod) in loopPeriods.entries.sortedBy { it.key }) {
                val periodMs = rawPeriod.primLongOrNull() ?: continue
                if (periodMs > 0L) {
                    mergedLoops[loopId] = periodMs
                    applied += "loop:$loopId=${periodMs}ms"
                } else {
                    unplaced += "loop:$loopId (non-positive period $periodMs)"
                }
            }
            doc = doc.copy(loopPeriodsMs = mergedLoops)
        }

        val sceneBeats = root["sceneBeats"] as? JsonObject
        if (sceneBeats != null) {
            for ((sceneKey, rawBeatOv) in sceneBeats.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }) {
                val sceneIndex = sceneKey.toIntOrNull() ?: continue
                val beatOv = rawBeatOv as? JsonObject ?: continue
                val slide = doc.slides.getOrNull(sceneIndex)
                if (slide == null) {
                    unplaced += "sceneBeats:$sceneIndex (no slide at index $sceneIndex)"
                    continue
                }
                val injected = (beatOv["injectedAfterBeats"] as? JsonArray)
                    ?.mapNotNull { it.primIntOrNull() }
                    .orEmpty()
                val deleted = (beatOv["deletedBeats"] as? JsonArray)
                    ?.mapNotNull { it.primIntOrNull() }
                    .orEmpty()
                for (afterBeat in injected.sortedDescending()) {
                    val curSlide = doc.slides.first { it.id == slide.id }
                    val insertAt = (afterBeat + 1).coerceIn(1, curSlide.beats.size)
                    doc = DeckStructureEdits.insertBeat(doc, slide.id, insertAt)
                    applied += "sceneBeats:${slide.id} inserted beat at $insertAt"
                }
                for (delBeat in deleted.sortedDescending()) {
                    val curSlide = doc.slides.first { it.id == slide.id }
                    if (delBeat in 1 until curSlide.beats.size) {
                        doc = DeckStructureEdits.deleteBeat(doc, slide.id, delBeat)
                        applied += "sceneBeats:${slide.id} deleted beat $delBeat"
                    } else {
                        unplaced += "sceneBeats:${slide.id} delete beat $delBeat out of range"
                    }
                }
            }
        }

        val tracks = root["tracks"] as? JsonObject
        if (tracks != null) {
            for ((trackId, rawOv) in tracks.entries.sortedBy { it.key }) {
                val ov = rawOv as? JsonObject ?: continue
                val updated = applySingleTrackOverride(doc, trackId, ov)
                if (updated != null) {
                    doc = updated
                    applied += "track:$trackId"
                } else {
                    unplaced += "track:$trackId (no matching build or specimen track)"
                }
            }
        }

        return doc
    }

    private fun applySingleTrackOverride(
        document: DeckDocument,
        trackId: String,
        ov: JsonObject,
    ): DeckDocument? {
        var matched = false
        val enterBeat = ov["enterBeat"]?.primIntOrNull()
        val delayMs = ov["delayMs"]?.primIntOrNull()
        val durationMs = ov["durationMs"]?.primIntOrNull()
        val curve = (ov["curve"] as? JsonObject)?.toDocCurve()

        val newSlides = document.slides.map { slide ->
            val updatedNodes = mapAllNodes(slide.nodes) { node ->
                val matchesNode = trackId == node.id || trackId == "${slide.id}/${node.id}"
                val updatedBuilds = node.builds.map { build ->
                    val buildMatches = matchesNode ||
                        (build.id != null && (trackId == "${node.id}#${build.id}" || trackId == "${slide.id}/${node.id}#${build.id}"))
                    if (!buildMatches) {
                        build
                    } else {
                        matched = true
                        patchBuildTiming(build, ov, curve)
                    }
                }
                val updatedSpecimen = node.specimen?.let { spec ->
                    val trackName = trackId.substringAfter("${node.id}#", missingDelimiterValue = "")
                    if (trackName.isNotEmpty() && (trackId == "${node.id}#$trackName" || trackId == "${slide.id}/${node.id}#$trackName")) {
                        matched = true
                        val existing = spec.tracks[trackName] ?: SpecimenTrackOverride()
                        val next = existing.copy(
                            beat = enterBeat ?: existing.beat,
                            delayMs = delayMs ?: existing.delayMs,
                            durationMs = durationMs ?: existing.durationMs,
                            curve = curve ?: existing.curve,
                        )
                        spec.copy(tracks = spec.tracks + (trackName to next))
                    } else {
                        spec
                    }
                }
                node.copy(builds = updatedBuilds, specimen = updatedSpecimen)
            }
            slide.copy(nodes = updatedNodes)
        }
        return if (matched) document.copy(slides = newSlides) else null
    }

    private fun patchBuildTiming(build: Build, ov: JsonObject, curve: Curve?): Build {
        val enterBeat = ov["enterBeat"]?.primIntOrNull()
        val exitBeat = ov["exitBeat"]?.primIntOrNull()
        val delayMs = ov["delayMs"]?.primIntOrNull()
        val durationMs = ov["durationMs"]?.primIntOrNull()
        val staggerStepMs = ov["staggerStepMs"]?.primIntOrNull()
        return when (build) {
            is Build.Enter -> build.copy(
                beat = enterBeat ?: build.beat,
                delayMs = delayMs ?: build.delayMs,
                durationMs = durationMs ?: build.durationMs,
                curve = curve ?: build.curve,
                stagger = staggerStepMs?.let { (build.stagger ?: Stagger()).copy(ms = it) } ?: build.stagger,
            )
            is Build.Exit -> build.copy(
                beat = exitBeat ?: build.beat,
                delayMs = delayMs ?: build.delayMs,
                durationMs = durationMs ?: build.durationMs,
                curve = curve ?: build.curve,
                stagger = staggerStepMs?.let { (build.stagger ?: Stagger()).copy(ms = it) } ?: build.stagger,
            )
            is Build.Emphasis -> build.copy(
                delayMs = delayMs ?: build.delayMs,
                durationMs = durationMs ?: build.durationMs,
                curve = curve ?: build.curve,
                stagger = staggerStepMs?.let { (build.stagger ?: Stagger()).copy(ms = it) } ?: build.stagger,
            )
            is Build.Change -> build.copy(
                delayMs = delayMs ?: build.delayMs,
                durationMs = durationMs ?: build.durationMs,
                curve = curve ?: build.curve,
            )
        }
    }

    private fun applyDesignSystemOverrides(
        document: DeckDocument,
        themeName: String,
        root: JsonObject,
        applied: MutableList<String>,
        unplaced: MutableList<String>,
    ): DeckDocument {
        // Unwrap dual-preset wrapper (`stageDark` / `keynoteLight`) if present, else read flat object.
        val presetSection = when {
            themeName.equals("light", ignoreCase = true) && root["keynoteLight"] is JsonObject ->
                root["keynoteLight"] as JsonObject
            themeName.equals("dark", ignoreCase = true) && root["stageDark"] is JsonObject ->
                root["stageDark"] as JsonObject
            else -> root
        }

        val existing = document.themes[themeName]
        if (existing == null) {
            if (presetSection.isNotEmpty()) {
                unplaced += "theme:$themeName (theme not declared in document)"
            }
            return document
        }

        var changed = false
        val colors = LinkedHashMap(existing.colors)
        val cObj = presetSection["colors"] as? JsonObject
        val colorFieldPairs = listOf(
            "background" to "backgroundArgb",
            "surface" to "surfaceArgb",
            "elevatedSurface" to "elevatedSurfaceArgb",
            "foreground" to "foregroundArgb",
            "muted" to "mutedArgb",
            "subtle" to "subtleArgb",
            "accent" to "accentArgb",
            "secondary" to "secondaryArgb",
            "positive" to "positiveArgb",
            "warning" to "warningArgb",
            "danger" to "dangerArgb",
            "border" to "borderArgb",
            "chipBackground" to "chipBackgroundArgb",
            "onAccent" to "onAccentArgb",
        )
        if (cObj != null) {
            for ((token, field) in colorFieldPairs) {
                val argb = cObj[field]?.primLongOrNull()
                if (argb != null) {
                    colors[token] = argbToHex(argb)
                    applied += "theme:$themeName.colors.$token"
                    changed = true
                }
            }
        }

        val typography = LinkedHashMap(existing.typography)
        val tObj = presetSection["typography"] as? JsonObject
        val roles = listOf(
            "hero", "title", "heading", "subheading", "body", "bodyEmphasis",
            "callout", "eyebrow", "code", "codeEmphasis", "stamp",
        )
        if (tObj != null) {
            for (role in roles) {
                val ov = tObj[role] as? JsonObject ?: continue
                val sizeSp = ov["fontSizeSp"]?.primDoubleOrNull()
                val lineHeightSp = ov["lineHeightSp"]?.primDoubleOrNull()
                val weight = ov["fontWeight"]?.primIntOrNull()
                val trackingSp = ov["letterSpacingSp"]?.primDoubleOrNull()
                if (sizeSp == null && lineHeightSp == null && weight == null && trackingSp == null) continue
                val prev = typography[role] ?: TextStyleOverride()
                typography[role] = prev.copy(
                    size = sizeSp ?: prev.size,
                    lineHeight = lineHeightSp ?: prev.lineHeight,
                    weight = weight ?: prev.weight,
                    tracking = trackingSp ?: prev.tracking,
                )
                applied += "theme:$themeName.typography.$role"
                changed = true
            }
        }

        val mObj = presetSection["motion"] as? JsonObject
        val defaultDur = mObj?.get("fadeMoveDurationMs")?.primIntOrNull()
            ?: mObj?.get("dissolveDurationMs")?.primIntOrNull()
            ?: mObj?.get("sharedBoundsDurationMs")?.primIntOrNull()
        val motion = if (defaultDur != null) {
            applied += "theme:$themeName.motion.durationMs"
            changed = true
            (existing.motion ?: ThemeMotionDefaults()).copy(durationMs = defaultDur)
        } else {
            existing.motion
        }

        if (!changed) return document
        val updatedTheme = existing.copy(
            colors = colors,
            typography = typography,
            motion = motion,
        )
        return document.copy(themes = document.themes + (themeName to updatedTheme))
    }

    private fun legacyElementObjToJsonPatch(patch: JsonObject): JsonObject = buildJsonObject {
        val text = patch["text"]?.primStringOrNull()
        val textColorArgb = patch["textColorArgb"]?.primLongOrNull()
        val fontSizeSp = patch["fontSizeSp"]?.primDoubleOrNull()
        val fontWeight = patch["fontWeight"]?.primIntOrNull()
        if (text != null || textColorArgb != null || fontSizeSp != null || fontWeight != null) {
            put(
                "text",
                buildJsonObject {
                    text?.let { put("value", JsonPrimitive(it)) }
                    textColorArgb?.let { put("color", JsonPrimitive(argbToHex(it))) }
                    fontSizeSp?.let { put("size", JsonPrimitive(it)) }
                    fontWeight?.let { put("weight", JsonPrimitive(it)) }
                },
            )
        }
        val offsetX = patch["offsetXDp"]?.primDoubleOrNull()
        val offsetY = patch["offsetYDp"]?.primDoubleOrNull()
        if (offsetX != null || offsetY != null) {
            put(
                "offset",
                buildJsonObject {
                    put("x", JsonPrimitive(offsetX ?: 0.0))
                    put("y", JsonPrimitive(offsetY ?: 0.0))
                },
            )
        }
        val fillArgb = patch["fillColorArgb"]?.primLongOrNull()
        val borderArgb = patch["borderColorArgb"]?.primLongOrNull()
        val borderWidth = patch["borderWidthDp"]?.primDoubleOrNull()
        val cornerRadius = patch["cornerRadiusDp"]?.primDoubleOrNull()
        val padding = patch["paddingDp"]?.primDoubleOrNull()
        if (fillArgb != null || borderArgb != null || borderWidth != null || cornerRadius != null || padding != null) {
            put(
                "surface",
                buildJsonObject {
                    fillArgb?.let { put("fill", JsonPrimitive(argbToHex(it))) }
                    if (borderArgb != null || borderWidth != null) {
                        put(
                            "stroke",
                            buildJsonObject {
                                borderArgb?.let { put("color", JsonPrimitive(argbToHex(it))) }
                                borderWidth?.let { put("width", JsonPrimitive(it)) }
                            },
                        )
                    }
                    cornerRadius?.let { put("radius", JsonPrimitive(it)) }
                    padding?.let { put("padding", JsonPrimitive(it)) }
                },
            )
        }
        val uniforms = patch["uniforms"] as? JsonObject
        if (uniforms != null && uniforms.isNotEmpty()) {
            put(
                "specimen",
                buildJsonObject {
                    put(
                        "params",
                        buildJsonObject {
                            for ((k, v) in uniforms.entries.sortedBy { it.key }) {
                                v.primDoubleOrNull()?.let { put(k, JsonPrimitive(it)) }
                            }
                        },
                    )
                },
            )
        }
    }

    private fun JsonObject.toDocCurve(): Curve {
        val isSpring = this["isSpring"]?.primBooleanOrNull() == true
        val preset = this["preset"]?.primStringOrNull() ?: "KeynoteEase"
        return when {
            isSpring -> Curve.Spring(
                damping = this["dampingRatio"]?.primDoubleOrNull() ?: 0.78,
                stiffness = this["stiffness"]?.primDoubleOrNull() ?: 520.0,
            )
            preset.equals("Linear", ignoreCase = true) -> Curve.Preset("linear")
            else -> Curve.Bezier(
                x1 = this["x1"]?.primDoubleOrNull() ?: 0.25,
                y1 = this["y1"]?.primDoubleOrNull() ?: 0.1,
                x2 = this["x2"]?.primDoubleOrNull() ?: 0.25,
                y2 = this["y2"]?.primDoubleOrNull() ?: 1.0,
            )
        }
    }

    private fun JsonElement.primStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonElement.primIntOrNull(): Int? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private fun JsonElement.primLongOrNull(): Long? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    private fun JsonElement.primDoubleOrNull(): Double? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.doubleOrNull ?: it.floatOrNull?.toDouble() }

    private fun JsonElement.primBooleanOrNull(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    private fun parseBeatElementId(elementId: String): Pair<String, Int?> {
        val idx = elementId.lastIndexOf(":b")
        if (idx <= 0) return elementId to null
        val beat = elementId.substring(idx + 2).toIntOrNull() ?: return elementId to null
        return elementId.substring(0, idx) to beat
    }

    private fun findSlideAndNode(document: DeckDocument, nodeId: String): Pair<String, NodeSpec>? {
        for (slide in document.slides) {
            val found = findNode(slide.nodes, nodeId)
            if (found != null) return slide.id to found
        }
        return null
    }

    private fun findNode(nodes: List<NodeSpec>, nodeId: String): NodeSpec? {
        for (n in nodes) {
            if (n.id == nodeId) return n
            findNode(n.children, nodeId)?.let { return it }
        }
        return null
    }

    private fun NodeSpec.withUpdatedChildren(newChildren: List<NodeSpec>): NodeSpec = when {
        stack != null -> copy(stack = stack.copy(children = newChildren))
        row != null -> copy(row = row.copy(children = newChildren))
        column != null -> copy(column = column.copy(children = newChildren))
        loupe != null -> copy(loupe = loupe.copy(children = newChildren))
        else -> this
    }

    private fun updateNodeTree(
        nodes: List<NodeSpec>,
        targetId: String,
        transform: (NodeSpec) -> NodeSpec,
    ): List<NodeSpec> = nodes.map { node ->
        val updatedChildren = if (node.children.isEmpty()) node.children else updateNodeTree(node.children, targetId, transform)
        val withChildren = if (updatedChildren === node.children) node else node.withUpdatedChildren(updatedChildren)
        if (withChildren.id == targetId) transform(withChildren) else withChildren
    }

    private fun mapAllNodes(nodes: List<NodeSpec>, transform: (NodeSpec) -> NodeSpec): List<NodeSpec> =
        nodes.map { node ->
            val updatedChildren = if (node.children.isEmpty()) node.children else mapAllNodes(node.children, transform)
            transform(if (updatedChildren === node.children) node else node.withUpdatedChildren(updatedChildren))
        }

    private fun argbToHex(argb: Long): String {
        val a = ((argb ushr 24) and 0xFF).toInt()
        val r = ((argb ushr 16) and 0xFF).toInt()
        val g = ((argb ushr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return if (a == 0xFF) {
            "#%02X%02X%02X".format(r, g, b)
        } else {
            "#%02X%02X%02X%02X".format(r, g, b, a)
        }
    }
}
