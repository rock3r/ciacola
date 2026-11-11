package dev.sebastiano.ciacola

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.bnorm.storyboard.Storyboard
import org.jetbrains.compose.reload.AfterHotReloadEffect

@Composable
fun rememberHotReloadGeneration(): Int {
    var generation by remember { mutableIntStateOf(0) }
    AfterHotReloadEffect {
        generation++
    }
    return generation
}

@Composable
fun rememberHotReloadStoryboard(
    vararg keys: Any?,
    build: () -> Storyboard,
): Storyboard {
    val reloadGeneration = rememberHotReloadGeneration()
    return remember(reloadGeneration, *keys) {
        build()
    }
}
