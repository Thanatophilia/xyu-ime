package org.thanatophilia.xyuime.ime

/** Manual Shift wins over editor auto-caps until the next letter is entered. */
internal class CaseState {
    private enum class Override { None, UpperOnce, LowerOnce, Locked }
    private var manualOverride = Override.None
    var automaticUppercase: Boolean = false
    val uppercase: Boolean get() = when (manualOverride) {
        Override.None -> automaticUppercase
        Override.UpperOnce, Override.Locked -> true
        Override.LowerOnce -> false
    }
    val locked: Boolean get() = manualOverride == Override.Locked

    fun tap() {
        manualOverride = if (uppercase) Override.LowerOnce else Override.UpperOnce
    }

    fun lock() {
        manualOverride = if (locked) Override.LowerOnce else Override.Locked
    }

    fun committed(text: String) {
        if (manualOverride != Override.Locked && text.any { it.isLetter() }) manualOverride = Override.None
    }

    fun reset() {
        manualOverride = Override.None
        automaticUppercase = false
    }
}
