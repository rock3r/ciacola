package dev.sebastiano.ciacola.timeline

/**
 * Decoupled agent-bake dispatch contract so `:deck-timeline` can trigger Kotlin source baking
 * without depending on `:deck-review`.
 */
fun interface AgentBakeDispatcher {
    suspend fun dispatchBake(summary: String): Result<Unit>
}
