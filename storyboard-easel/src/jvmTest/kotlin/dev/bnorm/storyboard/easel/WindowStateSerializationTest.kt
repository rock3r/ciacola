package dev.bnorm.storyboard.easel

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.bnorm.storyboard.easel.internal.WindowStateSerializer
import dev.bnorm.storyboard.easel.internal.clampWindowPositionToScreens
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class WindowStateSerializationTest {

    @Test
    fun negativeWindowCoordinatesDeserializeWithoutResettingSizeTo800x600() {
        val rawJson = """{"placement":"Floating","isMinimized":false,"position":{"x":-180.0,"y":64.0},"size":{"width":1440.0,"height":920.0}}"""
        val decoded = Json.decodeFromString(WindowStateSerializer, rawJson)

        assertEquals(DpSize(1440.dp, 920.dp), decoded.size)
        assertEquals(WindowPosition((-180).dp, 64.dp), decoded.position)

        // Round-trip negative X and negative Y when straddling or on a secondary display
        val tempFile = Files.createTempFile("story-window-", ".json")
        try {
            val saved = WindowState(
                placement = WindowPlacement.Floating,
                isMinimized = false,
                position = WindowPosition((-120).dp, (-20).dp),
                size = DpSize(1440.dp, 920.dp),
            )
            tempFile.writeText(Json.encodeToString(WindowStateSerializer, saved))
            val reloaded = Json.decodeFromString(WindowStateSerializer, Files.readString(tempFile))
            assertEquals(DpSize(1440.dp, 920.dp), reloaded.size)
            assertEquals(WindowPosition((-120).dp, (-20).dp), reloaded.position)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun clampWindowPositionKeepsOnscreenNegativeMonitorsAndRecoversUnpluggedDisplays() {
        val leftAndPrimaryScreens = listOf(
            Rect(-1920f, 0f, 0f, 1080f),
            Rect(0f, 0f, 1920f, 1080f),
        )
        val onLeftMonitor = WindowPosition((-1500).dp, 80.dp)
        assertEquals(
            onLeftMonitor,
            clampWindowPositionToScreens(onLeftMonitor, DpSize(1280.dp, 800.dp), leftAndPrimaryScreens),
        )

        val primaryOnly = listOf(Rect(0f, 0f, 1920f, 1080f))
        val straddlingEdge = WindowPosition((-180).dp, 64.dp)
        assertEquals(
            straddlingEdge,
            clampWindowPositionToScreens(straddlingEdge, DpSize(1440.dp, 920.dp), primaryOnly),
        )

        val unpluggedPos = WindowPosition((-3400).dp, (-1200).dp)
        val clamped = clampWindowPositionToScreens(unpluggedPos, DpSize(1440.dp, 920.dp), primaryOnly)
        assertEquals(WindowPosition(0.dp, 0.dp), clamped)
    }
}
