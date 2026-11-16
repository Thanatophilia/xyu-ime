package org.thanatophilia.xyuime.ime

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class EditorPolicyPhase2Test {
    @Test fun numericFlagBitsMustNotBeMisreadAsTextCapitalization() {
        val signedDecimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        assertEquals(EditorPolicy.InputKind.SignedDecimal, EditorPolicy.kind(signedDecimal))
        assertEquals(0, EditorPolicy.autoCapsFlags(signedDecimal))
        assertTrue(EditorPolicy.prefersNumbers(signedDecimal))
    }
    @Test fun emailAndUrlDisableCapsAndUseAsciiPunctuation() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI)) {
            val type = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            assertEquals(0, EditorPolicy.autoCapsFlags(type))
            assertTrue(EditorPolicy.asciiPunctuation(type))
            assertEquals("a,b.c@d", EditorPolicy.normalizeText("a，b。c﹫d", type))
        }
    }
    @Test fun ordinaryTextPreservesUserChinesePunctuationAndOnlyHonorsExplicitCaps() {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        assertEquals(InputType.TYPE_TEXT_FLAG_CAP_WORDS, EditorPolicy.autoCapsFlags(type))
        assertEquals("你好，world。", EditorPolicy.normalizeText("你好，world。", type))
        assertFalse(EditorPolicy.asciiPunctuation(type))
    }
    @Test fun numericPasswordRemainsNumericAndSensitive() {
        val type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertEquals(EditorPolicy.InputKind.Integer, EditorPolicy.kind(type))
        assertTrue(EditorPolicy.isPassword(type))
        assertEquals(0, EditorPolicy.autoCapsFlags(type))
    }
    @Test fun phoneAndDateClassVariationCollisionsAreNotPasswords() {
        assertEquals(EditorPolicy.InputKind.Phone, EditorPolicy.kind(InputType.TYPE_CLASS_PHONE))
        assertEquals(EditorPolicy.InputKind.DateTime, EditorPolicy.kind(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE))
        assertFalse(EditorPolicy.isPassword(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE))
    }
}
