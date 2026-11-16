package org.thanatophilia.xyuime.ime

import org.junit.Assert.*
import org.junit.Test

class EditorPolicySentenceCapsTest {
    @Test fun emptyAndWhitespaceOnlyAreSentenceStarts() {
        assertTrue(EditorPolicy.sentenceNeedsCaps(""))
        assertTrue(EditorPolicy.sentenceNeedsCaps("   \n  "))
    }

    @Test fun sentenceTerminatorsAllowSpacesAndClosingQuotes() {
        listOf(".", ". ", ".   ", "。 ", "! ", "？\n", "。\" ", "！』  ").forEach {
            assertTrue("Expected sentence start after $it", EditorPolicy.sentenceNeedsCaps(it))
        }
    }

    @Test fun ordinarySpaceAndWordContinuationDoNotCap() {
        listOf("hello", "hello ", "hello world", "hello, ", "hello: ").forEach {
            assertFalse("Must not start a sentence after $it", EditorPolicy.sentenceNeedsCaps(it))
        }
    }

    @Test fun punctuationAtDocumentEndIsRecognized() {
        assertTrue(EditorPolicy.sentenceNeedsCaps("First.   Second!   "))
        assertTrue(EditorPolicy.sentenceNeedsCaps("First..."))
        assertTrue(EditorPolicy.sentenceNeedsCaps("First\n  "))
        assertFalse(EditorPolicy.sentenceNeedsCaps(null))
    }
}
