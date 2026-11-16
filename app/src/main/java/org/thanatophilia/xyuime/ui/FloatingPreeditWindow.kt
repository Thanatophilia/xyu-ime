package org.thanatophilia.xyuime.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A non-touchable child window of the IME, never part of the input view's measurement.
 * Uses the IME window token, not an application-overlay permission or a second IME inset.
 * All methods are main-thread only. InputConnection composition remains owned by the service.
 */
class FloatingPreeditWindow(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val stateOwner: SavedStateRegistryOwner,
    private val modelOwner: ViewModelStoreOwner,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
    private var anchor: View? = null
    private var parentComposition: CompositionContext? = null
    private var popup: PopupWindow? = null
    private var content: ComposeView? = null
    private var bounds = Rect()
    private var code by mutableStateOf("")
    private var pending = false
    private val renderTask = Runnable { pending = false; render() }

    fun bind(view: View) {
        dispose()
        anchor = view
        view.addOnAttachStateChangeListener(this)
        view.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    fun setParentComposition(value: CompositionContext) {
        if (parentComposition !== value) {
            dismiss()
            parentComposition = value
            schedule()
        }
    }

    /** Bounds relative to the IME window, obtained from the actual Compose keyboard surface. */
    fun setKeyboardBounds(value: Rect) {
        if (bounds != value) { bounds = Rect(value); schedule() }
    }

    fun update(preedit: String, visible: Boolean) {
        val next = if (visible) preedit else ""
        if (code != next) code = next
        if (next.isEmpty()) dismiss() else schedule()
    }

    override fun onGlobalLayout() = schedule()
    override fun onViewAttachedToWindow(view: View) = schedule()
    override fun onViewDetachedFromWindow(view: View) = dismiss()

    private fun schedule() {
        val view = anchor ?: return
        if (!pending) { pending = true; view.post(renderTask) }
    }

    private fun render() {
        val view = anchor ?: return
        val parent = parentComposition ?: return
        if (code.isEmpty() || bounds.isEmpty || !view.isAttachedToWindow || !view.isShown || view.windowToken == null) {
            dismiss()
            return
        }
        val screen = IntArray(2)
        val window = IntArray(2)
        view.getLocationOnScreen(screen)
        view.getLocationInWindow(window)
        val keyboard = Rect(bounds).apply { offset(screen[0] - window[0], screen[1] - window[1]) }
        val frame = Rect().also { view.getWindowVisibleDisplayFrame(it) }
        val density = context.resources.displayMetrics
        val left = max(keyboard.left, frame.left)
        val width = min(keyboard.right, frame.right) - left
        val gap = ceil(4 * density.density).toInt()
        val available = keyboard.top - max(frame.top, 0) - gap
        val height = min(ceil(20 * density.scaledDensity + 12 * density.density).toInt(), available)
        if (width <= 0 || height <= 0) { dismiss(); return }
        val top = keyboard.top - gap - height
        // Attached subwindows use parent-window coordinates (also on API 26–28).
        val x = left - (screen[0] - window[0])
        val y = top - (screen[1] - window[1])
        try {
            if (popup == null) {
                val bubble = ComposeView(context).apply {
                    setViewTreeLifecycleOwner(lifecycleOwner)
                    setViewTreeSavedStateRegistryOwner(stateOwner)
                    setViewTreeViewModelStoreOwner(modelOwner)
                    // Measuring a standalone popup cannot discover the IME decor's recomposer.
                    setParentCompositionContext(parent)
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        val dark = isSystemInDarkTheme()
                        val shape = RoundedCornerShape(8.dp)
                        Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.BottomLeft) {
                            Text(code, color = if (dark) Color(0xFFE1E8E4) else Color(0xFF1A211F),
                                fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .background(if (dark) Color(0xFF303936) else Color(0xFFF6F8F6), shape)
                                    .border(1.dp, if (dark) Color(0xFF89968F) else Color(0xFF7B8984), shape)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .semantics {
                                        testTagsAsResourceId = true
                                        testTag = "floating_preedit"
                                        contentDescription = "悬浮编码"
                                    })
                        }
                    }
                }
                content = bubble
                popup = PopupWindow(bubble, width, height, false).apply {
                    windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
                    isFocusable = false
                    isTouchable = false
                    isOutsideTouchable = false
                    inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    isClippingEnabled = false
                    isAttachedInDecor = false
                    animationStyle = 0
                    setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                }
                // showAtLocation uses this view's IME window token; physical left is intentional.
                popup!!.showAtLocation(view, Gravity.TOP or Gravity.LEFT, x, y)
            } else {
                popup!!.update(x, y, width, height)
            }
        } catch (_: WindowManager.BadTokenException) {
            // A keyboard can be hidden between layout and this posted update.
            dismiss()
        } catch (_: IllegalStateException) {
            dismiss()
        }
    }

    private fun dismiss() {
        anchor?.removeCallbacks(renderTask)
        pending = false
        popup?.dismiss()
        popup = null
        content?.disposeComposition()
        content = null
    }

    fun dispose() {
        dismiss()
        anchor?.let { view ->
            view.removeOnAttachStateChangeListener(this)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(this)
        }
        anchor = null
        parentComposition = null
        bounds = Rect()
        code = ""
    }
}
