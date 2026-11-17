package org.thanatophilia.xyuime

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.thanatophilia.xyuime.ui.KeyboardAction
import org.thanatophilia.xyuime.ui.KeyboardUiState
import org.thanatophilia.xyuime.ui.XyuKeyboard
import java.io.File
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class StripCandidate(val index: Int, val text: String, val bounds: Rect)

internal data class CandidateStripSnapshot(val viewport: Rect, val items: List<StripCandidate>) {
    // One physical pixel accommodates screen-coordinate rounding, not an entire clipped cell.
    val intersecting get() = items.filter {
        it.bounds.right > viewport.left + 1 && it.bounds.left < viewport.right - 1 &&
            it.bounds.bottom > viewport.top + 1 && it.bounds.top < viewport.bottom - 1
    }.sortedBy { it.index }
    val fullyVisible get() = intersecting.filter {
        it.bounds.left >= viewport.left - 1 && it.bounds.right <= viewport.right + 1 &&
            it.bounds.top >= viewport.top - 1 && it.bounds.bottom <= viewport.bottom + 1
    }
    val continuationStart get() = fullyVisible.maxOfOrNull { it.index }?.plus(1)
        ?: intersecting.first().index
    val trailingPartial get() = intersecting.firstOrNull {
        it.index == continuationStart && it.bounds.right > viewport.right + 1
    }
}

/** Independent screen geometry oracle; never reads LazyListState or production cutoff helpers. */
internal fun readCandidateStrip(
    instrumentation: Instrumentation, device: UiDevice, packageName: String,
): CandidateStripSnapshot {
    device.freshImeTree()
    val viewport = Rect(requireNotNull(device.waitForFreshObject(
        By.res("candidate_strip_items").pkg(packageName), 8_000
    )) { "Missing candidate-only strip viewport" }.visibleBounds)
    val items = mutableListOf<StripCandidate>()
    instrumentation.runOnMainSync {
        fun visit(view: View) {
            if (!view.isAttachedToWindow || !view.isShown) return
            if (view is ViewRootForTest) {
                val nodes = view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                val strip = nodes.firstOrNull {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "candidate_strip_items"
                }
                if (strip != null) {
                    fun collect(node: SemanticsNode) {
                        // Lazy layouts retain prefetched/reusable nodes with old coordinates.
                        // Only attached and actually placed nodes belong to the current frame.
                        val layout = node.layoutInfo
                        if (!layout.isAttached || !layout.isPlaced || layout.isDeactivated) return
                        val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
                        val index = tag.takeIf { it.startsWith("candidate:") }
                            ?.substringAfter(':')?.toIntOrNull()
                        if (index != null) {
                            val position = node.positionOnScreen
                            val size = node.size
                            // UiObject2.visibleBounds is clipped and cannot establish full visibility.
                            val left = position.x.roundToInt()
                            val top = position.y.roundToInt()
                            items += StripCandidate(index,
                                node.config.getOrNull(SemanticsProperties.Text)
                                    .orEmpty().joinToString("") { it.text },
                                Rect(left, top, left + size.width, top + size.height))
                        }
                        node.children.forEach(::collect)
                    }
                    collect(strip)
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
    }
    return CandidateStripSnapshot(viewport, items.sortedBy { it.index })
}

/** Synthetic Activity fixture only: no native engine, dictionary, selected IME, or editor setup. */
@RunWith(AndroidJUnit4::class)
class ImeCandidateContinuationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val candidateSelector = By.res(Pattern.compile("candidate:[0-9]+"))
    private val words = listOf("同文", "春风雨", "同文", "春风化雨", "雨", "风雨同舟行", "同文", "春")
    private fun candidates(count: Int = 240, offset: Int = 0) = List(count) { words[(it + offset) % words.size] }

    private inner class Fixture(initial: List<String>) {
        var state by mutableStateOf(KeyboardUiState(chinese = true, preedit = "chun feng",
            candidates = initial, candidateComments = List(initial.size) { "chun" },
            highlightedCandidate = -1, candidateSession = 401, candidateRevision = 41L))
        val actions = mutableListOf<KeyboardAction>() // Main-thread access only.
        fun update(transform: (KeyboardUiState) -> KeyboardUiState) {
            instrumentation.runOnMainSync { state = transform(state) }
            instrumentation.waitForIdleSync()
        }
        fun selections(): List<KeyboardAction.SelectCandidate> {
            var result = emptyList<KeyboardAction.SelectCandidate>()
            instrumentation.runOnMainSync { result = actions.filterIsInstance<KeyboardAction.SelectCandidate>() }
            return result
        }
    }

    private data class Cell(val index: Int, val text: String, val bounds: Rect)
    private data class Drawer(val viewport: Rect, val cells: List<Cell>)

    private fun node(tag: String): UiObject2 = requireNotNull(
        device.waitForFreshObject(By.res(tag).pkg(context.packageName), 8_000)
    ) { "Missing $tag" }

    private fun <T> settled(message: String, read: () -> T, ready: (T) -> Boolean = { true }): T {
        val deadline = SystemClock.uptimeMillis() + 8_000
        var previous: T? = null
        var stableSince = SystemClock.uptimeMillis()
        do {
            val current = read()
            val now = SystemClock.uptimeMillis()
            if (current != previous) stableSince = now
            if (ready(current) && now - stableSince >= 350) return current
            previous = current
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("$message did not settle: $previous")
    }

    private fun strip() = settled("Strip geometry", {
        readCandidateStrip(instrumentation, device, context.packageName)
    }, { it.intersecting.isNotEmpty() })

    private fun readDrawer(): Drawer {
        device.freshImeTree()
        val grid = node("candidate_grid")
        val viewport = Rect(grid.visibleBounds)
        val cells = grid.findObjects(candidateSelector).map { cell ->
            Cell(requireNotNull(cell.resourceName).substringAfterLast(':').toInt(),
                requireNotNull(cell.text) { "Candidate must expose its original text" }, Rect(cell.visibleBounds))
        }.filter { !it.bounds.isEmpty && Rect.intersects(viewport, it.bounds) }
            .sortedWith(compareBy<Cell> { it.bounds.top }.thenBy { it.bounds.left })
        return Drawer(viewport, cells)
    }

    private fun drawer() = settled("Drawer geometry", ::readDrawer, { it.cells.isNotEmpty() })

    private fun assertSuffix(drawer: Drawer, source: List<String>, start: Int) {
        assertTrue("Drawer must contain candidates after raw cutoff $start", drawer.cells.isNotEmpty())
        drawer.cells.forEach {
            assertTrue("No earlier strip candidate may repeat in drawer: $it, cutoff=$start", it.index >= start)
            assertTrue("Raw candidate index must remain in source range: $it", it.index in source.indices)
            assertEquals("Original text must stay mapped to raw ID ${it.index}", source[it.index], it.text)
        }
        val indices = drawer.cells.map { it.index }
        assertEquals("All-mode drawer must preserve contiguous global IDs, including duplicate texts",
            (indices.first()..indices.last()).toList(), indices)
    }

    private fun assertStartsAt(geometry: CandidateStripSnapshot, source: List<String>): Drawer {
        val start = geometry.continuationStart
        val result = try {
            settled("Drawer starting at raw cutoff $start", ::readDrawer,
                { it.cells.firstOrNull()?.index == start })
        } catch (failure: Throwable) {
            val actual = strip()
            android.util.Log.e("XyuContinuationTest", "expectedStrip=$geometry; actualStrip=$actual")
            screenshot("phase3-candidate-continuation-mismatch")
            throw failure
        }
        assertSuffix(result, source, start)
        assertEquals("First drawer cell must immediately follow last FULLY visible strip cell",
            start, result.cells.first().index)
        assertTrue("No fully displayed strip ID may reappear",
            result.cells.none { cell -> geometry.fullyVisible.any { it.index == cell.index } })
        assertTrue("Continuation must start at top of grid", abs(result.cells.first().bounds.top - result.viewport.top) <= 1)
        geometry.trailingPartial?.let {
            assertEquals("Clipped trailing strip candidate must be the FIRST drawer item", it.index, result.cells.first().index)
        }
        return result
    }

    private fun toggle() {
        node("candidate_toggle").click()
        instrumentation.waitForIdleSync()
    }

    private fun assertDrawerClosed() {
        assertTrue("Horizontal strip drag must finish the drawer's closing animation",
            device.waitForFreshGone(By.res("candidate_grid").pkg(context.packageName), 5_000))
        assertNotNull("Closing must restore the ordinary keyboard", node("key:q"))
    }

    private fun swipeStrip(forward: Boolean = true): CandidateStripSnapshot {
        val bounds = strip().viewport
        val left = bounds.left + bounds.width() / 5
        val right = bounds.left + bounds.width() * 4 / 5
        assertTrue("Horizontal candidate-only drag must be injected", device.swipe(
            if (forward) right else left, bounds.centerY(),
            if (forward) left else right, bounds.centerY(), 80))
        return strip()
    }

    private fun swipeDrawer(): Drawer {
        val bounds = drawer().viewport
        assertTrue("Vertical grid drag must be injected", device.swipe(bounds.centerX(),
            bounds.top + bounds.height() * 3 / 4, bounds.centerX(),
            bounds.top + bounds.height() / 4, 80))
        return drawer()
    }

    private fun withKeyboard(source: List<String> = candidates(), test: (Fixture) -> Unit) {
        val emulator = Build.HARDWARE in listOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
        assumeTrue("Synthetic screen fixture is emulator-only", emulator)
        val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val fixture = Fixture(source)
        try {
            instrumentation.runOnMainSync {
                host.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                host.currentFocus?.clearFocus()
                host.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
                if (Build.VERSION.SDK_INT >= 30) host.window.insetsController?.hide(WindowInsets.Type.ime())
                host.setContent {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        XyuKeyboard(state = fixture.state, onAction = { action ->
                            fixture.actions += action
                            when (action) {
                                KeyboardAction.ToggleCandidates -> fixture.state = fixture.state.copy(
                                    candidatesExpanded = !fixture.state.candidatesExpanded)
                                is KeyboardAction.CollapseCandidates -> if (action.revision == fixture.state.candidateRevision)
                                    fixture.state = fixture.state.copy(candidatesExpanded = false)
                                else -> Unit
                            }
                        })
                    }
                }
            }
            instrumentation.waitForIdleSync()
            strip()
            var imeVisible = false
            instrumentation.runOnMainSync {
                if (Build.VERSION.SDK_INT >= 30) imeVisible =
                    host.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            }
            assertFalse("System IME must be hidden for the Activity fixture", imeVisible)
            test(fixture)
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun screenshot(name: String) {
        val directory = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES))
        assertTrue("Screenshot directory", directory.isDirectory || directory.mkdirs())
        val image = File(directory, "$name.png")
        val hierarchy = File(directory, "$name.xml")
        device.freshImeTree()
        assertTrue("Save $name screenshot", device.takeScreenshot(image))
        device.dumpWindowHierarchy(hierarchy)
        val review = "/sdcard/Download/xyu-ime-review"
        device.executeShellCommand("mkdir -p $review")
        device.executeShellCommand("cp ${image.absolutePath} $review/")
        device.executeShellCommand("cp ${hierarchy.absolutePath} $review/")
    }

    @Test fun partiallyClippedTrailingItemStartsDrawerAndDuplicateTextsKeepRawSelectionIds() {
        val source = listOf("同文", "同文", "春风化雨".repeat(30)) + candidates()
        withKeyboard(source) { fixture ->
            val geometry = strip()
            assertTrue("Fixture must show complete prefix candidates", geometry.fullyVisible.isNotEmpty())
            assertTrue("Candidate-only viewport must exclude expand toggle",
                geometry.viewport.right <= node("candidate_toggle").visibleBounds.left + 1)
            assertTrue("Existing header must still contain candidate-only viewport",
                node("candidate_strip").visibleBounds.contains(geometry.viewport))
            val partial = requireNotNull(geometry.trailingPartial) { "Fixture must end in a clipped candidate: $geometry" }
            assertTrue("Full Compose bounds must extend past the strip viewport", partial.bounds.right > geometry.viewport.right + 1)
            val accessible = requireNotNull(node("candidate_strip_items").findObject(By.res("candidate:${partial.index}")))
            assertTrue("Accessibility bounds alone would falsely look fully contained",
                accessible.visibleBounds.right <= geometry.viewport.right + 1)
            toggle()
            val expanded = assertStartsAt(geometry, source)
            screenshot("phase3-candidate-continuation")
            val duplicates = expanded.cells.filter { it.text == "同文" }
            assertTrue("Later same-text candidates must survive without deduplication", duplicates.size >= 2)
            val targets = duplicates.take(2)
            targets.forEach { target ->
                requireNotNull(node("candidate_grid").findObject(By.res("candidate:${target.index}"))).click()
                instrumentation.waitForIdleSync()
            }
            assertEquals("Each identical label must emit its own ORIGINAL raw ID and revision",
                targets.map { KeyboardAction.SelectCandidate(41L, it.index) }, fixture.selections())
        }
    }

    @Test fun horizontalStripDragClosesDrawerButReopeningKeepsItsInitialBoundary() {
        val source = candidates()
        withKeyboard(source) { fixture ->
            val initial = strip()
            val fixedStart = initial.continuationStart
            var moved = swipeStrip()
            if (moved.continuationStart <= fixedStart) moved = swipeStrip()
            assertTrue("Collapsed horizontal drag must advance the visible strip", moved.continuationStart > fixedStart)
            assertDrawerClosed()
            toggle()
            assertStartsAt(initial, source)
            val advanced = swipeStrip()
            assertTrue("Horizontal drag must still scroll the strip as it closes the drawer",
                advanced.continuationStart > moved.continuationStart)
            assertDrawerClosed()
            screenshot("phase3-candidate-strip-drag-closed")
            toggle()
            assertStartsAt(initial, source)
            val reversed = swipeStrip(forward = false)
            assertTrue("Reverse strip drag must lower the visible cutoff", reversed.continuationStart < advanced.continuationStart)
            assertDrawerClosed()
            toggle()
            assertStartsAt(initial, source)
            var verticallyScrolled = swipeDrawer()
            if (verticallyScrolled.cells.first().index <= fixedStart) verticallyScrolled = swipeDrawer()
            assertTrue("Vertical drawer drag must move candidates WITHOUT dismissing the page",
                verticallyScrolled.cells.first().index > fixedStart)
            assertSuffix(verticallyScrolled, source, fixedStart)
            screenshot("phase3-candidate-continuation-scrolled")
            toggle()
            assertDrawerClosed()
            assertEquals("Collapsing must preserve horizontal strip position", reversed, strip())
            toggle()
            val reopened = drawer()
            assertEquals("Reopening unchanged origin must preserve vertical IDs and pixel offsets", verticallyScrolled, reopened)
            assertSuffix(reopened, source, fixedStart)
            instrumentation.runOnMainSync {
                val dismissals = fixture.actions.filterIsInstance<KeyboardAction.CollapseCandidates>()
                assertEquals("Only the two expanded strip drags request dismissal", 2, dismissals.size)
                assertTrue("Every dismissal belongs to the current composition", dismissals.all { it.revision == 41L })
            }
            assertTrue("Strip and drawer drags must never select a candidate", fixture.selections().isEmpty())
        }
    }

    @Test fun oversizedIntersectingItemWithNoFullyVisibleCandidateIsNotSkipped() {
        val source = listOf("春风化雨".repeat(60)) + candidates()
        withKeyboard(source) {
            val initial = strip()
            assertTrue("Oversized candidate must exceed viewport", initial.intersecting.first().bounds.width() > initial.viewport.width())
            assertTrue("Fixture must have NO fully visible candidate", initial.fullyVisible.isEmpty())
            assertEquals("Initial intersecting raw ID is zero", 0, initial.intersecting.first().index)
            toggle()
            assertStartsAt(initial, source)
            screenshot("phase3-candidate-continuation-partial")
            val moved = swipeStrip()
            assertTrue("Swipe must move within the oversized cell", moved.intersecting.first().bounds.left < initial.intersecting.first().bounds.left)
            assertTrue("Oversized cell must still have no fully visible item", moved.fullyVisible.isEmpty())
            assertEquals("No-full-visible fallback must keep the intersecting ID", 0, moved.continuationStart)
            assertDrawerClosed()
            toggle()
            assertStartsAt(initial, source)
        }
    }

    @Test fun candidateRevisionResetsStripDrawerAndContinuationUsingNewScreenGeometry() {
        val original = candidates()
        withKeyboard(original) { fixture ->
            val initial = strip()
            val moved = swipeStrip()
            assertTrue("Old revision must have a noninitial horizontal cutoff", moved.continuationStart > initial.continuationStart)
            toggle()
            assertStartsAt(initial, original)
            val scrolled = swipeDrawer()
            assertTrue("Old revision must have a vertical offset", scrolled.cells.first().index > initial.continuationStart)
            // Equal candidate strings still represent a new revision; reset cannot depend on text inequality.
            fixture.update { it.copy(candidateRevision = 42L) }
            val reset = strip()
            assertEquals("New revision must restore original strip visibility", initial.intersecting, reset.intersecting)
            assertEquals("New revision must derive a fresh cutoff", initial.continuationStart, reset.continuationStart)
            val reopened = assertStartsAt(reset, original)
            val target = reopened.cells.first()
            requireNotNull(node("candidate_grid").findObject(By.res("candidate:${target.index}"))).click()
            instrumentation.waitForIdleSync()
            assertEquals("Selections after reset must use new revision with original global ID",
                listOf(KeyboardAction.SelectCandidate(42L, target.index)), fixture.selections())
        }
    }

    @Test fun appendingCandidatesKeepsInitialContinuationOriginAndGlobalIds() {
        val original = List(8) { "春" }
        withKeyboard(original) { fixture ->
            val initial = strip()
            val fixedStart = initial.continuationStart
            var end = swipeStrip()
            var attempts = 0
            while (end.fullyVisible.none { it.index == original.lastIndex } && attempts++ < 8) end = swipeStrip()
            assertTrue("Fixture must reach the last loaded strip candidate", end.fullyVisible.any { it.index == original.lastIndex })
            assertTrue("Initial boundary must be within the loaded candidate range", fixedStart in 0..original.size)
            val anchor = end.intersecting.first()
            // Appending while expanded must keep the initial continuation origin and native IDs.
            toggle()
            val beforeAppend = settled("Initial continuation drawer", ::readDrawer)
            if (beforeAppend.cells.isNotEmpty()) assertSuffix(beforeAppend, original, fixedStart)
            val expectedFirst = beforeAppend.cells.firstOrNull()?.index ?: fixedStart
            val appended = original + candidates(count = 180)
            fixture.update { it.copy(candidates = appended,
                candidateComments = List(appended.size) { "chun" }) }
            val after = strip()
            val retained = requireNotNull(after.intersecting.firstOrNull { it.index == anchor.index }) {
                "Append must preserve the first visible raw strip anchor: before=$end, after=$after"
            }
            assertEquals("Append must not jump the strip back to start or change anchor offset", anchor.bounds, retained.bounds)
            assertTrue("The strip is still horizontally scrolled after append",
                after.intersecting.first().index >= anchor.index)
            val result = drawer()
            assertEquals("Appending must keep the drawer anchored at the initial boundary",
                expectedFirst, result.cells.first().index)
            assertSuffix(result, appended, fixedStart)
            val scrolled = swipeDrawer()
            assertTrue("Appended candidates must remain reachable in the same continuous grid",
                scrolled.cells.any { it.index >= original.size })
            assertSuffix(scrolled, appended, fixedStart)
            // Appending a second offscreen tail cannot change either visible geometry or cutoff.
            val extended = appended + candidates(count = 80, offset = 3)
            fixture.update { it.copy(candidates = extended,
                candidateComments = List(extended.size) { "chun" }) }
            val unchanged = strip()
            assertEquals("Offscreen append must preserve visible strip candidates", after.intersecting, unchanged.intersecting)
            assertEquals("Offscreen append must not advance cutoff", after.continuationStart, unchanged.continuationStart)
            assertEquals("Offscreen append must preserve drawer IDs and positions", scrolled, drawer())
        }
    }
}
