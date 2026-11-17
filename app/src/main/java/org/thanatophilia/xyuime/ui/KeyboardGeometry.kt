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

    const val CANDIDATE_GUTTER_DP = 12f

    /** All-mode words wrap at their measured text width plus a small, consistent gutter.
     * A single-character slot remains the minimum, but a two-character word no longer
     * reserves two whole slots. Widths are pixel spans, so there is no coarse grid rounding.
     */
    fun compactCandidateWidthPx(textWidthPx: Int, availableWidthPx: Int, gutterPx: Int): Int {
        val available = availableWidthPx.coerceAtLeast(1)
        val minimum = (available / CANDIDATE_COLUMNS).coerceAtLeast(1)
        val requested = textWidthPx.coerceAtLeast(0).toLong() + gutterPx.coerceAtLeast(0)
        return requested.coerceAtLeast(minimum.toLong()).coerceAtMost(available.toLong()).toInt()
    }
}
