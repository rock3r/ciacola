package dev.sebastiano.ciacola.review

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One agent's claim on one comment: it has the note and is acting on it.
 *
 * [claimedAt] is an ISO-8601 instant, and null for a claim written before claims carried a time.
 */
@Serializable
data class ReviewClaim(val id: String, val claimedAt: String? = null) {

    /** How long this claim has been held, or null when it predates timestamps. */
    fun age(now: Instant): Duration? =
        runCatching { Duration.between(Instant.parse(claimedAt), now) }.getOrNull()
}

/**
 * Which comments an agent has already taken responsibility for.
 *
 * Distinct from what the author has *resolved*: a claim says somebody is working on a note, a
 * resolution says the work is done. The deck needs to read claims even though it never makes
 * one, because both halves can dispatch — `:deck:reviewWait` collects from the queue and Send
 * Queued pushes from it, and without a shared idea of "taken" the same note goes to two agents
 * who then edit the same slide.
 *
 * A claim has no expiry. An agent that dies holding one leaves the comment claimed until somebody
 * says otherwise, which is why the deck shows claimed comments as **With an agent** and offers
 * **Release**: guessing a timeout would hand a note to a second agent while the first is still
 * thinking, and that is the failure claims exist to prevent.
 */
object ReviewClaims {

    const val FILE_NAME = "review-agent-taken.json"

    @Serializable
    internal data class State(
        val claims: List<ReviewClaim> = emptyList(),
        /** The shape written before claims carried times. Read, never written. */
        val takenIds: List<String> = emptyList(),
    )

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Reads the claims, treating an unreadable sidecar as empty.
     *
     * Unlike the queue, losing this file costs a repeat rather than a comment, so it recovers
     * instead of failing — but it says so, because silently replaying every note ever taken
     * looks like the deck has gone mad.
     */
    fun read(file: Path, warn: (String) -> Unit = System.err::println): List<ReviewClaim> {
        val text = runCatching { ReviewQueueFile.readTextOrNull(file) }
            .getOrElse {
                warn("Could not read ${file.toAbsolutePath()}, treating it as empty: ${it.message}")
                null
            } ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val state = runCatching { lenient.decodeFromString<State>(text) }
            .getOrElse {
                warn("Ignoring unreadable claims in ${file.toAbsolutePath()}: ${it.message}")
                return emptyList()
            }
        if (state.claims.isNotEmpty()) return state.claims
        return state.takenIds.map { ReviewClaim(id = it) }
    }

    fun readIds(file: Path, warn: (String) -> Unit = System.err::println): Set<String> =
        read(file, warn).map { it.id }.toSet()

    fun write(file: Path, claims: List<ReviewClaim>) {
        val sorted = claims.distinctBy { it.id }.sortedBy { it.id }
        ReviewQueueFile.writeAtomically(file, pretty.encodeToString(State(claims = sorted)) + "\n")
    }

    /** Adds [ids] to the existing claims, stamped [now]; ids already claimed keep their time. */
    fun claiming(existing: List<ReviewClaim>, ids: Collection<String>, now: Instant): List<ReviewClaim> {
        val held = existing.map { it.id }.toSet()
        return existing + ids.filterNot { it in held }.map { ReviewClaim(it, now.toString()) }
    }

    fun releasing(existing: List<ReviewClaim>, ids: Collection<String>): List<ReviewClaim> {
        val dropped = ids.toSet()
        return existing.filterNot { it.id in dropped }
    }
}
