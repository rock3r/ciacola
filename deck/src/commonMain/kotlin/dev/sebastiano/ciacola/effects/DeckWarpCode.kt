package dev.sebastiano.ciacola.effects

import androidx.compose.animation.BoundsTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.bnorm.storyboard.BasicCode
import dev.bnorm.storyboard.DiffContents
import dev.bnorm.storyboard.Kotlin
import dev.bnorm.storyboard.Language
import dev.bnorm.storyboard.State
import dev.bnorm.storyboard.Token
import dev.bnorm.storyboard.buildDiffList
import dev.bnorm.storyboard.content
import dev.bnorm.storyboard.easel.DeterministicStateHost
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.DeckCodePalette
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.timeline.HarvestedTrackRecord
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline
import dev.sebastiano.ciacola.timeline.LocalTimelineHarvestCollector
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides
import dev.sebastiano.ciacola.timeline.TrackCategory
import dev.sebastiano.ciacola.timeline.timelineTarget

@Composable
fun DeckWarpCodeCard(
    codeStates: List<String>,
    stateTransition: Transition<Int>,
    modifier: Modifier = Modifier,
    language: Language = Kotlin,
    textStyle: TextStyle = LocalDeckTheme.current.typography.code,
) {
    require(codeStates.isNotEmpty()) { "codeStates must not be empty" }
    val theme = LocalDeckTheme.current
    val cardStyle = theme.styles.codeCard
    val palette = theme.colors.code
    val codeTextStyle = textStyle
    val sceneCtx = LocalActiveSceneTimeline.current
    val collector = LocalTimelineHarvestCollector.current
    val warpTrackId = if (sceneCtx != null) "s${sceneCtx.sceneIndex}:warp-code" else "warp-code"
    val warpOverride = LocalTimelineOverrides.current.tracks[warpTrackId]
    val tokenDurationMs = (warpOverride?.durationMs ?: theme.motion.warpTokenDurationMs).coerceAtLeast(40)

    SideEffect {
        if (sceneCtx != null && codeStates.size > 1) {
            collector?.record(
                HarvestedTrackRecord(
                    trackId = warpTrackId,
                    label = "Warp Code Morph",
                    category = TrackCategory.WarpCode,
                    sceneIndex = sceneCtx.sceneIndex,
                    triggerBeat = warpOverride?.enterBeat ?: 3.coerceAtMost((sceneCtx.totalBeats - 1).coerceAtLeast(0)),
                    durationMs = tokenDurationMs,
                    delayMs = warpOverride?.delayMs ?: 0,
                )
            )
        }
    }

    val diffList: List<DiffContents> = remember(codeStates, language) {
        val blocks = if (codeStates.size == 1) {
            listOf(BasicCode(codeStates[0]), BasicCode(codeStates[0]))
        } else {
            codeStates.map(::BasicCode)
        }
        buildDiffList(blocks, language)
    }
    val warpBoundsTransform = remember(theme.motion) {
        BoundsTransform { _, _ ->
            spring(
                dampingRatio = 0.82f,
                stiffness = 460f,
            )
        }
    }

    val currentCodeSnippet = codeStates[stateTransition.targetState.coerceIn(0, codeStates.lastIndex)]
    val shape = RoundedCornerShape(cardStyle.cornerRadius)
    Surface(
        color = palette.background,
        shape = shape,
        modifier = modifier
            .reviewTarget(
                kind = "WarpCodeCard",
                label = currentCodeSnippet.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "WarpCodeCard" },
                textSnippet = currentCodeSnippet,
            )
            .timelineTarget(warpTrackId)
            .border(cardStyle.borderWidth, theme.colors.border, shape),
    ) {
        SharedTransitionLayout(Modifier.padding(cardStyle.padding).horizontalScroll(rememberScrollState())) {
            stateTransition.DeterministicStateHost(
                enter = { _, _ -> fadeIn(tween(tokenDurationMs)) },
                exit = { _, _ -> fadeOut(tween(tokenDurationMs)) },
            ) { rawIndex ->
                val clampedIndex = rawIndex.coerceIn(0, diffList.lastIndex)
                val diffContents = diffList[clampedIndex]
                Column {
                    diffContents.rows.forEachIndexed { rowIdx, rowStates ->
                        Row {
                            rowStates.forEachIndexed { colIdx, state ->
                                when (state) {
                                    is State.Match -> {
                                        with(this@SharedTransitionLayout) {
                                            Text(
                                                text = state.content(),
                                                style = codeTextStyle,
                                                color = state.current.colorIn(palette),
                                                softWrap = false,
                                                maxLines = 1,
                                                modifier = Modifier.sharedElement(
                                                    sharedContentState = rememberSharedContentState(
                                                        key = state.sharedWarpKey(rowIdx, colIdx),
                                                    ),
                                                    animatedVisibilityScope = this@DeterministicStateHost,
                                                    boundsTransform = warpBoundsTransform,
                                                ),
                                            )
                                        }
                                    }
                                    is State.Insert,
                                    is State.Delete,
                                    -> {
                                        val tok = state.warpToken()
                                        with(this@SharedTransitionLayout) {
                                            with(this@DeterministicStateHost) {
                                                Text(
                                                    text = state.content(),
                                                    style = codeTextStyle,
                                                    color = tok.colorIn(palette),
                                                    softWrap = false,
                                                    maxLines = 1,
                                                    modifier = Modifier
                                                        .sharedElement(
                                                            sharedContentState = rememberSharedContentState(
                                                                key = "delta:${tok.key()}:$rowIdx:$colIdx",
                                                            ),
                                                            animatedVisibilityScope = this@DeterministicStateHost,
                                                            boundsTransform = warpBoundsTransform,
                                                        )
                                                        .renderInSharedTransitionScopeOverlay()
                                                        .animateEnterExit(
                                                            enter = fadeIn(tween(tokenDurationMs)),
                                                            exit = fadeOut(tween(tokenDurationMs)),
                                                        ),
                                                )
                                            }
                                        }
                                    }
                                    State.Empty -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Token.colorIn(palette: DeckCodePalette) =
    palette.colorForWarpScope(scope)

private fun State.warpToken(): Token = when (this) {
    is State.Match -> current
    is State.Insert -> token
    is State.Delete -> token
    State.Empty -> error("State.Empty does not hold a token")
}

private fun State.Match.sharedWarpKey(rowIdx: Int, colIdx: Int): String =
    if (previous.hasKey() && current.hasKey()) {
        current.key()
    } else {
        "WarpMatch($previousIdx->$currentIdx@$rowIdx:$colIdx)"
    }
