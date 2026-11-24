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

class PushAvailabilityTest {

    private val present = setOf("/usr/bin/agentapi", "/opt/tools/agentapi", "/tmp/fake-agentapi.sh")
    private val isExecutable: (java.nio.file.Path) -> Boolean = { it.toString() in present }

    @Test
    fun aBareNameIsLookedUpOnPath() {
        assertTrue(isExecutableAvailable("agentapi", "/usr/local/bin:/usr/bin", isExecutable = isExecutable))
        assertTrue(!isExecutableAvailable("agentapi", "/usr/local/bin:/sbin", isExecutable = isExecutable))
    }

    @Test
    fun anExplicitPathIsCheckedDirectlyAndNotSearched() {
        assertTrue(isExecutableAvailable("/tmp/fake-agentapi.sh", null, isExecutable = isExecutable))
        assertTrue(!isExecutableAvailable("/tmp/missing.sh", "/usr/bin", isExecutable = isExecutable))
    }

    @Test
    fun nothingConfiguredIsNotSomethingToPushTo() {
        assertTrue(!isExecutableAvailable("", "/usr/bin", isExecutable = isExecutable))
        assertTrue(!isExecutableAvailable("  ", "/usr/bin", isExecutable = isExecutable))
        assertTrue(!isExecutableAvailable("agentapi", null, isExecutable = isExecutable))
        assertTrue(!isExecutableAvailable("agentapi", "", isExecutable = isExecutable))
    }
}

class RelativePushCommandTest {

    @Test
    fun aRelativeCommandResolvesAgainstTheDirectoryThePushActuallyRunsIn() {
        val workspace = java.nio.file.Path.of("/repo")
        val present = setOf("/repo/tools/agentapi")
        val isExecutable: (java.nio.file.Path) -> Boolean = { it.toString() in present }

        // The push sets ProcessBuilder.directory(workspaceRoot), so the check must use it too:
        // against the process's own directory this command looks absent and Send disappears.
        assertTrue(isExecutableAvailable("./tools/agentapi", null, workspace, isExecutable = isExecutable))
        assertTrue(!isExecutableAvailable("./tools/agentapi", null, java.nio.file.Path.of("/elsewhere"), isExecutable = isExecutable))
    }

    @Test
    fun aWindowsPathAndPathExtAreUnderstood() {
        val present = setOf("c:/tools/agentapi.exe", "c:/program files/bin/agentapi.exe")
        // This test runs on POSIX, where Path joins with "/" and names are case-sensitive, so the
        // predicate stands in for a Windows filesystem. What is under test is which candidates
        // get tried — the separator and the extension — not how the JDK spells them.
        val isExecutable: (java.nio.file.Path) -> Boolean = {
            it.toString().replace('\\', '/').lowercase() in present
        }

        // A backslash path is a path, not a bare name to hunt for on PATH.
        assertTrue(
            isExecutableAvailable("C:\\tools\\agentapi.exe", null, null, ".EXE;.CMD", ';', isExecutable),
        )
        // A bare name has to pick up the extension, or Windows never finds anything and every
        // Send disappears with no explanation.
        assertTrue(
            isExecutableAvailable("agentapi", "C:\\tools", null, ".EXE;.CMD", ';', isExecutable),
        )
        // A quoted PATH entry is ordinary on Windows and must not throw during store init.
        assertTrue(
            isExecutableAvailable("agentapi", "\"C:\\Program Files\\bin\"", null, ".EXE;.CMD", ';', isExecutable),
        )
    }

    @Test
    fun aWindowsShimTheLaunchCannotRunDoesNotCountAsAvailable() {
        // CreateProcess appends .exe to a bare name and nothing else, so ProcessBuilder("agentapi")
        // cannot start an npm-style agentapi.cmd. Counting the shim would offer Send for a command
        // every push then fails to run, and report it in red as a delivery failure.
        //
        // The extensionless entry is npm's own shell script, which sits beside the .cmd and which
        // Files.isExecutable happily accepts on Windows: trying the name as given is right on
        // POSIX and wrong here.
        val present = setOf("c:/tools/agentapi.cmd", "c:/tools/agentapi.bat", "c:/tools/agentapi")
        val isExecutable: (java.nio.file.Path) -> Boolean = {
            it.toString().replace('\\', '/').lowercase() in present
        }
        assertTrue(
            !isExecutableAvailable("agentapi", "C:\\tools", null, ".COM;.EXE;.BAT;.CMD", ';', isExecutable),
        )
    }

    @Test
    fun anUnparseablePathEntryIsSkippedRatherThanThrowing() {
        // Path.of on a malformed entry throws InvalidPathException, and this runs while the store
        // is being constructed: letting it escape would stop the deck opening at all.
        assertTrue(
            !isExecutableAvailable("agentapi", "\u0000bad", null, null) { true },
        )
    }

    @Test
    fun anAbsoluteCommandIgnoresTheWorkspaceRoot() {
        val present = setOf("/usr/local/bin/agentapi")
        val isExecutable: (java.nio.file.Path) -> Boolean = { it.toString() in present }
        assertTrue(isExecutableAvailable("/usr/local/bin/agentapi", null, java.nio.file.Path.of("/repo"), isExecutable = isExecutable))
    }
}
