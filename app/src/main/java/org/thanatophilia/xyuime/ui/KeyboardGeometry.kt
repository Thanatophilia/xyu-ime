package org.thanatophilia.xyuime.ui

/** Shared horizontal geometry for the ordinary keyboard and the full candidate view.
 * Width means the actual key/touch surface, excluding the 3dp spaces between ordinary keys.
 */
internal object KeyboardGeometry {
    const val KEY_GAP_DP = 3f
    const val BOTTOM_KEY_COUNT = 7
    const val EDGE_KEY_WEIGHT = 1.30f
    const val BOTTOM_TOTAL_WEIGHT = 9f
    const val CANDIDATE_COLUMNS = 5

    /** Equal edge keys preserve the old row's total width and the middle keys' proportions. */
    fun edgeKeyWidthDp(rowWidthDp: Float): Float =
        (rowWidthDp - KEY_GAP_DP * (BOTTOM_KEY_COUNT - 1)).coerceAtLeast(0f) *
            EDGE_KEY_WEIGHT / BOTTOM_TOTAL_WEIGHT

    /** One Unicode character occupies one of the five columns; longer candidates span cells. */
    fun candidateSpan(text: String): Int =
        text.codePointCount(0, text.length).coerceIn(1, CANDIDATE_COLUMNS)
}
