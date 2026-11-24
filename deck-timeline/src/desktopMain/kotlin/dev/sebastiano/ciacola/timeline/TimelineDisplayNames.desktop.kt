package dev.sebastiano.ciacola.timeline

import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.CurveEvaluator
import dev.sebastiano.ciacola.document.DeckDisplayNames
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TrackKind
import dev.sebastiano.ciacola.document.TransitionType
import java.util.Locale

/**
 * Plain, author-facing display names for timeline slides, beats, nodes, tracks and curves.
 *
 * Raw document IDs never appear in labels; they are reserved for tooltips. Delegates shared
 * slide/beat/curve formatting to [DeckDisplayNames] in `deck-core`.
 */
internal object TimelineDisplayNames {
    private const val EXCERPT_MAX_CHARS = 26
    private val WhitespaceRunRegex = Regex("\\s+")
    private val CamelBoundaryRegex = Regex("([a-z0-9])([A-Z])")
    private val DelimiterRunRegex = Regex("[-_.]+")
    private val MarkdownPunctuationRegex = Regex("\\*+|`+|~~|__")
    private val MarkdownHeadingPrefixRegex = Regex("(?m)^\\s*#+\\s*")

    /** `Base` for beat 0, `Build N` afterwards. */
    fun beatLabel(beatIndex: Int): String = DeckDisplayNames.beatDisplayName(beatIndex)

    /** Slide title, falling back to `Slide N` (1-based) when the slide has no title. */
    fun slideTitle(slide: SlideSpec?, sceneIndex: Int): String =
        if (slide != null) DeckDisplayNames.slideDisplayTitle(slide, sceneIndex + 1) else "Slide ${sceneIndex + 1}"

    /** `Slide N · Title` (or just `Slide N` when the slide is untitled). */
    fun slideSegmentLabel(slide: SlideSpec?, sceneIndex: Int): String {
        val title = slide?.title?.trim()?.takeIf { it.isNotEmpty() }
        return if (title == null) "Slide ${sceneIndex + 1}" else "${sceneIndex + 1} · $title"
    }

    /** Drops raw markdown punctuation (`**`, `__`, backticks, leading `#`) so excerpts read as prose. */
    fun stripMarkdownPunctuation(text: String): String {
        var hasMarkdownChar = false
        for (i in 0 until text.length) {
            when (text[i]) {
                '*', '`', '~', '_', '#' -> {
                    hasMarkdownChar = true
                    break
                }
            }
        }
        if (!hasMarkdownChar) return text.trim()
        return text.replace(MarkdownPunctuationRegex, "").replace(MarkdownHeadingPrefixRegex, "").trim()
    }

    /** Trims and collapses whitespace, ellipsizing anything longer than [maxChars]. */
    fun excerpt(text: String, maxChars: Int = EXCERPT_MAX_CHARS): String {
        val collapsed = text.replace(WhitespaceRunRegex, " ").trim()
        if (collapsed.length <= maxChars) return collapsed
        val cut = collapsed.take(maxChars - 1)
        val wordBreak = cut.lastIndexOf(' ')
        val head = if (wordBreak > maxChars * 0.6) cut.substring(0, wordBreak) else cut
        return head.trimEnd() + "…"
    }

    /**
     * Readable node name: text excerpt for text/code nodes, specimen label for specimens, and a
     * humanized kind or id for everything else (`Rectangle`, `Image`, `Arrow`).
     */
    fun nodeName(node: NodeSpec?, nodeId: String, specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty): String {
        if (node == null) return humanizeId(nodeId)
        node.text?.value?.plainText?.let(::stripMarkdownPunctuation)?.takeIf { it.isNotBlank() }?.let { return excerpt(it) }
        if (node.code != null) return "Code · ${humanizeId(nodeId)}"
        node.specimen?.let { sp ->
            val decl = specimens.find(sp.ref)
            return decl?.label?.takeIf { it.isNotBlank() } ?: humanizeId(sp.ref.substringAfterLast('.'))
        }
        return when (node.kind) {
            NodeKind.Shape -> "Shape · ${humanizeId(nodeId)}"
            NodeKind.Image -> "Image · ${humanizeId(nodeId)}"
            NodeKind.Connector -> "Connector · ${humanizeId(nodeId)}"
            NodeKind.Stack, NodeKind.Row, NodeKind.Column, NodeKind.Loupe ->
                "${DeckDisplayNames.nodeTypeLabel(node)} · ${humanizeId(nodeId)}"
            else -> humanizeId(nodeId)
        }
    }

    /** `s1:hero-title` -> `Hero title`; `reticulate-splin-bg-3` -> `Reticulate splin bg 3`. */
    fun humanizeId(id: String): String {
        val local = id.substringAfterLast(':').substringAfterLast('/')
        val spaced = local
            .replace(CamelBoundaryRegex, "$1 $2")
            .replace(DelimiterRunRegex, " ")
            .trim()
            .lowercase(Locale.US)
        if (spaced.isEmpty()) return id
        return spaced.replaceFirstChar { it.titlecase(Locale.US) }
    }

    /** Short build effect name (`Enter · Fade move`). */
    fun buildKindLabel(build: Build?, kind: TrackKind): String = when (build) {
        is Build.Enter -> "Enter · ${humanizeId(build.effect)}"
        is Build.Exit -> "Exit · ${humanizeId(build.effect)}"
        is Build.Emphasis -> "Emphasis · ${humanizeId(build.effect)}"
        is Build.Change -> "Change"
        null -> humanizeId(kind.name)
    }

    /** `Build 1 · A scene begins…` — beat number first so two builds of one node never collide. */
    fun buildTrackLabel(beatIndex: Int, node: NodeSpec?, nodeId: String, specimens: DeckSpecimenRegistry): String =
        "${beatLabel(beatIndex)} · ${nodeName(node, nodeId, specimens)}"

    /** `Build 2 · Kaleidoscope · Intensity` for specimen parameter tracks. */
    fun specimenTrackLabel(
        beatIndex: Int,
        node: NodeSpec?,
        nodeId: String,
        trackName: String,
        specimens: DeckSpecimenRegistry,
    ): String = "${beatLabel(beatIndex)} · ${nodeName(node, nodeId, specimens)} · ${humanizeId(trackName)}"

    /**
     * Cross-slide transition track: `→ Shared move · Title` for nodes, or `→ Slide 2 · Dissolve`
     * for the synthetic whole-slide transition track (never the raw `@transition:…` id).
     */
    fun transitionTrackLabel(
        kind: TrackKind,
        toSlide: SlideSpec?,
        toSceneIndex: Int,
        node: NodeSpec?,
        nodeId: String,
        transitionType: TransitionType?,
        specimens: DeckSpecimenRegistry,
    ): String {
        val target = slideTitle(toSlide, toSceneIndex)
        if (nodeId.startsWith("@transition") || kind == TrackKind.TransitionSlide) {
            val typeName = transitionType?.let { transitionTypeName(it) } ?: "Transition"
            return "→ $target · $typeName"
        }
        val phase = when (kind) {
            TrackKind.TransitionMove -> "Move"
            TrackKind.TransitionExit -> "Exit"
            TrackKind.TransitionEnter -> "Enter"
            else -> humanizeId(kind.name)
        }
        return "→ $target · $phase · ${nodeName(node, nodeId, specimens)}"
    }

    fun transitionTypeName(type: TransitionType): String = when (type) {
        TransitionType.MagicMove -> "Magic Move"
        else -> humanizeId(type.name)
    }

    // ── Curves ──────────────────────────────────────────────────────────────────────────────────

    private val PRESET_DISPLAY_NAMES: Map<String, String> = mapOf(
        "linear" to "Linear",
        "ease" to "Ease",
        "easeIn" to "Ease in",
        "easeOut" to "Ease out",
        "easeInOut" to "Ease in-out",
        "fastOutSlowIn" to "Standard",
        "easeInCubic" to "Ease in (cubic)",
        "easeOutCubic" to "Ease out (cubic)",
        "easeInOutCubic" to "Ease in-out (cubic)",
    )

    /**
     * Human preset name for [curve]: `Standard`, `Ease in-out`, `Ease out`, `Ease in`, `Linear`,
     * `Spring`, Keynote presets as `Keynote · Pop`, and `Custom` for anything else. Never the
     * serialized control-point tuple.
     */
    fun curveName(curve: Curve?): String = when (curve) {
        null -> "Default"
        is Curve.Preset -> PRESET_DISPLAY_NAMES[curve.name]
            ?: if (curve.name.startsWith("keynote.")) {
                "Keynote · ${humanizeId(curve.name.removePrefix("keynote."))}"
            } else if (CurveEvaluator.isKnownPreset(curve.name)) {
                humanizeId(curve.name)
            } else {
                "Custom"
            }
        is Curve.Bezier, is Curve.Spring -> DeckDisplayNames.curveDisplayName(curve)
    }

    /** Precise control-point description, reserved for tooltips. */
    fun curveDetail(curve: Curve?): String = when (curve) {
        null -> "Theme default curve"
        is Curve.Preset -> CurveEvaluator.PRESET_BEZIERS[curve.name]?.let { b ->
            "${curveName(curve)} · cubic-bezier(%.2f, %.2f, %.2f, %.2f)".format(Locale.US, b.x1, b.y1, b.x2, b.y2)
        } ?: curveName(curve)
        is Curve.Bezier -> "cubic-bezier(%.2f, %.2f, %.2f, %.2f)".format(Locale.US, curve.x1, curve.y1, curve.x2, curve.y2)
        is Curve.Spring -> "Spring · damping %.2f · stiffness %.0f".format(Locale.US, curve.damping, curve.stiffness)
    }
}

/** Curve choices offered in Animate dropdowns, labelled with human preset names. */
internal val TIMELINE_CURVE_CHOICES: List<Pair<String, Curve>> = listOf(
    "Standard" to Curve.Preset("fastOutSlowIn"),
    "Ease in-out" to Curve.EaseInOut,
    "Ease out" to Curve.EaseOut,
    "Ease in" to Curve.EaseIn,
    "Linear" to Curve.Linear,
    "Spring" to Curve.Spring(damping = 0.7, stiffness = 380.0),
    "Keynote · Magic move" to Curve.Preset("keynote.magicMove"),
    "Keynote · Pop" to Curve.Preset("keynote.pop"),
)

/** One unit for a whole ruler, picked from the tick step and the largest visible tick. */
enum class TimelineRulerUnit { Millis, Seconds, MinSec }

/**
 * Chooses ONE ruler unit from the major tick [stepMs] and the largest visible tick [maxVisibleMs]
 * so a ruler never mixes `1500ms` with `2.0s`:
 * - any visible tick at or past 60 s → `m:ss`;
 * - sub-second steps on a ruler that stays under 2 s → milliseconds;
 * - otherwise seconds.
 */
fun timelineRulerUnitFor(stepMs: Int, maxVisibleMs: Int): TimelineRulerUnit {
    val wholeSecondSteps = stepMs >= 1_000 && stepMs % 1_000 == 0
    return when {
        maxVisibleMs >= 60_000 -> TimelineRulerUnit.MinSec
        !wholeSecondSteps && maxVisibleMs < 2_000 -> TimelineRulerUnit.Millis
        else -> TimelineRulerUnit.Seconds
    }
}

/**
 * Single ruler formatter shared by the master timeline and the loop sub-composition. Pass the
 * ruler's largest visible tick as [maxVisibleMs] so every label on it uses the same unit
 * ([timelineRulerUnitFor]); seconds drop `.0` when every tick is on a whole second (`10s`), and
 * `m:ss` gains a decimal when ticks are sub-second (`1:00.5`).
 */
fun formatTimelineRulerLabel(ms: Int, stepMs: Int, maxVisibleMs: Int = ms): String {
    val safe = ms.coerceAtLeast(0)
    val wholeSecondSteps = stepMs >= 1_000 && stepMs % 1_000 == 0
    return when (timelineRulerUnitFor(stepMs, maxVisibleMs.coerceAtLeast(safe))) {
        TimelineRulerUnit.Millis -> "${safe}ms"
        TimelineRulerUnit.Seconds -> when {
            wholeSecondSteps -> "${safe / 1_000}s"
            stepMs < 100 -> String.format(Locale.US, "%.2fs", safe / 1_000.0)
            else -> String.format(Locale.US, "%.1fs", safe / 1_000.0)
        }
        TimelineRulerUnit.MinSec -> {
            val totalSec = safe / 1_000
            val mins = totalSec / 60
            val secs = totalSec % 60
            if (wholeSecondSteps) {
                String.format(Locale.US, "%d:%02d", mins, secs)
            } else {
                val tenths = (safe % 1_000) / 100
                String.format(Locale.US, "%d:%02d.%d", mins, secs, tenths)
            }
        }
    }
}
