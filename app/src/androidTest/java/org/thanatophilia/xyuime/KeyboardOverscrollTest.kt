package org.thanatophilia.xyuime

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.thanatophilia.xyuime.ui.KeyboardAction
import org.thanatophilia.xyuime.ui.KeyboardPage
import org.thanatophilia.xyuime.ui.KeyboardUiState
import org.thanatophilia.xyuime.ui.XyuKeyboard
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the public keyboard entry, so omitting its no-overscroll scope is observable. */
class KeyboardOverscrollTest {
    @get:Rule val rule = createAndroidComposeRule<KeyboardTestActivity>()

    // Simulate an ancestor whose overscroll never reports that its edge animation has ended.
    private class BusyEffect : OverscrollEffect {
        override val node: DelegatableNode = object : Modifier.Node() {}
        override val isInProgress: Boolean get() = true
        override fun applyToScroll(delta: Offset, source: NestedScrollSource,
            performScroll: (Offset) -> Offset): Offset = performScroll(delta)
        override suspend fun applyToFling(velocity: Velocity,
            performFling: suspend (Velocity) -> Velocity) = performFling(velocity)
    }

    private data class CountingFactory(val creations: AtomicInteger = AtomicInteger()) : OverscrollFactory {
        override fun createOverscrollEffect(): OverscrollEffect {
            creations.incrementAndGet()
            return BusyEffect()
        }
    }

    @Test fun controlListOutsideKeyboardStillUsesAncestorOverscroll() {
        val factory = CountingFactory()
        val taps = AtomicInteger()
        rule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides factory) {
                LazyColumn(Modifier.width(180.dp).height(160.dp)) {
                    items(20) { i ->
                        Text("item $i", Modifier.height(48.dp).testTag("outside:$i")
                            .clickable { taps.incrementAndGet() })
                    }
                }
            }
        }
        rule.onNodeWithTag("outside:1").performTouchInput { click(center) }
        rule.runOnIdle {
            assertTrue("The fault-injection factory must be used by the control list", factory.creations.get() > 0)
            assertEquals("A busy overscroll effect intercepts the control list's tap", 0, taps.get())
        }
    }

    @Test fun symbolPagesAndCategoryRailDoNotInheritBusyOverscroll() {
        val factory = CountingFactory()
        val actions = mutableListOf<KeyboardAction>()
        rule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides factory) {
                XyuKeyboard(KeyboardUiState(page = KeyboardPage.Symbols), onAction = { actions.add(it) })
            }
        }
        rule.onNodeWithTag("symbol:common:2").performTouchInput { click(center) }
        rule.onNodeWithTag("symbol_tab:recent").performTouchInput { click(center) }
        rule.onNodeWithText("还没有最近使用的符号").assertIsDisplayed()
        rule.onNodeWithTag("symbol_tab:cn").performTouchInput { click(center) }
        rule.onNodeWithTag("symbol:cn:13").performTouchInput { click(center) }
        rule.runOnIdle {
            assertEquals(listOf(KeyboardAction.Text("、"), KeyboardAction.Text("《")), actions)
            assertEquals("Vertical grids, horizontal pager and rail must all suppress ancestor effects",
                0, factory.creations.get())
        }
    }

    @Test fun candidateStripAndExpandedGridDoNotInheritBusyOverscroll() {
        val factory = CountingFactory()
        val actions = mutableListOf<KeyboardAction>()
        var state by mutableStateOf(KeyboardUiState(chinese = true, preedit = "ni",
            candidates = List(90) { "字" }, candidateComments = List(90) { "ni" },
            candidateSession = 1, candidateRevision = 1))
        rule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides factory) {
                XyuKeyboard(state, onAction = { action ->
                    actions.add(action)
                    if (action == KeyboardAction.ToggleCandidates) {
                        state = state.copy(candidatesExpanded = !state.candidatesExpanded)
                    }
                })
            }
        }
        rule.onNodeWithTag("candidate:0").performTouchInput { click(center) }
        rule.onNodeWithTag("candidate_toggle").performTouchInput { click(center) }
        rule.onNodeWithTag("candidate_drawer").assertIsDisplayed()
        rule.onNodeWithTag("candidate_type").performTouchInput { click(center) }
        rule.runOnIdle {
            assertEquals(KeyboardAction.SelectCandidate(1, 0), actions.first())
            assertTrue(actions.contains(KeyboardAction.ToggleCandidates))
            assertEquals("Candidate strip, filters and grid must also suppress ancestor effects",
                0, factory.creations.get())
        }
    }

    @Test fun symbolsRemainTappableAfterDraggingPastBothVerticalEdges() {
        val factory = CountingFactory()
        val actions = mutableListOf<KeyboardAction>()
        rule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides factory) {
                XyuKeyboard(KeyboardUiState(page = KeyboardPage.Symbols), onAction = { actions.add(it) })
            }
        }
        val grid = rule.onNodeWithTag("symbol_page:common", useUnmergedTree = true)
        grid.performTouchInput { swipeDown() } // Already at the top edge.
        rule.onNodeWithTag("symbol:common:2").performTouchInput { click(center) }
        grid.performScrollToIndex(34)
        grid.performTouchInput { swipeUp() } // Already at the bottom edge.
        rule.onNodeWithTag("symbol:common:32").performTouchInput { click(center) }
        rule.runOnIdle {
            assertEquals(listOf(KeyboardAction.Text("、"), KeyboardAction.Text("°")), actions)
            assertEquals(0, factory.creations.get())
        }
    }
}
