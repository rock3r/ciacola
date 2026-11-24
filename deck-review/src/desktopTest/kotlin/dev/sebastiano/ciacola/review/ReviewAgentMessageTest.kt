package dev.sebastiano.ciacola.review

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class ReviewAgentMessageTest {

    @Test
    fun formatAgentMessageTargetsDeckJsonAndLintDeckWithMetadata() {
        val tempRoot = Files.createTempDirectory("review-agent-msg-test")
        try {
            val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
            val deckFile = tempRoot.resolve("talks/my-talk.deck.json").also {
                Files.createDirectories(it.parent)
                Files.writeString(it, "{}")
            }.toAbsolutePath().toString()
            val copied = mutableListOf<String>()
            val store = ReviewCommentStore(
                storyboardDir = storyboardDir,
                workspaceRoot = tempRoot,
                defaultDeckId = "my-talk",
                defaultDeckFilePath = deckFile,
                clipboardWriter = { copied += it },
            )
            val comment = SlideReviewComment(
                id = "rc-meta-1",
                sceneIndex = 2,
                frameIndex = 1,
                sceneCount = 10,
                frameCount = 3,
                sceneTitle = "Shader Basics",
                stageX = 960f,
                stageY = 540f,
                target = ReviewTargetHit(
                    kind = "Text",
                    label = "Heading",
                    textSnippet = "SkSL Fragment Shaders",
                    nodeId = "s03:heading",
                ),
                note = "Increase contrast on light theme",
                status = ReviewCommentStatus.Queued,
                screenshotPath = "/tmp/captures/rc-meta-1.png",
                createdAt = "2026-11-21T10:00:00Z",
                deckId = "my-talk",
                deckFilePath = deckFile,
                slideId = "s03",
                nodeId = "s03:heading",
            )

            val prompt = store.copyPromptToClipboard(listOf(comment))
            assertEquals(listOf(prompt), copied)
            assertFalse(prompt.contains("Apply the requested Kotlin changes"), prompt)
            assertFalse(prompt.contains("Trigger Compose Hot Reload"), prompt)
            assertTrue(prompt.contains("Deck ID: `my-talk`"), prompt)
            assertTrue(prompt.contains("Deck file: `$deckFile`"), prompt)
            assertTrue(prompt.contains("Slide 3/10 (slideId=s03) \"Shader Basics\", Beat 2/3 (beatIndex=1)"), prompt)
            assertTrue(prompt.contains("(nodeId=s03:heading)"), prompt)
            assertTrue(prompt.contains("Annotated slide PNG: `/tmp/captures/rc-meta-1.png`"), prompt)
            assertTrue(prompt.contains("DeckFileSync"), prompt)
            assertTrue(prompt.contains("Edit `$deckFile` directly"), prompt)
            assertTrue(prompt.contains("./gradlew :deck:lintDeck -Pciacola.deck=$deckFile"), prompt)
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun formatAgentMessageShellQuotesPathsWithSpacesAndFallsBackToResourcePath() {
        val tempRoot = Files.createTempDirectory("review-agent-quote-test")
        try {
            val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
            val store = ReviewCommentStore(
                storyboardDir = storyboardDir,
                workspaceRoot = tempRoot,
                defaultDeckId = "foundation",
            )
            val spaceyFile = tempRoot.resolve("my talks/deck's $(id).deck.json").also {
                Files.createDirectories(it.parent)
                Files.writeString(it, "{}")
            }.toAbsolutePath().toString()
            val comment = SlideReviewComment(
                id = "rc-quote-1",
                sceneIndex = 0,
                frameIndex = 0,
                sceneCount = 2,
                frameCount = 1,
                stageX = 100f,
                stageY = 100f,
                target = ReviewTargetHit(kind = "Text", label = "Title"),
                note = "Fix spacing",
                createdAt = "2026-11-21T10:00:00Z",
                deckId = "custom",
                deckFilePath = spaceyFile,
            )
            val quotedMsg = store.formatAgentMessage(listOf(comment))
            assertTrue(quotedMsg.contains("Edit `$spaceyFile` directly"), quotedMsg)
            assertTrue(
                quotedMsg.contains("-Pciacola.deck='${spaceyFile.replace("'", "'\"'\"'")}'"),
                "Expected shell-quoted deck path, got:\n$quotedMsg",
            )

            val resourceOnlyComment = comment.copy(deckId = "foundation", deckFilePath = null)
            val fallbackMsg = store.formatAgentMessage(listOf(resourceOnlyComment))
            assertTrue(
                fallbackMsg.contains("Edit `deck/src/desktopMain/resources/decks/foundation.deck.json` directly"),
                fallbackMsg,
            )
            assertTrue(fallbackMsg.contains("-Pciacola.deck=foundation"), fallbackMsg)

            val nonExistentResourcePathComment = comment.copy(
                deckId = "foundation",
                deckFilePath = "decks/foundation.deck.json",
            )
            val missingFileFallbackMsg = store.formatAgentMessage(listOf(nonExistentResourcePathComment))
            assertFalse(missingFileFallbackMsg.contains("Deck file: `decks/foundation.deck.json`"), missingFileFallbackMsg)
            assertTrue(
                missingFileFallbackMsg.contains("Edit `deck/src/desktopMain/resources/decks/foundation.deck.json` directly"),
                missingFileFallbackMsg,
            )
            assertTrue(missingFileFallbackMsg.contains("-Pciacola.deck=foundation"), missingFileFallbackMsg)
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun effectiveAgentApiBinIgnoresUntrustedConfigPathsAndHonorsTrustedOverrides() {
        val tempRoot = Files.createTempDirectory("review-agent-bin-sec-test")
        try {
            val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
            val store = ReviewCommentStore(
                storyboardDir = storyboardDir,
                workspaceRoot = tempRoot,
                envProvider = { null },
                systemPropertyProvider = { null },
            )
            store.saveConfig(ReviewConfig(conversationId = "conv-1", agentApiBin = "/tmp/evil-attacker-script.sh"))
            val resolved = store.effectiveAgentApiBin()
            assertFalse(
                resolved.contains("evil-attacker-script.sh"),
                "Untrusted review-config.json agentApiBin must be ignored, got: $resolved",
            )

            val propStore = ReviewCommentStore(
                storyboardDir = storyboardDir,
                workspaceRoot = tempRoot,
                envProvider = { null },
                systemPropertyProvider = { key -> if (key == "ciacola.agentapi.bin") "/opt/trusted/agentapi" else null },
            )
            assertEquals("/opt/trusted/agentapi", propStore.effectiveAgentApiBin())
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun slideReviewCommentJsonRoundTripPreservesOptionalMetadataAndLegacyPayloads() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val legacyJson = """
            {
              "id": "rc-legacy",
              "sceneIndex": 0,
              "frameIndex": 0,
              "sceneCount": 1,
              "frameCount": 1,
              "stageX": 100.0,
              "stageY": 200.0,
              "target": { "kind": "Text", "label": "Title" },
              "note": "Legacy note",
              "createdAt": "2026-11-21T00:00:00Z"
            }
        """.trimIndent()
        val decodedLegacy = json.decodeFromString<SlideReviewComment>(legacyJson)
        assertEquals(null, decodedLegacy.deckId)
        assertEquals(null, decodedLegacy.deckFilePath)
        assertEquals(null, decodedLegacy.slideId)
        assertEquals(null, decodedLegacy.nodeId)

        val tempRoot = Files.createTempDirectory("review-defaults-test")
        try {
            val store = ReviewCommentStore(
                storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard")),
                workspaceRoot = tempRoot,
                defaultDeckId = "foundation",
                defaultDeckFilePath = "/tmp/foundation.deck.json",
            )
            val saved = store.submitComment(
                comment = decodedLegacy.copy(
                    target = decodedLegacy.target.copy(nodeId = "s01:title"),
                    slideId = "s01",
                ),
                dispatchImmediate = false,
            )
            assertNotNull(saved)
            assertEquals("foundation", saved.deckId)
            assertEquals("/tmp/foundation.deck.json", saved.deckFilePath)
            assertEquals("s01", saved.slideId)
            assertEquals("s01:title", saved.nodeId)
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun formatAgentMessageListsAndLintsAllDistinctDeckFilesAcrossMultiDeckItems() {
        val tempRoot = Files.createTempDirectory("review-agent-multideck-test")
        try {
            val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
            val fileOne = tempRoot.resolve("talks/deck-one.deck.json").also {
                Files.createDirectories(it.parent)
                Files.writeString(it, "{}")
            }.toAbsolutePath().toString()
            val fileTwo = tempRoot.resolve("talks/deck-two.deck.json").also {
                Files.createDirectories(it.parent)
                Files.writeString(it, "{}")
            }.toAbsolutePath().toString()
            val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)
            val base = SlideReviewComment(
                id = "rc-1",
                sceneIndex = 0,
                frameIndex = 0,
                sceneCount = 2,
                frameCount = 1,
                stageX = 100f,
                stageY = 100f,
                target = ReviewTargetHit(kind = "Text", label = "Title"),
                note = "Fix deck one",
                createdAt = "2026-11-21T10:00:00Z",
                deckId = "deck-one",
                deckFilePath = fileOne,
            )
            val second = base.copy(
                id = "rc-2",
                note = "Fix deck two",
                deckId = "deck-two",
                deckFilePath = fileTwo,
            )

            val prompt = store.formatAgentMessage(listOf(base, second))
            assertTrue(prompt.contains("`$fileOne`"), prompt)
            assertTrue(prompt.contains("`$fileTwo`"), prompt)
            assertTrue(prompt.contains("-Pciacola.deck=$fileOne"), prompt)
            assertTrue(prompt.contains("-Pciacola.deck=$fileTwo"), prompt)
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}
