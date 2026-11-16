package org.thanatophilia.xyuime

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import kotlin.math.roundToInt
import android.view.WindowInsets
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
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil

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
        device.freshImeTree()
    }

    private fun node(tag: String): UiObject2 {
        freshTree()
        val result = device.waitForFreshObject(By.res(tag).pkg(pkg), 15_000)
        if (result == null && tag == "floating_preedit") floatingScreenshot("phase3-floating-missing")
        return requireNotNull(result) { tag }
    }

    private fun tap(tag: String) { node(tag).click(); device.waitForIdle() }
    private fun typeLetters(text: String) { text.forEach { tap("key:$it") } }
    private fun enter(label: String) = tap("key:回车：$label")
    private fun noCandidates() {
        freshTree()
        assertTrue("Candidate strip must disappear after commit",
            device.waitForFreshGone(By.res("candidate_toggle").pkg(pkg), 5_000))
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
            val ready = By.text(java.util.regex.Pattern.compile("絮雨 · (English|中文拼音)")).pkg(pkg)
            assertNotNull("Rime deployment must finish", device.waitForFreshObject(ready, 120_000))
            if (!device.hasObject(By.text("絮雨 · 中文拼音").pkg(pkg))) {
                tap("key:切换语言，长按切换输入法")
            }
            val chineseReady = device.waitForFreshObject(By.text("絮雨 · 中文拼音").pkg(pkg), 15_000) != null
            assertTrue("Chinese mode not reached; headers=" + device.findObjects(By.textStartsWith("絮雨 ·").pkg(pkg)).map { it.text }, chineseReady)
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

    /** All coordinates come from the app's real view/window, on its UI thread. */
    private data class EditorGeometry(
        val bounds: Rect,
        val visibleFrame: Rect,
        val imeBottom: Int,
        val imeVisible: Boolean,
        val editorFocused: Boolean,
        val windowFocused: Boolean,
    )

    private fun editorGeometry(): EditorGeometry {
        check(Build.VERSION.SDK_INT >= 30) { "Floating-window regressions require API 30+ (CI: 36)" }
        var result: EditorGeometry? = null
        instrumentation.runOnMainSync {
            val position = IntArray(2)
            field.getLocationOnScreen(position)
            val frame = Rect()
            field.getWindowVisibleDisplayFrame(frame)
            // Do not infer this from keyboard height: a popup must not enlarge the IME inset.
            val insets = requireNotNull(field.rootWindowInsets) { "Editor has no rootWindowInsets" }
            result = EditorGeometry(
                Rect(position[0], position[1], position[0] + field.width, position[1] + field.height),
                frame, insets.getInsets(WindowInsets.Type.ime()).bottom,
                insets.isVisible(WindowInsets.Type.ime()), field.hasFocus(), field.hasWindowFocus(),
            )
        }
        return requireNotNull(result)
    }

    private fun settledEditorGeometry(imeVisible: Boolean = true): EditorGeometry {
        val deadline = SystemClock.uptimeMillis() + 8_000
        var previous: EditorGeometry? = null
        var stableSince = SystemClock.uptimeMillis()
        do {
            val current = editorGeometry()
            val now = SystemClock.uptimeMillis()
            if (current != previous) stableSince = now
            val ready = current.imeVisible == imeVisible &&
                (if (imeVisible) current.imeBottom > 0 else current.imeBottom == 0) &&
                current.editorFocused && current.windowFocused
            if (ready && now - stableSince >= 300) return current
            previous = current
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Editor/IME geometry did not settle (visible=$imeVisible): $previous")
    }

    private data class CodeWindow(val bounds: Rect, val texts: List<String>, val independent: Boolean)

    /** Non-touchable windows are intentionally absent from Android's accessibility window list.
     * Inspect the attached real View/Compose roots instead; do not change product touch flags.
     */
    private fun floatingWindow(): CodeWindow? {
        var result: CodeWindow? = null
        instrumentation.runOnMainSync {
            fun visit(view: View) {
                if (!view.isAttachedToWindow || !view.isShown) return
                if (view is ViewRootForTest) {
                    val nodes = view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                    val code = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "floating_preedit" }
                    if (code != null) {
                        val position = code.positionOnScreen
                        val size = code.size
                        val bounds = Rect(position.x.roundToInt(), position.y.roundToInt(),
                            position.x.roundToInt() + size.width, position.y.roundToInt() + size.height)
                        result = CodeWindow(bounds,
                            code.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty(),
                            nodes.none { it.config.getOrNull(SemanticsProperties.TestTag) in
                                listOf("keyboard_surface", "candidate_strip") })
                    }
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return result
    }

    private fun noFloatingPreedit() {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (floatingWindow() != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertNull("Floating preedit window must actually detach", floatingWindow())
    }

    private fun assertFloatingPreedit(code: String): Rect {
        val surface = node("keyboard_surface").visibleBounds
        val deadline = SystemClock.uptimeMillis() + 8_000
        var window = floatingWindow()
        while ((window == null || window.bounds.isEmpty) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            window = floatingWindow()
        }
        if (window == null) floatingScreenshot("phase3-floating-missing")
        val codeWindow = requireNotNull(window) { "Attached floating encoding window not found" }
        val popup = codeWindow.bounds
        // The tag can describe Text inside the bubble's 10dp horizontal content padding.
        val insetTolerance = ceil(12 * context.resources.displayMetrics.density).toInt()
        assertFalse("Popup must expose nonempty screen bounds: $popup", popup.isEmpty)
        assertTrue("Popup must be above the keyboard: popup=$popup, surface=$surface",
            popup.bottom <= surface.top)
        assertTrue("Popup must align to the physical left: popup=$popup, surface=$surface",
            popup.left >= surface.left - 1 && popup.left <= surface.left + insetTolerance)
        assertTrue("Popup must fit the keyboard width: popup=$popup, surface=$surface",
            popup.right <= surface.right + 1)
        assertTrue("Popup must remain on screen: $popup", popup.top >= 0)
        val texts = codeWindow.texts
        assertTrue("Popup must expose the current code '$code'; actual=$texts",
            texts.any { it.replace(" ", "") == code.replace(" ", "") })
        assertTrue("Encoding must belong to a separate Compose/window root", codeWindow.independent)
        val strip = node("candidate_strip")
        assertFalse("Candidate strip must not contain the floating code",
            strip.hasObject(By.res("floating_preedit")))
        assertFalse("Candidate strip must contain candidates/toggle, not an inline preedit",
            strip.hasObject(By.desc("拼音预编辑")))
        assertTrue("Candidate row must still contain a selectable candidate", strip.hasObject(By.res("candidate:0")))
        assertTrue("Candidate row must still contain its toggle", strip.hasObject(By.res("candidate_toggle")))
        return Rect(popup)
    }

    private fun floatingScreenshot(name: String) {
        val directory = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES))
        assertTrue("Screenshot directory must exist", directory.isDirectory || directory.mkdirs())
        freshTree()
        val hierarchy = File(directory, "$name.xml")
        val screenshot = File(directory, "$name.png")
        device.dumpWindowHierarchy(hierarchy)
        assertTrue("Screenshot must be saved: $name.png", device.takeScreenshot(screenshot))
        // Existing emulator-smoke.sh pulls this directory, including successful tests.
        val review = "/sdcard/Download/xyu-ime-review"
        device.executeShellCommand("mkdir -p $review")
        device.executeShellCommand("cp ${hierarchy.absolutePath} $review/")
        device.executeShellCommand("cp ${screenshot.absolutePath} $review/")
        // UiAutomation executes an argv command, not a shell script (&& is not interpreted).
        val windows = File(directory, "$name-windows.txt")
        windows.writeText(device.executeShellCommand("dumpsys window windows"))
        device.executeShellCommand("cp ${windows.absolutePath} $review/")
    }

    @Test fun floatingPreeditStaysAboveKeyboardWithoutResizingAppOrKeys() = withChineseEditor {
        node("keyboard_surface")
        noFloatingPreedit()
        val editorBefore = settledEditorGeometry()
        val surfaceBefore = Rect(node("keyboard_surface").visibleBounds)
        val qBefore = Rect(node("key:q").visibleBounds)
        val spaceBefore = Rect(node("key:空格").visibleBounds)
        floatingScreenshot("phase3-floating-preedit-before")
        typeLetters("n")
        awaitSnapshot("n must start a real composition") { it.composing?.replace(" ", "") == "n" }
        assertFloatingPreedit("n")
        val stripBefore = Rect(node("candidate_strip").visibleBounds)
        typeLetters("ihao")
        val composing = awaitSnapshot("nihao must be composing") {
            it.composing?.replace(" ", "") == "nihao" && it.actions.isEmpty()
        }
        assertFloatingPreedit("nihao")
        floatingScreenshot("phase3-floating-preedit")
        assertEquals("Popup must not change editor bounds, visible frame, IME inset or focus",
            editorBefore, settledEditorGeometry())
        assertEquals("Keyboard surface must not move or resize", surfaceBefore, node("keyboard_surface").visibleBounds)
        assertEquals("Letter key must not move or shrink", qBefore, node("key:q").visibleBounds)
        assertEquals("Space key must not move or shrink", spaceBefore, node("key:空格").visibleBounds)
        assertEquals("Candidate row must not move or grow with code", stripBefore, node("candidate_strip").visibleBounds)
        tap("candidate_toggle")
        node("candidate_drawer")
        assertFloatingPreedit("nihao")
        floatingScreenshot("phase3-floating-preedit-drawer")
        assertEquals("Opening drawer must preserve the composition", composing, snapshot())
        assertEquals("Drawer must preserve editor/window geometry", editorBefore, settledEditorGeometry())
        assertEquals("Drawer must preserve keyboard bounds", surfaceBefore, node("keyboard_surface").visibleBounds)
        assertEquals("Drawer must preserve candidate-row bounds", stripBefore, node("candidate_strip").visibleBounds)
        tap("candidate_toggle")
        node("key:q")
        assertEquals("Closing drawer must restore letter bounds", qBefore, node("key:q").visibleBounds)
        assertEquals("Closing drawer must restore space bounds", spaceBefore, node("key:空格").visibleBounds)
    }

    @Test fun floatingPreeditDisappearsAfterRawEnterAndCandidateSelection() = withChineseEditor {
        val editorBefore = settledEditorGeometry()
        typeLetters("nihao")
        awaitSnapshot("nihao must be composing before raw Enter") {
            it.composing?.replace(" ", "") == "nihao" && it.actions.isEmpty()
        }
        assertFloatingPreedit("nihao")
        enter("完成")
        awaitSnapshot("Raw Enter must commit code without performing the editor action") {
            it.text == "nihao" && it.composing == null && it.actions.isEmpty()
        }
        noFloatingPreedit()
        noCandidates()
        node("key:q")
        assertEquals("Raw commit must leave editor/window geometry unchanged", editorBefore, settledEditorGeometry())
        floatingScreenshot("phase3-floating-preedit-after-enter")
        typeLetters("nihao")
        awaitSnapshot("Second composition must be visible") { it.composing?.replace(" ", "") == "nihao" }
        assertFloatingPreedit("nihao")
        val candidateNode = node("candidate:0")
        val candidate = requireNotNull(candidateNode.text?.takeIf { it.isNotBlank() }
            ?: candidateNode.findObject(By.text(java.util.regex.Pattern.compile(".+")))?.text) {
            "Candidate must expose its commit text in its cell or text child"
        }
        assertTrue("Candidate must not be empty", candidate.isNotBlank())
        tap("candidate:0")
        awaitSnapshot("Selection must commit the displayed candidate, preserving prior raw text") {
            it.text == "nihao$candidate" && it.composing == null && it.actions.isEmpty()
        }
        noFloatingPreedit()
        noCandidates()
        node("key:q")
        assertEquals("Candidate commit must leave editor/window geometry unchanged", editorBefore, settledEditorGeometry())
    }

    @Test fun floatingPreeditDoesNotConsumeFirstBack() = withChineseEditor {
        settledEditorGeometry()
        typeLetters("nihao")
        val before = awaitSnapshot("nihao must be composing before Back") {
            it.composing?.replace(" ", "") == "nihao" && it.actions.isEmpty()
        }
        assertFloatingPreedit("nihao")
        // UiDevice.pressBack reports whether a new accessibility window was observed, not
        // whether the key reached the IME (IME dismissal need not emit that event).
        // Inject exactly one Back; prove its effect using real window/inset assertions below.
        device.executeShellCommand("input keyevent KEYCODE_BACK")
        assertTrue("First Back must close the IME, not just its nonfocusable popup",
            device.waitForFreshGone(By.res("keyboard_surface").pkg(pkg), 8_000))
        noFloatingPreedit()
        val hidden = settledEditorGeometry(imeVisible = false)
        assertEquals("Hidden IME must report zero actual inset", 0, hidden.imeBottom)
        val after = snapshot()
        assertEquals("Back must not change editor text", before.text, after.text)
        assertTrue("Back must not invoke an editor action", after.actions.isEmpty())
    }

    @Test fun floatingPreeditPassesTouchesToTheEditorUnderneath() = withChineseEditor {
        val editorBefore = settledEditorGeometry()
        typeLetters("nihao")
        val before = awaitSnapshot("nihao must be composing before passthrough tap") {
            it.composing?.replace(" ", "") == "nihao" && it.actions.isEmpty()
        }
        val popup = assertFloatingPreedit("nihao")
        val x = popup.centerX()
        val y = popup.centerY()
        assertTrue("Synthetic editor must actually lie beneath popup center: editor=$editorBefore, popup=$popup",
            editorBefore.bounds.contains(x, y) && editorBefore.visibleFrame.contains(x, y))
        // This records real window-dispatched events. Consuming them protects the composing
        // span/caret from EditText's ordinary click behaviour, so passthrough is isolated.
        val events = mutableListOf<Triple<Int, Float, Float>>() // UI-thread access only.
        var selectionBefore = 0 to 0
        instrumentation.runOnMainSync {
            selectionBefore = field.selectionStart to field.selectionEnd
            field.setOnTouchListener { _, event ->
                events.add(Triple(event.actionMasked, event.rawX, event.rawY))
                true
            }
        }
        try {
            assertTrue("Popup-center tap must be injected", device.click(x, y))
            device.waitForIdle()
            instrumentation.waitForIdleSync()
            var received: List<Triple<Int, Float, Float>> = emptyList()
            var selectionAfter = 0 to 0
            instrumentation.runOnMainSync {
                received = events.toList()
                selectionAfter = field.selectionStart to field.selectionEnd
            }
            assertEquals("Editor beneath popup must receive a complete tap", listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP),
                received.filter { it.first != MotionEvent.ACTION_MOVE }.map { it.first })
            assertTrue("Received touch coordinates must be the popup center: $received",
                received.all { abs(it.second - x) <= 2f && abs(it.third - y) <= 2f })
            assertEquals("Consumed tap must not move the caret", selectionBefore, selectionAfter)
            assertEquals("Passthrough tap must not commit/change composition or invoke an action", before, snapshot())
            assertFloatingPreedit("nihao")
            assertEquals("Passthrough tap must preserve editor/window geometry", editorBefore, settledEditorGeometry())
        } finally {
            instrumentation.runOnMainSync { field.setOnTouchListener(null) }
        }
    }

    @Test fun candidateStripDoesNotResizeLetterKeys() = withChineseEditor {
        // The strip is reserved above the key area. Composition may only change its contents;
        // it must not move or shrink the letters or the fixed bottom row.
        val qBefore = node("key:q").visibleBounds
        val spaceBefore = node("key:空格").visibleBounds
        typeLetters("nihao")
        awaitSnapshot("nihao must be composing") { it.composing?.replace(" ", "") == "nihao" }
        val qAfter = node("key:q").visibleBounds
        val spaceAfter = node("key:空格").visibleBounds
        assertEquals("Letter key must not move or shrink when candidates appear", qBefore, qAfter)
        assertEquals("Bottom row must not move or shrink when candidates appear", spaceBefore, spaceAfter)
        val toggle = node("candidate_toggle").visibleBounds
        assertTrue("Candidate strip must sit above the letter keys", toggle.bottom <= qAfter.top)
        assertTrue("Candidate strip must sit above the bottom row", toggle.bottom <= spaceAfter.top)
    }
}
