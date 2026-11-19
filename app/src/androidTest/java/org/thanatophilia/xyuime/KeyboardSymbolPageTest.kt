package org.thanatophilia.xyuime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.thanatophilia.xyuime.ui.KeyboardAction
import org.thanatophilia.xyuime.ui.KeyboardPage
import org.thanatophilia.xyuime.ui.KeyboardUiState
import org.thanatophilia.xyuime.ui.SymbolLayout
import org.thanatophilia.xyuime.ui.XyuKeyboard
import kotlin.math.abs

/** D5.2 symbol page checks: eight groups, five visible chips, recent wiring, delete cell, ABC. */
@OptIn(ExperimentalTestApi::class)
class KeyboardSymbolPageTest {
    @get:Rule val rule = createAndroidComposeRule<KeyboardTestActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })

    private fun setKeyboard(ascii: Boolean, actions: MutableList<KeyboardAction>,
        recent: List<String> = emptyList()) {
        var state by mutableStateOf(KeyboardUiState(page = KeyboardPage.Symbols,
            symbolAscii = ascii, recentSymbols = recent))
        rule.setContent { XyuKeyboard(state, onAction = { actions.add(it) }) }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        val target = java.io.File(rule.activity.getExternalFilesDir(null), "$name.png")
        assertTrue("Screenshot must be captured", device.takeScreenshot(target))
        device.executeShellCommand("mkdir -p /sdcard/Download/xyu-ime-review")
        device.executeShellCommand("cp ${target.absolutePath} /sdcard/Download/xyu-ime-review/$name.png")
    }

    @Test fun commonGroupOpensFirstWithFiveChipsVisibleAndFloatingDelete() {
        setKeyboard(ascii = false, actions = mutableListOf())
        rule.onNodeWithTag("symbol_tab:common").assertIsSelected()
        rule.onNodeWithTag("symbol_tab:common").assertContentDescriptionEquals("常用")
        rule.onNodeWithText("● ○ ○ ○ ○ ○ ○ ○ 1/8").assertExists()
        rule.onNodeWithTag("symbol_group_indicator")
            .assertContentDescriptionEquals("符号分组 常用，第 1 组，共 8 组")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1/8"))
        // Five chips are visible at once: common, recent, cn, en, math.
        listOf("common", "recent", "cn", "en", "math").forEach { key ->
            rule.onNodeWithTag("symbol_tab:$key").assertIsDisplayed()
        }
        val grid = rule.onNodeWithTag("symbol_grid").fetchSemanticsNode().boundsInRoot
        val tolerance = 2f
        (0 until 24).forEach { index ->
            val cell = rule.onNodeWithTag("symbol:common:$index").fetchSemanticsNode().boundsInRoot
            assertTrue("cell $index must sit inside the grid",
                cell.left >= grid.left - tolerance && cell.right <= grid.right + tolerance &&
                    cell.top >= grid.top - tolerance && cell.bottom <= grid.bottom + tolerance)
        }
        val delete = rule.onNodeWithTag("key:删除").fetchSemanticsNode().boundsInRoot
        val firstCell = rule.onNodeWithTag("symbol:common:0").fetchSemanticsNode().boundsInRoot
        assertEquals("delete cell matches grid cell height", firstCell.height, delete.height, 1f)
        assertEquals("delete sits at the grid's bottom-right", grid.right, delete.right, 1f)
        assertEquals(grid.bottom, delete.bottom, 1f)
        rule.onNodeWithTag("key:返回字母键盘").assertContentDescriptionEquals("返回字母键盘")
        saveScreenshot("d5-common-symbols")
    }

    @Test fun hiddenChipScrollsIntoViewAndInsertsExactCharacter() {
        val actions = mutableListOf<KeyboardAction>()
        setKeyboard(ascii = false, actions)
        rule.onNodeWithTag("symbol_tab_bar").performScrollToIndex(SymbolLayout.groups.indexOf(
            SymbolLayout.groups.first { it.key == "marks" }))
        rule.onNodeWithTag("symbol_tab:marks").assertIsDisplayed()
        rule.onNodeWithTag("symbol_tab:marks").performClick()
        rule.onNodeWithTag("symbol_tab:marks").assertIsSelected()
        rule.onNodeWithTag("symbol:marks:1").assertContentDescriptionEquals("上标一")
        rule.onNodeWithTag("symbol:marks:1").performClick()
        rule.runOnIdle { assertEquals(listOf(KeyboardAction.Text("¹")), actions) }
    }

    @Test fun tabClickSwitchesGroupsAndInsertsExactCharacters() {
        val actions = mutableListOf<KeyboardAction>()
        setKeyboard(ascii = false, actions)
        rule.onNodeWithTag("symbol_tab:cn").performClick()
        rule.onNodeWithTag("symbol:cn:2").assertContentDescriptionEquals("顿号")
        rule.onNodeWithText("、").assertExists()
        rule.onNodeWithText("顿号").assertDoesNotExist()
        rule.onNodeWithTag("symbol:cn:2").performClick()
        rule.onNodeWithTag("symbol_tab:en").performClick()
        rule.onNodeWithTag("symbol:en:0").assertContentDescriptionEquals("叹号")
        rule.onNodeWithTag("symbol:en:0").performClick()
        rule.onNodeWithTag("symbol_tab:math").performClick()
        rule.onNodeWithTag("symbol:math:2").assertContentDescriptionEquals("乘")
        rule.onNodeWithTag("symbol:math:2").performClick()
        rule.onNodeWithTag("symbol_tab_bar").performScrollToIndex(5)
        rule.onNodeWithTag("symbol_tab:arrows").performClick()
        rule.onNodeWithTag("symbol:arrows:0").assertContentDescriptionEquals("上箭头")
        rule.onNodeWithTag("symbol:arrows:0").performClick()
        rule.onNodeWithTag("key:删除").performClick()
        rule.runOnIdle {
            assertEquals(listOf("、", "!", "×", "↑").map { KeyboardAction.Text(it) } + KeyboardAction.Backspace, actions)
        }
    }

    @Test fun horizontalSwipeCyclesThroughAllGroupsWhileVerticalDragOnlyScrolls() {
        val actions = mutableListOf<KeyboardAction>()
        setKeyboard(ascii = false, actions)
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeUp() }
        rule.onNodeWithTag("symbol_tab:common").assertIsSelected()
        val expected = listOf("recent", "cn", "en", "math", "arrows", "ordinal", "marks", "common")
        listOf(2, 3, 4, 5, 6, 7, 8, 1).forEach { expectedIndex ->
            rule.onNodeWithTag("symbol_grid").performTouchInput { swipeLeft() }
            rule.onNodeWithTag("symbol_group_indicator")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "${expectedIndex}/8"))
        }
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeRight() }
        rule.onNodeWithTag("symbol_group_indicator")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "8/8"))
        rule.runOnIdle { assertTrue("Scrolling/swiping must not insert or delete", actions.isEmpty()) }
    }

    @Test fun verticalGridFollowsFingerAndStopsAfterShortCoast() {
        val actions = mutableListOf<KeyboardAction>()
        setKeyboard(ascii = false, actions)
        val page = rule.onNodeWithTag("symbol_page:common", useUnmergedTree = true)
        fun cellY(index: Int): Float = rule.onNodeWithTag("symbol:common:$index", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        val rowStridePx = cellY(8) - cellY(0)
        val maxCoastPx = with(rule.density) { 16.dp.toPx() }
        fun scrollY(): Float {
            val state = page.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            val parts = state.removePrefix("scroll:").split(":")
            check(parts.size == 2)
            return (parts[0].toInt() / 8) * rowStridePx + parts[1].toInt()
        }
        val before = scrollY()
        rule.mainClock.autoAdvance = false
        try {
            page.performTouchInput {
                down(Offset(width * .5f, height * .82f))
                moveTo(Offset(width * .5f, height * .56f), delayMillis = 16)
                moveTo(Offset(width * .5f, height * .28f), delayMillis = 16)
            }
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeByFrame()
            assertTrue("Vertical content must move before the finger is lifted", scrollY() > before + 1f)
            page.performTouchInput { up() }
            val atRelease = scrollY()
            rule.mainClock.advanceTimeBy(128)
            val settled = scrollY()
            val coast = settled - atRelease
            assertTrue("Fast release must coast forward, but by no more than 16dp; moved $coast px",
                coast > 0.5f && coast <= maxCoastPx + 2f)
            rule.mainClock.advanceTimeBy(320)
            assertEquals("No late motion once the short coast ends", settled, scrollY(), 0.5f)
            rule.runOnIdle { assertTrue("Dragging must not insert or delete symbols", actions.isEmpty()) }
        } finally {
            rule.mainClock.autoAdvance = true
            rule.mainClock.advanceTimeBy(500)
        }
    }

    @Test fun horizontalGridFollowsFingerAndSettlesWithoutMovingDelete() {
        val actions = mutableListOf<KeyboardAction>()
        setKeyboard(ascii = false, actions)
        fun pageBounds(tag: String): Rect {
            val n = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            return Rect(n.positionInRoot, n.size.toSize())
        }
        val before = pageBounds("symbol_page:common")
        val delete = rule.onNodeWithTag("key:删除").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("symbol_grid").performTouchInput {
                down(Offset(width * .8f, height * .35f))
                moveTo(Offset(width * .42f, height * .35f), delayMillis = 96)
            }
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeByFrame()
            val dragged = pageBounds("symbol_page:common")
            assertTrue("Content must move before finger-up", dragged.left < before.left - before.width * .1f)
            assertEquals("Delete must stay fixed while symbols move", delete,
                rule.onNodeWithTag("key:删除").fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithTag("symbol_grid").performTouchInput {
                moveTo(Offset(width * .32f, height * .35f), delayMillis = 16)
                up()
            }
            rule.mainClock.advanceTimeBy(80)
            val snapping = pageBounds("symbol_page:recent")
            assertTrue("Adjacent group must enter continuously", snapping.left < before.right && snapping.right > before.left)
            rule.mainClock.advanceTimeBy(500)
            val settled = pageBounds("symbol_page:recent")
            assertTrue("Page must snap into the fixed viewport", abs(settled.left - before.left) < 2f)
            rule.onNodeWithTag("symbol_tab:recent").assertIsSelected()
            rule.runOnIdle { assertTrue("Drag must not insert symbols", actions.isEmpty()) }
        } finally {
            rule.mainClock.autoAdvance = true
            rule.mainClock.advanceTimeBy(500)
        }
    }

    @Test fun recentGroupShowsEmptyHint() {
        setKeyboard(ascii = false, actions = mutableListOf(), recent = emptyList())
        rule.onNodeWithTag("symbol_tab:recent").performClick()
        rule.onNodeWithTag("symbol_tab:recent").assertIsSelected()
        rule.onNodeWithText("还没有最近使用的符号").assertExists()
    }

    @Test fun recentGroupListsNewestEntryFirst() {
        setKeyboard(ascii = false, actions = mutableListOf(), recent = listOf("↑", "、"))
        rule.onNodeWithTag("symbol_tab:recent").performClick()
        rule.onNodeWithTag("symbol:recent:0").assertContentDescriptionEquals("上箭头")
        rule.onNodeWithTag("symbol:recent:1").assertContentDescriptionEquals("顿号")
    }

    @Test fun asciiEditorDefaultsToEnglishGroupAndCanStillReachHiddenChips() {
        setKeyboard(ascii = true, actions = mutableListOf())
        rule.onNodeWithTag("symbol_tab:en").assertIsSelected()
        rule.onNodeWithTag("symbol:en:0").assertContentDescriptionEquals("叹号")
        rule.onNodeWithTag("symbol_tab_bar").performScrollToIndex(7)
        rule.onNodeWithTag("symbol_tab:marks").performClick()
        rule.onNodeWithTag("symbol_tab:marks").assertIsSelected()
        rule.onNodeWithTag("symbol_tab_bar").performScrollToIndex(0)
        rule.onNodeWithTag("symbol_tab:common").performClick()
        rule.onNodeWithTag("symbol_tab:common").assertIsSelected()
    }
}
