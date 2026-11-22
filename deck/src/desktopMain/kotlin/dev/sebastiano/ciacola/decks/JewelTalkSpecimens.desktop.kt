package dev.sebastiano.ciacola.decks

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.RenderPurpose
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.replications.jewel.ALL_JEWEL_AND_NJPT_SPECIMENS
import dev.sebastiano.ciacola.replications.jewel.COHESION_PANEL_MODES
import dev.sebastiano.ciacola.replications.jewel.RenderJewelOrNjptSpecimen

private val JEWEL_DARK_SURFACE: Color = Color(0xFF1E1F22)
private val JEWEL_LIGHT_SURFACE: Color = Color(0xFFF7F8FA)

/**
 * Static [DeckSpecimenRegistry] wiring all 20 live Jewel talk UI specimens for
 * `jewel-talk-2024.deck.json`.
 *
 * All 16 animated specimens declare `loop("time", periodMs = ...)` matching
 * `jewel-talk-2024.deck.json`'s `loopPeriodsMs` keys (`"<ref>/time"`).
 */
val jewelTalkSpecimens: DeckSpecimenRegistry = deckSpecimens {
    for (desc in ALL_JEWEL_AND_NJPT_SPECIMENS) {
        if (!desc.ref.startsWith("jewel.")) continue
        specimen(desc.ref, label = desc.label) {
            val timeLoop = if (desc.periodMs > 0L) {
                loop(name = "time", label = desc.label, periodMs = desc.periodMs)
            } else {
                null
            }

            when (desc.ref) {
                "jewel.confettiPartyPopper" -> {
                    val mirrored = boolean(
                        name = "mirrored",
                        default = false,
                        label = "Mirrored",
                    )
                    content {
                        val isPresent = purpose == RenderPurpose.Present
                        val elapsed = if (reducedMotion || timeLoop == null) 0L else timeLoop()
                        ProvideSurfaceBackground(if (desc.isDark) JEWEL_DARK_SURFACE else JEWEL_LIGHT_SURFACE) {
                            RenderJewelOrNjptSpecimen(
                                ref = desc.ref,
                                loopMillis = elapsed,
                                params = mapOf("mirrored" to mirrored()),
                                isInteractive = isPresent,
                                modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                            )
                        }
                    }
                }

                "jewel.adaptiveWireframePanes" -> {
                    val widthFraction = float(
                        name = "widthFraction",
                        default = 0.72f,
                        range = 0.35f..0.96f,
                        step = 0.01f,
                        label = "Width Fraction",
                    )
                    content {
                        val isPresent = purpose == RenderPurpose.Present
                        val elapsed = if (reducedMotion || timeLoop == null) 0L else timeLoop()
                        ProvideSurfaceBackground(if (desc.isDark) JEWEL_DARK_SURFACE else JEWEL_LIGHT_SURFACE) {
                            RenderJewelOrNjptSpecimen(
                                ref = desc.ref,
                                loopMillis = elapsed,
                                params = mapOf("widthFraction" to widthFraction()),
                                isInteractive = isPresent,
                                modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                            )
                        }
                    }
                }

                "jewel.cohesionIdeShowcase" -> {
                    val panel = choice(
                        name = "panel",
                        default = "ideFull",
                        choices = COHESION_PANEL_MODES,
                        label = "Panel Mode",
                    )
                    content {
                        val isPresent = purpose == RenderPurpose.Present
                        val elapsed = if (reducedMotion || timeLoop == null) 0L else timeLoop()
                        ProvideSurfaceBackground(if (desc.isDark) JEWEL_DARK_SURFACE else JEWEL_LIGHT_SURFACE) {
                            RenderJewelOrNjptSpecimen(
                                ref = desc.ref,
                                loopMillis = elapsed,
                                params = mapOf("panel" to panel()),
                                isInteractive = isPresent,
                                modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                            )
                        }
                    }
                }

                else -> {
                    content {
                        val isPresent = purpose == RenderPurpose.Present
                        val elapsed = if (reducedMotion || timeLoop == null) 0L else timeLoop()
                        ProvideSurfaceBackground(if (desc.isDark) JEWEL_DARK_SURFACE else JEWEL_LIGHT_SURFACE) {
                            RenderJewelOrNjptSpecimen(
                                ref = desc.ref,
                                loopMillis = elapsed,
                                isInteractive = isPresent,
                                modifier = Modifier.fillMaxSize().let { if (isPresent) it.stageHitRegion() else it },
                            )
                        }
                    }
                }
            }
        }
    }
}
