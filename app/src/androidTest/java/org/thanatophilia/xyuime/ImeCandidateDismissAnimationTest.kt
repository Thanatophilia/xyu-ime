package org.thanatophilia.xyuime

import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thanatophilia.xyuime.ui.KeyboardAction
import org.thanatophilia.xyuime.ui.KeyboardUiState
import org.thanatophilia.xyuime.ui.XyuKeyboard

/** A real finger gesture and controlled frames prove the drawer animates, not just disappears. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ImeCandidateDismissAnimationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })

    @Test fun stripDragRetainsClosingDrawerUntilAnimationFinishes() {
        var state by mutableStateOf(KeyboardUiState(chinese = true, preedit = "shi",
            candidateRevision = 801L, candidateSession = 80,
            candidates = List(240) { "候选${it + 1}" }, candidateComments = List(240) { "shi" }))
        val actions = mutableListOf<KeyboardAction>()
        compose.runOnUiThread {
            val host = compose.activity
            host.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            host.currentFocus?.clearFocus()
            host.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
            host.setContent {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    XyuKeyboard(state, onAction = { action ->
                        actions += action
                        when (action) {
                            KeyboardAction.ToggleCandidates -> state = state.copy(candidatesExpanded = !state.candidatesExpanded)
                            is KeyboardAction.CollapseCandidates -> if (action.revision == state.candidateRevision)
                                state = state.copy(candidatesExpanded = false)
                            else -> Unit
                        }
                    })
                }
            }
        }
        compose.waitForIdle()
        fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
        fun gridBounds(): Rect {
            val grid = node("candidate_grid").fetchSemanticsNode()
            return Rect(grid.positionInRoot, grid.size.toSize())
        }
        node("candidate_toggle").performClick()
        compose.waitForIdle()
        assertTrue(state.candidatesExpanded)
        val expandedBounds = gridBounds()
        node("key:q").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        node("candidate_strip_items").performTouchInput {
            down(Offset(width * 0.8f, height * 0.5f))
            moveTo(Offset(width * 0.35f, height * 0.5f), delayMillis = 16L)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertFalse("Recognized horizontal drag must request a one-way dismissal", state.candidatesExpanded)
            assertEquals(1, actions.filterIsInstance<KeyboardAction.CollapseCandidates>().size)
        }
        compose.mainClock.advanceTimeBy(48L)
        compose.waitForIdle()
        val closingBounds = gridBounds()
        assertTrue("The same drawer must move upwards while closing", closingBounds.top < expandedBounds.top)
        assertTrue("An intermediate drawer frame must remain partly visible", closingBounds.bottom > expandedBounds.top)
        node("key:q").assertDoesNotExist()
        // Finish the same horizontal gesture: it scrolls the header and cannot select/reopen.
        node("candidate_strip_items").performTouchInput {
            moveTo(Offset(width * 0.15f, height * 0.5f), delayMillis = 16L)
            up()
        }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        node("candidate_grid").assertDoesNotExist()
        node("key:q").assertExists()
        compose.runOnIdle {
            assertFalse(state.candidatesExpanded)
            assertEquals("One drag must not toggle the drawer back open", 1,
                actions.filterIsInstance<KeyboardAction.CollapseCandidates>().size)
            assertTrue("The strip gesture must not select a word", actions.none { it is KeyboardAction.SelectCandidate })
            assertEquals("shi", state.preedit)
        }
        compose.mainClock.autoAdvance = true
        // Horizontal drags when already closed must leave it closed.
        val count = actions.filterIsInstance<KeyboardAction.CollapseCandidates>().size
        node("candidate_strip_items").performTouchInput {
            down(Offset(width * 0.8f, height * 0.5f))
            moveTo(Offset(width * 0.3f, height * 0.5f), delayMillis = 16L)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(state.candidatesExpanded)
            assertEquals(count, actions.filterIsInstance<KeyboardAction.CollapseCandidates>().size)
        }
    }
}
