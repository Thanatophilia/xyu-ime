package org.thanatophilia.xyuime.ime

import org.junit.Assert.*
import org.junit.Test

class ChineseSessionTest {
    @Test fun lateOutputCannotEnterAnotherEditor() {
        val gate = ChineseSession()
        val previous = gate.epoch
        assertTrue(gate.accept(previous, 10))
        gate.invalidate()
        assertFalse(gate.accept(previous, 11))
        assertTrue(gate.accept(gate.epoch, 12))
        assertFalse(gate.accept(gate.epoch, 12))
        assertFalse(gate.accept(gate.epoch, 9))
    }

    @Test fun loadingMorePreservesCandidateIdentityUntilCompositionChanges() {
        val gate = ChineseSession()
        val snapshot = gate.revision
        assertEquals(128, gate.more())
        assertTrue(gate.matches(snapshot))
        gate.compositionChanged() // Includes partial selection, even if preedit looks unchanged.
        assertFalse(gate.matches(snapshot))
        assertEquals(64, gate.limit)
    }

    @Test fun candidateRequestCannotExceedWrapperLimit() {
        val gate = ChineseSession()
        repeat(100) { gate.more() }
        assertEquals(4096, gate.limit)
        gate.invalidate()
        assertEquals(64, gate.limit)
    }
}
