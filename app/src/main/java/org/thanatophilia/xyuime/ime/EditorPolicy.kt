package org.thanatophilia.xyuime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Decisions based on the editor contract, independent of a live InputConnection. */
internal object EditorPolicy {
    data class EnterAction(val id: Int?, val label: String)

    fun enterAction(imeOptions: Int, customId: Int = 0, customLabel: String? = null): EnterAction {
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            return EnterAction(null, "回车")
        }
        if (customId != 0 && !customLabel.isNullOrBlank()) {
            return EnterAction(customId, customLabel)
        }
        val id = imeOptions and EditorInfo.IME_MASK_ACTION
        val label = when (id) {
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            EditorInfo.IME_ACTION_PREVIOUS -> "上一项"
            else -> return EnterAction(null, "回车")
        }
        return EnterAction(id, label)
    }

    fun prefersNumbers(inputType: Int): Boolean = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
        else -> false
    }

    fun isPassword(inputType: Int): Boolean {
        val kind = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
}
