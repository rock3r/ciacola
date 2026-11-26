package dev.sebastiano.ciacola.studio

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Render
import dev.bnorm.storyboard.Scene
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.LocalExportEmittedFrameIndex
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.document.render.documentSceneZIndex
import java.awt.Desktop
import javax.swing.SwingUtilities

/**
 * Picks the earliest beat on [slide] that maximizes visible populated content so multi-beat
 * slides whose hero content enters on Beat 2+ render populated thumbnails in Grid Overview
 * instead of blank Beat-0 canvases.
 */
internal fun representativeOverviewBeatIndex(slide: SlideSpec): Int {
    val beatCount = slide.beats.size
    if (beatCount <= 1) return 0

    fun countVisibleNodes(nodes: List<NodeSpec>, beat: Int): Int {
        var count = 0
        for (node in nodes) {
            if (!JsonMergePatch.isNodePresentAtBeat(node, beat)) continue
            val resolved = JsonMergePatch.resolveNodeAtBeat(node, beat)
            if (resolved.opacity <= 0.01) continue
            count += 1
            if (resolved.children.isNotEmpty()) {
                count += countVisibleNodes(resolved.children, beat)
            }
        }
        return count
    }

    if (countVisibleNodes(slide.nodes, 0) > 0) return 0
    for (beat in 1 until beatCount) {
        if (countVisibleNodes(slide.nodes, beat) > 0) return beat
    }
    return 0
}

/**
 * Precomputes one representative frame index per slide in [nonSkippedSlides] so Grid Overview
 * thumbnail rendering reads cached beat indices without scanning merge patches per frame.
 */
internal fun buildRepresentativeOverviewBeatIndices(nonSkippedSlides: List<SlideSpec>): List<Int> =
    nonSkippedSlides.map(::representativeOverviewBeatIndex)

@Stable
internal class OverviewSlideProviders(
    val titleProvider: (Int) -> String?,
    val thumbnailFrameProvider: (Int, Int) -> Int,
)

@Composable
internal fun rememberOverviewSlideProviders(nonSkippedSlides: List<SlideSpec>): OverviewSlideProviders {
    val titles = remember(nonSkippedSlides) { nonSkippedSlides.map { it.title?.takeIf { t -> t.isNotBlank() } } }
    val repBeats = remember(nonSkippedSlides) { buildRepresentativeOverviewBeatIndices(nonSkippedSlides) }
    return remember(titles, repBeats) {
        OverviewSlideProviders(
            titleProvider = { idx -> titles.getOrNull(idx) },
            thumbnailFrameProvider = { idx, currentFrameIdx ->
                if (currentFrameIdx > 0) currentFrameIdx else repBeats.getOrElse(idx) { currentFrameIdx }
            },
        )
    }
}

/**
 * Builds an Overview [Storyboard] where every rendered slide/beat thumbnail receives its own
 * isolated [RenderSession.forStill] instance.
 *
 * Isolating thumbnails per `(slideId, beatIdx)` ensures that stateful specimens' `retained` store
 * and `movableContentOf` slots never cross-talk across Overview cards.
 */
internal fun buildPerThumbnailOverviewStoryboard(
    frozenDoc: DeckDocument,
    config: DocumentRenderConfig,
    sessionFactory: (DeckDocument) -> RenderSession = { RenderSession.forStill(documentSnapshot = it) },
): Storyboard {
    val nonSkipped = frozenDoc.slides.filterNot { it.skipped }
    val format = SceneFormat(
        size = IntSize(frozenDoc.stage.width, frozenDoc.stage.height),
        density = Density(
            density = config.baseDensity,
            fontScale = config.fontScale,
        ),
    )

    return Storyboard.build(
        title = frozenDoc.title.ifBlank { frozenDoc.id },
        format = format,
        decorator = ContentDecorator.None,
    ) {
        if (nonSkipped.isEmpty()) {
            scene(
                frames = listOf(0),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                Box(Modifier.fillMaxSize())
            }
        } else {
            for (slide in nonSkipped) {
                val beatCount = slide.beats.size.coerceAtLeast(1)
                val singleSlideDoc = frozenDoc.copy(slides = listOf(slide))
                scene(
                    frames = List(beatCount) { it },
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                ) {
                    val frameState = transition.currentState
                    val beatIdx = when (frameState) {
                        is Frame.Value -> frameState.value.coerceIn(0, beatCount - 1)
                        Frame.Start -> 0
                        Frame.End -> (beatCount - 1).coerceAtLeast(0)
                    }
                    val thumbStoryboard = remember(singleSlideDoc, beatIdx, config) {
                        val thumbSession = sessionFactory(singleSlideDoc)
                        buildDocumentStoryboard(session = thumbSession, config = config)
                    }
                    @Suppress("UNCHECKED_CAST")
                    val thumbScene = thumbStoryboard.scenes.first() as Scene<Int>
                    thumbStoryboard.decorator.decorate {
                        Render(thumbScene.content)
                    }
                }
            }
        }
    }
}

/**
 * Builds a Video Export [Storyboard] that isolates `StoryboardVideoExporter`'s measurement pass
 * (`LocalExportEmittedFrameIndex.current == null`) and real video render pass
 * (`LocalExportEmittedFrameIndex.current != null`) onto two completely independent
 * [RenderSession.forVideo] instances.
 */
internal fun buildDualPassVideoStoryboard(
    frozenDoc: DeckDocument,
    config: DocumentRenderConfig,
    measurementSession: RenderSession = RenderSession.forVideo(documentSnapshot = frozenDoc),
    renderSession: RenderSession = RenderSession.forVideo(documentSnapshot = frozenDoc),
): Storyboard {
    val measurementSb = buildDocumentStoryboard(session = measurementSession, config = config)
    val renderSb = buildDocumentStoryboard(session = renderSession, config = config)
    val activePassSession = ActiveSessionBox(measurementSession)

    val dualDecorator = ContentDecorator { content ->
        val isRenderPass = LocalExportEmittedFrameIndex.current != null
        activePassSession.current = if (isRenderPass) renderSession else measurementSession
        val delegate = if (isRenderPass) renderSb else measurementSb
        delegate.decorator.decorate(content)
    }

    return Storyboard.build(
        title = renderSb.title,
        format = renderSb.format,
        decorator = dualDecorator,
        sceneZIndex = { index ->
            documentSceneZIndex(
                session = activePassSession.current,
                config = config,
                index = index,
            )
        },
    ) {
        for (sceneIdx in renderSb.scenes.indices) {
            @Suppress("UNCHECKED_CAST")
            val mScene = measurementSb.scenes[sceneIdx] as Scene<Int>
            @Suppress("UNCHECKED_CAST")
            val rScene = renderSb.scenes[sceneIdx] as Scene<Int>
            scene(
                frames = rScene.frames,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                val target = if (LocalExportEmittedFrameIndex.current != null) rScene else mScene
                Render(target.content)
            }
        }
    }
}

private class ActiveSessionBox(
    @Volatile
    var current: RenderSession,
)

/**
 * Installs a macOS / AWT application quit handler (`⌘Q`, Dock Quit, menu Quit) that routes quit
 * requests through [onQuitRequested] so the close-hold dialog and flush guard run before exit.
 *
 * Returns `true` when the desktop platform supported and installed the quit handler.
 */
internal fun installDesktopQuitHandler(
    onQuitRequested: () -> Unit,
): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return@runCatching false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) return@runCatching false
    desktop.setQuitHandler { _, response ->
        // Cancel AWT's immediate hard exit and let Compose's guarded requestExitApplication()
        // flush cleanly or present the close-hold dialog before calling exitApplication().
        response.cancelQuit()
        SwingUtilities.invokeLater { onQuitRequested() }
    }
    true
}.getOrDefault(false)

/** Clears any previously installed AWT application quit handler. */
internal fun clearDesktopQuitHandler() {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                desktop.setQuitHandler(null)
            }
        }
    }
}
