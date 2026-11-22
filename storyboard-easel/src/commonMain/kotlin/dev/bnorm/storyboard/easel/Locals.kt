package dev.bnorm.storyboard.easel

import androidx.compose.animation.core.Transition
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import dev.bnorm.storyboard.Storyboard

val LocalStoryboard: ProvidableCompositionLocal<Storyboard?> = compositionLocalOf { null }

val LocalSceneMode: ProvidableCompositionLocal<SceneMode?> = compositionLocalOf { null }

/**
 * Mount-local root [Animatic] transition provided by [Easel] before invoking [Storyboard.decorator]
 * and scene content. Allows decorators and scene hosts to bind a mount-local clock before
 * [Storyboard.sceneZIndex] is evaluated. `null` for still previews (`ScenePreview`).
 */
val LocalStoryboardTransition: ProvidableCompositionLocal<Transition<out Animatic.State<*>>?> =
    compositionLocalOf { null }

/**
 * Zero-based index of the video frame currently being rendered for emission during MP4 export.
 * Set to `k` before every render attempt for emitted frame `k` (including settle retries), never
 * incremented for discarded settle frames, and `null` outside video frame emission (including live
 * presentation, still previews, and export duration measurement passes).
 */
val LocalExportEmittedFrameIndex: ProvidableCompositionLocal<Long?> = compositionLocalOf { null }

/**
 * Target frame rate (frames per second) of the active video export session, or `null` outside
 * video frame emission. Together with [LocalExportEmittedFrameIndex], deterministic loop clocks can
 * derive exact emitted-frame time (`frameIndex * 1000.0 / fps`).
 */
val LocalExportFps: ProvidableCompositionLocal<Int?> = compositionLocalOf { null }

/**
 * `true` while the Story window presents fullscreen, i.e. it is the audience-facing view. Window
 * decorators use it to keep authoring chrome (HUDs, review pins) off the audience's screen.
 */
val LocalPresentingFullscreen: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * Whether the story viewport may draw its 1dp hairline around the stage. Hosts turn it off outside
 * authoring (e.g. a Present mode) so the audience never sees a frame around the slide.
 */
val LocalStageViewportHairline: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }

/**
 * `true` while the Esc Overview grid is visible so window-level HUD overlays stay hidden and do
 * not occlude overview slide thumbnails.
 */
val LocalOverviewOpen: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }
