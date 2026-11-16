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

/** Whole-candidate code point counts, including punctuation and mixed text; no trimming. */
internal enum class CandidateWordFilter { All, Single, Phrase }

/** Intersects the first-syllable and word-type filters without changing rank or native indices.
 * Empty text is available only under All. Supplementary characters count as one code point.
 */
internal fun filterCandidates(
    candidates: List<DrawerCandidate>,
    syllable: String?,
    type: CandidateWordFilter = CandidateWordFilter.All,
): List<DrawerCandidate> =
    if (syllable == null && type == CandidateWordFilter.All) candidates
    else candidates.filter { candidate ->
        (syllable == null || candidate.syllable == syllable) && when (type) {
            CandidateWordFilter.All -> true
            CandidateWordFilter.Single -> candidate.text.codePointCount(0, candidate.text.length) == 1
            CandidateWordFilter.Phrase -> candidate.text.codePointCount(0, candidate.text.length) > 1
        }
    }
