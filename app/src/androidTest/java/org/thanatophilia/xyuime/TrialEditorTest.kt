package org.thanatophilia.xyuime

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test

/** Tests the actual release trial UI; no network or real message sending. */
class TrialEditorTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun choose(label: String) {
        rule.onNodeWithText("更多测试类型").performScrollTo().performClick()
        rule.onNodeWithText(label, substring = false).performScrollTo().performClick()
    }
    private fun chooseExpanded(label: String) {
        rule.onNodeWithText(label, substring = false).performScrollTo().performClick()
    }

    @Test fun sendCounterAndTypeSwitchAreIsolated() {
        choose("发送")
        rule.onNodeWithTag("trial_editor").performScrollTo().performTextInput("test")
        rule.onNodeWithTag("trial_editor").performImeAction()
        rule.onNodeWithTag("trial_action_result").assertTextEquals("发送动作已收到 · 本地第 1 次")
        rule.onNodeWithTag("trial_editor").performImeAction()
        rule.onNodeWithTag("trial_action_result").assertTextEquals("发送动作已收到 · 本地第 2 次")
        chooseExpanded("中文长句")
        rule.onNodeWithTag("trial_action_result").assertDoesNotExist()
        rule.onNodeWithTag("trial_editor").assert(hasText("", substring = false, ignoreCase = false))
    }

    @Test fun navigationMovesFocusAndFieldsResetOnReselection() {
        choose("下一项/上一项")
        rule.onNodeWithTag("trial_first").performScrollTo().performTextInput("alpha")
        rule.onNodeWithTag("trial_first").performImeAction()
        rule.onNodeWithTag("trial_second").assertIsFocused().performTextInput("beta")
        rule.onNodeWithTag("trial_second").performImeAction()
        rule.onNodeWithTag("trial_first").assertIsFocused().assertTextContains("alpha")
        rule.onNodeWithTag("trial_second").assertTextContains("beta")
        rule.onNodeWithTag("trial_action_result").assertTextEquals("上一项动作已收到 · 本地第 2 次")
        chooseExpanded("下一项/上一项")
        rule.onNodeWithTag("trial_action_result").assertDoesNotExist()
        rule.onNodeWithTag("trial_first").assert(hasText("", substring = false, ignoreCase = false))
        rule.onNodeWithTag("trial_second").assert(hasText("", substring = false, ignoreCase = false))
    }

    @Test fun backgroundClearsTrialContentsAndCounters() {
        choose("发送")
        rule.onNodeWithTag("trial_editor").performScrollTo().performTextInput("synthetic private text")
        rule.onNodeWithTag("trial_editor").performImeAction()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithTag("trial_action_result").assertDoesNotExist()
        rule.onNodeWithTag("trial_editor").assert(hasText("", substring = false, ignoreCase = false))
    }
}
