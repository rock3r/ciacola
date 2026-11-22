package dev.sebastiano.ciacola.studio

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.decks.loadClasspathDeckDocument
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.document.render.resolveDeckTheme
import dev.sebastiano.ciacola.theme.LocalAmbientLoopsPaused
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `DECK_VISUAL_SMOKE_DIR` capture window: steps forward and backward through the requested deck and
 * writes a PNG of every transition midpoint and settled beat.
 */
@Composable
internal fun CiacolaVisualSmokeWindow(
    smokeDirectory: String,
    ambientLoopsPaused: Boolean,
    effectiveReducedMotion: Boolean,
    onExitApplication: () -> Unit,
) {
    val activeDeck = remember { DeckCatalog.byId(System.getProperty("ciacola.deck")) }
    val doc = remember(activeDeck) { loadClasspathDeckDocument(activeDeck) }
    val envTheme = System.getenv("CIACOLA_THEME")
    val themeName = remember(doc, envTheme) {
        if (envTheme.equals("light", ignoreCase = true) && "light" in doc.themes) "light" else doc.defaultTheme
    }
    val hostTheme = remember(doc, themeName) { resolveDeckTheme(doc, themeName) }
    val materialColors = remember(hostTheme) { hostTheme.toMaterialColors() }
    val materialTypography = remember(hostTheme) { hostTheme.toMaterialTypography() }
    var smokeClockMs by remember(activeDeck.id) { mutableLongStateOf(0L) }
    LaunchedEffect(activeDeck.id, ambientLoopsPaused, effectiveReducedMotion) {
        if (ambientLoopsPaused || effectiveReducedMotion) return@LaunchedEffect
        var prev = -1L
        while (true) {
            withFrameNanos { nanos ->
                if (prev >= 0L) smokeClockMs += ((nanos - prev) / 1_000_000L).coerceAtLeast(0L)
                prev = nanos
            }
        }
    }
    val session = remember(doc, activeDeck) {
        RenderSession.forPresent(
            documentProvider = { doc },
            freeClockMillis = { smokeClockMs },
        )
    }
    val config = remember(activeDeck, themeName, effectiveReducedMotion) {
        DocumentRenderConfig(
            themeName = themeName,
            specimens = activeDeck.specimens,
            reducedMotion = effectiveReducedMotion,
            assetsRootDir = activeDeck.assetsResourceDir,
        )
    }
    CompositionLocalProvider(LocalAmbientLoopsPaused provides ambientLoopsPaused) {
        val storyboard = rememberDocumentStoryboard(session = session, config = config)
        val animatic = rememberAnimatic(storyboard = { storyboard })
        Window(
            onCloseRequest = onExitApplication,
            title = "Deck visual smoke",
            state = rememberWindowState(width = 960.dp, height = 540.dp),
        ) {
            MaterialTheme(colors = materialColors, typography = materialTypography) {
                Easel(animatic)
                LaunchedEffect(animatic, window) {
                    val directory = File(smokeDirectory).apply { mkdirs() }
                    val robot = Robot()

                    suspend fun capture(name: String) {
                        delay(150)
                        val location = window.locationOnScreen
                        val image = robot.createScreenCapture(
                            Rectangle(location.x, location.y, window.width, window.height),
                        )
                        ImageIO.write(image, "png", File(directory, "$name.png"))
                    }

                    delay(750)
                    capture("00-start")
                    var forward = 1
                    while (true) {
                        val movement = launch { animatic.advance(AdvanceDirection.Forward) }
                        delay(350)
                        capture("f${forward.toString().padStart(2, '0')}-transition")
                        movement.join()
                        capture("f${forward.toString().padStart(2, '0')}-settled")
                        if (animatic.currentIndex.sceneIndex == 2) {
                            capture("loop-a")
                            delay(1_000)
                            capture("loop-b")
                        }
                        forward++
                        if (animatic.currentIndex == animatic.storyboard.indices.last()) break
                    }

                    var backward = 1
                    while (animatic.currentIndex != animatic.storyboard.indices.first()) {
                        val movement = launch { animatic.advance(AdvanceDirection.Backward) }
                        delay(350)
                        capture("b${backward.toString().padStart(2, '0')}-transition")
                        movement.join()
                        capture("b${backward.toString().padStart(2, '0')}-settled")
                        backward++
                    }
                    window.dispose()
                    onExitApplication()
                }
            }
        }
    }
}
