package org.thanatophilia.xyuime

import android.app.Activity
import android.content.Intent
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

/**
 * D3: punctuation and special editor fields against the real Rime engine.
 * - Chinese composition + comma/period: commit the highlighted candidate first, then insert the
 *   Chinese punctuation.
 * - Chinese composition + digits via the number page: raw pinyin is committed first, then the digit.
 * - Shift inside a composition: no commit, no loss (language page semantics are Chinese).
 * - Password fields: Chinese engine disabled, direct literal input.
 * - Number fields: the number page opens initially, digits commit directly, Done fires once.
 */
@RunWith(AndroidJUnit4::class)
class ImePhase5SpecialFieldInputTest {
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
    private fun enterWith(label: String) = tap("key:回车：$label")

    private fun awaitHeader(pattern: String, timeoutMs: Long = 120_000) {
        assertNotNull("Header must match $pattern",
            device.waitForFreshObject(By.text(java.util.regex.Pattern.compile(pattern)).pkg(pkg), timeoutMs))
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

    private fun snapshotOf(host: Activity, viewId: Int, actions: () -> List<Int> = { emptyList() }): Snapshot {
        var result: Snapshot? = null
        instrumentation.runOnMainSync {
            val text = host.findViewById<EditText>(viewId).text
            val start = BaseInputConnection.getComposingSpanStart(text)
            val end = BaseInputConnection.getComposingSpanEnd(text)
            result = Snapshot(text.toString(), if (start >= 0 && end >= start) text.subSequence(start, end).toString() else null, actions())
        }
        return requireNotNull(result)
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

    /** Real engine inside a synthetic single-line editor with an editor-action recorder. */
    private fun withChineseSyntheticEditor(action: Int = EditorInfo.IME_ACTION_DONE, test: (Activity, EditText, () -> Snapshot) -> Unit) {
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val hardware = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        val received = mutableListOf<Int>()
        var activity: Activity? = null
        var field: EditText? = null
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
            awaitHeader("絮雨 · (English|中文拼音)")
            if (!device.hasObject(By.text("絮雨 · 中文拼音").pkg(pkg))) {
                tap("key:切换语言，长按切换输入法")
            }
            awaitHeader("絮雨 · 中文拼音", 15_000)
            test(host, requireNotNull(field)) {
                var result: Snapshot? = null
                instrumentation.runOnMainSync {
                    val editor = requireNotNull(field)
                    val text = editor.text
                    val start = BaseInputConnection.getComposingSpanStart(text)
                    val end = BaseInputConnection.getComposingSpanEnd(text)
                    result = Snapshot(text.toString(), if (start >= 0 && end >= start) text.subSequence(start, end).toString() else null, received.toList())
                }
                requireNotNull(result)
            }
        } finally {
            activity?.let { a -> instrumentation.runOnMainSync { a.finish() } }
            restoreIme(previous, hardware)
        }
    }

    /** Real engine inside a password/number fixture; waits for the field-specific header. */
    private fun withSpecialFixture(fixture: String, header: String, test: (Activity, EditText) -> Unit) {
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val hardware = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        var activity: Activity? = null
        val viewId = if (fixture == EditorFixtureActivity.FIXTURE_PASSWORD) R.id.fixture_password_editor else R.id.fixture_number_editor
        try {
            device.executeShellCommand("ime enable $imeId")
            device.executeShellCommand("ime set $imeId")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            val host = instrumentation.startActivitySync(EditorFixtureActivity.intent(context, fixture)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity = host
            val field = device.waitForFreshObject(By.desc(if (fixture == EditorFixtureActivity.FIXTURE_PASSWORD) "Password" else "Number").pkg(pkg), 8_000)
            requireNotNull(field).click()
            device.waitForIdle()
            instrumentation.runOnMainSync {
                host.findViewById<EditText>(viewId).let {
                    host.getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            awaitHeader(header)
            test(host, host.findViewById<EditText>(viewId))
        } finally {
            activity?.let { a -> instrumentation.runOnMainSync { a.finish() } }
            restoreIme(previous, hardware)
        }
    }

    @Test fun compositionCommitsThenChineseCommaAndPeriod() = withChineseSyntheticEditor { host, editor, snapshot ->
        typeLetters("nihao")
        awaitSnapshot(snapshot, "Composition must be active") { it.composing?.replace(" ", "") == "nihao" }
        tap("key:逗号")
        awaitSnapshot(snapshot, "Comma must submit the candidate first, then insert Chinese comma") {
            it.text.endsWith("，") && it.text.length > 1 && it.composing == null && it.actions.isEmpty()
        }
        typeLetters("nihao")
        awaitSnapshot(snapshot, "Composition must be active again") { it.composing?.replace(" ", "") == "nihao" }
        tap("key:句号")
        awaitSnapshot(snapshot, "Period must submit the candidate first, then insert Chinese period") {
            it.text.endsWith("。") && it.text.length > 2 && it.composing == null && it.actions.isEmpty()
        }
        freshTree()
        assertFalse("No candidate strip may remain after punctuation commits",
            device.hasObject(By.res("candidate_toggle").pkg(pkg)))
    }

    @Test fun compositionCommitsRawThenDigitsFromNumberPage() = withChineseSyntheticEditor { host, editor, snapshot ->
        typeLetters("nihao")
        awaitSnapshot(snapshot, "Composition must be active") { it.composing?.replace(" ", "") == "nihao" }
        tap("key:数字键盘")
        node("key:1")
        tap("key:1")
        awaitSnapshot(snapshot, "Digit must commit raw pinyin first, then insert the digit") {
            it.text == "nihao1" && it.composing == null && it.actions.isEmpty()
        }
        tap("key:返回字母键盘")
        typeLetters("an")
        awaitSnapshot(snapshot, "Letters resume Chinese after the number page") { it.composing?.replace(" ", "") == "an" }
        enterWith("完成")
        awaitSnapshot(snapshot, "Final raw commit appends to the committed prefix") {
            it.text == "nihao1an" && it.composing == null && it.actions.isEmpty()
        }
    }

    @Test fun shiftInsideCompositionKeepsComposition() = withChineseSyntheticEditor { host, editor, snapshot ->
        typeLetters("nihao")
        awaitSnapshot(snapshot, "Composition must be active") { it.composing?.replace(" ", "") == "nihao" }
        tap("key:切换大小写，长按锁定大写")
        awaitSnapshot(snapshot, "Shift must not commit, drop, or alter an active composition") {
            it.composing?.replace(" ", "") == "nihao" && it.text.isEmpty()
        }
        node("candidate:0")
        enterWith("完成")
        awaitSnapshot(snapshot, "Composition still works after Shift") {
            it.text == "nihao" && it.composing == null && it.actions.isEmpty()
        }
    }

    @Test fun passwordFieldDisablesChineseEngineAndDirectInput() = withSpecialFixture(
        EditorFixtureActivity.FIXTURE_PASSWORD, "絮雨 · 密码") { host, editor ->
        typeLetters("abc")
        awaitSnapshot({ snapshotOf(host, R.id.fixture_password_editor) },
            "Password must stay direct literal input, no composition") {
            it.text == "abc" && it.composing == null
        }
        freshTree()
        assertFalse("Password field must never expose candidates",
            device.hasObject(By.res("candidate_toggle").pkg(pkg)))
        assertFalse("Password field must never expose the floating preedit",
            device.hasObject(By.res("floating_preedit").pkg(pkg)))
    }

    @Test fun numberFieldOpensNumberPageAndDoneFiresOnce() = withSpecialFixture(
        EditorFixtureActivity.FIXTURE_NUMBER, "絮雨 · 数字") { host, editor ->
        node("key:1")
        freshTree()
        assertFalse("Number field must open on the number page, not letters",
            device.hasObject(By.res("key:q").pkg(pkg)))
        tap("key:1")
        tap("key:2")
        awaitSnapshot({ snapshotOf(host, R.id.fixture_number_editor) },
            "Digits must commit directly in a number field") {
            it.text == "12" && it.composing == null
        }
        tap("key:回车：完成")
        device.waitForIdle()
        val label = requireNotNull(device.waitForFreshObject(By.res("$pkg:id/fixture_action_label").pkg(pkg), 5_000))
        assertEquals("Done must fire exactly once with IME_ACTION_DONE",
            "action:${EditorInfo.IME_ACTION_DONE}", label.text)
    }
}