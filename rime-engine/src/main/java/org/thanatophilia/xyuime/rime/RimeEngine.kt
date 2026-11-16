package org.thanatophilia.xyuime.rime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

internal object NativeRime {
    init { System.loadLibrary("xyu_rime") }
    external fun initialize(shared: String, user: String, log: String): Boolean
    external fun createSession(): Long
    external fun step(session: Long, operation: Int, key: Int, limit: Int): Array<String>
    external fun destroySession(session: Long)
    external fun shutdown()
}

data class RimeOutput(
    val inputSessionId: Int, val sequence: Long, val consumed: Boolean = false,
    val commitText: String = "", val preedit: String = "", val cursor: Int = 0,
    val candidates: List<String> = emptyList(), val highlightedIndex: Int = 0,
    val page: Int = 0, val isLastPage: Boolean = true, val error: String? = null,
) {
    val hasComposition: Boolean get() = preedit.isNotEmpty()
}

/** All native calls, including deployment, run on one process-wide serial worker. */
class RimeEngine(context: Context) {
    companion object {
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "RimeWorker") }
        private var initialized = false
        private const val DATA_VERSION = "pinyin-simp-0c6861e-1"
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var handle = 0L
    private var owner = -1
    private var sequence = 0L
    @Volatile private var closed = false

    private fun initialize() {
        if (initialized) return
        val root = File(app.filesDir, "rime")
        val shared = File(root, "shared").apply { mkdirs() }
        val user = File(root, "user").apply { mkdirs() }
        val log = File(root, "log").apply { mkdirs() }
        val marker = File(shared, "version")
        if (!marker.exists() || marker.readText() != DATA_VERSION) {
            // Replace only bundled files; never remove the writable user directory.
            app.assets.list("rime")!!.forEach { name ->
                val temp = File(shared, "$name.tmp")
                app.assets.open("rime/$name").use { input -> temp.outputStream().use { input.copyTo(it) } }
                check(temp.renameTo(File(shared, name)))
            }
            marker.writeText(DATA_VERSION)
        }
        check(NativeRime.initialize(shared.path, user.path, log.path))
        initialized = true
    }

    fun request(sessionId: Int, operation: Int = 0, key: Int = 0, limit: Int = 64,
                callback: (RimeOutput) -> Unit) {
        if (closed) return
        worker.execute {
            if (closed) return@execute
            val serial = ++sequence
            val result = try {
                initialize()
                if (owner != sessionId || handle == 0L) {
                    if (handle != 0L) NativeRime.destroySession(handle)
                    handle = NativeRime.createSession()
                    check(handle != 0L)
                    owner = sessionId
                }
                val values = NativeRime.step(handle, operation, key, limit.coerceIn(1, 4096))
                RimeOutput(sessionId, serial, values[0] == "1", values[1], values[2], values[3].toInt(),
                    values.drop(7), values[4].toInt(), values[5].toInt(), values[6] == "1")
            } catch (_: Exception) {
                RimeOutput(sessionId, serial, error = "中文引擎暂不可用")
            } catch (_: LinkageError) {
                RimeOutput(sessionId, serial, error = "中文引擎加载失败")
            }
            main.post { if (!closed) callback(result) }
        }
    }

    fun reset() {
        worker.execute {
            if (handle != 0L) NativeRime.destroySession(handle)
            handle = 0L; owner = -1
        }
    }

    fun close() { closed = true; reset() }
}
