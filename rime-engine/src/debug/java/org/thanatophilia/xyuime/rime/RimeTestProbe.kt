package org.thanatophilia.xyuime.rime

/** Debug-only backend observations for isolated D6 test processes. Never compiled into release. */
internal object RimeTestProbe {
    init { System.loadLibrary("xyu_rime") }
    external fun isReady(): Boolean
    /** Caller destroys all sessions first. Native side also cleans sessions before a safe API export.
     * Returns only the commit count of a fixed artificial entry, never the database contents.
     */
    external fun learnedCommits(exportPath: String, text: String, reading: String): Int
}
