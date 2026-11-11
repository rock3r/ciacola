package dev.sebastiano.ciacola.effects

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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
import dev.sebastiano.ciacola.theme.DeckCodePalette
import dev.sebastiano.ciacola.theme.LocalDeckTheme

@Composable
fun DeckWarpCodeCard(
    codeStates: List<String>,
    stateTransition: Transition<Int>,
    modifier: Modifier = Modifier,
    language: Language = Kotlin,
) {
    require(codeStates.isNotEmpty()) { "codeStates must not be empty" }
    val theme = LocalDeckTheme.current
    val cardStyle = theme.styles.codeCard
    val palette = theme.colors.code
    val codeTextStyle = theme.typography.code
    val tokenDurationMs = theme.motion.warpTokenDurationMs

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

    val shape = RoundedCornerShape(cardStyle.cornerRadius)
    Surface(
        color = palette.background,
        shape = shape,
        modifier = modifier.border(cardStyle.borderWidth, theme.colors.border, shape),
    ) {
        SharedTransitionLayout(Modifier.padding(cardStyle.padding)) {
            stateTransition.AnimatedContent(
                transitionSpec = {
                    fadeIn(tween(tokenDurationMs)) togetherWith fadeOut(tween(tokenDurationMs))
                },
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
                                                modifier = Modifier.sharedElement(
                                                    sharedContentState = rememberSharedContentState(
                                                        key = state.sharedWarpKey(rowIdx, colIdx),
                                                    ),
                                                    animatedVisibilityScope = this@AnimatedContent,
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
                                            with(this@AnimatedContent) {
                                                Text(
                                                    text = state.content(),
                                                    style = codeTextStyle,
                                                    color = tok.colorIn(palette),
                                                    modifier = Modifier
                                                        .sharedElement(
                                                            sharedContentState = rememberSharedContentState(
                                                                key = "delta:${tok.key()}:$rowIdx:$colIdx",
                                                            ),
                                                            animatedVisibilityScope = this@AnimatedContent,
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
