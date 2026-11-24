package dev.sebastiano.ciacola.review

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which comments an agent has already taken responsibility for.
 *
 * Distinct from what the author has *resolved*: a claim says somebody is working on a note, a
 * resolution says the work is done. The deck needs to read claims even though it never makes
 * one, because both halves can dispatch — `:deck:reviewWait` collects from the queue and Send
 * Queued pushes from it, and without a shared idea of "taken" the same note goes to two agents
 * who then edit the same slide.
 */
object ReviewClaims {

    const val FILE_NAME = "review-agent-taken.json"

    @Serializable
    internal data class State(val takenIds: List<String> = emptyList())

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Reads the claims, treating an unreadable sidecar as empty.
     *
     * Unlike the queue, losing this file costs a repeat rather than a comment, so it recovers
     * instead of failing — but it says so, because silently replaying every note ever taken
     * looks like the deck has gone mad.
     */
    fun read(file: Path, warn: (String) -> Unit = System.err::println): Set<String> {
        val text = runCatching { ReviewQueueFile.readTextOrNull(file) }
            .getOrElse {
                warn("Could not read ${file.toAbsolutePath()}, treating it as empty: ${it.message}")
                null
            } ?: return emptySet()
        if (text.isBlank()) return emptySet()
        return runCatching { lenient.decodeFromString<State>(text).takenIds.toSet() }
            .getOrElse {
                warn("Ignoring unreadable claims in ${file.toAbsolutePath()}: ${it.message}")
                emptySet()
            }
    }

    fun write(file: Path, ids: Set<String>) {
        ReviewQueueFile.writeAtomically(file, pretty.encodeToString(State(ids.toList().sorted())) + "\n")
    }
}
