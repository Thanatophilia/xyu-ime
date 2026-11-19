package org.thanatophilia.xyuime.ui

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GentleSymbolFlingBehaviorTest {
    @Test fun delegatesExactlyThirtyFivePercentOfReleaseVelocity() = runBlocking {
        var received = Float.NaN
        val delegate = object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                received = initialVelocity
                return initialVelocity / 2f
            }
        }
        val scrollScope = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float = pixels
        }
        val result = with(GentleSymbolFlingBehavior(delegate)) {
            scrollScope.performFling(2_000f)
        }
        assertEquals(700f, received, 0.01f)
        assertEquals(350f, result, 0.01f)
        assertTrue(SYMBOL_VERTICAL_FLING_SCALE in 0f..1f)
    }
}
