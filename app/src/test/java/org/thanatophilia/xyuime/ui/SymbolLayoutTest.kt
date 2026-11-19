package org.thanatophilia.xyuime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolLayoutTest {
    @Test fun groupsKeepExpectedSizesAndOrder() {
        assertEquals(listOf(SymbolGroup.Common, SymbolGroup.Recent, SymbolGroup.ChinesePunctuation,
            SymbolGroup.EnglishSymbols, SymbolGroup.MathUnits, SymbolGroup.Arrows,
            SymbolGroup.Ordinal, SymbolGroup.Scripts), SymbolLayout.groups)
        assertEquals(35, SymbolLayout.commonSymbols.size)
        assertEquals(33, SymbolLayout.chinesePunctuation.size)
        assertEquals(52, SymbolLayout.englishSymbols.size)
        assertEquals(59, SymbolLayout.mathUnits.size)
        assertEquals(18, SymbolLayout.arrows.size)
        assertEquals(30, SymbolLayout.ordinal.size)
        assertEquals(31, SymbolLayout.scripts.size)
        // The recent group is runtime data, not a static list.
        assertTrue(SymbolLayout.items(SymbolGroup.Recent).isEmpty())
    }

    @Test fun everyStaticGroupHasUniqueCharactersAndNames() {
        SymbolLayout.groups.filter { it != SymbolGroup.Recent }.forEach { group ->
            val items = SymbolLayout.items(group)
            assertEquals("${group.key} has duplicate characters", items.size, items.map { it.text }.toSet().size)
            items.forEach { item -> assertTrue("${item.text} needs a name", item.name.isNotBlank()) }
        }
    }

    @Test fun criticalCharactersUseTheirExactCodePoints() {
        val all = SymbolLayout.groups.flatMap { SymbolLayout.items(it) }.associateBy { it.text }
        fun codeOf(text: String) = all.getValue(text).text.codePointAt(0)
        assertEquals(0x00D7, codeOf("×"))
        assertEquals(0x00F7, codeOf("÷"))
        assertEquals(0x2212, codeOf("−"))
        assertEquals(0x2026, codeOf("…"))
        assertEquals(0x2014, codeOf("—"))
        assertEquals(0x3001, codeOf("、"))
        assertEquals(0xFF5E, codeOf("～"))
    }

    @Test fun englishCoreStaysAsciiAndCommaPeriodStayOnTheBottomRow() {
        val en = SymbolLayout.englishSymbols.map { it.text }.toSet()
        listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "-", "_", "=", "+", "/", "\\",
            "'", "\"", ":", ";", "<", ">", "?", "~", "{", "}", "[", "]", "|", "`").forEach {
            assertTrue("English group should contain $it", it in en)
        }
        listOf(",", ".").forEach {
            assertTrue("$it must stay on the context-aware bottom row", it !in en)
        }
    }

    @Test fun specializedGroupsContainTheirRepresentativeCharacters() {
        val cn = SymbolLayout.chinesePunctuation.map { it.text }.toSet()
        listOf("、", "《", "。", "※", "〇").forEach { assertTrue("Chinese group should contain $it", it in cn) }
        val math = SymbolLayout.mathUnits.map { it.text }.toSet()
        listOf("≠", "π", "Ω", "∑", "∈", "½", "₹").forEach { assertTrue("Math group should contain $it", it in math) }
        val arrows = SymbolLayout.arrows.map { it.text }.toSet()
        listOf("↑", "→", "↔", "⇨").forEach { assertTrue("Arrow group should contain $it", it in arrows) }
        val ordinal = SymbolLayout.ordinal.map { it.text }.toSet()
        listOf("①", "⑩", "⑴", "㈠").forEach { assertTrue("Ordinal group should contain $it", it in ordinal) }
        val scripts = SymbolLayout.scripts.map { it.text }.toSet()
        listOf("²", "ⁿ", "₀", "₎").forEach { assertTrue("Script group should contain $it", it in scripts) }
    }

    @Test fun recentItemsMapToNamesAndUnknownCharactersFallBackToThemselves() {
        val items = SymbolLayout.recentItems(listOf("、", "↑", "?"))
        assertEquals("顿号", items[0].name)
        assertEquals("上箭头", items[1].name)
        assertEquals("?", items[2].text)
        assertNotEquals("", SymbolLayout.nameOf("·"))
    }

    @Test fun initialGroupFollowsEditorType() {
        assertEquals(SymbolGroup.Common, SymbolLayout.initialGroup(asciiEditor = false))
        assertEquals(SymbolGroup.EnglishSymbols, SymbolLayout.initialGroup(asciiEditor = true))
    }

    @Test fun swipeNavigationWrapsBothWaysForAllGroups() {
        val page = SymbolLayout.middlePage(SymbolGroup.Common)
        SymbolLayout.groups.forEachIndexed { index, group ->
            assertEquals(group, SymbolLayout.groupAtPage(page + index))
            val previous = SymbolLayout.groups[(SymbolLayout.groups.size - index) % SymbolLayout.groups.size]
            assertEquals(previous, SymbolLayout.groupAtPage(page - index))
        }
        assertEquals(SymbolGroup.Common, SymbolLayout.groupAtPage(page + SymbolLayout.groups.size))
        assertEquals(SymbolGroup.Common, SymbolLayout.groupAtPage(page - SymbolLayout.groups.size))
        for (position in 0 until SymbolLayout.pageCount) {
            for (target in SymbolLayout.groups) {
                val nearest = SymbolLayout.nearestPage(position, target)
                assertTrue(nearest in 0 until SymbolLayout.pageCount)
                assertEquals(target, SymbolLayout.groupAtPage(nearest))
            }
            if (SymbolLayout.isNearPageLimit(position)) {
                val middle = SymbolLayout.middlePage(SymbolLayout.groupAtPage(position))
                assertTrue(!SymbolLayout.isNearPageLimit(middle))
                assertEquals(SymbolLayout.groupAtPage(position), SymbolLayout.groupAtPage(middle))
            }
        }
    }
}
