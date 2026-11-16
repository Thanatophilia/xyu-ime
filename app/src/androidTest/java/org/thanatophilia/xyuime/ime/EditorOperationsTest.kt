package org.thanatophilia.xyuime.ime

import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedTextRequest
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.thanatophilia.xyuime.ui.CursorDirection

@RunWith(AndroidJUnit4::class)
class EditorOperationsTest {
    @Test fun graphemeBoundariesKeepEmojiAndCombiningSequencesIntact() {
        val clusters = listOf("A", "👨‍👩‍👧‍👦", "👍🏽", "🇨🇳", "1️⃣", "e\u0301", "\r\n", "Z")
        val text = clusters.joinToString("")
        var offset = 0
        clusters.forEach { cluster ->
            val end = offset + cluster.length
            assertEquals(end, GraphemeBoundary.nextBoundary(text, offset))
            assertEquals(offset, GraphemeBoundary.previousBoundary(text, end))
            offset = end
        }
        assertEquals(0, GraphemeBoundary.previousBoundary("", 99))
        assertEquals(0, GraphemeBoundary.previousBoundary(text, -5))
        assertEquals(text.length, GraphemeBoundary.nextBoundary(text, text.length + 100))
    }

    @Test fun deletionUsesWholeClustersAndSelectionIsReplaced() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            val connection = BaseInputConnection(editor, true)
            val clusters = listOf("A", "👨‍👩‍👧‍👦", "👍🏽", "🇨🇳", "1️⃣", "e\u0301")
            connection.commitText(clusters.joinToString(""), 1)
            var remaining = clusters
            repeat(clusters.size) {
                assertTrue(EditorOperations.deleteGrapheme(connection))
                remaining = remaining.dropLast(1)
                assertEquals(remaining.joinToString(""), connection.editable.toString())
            }
            connection.commitText("abcdef", 1)
            connection.setSelection(1, 5)
            assertTrue(EditorOperations.deleteGrapheme(connection))
            assertEquals("af", connection.editable.toString())
        }
    }

    @Test fun cursorMovesOverEntireClustersAndLineEdges() {
        val text = "A👨‍👩‍👧‍👦B\nxy"
        val endOfEmoji = 1 + "👨‍👩‍👧‍👦".length
        val snapshot = EditorOperations.Snapshot(text, 0, endOfEmoji, endOfEmoji)
        assertEquals(1, EditorOperations.destination(snapshot, CursorDirection.Left, false))
        assertEquals(0, EditorOperations.destination(snapshot, CursorDirection.Home, false))
        assertEquals(endOfEmoji + 1, EditorOperations.destination(snapshot, CursorDirection.End, false))
        val selection = snapshot.copy(start = 0, end = endOfEmoji)
        assertEquals(0, EditorOperations.destination(selection, CursorDirection.Left, false))
        assertEquals(endOfEmoji + 1, EditorOperations.destination(selection, CursorDirection.Right, true))
    }
}
