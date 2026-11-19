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
 * D5: the real symbol page in the running IME. Tabs on top, vertical grid with horizontal
 * group swipes, one-cell delete floating on the bottom-right, and the bottom row's first
 * key reading ABC while the page is open.
 */
@RunWith(AndroidJUnit4::class)
class ImePhase7SymbolInputTest {
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

    private fun tap(tag: String) {
        repeat(4) { attempt ->
            val target = node(tag)
            try {
                target.click()
                device.waitForIdle()
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                // A successful action may re-lay the keyboard between lookup and click.
                SystemClock.sleep(150L * (attempt + 1))
            }
        }
        error("Tap target kept going stale: $tag")
    }
    private fun typeLetters(text: String) { text.forEach { tap("key:$it") } }

    private fun tapDescription(description: String) {
        repeat(4) { attempt ->
            freshTree()
            val target = device.waitForFreshObject(By.desc(description).pkg(pkg), 5_000)
            requireNotNull(target) { "Missing description: $description" }
            try {
                target.click()
                device.waitForIdle()
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                SystemClock.sleep(150L * (attempt + 1))
            }
        }
        error("Description target kept going stale: $description")
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

    private fun awaitHeader(pattern: String, timeoutMs: Long = 120_000) {
        assertNotNull("Header must match $pattern",
            device.waitForFreshObject(By.text(java.util.regex.Pattern.compile(pattern)).pkg(pkg), timeoutMs))
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

    /** Real engine inside a synthetic editor; the recorder collects editor actions. */
    private fun withSyntheticEditor(test: (Activity, EditText, () -> Snapshot) -> Unit) {
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val hardware = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        val received = mutableListOf<Int>()
        var activity: Activity? = null
        var field: EditText? = null
        fun snapshot(): Snapshot {
            var result: Snapshot? = null
            instrumentation.runOnMainSync {
                val editor = requireNotNull(field)
                val text = editor.text
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
                    imeOptions = EditorInfo.IME_ACTION_DONE
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
            test(host, requireNotNull(field), ::snapshot)
        } finally {
            activity?.let { a -> instrumentation.runOnMainSync { a.finish() } }
            restoreIme(previous, hardware)
        }
    }

    /** Special fixture editor (email): waits for its field header. */
    private fun withEmailFixture(test: (Activity, EditText) -> Unit) {
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val hardware = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        var activity: Activity? = null
        try {
            device.executeShellCommand("ime enable $imeId")
            device.executeShellCommand("ime set $imeId")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            val host = instrumentation.startActivitySync(EditorFixtureActivity.intent(context, EditorFixtureActivity.FIXTURE_EMAIL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity = host
            requireNotNull(device.waitForFreshObject(By.desc("Email").pkg(pkg), 8_000)).click()
            device.waitForIdle()
            instrumentation.runOnMainSync {
                host.findViewById<EditText>(R.id.fixture_email_editor).let {
                    host.getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            awaitHeader("絮雨 · 邮箱")
            test(host, host.findViewById(R.id.fixture_email_editor))
        } finally {
            activity?.let { a -> instrumentation.runOnMainSync { a.finish() } }
            restoreIme(previous, hardware)
        }
    }

    private fun fieldText(host: Activity, viewId: Int): String {
        var text = ""
        instrumentation.runOnMainSync { text = host.findViewById<EditText>(viewId).text.toString() }
        return text
    }

    private fun assertGroupSelected(tab: String) {
        val titles = mapOf("common" to "常用", "recent" to "最近", "cn" to "中", "en" to "英",
            "math" to "数", "arrows" to "箭头", "ordinal" to "序号", "marks" to "角标")
        val key = tab.substringAfter("symbol_tab:")
        val index = listOf("common", "recent", "cn", "en", "math", "arrows", "ordinal", "marks").indexOf(key) + 1
        require(index > 0) { "Unknown symbol group: $tab" }
        val description = "符号分组 ${titles.getValue(key)}，第 ${index} 组，共 8 组"
        assertNotNull("Missing group state: $description",
            device.waitForFreshObject(By.desc(description).pkg(pkg), 5_000))
    }

    /** Scrolls the five-visible chip strip until the requested group can be tapped. */
    private fun tapSymbolTab(key: String) {
        repeat(6) { attempt ->
            freshTree()
            val target = device.findObject(By.res("symbol_tab:$key").pkg(pkg))
            if (target != null) {
                target.click()
                device.waitForIdle()
                return
            }
            val bar = device.findObject(By.res("symbol_tab_bar").pkg(pkg))
            requireNotNull(bar) { "Missing symbol tab bar while looking for $key" }
            val bounds = bar.visibleBounds
            val y = bounds.centerY()
            if (attempt % 2 == 0) {
                device.swipe(bounds.left + 20, y, bounds.right - 20, y, 40)
            } else {
                device.swipe(bounds.right - 20, y, bounds.left + 20, y, 40)
            }
            device.waitForIdle()
        }
        error("Symbol tab not reachable: $key")
    }

    @Test fun chineseGroupInsertsPunctuationAndReturnsToLetters() = withSyntheticEditor { host, editor, snapshot ->
        tap("key:符号键盘")
        tapSymbolTab("cn")
        assertGroupSelected("symbol_tab:cn")
        assertNotNull("Group dots must appear in the info strip",
            device.waitForFreshObject(By.text("○ ○ ● ○ ○ ○ ○ ○ 3/8").pkg(pkg), 3_000))
        tapDescription("顿号")
        tapDescription("书名号左")
        tapDescription("方头括号左")
        awaitSnapshot(snapshot, "Chinese punctuation must land textually") {
            it.text == "、《【" && it.composing == null
        }
        // The first bottom key is ABC on the symbol page and returns to letters.
        assertEquals("ABC", node("key:返回字母键盘").text)
        tap("key:返回字母键盘")
        node("key:q")
        typeLetters("an")
        awaitSnapshot(snapshot, "Letters keep working after the symbol page") {
            it.composing?.replace(" ", "") == "an"
        }
    }

    @Test fun scrollingRevealsTheCellCoveredByFloatingDelete() = withSyntheticEditor { host, editor, snapshot ->
        tap("key:符号键盘")
        tapSymbolTab("cn")
        // 省略号 sits exactly under the floating delete key on the first screen; scrolling
        // must move it out from under the overlay so it can be tapped.
        val grid = node("symbol_grid").visibleBounds
        device.swipe(grid.centerX(), grid.bottom - 20, grid.centerX(), grid.top + 20, 60)
        device.waitForIdle()
        tapDescription("省略号")
        awaitSnapshot(snapshot, "A scrolled-into-view symbol must be tappable") {
            it.text == "…" && it.composing == null
        }
    }

    @Test fun swipeSwitchesGroupsAndMathCharacterCommits() = withSyntheticEditor { host, editor, snapshot ->
        tap("key:符号键盘")
        tapSymbolTab("cn")
        val grid = node("symbol_grid").visibleBounds
        device.swipe(grid.right - 20, grid.centerY(), grid.left + 20, grid.centerY(), 40)
        device.waitForIdle()
        assertGroupSelected("symbol_tab:en")
        tapDescription("半角冒号")
        device.swipe(grid.right - 20, grid.centerY(), grid.left + 20, grid.centerY(), 40)
        device.waitForIdle()
        assertGroupSelected("symbol_tab:math")
        tapDescription("乘")
        device.swipe(grid.right - 20, grid.centerY(), grid.left + 20, grid.centerY(), 40)
        device.waitForIdle()
        assertGroupSelected("symbol_tab:arrows")
        tapDescription("上箭头")
        awaitSnapshot(snapshot, "Swipe-selected groups must insert exact math and extended characters") {
            it.text == ":×↑" && it.composing == null
        }
    }

    @Test fun compositionPlusSymbolCommitsRawFirst() = withSyntheticEditor { host, editor, snapshot ->
        typeLetters("nihao")
        awaitSnapshot(snapshot, "Composition must be active") { it.composing?.replace(" ", "") == "nihao" }
        tap("key:符号键盘")
        tapDescription("叹号")
        awaitSnapshot(snapshot, "Symbol insertion must commit the raw code first, then the mark") {
            it.text == "nihao！" && it.composing == null && it.actions.isEmpty()
        }
        tap("key:返回字母键盘")
        node("key:q")
    }

    @Test fun emailEditorDefaultsToEnglishGroupAndNormalizesFullWidth() = withEmailFixture { host, editor ->
        tap("key:符号键盘")
        assertGroupSelected("symbol_tab:en")
        tapDescription("半角冒号")
        assertEquals(":", fieldText(host, R.id.fixture_email_editor))
        // Manually browsing back to Chinese punctuation must still reach an email-safe string.
        tapSymbolTab("cn")
        device.waitForIdle()
        tapDescription("顿号")
        assertEquals(":,", fieldText(host, R.id.fixture_email_editor))
    }

    @Test fun floatingDeleteRemovesExactlyOneCharacterPerTap() = withSyntheticEditor { host, editor, snapshot ->
        instrumentation.runOnMainSync {
            editor.setText("abc")
            // setText leaves the caret at the start; place it where normal typing leaves it.
            editor.setSelection(editor.text.length)
        }
        tap("key:符号键盘")
        tapSymbolTab("cn")
        SystemClock.sleep(400) // wait for the symbol grid and floating key to settle
        tap("key:删除")
        awaitSnapshot(snapshot, "One physical tap must delete one character") { it.text == "ab" }
        tap("key:删除")
        awaitSnapshot(snapshot, "The second physical tap deletes one more") { it.text == "a" }
    }

    @Test fun floatingDeleteLongPressRepeatsAndStopsOnRelease() = withSyntheticEditor { host, editor, snapshot ->
        instrumentation.runOnMainSync {
            editor.setText("abcdef")
            editor.setSelection(editor.text.length)
        }
        tap("key:符号键盘")
        tapSymbolTab("cn")
        SystemClock.sleep(400)
        node("key:删除").longClick()
        val afterHold = snapshot()
        assertTrue("Long press should delete multiple characters; actual=$afterHold", afterHold.text.length <= 4)
        SystemClock.sleep(400)
        assertEquals("Repeat must stop promptly after release", afterHold.text, snapshot().text)
    }

    @Test fun recentGroupRecordsNewestSymbolFirst() = withSyntheticEditor { host, editor, snapshot ->
        tap("key:符号键盘")
        tapSymbolTab("cn")
        tapDescription("顿号")
        tapDescription("书名号左")
        tapSymbolTab("recent")
        device.waitForIdle()
        freshTree()
        val first = device.waitForFreshObject(By.res("symbol:recent:0").pkg(pkg), 5_000)
        val second = device.waitForFreshObject(By.res("symbol:recent:1").pkg(pkg), 5_000)
        requireNotNull(first) { "Recent group must show the last symbol" }
        requireNotNull(second)
        assertEquals("书名号左", first.contentDescription)
        assertEquals("顿号", second.contentDescription)
        // Tapping a recent entry inserts it again and keeps it at the front.
        first.click()
        device.waitForIdle()
        awaitSnapshot(snapshot, "A recent entry must be insertable") {
            it.text == "、《《" && it.composing == null
        }
    }

}
