package org.thanatophilia.xyuime.ime

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import org.thanatophilia.xyuime.ui.CursorDirection

/** Short-lived text snapshots: never saved, logged, shown, or retained across input events. */
internal object EditorOperations {
    data class Snapshot(val text: String, val offset: Int, val start: Int, val end: Int)

    fun snapshot(connection: InputConnection): Snapshot? {
        val result = connection.getExtractedText(ExtractedTextRequest().apply {
            hintMaxChars = 16384
            hintMaxLines = 128
        }, 0) ?: return null
        val text = result.text?.toString() ?: return null
        if (result.partialStartOffset >= 0 || result.selectionStart !in 0..text.length || result.selectionEnd !in 0..text.length) return null
        return Snapshot(text, result.startOffset, result.startOffset + result.selectionStart, result.startOffset + result.selectionEnd)
    }

    fun deleteGrapheme(connection: InputConnection): Boolean {
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) return connection.commitText("", 1)
        // Bounded context avoids copying arbitrarily long documents into the IME on each key.
        val before = connection.getTextBeforeCursor(2048, 0)?.toString() ?: return false
        if (before.isEmpty()) return true
        val boundary = GraphemeBoundary.previousBoundary(before)
        return connection.deleteSurroundingText(before.length - boundary, 0)
    }

    fun destination(snapshot: Snapshot, direction: CursorDirection, extend: Boolean): Int {
        val start = (snapshot.start - snapshot.offset).coerceIn(0, snapshot.text.length)
        val end = (snapshot.end - snapshot.offset).coerceIn(0, snapshot.text.length)
        val text = snapshot.text
        if (!extend && start != end) {
            return snapshot.offset + if (direction in setOf(CursorDirection.Left, CursorDirection.Home, CursorDirection.Up)) minOf(start, end) else maxOf(start, end)
        }
        val cursor = end
        val lineStart = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1
        val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
        val next = when (direction) {
            CursorDirection.Left -> GraphemeBoundary.previousBoundary(text, cursor)
            CursorDirection.Right -> GraphemeBoundary.nextBoundary(text, cursor)
            CursorDirection.Home -> lineStart
            CursorDirection.End -> lineEnd
            CursorDirection.Up -> {
                if (lineStart == 0) 0 else {
                    val previousEnd = lineStart - 1
                    val previousStart = if (previousEnd == 0) 0 else text.lastIndexOf('\n', previousEnd - 1) + 1
                    sameColumn(text, lineStart, cursor, previousStart, previousEnd)
                }
            }
            CursorDirection.Down -> {
                if (lineEnd == text.length) text.length else {
                    val nextStart = lineEnd + 1
                    val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
                    sameColumn(text, lineStart, cursor, nextStart, nextEnd)
                }
            }
        }
        return snapshot.offset + next
    }

    private fun sameColumn(text: String, fromStart: Int, cursor: Int, toStart: Int, toEnd: Int): Int {
        var columns = 0
        var pos = fromStart
        while (pos < cursor) {
            val next = GraphemeBoundary.nextBoundary(text, pos)
            if (next <= pos) break
            pos = next
            columns++
        }
        pos = toStart
        repeat(columns) {
            if (pos < toEnd) pos = GraphemeBoundary.nextBoundary(text, pos).coerceAtMost(toEnd)
        }
        return pos
    }
}
