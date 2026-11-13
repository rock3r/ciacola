package dev.bnorm.storyboard.easel.export

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ExportProgressPopupTest {

    @Test
    fun exportingPopupShowsProgressSemanticsAndCancelButtonInvokesOnCancel() = runComposeUiTest {
        var cancelClicks = 0
        setContent {
            MaterialTheme(colors = darkColors()) {
                ExportProgressPopup(
                    status = ExportStatus.Exporting(0.5f, "Rendering MP4 (120 / 240 frames)\u2026"),
                    onCancel = { cancelClicks++ },
                )
            }
        }

        onNodeWithTag("export-progress-message")
            .assertIsDisplayed()
            .assertTextEquals("Rendering MP4 (120 / 240 frames)\u2026")

        onNodeWithTag("export-progress-bar")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.5f, 0f..1f),
                )
            )

        onNodeWithTag("export-cancel-button")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, cancelClicks)
    }

    @Test
    fun escapeKeyCancelsRunningExportPopup() = runComposeUiTest {
        var cancelClicks = 0
        setContent {
            MaterialTheme(colors = lightColors()) {
                ExportProgressPopup(
                    status = ExportStatus.Exporting(0.25f, "Generating PDF (2 / 8)\u2026"),
                    onCancel = { cancelClicks++ },
                )
            }
        }

        onNodeWithTag("export-progress-popup").assertIsDisplayed()
        onNodeWithTag("export-progress-popup").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()

        assertEquals(1, cancelClicks)
    }

    @Test
    fun failedPopupDismissesOnClick() = runComposeUiTest {
        var dismissClicks = 0
        var activeStatus by mutableStateOf<ExportStatus?>(
            ExportStatus.Failed(FFMPEG_MISSING_MESSAGE)
        )
        setContent {
            MaterialTheme(colors = lightColors()) {
                activeStatus?.let { status ->
                    ExportProgressPopup(
                        status = status,
                        onDismiss = {
                            dismissClicks++
                            activeStatus = null
                        },
                    )
                }
            }
        }

        onNodeWithTag("export-progress-message")
            .assertIsDisplayed()
            .assertTextEquals(FFMPEG_MISSING_MESSAGE)

        onNodeWithTag("export-dismiss-button")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, dismissClicks)
    }
}
