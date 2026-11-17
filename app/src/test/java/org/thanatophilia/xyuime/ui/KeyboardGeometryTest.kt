package org.thanatophilia.xyuime.ui

import org.junit.Assert.*
import org.junit.Test

class KeyboardGeometryTest {
    @Test fun edgeKeysAndMiddleWeightsFitTheSameBottomRow() {
        for (width in listOf(280f, 310f, 350f, 402f, 640f, 900f)) {
            val side = KeyboardGeometry.edgeKeyWidthDp(width)
            val usable = width - KeyboardGeometry.KEY_GAP_DP * 6
            val middle = usable * 6.4f / KeyboardGeometry.BOTTOM_TOTAL_WEIGHT
            assertEquals("Both edge keys and the five middle keys must fit $width", width,
                side * 2 + middle + KeyboardGeometry.KEY_GAP_DP * 6, 0.001f)
            assertTrue(side > 0)
        }
        assertEquals(0f, KeyboardGeometry.edgeKeyWidthDp(10f), 0f)
    }

    @Test fun singleCharactersRetainFiveSlotsAndWordsOnlyUseMeasuredWidth() {
        assertEquals(5, KeyboardGeometry.CANDIDATE_COLUMNS)
        assertEquals(48, KeyboardGeometry.compactCandidateWidthPx(20, 240, 12))
        assertEquals(52, KeyboardGeometry.compactCandidateWidthPx(40, 240, 12))
        assertEquals(72, KeyboardGeometry.compactCandidateWidthPx(60, 240, 12))
        assertEquals(92, KeyboardGeometry.compactCandidateWidthPx(80, 240, 12))
        assertEquals(112, KeyboardGeometry.compactCandidateWidthPx(100, 240, 12))
        // Four two-character words fit where the old two-whole-slot rule fitted only two.
        assertTrue(KeyboardGeometry.compactCandidateWidthPx(40, 240, 12) * 4 <= 240)
        assertTrue(KeyboardGeometry.compactCandidateWidthPx(100, 240, 12) < 240)
    }

    @Test fun extremelyLongOrEmptyCandidatesStayInsideAvailableWidth() {
        assertEquals(240, KeyboardGeometry.compactCandidateWidthPx(Int.MAX_VALUE, 240, 12))
        assertEquals(48, KeyboardGeometry.compactCandidateWidthPx(0, 240, 12))
        assertEquals(1, KeyboardGeometry.compactCandidateWidthPx(20, 0, 12))
        assertEquals(48, KeyboardGeometry.compactCandidateWidthPx(-8, 240, -2))
    }
}
