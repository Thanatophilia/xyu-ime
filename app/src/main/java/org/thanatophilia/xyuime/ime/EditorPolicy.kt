package org.thanatophilia.xyuime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Decisions based on the editor contract, independent of a live InputConnection. */
internal object EditorPolicy {
    enum class InputKind {
        Text,
        Email,
        Url,
        Integer,
        Decimal,
        SignedInteger,
        SignedDecimal,
        Phone,
        DateTime,
        Password,
    }

    data class EnterAction(val id: Int?, val label: String)

    private const val CAP_FLAGS =
        InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

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

    fun kind(inputType: Int): InputKind {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> when {
                isPassword(inputType) -> InputKind.Password
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> InputKind.Email
                variation == InputType.TYPE_TEXT_VARIATION_URI -> InputKind.Url
                else -> InputKind.Text
            }

            InputType.TYPE_CLASS_NUMBER -> when {
                // Keep numeric passwords on the numeric keyboard; sensitivity is exposed by isPassword.
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> InputKind.Integer
                inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0 &&
                    inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0 -> InputKind.SignedDecimal
                inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0 -> InputKind.Decimal
                inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0 -> InputKind.SignedInteger
                else -> InputKind.Integer
            }

            InputType.TYPE_CLASS_PHONE -> InputKind.Phone
            InputType.TYPE_CLASS_DATETIME -> InputKind.DateTime
            else -> InputKind.Text
        }
    }

    fun prefersNumbers(inputType: Int): Boolean = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
        else -> false
    }

    fun asciiPunctuation(inputType: Int): Boolean = when (kind(inputType)) {
        InputKind.Email,
        InputKind.Url,
        InputKind.Integer,
        InputKind.Decimal,
        InputKind.SignedInteger,
        InputKind.SignedDecimal,
        InputKind.Phone,
        InputKind.DateTime,
        InputKind.Password,
        -> true

        InputKind.Text -> false
    }

    /**
     * Normalizes compatibility punctuation for editors that expect an ASCII alphabet.
     * Unknown clipboard content is retained verbatim.
     */
    fun normalizeText(text: String, inputType: Int): String {
        if (!asciiPunctuation(inputType) || text.isEmpty()) return text
        return buildString(text.length) {
            text.forEach { character ->
                when {
                    character in '\uFF01'..'\uFF5E' -> append((character.code - 0xFEE0).toChar())
                    character == '\u3002' -> append('.')
                    character == '\u3001' -> append(',')
                    character == '\uFE50' || character == '\uFE51' -> append(',')
                    character == '\uFE52' -> append('.')
                    character == '\uFE54' -> append(';')
                    character == '\uFE55' -> append(':')
                    character == '\uFE56' -> append('?')
                    character == '\uFE57' -> append('!')
                    character == '\uFE5F' -> append('#')
                    character == '\uFE60' -> append('&')
                    character == '\uFE61' -> append('*')
                    character == '\uFE62' -> append('+')
                    character == '\uFE63' -> append('-')
                    character == '\uFE64' -> append('<')
                    character == '\uFE65' -> append('>')
                    character == '\uFE66' -> append('=')
                    character == '\uFE68' -> append('\\')
                    character == '\uFE69' -> append('$')
                    character == '\uFE6A' -> append('%')
                    character == '\uFE6B' -> append('@')
                    else -> append(character)
                }
            }
        }
    }

    /**
     * Detects a sentence start from the text immediately before the cursor.
     * This supplements getCursorCapsMode(), which is inconsistent after punctuation
     * in some editors and Compose text fields.
     */
    fun sentenceNeedsCaps(textBeforeCursor: CharSequence?): Boolean {
        val text = textBeforeCursor ?: return false // Unavailable context is not an empty document.
        var index = text.lastIndex
        while (index >= 0) {
            val char = text[index]
            if (char == '\n' || char == '\r') return true
            if (char.isWhitespace() || char in CLOSING_DELIMITERS) index-- else break
        }
        return index < 0 || text[index] in SENTENCE_TERMINATORS
    }

    private const val SENTENCE_TERMINATORS = ".。!?！？"
    private const val CLOSING_DELIMITERS = "\\\"'”’)]}》」』〕】"

    /** Returns only explicit text capitalization flags applicable to this editor. */
    fun autoCapsFlags(inputType: Int): Int {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return 0
        return when (kind(inputType)) {
            InputKind.Text -> inputType and CAP_FLAGS
            else -> 0
        }
    }

    fun isPassword(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
