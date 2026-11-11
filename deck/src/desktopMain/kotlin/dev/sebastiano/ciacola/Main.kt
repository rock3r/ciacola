package dev.sebastiano.ciacola

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.DesktopEasel
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.assist.rememberAssistantWindow
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberSlideReviewWindow
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() = application {
    var preset by remember {
        val envPreset = System.getenv("CIACOLA_THEME")
        mutableStateOf(
            if (envPreset.equals("light", ignoreCase = true)) {
                DeckThemePreset.KeynoteLight
            } else {
                DeckThemePreset.StageDark
            }
        )
    }
    val hostTheme = remember(preset) { DeckThemes.forPreset(preset) }
    val materialColors = remember(hostTheme) { hostTheme.toMaterialColors() }
    val materialTypography = remember(hostTheme) { hostTheme.toMaterialTypography() }

    MaterialTheme(
        colors = materialColors,
        typography = materialTypography,
    ) {
        val smokeDirectory = System.getenv("DECK_VISUAL_SMOKE_DIR")
        if (smokeDirectory == null) {
            val reviewStore = remember { ReviewCommentStore() }
            val reviewStageCoordinator = rememberReviewStageCoordinator()
            val storyboard = rememberHotReloadStoryboard(preset, reviewStageCoordinator) {
                createFoundationDeck(
                    theme = hostTheme,
                    bundledPreset = preset,
                    includeKeynoteEffectsLab = true,
                    stageDecorator = reviewStageCoordinator.stageDecorator,
                )
            }
            val animatic = rememberAnimatic(
                fileName = "animatic",
                storyboard = { storyboard },
            )
            val themeWindow = rememberThemeSwitcherWindow(
                currentPreset = preset,
                onSelectPreset = { preset = it },
            )
            val reviewWindow = rememberSlideReviewWindow(
                animatic = animatic,
                store = reviewStore,
                stageCoordinator = reviewStageCoordinator,
                currentPresetProvider = { preset },
            )
            DesktopEasel(
                animatic = animatic,
                windows = listOf(rememberAssistantWindow(animatic), themeWindow, reviewWindow),
            )
        } else {
            val storyboard = rememberHotReloadStoryboard(preset) {
                createFoundationDeck(
                    theme = hostTheme,
                    bundledPreset = preset,
                )
            }
            val animatic = rememberAnimatic(storyboard = { storyboard })
            Window(
                onCloseRequest = ::exitApplication,
                title = "Deck visual smoke",
                state = rememberWindowState(width = 960.dp, height = 540.dp),
            ) {
                Easel(animatic)
                LaunchedEffect(animatic, window) {
                    val directory = File(smokeDirectory).apply { mkdirs() }
                    val robot = Robot()

                    suspend fun capture(name: String) {
                        delay(150)
                        val location = window.locationOnScreen
                        val image = robot.createScreenCapture(
                            Rectangle(location.x, location.y, window.width, window.height)
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
                    exitApplication()
                }
            }
        }
    }
}

@Composable
private fun rememberThemeSwitcherWindow(
    currentPreset: DeckThemePreset,
    onSelectPreset: (DeckThemePreset) -> Unit,
): EaselWindow = remember(currentPreset, onSelectPreset) {
    object : EaselWindow {
        override val name: String = "Theme"
        override var visible: Boolean by mutableStateOf(false)

        @Composable
        override fun MenuScope.Menu() {
            Item(
                text = if (currentPreset == DeckThemePreset.StageDark) "✓ Stage Dark" else "Stage Dark",
                onClick = { onSelectPreset(DeckThemePreset.StageDark) },
            )
            Item(
                text = if (currentPreset == DeckThemePreset.KeynoteLight) "✓ Keynote Light" else "Keynote Light",
                onClick = { onSelectPreset(DeckThemePreset.KeynoteLight) },
            )
            Separator()
            Item(
                text = if (visible) "Hide Theme Inspector" else "Show Theme Inspector",
                onClick = { visible = !visible },
            )
        }

        @Composable
        override fun Content() {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Active preset: ${currentPreset.name}")
                Button(onClick = { onSelectPreset(DeckThemePreset.KeynoteLight) }) {
                    Text("Switch to Keynote Light")
                }
                Button(onClick = { onSelectPreset(DeckThemePreset.StageDark) }) {
                    Text("Switch to Stage Dark")
                }
            }
        }
    }
}
