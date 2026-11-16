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

    @Test fun loadingMoreKeepsEarlierIndicesAndAddsNewSyllables() {
        val short = drawerCandidates(listOf("你", "那"), listOf("ni", "na"))
        val longer = drawerCandidates(listOf("你", "那", "呢", "娜"), listOf("ni", "na", "ne", "na"))
        assertEquals(short, longer.take(2))
        assertEquals(listOf(1, 3), filterCandidates(longer, "na").map { it.index })
        assertEquals(listOf("ni", "na", "ne"), syllableChoices(longer))
    }
}
