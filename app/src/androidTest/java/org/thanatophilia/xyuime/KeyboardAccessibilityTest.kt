package org.thanatophilia.xyuime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.thanatophilia.xyuime.ui.KeyboardAction
import org.thanatophilia.xyuime.ui.KeyboardUiState
import org.thanatophilia.xyuime.ui.XyuKeyboard

/** Actual Compose semantics actions: complements UIAutomator descriptions, not a TalkBack claim. */
class KeyboardAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<KeyboardTestActivity>()

    @Test fun shiftExposesLowerUpperLockedAndChineseStates() {
        var state by mutableStateOf(KeyboardUiState())
        val actions = mutableListOf<KeyboardAction>()
        rule.setContent { XyuKeyboard(state, onAction = { actions.add(it) }) }
        val shift = rule.onNodeWithTag("key:切换大小写，长按锁定大写")
        shift.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "小写"))
        shift.performSemanticsAction(SemanticsActions.OnClick) { it() }
        shift.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        rule.runOnIdle { assertEquals(listOf(KeyboardAction.Shift, KeyboardAction.CapsLock), actions) }
        rule.runOnIdle { state = state.copy(uppercase = true) }
        shift.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "大写"))
        rule.runOnIdle { state = state.copy(capsLock = true) }
        shift.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "大写已锁定"))
        rule.runOnIdle { state = state.copy(chinese = true, inputHint = "中文拼音") }
        shift.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "中文拼音"))
    }

    @Test fun languageSwitchAndSystemPickerAreSeparateAccessibleActions() {
        val actions = mutableListOf<KeyboardAction>()
        rule.setContent { XyuKeyboard(KeyboardUiState(inputHint = "English"), onAction = { actions.add(it) }) }
        val globe = rule.onNodeWithTag("key:切换语言，长按切换输入法")
        globe.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "English"))
        globe.performSemanticsAction(SemanticsActions.OnClick) { it() }
        globe.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        rule.runOnIdle {
            assertEquals(listOf(KeyboardAction.NextLanguage, KeyboardAction.PickInputMethod), actions)
        }
    }

    @Test fun heldDeleteStopsAfterDraggingOutsideAndCancellation() {
        val clicks = java.util.concurrent.atomic.AtomicInteger()
        rule.setContent { XyuKeyboard(KeyboardUiState(), onAction = {
            if (it == KeyboardAction.Backspace) clicks.incrementAndGet()
        }) }
        val delete = rule.onNodeWithTag("key:删除，长按连续删除")
        delete.performTouchInput { down(center) }
        rule.waitUntil(3_000) { clicks.get() >= 2 }
        delete.performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(-20f, centerY)); up() }
        val afterExit = clicks.get()
        android.os.SystemClock.sleep(250)
        rule.runOnIdle { assertEquals("Repeat must stop after exiting its bounds", afterExit, clicks.get()) }
        delete.performTouchInput { down(center) }
        rule.waitUntil(3_000) { clicks.get() >= afterExit + 2 }
        delete.performTouchInput { cancel() }
        val afterCancel = clicks.get()
        android.os.SystemClock.sleep(250)
        rule.runOnIdle { assertEquals("Repeat must stop after ACTION_CANCEL", afterCancel, clicks.get()) }
    }

    @Test fun candidateTypeAnnouncesCurrentFilterAndSyllableSelection() {
        var state by mutableStateOf(KeyboardUiState(chinese = true, preedit = "shi",
            candidates = List(60) { "字" }, candidateComments = List(60) { "shi" },
            candidateSession = 1, candidateRevision = 1))
        rule.setContent { XyuKeyboard(state, onAction = {
            if (it == KeyboardAction.ToggleCandidates) state = state.copy(candidatesExpanded = !state.candidatesExpanded)
        }) }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("candidate_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("candidate_toggle").performClick()
        val filter = rule.onNodeWithTag("candidate_type")
        filter.assertContentDescriptionEquals("切换候选字类")
        filter.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "全部字词候选"))
        filter.performClick()
        filter.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "仅单字候选"))
        rule.onNodeWithTag("syllable:all").assertIsSelected()
        rule.onNodeWithTag("syllable:shi").performClick().assertIsSelected()
        rule.onNodeWithTag("syllable:all").assertIsNotSelected()
        filter.performClick()
        filter.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "全部字词候选"))
    }
}
