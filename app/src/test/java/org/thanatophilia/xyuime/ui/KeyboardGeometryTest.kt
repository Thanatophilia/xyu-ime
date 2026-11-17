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

    @Test fun exactlyFiveSingleCharactersFitOneRowAndWordsSpanTheirLength() {
        assertEquals(5, KeyboardGeometry.CANDIDATE_COLUMNS)
        assertEquals(1, KeyboardGeometry.candidateSpan("字"))
        assertEquals(1, KeyboardGeometry.candidateSpan("\uD840\uDC00"))
        assertEquals(2, KeyboardGeometry.candidateSpan("你好"))
        assertEquals(5, KeyboardGeometry.candidateSpan("一二三四五六七"))
        assertEquals(1, KeyboardGeometry.candidateSpan(""))
    }
}
