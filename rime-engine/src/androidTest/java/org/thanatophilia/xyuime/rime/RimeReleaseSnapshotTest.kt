package org.thanatophilia.xyuime.rime

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit G1 synthetic calibration, not a release capture or ordinary engine test. */
@RunWith(AndroidJUnit4::class)
class RimeReleaseSnapshotTest {
    @Test fun calibrateClosedCopyObserver() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("d6Phase") == "calibrate") { "D6_CALIBRATION_EXPLICIT_PHASE_REQUIRED" }
        val run = args.getString("d6Run") ?: error("D6_RUN_REQUIRED")
        check(D6RimeTestProbe.safeCaseId(run)) { "D6_INVALID_RUN" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = D6RimeTestProbe.testFiles(context)
        check(Build.VERSION.SDK_INT == 36 && Build.SUPPORTED_ABIS.firstOrNull() == "x86_64" &&
            D6RimeTestProbe.isQemu()) { "D6_API36_X86_64_QEMU_REQUIRED" }
        val evidence = directory(File(files, "d6-release-snapshot-evidence"))
        val reportPath = File(evidence, "calibrate.json").toPath()
        check(Files.notExists(reportPath, NOFOLLOW_LINKS)) { "D6_STALE_CALIBRATION_REPORT" }
        val report = JSONObject().put("run", run).put("phase", "calibrate")
            .put("fixture_id", "synthetic-pinyin-01").put("sdk", 36).put("abi", "x86_64")
            .put("process", "${Process.myPid()}:${SystemClock.elapsedRealtime()}")
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "D6SnapshotCalibration") }
        var failure: Exception? = null
        try {
            worker.submit { calibrate(context, files, run, report) }.get(180, TimeUnit.SECONDS)
        } catch (e: Exception) {
            failure = e
        } finally {
            worker.shutdownNow()
        }
        // Only whitelisted fields leave the test. Do not serialize exceptions or native paths.
        // On failure the worker may still be unwinding; don't read its partial result object.
        val result = if (failure == null) report.put("result", "PASS") else JSONObject()
            .put("run", run).put("phase", "calibrate").put("result", "FAIL")
            .put("error", if (failure is java.util.concurrent.TimeoutException) "TIMEOUT" else "CALIBRATION_FAILED")
        Files.write(reportPath, result.toString(2).toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        check(failure == null) { "D6_SNAPSHOT_CALIBRATION_FAILED" }
    }

    private fun directory(dir: File): File {
        if (!Files.exists(dir.toPath(), NOFOLLOW_LINKS)) check(dir.mkdir())
        check(Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS))
        return dir
    }

    private fun freshDirectory(dir: File): File {
        check(dir.mkdir()) { "D6_CALIBRATION_REQUIRES_FRESH_ROOT" }
        return dir
    }

    private fun calibrate(context: Context, files: File, run: String, report: JSONObject) {
        // This root is a synthetic source, structurally outside the observer's allowlist.
        val source = freshDirectory(File(directory(File(files, "d6-release-calibration")), run))
        val user = freshDirectory(File(source, "user"))
        val log = freshDirectory(File(source, "log"))
        val snapshotRoot = directory(File(files, "d6-release-snapshots"))
        // Keep case IDs bounded and reserve a calibration-only prefix for the host.
        val prefix = "cal-" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(run.toByteArray(Charsets.US_ASCII)).take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        val phrase = "你好你好"
        check(!RimeTestProbe.isReady()) { "D6_SHARED_BACKEND" }
        context.assets.open("rime/pinyin_simp.dict.yaml").bufferedReader().useLines { lines ->
            check(lines.none { it.substringBefore('\t') == phrase }) { "D6_FIXED_PHRASE_IN_BASE_DICT" }
        }
        val data = BundledRimeData.install(context.assets, source, "d6-snapshot-calibration")
        val liveDb = File(user, "pinyin_simp.userdb")
        fun seed(commits: Int) {
            check(!RimeTestProbe.isReady())
            try {
                check(NativeRime.initialize(data.path, user.path, log.path))
                val session = NativeRime.createSession()
                check(session != 0L)
                try {
                    repeat(commits) {
                        // Native learning is allowed only in this synthetic source fixture.
                        "nihaonihao".forEach { NativeRime.step(session, RimeEngine.PROCESS_KEY, it.code, 128) }
                        val candidates = NativeRime.step(session, RimeEngine.SNAPSHOT, 0, 128)
                        val index = (0 until (candidates.size - 7) / 2).firstOrNull {
                            candidates[7 + 2 * it] == phrase && candidates[8 + 2 * it] == D6RimeTestProbe.READING
                        } ?: error("D6_CALIBRATION_CANDIDATE_MISSING")
                        val result = NativeRime.step(session, RimeEngine.SELECT_CANDIDATE, index, 128)
                        check(result[1] == phrase && result[2].isEmpty()) { "D6_CALIBRATION_COMMIT_FAILED" }
                        val after = NativeRime.step(session, RimeEngine.SNAPSHOT, 0, 128)
                        check(after[1].isEmpty() && after[2].isEmpty())
                    }
                } finally { NativeRime.destroySession(session) }
            } finally { NativeRime.shutdown() }
            check(!RimeTestProbe.isReady()) { "D6_NATIVE_NOT_FINALIZED" }
        }

        fun case(label: String) = "$prefix-$label"
        fun stage(label: String, db: Boolean = true): File {
            check(!RimeTestProbe.isReady())
            val id = case(label)
            val target = File(snapshotRoot, "$id/working/user/pinyin_simp.userdb")
            val caseRoot = freshDirectory(File(snapshotRoot, id))
            val parent = freshDirectory(File(freshDirectory(File(caseRoot, "working")), "user"))
            check(target.parentFile == parent)
            if (db) {
                freshDirectory(target)
                checkNotNull(liveDb.listFiles()).forEach { f ->
                    check(Files.isRegularFile(f.toPath(), NOFOLLOW_LINKS))
                    Files.copy(f.toPath(), File(target, f.name).toPath(), NOFOLLOW_LINKS)
                }
            }
            return target
        }
        fun read(label: String, hash: String) = D6RimeTestProbe.read(context, case(label), hash)
        fun reject(label: String, hash: String, status: String) {
            val r = read(label, hash)
            check(r.status == status && r.commits == null && r.reading == null) { "D6_REJECTION_$label" }
            report.put(label.replace('-', '_'), status)
        }
        fun controls(label: String, expectedStatus: String, expectedHash: String): Int {
            val working = stage(label)
            val observed = read(label, expectedHash)
            check(observed.status == expectedStatus)
            if (expectedStatus == "PRESENT") check(observed.reading == D6RimeTestProbe.READING)
            else check(observed.reading == null)
            // Also check the post-open copy, without accepting its new hash as source evidence.
            // This exposes target creation by initial observation, not just instability on replay.
            check(read(label, D6RimeTestProbe.directoryDigest(working)) == observed)
            stage("$label-replay")
            check(read("$label-replay", expectedHash) == observed)
            check(D6RimeTestProbe.directoryDigest(liveDb) == expectedHash)
            return checkNotNull(observed.commits)
        }

        seed(0)
        val zeroHash = D6RimeTestProbe.directoryDigest(liveDb)
        val zero = controls("negative", "ABSENT", zeroHash)
        check(zero == 0)
        report.put("negative_control_commits", zero).put("negative_db_sha256", zeroHash)
        seed(3)
        val positiveHash = D6RimeTestProbe.directoryDigest(liveDb)
        val positive = controls("positive", "PRESENT", positiveHash)
        check(positive >= 1 && zeroHash != positiveHash)
        report.put("positive_control_commits", positive).put("positive_db_sha256", positiveHash)
            .put("reading", D6RimeTestProbe.READING).put("synthetic_selections", 3)

        val missing = stage("missing-db", db = false)
        reject("missing-db", zeroHash, "MISSING_DB")
        check(Files.notExists(missing.toPath(), NOFOLLOW_LINKS)) // create_if_missing must stay off
        val symlink = stage("symlink-db", db = false)
        Os.symlink(liveDb.path, symlink.path)
        try { reject("symlink-db", positiveHash, "INVALID_COPY") }
        finally { check(symlink.delete()) }
        stage("wrong-digest")
        reject("wrong-digest", zeroHash, "COPY_MISMATCH")
        fun manifest(db: File) = File(db, File(db, "CURRENT").readText().trim())
        val missingManifest = stage("missing-manifest")
        check(manifest(missingManifest).delete())
        reject("missing-manifest", D6RimeTestProbe.directoryDigest(missingManifest), "INCOMPLETE_DB")
        val truncated = stage("truncated-manifest")
        RandomAccessFile(manifest(truncated), "rw").use {
            check(it.length() > 16)
            it.setLength(it.length() - 1) // torn last record, not a blanket empty-file check
        }
        reject("truncated-manifest", D6RimeTestProbe.directoryDigest(truncated), "CORRUPT_DB")
        val noWal = stage("missing-wal")
        val wals = checkNotNull(noWal.listFiles()).filter { it.name.endsWith(".log") }
        check(wals.isNotEmpty() && wals.all { it.delete() })
        reject("missing-wal", D6RimeTestProbe.directoryDigest(noWal), "INCOMPLETE_DB")
        check(D6RimeTestProbe.directoryDigest(liveDb) == positiveHash)
        report.put("replay_and_source_immutability", "PASS").put("native_finalized_before_copy", true)
            .put("scope", "synthetic calibration only; work-copy WAL recovery is not source flush evidence")
    }
}
