package dev.sebastiano.ciacola.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Per-slide surface and foreground palette override applied by the slide authoring DSL.
 *
 * When provided to a slide, [canvas] paints the full-bleed slide background and updates
 * `LocalEffectiveBackgroundColor` so descendant contrast checks and loupes resolve against it.
 */
@Immutable
data class SlidePalette(
    val canvas: Color,
    val ink: Color? = null,
    val mutedInk: Color? = null,
    val accent: Color? = null,
) {
    constructor(
        canvas: Long,
        ink: Long? = null,
        mutedInk: Long? = null,
        accent: Long? = null,
    ) : this(
        canvas = Color(canvas),
        ink = ink?.let(::Color),
        mutedInk = mutedInk?.let(::Color),
        accent = accent?.let(::Color),
    )
}

val LocalSlidePalette = compositionLocalOf<SlidePalette?> { null }
