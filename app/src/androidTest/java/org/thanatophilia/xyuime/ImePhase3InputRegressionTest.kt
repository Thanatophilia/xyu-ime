package org.thanatophilia.xyuime

import android.app.Activity
import android.content.Intent
import android.os.Build
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

/** Real IME tests. The editor/action recorder exists only in the instrumentation APK. */
@RunWith(AndroidJUnit4::class)
class ImePhase3InputRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = context.packageName
    private lateinit var field: EditText
    private val receivedActions = mutableListOf<Int>() // Accessed only on the main thread.

    private data class Snapshot(val text: String, val composing: String?, val actions: List<Int>)

    private fun snapshot(): Snapshot {
        var result: Snapshot? = null
        instrumentation.runOnMainSync {
            val text = field.text
            val start = BaseInputConnection.getComposingSpanStart(text)
            val end = BaseInputConnection.getComposingSpanEnd(text)
            result = Snapshot(text.toString(),
                if (start >= 0 && end >= start) text.subSequence(start, end).toString() else null,
                receivedActions.toList())
        }
        return requireNotNull(result)
    }

    private fun awaitSnapshot(message: String, predicate: (Snapshot) -> Boolean): Snapshot {
        val deadline = SystemClock.uptimeMillis() + 8_000
        var current = snapshot()
        while (!predicate(current) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            current = snapshot()
        }
        assertTrue("$message; actual=$current", predicate(current))
        return current
    }

    private fun freshTree() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        device.waitForIdle()
    }

    private fun node(tag: String): UiObject2 {
        freshTree()
        return requireNotNull(device.wait(Until.findObject(By.res(tag).pkg(pkg)), 15_000)) { tag }
    }

    private fun tap(tag: String) { node(tag).click(); device.waitForIdle() }
    private fun typeLetters(text: String) { text.forEach { tap("key:$it") } }
    private fun enter(label: String) = tap("key:回车：$label")
    private fun noCandidates() {
        freshTree()
        assertTrue("Candidate strip must disappear after commit",
            device.wait(Until.gone(By.res("candidate_toggle").pkg(pkg)), 5_000))
        assertFalse(device.hasObject(By.res("candidate_drawer").pkg(pkg)))
    }

    private fun withChineseEditor(action: Int = EditorInfo.IME_ACTION_DONE, test: () -> Unit) {
        val id = "$pkg/org.thanatophilia.xyuime.ime.XyuImeService"
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val hardware = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        var activity: Activity? = null
        try {
            device.executeShellCommand("ime enable $id")
            device.executeShellCommand("ime set $id")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity = host
            instrumentation.runOnMainSync {
                receivedActions.clear()
                field = EditText(host).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                    imeOptions = action
                    hint = "Phase 3 synthetic editor"
                    setOnEditorActionListener { _, actionId, _ ->
                        receivedActions.add(actionId)
                        true // Keep focus/keyboard so a second Enter is observable.
                    }
                }
                host.setContentView(field)
                field.requestFocus()
            }
            requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(pkg)), 8_000)).click()
            instrumentation.runOnMainSync {
                host.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
            // A previously running service may retain the Chinese preference.
            val ready = By.textMatches("絮雨 · (English|中文拼音)").pkg(pkg)
            assertTrue("Rime deployment must finish", device.wait(Until.hasObject(ready), 120_000))
            if (!device.hasObject(By.text("絮雨 · 中文拼音").pkg(pkg))) {
                tap("key:切换语言，长按切换输入法")
            }
            assertTrue(device.wait(Until.hasObject(By.text("絮雨 · 中文拼音").pkg(pkg)), 15_000))
            test()
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            if (previous.contains('/')) device.executeShellCommand("ime set $previous")
            if (hardware == "null" || hardware.isEmpty()) {
                device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            } else {
                device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $hardware")
            }
        }
    }

    private fun assertTwoStageEnter(action: Int, label: String) = withChineseEditor(action) {
        typeLetters("nihao")
        awaitSnapshot("nihao must be composing before Enter") {
            it.composing?.replace(" ", "") == "nihao" && it.actions.isEmpty()
        }
        node("candidate:0")
        enter(label)
        awaitSnapshot("First Enter commits raw input without invoking editor action") {
            it.text == "nihao" && it.composing == null && it.actions.isEmpty()
        }
        noCandidates()
        enter(label)
        awaitSnapshot("Second Enter invokes exactly the requested editor action") {
            it.text == "nihao" && it.composing == null && it.actions == listOf(action)
        }
    }

    @Test fun searchEnterCommitsRawThenSearches() = assertTwoStageEnter(EditorInfo.IME_ACTION_SEARCH, "搜索")
    @Test fun sendEnterCommitsRawThenSends() = assertTwoStageEnter(EditorInfo.IME_ACTION_SEND, "发送")
    @Test fun doneEnterCommitsRawThenFinishes() = assertTwoStageEnter(EditorInfo.IME_ACTION_DONE, "完成")

    @Test fun standaloneApostropheCommitsLiteralWithoutCandidates() = withChineseEditor {
        tap("key:符号键盘")
        tap("key:'")
        awaitSnapshot("Standalone apostrophe must survive as literal text") {
            it.text == "'" && it.composing == null && it.actions.isEmpty()
        }
        noCandidates()
        tap("key:返回字母键盘")
        typeLetters("nihao")
        awaitSnapshot("Literal apostrophe must not join the next composition") {
            it.text.startsWith("'") && it.composing?.replace(" ", "") == "nihao"
        }
        enter("完成")
        awaitSnapshot("Next raw commit preserves the literal apostrophe") {
            it.text == "'nihao" && it.composing == null && it.actions.isEmpty()
        }
    }

    @Test fun apostropheBetweenSyllablesStaysInsideOneComposition() = withChineseEditor {
        typeLetters("xi")
        awaitSnapshot("xi must begin a composition") { it.composing == "xi" }
        // Exercise the actual visible route to apostrophe, including page transitions.
        // This intentionally catches a page switch prematurely committing xi.
        tap("key:符号键盘")
        tap("key:'")
        tap("key:返回字母键盘")
        typeLetters("an")
        awaitSnapshot("xi'an must remain one composition, not committed xi plus composing an") {
            it.composing?.replace(" ", "") == "xi'an" && it.actions.isEmpty()
        }
        node("candidate:0")
        enter("完成")
        awaitSnapshot("Enter preserves the raw syllable delimiter") {
            it.text == "xi'an" && it.composing == null && it.actions.isEmpty()
        }
        noCandidates()
    }

    @Test fun drawerScrollDoesNotSelectOrExposeOrdinaryKeys() = withChineseEditor {
        typeLetters("shi") // Many candidates; do not depend on dictionary ordering.
        val before = awaitSnapshot("shi must be composing") { it.composing == "shi" }
        node("candidate:0")
        tap("candidate_toggle")
        val drawer = node("candidate_drawer").visibleBounds
        fun assertNoOrdinaryKeys() {
            freshTree()
            listOf("q", "a", "z", "空格", "逗号", "句号", "符号键盘", "数字键盘").forEach {
                assertFalse("Ordinary key leaked through drawer: $it", device.hasObject(By.res("key:$it").pkg(pkg)))
            }
        }
        assertNoOrdinaryKeys()
        // Scope to the drawer: the separate horizontal strip can still expose candidate:0.
        val candidateSelector = By.res(java.util.regex.Pattern.compile("candidate:[0-9]+"))
        val cells = node("candidate_drawer").findObjects(candidateSelector)
        assertTrue("Drawer must contain visible candidate cells", cells.isNotEmpty())
        val visibleBefore = cells.map { it.resourceName }.toSet()
        // Start inside a low candidate cell and drag; releasing must not become a click.
        val start = cells.map { it.visibleBounds }.filter { it.height() > 10 }
            .maxByOrNull { it.centerY() } ?: error("No usable candidate gesture target")
        device.swipe(start.centerX(), start.centerY(), start.centerX(), drawer.top + 4, 30)
        device.waitForIdle()
        val visibleAfter = node("candidate_drawer").findObjects(candidateSelector)
            .map { it.resourceName }.toSet()
        assertNotEquals("Gesture must actually change visible drawer candidates", visibleBefore, visibleAfter)
        assertNoOrdinaryKeys()
        assertEquals("Scrolling must neither commit nor alter composition", before, snapshot())
        tap("candidate_toggle")
        node("key:q")
        enter("完成")
        awaitSnapshot("Raw commit after scrolling still contains the original input") {
            it.text == "shi" && it.composing == null && it.actions.isEmpty()
        }
        noCandidates()
    }
}
