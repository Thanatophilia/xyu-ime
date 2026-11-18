package org.thanatophilia.xyuime

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.thanatophilia.xyuime.ime.XyuImeService
import org.thanatophilia.xyuime.rime.EngineBridge
import org.thanatophilia.xyuime.rime.RimeOutput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * D2: composition vs editing and the serial engine queue. A controllable fake engine holds
 * native replies open while the user keeps typing, switches language or switches editors.
 * The real engine covers the editing-panel bridge and candidate long-delete boundary.
 */
@RunWith(AndroidJUnit4::class)
class ImePhase4CompositionEditTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = context.packageName
    private val imeId = "$pkg/org.thanatophilia.xyuime.ime.XyuImeService"

    private data class Snapshot(val text: String, val composing: String?, val actions: List<Int>)

    private fun freshTree() {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        device.waitForIdle()
    }

    private fun node(tag: String): UiObject2 {
        freshTree()
        return requireNotNull(device.waitForFreshObject(By.res(tag).pkg(pkg), 10_000)) { tag }
    }

    private fun tap(tag: String) { node(tag).click(); device.waitForIdle() }
    private fun typeLetters(text: String) { text.forEach { tap("key:$it") } }

    private fun dumpUi(prefix: String) {
        try {
            freshTree()
            val file = java.io.File(context.getExternalFilesDir(null), "$prefix.xml")
            device.dumpWindowHierarchy(file)
            device.executeShellCommand("mkdir -p /sdcard/Download/xyu-ime-review")
            device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/xyu-ime-review/$prefix.xml")
        } catch (_: Throwable) { }
    }

    /** Tap with a diagnostic UI dump when the key is missing, instead of a bare timeout. */
    private fun requireKey(tag: String): UiObject2 {
        freshTree()
        val key = device.waitForFreshObject(By.res(tag).pkg(pkg), 5_000)
        if (key == null) dumpUi("phase4-missing-$tag")
        return requireNotNull(key) { "Missing key: $tag" }
    }

    private fun awaitChineseHeader() {
        val ready = By.text(java.util.regex.Pattern.compile("絮雨 · (English|中文拼音)")).pkg(pkg)
        assertNotNull("Engine readiness must finish", device.waitForFreshObject(ready, 120_000))
        if (!device.hasObject(By.text("絮雨 · 中文拼音").pkg(pkg))) {
            tap("key:切换语言，长按切换输入法")
        }
        assertNotNull("Chinese mode not reached", device.waitForFreshObject(By.text("絮雨 · 中文拼音").pkg(pkg), 15_000))
    }

    private fun restoreIme(previous: String, hardware: String) {
        if (previous.isNotEmpty() && previous != "null" && previous.contains('/')) {
            device.executeShellCommand("ime set $previous")
        }
        if (hardware == "null" || hardware.isEmpty()) {
            device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
        } else {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $hardware")
        }
    }

    /** Recreates the IME service with the fake engine installed, on the two-field fixture. */
    private fun withFakeEngine(test: (FakeEngineBridge, Activity) -> Unit) {
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val hardware = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        var activity: Activity? = null
        try {
            device.executeShellCommand("ime disable $imeId")
            val fake = FakeEngineBridge()
            XyuImeService.engineOverride = fake
            device.executeShellCommand("ime enable $imeId")
            device.executeShellCommand("ime set $imeId")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            val host = instrumentation.startActivitySync(EditorFixtureActivity.intent(context, EditorFixtureActivity.FIXTURE_SWITCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity = host
            val fieldA = device.waitForFreshObject(By.desc("Field A").pkg(pkg), 8_000)
            requireNotNull(fieldA).click()
            device.waitForIdle()
            instrumentation.runOnMainSync {
                host.findViewById<EditText>(R.id.fixture_editor_a).let {
                    host.getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            awaitChineseHeader()
            test(fake, host)
        } finally {
            activity?.let { a -> instrumentation.runOnMainSync { a.finish() } }
            XyuImeService.engineOverride = null
            restoreIme(previous, hardware)
        }
    }

    /** Real Rime round trip inside a synthetic single-line editor with an action recorder. */
    private fun withRealChineseEditor(action: Int = EditorInfo.IME_ACTION_DONE, test: (Activity, EditText, () -> Snapshot) -> Unit) {
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val hardware = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        val received = mutableListOf<Int>()
        var activity: Activity? = null
        var field: EditText? = null
        fun snapshot(): Snapshot {
            var result: Snapshot? = null
            instrumentation.runOnMainSync {
                val f = requireNotNull(field)
                val text = f.text
                val start = BaseInputConnection.getComposingSpanStart(text)
                val end = BaseInputConnection.getComposingSpanEnd(text)
                result = Snapshot(text.toString(), if (start >= 0 && end >= start) text.subSequence(start, end).toString() else null, received.toList())
            }
            return requireNotNull(result)
        }
        try {
            device.executeShellCommand("ime enable $imeId")
            device.executeShellCommand("ime set $imeId")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity = host
            instrumentation.runOnMainSync {
                received.clear()
                val editor = EditText(host).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                    imeOptions = action
                    setOnEditorActionListener { _, actionId, _ -> received.add(actionId); true }
                }
                field = editor
                host.setContentView(editor)
                editor.requestFocus()
            }
            requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(pkg)), 8_000)).click()
            instrumentation.runOnMainSync {
                host.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(requireNotNull(field), InputMethodManager.SHOW_IMPLICIT)
            }
            awaitChineseHeader()
            test(host, requireNotNull(field), ::snapshot)
        } finally {
            activity?.let { a -> instrumentation.runOnMainSync { a.finish() } }
            restoreIme(previous, hardware)
        }
    }

    private fun awaitSnapshot(snapshot: () -> Snapshot, message: String, predicate: (Snapshot) -> Boolean): Snapshot {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var current = snapshot()
        while (!predicate(current) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            current = snapshot()
        }
        assertTrue("$message; actual=$current", predicate(current))
        return current
    }

    private fun textOf(host: Activity, viewId: Int): String {
        var text = ""
        instrumentation.runOnMainSync { text = host.findViewById<EditText>(viewId).text.toString() }
        return text
    }

    @Test fun delayedRapidKeysAreProcessedInOrderAndCommitOnce() = withFakeEngine { fake, host ->
        fake.hold = true
        typeLetters("nih")
        fake.flushAll()
        fake.hold = true
        typeLetters("ao")
        fake.flushAll()
        fake.hold = true
        tap("key:空格")
        fake.flushAll()
        awaitSnapshot({ Snapshot(textOf(host, R.id.fixture_editor_a), null, emptyList()) },
            "Space must commit the whole code exactly once, in order; ops=${fake.operations}") {
            it.text == "nihao"
        }
        freshTree()
        assertFalse("No candidate strip may remain after the raw commit",
            device.hasObject(By.res("candidate_toggle").pkg(pkg)))
        val keys = fake.operations.dropWhile { it == "init" }
        assertEquals("Every key must reach the engine once, in order",
            listOf("n", "i", "h", "a", "o", "space"), keys)
    }

    @Test fun delayedOutputIsDroppedWhenLanguageSwitched() = withFakeEngine { fake, host ->
        fake.hold = true
        typeLetters("n")
        // Keep some distance from the last letter tap so the IME never merges gestures.
        device.waitForIdle()
        android.os.SystemClock.sleep(400)
        requireKey("key:切换语言，长按切换输入法").click()
        device.waitForIdle()
        val english = device.waitForFreshObject(By.text("絮雨 · English").pkg(pkg), 10_000)
        if (english == null) dumpUi("phase4-language-switch-fail")
        assertNotNull("English header must appear", english)
        fake.flushAll()
        device.waitForIdle()
        typeLetters("ok")
        device.waitForIdle()
        assertEquals("Stale Chinese preedit must never commit after switching language",
            "ok", textOf(host, R.id.fixture_editor_a))
    }

    @Test fun delayedOutputIsDroppedWhenEditorChanged() = withFakeEngine { fake, host ->
        fake.hold = true
        typeLetters("n")
        val fieldB = requireNotNull(device.waitForFreshObject(By.desc("Field B").pkg(pkg), 8_000))
        fieldB.click()
        device.waitForIdle()
        fake.flushAll()
        device.waitForIdle()
        // The second field keeps the language preference (Chinese); switch to English so a
        // literal reaches the committed text directly.
        tap("key:切换语言，长按切换输入法")
        assertNotNull("English header must appear on the new editor",
            device.waitForFreshObject(By.text("絮雨 · English").pkg(pkg), 10_000))
        typeLetters("ok")
        device.waitForIdle()
        assertEquals("New field receives only its own input", "ok", textOf(host, R.id.fixture_editor_b))
        assertEquals("Old field must stay empty; stale code never lands in the new editor",
            "", textOf(host, R.id.fixture_editor_a))
    }

    /** The editing tool is only reachable without a composition; raw commit ends it exactly once. */
    @Test fun editingPanelAfterRawCommitMovesCursorAndResumesChinese() = withRealChineseEditor { host, editor, snapshot ->
        typeLetters("nihao")
        awaitSnapshot(snapshot, "Composition must be active") { it.composing?.replace(" ", "") == "nihao" }
        enterWith("完成")
        awaitSnapshot(snapshot, "Raw commit must end the composition exactly once") {
            it.text == "nihao" && it.composing == null && it.actions.isEmpty()
        }
        tap("edit_toggle")
        node("key:光标左移").click()
        device.waitForIdle()
        awaitSnapshot(snapshot, "Editing moves the cursor over committed text") {
            it.text == "nihao" && it.composing == null
        }
        tap("edit_toggle")
        typeLetters("x")
        enterWith("完成")
        awaitSnapshot(snapshot, "Chinese resumes after editing at the moved cursor") {
            it.text == "nihaxo" && it.composing == null && it.actions.isEmpty()
        }
    }

    /** Long-pressing the candidate-panel delete must stop at an empty code, never delete text. */
    @Test fun candidateLongDeleteStopsAtEmptyCodePreservingCommittedText() = withRealChineseEditor { host, editor, snapshot ->
        typeLetters("shi")
        enterWith("完成")
        awaitSnapshot(snapshot, "Seed committed prefix") { it.text == "shi" && it.composing == null }
        typeLetters("shi")
        awaitSnapshot(snapshot, "Second composition must be active") { it.composing?.replace(" ", "") == "shi" }
        tap("candidate_toggle")
        val deadline = SystemClock.uptimeMillis() + 10_000
        var cleared = false
        while (SystemClock.uptimeMillis() < deadline && !cleared) {
            node("candidate_delete").longClick()
            device.waitForIdle()
            val current = snapshot()
            cleared = current.composing == null && current.text == "shi"
        }
        assertTrue("Held delete must empty the code and stop, keeping committed text", cleared)
        freshTree()
        assertFalse("Candidates must disappear when the composition ends",
            device.hasObject(By.res("candidate_toggle").pkg(pkg)))
    }

    private fun enterWith(label: String) = tap("key:回车：$label")
}

/** Deterministic stand-in for librime: replies can be held and flushed from the test. */
private class FakeEngineBridge : EngineBridge {
    @Volatile var hold = false
    val operations = mutableListOf<String>()
    private var code = ""
    private var serial = 0L
    private val held = mutableListOf<Pair<RimeOutput, (RimeOutput) -> Unit>>()

    private fun spaced() = code.toCharArray().joinToString(" ")

    override fun request(sessionId: Int, operation: Int, key: Int, limit: Int, callback: (RimeOutput) -> Unit) {
        val out = step(sessionId, operation, key)
        operations.add(marker(operation, key))
        if (hold) {
            synchronized(held) { held.add(out to callback) }
        } else {
            post(out, callback)
        }
    }

    private fun marker(operation: Int, key: Int) = when (operation) {
        0 -> "init"
        1 -> when (key) {
            0x20 -> "space"
            0xff08 -> "del"
            0xff0d -> "enter"
            else -> key.toChar().lowercaseChar().toString()
        }
        2 -> "select:$key"
        4 -> "commit"
        else -> "op$operation"
    }

    private fun step(sessionId: Int, operation: Int, key: Int): RimeOutput {
        serial++
        val seq = serial
        return when (operation) {
            0 -> RimeOutput(sessionId, seq, consumed = true, preedit = "", candidates = emptyList())
            1 -> {
                val c = key.toChar()
                when {
                    key == 0x20 -> { val committed = code; code = ""; RimeOutput(sessionId, seq, consumed = true, commitText = committed, preedit = "") }
                    key == 0xff08 -> { code = code.dropLast(1); RimeOutput(sessionId, seq, consumed = true, preedit = spaced()) }
                    key == 0xff0d -> { val committed = code; code = ""; RimeOutput(sessionId, seq, consumed = true, commitText = committed, preedit = "") }
                    c.isLetter() -> { code += c.lowercaseChar(); RimeOutput(sessionId, seq, consumed = true, preedit = spaced(), candidates = listOf(code)) }
                    else -> RimeOutput(sessionId, seq, consumed = true, preedit = spaced())
                }
            }
            2 -> { val committed = if (code.isNotEmpty()) code else ""; code = ""; RimeOutput(sessionId, seq, consumed = true, commitText = committed, preedit = "") }
            else -> RimeOutput(sessionId, seq, consumed = true, preedit = spaced())
        }
    }

    override fun reset() {
        code = ""
        synchronized(held) { held.clear() }
    }

    override fun close() { reset() }

    fun flushAll() {
        // Callbacks drain the action queue synchronously on the main looper, which may
        // re-enter request() and re-hold the next key. After each batch, wait for the main
        // thread to finish processing (barrier runnable) before checking for new pending
        // replies, so the test never races ahead of the IME.
        val main = Handler(Looper.getMainLooper())
        while (true) {
            val batch: List<Pair<RimeOutput, (RimeOutput) -> Unit>>
            synchronized(held) {
                if (held.isEmpty()) return
                batch = held.toList()
                held.clear()
            }
            batch.forEach { (out, callback) -> main.post { callback(out) } }
            val barrier = CountDownLatch(1)
            main.post { barrier.countDown() }
            if (!barrier.await(5, TimeUnit.SECONDS)) error("Main thread did not drain engine replies")
        }
    }

    private fun post(out: RimeOutput, callback: (RimeOutput) -> Unit) {
        // Always deliver on the main looper, like RimeEngine does: the service may call
        // request() from the main thread during prepareChinese, where runOnMainSync would fail.
        Handler(Looper.getMainLooper()).post { callback(out) }
    }
}