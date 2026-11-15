package dev.sebastiano.ciacola.authoring

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.DeckTextRenderer
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.MagicTextV1Renderer
import dev.sebastiano.ciacola.effects.LocalStageAnchorRegistry
import dev.sebastiano.ciacola.effects.StageAnchorRegistry
import dev.sebastiano.ciacola.inspector.DeckElementOverridesFile
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.StageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.StageRootCoordinatesHolder
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.theme.LocalReducedMotion
import dev.sebastiano.ciacola.theme.withOverrides
import dev.sebastiano.ciacola.timeline.DeckTimelineOverridesFile
import dev.sebastiano.ciacola.timeline.LocalTimelineOffscreen
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides

/**
 * Host configuration, theme bindings, and live Studio override providers passed to deck factories
 * and [ciacolaDeck].
 *
 * @property textRenderer Text animation engine installed via [DeckTextRendererProvider].
 * @property theme Fallback [DeckTheme] used when [bundledPreset] is `null`.
 * @property bundledPreset Optional bundled preset resolved with bundled Inter & JetBrains Mono fonts.
 * @property stageDecorator Additional [ContentDecorator] chained inside the stage root.
 * @property timelineOverrides Static timeline override snapshot (used when [timelineOverridesProvider] is default).
 * @property reducedMotion Enables shortened, motion-safe slide and build transitions.
 * @property elementOverrides Static element override snapshot (used when [elementOverridesProvider] is default).
 * @property designOverrides Static design-system override snapshot (used when [designOverridesProvider] is default).
 * @property timelineOverridesProvider Live provider read per composition for Studio timeline overrides.
 * @property elementOverridesProvider Live provider read per composition for Studio per-element overrides.
 * @property designOverridesProvider Live provider read per composition for Studio design-system overrides.
 */
data class DeckHostParams(
    val textRenderer: DeckTextRenderer = MagicTextV1Renderer,
    val theme: DeckTheme = DeckThemes.stageDark(),
    val bundledPreset: DeckThemePreset? = null,
    val stageDecorator: ContentDecorator = ContentDecorator.None,
    val timelineOverrides: DeckTimelineOverridesFile = DeckTimelineOverridesFile(),
    val reducedMotion: Boolean = false,
    val elementOverrides: DeckElementOverridesFile = DeckElementOverridesFile(),
    val designOverrides: DesignSystemOverrides = DesignSystemOverrides(),
    val timelineOverridesProvider: () -> DeckTimelineOverridesFile = { timelineOverrides },
    val elementOverridesProvider: () -> DeckElementOverridesFile = { elementOverrides },
    val designOverridesProvider: () -> DesignSystemOverrides = { designOverrides },
)

/**
 * Root [ContentDecorator] installing theme, text renderer, global `LocalDensity.fontScale`, stage
 * coordinate root, interactive specimen registry, and live Studio override CompositionLocals for a
 * deck hosted with [host].
 */
fun deckRootDecorator(host: DeckHostParams): ContentDecorator {
    val coreDecorator = ContentDecorator { content ->
        val currentDesignOverrides = host.designOverridesProvider()
        val currentTimelineOverrides = host.timelineOverridesProvider()
        val currentElementOverrides = host.elementOverridesProvider()
        val baseActiveTheme = if (host.bundledPreset != null) {
            DeckThemes.rememberBundledDeckTheme(host.bundledPreset)
        } else {
            host.theme
        }
        val activeTheme = remember(baseActiveTheme, currentDesignOverrides) {
            baseActiveTheme.withOverrides(currentDesignOverrides)
        }
        val effectiveOverrides = if (LocalTimelineOffscreen.current) {
            LocalTimelineOverrides.current
        } else {
            currentTimelineOverrides
        }
        val effectiveElementOverrides = if (currentElementOverrides.hasOverrides) {
            currentElementOverrides
        } else {
            LocalElementOverrides.current
        }
        val existingStageRoot = LocalStageRootCoordinates.current
        val stageRootHolder = existingStageRoot ?: remember { StageRootCoordinatesHolder() }
        val existingInteractiveRegistry = LocalStageInteractiveRegistry.current
        val interactiveRegistry = existingInteractiveRegistry ?: remember { StageInteractiveRegistry() }
        val existingAnchorRegistry = LocalStageAnchorRegistry.current
        val anchorRegistry = existingAnchorRegistry ?: remember { StageAnchorRegistry() }
        val baseDensity = LocalDensity.current
        val fontScaleMultiplier = (currentDesignOverrides.typography.globalFontScale ?: 1f).coerceIn(0.5f, 2.5f)
        val stageDensity = remember(baseDensity, fontScaleMultiplier) {
            Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * fontScaleMultiplier,
            )
        }
        CompositionLocalProvider(
            LocalDensity provides stageDensity,
            LocalTimelineOverrides provides effectiveOverrides,
            LocalElementOverrides provides effectiveElementOverrides,
            LocalReducedMotion provides host.reducedMotion,
            LocalStageRootCoordinates provides stageRootHolder,
            LocalStageInteractiveRegistry provides interactiveRegistry,
            LocalStageAnchorRegistry provides anchorRegistry,
        ) {
            DeckTextRendererProvider(host.textRenderer) {
                DeckThemeProvider(activeTheme) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .onPlaced { coords ->
                                if (existingStageRoot == null) {
                                    stageRootHolder.coordinates = coords
                                }
                            },
                        color = activeTheme.colors.background,
                    ) { content() }
                }
            }
        }
    }
    return coreDecorator + host.stageDecorator
}
