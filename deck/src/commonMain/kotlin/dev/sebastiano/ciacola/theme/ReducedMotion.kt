package dev.sebastiano.ciacola.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * User-selectable motion preference for Ciacola's presentation stage and effects.
 */
enum class ReducedMotionMode {
    /** Follow the host OS accessibility Reduce Motion setting. */
    System,

    /** Force calm reduced motion (crossfades <= 150 ms, no spatial flights or ambient loops). */
    Reduced,

    /** Force full Keynote choreography and ambient motion. */
    Full,
}

/** Maximum duration (in milliseconds) for reduced-motion opacity crossfades. */
const val REDUCED_MOTION_FADE_DURATION_MS: Int = 150

/**
 * Whether reduced motion is currently active in composition.
 * Defaults to `false` so unit tests stay deterministic unless opted in.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * Whether ambient background loops are explicitly paused by the user (WCAG 2.2.2).
 */
val LocalAmbientLoopsPaused: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * Parses `CIACOLA_REDUCE_MOTION` (or equivalent toggle string) into a [ReducedMotionMode].
 */
fun parseReducedMotionEnv(raw: String?): ReducedMotionMode = when (raw?.trim()?.lowercase()) {
    "1", "true", "yes", "reduced", "reduce", "on" -> ReducedMotionMode.Reduced
    "0", "false", "no", "full", "off" -> ReducedMotionMode.Full
    else -> ReducedMotionMode.System
}

/**
 * Resolves the effective boolean reduced-motion state from [mode] and the OS preference.
 */
fun resolveEffectiveReducedMotion(
    mode: ReducedMotionMode,
    systemReducedMotion: Boolean,
): Boolean = when (mode) {
    ReducedMotionMode.System -> systemReducedMotion
    ReducedMotionMode.Reduced -> true
    ReducedMotionMode.Full -> false
}
