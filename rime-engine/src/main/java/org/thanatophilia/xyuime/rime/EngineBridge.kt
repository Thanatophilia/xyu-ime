package org.thanatophilia.xyuime.rime

/**
 * Minimal engine seam used by the IME frontend. [RimeEngine] is the production implementation;
 * tests inject a controllable fake through XyuImeService.engineOverride to verify queued input,
 * stale-callback invalidation and editor switching without real deployment timing.
 */
interface EngineBridge {
    /** Queue one engine operation; [callback] arrives later, on the main thread. */
    fun request(sessionId: Int, operation: Int, key: Int, limit: Int, callback: (RimeOutput) -> Unit)

    /** Cancel in-flight results and drop the current session. */
    fun reset()

    /** Release native resources; called once when the IME service is destroyed. */
    fun close()
}