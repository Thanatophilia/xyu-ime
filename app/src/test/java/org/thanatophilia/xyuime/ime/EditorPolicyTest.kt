package org.thanatophilia.xyuime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class EditorPolicyTest {
    @Test fun multilineNoEnterActionMustInsertNewlineEvenWhenSendIsAdvertised() {
        val action = EditorPolicy.enterAction(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        assertNull(action.id)
        assertEquals("回车", action.label)
    }
    @Test fun searchAndCustomActionAreForwardedToEditor() {
        assertEquals(EditorInfo.IME_ACTION_SEARCH, EditorPolicy.enterAction(EditorInfo.IME_ACTION_SEARCH).id)
        assertEquals("搜索", EditorPolicy.enterAction(EditorInfo.IME_ACTION_SEARCH).label)
        assertEquals(42, EditorPolicy.enterAction(0, 42, "提交").id)
    }
    @Test fun noActionIsNotTreatedAsDone() {
        assertNull(EditorPolicy.enterAction(EditorInfo.IME_ACTION_NONE).id)
        assertNull(EditorPolicy.enterAction(EditorInfo.IME_ACTION_UNSPECIFIED).id)
    }
    @Test fun numericPasswordIsBothNumericAndSensitive() {
        val type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(EditorPolicy.prefersNumbers(type))
        assertTrue(EditorPolicy.isPassword(type))
    }
    @Test fun textPasswordVariationsAreSensitiveButOrdinaryTextIsNot() {
        listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).forEach {
            assertTrue(EditorPolicy.isPassword(InputType.TYPE_CLASS_TEXT or it))
        }
        assertFalse(EditorPolicy.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }
}
