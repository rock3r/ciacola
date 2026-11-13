package dev.sebastiano.ciacola

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.bnorm.storyboard.SceneFormat
import dev.sebastiano.ciacola.theme.DeckThemes

object DeckDesign {
    private val defaultTheme = DeckThemes.stageDark()

    val background get() = defaultTheme.colors.background
    val surface get() = defaultTheme.colors.surface
    val foreground get() = defaultTheme.colors.foreground
    val muted get() = defaultTheme.colors.muted
    val accent get() = defaultTheme.colors.accent
    val secondary get() = defaultTheme.colors.secondary

    val title: TextStyle get() = defaultTheme.typography.title
    val heading: TextStyle get() = defaultTheme.typography.heading
    val body: TextStyle get() = defaultTheme.typography.body
    val code: TextStyle get() = defaultTheme.typography.code
    val codeEmphasis: TextStyle get() = defaultTheme.typography.codeEmphasis

    val format = SceneFormat(IntSize(1920, 1080), Density(2f))
}
