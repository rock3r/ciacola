package dev.sebastiano.ciacola.decks

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.RenderPurpose
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.replications.jewel.ALL_JEWEL_AND_NJPT_SPECIMENS
import dev.sebastiano.ciacola.replications.jewel.RenderJewelOrNjptSpecimen

private val NJPT_DARK_SURFACE: Color = Color(0xFF1E1F22)
private val NJPT_LIGHT_SURFACE: Color = Color(0xFFF7F8FA)

/**
 * Static [DeckSpecimenRegistry] wiring both live Jewel Markdown specimens for
 * `not-just-plain-text-2024.deck.json` (`njpt.jewelMarkdownSplitDemo` and
 * `njpt.geminiDarkMarkdownChat`).
 */
val notJustPlainTextSpecimens: DeckSpecimenRegistry = deckSpecimens {
    for (desc in ALL_JEWEL_AND_NJPT_SPECIMENS) {
        if (!desc.ref.startsWith("njpt.")) continue
        specimen(desc.ref, label = desc.label) {
            val timeLoop = if (desc.periodMs > 0L) {
                loop(name = "time", label = desc.label, periodMs = desc.periodMs)
            } else {
                null
            }
            content {
                val isPresent = purpose == RenderPurpose.Present
                val elapsed = if (reducedMotion || timeLoop == null) 0L else timeLoop()
                ProvideSurfaceBackground(if (desc.isDark) NJPT_DARK_SURFACE else NJPT_LIGHT_SURFACE) {
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
