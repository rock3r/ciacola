package dev.sebastiano.ciacola.inspector.element

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElementOverridesStoreTest {
    @Test
    fun persistsAtomicallyAndReloadsFromDisk() {
        val dir = Files.createTempDirectory("ciacola-el-persist")
        val store = ElementOverridesStore(dir)

        store.updateElement("s1:headline") {
            it.copy(text = "Live Title", textColorArgb = 0xFFFF6B5EL, fontSizeSp = 64f, fontWeight = 700)
        }
        assertTrue(Files.exists(store.overridesFile))
        assertTrue(store.data().hasOverrides)

        val reloaded = ElementOverridesStore(dir)
        val patch = reloaded.patchFor("s1:headline")
        assertEquals("Live Title", patch?.text)
        assertEquals(0xFFFF6B5EL, patch?.textColorArgb)
        assertEquals(64f, patch?.fontSizeSp)
        assertEquals(700, patch?.fontWeight)
    }

    @Test
    fun backsUpCorruptJsonAndDetectsExternalMtimeReloadAndDeletion() {
        val dir = Files.createTempDirectory("ciacola-el-corrupt")
        val file = dir.resolve("element-overrides.json")
        Files.writeString(file, "{ broken json")

        val store = ElementOverridesStore(dir)
        assertFalse(store.data().hasOverrides)
        val backup = dir.resolve("element-overrides.corrupt.json")
        assertTrue(Files.exists(backup))
        assertEquals("{ broken json", Files.readString(backup))

        // Write valid external JSON with a new size/mtime.
        Files.writeString(
            file,
            """
            {
              "elements": {
                "s3:headline": { "elementId": "s3:headline", "text": "External Edit" }
              }
            }
            """.trimIndent(),
        )
        assertTrue(store.reloadFromDiskIfChanged())
        assertEquals("External Edit", store.patchFor("s3:headline")?.text)

        // External deletion clears overrides on reload.
        Files.deleteIfExists(file)
        assertTrue(store.reloadFromDiskIfChanged())
        assertFalse(store.data().hasOverrides)
    }

    @Test
    fun undoRedoSupports64StepsGesturesAndCoalescing() {
        val dir = Files.createTempDirectory("ciacola-el-undo")
        var fakeClock = 1_000L
        val store = ElementOverridesStore(dir, clockMillis = { fakeClock })

        // 1. Gesture coalescing: 10 drag updates collapse into 1 undo entry and persist once on end.
        store.beginEditGesture()
        repeat(10) { step ->
            store.updateElement("s2:card", coalesce = true) { it.copy(offsetXDp = (step + 1) * 5f) }
        }
        assertFalse(Files.exists(store.overridesFile), "Disk write must defer until endEditGesture")
        store.endEditGesture()
        assertTrue(Files.exists(store.overridesFile))
        assertEquals(50f, store.patchFor("s2:card")?.offsetXDp)

        assertTrue(store.undo())
        assertNull(store.patchFor("s2:card"))
        assertTrue(store.redo())
        assertEquals(50f, store.patchFor("s2:card")?.offsetXDp)

        // 2. Typing coalesce window (within 400ms merges, past 400ms splits).
        fakeClock = 5_000L
        store.updateElement("s1:prose", coalesce = true, coalesceKey = "text:s1:prose") { it.copy(text = "A") }
        fakeClock = 5_100L
        store.updateElement("s1:prose", coalesce = true, coalesceKey = "text:s1:prose") { it.copy(text = "AB") }
        fakeClock = 6_000L
        store.updateElement("s1:prose", coalesce = true, coalesceKey = "text:s1:prose") { it.copy(text = "ABC") }

        assertEquals("ABC", store.patchFor("s1:prose")?.text)
        assertTrue(store.undo())
        assertEquals("AB", store.patchFor("s1:prose")?.text)
        assertTrue(store.undo())
        assertNull(store.patchFor("s1:prose"))

        // 3. 64-step bounded undo history.
        val boundedStore = ElementOverridesStore(Files.createTempDirectory("ciacola-el-cap"))
        repeat(80) { i ->
            boundedStore.updateElement("el", coalesce = false) { it.copy(offsetXDp = i.toFloat()) }
        }
        var undoCount = 0
        while (boundedStore.undo()) undoCount++
        assertEquals(ElementOverridesStore.MAX_UNDO_HISTORY, undoCount)
    }

    @Test
    fun resetAndExportKotlinSnippetProduceCleanCode() {
        val dir = Files.createTempDirectory("ciacola-el-export")
        val store = ElementOverridesStore(dir)

        store.updateElement("s5:stamp") {
            it.copy(text = "VERIFIED", cornerRadiusDp = 16f, borderWidthDp = 4f, offsetXDp = 12f)
        }
        val singleSnippet = store.exportKotlinSnippet("s5:stamp", copyToClipboard = false)
        assertTrue(singleSnippet.contains("elementId = \"s5:stamp\""))
        assertTrue(singleSnippet.contains("text = \"VERIFIED\""))
        assertTrue(singleSnippet.contains("cornerRadiusDp = 16.0f"))
        assertTrue(singleSnippet.contains("borderWidthDp = 4.0f"))
        assertTrue(singleSnippet.contains("offsetXDp = 12.0f"))

        store.resetElement("s5:stamp")
        assertFalse(store.data().hasOverrides)

        store.updateElement("s1:eyebrow") { it.copy(text = "INTRO") }
        store.updateElement("s3:card") { it.copy(paddingDp = 32f) }
        val fullSnippet = store.exportKotlinSnippet(copyToClipboard = false)
        assertTrue(fullSnippet.contains("DeckElementOverridesFile("))
        assertTrue(fullSnippet.contains("\"s1:eyebrow\""))
        assertTrue(fullSnippet.contains("\"s3:card\""))

        store.resetAll()
        assertFalse(store.data().hasOverrides)
        store.close()
    }

    @Test
    fun outOfOrderEpochWritesNeverOverwriteNewerSnapshotOnDisk() {
        val dir = Files.createTempDirectory("ciacola-el-epoch-order")
        val store = ElementOverridesStore(dir)
        try {
            val older = dev.sebastiano.ciacola.inspector.DeckElementOverridesFile(
                elements = mapOf("s1:eyebrow" to dev.sebastiano.ciacola.inspector.ElementPatch("s1:eyebrow", text = "OLDER")),
            )
            val newer = dev.sebastiano.ciacola.inspector.DeckElementOverridesFile(
                elements = mapOf("s1:eyebrow" to dev.sebastiano.ciacola.inspector.ElementPatch("s1:eyebrow", text = "NEWER")),
            )

            assertTrue(store.writeSnapshotToDisk(newer, targetEpoch = 2L))
            assertFalse(
                store.writeSnapshotToDisk(older, targetEpoch = 1L),
                "Older epoch write arriving after newer epoch must be rejected",
            )

            ElementOverridesStore(dir).use { reloaded ->
                assertEquals("NEWER", reloaded.patchFor("s1:eyebrow")?.text)
            }
            val strayTmps = Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".tmp") }.count()
            }
            assertEquals(0L, strayTmps, "No temporary .tmp files should remain in storyboardDir")
        } finally {
            store.close()
        }
    }
}
