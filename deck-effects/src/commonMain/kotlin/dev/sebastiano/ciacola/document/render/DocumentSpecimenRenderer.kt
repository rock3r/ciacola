package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.LocalExportEmittedFrameIndex
import dev.bnorm.storyboard.easel.LocalExportFps
import dev.sebastiano.ciacola.document.DocumentSpecimenScope
import dev.sebastiano.ciacola.document.RenderPurpose
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.TrackHandle
import dev.sebastiano.ciacola.theme.DeckTheme
import kotlinx.serialization.json.JsonElement

/**
 * Identifies a specimen instance on a specific slide for contiguous `magicMove`-chain
 * `movableContentOf` ownership across transitions.
 */
@Immutable
internal data class MovableSpecimenKey(
    val slideId: String,
    val nodeId: String,
    val ref: String,
)

/**
 * Mount-local pool of `movableContentOf` slots remembered inside [DocumentStageHostDecorator]
 * (inside `Easel`'s `key(scrubEpoch)`).
 *
 * Keeping the pool mount-local (rather than on [RenderSession]) ensures:
 * 1. Live `magicMove` travel across `SceneA <-> Overlay <-> SceneB` shares ONE live slot (`mounts == 1`,
 *    `remember` preserved).
 * 2. Cold seeks (`rebuildScene = true` -> `scrubEpoch++`) recreate the pool from scratch so `remember`
 *    genuinely resets while [RenderSession.retained] survives.
 * 3. Concurrent animated mounts sharing a [RenderSession] never dual-invoke the same movable slot.
 */
@Stable
internal class MovableSpecimenSlotPool {
    private val slots = mutableMapOf<String, @Composable (@Composable () -> Unit) -> Unit>()
    private val lastKnownSizes = mutableMapOf<Pair<String, String>, IntSize>()

    fun slot(slotKey: String): @Composable (@Composable () -> Unit) -> Unit =
        slots.getOrPut(slotKey) {
            movableContentOf { content: @Composable () -> Unit ->
                content()
            }
        }

    fun recordSize(slideId: String, nodeId: String, widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) {
            lastKnownSizes[slideId to nodeId] = IntSize(widthPx, heightPx)
        }
    }

    fun lastKnownSize(slideId: String, nodeId: String): IntSize? =
        lastKnownSizes[slideId to nodeId]
}

internal val LocalMovableSpecimenSlotPool =
    compositionLocalOf<MovableSpecimenSlotPool?> { null }

/**
 * The deck's font resolver handle while specimen content composes, or `null` when the document
 * declares no fonts. Desktop text helpers such as `VariableAxisText` read it to reach the deck's
 * bundled faces.
 */
val LocalSpecimenFontResolverHandle =
    compositionLocalOf<Any?> { null }

@Composable
internal fun DocumentSpecimenPayloadView(
    nodeId: String,
    interactive: String?,
    payload: SpecimenPayload,
    fromParams: Map<String, JsonElement>,
    toParams: Map<String, JsonElement>?,
    paramProgress: Float,
    trackProgress: (TrackHandle) -> Float,
    ctx: DocumentNodeRenderContext,
    modifier: Modifier,
    isTravelling: Boolean = false,
) {
    val entry = ctx.config.specimens.find(payload.ref)
    if (entry == null) {
        MissingSpecimenPlaceholder(
            ref = payload.ref,
            theme = ctx.theme,
            modifier = modifier,
        )
        return
    }

    val chainSlotKey = ctx.movableSpecimenSlotKeys[MovableSpecimenKey(ctx.slide.id, nodeId, payload.ref)]
    val retainedNamespace = if (chainSlotKey != null) {
        "specimen:$chainSlotKey"
    } else {
        "specimen:${ctx.slide.id}/$nodeId/${payload.ref}"
    }

    val emitted = if (ctx.session.purpose == RenderPurpose.Video) LocalExportEmittedFrameIndex.current else null
    val fps = if (ctx.session.purpose == RenderPurpose.Video) LocalExportFps.current else null
    val specimenScope = DocumentSpecimenScope(
        slideId = ctx.slide.id,
        nodeId = nodeId,
        specimenRef = payload.ref,
        session = ctx.session,
        clock = ctx.clock,
        beatsCount = ctx.slide.beats.size.coerceAtLeast(1),
        fromParams = fromParams,
        toParams = toParams,
        paramProgress = paramProgress,
        trackProgressProvider = trackProgress,
        emittedFrameIndex = emitted,
        exportFps = fps,
        theme = ctx.theme,
        palette = ctx.slidePalette,
        effectiveBackground = ctx.effectiveSolidBg,
        fontScale = ctx.config.fontScale,
        reducedMotion = ctx.reducedMotion,
        resolveColorToken = { token -> ctx.colorResolver.resolveOrNull(token) },
        retainedNamespace = retainedNamespace,
    )

    val slotPool = LocalMovableSpecimenSlotPool.current
    if (ctx.isGhostSubtree) {
        // Ghost specimen in SceneA/SceneB during Cross(A, B): never invoke movableContentOf slot
        // or specimen content (Overlay owns the single live instance), preserving layout size.
        val lastKnown = slotPool?.lastKnownSize(ctx.slide.id, nodeId)
        val ghostMod = if (lastKnown != null) {
            val density = LocalDensity.current
            modifier.defaultMinSize(
                minWidth = lastKnown.width.toDouble().toStageDp(density),
                minHeight = lastKnown.height.toDouble().toStageDp(density),
            )
        } else {
            modifier
        }
        Box(modifier = ghostMod)
        return
    }

    val keyboardController = ctx.config.keyboardController ?: LocalDocumentKeyboardController.current
    val focusRequester = remember(nodeId) { FocusRequester() }
    val interactiveMod = modifier.documentInteractiveKeyboardNode(
        slideId = ctx.slide.id,
        nodeId = nodeId,
        interactive = interactive,
        controller = keyboardController,
        focusRequester = focusRequester,
        onSelectNode = ctx.config.onNodeClick,
        onDoubleClickNode = ctx.config.onNodeDoubleClick,
    )

    val recordSizeMod = if (slotPool != null && !isTravelling) {
        interactiveMod.onSizeChanged { sz ->
            slotPool.recordSize(ctx.slide.id, nodeId, sz.width, sz.height)
        }
    } else {
        interactiveMod
    }
    val useMovable = ctx.isAnimatedMount && !ctx.reducedMotion && chainSlotKey != null && slotPool != null
    Box(modifier = recordSizeMod) {
        CompositionLocalProvider(LocalSpecimenFontResolverHandle provides ctx.fontResolverHandle) {
            if (useMovable) {
                val slot = slotPool.slot(chainSlotKey)
                slot {
                    entry.content(specimenScope)
                }
            } else {
                entry.content(specimenScope)
            }
        }
    }
}

@Composable
private fun MissingSpecimenPlaceholder(
    ref: String,
    theme: DeckTheme,
    modifier: Modifier,
) {
    val label = if (ref.startsWith("todo.video")) {
        "Video placeholder ($ref)"
    } else {
        "Missing specimen: $ref"
    }
    Box(
        modifier = modifier
            .background(theme.colors.surface, RoundedCornerShape(10.dp))
            .border(1.5.dp, theme.colors.warning, RoundedCornerShape(10.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = theme.typography.caption,
            color = theme.colors.warning,
        )
    }
}
