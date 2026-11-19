package org.thanatophilia.xyuime.ui

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GentleSymbolFlingBehaviorTest {
    private class TestClock : MonotonicFrameClock {
        var frames = 0
            private set

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
            onFrame(frames++ * 16_000_000L)
    }

    private class RecordingScroll(private val atEdge: Boolean = false) : ScrollScope {
        val deltas = mutableListOf<Float>()

        override fun scrollBy(pixels: Float): Float {
            deltas.add(pixels)
            return if (atEdge) 0f else pixels
        }
    }

    private suspend fun fling(velocity: Float, scope: ScrollScope, maxDistancePx: Float = 32f) =
        with(GentleSymbolFlingBehavior(maxDistancePx)) { scope.performFling(velocity) }

    @Test fun fastFlingMovesOnFirstFrameAndClampsBothDirections() = runBlocking {
        for (direction in listOf(1f, -1f)) {
            val clock = TestClock()
            val scope = RecordingScroll()
            val leftover = withContext(clock) { fling(direction * 100_000f, scope) }
            assertEquals(0f, leftover, 0f)
            assertTrue("The first frame must already move in the intended direction",
                scope.deltas.first() * direction > 0f)
            assertTrue(scope.deltas.all { it * direction > 0f })
            assertEquals(32f * direction, scope.deltas.sum(), 0.01f)
            assertEquals("Finish on the first 16ms frame covering the 90ms window", 7, clock.frames)
        }
    }

    @Test fun ordinaryFlingCoastsLessThanTheLimit() = runBlocking {
        val scope = RecordingScroll()
        withContext(TestClock()) { fling(2_000f, scope) }
        assertEquals(7.2f, scope.deltas.sum(), 0.01f)
    }

    @Test fun slowReleaseAndDisabledAnimationsDoNotWaitForAFrame() = runBlocking {
        val clock = TestClock()
        val scope = RecordingScroll()
        val animationsOff = object : MotionDurationScale { override val scaleFactor = 0f }
        assertEquals(0f, withContext(clock) { fling(100f, scope) }, 0f)
        assertEquals(0f, withContext(clock + animationsOff) { fling(100_000f, scope) }, 0f)
        assertEquals(0, clock.frames)
        assertTrue(scope.deltas.isEmpty())
    }

    @Test fun hittingTheContentEdgeEndsCoastImmediately() = runBlocking {
        val clock = TestClock()
        val scope = RecordingScroll(atEdge = true)
        assertEquals(0f, withContext(clock) { fling(100_000f, scope) }, 0f)
        assertEquals(1, clock.frames)
        assertEquals(1, scope.deltas.size)
    }
}
