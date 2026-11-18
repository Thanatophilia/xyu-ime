package org.thanatophilia.xyuime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D5 symbol table: exact insert text, code points, and group navigation. */
class SymbolLayoutTest {
    @Test fun groupsKeepExpectedSizesAndOrder() {
        assertEquals(listOf(SymbolGroup.ChinesePunctuation, SymbolGroup.EnglishSymbols, SymbolGroup.MathUnits),
            SymbolLayout.groups)
        assertEquals(27, SymbolLayout.chinesePunctuation.size)
        assertEquals(26, SymbolLayout.englishSymbols.size)
        assertEquals(22, SymbolLayout.mathUnits.size)
    }

    @Test fun everyGroupIsDuplicateFreeAndFullyNamed() {
        SymbolLayout.groups.forEach { group ->
            val items = SymbolLayout.items(group)
            assertEquals("$group must not repeat characters", items.size, items.map { it.text }.toSet().size)
            assertTrue("$group must name every key", items.all { it.name.isNotBlank() })
        }
    }

    @Test fun criticalCharactersUseTheirExactCodePoints() {
        val all = SymbolLayout.groups.flatMap { SymbolLayout.items(it) }.associateBy { it.text }
        fun codeOf(text: String) = all.getValue(text).text.codePointAt(0)
        assertEquals(0x00D7, codeOf("×")) // multiplication sign, not letter x
        assertEquals(0x00F7, codeOf("÷")) // division sign, not slash
        assertEquals(0x2212, codeOf("−")) // minus sign, not ASCII hyphen
        assertEquals(0x2026, codeOf("…")) // horizontal ellipsis, not three periods
        assertEquals(0x2014, codeOf("—")) // em dash
        assertEquals(0x3001, codeOf("、")) // enumeration comma
        assertEquals(0xFF5E, codeOf("～")) // full-width tilde in the Chinese group
    }

    @Test fun englishGroupStaysPureAscii() {
        SymbolLayout.englishSymbols.forEach { item ->
            val code = item.text.single().code
            assertTrue("${item.text} must be printable ASCII", code in 0x20..0x7E)
        }
    }

    @Test fun chineseGroupIsNonAsciiPunctuationOnly() {
        SymbolLayout.chinesePunctuation.forEach { item ->
            assertTrue("${item.text} must not be a plain ASCII letter/digit", item.text.single().code > 0x7F)
        }
    }

    @Test fun initialGroupFollowsEditorType() {
        assertEquals(SymbolGroup.ChinesePunctuation, SymbolLayout.initialGroup(asciiEditor = false))
        assertEquals(SymbolGroup.EnglishSymbols, SymbolLayout.initialGroup(asciiEditor = true))
    }

    @Test fun swipeNavigationWrapsBothWays() {
        val page = SymbolLayout.middlePage(SymbolGroup.ChinesePunctuation)
        assertEquals(SymbolGroup.EnglishSymbols, SymbolLayout.groupAtPage(page + 1))
        assertEquals(SymbolGroup.MathUnits, SymbolLayout.groupAtPage(page + 2))
        assertEquals(SymbolGroup.ChinesePunctuation, SymbolLayout.groupAtPage(page + 3))
        assertEquals(SymbolGroup.MathUnits, SymbolLayout.groupAtPage(page - 1))
        assertEquals(SymbolGroup.EnglishSymbols, SymbolLayout.groupAtPage(page - 2))
        assertEquals(SymbolGroup.ChinesePunctuation, SymbolLayout.groupAtPage(page - 3))
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
