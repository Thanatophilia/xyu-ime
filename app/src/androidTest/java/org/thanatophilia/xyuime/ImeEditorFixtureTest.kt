package org.thanatophilia.xyuime

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** D1 editor fixtures: composition must not leak between fields; web editors accept offline input. */
@RunWith(AndroidJUnit4::class)
class ImeEditorFixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = context.packageName

    private fun fieldText(host: Activity, viewId: Int): String {
        var text = ""
        instrumentation.runOnMainSync {
            text = host.findViewById<EditText>(viewId).text.toString()
        }
        return text
    }

    private fun composingOf(host: Activity, viewId: Int): String? {
        var composing: String? = null
        instrumentation.runOnMainSync {
            val text = host.findViewById<EditText>(viewId).text
            val start = BaseInputConnection.getComposingSpanStart(text)
            val end = BaseInputConnection.getComposingSpanEnd(text)
            composing = if (start >= 0 && end >= start) text.subSequence(start, end).toString() else null
        }
        return composing
    }

    private fun await(host: Activity, viewId: Int, message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("$message; text=${fieldText(host, viewId)} composing=${composingOf(host, viewId)}", predicate())
    }

    private fun tap(tag: String) {
        device.waitForFreshObject(By.res(tag).pkg(pkg), 10_000)?.click()
        device.waitForIdle()
    }

    private fun typeLetters(text: String) { text.forEach { tap("key:$it") } }

    private fun withChineseFixture(fixture: String, webFocus: Boolean = false, test: (Activity) -> Unit) {
        val id = "$pkg/org.thanatophilia.xyuime.ime.XyuImeService"
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val hardware = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        var activity: Activity? = null
        try {
            device.executeShellCommand("ime enable $id")
            device.executeShellCommand("ime set $id")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            val host = instrumentation.startActivitySync(EditorFixtureActivity.intent(context, fixture)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity = host
            if (webFocus) {
                val web = host.findViewById<WebView>(R.id.fixture_web_view)
                val loaded = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    web.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
                    }
                    web.reload()
                }
                assertTrue("Web fixture must load", loaded.await(10, TimeUnit.SECONDS))
                // The textarea is the first editable node in the local page. A real tap on it
                // establishes the editor connection and opens the IME (JS focus alone does not).
                val editable = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(pkg)), 8_000)
                requireNotNull(editable) { "Web fixture must expose its editable field" }.click()
            } else {
                requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(pkg)), 8_000)).click()
            }
            instrumentation.runOnMainSync {
                host.getSystemService(InputMethodManager::class.java).showSoftInput(
                    if (webFocus) host.findViewById<WebView>(R.id.fixture_web_view) else host.currentFocus,
                    InputMethodManager.SHOW_IMPLICIT)
            }
            val ready = By.text(java.util.regex.Pattern.compile("絮雨 · (English|中文拼音)")).pkg(pkg)
            assertNotNull("Rime deployment must finish", device.waitForFreshObject(ready, 120_000))
            if (!device.hasObject(By.text("絮雨 · 中文拼音").pkg(pkg))) {
                tap("key:切换语言，长按切换输入法")
            }
            assertNotNull("Chinese mode not reached",
                device.waitForFreshObject(By.text("絮雨 · 中文拼音").pkg(pkg), 15_000))
            test(host)
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

    @Test fun pendingCompositionDoesNotLeakIntoSecondField() = withChineseFixture(EditorFixtureActivity.FIXTURE_SWITCH) { host ->
        typeLetters("nihao")
        await(host, R.id.fixture_editor_a, "nihao must be composing in field A") {
            composingOf(host, R.id.fixture_editor_a)?.replace(" ", "") == "nihao"
        }
        // Focus moves to field B; the IME must finish/reset the old composition.
        val b = device.waitForFreshObject(By.desc("Field B").pkg(pkg), 8_000)
            ?: device.findObjects(By.clazz("android.widget.EditText").pkg(pkg))[1]
        b.click()
        device.waitForIdle()
        // Switch to English and type a literal so the second field gets committed text.
        tap("key:切换语言，长按切换输入法")
        assertNotNull(device.waitForFreshObject(By.text("絮雨 · English").pkg(pkg), 10_000))
        typeLetters("ok")
        await(host, R.id.fixture_editor_b, "Field B must receive committed literal text") {
            fieldText(host, R.id.fixture_editor_b) == "ok"
        }
        // Platform behaviour: the unfinished pinyin stays in the ORIGINAL field as raw text
        // (like mainstream IMEs). The leak we forbid is text arriving in the NEW field.
        assertEquals("Raw preedit must remain in field A, not vanish",
            "ni hao", fieldText(host, R.id.fixture_editor_a))
    }

    @Test fun webTextareaAcceptsOfflinePinyinSelection() = withChineseFixture(EditorFixtureActivity.FIXTURE_WEB, webFocus = true) { host ->
        val web = host.findViewById<WebView>(R.id.fixture_web_view)
        instrumentation.runOnMainSync {
            host.getSystemService(InputMethodManager::class.java)
                .showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
        }
        device.waitForIdle()
        typeLetters("nihao")
        // Await a visible candidate, then commit the highlighted candidate via the candidate strip.
        val candidate = By.res(java.util.regex.Pattern.compile("candidate:[0-9]+")).pkg(pkg)
        val cell = device.wait(Until.findObject(candidate), 15_000)
        assertNotNull("Candidates must appear for web input", cell)
        cell.click()
        val deadline = SystemClock.uptimeMillis() + 8_000
        var value = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                web.evaluateJavascript("(document.getElementById('area').value || document.getElementById('input').value);") { result ->
                    value = result.trim().removePrefix("\"").removeSuffix("\"").replace("\\u003d", "=")
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            if (value.isNotEmpty()) break
            SystemClock.sleep(100)
        }
        assertTrue("Web editor must receive the selected candidate; value=$value", value.isNotEmpty())
    }
}