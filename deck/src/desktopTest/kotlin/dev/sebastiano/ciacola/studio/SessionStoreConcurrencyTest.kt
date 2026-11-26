package dev.sebastiano.ciacola.studio

import dev.sebastiano.ciacola.presenter.PresenterController
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [StudioSessionStore] serializes concurrent `session.json` read-modify-write updates
 * across background IO savers and UI-thread writers without losing updates or corrupting JSON.
 */
class SessionStoreConcurrencyTest {

    @Test
    fun concurrentNotesFontAndInspectorWidthWritesNeverLoseUpdates() {
        val dir = Files.createTempDirectory("ciacola-session-concurrency-")
        val pool = Executors.newFixedThreadPool(2)
        try {
            var lost = 0
            val iterations = 300
            repeat(iterations) { i ->
                val barrier = CyclicBarrier(2)
                val font = (PresenterController.MIN_NOTES_FONT_SP + (i % 4))
                    .coerceAtMost(PresenterController.MAX_NOTES_FONT_SP)
                val width = MIN_STUDIO_INSPECTOR_WIDTH_DP + (i % 150)
                val a = pool.submit { barrier.await(); persistPresenterNotesFontSizeSp(dir, font) }
                val b = pool.submit { barrier.await(); persistInspectorWidthDp(dir, width) }
                a.get()
                b.get()
                if (loadPersistedPresenterNotesFontSizeSp(dir) != font || loadPersistedInspectorWidthDp(dir) != width) {
                    lost++
                }
            }
            assertEquals(0, lost, "session.json lost $lost of $iterations concurrent update pairs")
        } finally {
            pool.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }
}
