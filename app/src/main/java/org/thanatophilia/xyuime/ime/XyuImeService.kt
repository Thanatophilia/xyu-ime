package org.thanatophilia.xyuime.ime

import android.content.res.Configuration
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.inputmethodservice.InputMethodService
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.thanatophilia.xyuime.ui.KeyboardAction
import org.thanatophilia.xyuime.ui.KeyboardPage
import org.thanatophilia.xyuime.ui.KeyboardUiState
import org.thanatophilia.xyuime.ui.XyuKeyboard

/** A normal system IME window. No overlay, accessibility, or OEM setting access. */
class XyuImeService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private var keyboardState by mutableStateOf(KeyboardUiState())
    private var composeView: ComposeView? = null
    private var toast: Toast? = null
    private var inputViewActive by mutableStateOf(false)
    private var inputSession by mutableStateOf(0)

    override fun onCreate() {
        savedState.performAttach()
        savedState.performRestore(null)
        super.onCreate()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        installWindowOwners()
    }

    private fun installWindowOwners() {
        // WindowRecomposer discovers its owner through the root, above ComposeView.
        window?.window?.decorView?.apply {
            setViewTreeLifecycleOwner(this@XyuImeService)
            setViewTreeViewModelStoreOwner(this@XyuImeService)
            setViewTreeSavedStateRegistryOwner(this@XyuImeService)
        }
    }

    override fun onCreateInputView(): View {
        installWindowOwners()
        composeView?.disposeComposition()
        val view = ComposeView(this).apply {
            // The framework owns window positioning. Compose consumes remaining system
            // footer/cutout insets once, never its own IME inset or an OEM private value.
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setViewTreeLifecycleOwner(this@XyuImeService)
            setViewTreeViewModelStoreOwner(this@XyuImeService)
            setViewTreeSavedStateRegistryOwner(this@XyuImeService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                key(inputViewActive, inputSession) {
                    XyuKeyboard(state = keyboardState, onAction = { action ->
                        if (inputViewActive) handleAction(action)
                    })
                }
            }
        }
        composeView = view
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputSession++
        keyboardState = KeyboardUiState(
            page = if (EditorPolicy.prefersNumbers(attribute?.inputType ?: 0)) KeyboardPage.Numbers else KeyboardPage.Letters,
            actionLabel = EditorPolicy.enterAction(
                attribute?.imeOptions ?: 0, attribute?.actionId ?: 0, attribute?.actionLabel?.toString(),
            ).label,
        )
        updateAutoCaps()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputViewActive = true
        if (!registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        if (!registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        window?.window?.let { imeWindow ->
            WindowCompat.getInsetsController(imeWindow, imeWindow.decorView).isAppearanceLightNavigationBars =
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        }
        if (!registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        if (!registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onWindowHidden() {
        pauseViewLifecycle()
        super.onWindowHidden()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        pauseViewLifecycle()
        toast?.cancel()
        super.onFinishInputView(finishingInput)
    }

    private fun pauseViewLifecycle() {
        inputViewActive = false
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onFinishInput() {
        keyboardState = KeyboardUiState()
        super.onFinishInput()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        composeView?.requestLayout()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        // Phase one exposes only en-US. Later Rime languages will be selected here.
        keyboardState = keyboardState.copy(page = KeyboardPage.Letters, uppercase = false, capsLock = false)
        updateAutoCaps()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCaps()
    }

    private fun updateAutoCaps() {
        val info = currentInputEditorInfo ?: return
        if (keyboardState.capsLock || EditorPolicy.isPassword(info.inputType)) return
        val flags = info.inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        if (flags != 0) keyboardState = keyboardState.copy(uppercase = (currentInputConnection?.getCursorCapsMode(flags) ?: 0) != 0)
    }

    private fun handleAction(action: KeyboardAction) {
        when (action) {
            is KeyboardAction.Text -> commitText(action.value)
            KeyboardAction.Space -> commitText(" ")
            KeyboardAction.Enter -> enter()
            KeyboardAction.Backspace -> backspace()
            KeyboardAction.Shift -> keyboardState = keyboardState.copy(uppercase = !keyboardState.uppercase, capsLock = false)
            KeyboardAction.CapsLock -> keyboardState = keyboardState.copy(uppercase = !keyboardState.capsLock, capsLock = !keyboardState.capsLock)
            KeyboardAction.ToggleNumbers -> keyboardState = keyboardState.copy(page = if (keyboardState.page == KeyboardPage.Numbers) KeyboardPage.Letters else KeyboardPage.Numbers)
            KeyboardAction.ToggleSymbols -> keyboardState = keyboardState.copy(page = if (keyboardState.page == KeyboardPage.Symbols) KeyboardPage.Letters else KeyboardPage.Symbols)
            KeyboardAction.NextLanguage -> {
                toast?.cancel()
                toast = Toast.makeText(this, "当前仅启用 English；中文将在 Rime 阶段加入", Toast.LENGTH_SHORT).also { it.show() }
            }
            KeyboardAction.PickInputMethod -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    private fun commitText(text: String) {
        val connection = currentInputConnection ?: return
        val inputType = currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL
        val committed = if (EditorPolicy.isPassword(inputType)) text.map { char ->
            when (char) {
                '。' -> '.'
                in '！'..'～' -> (char.code - 0xFEE0).toChar()
                else -> char
            }
        }.joinToString("") else text
        if (inputType == InputType.TYPE_NULL && committed.all { it.code < 128 }) {
            committed.forEach { sendKeyChar(it) }
        } else {
            connection.commitText(committed, 1)
        }
        if (!keyboardState.capsLock) keyboardState = keyboardState.copy(uppercase = false)
    }

    private fun backspace() {
        val connection = currentInputConnection ?: return
        if (currentInputEditorInfo?.inputType == InputType.TYPE_NULL) {
            key(connection, KeyEvent.KEYCODE_DEL)
            return
        }
        if (!connection.getSelectedText(0).isNullOrEmpty()) {
            connection.commitText("", 1)
        } else if (!connection.deleteSurroundingTextInCodePoints(1, 0)) {
            // Legacy editors occasionally do not implement the code-point API.
            val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            val units = if (before.length >= 2 && Character.isSurrogatePair(before[before.lastIndex - 1], before.last())) 2 else 1
            connection.deleteSurroundingText(units, 0)
        }
        updateAutoCaps()
    }

    private fun enter() {
        val connection = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        val action = EditorPolicy.enterAction(info.imeOptions, info.actionId, info.actionLabel?.toString())
        if (action.id != null && connection.performEditorAction(action.id)) return
        if (info.inputType == InputType.TYPE_NULL) key(connection, KeyEvent.KEYCODE_ENTER) else connection.commitText("\n", 1)
    }

    private fun key(connection: InputConnection, code: Int) {
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, -1, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
    }

    override fun onDestroy() {
        toast?.cancel()
        pauseViewLifecycle()
        composeView?.disposeComposition()
        composeView = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }
}
