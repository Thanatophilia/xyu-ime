package org.thanatophilia.xyuime.d6fixture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSnapshotTest {
    @Test fun composingTextInBufferDoesNotMeanCommittedText() {
        val s = EditorSnapshot("pre-ni hao", 4, 10, 10, 10)
        assertTrue(s.isComposing("pre-", "nihao"))
        assertFalse(s.isCommitted("pre-ni hao"))
    }
    @Test fun commitRequiresExactTextAndBothAbsentSpanEndpoints() {
        val s = EditorSnapshot("synthetic", -1, -1, 9, 9)
        assertTrue(s.isCommitted("synthetic"))
        assertFalse(s.isCommitted("syntheticsynthetic"))
        assertFalse(s.copy(composingStart = 0).isCommitted("synthetic"))
        assertFalse(s.copy(composingEnd = 0).isCommitted("synthetic"))
    }
    @Test fun invalidCompositionRangeDoesNotPass() {
        assertFalse(EditorSnapshot("ni", 3, 1, 2, 2).isComposing("", "ni"))
        assertFalse(EditorSnapshot("ni", -1, 2, 2, 2).isComposing("", "ni"))
        assertFalse(EditorSnapshot("prefixni", 0, 8, 8, 8).isComposing("prefix", "ni"))
    }
    @Test fun diagnosticsDoNotContainInputText() {
        assertFalse(EditorSnapshot("private-synthetic", -1, -1, 0, 0).diagnostic()
            .contains("private-synthetic"))
    }
}
