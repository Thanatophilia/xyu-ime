package org.thanatophilia.xyuime.rime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Debug only. Caller supplies a fresh, closed, exclusive working copy and an independently
 * computed digest of the unopened snapshot's target DB. Never stage by linking to the source.
 * LevelDB can recover its WAL and rewrite bookkeeping on the copy: this is not proof of source flush.
 */
internal object D6RimeTestProbe {
    const val TEST_PACKAGE = "org.thanatophilia.xyuime.rime.test"
    const val READING = "ni hao ni hao"
    private val safeCase = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val safeFile = Regex("CURRENT|LOCK|LOG(?:\\.old)?|MANIFEST-[0-9]{6,20}|[0-9]{6,20}\\.(?:log|ldb|sst|dbtmp)")
    private val sha256 = Regex("[0-9a-f]{64}")
    private const val MAX_BYTES = 256L * 1024 * 1024

    data class Result(val status: String, val commits: Int? = null, val reading: String? = null)

    internal fun safeCaseId(id: String) = safeCase.matches(id)

    internal fun testFiles(context: Context): File {
        require(context.packageName == TEST_PACKAGE && context.applicationInfo.uid == Process.myUid() &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Isolated debug test context required"
        }
        val files = context.filesDir.canonicalFile
        require(files.path == "/data/data/$TEST_PACKAGE/files" ||
            files.path == "/data/user/0/$TEST_PACKAGE/files") { "Unexpected test files directory" }
        return files
    }

    private fun regularDirectory(file: File) = try {
        OsConstants.S_ISDIR(Os.lstat(file.path).st_mode)
    } catch (_: Exception) { false }

    /** Binding format: SHA-256 of sorted ASCII `name<TAB>length<TAB>file_sha256<LF>` rows,
     * one for every direct child of pinyin_simp.userdb (not an Android data tree digest).
     * Host computes this from its immutable snapshot; this method is also used for synthetic calibration.
     */
    internal fun directoryDigest(db: File): String {
        require(regularDirectory(db))
        val files = checkNotNull(db.listFiles()).sortedBy { it.name }
        require(files.size in 1..4096)
        var total = 0L
        val manifest = MessageDigest.getInstance("SHA-256")
        for (file in files) {
            require(safeFile.matches(file.name))
            val stat = Os.lstat(file.path)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L)
            require(stat.st_size in 0..(MAX_BYTES - total))
            total += stat.st_size
            val hash = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { stream ->
                val bytes = ByteArray(8192)
                while (true) {
                    val n = stream.read(bytes)
                    if (n < 0) break
                    size += n
                    require(size <= stat.st_size)
                    hash.update(bytes, 0, n)
                }
            }
            require(size == stat.st_size)
            manifest.update("${file.name}\t$size\t${hex(hash.digest())}\n".toByteArray(Charsets.US_ASCII))
        }
        return hex(manifest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized
    fun read(context: Context, caseId: String, expectedDbSha256: String): Result {
        if (!safeCaseId(caseId) || !sha256.matches(expectedDbSha256)) return Result("INVALID_ARGUMENT")
        val files = try { testFiles(context) } catch (_: Exception) { return Result("INVALID_CONTEXT") }
        var db = files
        for (part in listOf("d6-release-snapshots", caseId, "working", "user", "pinyin_simp.userdb")) {
            db = File(db, part)
            val stat = try { Os.lstat(db.path) } catch (e: android.system.ErrnoException) {
                return Result(if (e.errno == OsConstants.ENOENT) "MISSING_DB" else "INVALID_COPY")
            }
            if (!OsConstants.S_ISDIR(stat.st_mode)) return Result("INVALID_COPY")
        }
        val digest = try { directoryDigest(db) } catch (_: Exception) { return Result("INVALID_COPY") }
        if (digest != expectedDbSha256) return Result("COPY_MISMATCH")
        return when (val n = readNative(files.path, caseId)) {
            -1 -> Result("INVALID_COPY")
            -2 -> Result("INCOMPLETE_DB")
            -3 -> Result("CORRUPT_DB")
            -4 -> Result("INVALID_USERDB")
            -5 -> Result("ABSENT", 0)
            -6 -> Result("READER_ERROR")
            else -> if (n >= 0) Result("PRESENT", n, READING) else Result("READER_ERROR")
        }
    }

    // The calibration gate uses an Android native property read, not an overridable JVM model string.
    fun isQemu(): Boolean = isQemuNative()

    init { System.loadLibrary("xyu_rime") }
    private external fun isQemuNative(): Boolean
    private external fun readNative(filesDir: String, caseId: String): Int
}
