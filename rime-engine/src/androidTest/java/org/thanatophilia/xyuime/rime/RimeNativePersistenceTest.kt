package org.thanatophilia.xyuime.rime

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Invoked explicitly in four fresh instrumentation processes by d6-native-persistence.sh.
 * Excluded (not skipped) from the ordinary rime-engine suite. Never uses the app's real rime/.
 */
@RunWith(AndroidJUnit4::class)
class RimeNativePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.filesDir, "d6-native-persistence")
    private val evidence get() = File(context.filesDir, "d6-native-evidence").apply { mkdirs() }
    private val stateFile get() = File(evidence, "state.json")
    private val exportFile get() = File(root, "d6-user-dict.tsv")
    private val input = "nihaonihao"
    private val phrase = "你好你好"
    private val reading = "ni hao ni hao"

    private fun identity() = "${Process.myPid()}:${Process.getStartElapsedRealtime()}"
    private fun initialize() {
        assertFalse("Fresh init must start with a finalized native engine", RimeTestProbe.isReady())
        RimeDeployment(root).initialize("d6-persistence",
            prepare = { BundledRimeData.install(context.assets, root, "d6-persistence") },
            start = { data, user ->
                assertTrue(NativeRime.initialize(data.path, user.path,
                    File(root, "log").apply { mkdirs() }.path))
            }, stop = { NativeRime.shutdown() })
        assertTrue("Actual native init must finish before querying", RimeTestProbe.isReady())
    }

    private fun shutdown() {
        NativeRime.shutdown()
        assertFalse("Shutdown must reach librime finalize, not just reset a session", RimeTestProbe.isReady())
    }

    private fun commits(): Int {
        val result = RimeTestProbe.learnedCommits(exportFile.path, phrase, reading)
        assertFalse("Temporary full export must be removed by native RAII", exportFile.exists())
        return result
    }

    private fun learn(times: Int) {
        val session = NativeRime.createSession()
        assertTrue("Native session must be valid", session != 0L)
        try {
            repeat(times) {
                NativeRime.step(session, RimeEngine.CLEAR, 0, 128)
                input.forEach { NativeRime.step(session, RimeEngine.PROCESS_KEY, it.code, 128) }
                val output = NativeRime.step(session, RimeEngine.SNAPSHOT, 0, 128)
                val count = (output.size - 7) / 2
                val index = (0 until count).firstOrNull { output[7 + it * 2] == phrase }
                assertNotNull("Synthetic phrase must be selectable", index)
                assertEquals(reading, output[8 + index!! * 2])
                val selected = NativeRime.step(session, RimeEngine.SELECT_CANDIDATE, index, 128)
                assertEquals(phrase, selected[1])
                assertEquals("", NativeRime.step(session, RimeEngine.SNAPSHOT, 0, 128)[1])
            }
        } finally { NativeRime.destroySession(session) }
    }

    private fun assertCandidateWithoutLearning() {
        val session = NativeRime.createSession()
        assertTrue(session != 0L)
        try {
            input.forEach { NativeRime.step(session, RimeEngine.PROCESS_KEY, it.code, 128) }
            val output = NativeRime.step(session, RimeEngine.SNAPSHOT, 0, 128)
            val index = (0 until (output.size - 7) / 2).firstOrNull { output[7 + it * 2] == phrase }
            assertNotNull("Cold engine must offer the learned phrase", index)
            assertEquals(reading, output[8 + index!! * 2])
            assertEquals("Candidate observation must not commit or learn", "", output[1])
        } finally { NativeRime.destroySession(session) }
    }

    /** File reads only after the actual native engine is finalized. */
    private fun closedUserDigests(): JSONObject {
        assertFalse("Never read database files while native engine is open", RimeTestProbe.isReady())
        val user = File(root, "user")
        val result = JSONObject()
        user.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(user).invariantSeparatorsPath }
            .forEach { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val bytes = ByteArray(8192)
                    while (true) {
                        val n = stream.read(bytes)
                        if (n < 0) break
                        digest.update(bytes, 0, n)
                    }
                }
                result.put(file.relativeTo(user).invariantSeparatorsPath,
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            }
        assertTrue("Actual native user DB must exist", result.keys().asSequence().any { it.contains(".userdb/") })
        return result
    }

    private fun assertClosedFilesMatch(expected: JSONObject) {
        val actual = closedUserDigests()
        assertEquals("Closed database file set changed between processes", expected.length(), actual.length())
        expected.keys().forEach { name ->
            assertEquals("Closed database digest changed: $name", expected.getString(name), actual.getString(name))
        }
    }

    private fun record(phase: String, values: JSONObject) {
        values.put("phase", phase).put("process", identity()).put("phrase_id", "synthetic-pinyin-01")
        File(evidence, "$phase.json").writeText(values.toString(2))
    }

    private fun seed() {
        assertFalse(RimeTestProbe.isReady())
        check(root.deleteRecursively() || !root.exists())
        evidence.listFiles()?.forEach { check(it.delete()) }
        context.assets.open("rime/pinyin_simp.dict.yaml").bufferedReader().useLines { lines ->
            assertFalse("Probe phrase must not be a fixed bundled dictionary entry",
                lines.any { it.substringBefore('\t') == phrase })
        }
        initialize()
        assertEquals("Negative control: new user DB must not already contain the phrase", 0, commits())
        learn(3)
        val learned = commits()
        assertTrue("Selection must create a real user DB record, not just a synthesized candidate", learned > 0)
        shutdown()
        // This is a native finalize/reinitialize, distinct from RimeEngine.reset()/close().
        initialize()
        assertTrue("Learned record must survive native finalize", commits() >= learned)
        assertCandidateWithoutLearning()
        shutdown()
        val state = JSONObject().put("seed_process", identity()).put("minimum_commits", learned)
            .put("closed_user_digests", closedUserDigests())
        stateFile.writeText(state.toString(2))
        record("seed", JSONObject().put("negative_control_commits", 0).put("learned_commits", learned)
            .put("native_finalize_reinitialize", "PASS").put("user_digests", state.getJSONObject("closed_user_digests")))
    }

    private fun verifyCold() {
        val state = JSONObject(stateFile.readText())
        assertNotEquals("Must be a fresh OS process, not a new session", state.getString("seed_process"), identity())
        assertClosedFilesMatch(state.getJSONObject("closed_user_digests"))
        initialize()
        val learned = commits()
        assertTrue("Cold-process user record must persist", learned >= state.getInt("minimum_commits"))
        assertCandidateWithoutLearning()
        shutdown()
        state.put("verify_process", identity()).put("closed_user_digests", closedUserDigests())
        stateFile.writeText(state.toString(2))
        record("verify", JSONObject().put("user_record_commits", learned)
            .put("new_process", "PASS").put("candidate_reading", "PASS")
            .put("user_digests", state.getJSONObject("closed_user_digests")))
    }

    private fun interruptedDeployment() {
        val state = JSONObject(stateFile.readText())
        assertNotEquals(state.getString("verify_process"), identity())
        assertClosedFilesMatch(state.getJSONObject("closed_user_digests"))
        initialize()
        val original = commits()
        shutdown()
        val baseline = closedUserDigests()
        val deployment = RimeDeployment(root)
        deployment.recover()
        deployment.begin() // snapshot only after proven native shutdown
        initializeNativeWithoutDeployment()
        learn(2)
        val changed = commits()
        assertTrue("Fault fixture must contain real native DB changes", changed > original)
        shutdown()
        assertTrue("Failed transaction must retain the old commit decision",
            File(root, "deployment.pending/state").readText() == "pending")
        // Intentionally leave a real pending snapshot + modified closed user DB. The shell
        // force-stops this finalized process; a separate process performs recovery.
        state.put("fault_process", identity()).put("rollback_commits", original)
            .put("rollback_user_digests", baseline)
        stateFile.writeText(state.toString(2))
        record("fault", JSONObject().put("original_commits", original).put("modified_commits", changed)
            .put("native_closed_before_process_stop", true))
    }

    private fun initializeNativeWithoutDeployment() {
        assertFalse(RimeTestProbe.isReady())
        val data = File(root, "shared-d6-persistence")
        assertTrue(NativeRime.initialize(data.path, File(root, "user").path, File(root, "log").path))
        assertTrue(RimeTestProbe.isReady())
    }

    private fun recoverCold() {
        val state = JSONObject(stateFile.readText())
        assertNotEquals(state.getString("fault_process"), identity())
        assertFalse(RimeTestProbe.isReady())
        val deployment = RimeDeployment(root)
        deployment.recover()
        assertClosedFilesMatch(state.getJSONObject("rollback_user_digests"))
        deployment.recover()
        assertClosedFilesMatch(state.getJSONObject("rollback_user_digests"))
        initialize()
        val restored = commits()
        assertEquals("Rollback must restore old real user DB record count", state.getInt("rollback_commits"), restored)
        assertCandidateWithoutLearning()
        shutdown()
        record("recover", JSONObject().put("restored_commits", restored)
            .put("recovery_repeated", 2).put("closed_digest_restore", "PASS")
            .put("candidate_reading", "PASS").put("user_digests", closedUserDigests()))
    }

    @Test fun isolatedNativeLearningSurvivesColdProcess() {
        val phase = InstrumentationRegistry.getArguments().getString("d6Phase")
        require(phase in setOf("seed", "verify", "fault", "recover")) { "Explicit isolated phase required" }
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "D6NativeWorker") }
        try {
            executor.submit {
                try {
                    assertFalse("Test must not share a preinitialized production backend", RimeTestProbe.isReady())
                    when (phase) {
                        "seed" -> seed()
                        "verify" -> verifyCold()
                        "fault" -> interruptedDeployment()
                        "recover" -> recoverCold()
                        else -> error("Unexpected isolated phase")
                    }
                } finally {
                    shutdown()
                    check(!exportFile.exists())
                }
            }.get(180, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
