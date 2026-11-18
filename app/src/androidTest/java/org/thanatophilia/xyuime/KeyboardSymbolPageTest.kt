package org.thanatophilia.xyuime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
class KeyboardSymbolPageTest {
    @get:Rule val rule = createAndroidComposeRule<KeyboardTestActivity>()

    private fun setKeyboard(ascii: Boolean, actions: MutableList<KeyboardAction>) {
        var state by mutableStateOf(KeyboardUiState(page = KeyboardPage.Symbols, symbolAscii = ascii))
        rule.setContent {
            // The panel keeps its own group state; rerunning setContent is not needed.
            XyuKeyboard(state, onAction = { actions.add(it) })
        }
    }

    @Test fun chineseEditorOpensChineseGroupWithFullRowsAndFloatingDelete() {
        setKeyboard(ascii = false, actions = mutableListOf())
        rule.onNodeWithTag("symbol_tab:cn").assertIsSelected()
        rule.onNodeWithTag("symbol_tab:cn").assertContentDescriptionEquals("中文标点")
        // The first three grid rows (24 cells) are fully visible: three complete rows.
        val grid = rule.onNodeWithTag("symbol_grid").fetchSemanticsNode().boundsInRoot
        (0 until 24).forEach { index ->
            val cell = rule.onNodeWithTag("symbol:cn:$index").fetchSemanticsNode().boundsInRoot
            assertTrue("cell $index must intersect the grid", cell.height > 0f && cell.width > 0f)
            assertTrue("cell $index must sit inside the grid", grid.contains(cell.topLeft) && grid.contains(cell.bottomRight))
        }
        // Delete floats as exactly one cell anchored to the grid's bottom-right corner.
        val delete = rule.onNodeWithTag("key:删除，长按连续删除").fetchSemanticsNode().boundsInRoot
        val firstCell = rule.onNodeWithTag("symbol:cn:0").fetchSemanticsNode().boundsInRoot
        assertEquals("delete cell matches grid cell height", firstCell.height, delete.height, 1f)
        assertEquals("delete cell matches grid cell width", firstCell.width, delete.width, 1f)
        assertEquals("delete sits at the grid's right edge", grid.right, delete.right, 1f)
        assertEquals("delete sits at the grid's bottom edge", grid.bottom, delete.bottom, 1f)
        // The bottom bar's first key reads ABC on the symbol page.
        rule.onNodeWithTag("key:返回字母键盘").assertContentDescriptionEquals("返回字母键盘")
    }

    @Test fun tabClickSwitchesGroupAndInsertsExactCharacters() {
        val actions = mutableListOf<KeyboardAction>()
        setKeyboard(ascii = false, actions)
        rule.onNodeWithTag("symbol:cn:2").assertContentDescriptionEquals("顿号")
        rule.onNodeWithTag("symbol:cn:2").performClick()
        rule.onNodeWithTag("symbol_tab:en").performClick()
        rule.onNodeWithTag("symbol_tab:en").assertIsSelected()
        rule.onNodeWithTag("symbol_tab:cn").assertIsNotSelected()
        rule.onNodeWithTag("symbol:en:0").assertContentDescriptionEquals("叹号")
        rule.onNodeWithTag("symbol:en:0").performClick()
        rule.onNodeWithTag("symbol_tab:math").performClick()
        rule.onNodeWithTag("symbol:math:2").assertContentDescriptionEquals("乘")
        rule.onNodeWithTag("symbol:math:2").performClick()
        rule.runOnIdle {
            assertEquals(listOf("、", "!", "×").map { KeyboardAction.Text(it) }, actions)
        }
    }

    @Test fun horizontalSwipeSwitchesGroupWhileVerticalDragDoesNot() {
        setKeyboard(ascii = false, actions = mutableListOf())
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("symbol_tab:en").assertIsSelected()
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("symbol_tab:math").assertIsSelected()
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeRight() }
        rule.onNodeWithTag("symbol_tab:en").assertIsSelected()
        rule.onNodeWithTag("symbol_grid").performTouchInput { swipeRight() }
        rule.onNodeWithTag("symbol_tab:cn").assertIsSelected()
    }

    @Test fun asciiEditorDefaultsToEnglishGroupAndCanStillBrowseAll() {
        setKeyboard(ascii = true, actions = mutableListOf())
        rule.onNodeWithTag("symbol_tab:en").assertIsSelected()
        rule.onNodeWithTag("symbol:en:0").assertContentDescriptionEquals("叹号")
        rule.onNodeWithTag("symbol_tab:cn").performClick()
        rule.onNodeWithTag("symbol_tab:cn").assertIsSelected()
    }
}
