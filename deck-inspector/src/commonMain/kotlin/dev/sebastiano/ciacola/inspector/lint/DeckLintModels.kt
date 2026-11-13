package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.inspector.ElementPatch

const val STAGE_WIDTH_PX = 1920f
const val STAGE_HEIGHT_PX = 1080f
const val SAFE_AREA_MARGIN_PX = 28f
const val TINY_TEXT_MIN_SP = 14f
const val SPOTLIGHT_SKIP_ALPHA = 0.45f
const val INVISIBLE_ALPHA_THRESHOLD = 0.01f
const val OVERLAP_THRESHOLD_FRACTION = 0.25f
const val NARROW_WRAP_MIN_LINES = 3
const val NARROW_WRAP_WIDTH_MULTIPLIER = 2.5f
const val WCAG_NORMAL_TEXT_RATIO = 4.5f
const val WCAG_LARGE_TEXT_RATIO = 3.0f
const val WCAG_LARGE_TEXT_SP = 24f
const val WCAG_LARGE_BOLD_TEXT_SP = 18.66f

/**
 * High-signal slide and deck diagnostic rule identifiers.
 */
enum class LintRuleId(
    val shortLabel: String,
    val defaultSeverity: LintSeverity,
) {
    TEXT_CLIPPED_OR_OVERFLOW(shortLabel = "Overflow", defaultSeverity = LintSeverity.Error),
    STAGE_OUT_OF_BOUNDS(shortLabel = "Out of Bounds", defaultSeverity = LintSeverity.Error),
    SAFE_AREA_BLEED(shortLabel = "Safe Area", defaultSeverity = LintSeverity.Warning),
    LOW_CONTRAST(shortLabel = "Contrast", defaultSeverity = LintSeverity.Error),
    TINY_STAGE_TEXT(shortLabel = "Tiny Text", defaultSeverity = LintSeverity.Warning),
    TEXT_OVERLAP(shortLabel = "Overlap", defaultSeverity = LintSeverity.Warning),
}

enum class LintSeverity {
    Error,
    Warning,
}

enum class LintScope {
    CurrentBeat,
    AllSlides,
}

enum class LintSeverityFilter {
    All,
    ErrorsOnly,
    WarningsOnly,
}

/**
 * Actionable remediation attached to a [DeckLintIssue].
 */
@Immutable
data class LintQuickFix(
    val label: String,
    val tooltip: String,
    val patch: ElementPatch? = null,
    val opensElementEditor: Boolean = false,
)

/**
 * A single diagnostic issue detected on a slide beat in 1920x1080 stage coordinates.
 */
@Immutable
data class DeckLintIssue(
    val id: String,
    val ruleId: LintRuleId,
    val severity: LintSeverity,
    val sceneIndex: Int,
    val beatIndex: Int,
    val sceneTitle: String = "",
    val elementId: String? = null,
    val elementLabel: String,
    val textSnippet: String = "",
    val stageBounds: Rect,
    val title: String,
    val detail: String,
    val quickFixes: List<LintQuickFix> = emptyList(),
) {
    val locationBadge: String
        get() = "S${sceneIndex + 1}:B${beatIndex + 1}"
}

/**
 * Normalized text or interactive element candidate harvested from [StageNodeSnapshot] and/or
 * Compose [SemanticsNode]s in 1920x1080 stage coordinates.
 */
@Immutable
data class LintTextNodeCandidate(
    val elementId: String? = null,
    val kind: String = "Text",
    val label: String,
    val text: String,
    val stageBounds: Rect,
    val unclippedStageBounds: Rect = stageBounds,
    val hasVisualOverflow: Boolean = false,
    val overflowPx: Float = 0f,
    val hasEllipsis: Boolean = false,
    val lineCount: Int = 1,
    val maxLineWidthPx: Float = stageBounds.width,
    val fontSizePx: Float = 0f,
    val fontSizeSp: Float = 0f,
    val isBold: Boolean = false,
    val effectiveAlpha: Float = 1f,
    val effectiveForeground: Color? = null,
    val opaqueForeground: Color? = null,
    val effectiveBackground: Color? = null,
    val isEditable: Boolean = false,
    val isFullBleedSurface: Boolean = false,
    val isTextNode: Boolean = true,
    val semanticsNodeId: Int? = null,
    val ancestorSemanticsIds: Set<Int> = emptySet(),
)

/**
 * Optional enclosing card or surface snapshot used for background inference and bounds checks.
 */
@Immutable
data class LintSurfaceCandidate(
    val elementId: String? = null,
    val kind: String = "Surface",
    val label: String,
    val stageBounds: Rect,
    val effectiveBackground: Color? = null,
    val effectiveAlpha: Float = 1f,
    val isInteractive: Boolean = false,
    val isEditable: Boolean = false,
    val isFullBleedSurface: Boolean = false,
)

/**
 * Complete per-beat input evaluated by [DeckLintRules].
 */
@Immutable
data class LintSlideInput(
    val sceneIndex: Int,
    val beatIndex: Int,
    val sceneTitle: String = "",
    val stageWidth: Float = STAGE_WIDTH_PX,
    val stageHeight: Float = STAGE_HEIGHT_PX,
    val textNodes: List<LintTextNodeCandidate> = emptyList(),
    val surfaces: List<LintSurfaceCandidate> = emptyList(),
)
