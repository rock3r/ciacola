package dev.sebastiano.ciacola.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewActivityNoticeTest {

    @Test
    fun commentNumbersReadAsAList() {
        assertEquals("#9", formatCommentNumbers(listOf(9)))
        assertEquals("#9 and #10", formatCommentNumbers(listOf(9, 10)))
        assertEquals("#9, #10 and #11", formatCommentNumbers(listOf(9, 10, 11)))
        assertEquals("the comment", formatCommentNumbers(emptyList()))
    }

    @Test
    fun queueingSaysWhoPicksTheCommentUp() {
        val (title, detail) = queuedNotice(listOf(9), agentConfigured = false)
        assertEquals("Queued comment #9", title)
        assertTrue(detail.contains("reviewWait"), detail)
        // Nothing to push to, so do not offer a Send button that is not there.
        assertTrue(!detail.contains("Send"), detail)
    }

    @Test
    fun queueingOffersThePushShortcutOnlyWhenAnAgentIsConfigured() {
        val (_, detail) = queuedNotice(listOf(9), agentConfigured = true)
        assertTrue(detail.contains("Send"), detail)
    }

    @Test
    fun aFailedPushWithNoAgentInstalledStillReadsAsWaiting() {
        val (title, detail) = nothingToPushToNotice(listOf(4))
        assertTrue(title.contains("still queued"), title)
        assertTrue(detail.contains("reviewWait"), detail)
    }

    @Test
    fun aRealFailureCarriesItsReasonAndNeverAnEmptyBody() {
        assertEquals("agentapi exited with 1", deliveryFailedNotice(listOf(2), "agentapi exited with 1").second)
        assertTrue(deliveryFailedNotice(listOf(2), "").second.isNotBlank())
    }

    @Test
    fun pluralsAgreeWithTheNumberOfComments() {
        assertTrue(queuedNotice(listOf(1), agentConfigured = false).first.contains("comment #1"))
        assertTrue(queuedNotice(listOf(1, 2), agentConfigured = false).first.contains("comments #1 and #2"))
    }
}
