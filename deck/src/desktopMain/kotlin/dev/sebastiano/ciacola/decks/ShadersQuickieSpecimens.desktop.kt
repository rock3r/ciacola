package dev.sebastiano.ciacola.decks

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.RenderPurpose
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.replications.shaders.RenderShadersSpecimen
import dev.sebastiano.ciacola.replications.shaders.ShadersSpecimenIds

/**
 * Static [DeckSpecimenRegistry] wiring all 14 live SkSL / Skia `RenderEffect` specimens for
 * `shaders-quickie-2025.deck.json`.
 *
 * All 7 animated specimens declare `loop("time", periodMs = ...)` matching
 * `shaders-quickie-2025.deck.json`'s `loopPeriodsMs` keys (`"<ref>/time"`).
 */
val shadersQuickieSpecimens: DeckSpecimenRegistry = deckSpecimens {
    specimen(ShadersSpecimenIds.TRIPPY_KALEIDOSCOPE, label = "Trippy Kaleidoscope") {
        val time = loop(
            name = "time",
            label = "Kaleidoscope Orbit",
            periodMs = ShadersSpecimenIds.TRIPPY_KALEIDOSCOPE_PERIOD_MS,
        )
        val folds = uniformFloat(
            name = "folds",
            default = 6f,
            range = 3f..12f,
            step = 1f,
            label = "Folds",
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color(0xFF0E1017)) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.TRIPPY_KALEIDOSCOPE,
                    loopMillis = if (reducedMotion) 0L else time(),
                    params = mapOf("folds" to folds()),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.JOURNEY_DUNES, label = "Journey Desert Dunes") {
        val time = loop(
            name = "time",
            label = "Dunes Shimmer",
            periodMs = ShadersSpecimenIds.JOURNEY_DUNES_PERIOD_MS,
        )
        val shimmer = uniformFloat(
            name = "shimmer",
            default = 1f,
            range = 0f..2f,
            step = 0.05f,
            label = "Heat Shimmer",
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color(0xFF281812)) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.JOURNEY_DUNES,
                    loopMillis = if (reducedMotion) 0L else time(),
                    params = mapOf("shimmer" to shimmer()),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.HAZE_SCROLL_HEADER, label = "Haze Frosted Scroll Header") {
        val time = loop(
            name = "time",
            label = "Haze Scroll Cycle",
            periodMs = ShadersSpecimenIds.HAZE_SCROLL_HEADER_PERIOD_MS,
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color(0xFF121620)) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.HAZE_SCROLL_HEADER,
                    loopMillis = if (reducedMotion) 0L else time(),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.LIQUID_GLASS_LENS, label = "Liquid Glass Refractive Lens") {
        val time = loop(
            name = "time",
            label = "Liquid Glass Orbit",
            periodMs = ShadersSpecimenIds.LIQUID_GLASS_LENS_PERIOD_MS,
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color(0xFF101824)) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.LIQUID_GLASS_LENS,
                    loopMillis = if (reducedMotion) 0L else time(),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.RAINDROPS_WINDOW, label = "Raindrops on Glass") {
        val time = loop(
            name = "time",
            label = "Raindrops Fall",
            periodMs = ShadersSpecimenIds.RAINDROPS_WINDOW_PERIOD_MS,
        )
        val intensity = uniformFloat(
            name = "intensity",
            default = 1f,
            range = 0.2f..2f,
            step = 0.05f,
            label = "Rain Intensity",
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color(0xFF0E141E)) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.RAINDROPS_WINDOW,
                    loopMillis = if (reducedMotion) 0L else time(),
                    params = mapOf("intensity" to intensity()),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.COLOR_EVOLUTION, label = "Flat RGBA Color Shader") {
        val r = uniformFloat(name = "r", default = 0f, range = 0f..1f, step = 0.01f, label = "Red")
        val g = uniformFloat(name = "g", default = 0f, range = 0f..1f, step = 0.01f, label = "Green")
        val b = uniformFloat(name = "b", default = 0f, range = 0f..1f, step = 0.01f, label = "Blue")
        val a = uniformFloat(name = "a", default = 1f, range = 0f..1f, step = 0.01f, label = "Alpha")
        content {
            val isPresent = purpose == RenderPurpose.Present
            val rVal = r()
            val gVal = g()
            val bVal = b()
            val aVal = a()
            ProvideSurfaceBackground(Color(rVal, gVal, bVal, aVal)) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.COLOR_EVOLUTION,
                    loopMillis = 0L,
                    params = mapOf("r" to rVal, "g" to gVal, "b" to bVal, "a" to aVal),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.LIVE_UV_STATIC, label = "Live UV Gradient (Static)") {
        val blue = uniformFloat(
            name = "blue",
            default = 1f,
            range = 0f..1f,
            step = 0.05f,
            label = "Blue Channel",
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color(0xFF202040)) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.LIVE_UV_STATIC,
                    loopMillis = 0L,
                    params = mapOf("blue" to blue()),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.LIVE_UV_ANIMATED, label = "Live UV Gradient (Animated)") {
        val time = loop(
            name = "time",
            label = "Animated UV Cycle",
            periodMs = ShadersSpecimenIds.LIVE_UV_ANIMATED_PERIOD_MS,
        )
        val speed = uniformFloat(
            name = "speed",
            default = 1f,
            range = 0f..4f,
            step = 0.05f,
            label = "Speed",
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color(0xFF202040)) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.LIVE_UV_ANIMATED,
                    loopMillis = if (reducedMotion) 0L else time(),
                    params = mapOf("speed" to speed()),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.SDF_GOAL_CIRCLE, label = "SDF Goal Circle") {
        val radius = uniformFloat(
            name = "radius",
            default = 0.5f,
            range = 0.05f..0.95f,
            step = 0.01f,
            label = "Radius",
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color.Black) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.SDF_GOAL_CIRCLE,
                    loopMillis = 0L,
                    params = mapOf("radius" to radius()),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.SDF_RAW_DISTANCE, label = "SDF Raw Distance Field") {
        val radius = uniformFloat(
            name = "radius",
            default = 0.5f,
            range = 0.05f..0.95f,
            step = 0.01f,
            label = "Radius",
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color.Black) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.SDF_RAW_DISTANCE,
                    loopMillis = 0L,
                    params = mapOf("radius" to radius()),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.SDF_STEP_CIRCLE, label = "SDF Step Circle") {
        val radius = uniformFloat(
            name = "radius",
            default = 0.5f,
            range = 0.05f..0.95f,
            step = 0.01f,
            label = "Radius",
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color.Black) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.SDF_STEP_CIRCLE,
                    loopMillis = 0L,
                    params = mapOf("radius" to radius()),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.SDF_CSG_SUBTRACT, label = "SDF CSG Subtract") {
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color.Black) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.SDF_CSG_SUBTRACT,
                    loopMillis = 0L,
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.SDF_CSG_UNION_SUBTRACT, label = "SDF CSG Union & Subtract") {
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color.Black) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.SDF_CSG_UNION_SUBTRACT,
                    loopMillis = 0L,
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }

    specimen(ShadersSpecimenIds.PLAID_HYPERSPACE_STARFIELD, label = "Plaid Hyperspace Starfield") {
        val time = loop(
            name = "time",
            label = "Plaid Starfield Loop",
            periodMs = ShadersSpecimenIds.PLAID_HYPERSPACE_STARFIELD_PERIOD_MS,
        )
        val uPlaidMix = uniformFloat(
            name = "uPlaidMix",
            default = 0.65f,
            range = 0f..1f,
            step = 0.05f,
            label = "Plaid Mix",
        )
        content {
            val isPresent = purpose == RenderPurpose.Present
            ProvideSurfaceBackground(Color(0xFF0A0D18)) {
                RenderShadersSpecimen(
                    ref = ShadersSpecimenIds.PLAID_HYPERSPACE_STARFIELD,
                    loopMillis = if (reducedMotion) 0L else time(),
                    params = mapOf("uPlaidMix" to uPlaidMix()),
                    isInteractive = isPresent,
                    modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                )
            }
        }
    }
}
