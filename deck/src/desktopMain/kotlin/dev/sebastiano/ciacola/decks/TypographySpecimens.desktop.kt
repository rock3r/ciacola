package dev.sebastiano.ciacola.decks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.document.render.toStageSp
import dev.sebastiano.ciacola.text.v2.VariableAxisText
import kotlin.math.sin

/** Ids and loop periods of the typography specimens. */
object TypographySpecimenIds {
    const val AXIS_BREATH: String = "typography.axisBreath"
    const val AXIS_BREATH_PERIOD_MS: Long = 2_400L
}

/**
 * Typography specimens that loop variable font axes on their own clock, beyond what declarative
 * builds express. Not tied to one deck: external decks resolve them through
 * [AllDocumentDecks.allSpecimens].
 */
val typographySpecimens: DeckSpecimenRegistry = deckSpecimens {
    specimen(TypographySpecimenIds.AXIS_BREATH, label = "Breathing Variable Axes") {
        val time = loop(
            name = "time",
            label = "Breath",
            periodMs = TypographySpecimenIds.AXIS_BREATH_PERIOD_MS,
        )
        val word = string(name = "text", default = "Variable", label = "Text")
        val font = string(name = "font", default = "Roboto", label = "Font family")
        val sizeUnits = float(name = "size", default = 220f, range = 48f..400f, step = 4f, label = "Size (stage units)")
        val phasePerCharacter = float(
            name = "phasePerCharacter",
            default = 0.6f,
            range = 0f..2f,
            step = 0.05f,
            label = "Phase per character (rad)",
        )

        content {
            // Each character breathes between light-condensed and bold-wide, a wave travelling
            // through the word once per loop.
            val phase = if (reducedMotion) 0f else time.phaseRadians()
            val step = phasePerCharacter()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VariableAxisText(
                    text = word(),
                    fontFamilyName = font(),
                    fontSize = sizeUnits().toStageSp(LocalDensity.current),
                    axes = mapOf("wght" to 400.0, "wdth" to 100.0),
                    color = theme.colors.foreground,
                    perCharacterAxes = { index, _ ->
                        val s = sin(phase - index * step).toDouble()
                        mapOf("wght" to 500.0 + 300.0 * s, "wdth" to 87.5 + 12.5 * s)
                    },
                )
            }
        }
    }
}
