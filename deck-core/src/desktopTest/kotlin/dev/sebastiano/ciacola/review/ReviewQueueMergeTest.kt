package dev.sebastiano.ciacola.review

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewQueueMergeTest {

    private fun comment(id: String, note: String = "note") = SlideReviewComment(
        id = id,
        sceneIndex = 0,
        frameIndex = 0,
        sceneCount = 1,
        frameCount = 1,
        sceneTitle = "Slide",
        sceneHeader = "",
        themePreset = "KeynoteLight",
        stageX = 0f,
        stageY = 0f,
        target = ReviewTargetHit(kind = "text", label = "Title"),
        note = note,
        createdAt = "2026-11-24T00:00:00Z",
    )

    @Test
    fun anotherWritersCommentSurvivesThisWritersChange() {
        // The app knew about A only; an agent added B meanwhile. Writing the app's list wholesale
        // would erase B, which is the whole bug.
        val merged = mergeOntoDisk(
            onDisk = listOf(comment("A"), comment("B")),
            believed = listOf(comment("A")),
            updated = listOf(comment("A", note = "edited")),
        )
        assertEquals(listOf("A", "B"), merged.map { it.id })
        assertEquals("edited", merged.first().note)
    }

    @Test
    fun aDeletedCommentStaysDeletedRatherThanBeingResurrected() {
        // A plain union would bring A back every time the two writers disagree.
        val merged = mergeOntoDisk(
            onDisk = listOf(comment("A"), comment("B")),
            believed = listOf(comment("A"), comment("B")),
            updated = listOf(comment("B")),
        )
        assertEquals(listOf("B"), merged.map { it.id })
    }

    @Test
    fun anotherWritersEditOfACommentThisWriterDidNotTouchIsKept() {
        val merged = mergeOntoDisk(
            onDisk = listOf(comment("A", note = "resolved by the agent")),
            believed = listOf(comment("A")),
            updated = listOf(comment("A"), comment("B")),
        )
        assertEquals(listOf("A", "B"), merged.map { it.id })
        assertEquals("resolved by the agent", merged.first().note)
    }

    @Test
    fun aNewCommentIsAppendedOnce() {
        val merged = mergeOntoDisk(
            onDisk = listOf(comment("A")),
            believed = listOf(comment("A")),
            updated = listOf(comment("A"), comment("B")),
        )
        assertEquals(listOf("A", "B"), merged.map { it.id })
    }
}
