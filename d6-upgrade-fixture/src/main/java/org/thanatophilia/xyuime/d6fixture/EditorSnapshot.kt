package org.thanatophilia.xyuime.d6fixture

/** Captured atomically on the editor's main thread. No null/invalid span means "committed". */
data class EditorSnapshot(
    val text: String,
    val composingStart: Int,
    val composingEnd: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    fun isCommitted(expected: String): Boolean =
        text == expected && composingStart == -1 && composingEnd == -1

    fun isComposing(prefix: String, code: String): Boolean =
        composingStart == prefix.length && composingEnd == text.length &&
            composingEnd > composingStart && text.startsWith(prefix) &&
            text.substring(composingStart, composingEnd).replace(" ", "") == code

    /** Failure diagnostics intentionally omit input text, even for synthetic editor content. */
    fun diagnostic(): String = "len=${text.length},span=$composingStart:$composingEnd," +
        "selection=$selectionStart:$selectionEnd"
}
