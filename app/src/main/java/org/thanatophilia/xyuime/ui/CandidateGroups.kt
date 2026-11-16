package org.thanatophilia.xyuime.ui

import java.util.Locale

/** A display projection only: the index always belongs to the unfiltered Rime prefix. */
internal data class DrawerCandidate(val index: Int, val text: String, val syllable: String?)

/** These annotations come from script_translator spelling_hints, not from the entered code.
 * Unannotated or non-spelling comments remain available under All; never invent a reading.
 */
internal fun candidateSyllable(comment: String): String? {
    val spelling = comment.trim().lowercase(Locale.ROOT).replace("u:", "ü").replace('v', 'ü')
    if (!spelling.matches(Regex("[a-zü]+(?:[ '\u2019]+[a-zü]+)*"))) return null
    return spelling.split(' ', '\'', '\u2019').firstOrNull { it.isNotEmpty() }
}

internal fun drawerCandidates(texts: List<String>, comments: List<String>): List<DrawerCandidate> =
    texts.mapIndexed { index, text ->
        DrawerCandidate(index, text, candidateSyllable(comments.getOrNull(index).orEmpty()))
    }

internal fun syllableChoices(candidates: List<DrawerCandidate>): List<String> =
    candidates.mapNotNull { it.syllable }.distinct()

internal fun filterCandidates(candidates: List<DrawerCandidate>, syllable: String?): List<DrawerCandidate> =
    if (syllable == null) candidates else candidates.filter { it.syllable == syllable }
