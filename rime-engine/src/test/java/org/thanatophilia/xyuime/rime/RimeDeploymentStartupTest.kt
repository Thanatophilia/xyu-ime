package org.thanatophilia.xyuime.rime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Ordinary-file transaction tests; these are NOT native DB or killed-process evidence. */
class RimeDeploymentStartupTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("d6-startup-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun put(root: File, path: String, value: String) {
        File(root, path).apply { parentFile!!.mkdirs(); writeText(value) }
    }
    private fun seed(root: File) {
        put(root, "version", "old")
        put(root, "shared-old/schema", "old schema")
        put(root, "user/sentinel", "synthetic-user-v1")
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid version must be rejected") }
        catch (_: IllegalStateException) { }
    }

    @Test fun pendingTransactionIsRecoveredBeforeNewStart() = fixture { root ->
        seed(root)
        RimeDeployment(root).begin()
        put(root, "user/sentinel", "partial mutation")
        put(root, "user/partial", "must disappear")
        put(root, "version", "uncommitted")
        var calls = 0
        RimeDeployment(root).initialize("next", {
            File(root, "shared-next").apply { mkdirs() }
        }, { _, user ->
            calls++
            assertEquals("synthetic-user-v1", File(user, "sentinel").readText())
            assertFalse(File(user, "partial").exists())
            assertEquals("old", File(root, "version").readText())
        }, {})
        assertEquals(1, calls)
        assertEquals("next", File(root, "version").readText())
        repeat(3) { RimeDeployment(root).recover() }
        assertEquals("synthetic-user-v1", File(root, "user/sentinel").readText())
    }

    @Test fun committedSnapshotIsCollectedWithoutRollingBackLearnedFiles() = fixture { root ->
        seed(root)
        val deployment = RimeDeployment(root)
        deployment.begin()
        put(root, "shared-next/schema", "new schema")
        put(root, "user/sentinel", "synthetic-user-v2")
        deployment.commit("next")
        // Re-enter initialize directly: it must collect the old committed transaction first.
        RimeDeployment(root).initialize("next", { File(root, "shared-next") }, { _, user ->
            assertEquals("synthetic-user-v2", File(user, "sentinel").readText())
        }, {})
        repeat(3) { RimeDeployment(root).recover() }
        assertEquals("next", File(root, "version").readText())
        assertEquals("synthetic-user-v2", File(root, "user/sentinel").readText())
        assertFalse(File(root, "deployment.pending").exists())
    }

    @Test fun interruptedPreparationAndVersionTempAreCleanedBeforeStart() = fixture { root ->
        seed(root)
        put(root, "deployment.preparing/user/sentinel", "half snapshot")
        put(root, "version.tmp", "half version")
        RimeDeployment(root).initialize("old", { File(root, "shared-old") }, { _, user ->
            assertFalse(File(root, "version.tmp").exists())
            assertEquals("synthetic-user-v1", File(user, "sentinel").readText())
        }, {})
        RimeDeployment(root).recover()
        assertFalse(File(root, "deployment.preparing").exists())
        assertFalse(File(root, "version.tmp").exists())
    }

    @Test fun invalidTargetCannotTouchAnExistingPendingTransaction() = fixture { root ->
        seed(root)
        RimeDeployment(root).begin()
        put(root, "user/sentinel", "interrupted")
        val backup = File(root, "deployment.pending/user/sentinel").readBytes()
        for (invalid in listOf("", "../escape", "a/b", "a\\b", "a\n", "版本", "a\u0000b")) {
            rejected {
                RimeDeployment(root).initialize(invalid, { error("prepare must not run") },
                    { _, _ -> error("native must not start") }, {})
            }
            assertArrayEquals(backup, File(root, "deployment.pending/user/sentinel").readBytes())
            assertEquals("interrupted", File(root, "user/sentinel").readText())
        }
        RimeDeployment(root).recover()
        assertEquals("synthetic-user-v1", File(root, "user/sentinel").readText())
    }

    @Test fun invalidCommitDoesNotPublishVersionOrCommitDecision() = fixture { root ->
        seed(root)
        val deployment = RimeDeployment(root)
        deployment.begin()
        rejected { deployment.commit("bad/version") }
        assertEquals("old", File(root, "version").readText())
        assertEquals("pending", File(root, "deployment.pending/state").readText())
        assertFalse(File(root, "version.tmp").exists())
        deployment.recover()
        assertEquals("synthetic-user-v1", File(root, "user/sentinel").readText())
    }
}
