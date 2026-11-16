package org.thanatophilia.xyuime.ime

import android.icu.text.BreakIterator
import java.util.Locale

/**
 * Platform grapheme boundaries for editor cursor and deletion operations.
 *
 * Android ICU follows the Unicode data shipped with the device, so behavior for
 * newer emoji sequences can vary on older Android releases.
 */
internal object GraphemeBoundary {
    fun previousBoundary(text: String, index: Int = text.length): Int {
        if (text.isEmpty()) return 0
        val safeIndex = index.coerceIn(0, text.length)
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val boundary = iterator.preceding(safeIndex)
        return if (boundary == BreakIterator.DONE) 0 else boundary
    }

    fun nextBoundary(text: String, index: Int = 0): Int {
        if (text.isEmpty()) return 0
        val safeIndex = index.coerceIn(0, text.length)
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val boundary = iterator.following(safeIndex)
        return if (boundary == BreakIterator.DONE) text.length else boundary
    }
}
