package dev.sebastiano.ciacola.review

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissingAgentBinaryTest {

    @Test
    fun aMissingAgentCommandIsNotTreatedAsADeliveryFailure() {
        // Exactly what ProcessBuilder throws when the configured command is not on PATH.
        val cause = IOException(
            "Cannot run program \"agentapi\" (in directory \"/repo\"): error=2, No such file or directory",
        )
        assertTrue(isAgentBinaryMissing(cause))
    }

    @Test
    fun arealDeliveryFailureStillCounts() {
        assertFalse(isAgentBinaryMissing(IllegalStateException("agentapi exited with 1: boom")))
        assertFalse(isAgentBinaryMissing(IllegalStateException("Timed out invoking agentapi after 15s")))
        assertFalse(isAgentBinaryMissing(IOException("broken pipe")))
        assertFalse(isAgentBinaryMissing(null))
    }
}
