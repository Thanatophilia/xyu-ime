package org.thanatophilia.xyuime.rime

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Only call while native databases are closed, on the process-wide engine worker.
 * The backup stays immutable until a durable commit or a completed restore.
 */
internal class RimeDeployment(
    private val root: File,
    private val syncDirectory: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    },
) {
    private val pending = File(root, "deployment.pending")
    private val preparing = File(root, "deployment.preparing")
    private val versionFile = File(root, "version")

    private fun version(): String? = if (versionFile.exists()) {
        versionFile.readText().also { check(it.matches(Regex("[A-Za-z0-9._-]+"))) }
    } else null

    private fun remove(file: File) { check(!file.exists() || file.deleteRecursively()) }

    private fun copy(source: File, target: File) {
        check(!java.nio.file.Files.isSymbolicLink(source.toPath())) { "Symlink in deployment data" }
        if (source.isDirectory) {
            check(target.isDirectory || target.mkdirs())
            checkNotNull(source.listFiles()).forEach { copy(it, File(target, it.name)) }
            syncDirectory(target)
        } else {
            check(source.isFile)
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
            }
        }
    }

    private fun atomicText(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temp.renameTo(file))
        syncDirectory(file.parentFile!!)
    }

    /** Safe to repeat after interruption, including during restoration itself. */
    fun recover() {
        check(root.isDirectory || root.mkdirs())
        if (pending.exists()) {
            if (File(pending, "state").readText() != "committed") {
                val oldVersion = File(pending, "version").takeIf { it.exists() }?.readText()
                if (oldVersion != null) check(oldVersion.matches(Regex("[A-Za-z0-9._-]+")))
                val user = File(root, "user")
                remove(user)
                File(pending, "user").takeIf { it.exists() }?.let { copy(it, user) }
                if (oldVersion != null) {
                    val shared = File(root, "shared-$oldVersion")
                    remove(shared)
                    copy(File(pending, "shared"), shared)
                    atomicText(versionFile, oldVersion)
                } else {
                    remove(versionFile)
                }
                syncDirectory(root)
                // Once restored, pending is no longer needed. Rename before deleting so a
                // crash during recursive deletion cannot leave a half-backup to restore.
                check(pending.renameTo(preparing))
                syncDirectory(root)
            } else {
                check(pending.renameTo(preparing))
                syncDirectory(root)
            }
        }
        remove(preparing)
        remove(File(root, "version.tmp"))
        syncDirectory(root)
    }

    fun begin() {
        check(!pending.exists())
        remove(preparing)
        check(preparing.mkdirs())
        File(root, "user").takeIf { it.exists() }?.let { copy(it, File(preparing, "user")) }
        version()?.let { old ->
            copy(File(root, "shared-$old"), File(preparing, "shared"))
            copy(versionFile, File(preparing, "version"))
        }
        atomicText(File(preparing, "state"), "pending")
        syncDirectory(preparing)
        check(preparing.renameTo(pending))
        syncDirectory(root)
    }

    fun commit(version: String) {
        atomicText(versionFile, version)
        atomicText(File(pending, "state"), "committed")
        // Do not recursively delete the backup while native databases are open.
        // The next cold initialization collects this committed transaction.
    }

    /** start must return only after the native deployment + usable-schema probe succeeds.
     * stop must close all native handles; if it fails we deliberately do not restore files.
     */
    fun initialize(target: String, prepare: () -> File,
                   start: (File, File) -> Unit, stop: () -> Unit) {
        recover()
        val previous = version()
        fun attempt(v: String, shared: () -> File) {
            begin()
            try {
                val data = shared()
                val user = File(root, "user").apply { check(isDirectory || mkdirs()) }
                start(data, user)
                commit(v)
            } catch (failure: Throwable) {
                try { stop() } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                    throw failure
                }
                // A rename can succeed before its directory fsync reports an error.
                // Preserve that committed decision for the next cold initialization;
                // never run the old version against potentially committed new user data.
                if (File(pending, "state").readText() == "committed") throw failure
                try { recover() } catch (restoreFailure: Throwable) {
                    failure.addSuppressed(restoreFailure)
                    throw failure
                }
                throw failure
            }
        }
        try {
            attempt(target, prepare)
        } catch (failure: Throwable) {
            // Never proceed if shutdown/restore left a pending transaction.
            if (pending.exists() || previous == null || previous == target) throw failure
            try { attempt(previous) { File(root, "shared-$previous") } }
            catch (fallbackFailure: Throwable) {
                fallbackFailure.addSuppressed(failure)
                throw fallbackFailure
            }
        }
    }
}
