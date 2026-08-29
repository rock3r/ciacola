package dev.sebastiano.deck

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.DesktopEasel
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() = application {
    MaterialTheme(colors = darkColors()) {
        val smokeDirectory = System.getenv("DECK_VISUAL_SMOKE_DIR")
        if (smokeDirectory == null) {
            DesktopEasel { createFoundationDeck() }
        } else {
            val animatic = rememberAnimatic(storyboard = ::createFoundationDeck)
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
