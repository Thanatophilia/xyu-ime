package org.thanatophilia.xyuime

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * D4: window behaviour, third-party-style editors, semantics and touch cancellation.
 * Covered here: landscape layout integrity, large font scale, drag-off key cancellation,
 * accessibility descriptions on the real IME window, candidate drawer inside screen bounds,
 * and a web contenteditable receiving an offline Chinese commit.
 */
@RunWith(AndroidJUnit4::class)
class ImePhase6WindowAccessibilityTest {
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

    private fun withImeEditor(fixture: String? = null, test: (Activity) -> Unit) {
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val hardware = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        var activity: Activity? = null
        try {
            device.executeShellCommand("ime enable $imeId")
            device.executeShellCommand("ime set $imeId")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            val intent = if (fixture != null) {
                EditorFixtureActivity.intent(context, fixture)
            } else {
                Intent(context, MainActivity::class.java)
            }
            val host = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity = host
            test(host)
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

    private fun syntheticEditorSnapshot(host: Activity, field: EditText, actions: () -> List<Int>): Snapshot {
        var result: Snapshot? = null
        instrumentation.runOnMainSync {
            val text = field.text
            val start = BaseInputConnection.getComposingSpanStart(text)
            val end = BaseInputConnection.getComposingSpanEnd(text)
            result = Snapshot(text.toString(), if (start >= 0 && end >= start) text.subSequence(start, end).toString() else null, actions())
        }
        return requireNotNull(result)
    }

    @Test fun landscapeKeyboardKeepsFullLayoutAndCandidateStrip() = withImeEditor(EditorFixtureActivity.FIXTURE_NATIVE) { host ->
        val field = host.findViewById<EditText>(R.id.fixture_native_editor)
        field.performClick()
        device.waitForIdle()
        instrumentation.runOnMainSync {
            host.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
        node("key:q")
        device.setOrientationLeft()
        try {
            // Rotation tears the IME window down; show the keyboard again if needed and wait
            // until the letters return.
            var waited = 0L
            val rotateDeadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < rotateDeadline &&
                device.findObject(By.res("key:q").pkg(pkg)) == null) {
                if (waited > 0 && waited % 3000 == 0L) {
                    // The IME window is torn down on rotation; refocus the editor and ask the
                    // system again until the keyboard returns.
                    val editable = device.findObject(By.clazz("android.widget.EditText").pkg(pkg))
                    editable?.click()
                    instrumentation.runOnMainSync {
                        host.getSystemService(InputMethodManager::class.java)
                            .showSoftInput(host.currentFocus, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                SystemClock.sleep(200)
                waited += 200
            }
            device.waitForIdle()
            val surface = node("keyboard_surface").visibleBounds
            assertTrue("Landscape keyboard must span the full width",
                surface.right - surface.left >= device.displayWidth - 4)
            listOf("key:q", "key:z", "key:空格", "key:回车：回车").forEach { node(it) }
            // Chinese composition still renders its floating preedit in landscape.
            tap("key:切换语言，长按切换输入法")
            assertNotNull(device.waitForFreshObject(By.text("絮雨 · 中文拼音").pkg(pkg), 15_000))
            typeLetters("nihao")
            val candidateDeadline = SystemClock.uptimeMillis() + 10_000
            var candidateSeen = false
            while (SystemClock.uptimeMillis() < candidateDeadline && !candidateSeen) {
                freshTree()
                candidateSeen = device.hasObject(By.res("candidate_toggle").pkg(pkg))
                if (!candidateSeen) SystemClock.sleep(100)
            }
            assertTrue("Candidate strip must appear in landscape", candidateSeen)
            freshTree()
            assertFalse("Drawer must not be open after typing only",
                device.hasObject(By.res("candidate_drawer").pkg(pkg)))
        } finally {
            device.setOrientationNatural()
        }
    }

    @Test fun largeFontScaleKeepsKeyboardUsable() = withImeEditor { host ->
        device.executeShellCommand("settings put system font_scale 1.3")
        try {
            val field = device.waitForFreshObject(By.clazz("android.widget.EditText").pkg(pkg), 8_000)
            requireNotNull(field).click()
            device.waitForIdle()
            node("key:q")
            listOf("key:a", "key:z", "key:空格", "key:逗号", "key:回车：回车").forEach { node(it) }
            typeLetters("abc")
            val text = device.waitForFreshObject(By.clazz("android.widget.EditText").pkg(pkg), 5_000)
            assertEquals("Large font must not break direct input", "abc", requireNotNull(text).text)
            tap("edit_toggle")
            node("key:全选")
            tap("edit_toggle")
            node("key:q")
        } finally {
            device.executeShellCommand("settings put system font_scale 1.0")
        }
    }

    @Test fun dragOffKeyCancelsWithoutInserting() = withImeEditor(EditorFixtureActivity.FIXTURE_NATIVE) { host ->
        val field = host.findViewById<EditText>(R.id.fixture_native_editor)
        instrumentation.runOnMainSync {
            field.requestFocus()
            host.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
        try {
            node("key:q")
            val key = node("key:q").visibleBounds
            val surface = node("keyboard_surface").visibleBounds
            // Press the key and drag well outside the keyboard before releasing: the key
            // gesture must cancel and insert nothing.
            device.swipe(key.centerX(), key.centerY(), surface.left, surface.bottom + 200, 80)
            device.waitForIdle()
            val after = awaitSnapshot({ syntheticEditorSnapshot(host, field) { emptyList() } },
                "Drag-off must cancel the key press") { it.text.isEmpty() }
            assertFalse("No candidate strip may appear from a cancelled key", device.hasObject(By.res("candidate_toggle").pkg(pkg)))
            assertEquals("", after.text)
        } finally {
            instrumentation.runOnMainSync { field.setText("") }
        }
    }

    @Test fun functionalKeysExposeAccessibilityDescriptions() = withImeEditor { host ->
        val field = device.waitForFreshObject(By.clazz("android.widget.EditText").pkg(pkg), 8_000)
        requireNotNull(field).click()
        device.waitForIdle()
        node("key:q")
        val expected = mapOf(
            "key:切换语言，长按切换输入法" to "切换语言，长按切换输入法",
            "key:切换大小写，长按锁定大写" to "切换大小写，长按锁定大写",
            "key:删除，长按连续删除" to "删除，长按连续删除",
            "edit_toggle" to "编辑工具",
        )
        expected.forEach { (tag, desc) ->
            val key = node(tag)
            assertEquals("$tag must keep its spoken description", desc, key.contentDescription)
        }
        // Candidate toggle flips its description once a composition opens.
        tap("key:切换语言，长按切换输入法")
        assertNotNull(device.waitForFreshObject(By.text("絮雨 · 中文拼音").pkg(pkg), 15_000))
        typeLetters("shi")
        val deadline = SystemClock.uptimeMillis() + 10_000
        var strip: UiObject2? = null
        while (SystemClock.uptimeMillis() < deadline && strip == null) {
            freshTree()
            strip = device.findObject(By.res("candidate_toggle").pkg(pkg))
            if (strip == null) SystemClock.sleep(100)
        }
        requireNotNull(strip)
        // The spoken description may live on the toggle or one of its children in the a11y tree.
        freshTree()
        val toggle = node("candidate_toggle")
        val descriptions = mutableListOf<String>()
        if (!toggle.contentDescription.isNullOrBlank()) descriptions.add(toggle.contentDescription)
        descriptions.addAll(toggle.findObjects(By.desc(java.util.regex.Pattern.compile(".+"))).mapNotNull { it.contentDescription })
        assertTrue("Candidate toggle must expose 展开候选; actual=$descriptions",
            descriptions.contains("展开候选"))
        tap("candidate_toggle")
        freshTree()
        val collapsed = node("candidate_toggle").findObjects(By.desc(java.util.regex.Pattern.compile(".+"))).mapNotNull { it.contentDescription }.toMutableList()
        if (!node("candidate_toggle").contentDescription.isNullOrBlank()) collapsed.add(node("candidate_toggle").contentDescription)
        assertTrue("Candidate toggle must expose 收回候选; actual=$collapsed",
            collapsed.contains("收回候选"))
    }

    @Test fun expandedCandidatePageStaysInsideScreenBounds() = withImeEditor { host ->
        val field = device.waitForFreshObject(By.clazz("android.widget.EditText").pkg(pkg), 8_000)
        requireNotNull(field).click()
        device.waitForIdle()
        tap("key:切换语言，长按切换输入法")
        assertNotNull(device.waitForFreshObject(By.text("絮雨 · 中文拼音").pkg(pkg), 15_000))
        typeLetters("shi")
        node("candidate_toggle")
        tap("candidate_toggle")
        val drawer = node("candidate_drawer").visibleBounds
        assertTrue("Drawer must fit the screen width",
            drawer.left >= 0 && drawer.right <= device.displayWidth)
        assertTrue("Drawer must fit the screen height",
            drawer.top >= 0 && drawer.bottom <= device.displayHeight)
        freshTree()
        listOf("q", "a", "z", "空格", "符号键盘").forEach {
            assertFalse("Ordinary keys must be replaced by the drawer: $it",
                device.hasObject(By.res("key:$it").pkg(pkg)))
        }
        tap("candidate_toggle")
        node("key:q")
    }

    @Test fun webContentEditableReceivesChineseCandidate() = withImeEditor(EditorFixtureActivity.FIXTURE_WEB) { host ->
        val web = host.findViewById<WebView>(R.id.fixture_web_view)
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
            }
            web.reload()
        }
        assertTrue("Web fixture must load", loaded.await(10, TimeUnit.SECONDS))
        // The contenteditable div renders as the lowest editable node in the page; click it
        // directly so the IME connects to the right element without fragile JS callbacks.
        val edits = device.findObjects(By.clazz("android.widget.EditText").pkg(pkg))
            .filter { !it.visibleBounds.isEmpty }
            .sortedByDescending { it.visibleBounds.bottom }
        require(edits.isNotEmpty()) { "Web fixture must expose editable nodes" }
        edits.first().click()
        device.waitForIdle()
        instrumentation.runOnMainSync {
            host.getSystemService(InputMethodManager::class.java).showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
        }
        assertNotNull("Engine readiness must finish",
            device.waitForFreshObject(By.text(java.util.regex.Pattern.compile("絮雨 · (English|中文拼音)")).pkg(pkg), 120_000))
        if (!device.hasObject(By.text("絮雨 · 中文拼音").pkg(pkg))) {
            tap("key:切换语言，长按切换输入法")
        }
        assertNotNull("Chinese header must appear",
            device.waitForFreshObject(By.text("絮雨 · 中文拼音").pkg(pkg), 15_000))
        typeLetters("nihao")
        val candidate = By.res(java.util.regex.Pattern.compile("candidate:[0-9]+")).pkg(pkg)
        val cell = device.wait(Until.findObject(candidate), 15_000)
        assertNotNull("Candidates must appear for the contenteditable", cell)
        cell.click()
        val deadline = SystemClock.uptimeMillis() + 8_000
        var text = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                web.evaluateJavascript("document.getElementById('editable').innerText;") { result ->
                    text = result.trim().removePrefix("\"").removeSuffix("\"").replace("\\u003d", "=").replace("\\n", "")
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            if (text.isNotEmpty()) break
            SystemClock.sleep(100)
        }
        assertTrue("Contenteditable must receive the selected candidate; text=$text", text.isNotEmpty())
    }
}