package dev.sebastiano.ciacola.inspector.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.DocumentIssue
import dev.sebastiano.ciacola.document.IssueSeverity
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InvalidJsonBannerTest {

    private val errors = listOf(
        DocumentIssue(
            code = "JSON_SYNTAX",
            severity = IssueSeverity.LoadError,
            address = "slides[2].nodes[0]",
            message = "Expected ',' or '}' after property value",
            line = 42,
            column = 17,
        ),
        DocumentIssue(
            code = "UNKNOWN_KEY",
            severity = IssueSeverity.LoadError,
            address = "slides[3].frame",
            message = "Unknown key 'heigth'",
        ),
    )

    @Test
    fun agentReportListsEveryErrorWithItsExactLocation() {
        val report = formatInvalidJsonAgentReport("/decks/talk.deck.json", errors)
        assertTrue("/decks/talk.deck.json" in report)
        assertTrue("Line 42, column 17 — JSON_SYNTAX: Expected ',' or '}' after property value (at slides[2].nodes[0])" in report, report)
        assertTrue("slides[3].frame — UNKNOWN_KEY: Unknown key 'heigth'" in report, report)
        assertTrue("these 2 errors" in report)
        assertTrue("reloads it automatically" in report)
        assertEquals("Line 42, column 17", invalidJsonLocation(errors[0]))
        assertEquals("slides[3].frame", invalidJsonLocation(errors[1]))
        assertEquals("Unknown location", invalidJsonLocation(null))
    }

    @Test
    fun bannerShowsLocationAndCopiesFixContextForAgent() = runComposeUiTest {
        val copied = mutableListOf<String>()
        var retries = 0
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.width(1280.dp)) {
                    DocumentInvalidJsonBanner(
                        filePath = "/decks/talk.deck.json",
                        errors = errors,
                        isBusy = false,
                        onRetry = { retries++ },
                        onOverwrite = {},
                        onOpenRecovery = {},
                        onCopyText = { copied += it; true },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(InvalidJsonBannerTestTags.LOCATION, useUnmergedTree = true).assertTextContains("Line 42, column 17")
        onNodeWithTag(InvalidJsonBannerTestTags.MESSAGE, useUnmergedTree = true).assertTextContains("Expected ',' or '}'", substring = true)
        onNodeWithTag(InvalidJsonBannerTestTags.COPIED, useUnmergedTree = true).assertDoesNotExist()

        onNodeWithTag(InvalidJsonBannerTestTags.BTN_COPY_FOR_AGENT, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, copied.size)
        assertEquals(formatInvalidJsonAgentReport("/decks/talk.deck.json", errors), copied.single())
        onNodeWithTag(InvalidJsonBannerTestTags.COPIED, useUnmergedTree = true).assertExists()

        onNodeWithTag(InvalidJsonBannerTestTags.BTN_RETRY, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, retries)
        onNodeWithTag(SyncUiTestTags.BTN_OVERWRITE_INVALID, useUnmergedTree = true).assertExists()
    }
}
