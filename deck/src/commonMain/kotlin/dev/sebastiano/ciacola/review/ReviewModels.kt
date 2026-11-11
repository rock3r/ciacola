package dev.sebastiano.ciacola.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReviewCommentStatus {
    @SerialName("queued") Queued,
    @SerialName("applying") Applying,
    @SerialName("applied") Applied,
    @SerialName("failed") Failed,
}

@Serializable
data class StageRectDto(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun toRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun fromRect(rect: Rect): StageRectDto = StageRectDto(
            left = min(rect.left, rect.right),
            top = min(rect.top, rect.bottom),
            right = max(rect.left, rect.right),
            bottom = max(rect.top, rect.bottom),
        )
    }
}

@Serializable
data class ReviewTargetHit(
    val kind: String,
    val label: String,
    val textSnippet: String = "",
    val clickedToken: String = "",
    val bounds: StageRectDto? = null,
)

@Serializable
data class SlideReviewComment(
    val id: String,
    val sceneIndex: Int,
    val frameIndex: Int,
    val sceneCount: Int,
    val frameCount: Int,
    val sceneHeader: String = "",
    val themePreset: String = "StageDark",
    val stageX: Float,
    val stageY: Float,
    val selectionBounds: StageRectDto? = null,
    val target: ReviewTargetHit,
    val note: String,
    val status: ReviewCommentStatus = ReviewCommentStatus.Applying,
    val resolution: String = "",
    val screenshotPath: String? = null,
    val deliveryError: String? = null,
    val createdAt: String,
)

@Serializable
data class ReviewConfig(
    val conversationId: String = "",
    val agentApiBin: String = "",
    val gradleTaskHint: String = ":deck:reload",
)

data class RegisteredTargetSnapshot(
    val id: Any,
    val kind: String,
    val label: String,
    val textSnippet: String,
    val isContainer: Boolean,
    val stageBounds: Rect,
)

private const val MIN_REGION_DRAG_PX = 16f
private const val MAX_SNIPPET_CHARS = 1_200

fun normalizeDragRect(start: Offset, end: Offset): Rect = Rect(
    left = min(start.x, end.x),
    top = min(start.y, end.y),
    right = max(start.x, end.x),
    bottom = max(start.y, end.y),
)

fun isSignificantDrag(rect: Rect): Boolean =
    rect.width >= MIN_REGION_DRAG_PX || rect.height >= MIN_REGION_DRAG_PX

fun resolveBestReviewHit(
    stagePoint: Offset,
    dragRect: Rect? = null,
    registeredTargets: List<RegisteredTargetSnapshot>,
    semanticsHits: List<ReviewTargetHit> = emptyList(),
): ReviewTargetHit {
    if (dragRect != null && isSignificantDrag(dragRect)) {
        val norm = Rect(
            left = min(dragRect.left, dragRect.right),
            top = min(dragRect.top, dragRect.bottom),
            right = max(dragRect.left, dragRect.right),
            bottom = max(dragRect.top, dragRect.bottom),
        )
        val matchingRegistered = registeredTargets
            .filter { it.kind != "Frame" && it.stageBounds.overlaps(norm) }
            .sortedBy { it.stageBounds.top * 10_000f + it.stageBounds.left }
        val matchingSemantics = semanticsHits.filter { hit ->
            val rect = hit.bounds?.toRect() ?: return@filter false
            rect.overlaps(norm)
        }

        val combinedSnippets = buildList {
            matchingRegistered.forEach { t ->
                val text = t.textSnippet.ifBlank { t.label }.trim()
                if (text.isNotEmpty()) add(text)
            }
            matchingSemantics.forEach { s ->
                val text = s.textSnippet.ifBlank { s.label }.trim()
                if (text.isNotEmpty() && none { existing -> existing.contains(text) }) {
                    add(text)
                }
            }
        }.joinToString(" · ").take(MAX_SNIPPET_CHARS)

        val primaryLabels = matchingRegistered.map { "${it.kind}: ${it.label}" }.distinct().take(3)
        val regionLabel = if (primaryLabels.isNotEmpty()) {
            "Region (${norm.width.toInt()}×${norm.height.toInt()}) · ${primaryLabels.joinToString(", ")}"
        } else {
            "Selected region (${norm.width.toInt()}×${norm.height.toInt()})"
        }
        return ReviewTargetHit(
            kind = "Region",
            label = regionLabel,
            textSnippet = combinedSnippets,
            bounds = StageRectDto.fromRect(norm),
        )
    }

    val containingRegistered = registeredTargets.filter {
        it.stageBounds.width > 0f && it.stageBounds.height > 0f && it.stageBounds.contains(stagePoint)
    }
    val bestLeafRegistered = containingRegistered
        .filter { !it.isContainer && it.kind != "Frame" }
        .minByOrNull { it.stageBounds.width * it.stageBounds.height }
    val bestContainerRegistered = containingRegistered
        .filter { it.isContainer && it.kind != "Frame" }
        .minByOrNull { it.stageBounds.width * it.stageBounds.height }
    val chosenRegistered = bestLeafRegistered ?: bestContainerRegistered

    val bestSemantics = semanticsHits
        .filter { hit ->
            val rect = hit.bounds?.toRect() ?: return@filter false
            rect.width > 0f && rect.height > 0f && rect.contains(stagePoint) &&
                (hit.textSnippet.isNotBlank() || hit.label.isNotBlank())
        }
        .minByOrNull { hit ->
            val b = hit.bounds!!
            b.width * b.height
        }

    if (chosenRegistered != null && bestSemantics != null) {
        val fullSnippet = chosenRegistered.textSnippet.ifBlank { bestSemantics.textSnippet }
            .take(MAX_SNIPPET_CHARS)
        val token = bestSemantics.textSnippet.trim().takeIf {
            it.isNotEmpty() && it != fullSnippet
        }.orEmpty()
        return ReviewTargetHit(
            kind = chosenRegistered.kind,
            label = chosenRegistered.label,
            textSnippet = fullSnippet,
            clickedToken = token,
            bounds = StageRectDto.fromRect(chosenRegistered.stageBounds),
        )
    }

    if (chosenRegistered != null) {
        return ReviewTargetHit(
            kind = chosenRegistered.kind,
            label = chosenRegistered.label,
            textSnippet = chosenRegistered.textSnippet.take(MAX_SNIPPET_CHARS),
            bounds = StageRectDto.fromRect(chosenRegistered.stageBounds),
        )
    }

    if (bestSemantics != null) {
        return bestSemantics.copy(
            textSnippet = bestSemantics.textSnippet.take(MAX_SNIPPET_CHARS),
        )
    }

    return ReviewTargetHit(
        kind = "Stage",
        label = "Canvas (${stagePoint.x.toInt()}, ${stagePoint.y.toInt()})",
        bounds = StageRectDto(
            left = (stagePoint.x - 24f).coerceAtLeast(0f),
            top = (stagePoint.y - 24f).coerceAtLeast(0f),
            right = stagePoint.x + 24f,
            bottom = stagePoint.y + 24f,
        ),
    )
}
