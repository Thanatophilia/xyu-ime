package org.thanatophilia.xyuime

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import kotlin.math.ceil
import kotlin.math.max

/** Public Compose view only: no IME selection, native engine, or dictionary dependency. */
@RunWith(AndroidJUnit4::class)
class ImeCandidateFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val density = context.resources.displayMetrics.density
    private val tolerance get() = ceil(4 * density).toInt()
    private val chromeTags = listOf("candidate_drawer", "candidate_syllables", "candidate_grid",
        "candidate_tools", "candidate_type", "candidate_delete", "candidate_tools_page_up",
        "candidate_tools_page_down", "candidate_enter")

    private data class Cell(val index: Int, val text: String, val bounds: Rect)
    private data class Layout(val chrome: Map<String, Rect>, val cells: List<Cell>) {
        val grid get() = chrome.getValue("candidate_grid")
        val rows get() = cells.groupBy { it.bounds.top }.toSortedMap().values.map { row ->
            row.sortedBy { it.bounds.left }
        }
    }

    private fun node(tag: String): UiObject2 = requireNotNull(
        device.waitForFreshObject(By.res(tag).pkg(context.packageName), 8_000)
    ) { "Missing $tag" }

    private fun readLayout(): Layout {
        device.freshImeTree()
        val root = node("candidate_grid")
        val grid = Rect(root.visibleBounds)
        val cells = root.findObjects(By.res(Pattern.compile("candidate:[0-9]+")))
            .map { cell ->
                val text = cell.text?.takeIf { it.isNotBlank() }
                    ?: cell.findObject(By.text(Pattern.compile(".+")))?.text
                Cell(requireNotNull(cell.resourceName).substringAfterLast(':').toInt(),
                    requireNotNull(text) { "Candidate must expose its text" }, Rect(cell.visibleBounds))
            }
            .filter { !it.bounds.isEmpty && Rect.intersects(grid, it.bounds) }
            .sortedWith(compareBy<Cell> { it.bounds.top }.thenBy { it.bounds.left })
        return Layout(chromeTags.associateWith { Rect(node(it).visibleBounds) }, cells)
    }

    private fun settledLayout(): Layout {
        val deadline = SystemClock.uptimeMillis() + 8_000
        var previous: Layout? = null
        var stableSince = SystemClock.uptimeMillis()
        do {
            val current = readLayout()
            val now = SystemClock.uptimeMillis()
            if (current != previous) stableSince = now
            if (current.cells.isNotEmpty() && now - stableSince >= 300) return current
            previous = current
            SystemClock.sleep(60)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Candidate geometry did not settle: $previous")
    }

    private fun withKeyboard(candidates: List<String>, test: (Map<String, Int>) -> Unit) {
        val emulator = Build.HARDWARE in listOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
        assumeTrue("Synthetic screen fixture is emulator-only", emulator)
        val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val textWidths = mutableMapOf<String, Int>()
        try {
            instrumentation.runOnMainSync {
                host.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                host.currentFocus?.clearFocus()
                host.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
                if (Build.VERSION.SDK_INT >= 30) host.window.insetsController?.hide(WindowInsets.Type.ime())
                val state = KeyboardUiState(chinese = true, preedit = "chun feng",
                    candidatesExpanded = true, candidates = candidates,
                    candidateComments = candidates.map { word -> List(word.length) { "chun" }.joinToString(" ") },
                    highlightedCandidate = -1, candidateSession = 101, candidateRevision = 1L)
                host.setContent {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        // Independent real Text layouts supply glyph widths, never grid spans/packing.
                        Box(Modifier.align(Alignment.TopStart).alpha(0f).clearAndSetSemantics {}) {
                            MaterialTheme {
                                candidates.distinct().forEach { word ->
                                    Text(word, fontSize = 20.sp, fontWeight = FontWeight.Normal,
                                        maxLines = 1, softWrap = false,
                                        onTextLayout = {
                                            textWidths[word] = ceil(it.getLineRight(0) - it.getLineLeft(0)).toInt()
                                        })
                                }
                            }
                        }
                        XyuKeyboard(state = state, onAction = {})
                    }
                }
            }
            instrumentation.waitForIdleSync()
            settledLayout()
            var imeVisible = false
            var measured: Map<String, Int> = emptyMap()
            instrumentation.runOnMainSync {
                if (Build.VERSION.SDK_INT >= 30) imeVisible =
                    host.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                measured = textWidths.toMap()
            }
            assertFalse("Real system IME must be hidden before measuring the fixture", imeVisible)
            assertEquals("All independent text probes must be laid out", candidates.toSet(), measured.keys)
            test(measured)
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun fullRows(layout: Layout): List<List<Cell>> = layout.rows.filter { row ->
        row.all { layout.grid.contains(it.bounds) && it.bounds.height() >= 48 * density - 1 }
    }

    private fun assertOrderAndPacking(layout: Layout, candidates: List<String>) {
        assertTrue("Visible cells required", layout.cells.isNotEmpty())
        layout.cells.forEach { cell ->
            assertEquals("Original candidate index must keep its text", candidates[cell.index], cell.text)
            assertTrue("Cell must stay inside the grid: $cell", layout.grid.contains(cell.bounds))
        }
        val indices = layout.cells.map { it.index }
        assertEquals("Screen reading order must be the original contiguous order",
            (indices.first()..indices.last()).toList(), indices)
        layout.cells.forEachIndexed { index, cell ->
            layout.cells.drop(index + 1).forEach { other ->
                assertFalse("Candidate tap targets must not overlap: $cell / $other",
                    Rect.intersects(cell.bounds, other.bounds))
            }
        }
        layout.rows.forEach { row ->
            assertTrue("Rows must start at the grid left, not be centered", abs(row.first().bounds.left - layout.grid.left) <= 2)
            row.zipWithNext().forEach { (a, b) ->
                assertTrue("Cells must stay adjacent, not spread/justify: $a / $b", abs(b.bounds.left - a.bounds.right) <= 2)
            }
        }
        layout.rows.zipWithNext().forEach { (row, next) ->
            val leftover = layout.grid.right - row.last().bounds.right
            assertTrue("Greedy wrap must leave less room than the next real cell needs: leftover=$leftover, next=${next.first()}",
                leftover < next.first().bounds.width() + 2)
        }
    }

    private fun assertNaturalWidths(layout: Layout, measured: Map<String, Int>) {
        layout.cells.forEach { cell ->
            // Compare rendered rectangles to independently rendered text, not production helpers.
            val natural = max(layout.grid.width() / 5f, measured.getValue(cell.text) + 12 * density)
                .coerceAtMost(layout.grid.width().toFloat())
            assertTrue("Cell must hug measured text + 12dp (or 1/5-grid minimum): $cell, expected~$natural, grid=${layout.grid}",
                abs(cell.bounds.width() - natural) <= tolerance)
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

    @Test fun allModePacksTwoToFiveCharacterWordsAtNaturalWidths() {
        listOf("春风", "春风雨", "春风化雨", "春风化雨来").forEach { word ->
            val candidates = List(80) { word }
            withKeyboard(candidates) { measured ->
                val layout = settledLayout()
                if (word.length == 2) screenshot("phase3-candidate-flow-words")
                assertOrderAndPacking(layout, candidates)
                assertNaturalWidths(layout, measured)
                val firstRow = fullRows(layout).first()
                if (word.length == 2) {
                    assertTrue("Two-character words must fit at least THREE in the first row; old two-column layout must fail: $firstRow",
                        firstRow.size >= 3)
                    assertTrue("Two-character cells must be substantially narrower than 2/5 of the grid",
                        firstRow.all { it.bounds.width() < layout.grid.width() * 0.35f })
                }
                assertTrue("${word.length}-character words must not be forced full width",
                    firstRow.all { it.bounds.width() < layout.grid.width() * 0.9f })
            }
        }
    }

    private fun swipeUp(layout: Layout): Layout {
        val grid = layout.grid
        // Slow, short drag preserves overlapping rows instead of jumping a fixed page.
        assertTrue("Grid drag must be injected", device.swipe(grid.centerX(), grid.bottom - 3,
            grid.centerX(), grid.bottom - grid.height() / 3, 90))
        return settledLayout()
    }

    private fun assertRails(layout: Layout) {
        val drawer = layout.chrome.getValue("candidate_drawer")
        val left = layout.chrome.getValue("candidate_syllables")
        val right = layout.chrome.getValue("candidate_tools")
        assertTrue("Drawer must contain both side rails and grid",
            drawer.contains(left) && drawer.contains(layout.grid) && drawer.contains(right))
        assertTrue("Side rails must flank the grid", left.right <= layout.grid.left && layout.grid.right <= right.left)
        assertTrue("Both existing edge rails must keep the same width", abs(left.width() - right.width()) <= 1)
        assertTrue("Left rail must stay at drawer edge", abs(left.left - drawer.left) <= 1)
        assertTrue("Right rail must stay at drawer edge", abs(right.right - drawer.right) <= 1)
        assertTrue("Type control must remain inside the left rail", left.contains(layout.chrome.getValue("candidate_type")))
        listOf("candidate_delete", "candidate_tools_page_up", "candidate_tools_page_down", "candidate_enter")
            .map { tag -> layout.chrome.getValue(tag).also { bounds ->
                assertTrue("$tag must stay inside right rail", right.contains(bounds))
                assertTrue("$tag must retain the full right rail width", abs(bounds.width() - right.width()) <= 1)
            } }.zipWithNext().forEach { (a, b) ->
                assertTrue("Delete/up/down/enter must keep their vertical order", a.bottom <= b.top)
            }
    }

    @Test fun mixedLengthsKeepReadingOrderGreedyWrapAndContinuousScroll() {
        val words = listOf("春风雨", "春风化雨", "春风化雨来", "风雨", "雨", "春风", "风雨同舟行", "风雨同舟", "风雨来")
        val candidates = List(18) { "春风" } + List(16) { words }.flatten()
        withKeyboard(candidates) { measured ->
            var layout = settledLayout()
            val chromeBefore = layout.chrome
            val seenLengths = mutableSetOf<Int>()
            var savedMixed = false
            repeat(9) {
                assertOrderAndPacking(layout, candidates)
                assertNaturalWidths(layout, measured)
                assertEquals("Word flow must not move/rescale the rails or controls", chromeBefore, layout.chrome)
                assertRails(layout)
                seenLengths.addAll(layout.cells.map { it.text.length })
                if (!savedMixed && layout.cells.any { it.text.length == 5 }) {
                    screenshot("phase3-candidate-flow-mixed")
                    savedMixed = true
                }
                val previous = layout
                layout = swipeUp(previous)
                assertTrue("Continuous drag must advance through the original candidate list",
                    layout.cells.first().index > previous.cells.first().index ||
                        (layout.cells.first().index == previous.cells.first().index &&
                            layout.cells.first().bounds.top < previous.cells.first().bounds.top))
                assertTrue("A short drag must retain overlapping candidates, not replace a fixed page",
                    layout.cells.map { it.index }.intersect(previous.cells.map { it.index }.toSet()).isNotEmpty())
            }
            assertOrderAndPacking(layout, candidates)
            assertNaturalWidths(layout, measured)
            assertTrue("Mixed scroll must actually visit 2/3/4/5-character words: $seenLengths",
                seenLengths.containsAll(listOf(2, 3, 4, 5)))
            assertTrue("Mixed screenshot must be captured", savedMixed)
        }
    }

    private fun assertFiveColumns(layout: Layout) {
        val rows = fullRows(layout)
        assertTrue("At least one fully visible single-character row is required", rows.isNotEmpty())
        assertTrue("All visible candidates must be single characters", layout.cells.all {
            it.text.codePointCount(0, it.text.length) == 1
        })
        rows.forEach { row ->
            assertEquals("Each fully visible single-character row must contain exactly FIVE cells", 5, row.size)
            assertTrue("Five columns must begin at the grid left", abs(row.first().bounds.left - layout.grid.left) <= 1)
            assertTrue("Five columns must fill the grid right", abs(row.last().bounds.right - layout.grid.right) <= 1)
            row.forEach { cell ->
                assertTrue("Single cells must use one fifth of the real grid width",
                    abs(cell.bounds.width() - layout.grid.width() / 5f) <= 2)
            }
        }
    }

    @Test fun singleModeKeepsFiveColumnsAndPinnedRailsDuringContinuousScroll() {
        val singles = listOf("春", "风", "雨", "来", "归")
        val candidates = List(180) { index ->
            if (index < 30 || index % 3 != 0) singles[index % singles.size] else "春风"
        }
        withKeyboard(candidates) { measured ->
            val all = settledLayout()
            assertOrderAndPacking(all, candidates)
            assertNaturalWidths(all, measured)
            val firstAllRow = fullRows(all).first()
            assertEquals("Standalone singles must already stay five per row in All mode", 5, firstAllRow.size)
            node("candidate_type").click()
            var single = settledLayout()
            val type = node("candidate_type")
            assertTrue("Type control must actually switch to 单字", type.text == "单字" || type.hasObject(By.text("单字")))
            assertEquals("Switching to Single must preserve all rail/grid/control rectangles", all.chrome, single.chrome)
            assertRails(single)
            assertFiveColumns(single)
            repeat(2) {
                val previousFirst = single.cells.first().index
                node("candidate_tools_page_down").click()
                single = settledLayout()
                assertTrue("Down rail must advance the continuous Single list", single.cells.first().index > previousFirst)
                assertFiveColumns(single)
                assertEquals("Single scrolling must leave rails/grid/controls pinned", all.chrome, single.chrome)
                single.cells.forEach { cell -> assertEquals("Filtering must preserve original index/text", candidates[cell.index], cell.text) }
            }
            val downFirst = single.cells.first().index
            node("candidate_tools_page_up").click()
            single = settledLayout()
            assertTrue("Up rail must move back through the same Single list", single.cells.first().index < downFirst)
            assertFiveColumns(single)
            assertEquals("Up control must leave both rail widths unchanged", all.chrome, single.chrome)
            node("candidate_type").click()
            val restored = settledLayout()
            assertEquals("Returning to All must preserve drawer geometry", all.chrome, restored.chrome)
            assertOrderAndPacking(restored, candidates)
            assertNaturalWidths(restored, measured)
        }
    }

    @Test fun veryLongWordsAreCappedAtTheGridWidth() {
        val longWord = "春风化雨万物复苏风雨同舟山河锦绣春暖花开".repeat(3)
        val candidates = listOf(longWord) + List(50) { "春风" }
        withKeyboard(candidates) { measured ->
            val layout = settledLayout()
            assertTrue("Long-word fixture must actually exceed the grid", measured.getValue(longWord) > layout.grid.width())
            val first = layout.cells.first()
            assertEquals("Long candidate must retain original index", 0, first.index)
            assertTrue("Very long candidate must be capped at exactly the real grid width",
                abs(first.bounds.width() - layout.grid.width()) <= 1)
            assertOrderAndPacking(layout, candidates)
            assertNaturalWidths(layout, measured)
        }
    }
}
