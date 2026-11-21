package org.thanatophilia.xyuime.d6fixture

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern

/** Explicit dedicated phases only. Never executed by the ordinary connected test suite. */
@RunWith(AndroidJUnit4::class)
class ReleaseImePhaseTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val device = UiDevice.getInstance(ins)
    private lateinit var host: EditorActivity
    private var savedIme = ""
    private var hardwareIme = ""
    private var restoreSettings = false
    private var enabledOriginally = false
    private val selections = JSONArray()
    private var assertions = 0
    private var committedTargets = 0
    private var previousRecent: List<String>? = null

    companion object {
        private const val PKG = "org.thanatophilia.xyuime"
        private const val IME = "$PKG/$PKG.ime.XyuImeService"
        private const val PHRASE = "你好你好"
        private const val CODE = "nihaonihao"
        private const val CERT = "fa62895fa256fb286acb6546a1663331af42fa08abac4c1ce3fa1645ee90d7d0"
        private val phases = setOf("T0", "T1", "T2", "T3", "T5", "T6", "T7")
    }

    private fun requireState(value: Boolean, code: String) {
        check(value) { "D6_UI_$code" }
        assertions++
    }

    private fun fresh() {
        device.waitForIdle(500)
        if (Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
    }

    private fun node(selector: BySelector, timeout: Long = 10_000): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            fresh()
            device.findObject(selector)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("D6_UI_NODE_NOT_FOUND")
    }

    private fun key(tag: String) = node(By.res(tag).pkg(PKG))
    private fun tap(tag: String) { key(tag).click(); device.waitForIdle(500) }
    private fun type(code: String) { code.forEach { tap("key:$it") } }

    private fun snapshot(): EditorSnapshot {
        var result: EditorSnapshot? = null
        ins.runOnMainSync {
            val editor = host.findViewById<EditText>(R.id.editor)
            val buffer = editor.text
            result = EditorSnapshot(buffer.toString(),
                BaseInputConnection.getComposingSpanStart(buffer),
                BaseInputConnection.getComposingSpanEnd(buffer),
                editor.selectionStart, editor.selectionEnd)
        }
        return requireNotNull(result)
    }

    private fun awaitState(predicate: (EditorSnapshot) -> Boolean): EditorSnapshot {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var current = snapshot()
        while (!predicate(current) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            current = snapshot()
        }
        requireState(predicate(current), "EDITOR_STATE_${current.diagnostic()}")
        return current
    }

    private fun committed(expected: String) {
        awaitState { it.isCommitted(expected) }
        // A transient match followed by a duplicate commit must not count as a pass.
        val deadline = SystemClock.uptimeMillis() + 350
        do {
            requireState(snapshot().isCommitted(expected), "UNSTABLE_COMMIT")
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private fun clearOwnEditor() {
        requireState(snapshot().composingStart == -1 && snapshot().composingEnd == -1, "CLEAR_DURING_COMPOSITION")
        ins.runOnMainSync { host.findViewById<EditText>(R.id.editor).text.clear() }
        committed("")
    }

    private fun ensureMode(chinese: Boolean) {
        val desired = if (chinese) "絮雨 · 中文拼音" else "絮雨 · English"
        val ready = By.text(Pattern.compile("絮雨 · (English|中文拼音)")).pkg(PKG)
        val current = node(ready, 120_000).text
        if (current != desired) tap("key:切换语言，长按切换输入法")
        requireState(node(By.text(desired).pkg(PKG), 120_000).text == desired, "MODE_NOT_READY")
    }

    private fun englishAndChineseSmoke() {
        clearOwnEditor()
        ensureMode(false)
        type("ok")
        committed("ok") // English is direct committed text, not composing.
        clearOwnEditor()
        ensureMode(true)
        // Do not commit 你好 after T1: consecutive 你好 submissions can themselves
        // trigger contextual learning of 你好你好. Use an unrelated fixed smoke code.
        type("zhongguo")
        awaitState { it.isComposing("", "zhongguo") }
        val candidate = key("candidate:0")
        val text = requireNotNull(candidate.text?.takeIf { it.isNotBlank() }) { "D6_UI_CANDIDATE_TEXT_MISSING" }
        requireState(text != PHRASE, "SMOKE_MUST_NOT_LEARN_TARGET")
        candidate.click() // Never retry a candidate click whose outcome is uncertain.
        committed(text)
        clearOwnEditor()
        ensureMode(true)
    }

    private fun observeTarget(learn: Boolean) {
        clearOwnEditor()
        ensureMode(true)
        val prefix = snapshot().text
        type(CODE)
        awaitState { it.isComposing(prefix, CODE) }
        val selector = By.res(Pattern.compile("candidate:[0-9]+")).pkg(PKG).text(PHRASE)
        fresh()
        var candidate = device.findObject(selector)
        if (candidate == null) {
            tap("candidate_toggle")
            node(By.res("candidate_drawer").pkg(PKG))
            val deadline = SystemClock.uptimeMillis() + 15_000
            var pages = 0
            while (candidate == null && pages < 10 && SystemClock.uptimeMillis() < deadline) {
                fresh()
                candidate = device.findObject(selector)
                if (candidate == null) {
                    tap("candidate_tools_page_down")
                    pages++
                }
            }
        }
        requireState(candidate != null, "TARGET_CANDIDATE_MISSING")
        requireState(candidate!!.text == PHRASE, "TARGET_CANDIDATE_TEXT")
        if (learn) {
            selections.put(candidate!!.resourceName)
            candidate!!.click()
            committedTargets++
            committed(prefix + PHRASE)
        } else {
            // Do not click target or Return: remove the composition through actual delete keys.
            fresh()
            if (device.hasObject(By.res("candidate_drawer").pkg(PKG))) tap("candidate_toggle")
            repeat(CODE.length) { tap("key:删除") }
            committed(prefix)
        }
        clearOwnEditor()
    }

    private fun selectTab(tag: String) {
        var count = 0
        while (true) {
            fresh()
            val tab = device.findObject(By.res("symbol_tab:$tag").pkg(PKG))
            if (tab != null && tab.visibleBounds.width() > 12) { tab.click(); break }
            requireState(count++ < 4, "SYMBOL_TAB_MISSING")
            val bounds = key("symbol_tab_bar").visibleBounds
            device.swipe(bounds.right - 12, bounds.centerY(), bounds.left + 12, bounds.centerY(), 25)
        }
        key("symbol_page:$tag")
    }

    private fun symbols(record: Boolean) {
        clearOwnEditor()
        tap("key:符号键盘")
        if (record) {
            selectTab("arrows")
            var expected = ""
            for (index in listOf(0, 1, 0, 3)) {
                val text = mapOf(0 to "↑", 1 to "↓", 3 to "→").getValue(index)
                key("symbol:arrows:$index").click()
                expected += text
                committed(expected)
            }
            // The manual category strip may now need a rightward drag to show Recent.
            val bounds = key("symbol_tab_bar").visibleBounds
            device.swipe(bounds.left + 12, bounds.centerY(), bounds.right - 12, bounds.centerY(), 25)
        }
        selectTab("recent")
        val items = (0..2).map { index ->
            val name = key("symbol:recent:$index").contentDescription
            requireNotNull(name) { "D6_UI_RECENT_LABEL_MISSING" }
        }
        requireState(items == listOf("右箭头", "上箭头", "下箭头"), "RECENT_PREFIX_NOT_PRESERVED")
        previousRecent = items
        tap("key:返回字母键盘")
        // Clear only the editor's own synthetic symbols, never any IME preferences.
        clearOwnEditor()
    }

    private fun verifyRelease(version: Int) {
        val info = context.packageManager.getPackageInfo(PKG, PackageManager.GET_SIGNING_CERTIFICATES)
        requireState(info.longVersionCode == version.toLong(), "INSTALLED_VERSION_MISMATCH")
        requireState(info.versionName == "0.3.$version", "INSTALLED_NAME_MISMATCH")
        val flags = requireNotNull(info.applicationInfo).flags
        requireState(flags and ApplicationInfo.FLAG_DEBUGGABLE == 0, "INSTALLED_DEBUGGABLE")
        requireState(flags and ApplicationInfo.FLAG_TEST_ONLY == 0, "INSTALLED_TEST_ONLY")
        val signatures = requireNotNull(info.signingInfo).apkContentsSigners
        requireState(signatures.size == 1, "INSTALLED_SIGNER_COUNT")
        val digest = MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        requireState(digest == CERT, "INSTALLED_SIGNER_MISMATCH")
    }

    @Test fun explicitReleaseInputPhase() {
        val args = InstrumentationRegistry.getArguments()
        val phase = requireNotNull(args.getString("d6Phase")) { "D6_EXPLICIT_PHASE_REQUIRED" }
        requireState(phase in phases, "UNKNOWN_PHASE")
        val run = requireNotNull(args.getString("d6Run"))
        requireState(run.matches(Regex("[A-Za-z0-9_-]{1,80}")), "BAD_RUN_ID")
        requireState(args.getString("d6Disposable") == "YES", "DISPOSABLE_MARKER_REQUIRED")
        requireState(args.getString("d6Serial")?.matches(Regex("emulator-[0-9]+")) == true, "BAD_SERIAL_ARGUMENT")
        requireState(args.getString("d6G1") == "PASS", "G1_REQUIRED")
        requireState(Build.VERSION.SDK_INT == 36 && Build.SUPPORTED_ABIS.first() == "x86_64", "WRONG_DEVICE")
        requireState(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1", "NOT_QEMU")
        val expectedVersion = if (phase in setOf("T0", "T1", "T2", "T3")) 145 else 149
        verifyRelease(expectedVersion)
        val networks = context.getSystemService(ConnectivityManager::class.java)
        requireState(networks.allNetworks.isEmpty() && networks.activeNetwork == null, "DEVICE_NOT_OFFLINE")
        requireState(device.executeShellCommand("settings get global wifi_on").trim() == "0", "WIFI_NOT_DISABLED")
        requireState(device.executeShellCommand("settings get global mobile_data").trim() == "0", "MOBILE_DATA_NOT_DISABLED")
        val output = File(context.filesDir, "d6-fixture-evidence/$run-$phase.json")
        requireState(!output.exists(), "STALE_PHASE_REPORT")
        savedIme = device.executeShellCommand("settings get secure default_input_method").trim()
        requireState(savedIme.contains('/') && savedIme != IME &&
            savedIme.matches(Regex("[A-Za-z0-9_./]+")), "SAFE_FALLBACK_IME_REQUIRED")
        hardwareIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        requireState(hardwareIme in setOf("0", "1", "null", ""), "BAD_HARDWARE_IME_SETTING")
        enabledOriginally = device.executeShellCommand("ime list -s").lineSequence().any { it.trim() == IME }
        try {
            restoreSettings = true
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            device.executeShellCommand("ime enable $IME")
            device.executeShellCommand("ime set $IME")
            requireState(device.executeShellCommand("settings get secure default_input_method").trim() == IME,
                "IME_SELECTION_FAILED")
            host = ins.startActivitySync(Intent(context, EditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as EditorActivity
            node(By.res(context.packageName, "editor").pkg(context.packageName)).click()
            ins.runOnMainSync {
                host.getSystemService(InputMethodManager::class.java).showSoftInput(
                    host.findViewById<EditText>(R.id.editor), InputMethodManager.SHOW_IMPLICIT)
            }
            englishAndChineseSmoke()
            when (phase) {
                "T0" -> Unit // Never select target before negative-control checkpoint.
                "T1" -> repeat(3) { observeTarget(learn = true) }
                "T2" -> { observeTarget(learn = false); symbols(record = true) }
                else -> { observeTarget(learn = false); symbols(record = false) }
            }
            requireState(committedTargets == if (phase == "T1") 3 else 0, "TARGET_COMMIT_COUNT")
            val final = snapshot()
            requireState(final.composingStart == -1 && final.composingEnd == -1, "LIVE_COMPOSITION_AT_END")
            val result = JSONObject().put("result", "PASS").put("run", run).put("phase", phase)
                .put("installed_version", expectedVersion).put("assertions", assertions)
                .put("target_ui_commits", committedTargets).put("selected_candidate_ids", selections)
                .put("source", "real release IME UI only").put("editor_empty", final.text.isEmpty())
                .put("pid", android.os.Process.myPid())
            previousRecent?.let { result.put("recent_prefix_labels", JSONArray(it)) }
            // Emit only after restoration has succeeded.
            restore()
            output.parentFile!!.mkdirs()
            output.writeText(result.toString(2))
        } finally { restore() }
    }

    private fun restore() {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) { failure = failure ?: error } }
        if (::host.isInitialized) attempt { ins.runOnMainSync { host.finish() } }
        if (restoreSettings) {
            attempt {
                device.executeShellCommand("ime set $savedIme")
                requireState(device.executeShellCommand("settings get secure default_input_method").trim() == savedIme,
                    "FALLBACK_IME_RESTORE_FAILED")
            }
            attempt {
                if (hardwareIme == "null" || hardwareIme.isEmpty())
                    device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
                else device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $hardwareIme")
            }
            attempt { if (!enabledOriginally) device.executeShellCommand("ime disable $IME") }
            restoreSettings = false
        }
        failure?.let { throw IllegalStateException("D6_UI_RESTORE_FAILED", it) }
    }
}
