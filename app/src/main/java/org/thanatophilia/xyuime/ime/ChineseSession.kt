package org.thanatophilia.xyuime.ime

/** Main-thread gate. A candidate index belongs to exactly one composition revision. */
internal class ChineseSession {
    var epoch = 0
        private set
    var sequence = 0L
        private set
    var revision = 0L
        private set
    var limit = INITIAL_LIMIT
        private set

    fun invalidate() { epoch++; sequence = 0; compositionChanged() }
    fun accept(owner: Int, serial: Long): Boolean {
        if (owner != epoch || serial <= sequence) return false
        sequence = serial
        return true
    }
    fun compositionChanged() { revision++; limit = INITIAL_LIMIT }
    fun matches(candidateRevision: Long) = candidateRevision == revision
    fun more(): Int { limit = (limit + INITIAL_LIMIT).coerceAtMost(MAX_LIMIT); return limit }
    companion object {
        const val INITIAL_LIMIT = 64
        // The current wrapper caps its read-only candidate iterator at this limit.
        const val MAX_LIMIT = 4096
    }
}
