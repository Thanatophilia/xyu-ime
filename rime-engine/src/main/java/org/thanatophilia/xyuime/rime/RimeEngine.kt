package org.thanatophilia.xyuime.rime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

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
        private var nativeClosed = true
        private const val DATA_VERSION = "pinyin-simp-0c6861e-2"
        const val SNAPSHOT = 0
        const val PROCESS_KEY = 1
        const val SELECT_CANDIDATE = 2
        const val CLEAR = 3
        const val COMMIT = 4
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var handle = 0L
    private var owner = -1
    private var sequence = 0L
    @Volatile private var closed = false
    private val generation = AtomicLong()

    private fun initialize() {
        if (initialized) return
        val root = File(app.filesDir, "rime")
        // A failed shutdown must never permit a later request to snapshot an open DB.
        if (!nativeClosed) {
            NativeRime.shutdown()
            nativeClosed = true
        }
        RimeDeployment(root).initialize(DATA_VERSION,
            prepare = { BundledRimeData.install(app.assets, root, DATA_VERSION) },
            start = { shared, user ->
                val log = File(root, "log").apply { check(isDirectory || mkdirs()) }
                nativeClosed = false
                check(NativeRime.initialize(shared.path, user.path, log.path))
            },
            stop = {
                if (!nativeClosed) {
                    NativeRime.shutdown()
                    nativeClosed = true
                }
            })
        initialized = true
    }

    /**
     * Main-thread callback; reset/close cancel queued results. Caller must also reject old
     * inputSessionId/sequence and candidate snapshots when its editor/composition changes.
     * key is an X11/Rime keysym for PROCESS_KEY, a zero-based global index for SELECT_CANDIDATE.
     * SNAPSHOT returns the first limit global candidates without changing the selection.
     * isLastPage means this returned prefix exhausted the iterator (not menu.page_no).
     * COMMIT commits Rime's composition; raw-pinyin Return uses keysym 0xff0d instead.
     * cursor is a UTF-16 offset; commitText is drained exactly once by every request.
     */
    fun request(sessionId: Int, operation: Int = 0, key: Int = 0, limit: Int = 64,
                callback: (RimeOutput) -> Unit) {
        if (closed) return
        val epoch = generation.get()
        worker.execute {
            if (closed || generation.get() != epoch) return@execute
            val serial = ++sequence
            val result = try {
                initialize()
                if (owner != sessionId || handle == 0L) {
                    destroySession()
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
            main.post { if (!closed && generation.get() == epoch) callback(result) }
        }
    }

    private fun destroySession() {
        val previous = handle
        handle = 0L
        owner = -1
        if (previous != 0L) NativeRime.destroySession(previous)
    }

    fun reset() {
        generation.incrementAndGet()
        worker.execute {
            try { destroySession() } catch (_: Exception) {
                // The local handle is already invalidated. Never log input or crash the worker.
            } catch (_: LinkageError) {
                // A missing native library must not break closing the frontend.
            }
        }
    }

    fun close() { closed = true; reset() }
}
