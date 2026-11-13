package dev.bnorm.storyboard.easel

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import dev.bnorm.storyboard.Storyboard

val LocalStoryboard: ProvidableCompositionLocal<Storyboard?> = compositionLocalOf { null }

val LocalSceneMode: ProvidableCompositionLocal<SceneMode?> = compositionLocalOf { null }

/**
 * `true` while the Story window presents fullscreen, i.e. it is the audience-facing view. Window
 * decorators use it to keep authoring chrome (HUDs, review pins) off the audience's screen.
 */
val LocalPresentingFullscreen: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }
