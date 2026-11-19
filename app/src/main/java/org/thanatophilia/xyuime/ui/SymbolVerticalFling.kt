package org.thanatophilia.xyuime.ui

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal const val SYMBOL_VERTICAL_MAX_COAST_DP = 16
private const val COAST_DURATION_NANOS = 90_000_000L
private const val COAST_DURATION_SECONDS = 0.09f
private const val FIRST_FRAME_STEP_NANOS = 8_000_000L
private const val RELEASE_VELOCITY_SCALE = 0.08f

/** Only used by the vertical symbol grid. Dragging still belongs to LazyVerticalGrid. */
internal class GentleSymbolFlingBehavior(private val maxDistancePx: Float) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (!initialVelocity.isFinite() || coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return 0f

        // Quadratic deceleration: v starts at 8% of release speed (or less when capped)
        // and reaches zero after 90ms. Do not inherit a long decay or animation scale.
        val distance = (initialVelocity * RELEASE_VELOCITY_SCALE * COAST_DURATION_SECONDS / 2f)
            .coerceIn(-maxDistancePx, maxDistancePx)
        if (abs(distance) < 1f) return 0f

        var start: Long? = null
        var applied = 0f
        while (true) {
            val time = withFrameNanos { it }
            // Move on the first available frame rather than spending a frame at delta zero.
            val startTime = start ?: (time - FIRST_FRAME_STEP_NANOS).also { start = it }
            val progress = ((time - startTime).toFloat() / COAST_DURATION_NANOS).coerceIn(0f, 1f)
            val remaining = 1f - progress
            val target = distance * (1f - remaining * remaining)
            val delta = target - applied
            // Finish at a grid edge without passing residual momentum to another scrollable.
            if (delta != 0f && abs(delta - scrollBy(delta)) > 0.5f) return 0f
            applied = target
            if (progress >= 1f) return 0f
        }
    }
}
