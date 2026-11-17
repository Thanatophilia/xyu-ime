package org.thanatophilia.xyuime.ui

import org.junit.Assert.*
import org.junit.Test

class CandidateContinuationTest {
    @Test fun completeCellsContinueAtTheirNextNativeIndex() {
        val cells = listOf(StripCandidateBounds(0, 0, 50), StripCandidateBounds(1, 50, 50),
            StripCandidateBounds(2, 100, 50))
        assertEquals(3, candidateContinuationStart(cells, 0, 150, 20))
        assertEquals(2, candidateContinuationStart(cells, 0, 149, 20))
    }

    @Test fun initialBoundaryIsCapturedOnlyAtTheLeftmostStripPosition() {
        val initial = listOf(StripCandidateBounds(0, 0, 60), StripCandidateBounds(1, 60, 60),
            StripCandidateBounds(2, 120, 60))
        val start = initialCandidateContinuationStart(initial, 0, 110, 20, 0, 0)
        assertEquals(1, start)
        val scrolled = listOf(StripCandidateBounds(4, 0, 60), StripCandidateBounds(5, 60, 60))
        assertNull(initialCandidateContinuationStart(scrolled, 0, 110, 20, 4, 0))
        assertNull(initialCandidateContinuationStart(initial, 0, 110, 20, 0, 12))
        // The latched value remains the initial value when the live strip advances.
        assertEquals(1, start)
    }

    @Test fun partialTrailingWordIsKeptAndPrefetchedOffscreenCellsDoNotCount() {
        val cells = listOf(StripCandidateBounds(8, -30, 60), StripCandidateBounds(9, 30, 50),
            StripCandidateBounds(10, 80, 50), StripCandidateBounds(11, 130, 60))
        assertEquals(10, candidateContinuationStart(cells, 0, 110, 40))
    }

    @Test fun oversizedOrOnlyPartiallyDisplayedCellMustNotBeSkipped() {
        assertEquals(5, candidateContinuationStart(listOf(StripCandidateBounds(5, -15, 400)), 0, 300, 20))
        assertEquals(7, candidateContinuationStart(listOf(StripCandidateBounds(6, 0, 300)), 0, 300, 20))
    }

    @Test fun emptyUnmeasuredAndExhaustedPrefixesAreDistinct() {
        assertNull(candidateContinuationStart(emptyList(), 0, 0, 20))
        assertNull(candidateContinuationStart(emptyList(), 0, 300, 0))
        assertNull(candidateContinuationStart(listOf(StripCandidateBounds(4, 300, 50)), 0, 300, 10))
        assertEquals(10, candidateContinuationStart(listOf(StripCandidateBounds(9, 0, 100)), 0, 100, 10))
    }

    @Test fun suffixAndFiltersKeepOriginalIdsAndSameTextCandidates() {
        val all = drawerCandidates(listOf("你", "那", "哪", "哪", "哪里", "你"),
            listOf("ni", "na", "na", "nei", "na li", "ni"))
        val suffix = continuationCandidates(all, 2)
        assertEquals(listOf(2, 3, 4, 5), suffix.map { it.index })
        assertEquals(listOf("哪", "哪"), suffix.take(2).map { it.text })
        assertSame(all[2], suffix.first())
        assertEquals(listOf(2), filterCandidates(suffix, "na", CandidateWordFilter.Single).map { it.index })
        assertTrue(continuationCandidates(all, 6).isEmpty())
        val longer = all + DrawerCandidate(6, "那里", "na")
        assertEquals(listOf(2, 3, 4, 5, 6), continuationCandidates(longer, 2).map { it.index })
    }
}
