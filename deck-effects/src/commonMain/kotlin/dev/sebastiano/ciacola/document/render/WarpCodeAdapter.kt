package dev.sebastiano.ciacola.document.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.Agsl as WarpAgslLanguage
import dev.bnorm.storyboard.BasicCode
import dev.bnorm.storyboard.C as WarpCLanguage
import dev.bnorm.storyboard.CSharp as WarpCSharpLanguage
import dev.bnorm.storyboard.Cpp as WarpCppLanguage
import dev.bnorm.storyboard.Css as WarpCssLanguage
import dev.bnorm.storyboard.Dart as WarpDartLanguage
import dev.bnorm.storyboard.Diff as WarpDiffLanguage
import dev.bnorm.storyboard.DiffContents
import dev.bnorm.storyboard.Dockerfile as WarpDockerfileLanguage
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Glsl as WarpGlslLanguage
import dev.bnorm.storyboard.Go as WarpGoLanguage
import dev.bnorm.storyboard.Groovy as WarpGroovyLanguage
import dev.bnorm.storyboard.Hcl as WarpHclLanguage
import dev.bnorm.storyboard.Html as WarpHtmlLanguage
import dev.bnorm.storyboard.Java as WarpJavaLanguage
import dev.bnorm.storyboard.JavaScript as WarpJavaScriptLanguage
import dev.bnorm.storyboard.Json as WarpJsonLanguage
import dev.bnorm.storyboard.Kotlin as WarpKotlinLanguage
import dev.bnorm.storyboard.Language as WarpLanguage
import dev.bnorm.storyboard.Markdown as WarpMarkdownLanguage
import dev.bnorm.storyboard.Php as WarpPhpLanguage
import dev.bnorm.storyboard.PowerShell as WarpPowerShellLanguage
import dev.bnorm.storyboard.Protobuf as WarpProtobufLanguage
import dev.bnorm.storyboard.Python as WarpPythonLanguage
import dev.bnorm.storyboard.Ruby as WarpRubyLanguage
import dev.bnorm.storyboard.Rust as WarpRustLanguage
import dev.bnorm.storyboard.Shell as WarpShellLanguage
import dev.bnorm.storyboard.Sksl as WarpSkslLanguage
import dev.bnorm.storyboard.Sql as WarpSqlLanguage
import dev.bnorm.storyboard.State as WarpTokenState
import dev.bnorm.storyboard.Swift as WarpSwiftLanguage
import dev.bnorm.storyboard.Token
import dev.bnorm.storyboard.Toml as WarpTomlLanguage
import dev.bnorm.storyboard.TypeScript as WarpTypeScriptLanguage
import dev.bnorm.storyboard.Xml as WarpXmlLanguage
import dev.bnorm.storyboard.Yaml as WarpYamlLanguage
import dev.bnorm.storyboard.buildDiffList
import dev.bnorm.storyboard.content
import dev.bnorm.storyboard.easel.LocalStoryboardTransition
import dev.bnorm.storyboard.easel.stableSegment
import dev.sebastiano.ciacola.core.fonts.FontAxes
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.SegmentPlan
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.document.TrackKind
import dev.sebastiano.ciacola.document.mirroredWarpWindow
import dev.sebastiano.ciacola.document.rootSegment
import dev.sebastiano.ciacola.effects.LocalWarpSegmentObserver
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.theme.DeckCodePalette
import dev.sebastiano.ciacola.theme.DeckTheme
import kotlin.math.roundToInt

/**
 * Diagnostic snapshot of the animation windows mounted by [WarpCodeAdapter] for a transitioning
 * `code` node, observed via [LocalWarpTimingObserver] in tests.
 */
@Immutable
internal data class WarpMountedTimingSnapshot(
    val nodeId: String,
    val fromIdx: Int,
    val toIdx: Int,
    val isBackwardRoot: Boolean,
    val reducedMotion: Boolean,
    val effectiveStartMs: Int,
    val totalSpanMs: Int,
    val leaveDelayMs: Int,
    val leaveDurationMs: Int,
    val moveDelayMs: Int,
    val moveDurationMs: Int,
    val arriveDelayMs: Int,
    val arriveDurationMs: Int,
)

/** Optional test observer invoked inside [SideEffect] with mounted Warp animation windows. */
internal val LocalWarpTimingObserver = compositionLocalOf<((WarpMountedTimingSnapshot) -> Unit)?> { null }

/** Optional test observer invoked during [DocWarpStateHost] measure with the child [Constraints]. */
internal val LocalWarpChildConstraintsObserver = compositionLocalOf<((String, Constraints) -> Unit)?> { null }

/**
 * Document renderer adapter for `code` nodes backed by Storyboard Warp.
 *
 * Guarantees:
 * - Reads zero legacy element/timeline override or harvest state.
 * - Derives 100 % of Warp timing from [SegmentPlan]'s `change` track `warpSchedule` and `startMs`
 *   (plus [mirroredWarpWindow] on backward playback) — never falls back to `theme.motion`.
 * - Ensures `effectiveDurationNanos == plan.segmentMs * 1_000_000L` across every duration
 *   (`10 ms`, `60 ms`, delayed, `highlightLines`-only, `reducedMotion = true`) and both directions
 *   (`forward`, `backward`, `← → ←`).
 * - Reserves a fixed outer `w x h` box when explicit dimensions are declared (`frame.w` / `frame.h`),
 *   keeping sibling layout in `Row`/`Column` 100 % stationary while the inner card surface animates
 *   smoothly at top-left clipped to `w x h` with bounded child constraints.
 */
@Composable
fun WarpCodeAdapter(
    rawNode: NodeSpec,
    resolvedNode: NodeSpec,
    slide: SlideSpec,
    frameTransition: Transition<out Frame<Int>>?,
    segmentPlan: SegmentPlan,
    theme: DeckTheme,
    colorResolver: DocumentColorResolver,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    fontResolverHandle: Any? = null,
) {
    val beatCount = slide.beats.size.coerceAtLeast(1)
    val density = LocalDensity.current

    // Resolve per-beat code payloads & deduplicate adjacent identical snippets so beats where
    // code does not change map to the exact same state index (0 ns contributed to transition).
    val codeTimeline = remember(rawNode, beatCount) {
        buildCodeStateTimeline(rawNode, beatCount)
    }

    val payload = resolvedNode.code ?: return
    val language = remember(payload.language) { resolveWarpLanguage(payload.language) }
    val diffList: List<DiffContents> = remember(codeTimeline.states, language) {
        val blocks = if (codeTimeline.states.size == 1) {
            listOf(BasicCode(codeTimeline.states[0]), BasicCode(codeTimeline.states[0]))
        } else {
            codeTimeline.states.map(::BasicCode)
        }
        buildDiffList(blocks, language)
    }

    // Determine fixed reserved outer box in pixels when explicit frame dimensions exist.
    val fixedWidthPx: Int? = resolvedNode.frame?.w?.roundToInt()?.coerceAtLeast(1)
    val fixedHeightPx: Int? = resolvedNode.frame?.h?.roundToInt()?.coerceAtLeast(1)

    // Derive child Transition<Int> from the scene's frameTransition when animating, or render still.
    val stateTransition: Transition<Int>? = frameTransition?.createChildTransition(
        label = "DocWarpCode:${rawNode.id}",
    ) { frame ->
        val beatIdx = when (frame) {
            Frame.Start -> 0
            Frame.End -> (beatCount - 1).coerceAtLeast(0)
            is Frame.Value -> frame.value.coerceIn(0, beatCount - 1)
        }
        codeTimeline.beatToStateIndex[beatIdx]
    }

    val (rawFromIdx, rawToIdx) = stateTransition?.stableSegment() ?: run {
        val stillHighlights = payload.highlightLines?.toSet().orEmpty()
        val matchedStateIdx = codeTimeline.states.indices.firstOrNull { idx ->
            codeTimeline.states[idx] == payload.value && codeTimeline.highlightsByState[idx] == stillHighlights
        } ?: codeTimeline.states.indexOf(payload.value).takeIf { it >= 0 }
            ?: codeTimeline.beatToStateIndex.firstOrNull()
            ?: 0
        matchedStateIdx to matchedStateIdx
    }
    val fromIdx = rawFromIdx.coerceIn(0, diffList.lastIndex)
    val toIdx = rawToIdx.coerceIn(0, diffList.lastIndex)

    val segmentObserver = LocalWarpSegmentObserver.current
    if (segmentObserver != null) {
        SideEffect {
            segmentObserver.invoke(rawNode.id, fromIdx, toIdx)
        }
    }

    // Resolve the authoritative WarpSchedule & start delay strictly from SegmentPlan (applied ONCE!).
    val rootSegment = LocalStoryboardTransition.current?.rootSegment()
    val isBackwardRoot = rootSegment != null && rootSegment.to < rootSegment.from
    val changeTrack = segmentPlan.tracks.firstOrNull {
        it.nodeId == rawNode.id && it.kind == TrackKind.Change && it.warpSchedule != null
    }
    val ws = changeTrack?.warpSchedule
    val forwardStartMs = changeTrack?.startMs ?: 0
    val effectiveStartMs = if (ws != null && isBackwardRoot && segmentPlan.segmentMs > 0) {
        mirroredWarpWindow(
            segmentMs = segmentPlan.segmentMs,
            trackStartMs = forwardStartMs,
            totalSpanMs = ws.totalSpanMs,
        ).first
    } else {
        forwardStartMs
    }

    val timingObserver = LocalWarpTimingObserver.current
    if (timingObserver != null && stateTransition != null && fromIdx != toIdx && ws != null) {
        val snapshot = if (reducedMotion) {
            WarpMountedTimingSnapshot(
                nodeId = rawNode.id,
                fromIdx = fromIdx,
                toIdx = toIdx,
                isBackwardRoot = isBackwardRoot,
                reducedMotion = true,
                effectiveStartMs = effectiveStartMs,
                totalSpanMs = ws.totalSpanMs,
                leaveDelayMs = effectiveStartMs + ws.moving.startMs,
                leaveDurationMs = ws.moving.durationMs,
                moveDelayMs = effectiveStartMs + ws.moving.startMs,
                moveDurationMs = ws.moving.durationMs,
                arriveDelayMs = effectiveStartMs + ws.moving.startMs,
                arriveDurationMs = ws.moving.durationMs,
            )
        } else {
            WarpMountedTimingSnapshot(
                nodeId = rawNode.id,
                fromIdx = fromIdx,
                toIdx = toIdx,
                isBackwardRoot = isBackwardRoot,
                reducedMotion = false,
                effectiveStartMs = effectiveStartMs,
                totalSpanMs = ws.totalSpanMs,
                leaveDelayMs = effectiveStartMs + ws.leaving.startMs,
                leaveDurationMs = ws.leaving.durationMs,
                moveDelayMs = effectiveStartMs + ws.moving.startMs,
                moveDurationMs = ws.moving.durationMs,
                arriveDelayMs = effectiveStartMs + ws.arriving.startMs,
                arriveDurationMs = ws.arriving.durationMs,
            )
        }
        SideEffect {
            timingObserver.invoke(snapshot)
        }
    }

    val surface: SurfaceSpec = payload.surface ?: defaultCodeSurfaceSpec(theme, density)
    val effectivePadding = surface.padding?.toPaddingValues(density)
        ?: androidx.compose.foundation.layout.PaddingValues(theme.styles.codeCard.padding)
    val codePalette: DeckCodePalette = theme.colors.code
    val baseTextStyle: TextStyle = remember(theme.typography, colorResolver.themeSpec, payload.fit, density, fontResolverHandle) {
        resolveDocumentCodeTextStyle(
            theme = theme,
            colorResolver = colorResolver,
            fit = payload.fit,
            density = density,
            fontResolverHandle = fontResolverHandle,
        )
    }

    val textMeasurer = rememberTextMeasurer()
    val horizontalPadPx = with(density) {
        (surface.padding?.let { (it.left + it.right).roundToInt() }
            ?: (theme.styles.codeCard.padding * 2).roundToPx())
    }
    val innerMaxWidthPx = fixedWidthPx?.let { (it - horizontalPadPx).coerceAtLeast(1) } ?: Int.MAX_VALUE
    val fittedStyle = remember(diffList, baseTextStyle, payload.fit, innerMaxWidthPx, density) {
        computeFittedCodeStyle(
            diffList = diffList,
            baseStyle = baseTextStyle,
            fit = payload.fit,
            innerMaxWidthPx = innerMaxWidthPx,
            textMeasurer = textMeasurer,
        )
    }

    val fromKeys = remember(diffList, fromIdx) { collectStableKeys(diffList[fromIdx]) }
    val toKeys = remember(diffList, toIdx) { collectStableKeys(diffList[toIdx]) }
    val sharedKeys = remember(fromKeys, toKeys) { fromKeys intersect toKeys }
    val fromRowByKey = remember(diffList, fromIdx) { collectKeyRowMap(diffList[fromIdx]) }

    fun lineAlphaFor(stateIdx: Int, rowIdx: Int): Float {
        val hl = codeTimeline.highlightsByState.getOrNull(stateIdx)
        if (hl.isNullOrEmpty()) return 1f
        return if (rowIdx in hl) 1f else 0.35f
    }

    // Outer fixed reserved layout box: reports exact `w x h` when declared so Row/Column
    // siblings never shift during code morphs; inner card surface animates at (0, 0) clipped to box.
    val isGhost = LocalIsGhostSubtree.current
    val tagPrefix = if (isGhost) "doc-ghost-warp" else "doc-warp"
    Layout(
        modifier = modifier
            .testTag("$tagPrefix-box-${rawNode.id}")
            .clipToBounds(),
        content = {
            Box(
                modifier = Modifier
                    .testTag("$tagPrefix-surface-${rawNode.id}")
                    .applyDocumentSurfaceChrome(surface, colorResolver, density)
                    .padding(effectivePadding),
            ) {
                if (stateTransition == null || fromIdx == toIdx || ws == null) {
                    StaticWarpCodeRows(
                        diffContents = diffList[toIdx],
                        style = fittedStyle,
                        palette = codePalette,
                        lineAlpha = { rowIdx -> lineAlphaFor(toIdx, rowIdx) },
                    )
                } else if (reducedMotion) {
                    val fadeSpec = tween<Float>(
                        durationMillis = ws.moving.durationMs,
                        delayMillis = effectiveStartMs + ws.moving.startMs,
                        easing = ws.moving.easing,
                    )
                    stateTransition.DocWarpStateHost(
                        nodeId = rawNode.id,
                        fromIdx = fromIdx,
                        toIdx = toIdx,
                        maxStateIndex = diffList.lastIndex,
                        sizeSpec = fadeSpec,
                        enter = fadeIn(fadeSpec),
                        exit = fadeOut(fadeSpec),
                    ) { clampedIdx, _ ->
                        StaticWarpCodeRows(
                            diffContents = diffList[clampedIdx],
                            style = fittedStyle,
                            palette = codePalette,
                            lineAlpha = { rowIdx -> lineAlphaFor(clampedIdx, rowIdx) },
                        )
                    }
                } else {
                    val leaveSpec = tween<Float>(
                        durationMillis = ws.leaving.durationMs,
                        delayMillis = effectiveStartMs + ws.leaving.startMs,
                        easing = ws.leaving.easing,
                    )
                    val moveFloatSpec = tween<Float>(
                        durationMillis = ws.moving.durationMs,
                        delayMillis = effectiveStartMs + ws.moving.startMs,
                        easing = ws.moving.easing,
                    )
                    val arriveSpec = tween<Float>(
                        durationMillis = ws.arriving.durationMs,
                        delayMillis = effectiveStartMs + ws.arriving.startMs,
                        easing = ws.arriving.easing,
                    )
                    val boundsTransform = BoundsTransform { _, _ ->
                        tween(
                            durationMillis = ws.moving.durationMs,
                            delayMillis = effectiveStartMs + ws.moving.startMs,
                            easing = ws.moving.easing,
                        )
                    }

                    SharedTransitionLayout {
                        stateTransition.DocWarpStateHost(
                            nodeId = rawNode.id,
                            fromIdx = fromIdx,
                            toIdx = toIdx,
                            maxStateIndex = diffList.lastIndex,
                            sizeSpec = moveFloatSpec,
                            enter = fadeIn(arriveSpec),
                            exit = fadeOut(leaveSpec),
                        ) { clampedIdx, morphProgress ->
                            val diffContents = diffList[clampedIdx]
                            Column {
                                diffContents.rows.forEachIndexed { rowIdx, rowStates ->
                                    val rowAlpha = lineAlphaFor(clampedIdx, rowIdx)
                                    if (rowStates.isEmpty()) {
                                        Text(
                                            text = " ",
                                            style = fittedStyle,
                                            softWrap = false,
                                            maxLines = 1,
                                        )
                                    } else {
                                        Row {
                                            rowStates.forEachIndexed { colIdx, tokenState ->
                                                if (tokenState == WarpTokenState.Empty) return@forEachIndexed
                                                val text = tokenState.content()
                                                if (text.isBlank()) {
                                                    Text(
                                                        text = text,
                                                        style = fittedStyle,
                                                        softWrap = false,
                                                        maxLines = 1,
                                                    )
                                                } else {
                                                    val stableKey = tokenState.docWarpStableKey()
                                                    val isRetained = (stableKey != null && stableKey in sharedKeys) ||
                                                        (tokenState is WarpTokenState.Match && stableKey == null)
                                                    val colorToken = tokenState.docResolveColorToken()
                                                    if (isRetained) {
                                                        val sharedKey = stableKey
                                                            ?: (tokenState as? WarpTokenState.Match)
                                                                ?.docSharedKey(rowIdx, colIdx)
                                                            ?: "doc-warp:$rowIdx:$colIdx"
                                                        val fromRowIdx = stableKey?.let { fromRowByKey[it] } ?: rowIdx
                                                        val fromA = lineAlphaFor(fromIdx, fromRowIdx)
                                                        val toA = lineAlphaFor(toIdx, rowIdx)
                                                        with(this@SharedTransitionLayout) {
                                                            Text(
                                                                text = text,
                                                                style = fittedStyle.merge(colorToken.docStyleIn(codePalette)),
                                                                softWrap = false,
                                                                maxLines = 1,
                                                                modifier = Modifier
                                                                    .sharedElement(
                                                                        sharedContentState = rememberSharedContentState(key = sharedKey),
                                                                        animatedVisibilityScope = this@DocWarpStateHost,
                                                                        boundsTransform = boundsTransform,
                                                                    )
                                                                    .graphicsLayer {
                                                                        val p = morphProgress().coerceIn(0f, 1f)
                                                                        alpha = fromA + (toA - fromA) * p
                                                                    },
                                                            )
                                                        }
                                                    } else {
                                                        with(this@DocWarpStateHost) {
                                                            Text(
                                                                text = text,
                                                                style = fittedStyle.merge(colorToken.docStyleIn(codePalette)),
                                                                softWrap = false,
                                                                maxLines = 1,
                                                                modifier = Modifier
                                                                    .animateEnterExit(
                                                                        enter = fadeIn(arriveSpec),
                                                                        exit = fadeOut(leaveSpec),
                                                                    )
                                                                    .graphicsLayer { alpha = rowAlpha },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val fillBoxWidth = payload.fit == "fillWidth" || payload.fit == "compact"
        val resolvedMaxW = fixedWidthPx ?: constraints.maxWidth
        val innerConstraints = Constraints(
            minWidth = if (fillBoxWidth && fixedWidthPx != null) resolvedMaxW else 0,
            maxWidth = resolvedMaxW,
            minHeight = 0,
            maxHeight = fixedHeightPx ?: constraints.maxHeight,
        )
        val placeable = measurables.first().measure(innerConstraints)
        val outW = (fixedWidthPx ?: placeable.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        val outH = (fixedHeightPx ?: placeable.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(outW, outH) {
            placeable.place(0, 0)
        }
    }
}

@Composable
private fun StaticWarpCodeRows(
    diffContents: DiffContents,
    style: TextStyle,
    palette: DeckCodePalette,
    lineAlpha: (rowIdx: Int) -> Float,
) {
    Column {
        diffContents.rows.forEachIndexed { rowIdx, rowStates ->
            val rowAlpha = lineAlpha(rowIdx)
            if (rowStates.isEmpty()) {
                Text(text = " ", style = style, softWrap = false, maxLines = 1)
            } else {
                Row {
                    rowStates.forEach { state ->
                        if (state == WarpTokenState.Empty) return@forEach
                        val text = state.content()
                        if (text.isBlank()) {
                            Text(text = text, style = style, softWrap = false, maxLines = 1)
                        } else {
                            val colorToken = state.docResolveColorToken()
                            Text(
                                text = text,
                                style = style.merge(colorToken.docStyleIn(palette)),
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.graphicsLayer { alpha = rowAlpha },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Transition<Int>.DocWarpStateHost(
    nodeId: String,
    fromIdx: Int,
    toIdx: Int,
    maxStateIndex: Int,
    sizeSpec: FiniteAnimationSpec<Float>,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable AnimatedVisibilityScope.(stateIdx: Int, morphProgress: () -> Float) -> Unit,
) {
    val active = if (fromIdx == toIdx) listOf(toIdx) else listOf(fromIdx, toIdx)
    val sizeProgressHolder = remember { arrayOfNulls<State<Float>>(1) }
    if (active.size < 2) sizeProgressHolder[0] = null
    val morphProgress: () -> Float = { sizeProgressHolder[0]?.value ?: 1f }
    val constraintsObserver = LocalWarpChildConstraintsObserver.current

    Layout(
        content = {
            for (stateIdx in active) {
                key(stateIdx) {
                    AnimatedVisibility(
                        visible = { raw -> raw.coerceIn(0, maxStateIndex) == stateIdx },
                        enter = enter,
                        exit = exit,
                    ) {
                        if (active.size == 2 && stateIdx == toIdx) {
                            sizeProgressHolder[0] = transition.animateFloat(
                                transitionSpec = { sizeSpec },
                                label = "DocWarpSizeProgress",
                            ) { enterExit ->
                                if (enterExit == EnterExitState.Visible) 1f else 0f
                            }
                        }
                        content(stateIdx, morphProgress)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // Preserve bounded width/height constraints so code morphs never measure with Constraints.Infinity.
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        constraintsObserver?.invoke(nodeId, childConstraints)
        val placeables = measurables.map { it.measure(childConstraints) }
        val (width, height) = if (placeables.size == 2) {
            if (isLookingAhead) {
                placeables[1].width to placeables[1].height
            } else {
                val p = (sizeProgressHolder[0]?.value ?: 1f).coerceIn(0f, 1f)
                val w = placeables[0].width + (placeables[1].width - placeables[0].width) * p
                val h = placeables[0].height + (placeables[1].height - placeables[0].height) * p
                w.roundToInt() to h.roundToInt()
            }
        } else {
            (placeables.firstOrNull()?.width ?: 0) to (placeables.firstOrNull()?.height ?: 0)
        }
        layout(
            width = width.coerceIn(constraints.minWidth, constraints.maxWidth),
            height = height.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

private data class CodeStateTimeline(
    val states: List<String>,
    val highlightsByState: List<Set<Int>>,
    val beatToStateIndex: IntArray,
)

private fun buildCodeStateTimeline(rawNode: NodeSpec, beatCount: Int): CodeStateTimeline {
    val states = ArrayList<String>(beatCount)
    val highlights = ArrayList<Set<Int>>(beatCount)
    val beatToIdx = IntArray(beatCount)
    for (b in 0 until beatCount) {
        val resolved = JsonMergePatch.resolveNodeAtBeat(rawNode, b).code
        val codeText = resolved?.value.orEmpty()
        val hl = resolved?.highlightLines?.toSet().orEmpty()
        val lastIdx = states.lastIndex
        if (lastIdx >= 0 && states[lastIdx] == codeText && highlights[lastIdx] == hl) {
            beatToIdx[b] = lastIdx
        } else {
            beatToIdx[b] = states.size
            states += codeText
            highlights += hl
        }
    }
    if (states.isEmpty()) {
        states.add("")
        highlights.add(emptySet())
    }
    return CodeStateTimeline(states, highlights, beatToIdx)
}

internal fun resolveWarpLanguage(language: String?): WarpLanguage =
    when (language?.let(SyntaxLanguage::fromNameOrAlias)) {
        SyntaxLanguage.Kotlin, null -> WarpKotlinLanguage
        SyntaxLanguage.Xml -> WarpXmlLanguage
        SyntaxLanguage.Json -> WarpJsonLanguage
        SyntaxLanguage.Html -> WarpHtmlLanguage
        SyntaxLanguage.Css -> WarpCssLanguage
        SyntaxLanguage.JavaScript -> WarpJavaScriptLanguage
        SyntaxLanguage.TypeScript -> WarpTypeScriptLanguage
        SyntaxLanguage.Rust -> WarpRustLanguage
        SyntaxLanguage.Go -> WarpGoLanguage
        SyntaxLanguage.Python -> WarpPythonLanguage
        SyntaxLanguage.Toml -> WarpTomlLanguage
        SyntaxLanguage.Java -> WarpJavaLanguage
        SyntaxLanguage.C -> WarpCLanguage
        SyntaxLanguage.Cpp -> WarpCppLanguage
        SyntaxLanguage.Glsl -> WarpGlslLanguage
        SyntaxLanguage.Sksl -> WarpSkslLanguage
        SyntaxLanguage.Agsl -> WarpAgslLanguage
        SyntaxLanguage.Yaml -> WarpYamlLanguage
        SyntaxLanguage.Sql -> WarpSqlLanguage
        SyntaxLanguage.Shell -> WarpShellLanguage
        SyntaxLanguage.CSharp -> WarpCSharpLanguage
        SyntaxLanguage.Php -> WarpPhpLanguage
        SyntaxLanguage.Swift -> WarpSwiftLanguage
        SyntaxLanguage.Ruby -> WarpRubyLanguage
        SyntaxLanguage.Dart -> WarpDartLanguage
        SyntaxLanguage.Groovy -> WarpGroovyLanguage
        SyntaxLanguage.PowerShell -> WarpPowerShellLanguage
        SyntaxLanguage.Dockerfile -> WarpDockerfileLanguage
        SyntaxLanguage.Diff -> WarpDiffLanguage
        SyntaxLanguage.Markdown -> WarpMarkdownLanguage
        SyntaxLanguage.Protobuf -> WarpProtobufLanguage
        SyntaxLanguage.Hcl -> WarpHclLanguage
    }

private fun defaultCodeSurfaceSpec(theme: DeckTheme, density: Density): SurfaceSpec {
    val scale = density.density.toDouble()
    val cardStyle = theme.styles.codeCard
    return SurfaceSpec(
        fill = dev.sebastiano.ciacola.document.Fill.Solid(colorToHex(theme.colors.code.background)),
        stroke = dev.sebastiano.ciacola.document.StrokeSpec(
            color = colorToHex(theme.colors.border),
            width = cardStyle.borderWidth.value * scale,
        ),
        cornerRadius = cardStyle.cornerRadius.value * scale,
        padding = dev.sebastiano.ciacola.document.PaddingSpec(cardStyle.padding.value * scale),
    )
}

private fun colorToHex(color: Color): String {
    val a = (color.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (color.red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (color.green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (color.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    fun h(v: Int) = v.toString(16).uppercase().padStart(2, '0')
    return "#${h(a)}${h(r)}${h(g)}${h(b)}"
}

internal fun resolveDocumentCodeTextStyle(
    theme: DeckTheme,
    colorResolver: DocumentColorResolver,
    fit: String?,
    density: Density,
    fontResolverHandle: Any? = null,
): TextStyle {
    val isCompact = fit == "compact"
    val fallback = if (isCompact) theme.typography.codeCompact else theme.typography.code
    val roles = colorResolver.themeSpec?.typography.orEmpty()
    val override = if (isCompact) roles["codeCompact"] else roles["code"]
    val base = if (override != null) {
        val fontSizeSp = override.size?.toStageSp(density) ?: fallback.fontSize
        val explicitLineHeight = override.lineHeight
        val lineHeightSp = when {
            explicitLineHeight != null -> if (explicitLineHeight < 6.0) {
                (explicitLineHeight * fontSizeSp.value.toDouble()).sp
            } else {
                explicitLineHeight.toStageSp(density)
            }
            override.size != null -> (fontSizeSp.value * 1.35f).sp
            else -> fallback.lineHeight
        }
        fallback.copy(
            fontSize = fontSizeSp,
            lineHeight = lineHeightSp,
            fontWeight = override.weight?.let { FontWeight(it.coerceIn(1, 1000)) } ?: fallback.fontWeight,
            fontStyle = override.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal } ?: fallback.fontStyle,
            letterSpacing = override.tracking?.toStageSp(density) ?: fallback.letterSpacing,
        )
    } else if (isCompact) {
        fallback.copy(fontSize = 18.sp, lineHeight = 26.sp)
    } else {
        fallback
    }
    val fontFamilyName = override?.font ?: roles["code"]?.font
    val axes = FontAxes.merge(roles["code"]?.axes.orEmpty(), override?.axes.orEmpty())
    return resolveDocumentFontStyle(fontResolverHandle, fontFamilyName, base, axes)
}

private fun computeFittedCodeStyle(
    diffList: List<DiffContents>,
    baseStyle: TextStyle,
    fit: String?,
    innerMaxWidthPx: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
): TextStyle {
    if (fit != "shrinkToWidth" || innerMaxWidthPx == Int.MAX_VALUE) return baseStyle
    fun measureMaxRow(style: TextStyle): Int {
        var widest = 0
        for (diff in diffList) {
            for (row in diff.rows) {
                var rowW = 0
                for (st in row) {
                    if (st == WarpTokenState.Empty) continue
                    val t = st.content()
                    if (t.isEmpty()) continue
                    rowW += textMeasurer.measure(t, style = style, softWrap = false, maxLines = 1).size.width
                }
                if (rowW > widest) widest = rowW
            }
        }
        return widest
    }
    val naturalMax = measureMaxRow(baseStyle)
    if (naturalMax <= innerMaxWidthPx || naturalMax <= 0) return baseStyle
    val scale = (innerMaxWidthPx.toFloat() / naturalMax.toFloat()).coerceIn(0.6f, 1f)
    val scaledSize = if (baseStyle.fontSize.isSp) (baseStyle.fontSize.value * scale).coerceAtLeast(6f).sp else baseStyle.fontSize
    val scaledLineH = if (baseStyle.lineHeight.isSp) (baseStyle.lineHeight.value * scale).coerceAtLeast(8f).sp else baseStyle.lineHeight
    return baseStyle.copy(fontSize = scaledSize, lineHeight = scaledLineH)
}

private fun collectStableKeys(diff: DiffContents): Set<String> {
    val keys = HashSet<String>()
    for (row in diff.rows) {
        for (st in row) {
            st.docWarpStableKey()?.let { keys += it }
        }
    }
    return keys
}

private fun collectKeyRowMap(diff: DiffContents): Map<String, Int> {
    val map = HashMap<String, Int>()
    diff.rows.forEachIndexed { rowIdx, row ->
        for (st in row) {
            st.docWarpStableKey()?.let { map.putIfAbsent(it, rowIdx) }
        }
    }
    return map
}

private fun Token.docStyleIn(palette: DeckCodePalette): SpanStyle = palette.styleForWarpScope(scope)

private fun String.isPlainWarpScope(): Boolean = startsWith("source.") || startsWith("text.")

private fun WarpTokenState.docResolveColorToken(): Token = if (
    this is WarpTokenState.Match &&
    current.scope.isPlainWarpScope() &&
    !previous.scope.isPlainWarpScope()
) {
    previous
} else {
    when (this) {
        is WarpTokenState.Match -> current
        is WarpTokenState.Insert -> token
        is WarpTokenState.Delete -> token
        WarpTokenState.Empty -> error("State.Empty does not hold a token")
    }
}

private fun WarpTokenState.Match.docSharedKey(rowIdx: Int, colIdx: Int): String =
    if (previous.hasKey() && current.hasKey()) {
        current.key()
    } else {
        "DocWarpMatch($previousIdx->$currentIdx@$rowIdx:$colIdx)"
    }

private fun WarpTokenState.docWarpStableKey(): String? = when (this) {
    is WarpTokenState.Match -> if (current.hasKey()) current.key() else null
    is WarpTokenState.Insert -> if (token.hasKey()) token.key() else null
    is WarpTokenState.Delete -> if (token.hasKey()) token.key() else null
    WarpTokenState.Empty -> null
}
