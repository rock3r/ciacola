package dev.bnorm.storyboard.easel.internal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.pointer.pointerInput

@Composable
internal fun Modifier.requestFocus(): Modifier {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    return this
        .pointerInput(focusRequester) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = true)
                focusRequester.requestFocus()
            }
        }
        .focusRequester(focusRequester)
        .focusTarget()
}
