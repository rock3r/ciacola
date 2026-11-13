package dev.bnorm.storyboard.easel

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.internal.requestFocus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun Modifier.onStoryNavigation(animatic: Animatic): Modifier {
    val coroutineScope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun handle(event: KeyEvent): Boolean {
        when (event.type) {
            KeyEventType.KeyDown -> {
                when (event.key) {
                    // Presentation clickers send PageDown/PageUp (some send Space/Enter).
                    Key.DirectionRight,
                    Key.DirectionDown,
                    Key.PageDown,
                    Key.Enter,
                    Key.NumPadEnter,
                    Key.Spacebar,
                        -> {
                        job?.cancel()
                        job = coroutineScope.launch {
                            animatic.advance(AdvanceDirection.Forward)
                            job = null
                        }
                        return true
                    }

                    Key.DirectionLeft,
                    Key.DirectionUp,
                    Key.PageUp,
                    Key.Backspace,
                        -> {
                        job?.cancel()
                        job = coroutineScope.launch {
                            animatic.advance(AdvanceDirection.Backward)
                            job = null
                        }
                        return true
                    }
                }
            }
        }

        return false
    }

    return requestFocus().onKeyEvent { handle(it) }
}
