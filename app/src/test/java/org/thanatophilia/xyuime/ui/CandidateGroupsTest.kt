package org.thanatophilia.xyuime.ui

import org.junit.Assert.*
import org.junit.Test

class CandidateGroupsTest {
    @Test fun filteringKeepsNativeIndicesAndRankIncludingDuplicateWords() {
        val candidates = drawerCandidates(listOf("你", "那", "呢", "哪", "哪", "你好"),
            listOf("ni", "na", "ne", "na", "nei", "ni hao"))
        assertEquals(listOf("ni", "na", "ne", "nei"), syllableChoices(candidates))
        assertEquals(listOf(1, 3), filterCandidates(candidates, "na").map { it.index })
        assertEquals(listOf(0, 5), filterCandidates(candidates, "ni").map { it.index })
        assertEquals(candidates, filterCandidates(candidates, null))
    }

    @Test fun missingOrNonPhoneticCommentIsNotGuessed() {
        val candidates = drawerCandidates(listOf("你", "哈哈", "未知", "注"), listOf("ni", "☯", ""))
        assertEquals(listOf("ni"), syllableChoices(candidates))
        assertEquals(4, filterCandidates(candidates, null).size)
        assertEquals(listOf(0), filterCandidates(candidates, "ni").map { it.index })
        assertNull(candidateSyllable("a12"))
        assertNull(candidateSyllable("拼音"))
    }

    @Test fun delimitersAndUmlautNormalizeWithoutChangingCandidateIdentity() {
        assertEquals("xi", candidateSyllable(" xi'an "))
        assertEquals("ni", candidateSyllable("ni’hao"))
        assertEquals("nü", candidateSyllable("nv"))
        assertEquals("lü", candidateSyllable("lu:"))
        assertEquals("ni", candidateSyllable("NI HAO"))
    }

    @Test fun wordTypeIntersectsFirstSyllableWithoutGuessingMissingComments() {
        val candidates = drawerCandidates(
            listOf("你", "你好", "那", "那里", "未知", "注"),
            listOf("ni", "ni hao", "na", "na li", ""),
        )
        assertEquals(listOf(0), filterCandidates(candidates, "ni", CandidateWordFilter.Single).map { it.index })
        assertEquals(listOf(1), filterCandidates(candidates, "ni", CandidateWordFilter.Phrase).map { it.index })
        assertEquals(listOf(1, 3, 4), filterCandidates(candidates, null, CandidateWordFilter.Phrase).map { it.index })
        assertEquals(listOf(0, 2, 5), filterCandidates(candidates, null, CandidateWordFilter.Single).map { it.index })
        assertEquals(candidates, filterCandidates(candidates, null, CandidateWordFilter.All))
    }

    @Test fun wordTypeCountsUnicodeCodePointsAcrossTheWholeCandidate() {
        val supplementaryHan = "\uD840\uDC00" // U+20000, one character represented by two UTF-16 units.
        val candidates = drawerCandidates(
            listOf("", "字", supplementaryHan, "词语", supplementaryHan + "字", "！", "A字", "e\u0301", " "),
            emptyList(),
        )
        assertEquals(listOf(1, 2, 5, 8), filterCandidates(candidates, null, CandidateWordFilter.Single).map { it.index })
        assertEquals(listOf(3, 4, 6, 7), filterCandidates(candidates, null, CandidateWordFilter.Phrase).map { it.index })
        assertEquals(candidates, filterCandidates(candidates, null))
    }

    @Test fun noMatchingCandidatesDoesNotFallBackOrFabricateResults() {
        val candidates = drawerCandidates(listOf("你", "那里", ""), listOf("ni", "na li", "ni"))
        assertTrue(filterCandidates(candidates, "ni", CandidateWordFilter.Phrase).isEmpty())
        assertTrue(filterCandidates(candidates, "na", CandidateWordFilter.Single).isEmpty())
        for (type in CandidateWordFilter.values()) {
            assertTrue(filterCandidates(candidates, "missing", type).isEmpty())
            assertTrue(filterCandidates(emptyList(), null, type).isEmpty())
        }
    }

    @Test fun wordTypePreservesOrderGlobalIndicesAndDuplicateCandidates() {
        val candidates = listOf(
            DrawerCandidate(42, "你好", "ni"),
            DrawerCandidate(7, "你", "ni"),
            DrawerCandidate(103, "你好", "ni"),
            DrawerCandidate(9, "那里", "na"),
        )
        val phrases = filterCandidates(candidates, null, CandidateWordFilter.Phrase)
        assertEquals(listOf(42, 103, 9), phrases.map { it.index })
        assertSame(candidates[0], phrases[0])
        assertSame(candidates[2], phrases[1])
        assertSame(candidates[3], phrases[2])
        assertEquals(listOf(42, 103), filterCandidates(candidates, "ni", CandidateWordFilter.Phrase).map { it.index })
        assertEquals(listOf(7), filterCandidates(candidates, null, CandidateWordFilter.Single).map { it.index })
        assertSame(candidates, filterCandidates(candidates, null))
    }

    @Test fun loadingMoreKeepsEarlierIndicesAndAddsNewSyllables() {
        val short = drawerCandidates(listOf("你", "那"), listOf("ni", "na"))
        val longer = drawerCandidates(listOf("你", "那", "呢", "娜"), listOf("ni", "na", "ne", "na"))
        assertEquals(short, longer.take(2))
        assertEquals(listOf(1, 3), filterCandidates(longer, "na").map { it.index })
        assertEquals(listOf("ni", "na", "ne"), syllableChoices(longer))
    }
}
