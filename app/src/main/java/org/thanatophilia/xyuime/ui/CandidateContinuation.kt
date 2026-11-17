package org.thanatophilia.xyuime.ui

/** Positions come from LazyRow layout, before clipping or minimum touch-target expansion. */
internal data class StripCandidateBounds(val index: Int, val offset: Int, val size: Int)

/** First native candidate not completely displayed in the current horizontal viewport.
 * null means layout is not ready; a partially visible trailing word must not be skipped.
 * If an oversized word prevents any complete cell from fitting, the drawer starts with it.
 */
internal fun candidateContinuationStart(
    items: List<StripCandidateBounds>,
    viewportStart: Int,
    viewportEnd: Int,
    totalItems: Int,
): Int? {
    if (totalItems <= 0 || viewportEnd <= viewportStart) return null
    val intersecting = items.filter {
        it.index in 0 until totalItems && it.size > 0 && it.offset < viewportEnd &&
            it.offset.toLong() + it.size > viewportStart
    }
    val lastComplete = intersecting.filter {
        it.offset >= viewportStart && it.offset.toLong() + it.size <= viewportEnd
    }.maxOfOrNull { it.index }
    return lastComplete?.plus(1) ?: intersecting.minOfOrNull { it.index }
}

/** Never deduplicate by text: equally-spelled words can have distinct readings/native IDs. */
internal fun continuationCandidates(candidates: List<DrawerCandidate>, start: Int): List<DrawerCandidate> =
    if (start <= 0) candidates else candidates.filter { it.index >= start }
