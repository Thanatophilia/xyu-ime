package org.thanatophilia.xyuime.ime

import org.junit.Assert.*
import org.junit.Test

class CaseStateTest {
    @Test fun manualLowerSurvivesAutomaticCapsCallbacksUntilLetter() {
        val state = CaseState().apply { automaticUppercase = true }
        state.tap()
        state.automaticUppercase = true
        assertFalse(state.uppercase)
        state.committed("a")
        assertTrue(state.uppercase)
    }
    @Test fun oneShotUpperPersistsAcrossSpacesAndEndsAfterLetter() {
        val state = CaseState()
        state.tap()
        state.committed(" ")
        assertTrue(state.uppercase)
        state.committed("A")
        assertFalse(state.uppercase)
    }
    @Test fun capsLockSurvivesTypingAndCanBeExplicitlyCancelled() {
        val state = CaseState()
        state.lock()
        state.committed("ABC")
        assertTrue(state.locked)
        state.tap()
        assertFalse(state.locked)
        assertFalse(state.uppercase)
    }
    @Test fun switchingEditorsResetsShiftOverrideAndLock() {
        val state = CaseState().apply { lock() }
        state.reset()
        assertFalse(state.uppercase)
        assertFalse(state.locked)
    }
}
