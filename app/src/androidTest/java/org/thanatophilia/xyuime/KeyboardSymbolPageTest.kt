package org.thanatophilia.xyuime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.thanatophilia.xyuime.ui.KeyboardAction
import org.thanatophilia.xyuime.ui.KeyboardPage
import org.thanatophilia.xyuime.ui.KeyboardUiState
import org.thanatophilia.xyuime.ui.XyuKeyboard

/** D5 Compose-level checks: tabs, group switching (tap + swipe), grid, delete cell, ABC key. */
@OptIn(ExperimentalTestApi::class)
class KeyboardSymbolPageTest {
    @get:Rule val rule = createAndroidComposeRule<KeyboardTestActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })

    private fun setKeyboard(ascii: Boolean, actions: MutableList<KeyboardAction>) {
        var state by mutableStateOf(KeyboardUiState(page = KeyboardPage.Symbols, symbolAscii = ascii))
        rule.setContent {
            // The panel keeps its own group state; rerunning setContent is not needed.
            XyuKeyboard(state, onAction = { actions.add(it) })
        }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        val target = java.io.File(rule.activity.getExternalFilesDir(null), "$name.png")
        assertTrue("Screenshot must be captured", device.takeScreenshot(target))
        device.executeShellCommand("mkdir -p /sdcard/Download/xyu-ime-review")
        device.executeShellCommand("cp ${target.absolutePath} /sdcard/Download/xyu-ime-review/$name.png")
    }

    @Test fun chineseEditorOpensChineseGroupWithFullRowsAndFloatingDelete() {
        setKeyboard(ascii = false, actions = mutableListOf())
        rule.onNodeWithTag("symbol_tab:cn").assertIsSelected()
        rule.onNodeWithTag("symbol_tab:cn").assertContentDescriptionEquals("中文标点")
        rule.onNodeWithText("● ○ ○ 1/3").assertExists()
        rule.onNodeWithTag("symbol_group_indicator")
            .assertContentDescriptionEquals("符号分组 中文标点，第 1 组，共 3 组")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1/3"))
        // The first three grid rows (24 cells) are fully visible: three complete rows.
        val grid = rule.onNodeWithTag("symbol_grid").fetchSemanticsNode().boundsInRoot
        val tolerance = 2f
        (0 until 24).forEach { index ->
            val cell = rule.onNodeWithTag("symbol:cn:$index").fetchSemanticsNode().boundsInRoot
            assertTrue("cell $index must intersect the grid", cell.height > 0f && cell.width > 0f)
            assertTrue("cell $index must sit inside the grid",
                cell.left >= grid.left - tolerance && cell.right <= grid.right + tolerance &&
                    cell.top >= grid.top - tolerance && cell.bottom <= grid.bottom + tolerance)
        }
        // Delete floats as exactly one cell anchored to the grid's bottom-right corner.
        val delete = rule.onNodeWithTag("key:删除").fetchSemanticsNode().boundsInRoot
        val firstCell = rule.onNodeWithTag("symbol:cn:0").fetchSemanticsNode().boundsInRoot
        assertEquals("delete cell matches grid cell height", firstCell.height, delete.height, 1f)
        assertEquals("delete cell matches grid cell width", firstCell.width, delete.width, 1f)
        assertEquals("delete sits at the grid's right edge", grid.right, delete.right, 1f)
        assertEquals("delete sits at the grid's bottom edge", grid.bottom, delete.bottom, 1f)
        // The bottom bar's first key reads ABC on the symbol page.
        rule.onNodeWithTag("key:返回字母键盘").assertContentDescriptionEquals("返回字母键盘")
        saveScreenshot("d5-centered-symbols")
    }

    @Test fun tabClickSwitchesGroupAndInsertsExactCharacters() {
        val actions = mutableListOf<KeyboardAction>()
        setKeyboard(ascii = false, actions)
        rule.onNodeWithTag("symbol:cn:2").assertContentDescriptionEquals("顿号")
        rule.onNodeWithText("、").assertExists()
        rule.onNodeWithText("顿号").assertDoesNotExist() // name is spoken, never rendered on the key face
        rule.onNodeWithTag("symbol:cn:2").performClick()
        rule.onNodeWithTag("symbol_tab:en").performClick()
        rule.onNodeWithTag("symbol_tab:en").assertIsSelected()
        rule.onNodeWithTag("symbol_tab:cn").assertIsNotSelected()
        rule.onNodeWithTag("symbol:en:0").assertContentDescriptionEquals("叹号")
        rule.onNodeWithTag("symbol:en:0").performClick()
        rule.onNodeWithTag("symbol_tab:math").performClick()
        rule.onNodeWithTag("symbol:math:2").assertContentDescriptionEquals("乘")
        rule.onNodeWithTag("symbol:math:2").performClick()
        rule.onNodeWithTag("key:删除").performClick()
        rule.runOnIdle {
            assertEquals(listOf("、", "!", "×").map { KeyboardAction.Text(it) } + KeyboardAction.Backspace, actions)
        }
    }

    @Test fun horizontalSwipeCyclesAndVerticalDragOnlyScrollsCurrentGroup() {
        val actions = mutableListOf<KeyboardAction>()
        setKeyboard(ascii = false, actions)
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeUp() }
        rule.onNodeWithTag("symbol_tab:cn").assertIsSelected()
        rule.onNodeWithTag("symbol:cn:26").assertIsDisplayed()
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeDown() }
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("symbol_tab:en").assertIsSelected()
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("symbol_tab:math").assertIsSelected()
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("symbol_tab:cn").assertIsSelected()
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeRight() }
        rule.onNodeWithTag("symbol_tab:math").assertIsSelected()
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeRight() }
        rule.onNodeWithTag("symbol_tab:en").assertIsSelected()
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeRight() }
        rule.onNodeWithTag("symbol_tab:cn").assertIsSelected()
        rule.runOnIdle { assertTrue("Scrolling/swiping must not insert or delete", actions.isEmpty()) }
    }

    @Test fun horizontalGridFollowsFingerAndSettlesWithoutMovingDelete() {
        val actions = mutableListOf<KeyboardAction>()
        setKeyboard(ascii = false, actions)
        fun pageBounds(tag: String): Rect {
            val n = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            return Rect(n.positionInRoot, n.size.toSize())
        }
        val before = pageBounds("symbol_page:cn")
        val delete = rule.onNodeWithTag("key:删除").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("symbol_grid").performTouchInput {
                down(Offset(width * .8f, height * .35f))
                moveTo(Offset(width * .42f, height * .35f), delayMillis = 96)
            }
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeByFrame()
            val dragged = pageBounds("symbol_page:cn")
            assertTrue("Content must move before finger-up", dragged.left < before.left - before.width * .1f)
            assertTrue("Current page must stay partly visible during drag", dragged.right > before.left)
            saveScreenshot("d5-swipe-mid-drag")
            assertEquals("Delete must stay fixed while symbols move", delete,
                rule.onNodeWithTag("key:删除").fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithTag("symbol_grid").performTouchInput {
                moveTo(Offset(width * .32f, height * .35f), delayMillis = 16)
                up()
            }
            rule.mainClock.advanceTimeBy(80)
            val snapping = pageBounds("symbol_page:en")
            assertTrue("Adjacent group must enter continuously", snapping.left < before.right && snapping.right > before.left)
            rule.mainClock.advanceTimeBy(500)
            val settled = pageBounds("symbol_page:en")
            assertTrue("Page must snap into the fixed viewport", abs(settled.left - before.left) < 2f)
            rule.onNodeWithTag("symbol_tab:en").assertIsSelected()
            rule.runOnIdle { assertTrue("Drag must not insert symbols", actions.isEmpty()) }
        } finally {
            rule.mainClock.autoAdvance = true
            rule.mainClock.advanceTimeBy(500)
        }
    }

    @Test fun asciiEditorDefaultsToEnglishGroupAndCanStillBrowseAll() {
        setKeyboard(ascii = true, actions = mutableListOf())
        rule.onNodeWithTag("symbol_tab:en").assertIsSelected()
        rule.onNodeWithTag("symbol:en:0").assertContentDescriptionEquals("叹号")
        rule.onNodeWithTag("symbol_tab:cn").performClick()
        rule.onNodeWithTag("symbol_tab:cn").assertIsSelected()
    }
}
