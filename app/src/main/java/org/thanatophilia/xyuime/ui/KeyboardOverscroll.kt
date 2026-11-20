package org.thanatophilia.xyuime.ui

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Applies only to keyboard content, not the app's settings/trial screens. Suppressing the
 * overscroll effect removes edge stretch/glow and its gesture interception; it does not
 * replace fling physics or the horizontal pager's settling animation.
 */
@Composable
internal fun NoKeyboardOverscroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides null, content = content)
}
