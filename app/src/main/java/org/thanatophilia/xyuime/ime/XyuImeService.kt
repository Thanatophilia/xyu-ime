package org.thanatophilia.xyuime.ime

import android.content.res.Configuration
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ClipDescription
import android.view.inputmethod.ExtractedTextRequest
import org.thanatophilia.xyuime.ui.CursorDirection
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
    private val caseState = CaseState()
    private var selectionStart = -1
    private var selectionEnd = -1
    private var selectionAnchor: Int? = null
    private var requestedSelection: Pair<Int, Int>? = null
    private lateinit var rime: org.thanatophilia.xyuime.rime.RimeEngine
    private val chineseSession = ChineseSession()
    private val actions = ArrayDeque<KeyboardAction>()
    private var engineBusy = false
    private var chinesePreferred = false
    private var chineseAllowed = false
    private var composing = false
    private var applyingComposition = false
    private var composingEnd = -1
    private var lastPreedit = ""
    private var engineReady = false
    private var preparingChinese = false
    private val expectedEditorSelections = mutableSetOf<Pair<Int, Int>>()

    private val chinese: Boolean get() = chinesePreferred && chineseAllowed && engineReady

    private fun resetChinese(clearActions: Boolean = true) {
        chineseSession.invalidate()
        if (clearActions) actions.clear()
        expectedEditorSelections.clear()
        engineBusy = false
        preparingChinese = false
        composing = false
        composingEnd = -1
        lastPreedit = ""
        if (::rime.isInitialized) rime.reset()
        keyboardState = keyboardState.copy(preedit = "", candidates = emptyList(),
            candidateRevision = chineseSession.revision, candidatesExpanded = false, loadingCandidates = false)
    }

    private fun configureChinese(info: EditorInfo?) {
        val type = info?.inputType ?: 0
        chineseAllowed = type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            !EditorPolicy.isPassword(type) && !EditorPolicy.asciiPunctuation(type) &&
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        keyboardState = keyboardState.copy(chinese = chinese,
            inputHint = if (chinese) "中文拼音" else keyboardState.inputHint)
        if (chineseAllowed && !engineReady) prepareChinese() else if (chinese) requestRime()
    }

    private fun prepareChinese() {
        if (preparingChinese) return
        preparingChinese = true
        val owner = chineseSession.epoch
        keyboardState = keyboardState.copy(inputHint = "正在准备词库 · English")
        rime.request(owner) { output ->
            if (!chineseSession.accept(output.inputSessionId, output.sequence)) return@request
            preparingChinese = false
            engineReady = output.error == null
            // Deployment only opens the Chinese entry; never switches what the user is typing.
            chinesePreferred = false
            keyboardState = keyboardState.copy(chinese = false,
                inputHint = if (engineReady) "English" else "中文暂不可用 · 点击地球重试")
        }
    }

    private fun requestRime(operation: Int = 0, key: Int = 0, more: Boolean = false,
                            fallback: String? = null, after: (() -> Unit)? = null) {
        engineBusy = true
        keyboardState = keyboardState.copy(loadingCandidates = true)
        val owner = chineseSession.epoch
        rime.request(owner, operation, key, chineseSession.limit) { output ->
            if (!chineseSession.accept(output.inputSessionId, output.sequence)) return@request
            engineBusy = false
            keyboardState = keyboardState.copy(loadingCandidates = false)
            if (output.error != null) {
                // Keep existing composing text as literal text; never delete an unknown range.
                currentInputConnection?.finishComposingText()
                composing = false
                chinesePreferred = false
                engineReady = false
                resetChinese(clearActions = false)
                keyboardState = keyboardState.copy(chinese = false, inputHint = "中文暂不可用 · English")
                updateAutoCaps()
                drainActions()
                return@request
            }
            if (!more) {
                chineseSession.compositionChanged()
                val connection = currentInputConnection
                val base = if (composingEnd >= 0) composingEnd - lastPreedit.length
                    else minOf(selectionStart, selectionEnd).coerceAtLeast(0)
                val nextEnd = base + output.commitText.length + output.preedit.length
                expectedEditorSelections.add(nextEnd to nextEnd)
                applyingComposition = true
                connection?.beginBatchEdit()
                try {
                    if (output.commitText.isNotEmpty()) connection?.commitText(output.commitText, 1)
                    if (output.hasComposition) {
                        connection?.setComposingText(output.preedit, 1)
                    } else if (composing && output.commitText.isEmpty()) {
                        connection?.setComposingText("", 1)
                    }
                    if (!output.hasComposition) connection?.finishComposingText()
                    composing = output.hasComposition
                    lastPreedit = output.preedit
                    composingEnd = if (output.hasComposition) nextEnd else -1
                } finally {
                    connection?.endBatchEdit()
                    applyingComposition = false
                }
            }
            keyboardState = keyboardState.copy(preedit = output.preedit,
                candidates = output.candidates, candidateRevision = chineseSession.revision,
                highlightedCandidate = output.highlightedIndex,
                hasMoreCandidates = !output.isLastPage && chineseSession.limit < ChineseSession.MAX_LIMIT,
                candidatesExpanded = keyboardState.candidatesExpanded && output.hasComposition)
            if (!output.consumed && fallback != null && output.commitText.isEmpty() && !output.hasComposition)
                commitText(fallback)
            after?.invoke()
            drainActions()
        }
    }

    private fun drainActions() {
        while (!engineBusy && actions.isNotEmpty()) dispatchAction(actions.removeFirst())
    }

    /** Return is handled by Rime's express_editor: commit raw input, not the candidate. */
    private fun finishRaw(after: () -> Unit) {
        if (composing) requestRime(1, 0xff0d, after = after) else after()
    }

    private fun switchLanguage(preferred: Boolean) {
        finishRaw {
            chinesePreferred = preferred
            resetChinese(clearActions = false)
            caseState.reset()
            keyboardState = keyboardState.copy(chinese = chinese,
                inputHint = if (chinese) "中文拼音" else "English", editing = false)
            updateAutoCaps()
            if (chinese) requestRime()
        }
    }

    override fun onCreate() {
        savedState.performAttach()
        savedState.performRestore(null)
        super.onCreate()
        rime = org.thanatophilia.xyuime.rime.RimeEngine(this)
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
        resetChinese()
        caseState.reset()
        selectionStart = attribute?.initialSelStart ?: -1
        selectionEnd = attribute?.initialSelEnd ?: -1
        selectionAnchor = null
        requestedSelection = null
        val type = attribute?.inputType ?: 0
        val kind = EditorPolicy.kind(type)
        val ascii = EditorPolicy.asciiPunctuation(type)
        keyboardState = KeyboardUiState(
            page = if (EditorPolicy.prefersNumbers(type)) KeyboardPage.Numbers else KeyboardPage.Letters,
            actionLabel = EditorPolicy.enterAction(
                attribute?.imeOptions ?: 0, attribute?.actionId ?: 0, attribute?.actionLabel?.toString(),
            ).label,
            editing = false,
            selecting = false,
            sensitive = EditorPolicy.isPassword(type),
            hasSelection = selectionStart >= 0 && selectionStart != selectionEnd,
            comma = if (ascii) "," else "，",
            period = if (ascii) "." else "。",
            inputHint = if (EditorPolicy.isPassword(type)) "密码" else when (kind.name) {
                "Email" -> "邮箱"; "Url" -> "网址"; "Phone" -> "电话"
                "Integer", "SignedInteger" -> "数字"; "Decimal", "SignedDecimal" -> "小数"
                "DateTime" -> "日期时间"; else -> "English"
            },
            numberKind = when (kind.name) {
                "Phone" -> "phone"; "SignedInteger" -> "signed"
                "Decimal", "SignedDecimal" -> "decimal"; "DateTime" -> "datetime"; else -> "number"
            },
        )
        configureChinese(attribute)
        updateAutoCaps()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Some editors reuse a connection without a fresh onStartInput callback. Reset
        // view-only controls here so a previous editing panel never leaks into a new field.
        inputSession++
        resetChinese()
        caseState.reset()
        keyboardState = keyboardState.copy(
            page = if (EditorPolicy.prefersNumbers(info?.inputType ?: currentInputEditorInfo?.inputType ?: 0)) KeyboardPage.Numbers else KeyboardPage.Letters,
            editing = false,
            selecting = false,
        )
        configureChinese(info ?: currentInputEditorInfo)
        inputViewActive = true
        selectionAnchor = null
        requestedSelection = null
        updateAutoCaps()
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
        currentInputConnection?.finishComposingText()
        resetChinese()
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onFinishInput() {
        inputViewActive = false
        inputSession++
        resetChinese()
        caseState.reset()
        selectionAnchor = null
        requestedSelection = null
        selectionStart = -1
        selectionEnd = -1
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
        handleAction(KeyboardAction.SetChinese(newSubtype?.languageTag?.startsWith("zh") == true ||
            newSubtype?.locale?.startsWith("zh") == true))
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val expected = expectedEditorSelections.remove(newSelStart to newSelEnd)
        if ((composing || engineBusy) && !applyingComposition && !expected &&
            (newSelStart != oldSelStart || newSelEnd != oldSelEnd)) {
            // Invalidate even before the first preedit arrives; it must not replace a
            // selection the user made while a native request was still pending.
            currentInputConnection?.finishComposingText()
            resetChinese()
        }
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        if (requestedSelection == (newSelStart to newSelEnd)) requestedSelection = null
        keyboardState = keyboardState.copy(hasSelection = newSelStart >= 0 && newSelStart != newSelEnd)
        updateAutoCaps()
    }

    private fun updateAutoCaps() {
        val info = currentInputEditorInfo ?: return
        val flags = EditorPolicy.autoCapsFlags(info.inputType)
        val connection = currentInputConnection
        val editorCaps = flags != 0 && (connection?.getCursorCapsMode(flags) ?: 0) != 0
        val sentenceCaps = flags != 0 &&
            (info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0 &&
            EditorPolicy.sentenceNeedsCaps(connection?.getTextBeforeCursor(256, 0))
        caseState.automaticUppercase = !chinese && (editorCaps || sentenceCaps)
        syncCaseState()
    }

    private fun syncCaseState() {
        keyboardState = keyboardState.copy(uppercase = caseState.uppercase, capsLock = caseState.locked)
    }

    private fun handleAction(action: KeyboardAction) {
        // Reject obsolete UI snapshots before enqueuing and again before dispatch.
        if (action is KeyboardAction.SelectCandidate && !chineseSession.matches(action.revision)) return
        if (action is KeyboardAction.LoadMoreCandidates &&
            (engineBusy || !chineseSession.matches(action.revision))) return
        actions.addLast(action)
        drainActions()
    }

    private fun dispatchAction(action: KeyboardAction) {
        when (action) {
            is KeyboardAction.SetChinese -> { switchLanguage(action.enabled); return }
            KeyboardAction.NextLanguage -> {
                if (org.thanatophilia.xyuime.BuildConfig.DEBUG) android.util.Log.d("XyuState", "language allowed=$chineseAllowed ready=$engineReady preferred=$chinesePreferred busy=$engineBusy")
                if (!chineseAllowed) { showNotice("此输入框使用安全英文直输"); return }
                if (!engineReady) { prepareChinese(); showNotice("正在准备词库，可继续英文输入"); return }
                switchLanguage(!chinesePreferred); return
            }
            is KeyboardAction.SelectCandidate -> {
                if (chinese && composing && chineseSession.matches(action.revision) &&
                    action.index in keyboardState.candidates.indices) requestRime(2, action.index)
                return
            }
            is KeyboardAction.LoadMoreCandidates -> {
                if (chinese && composing && chineseSession.matches(action.revision) && keyboardState.hasMoreCandidates) {
                    chineseSession.more()
                    requestRime(more = true)
                }
                return
            }
            KeyboardAction.ToggleCandidates -> {
                if (composing) keyboardState = keyboardState.copy(candidatesExpanded = !keyboardState.candidatesExpanded)
                return
            }
            else -> Unit
        }
        if (chinese) {
            when (action) {
                is KeyboardAction.Text -> {
                    val value = action.value
                    if (value.length == 1 && (value[0] in 'a'..'z' || value == "'")) {
                        requestRime(1, value[0].code, fallback = value); return
                    }
                    if (value == "," || value == "，" || value == "." || value == "。") {
                        val punctuation = if (value == "," || value == "，") "，" else "。"
                        if (composing) requestRime(4, after = { commitText(punctuation) }) else commitText(punctuation)
                        return
                    }
                    finishRaw { commitText(value) }; return
                }
                KeyboardAction.Space -> {
                    if (composing) requestRime(1, 0x20) else commitText(" ")
                    return
                }
                KeyboardAction.Backspace -> {
                    if (composing) requestRime(1, 0xff08) else backspace()
                    return
                }
                KeyboardAction.Enter -> {
                    if (composing) finishRaw {} else enter()
                    return
                }
                KeyboardAction.Shift, KeyboardAction.CapsLock,
                KeyboardAction.ToggleSymbols, KeyboardAction.ToggleNumbers -> Unit
                else -> if (composing) {
                    finishRaw { dispatchOrdinaryAction(action) }; return
                }
            }
        }
        dispatchOrdinaryAction(action)
    }

    private fun dispatchOrdinaryAction(action: KeyboardAction) {
        when (action) {
            is KeyboardAction.Text -> commitText(action.value)
            KeyboardAction.Space -> commitText(" ")
            KeyboardAction.Enter -> enter()
            KeyboardAction.Backspace -> backspace()
            KeyboardAction.Shift -> { caseState.tap(); syncCaseState() }
            KeyboardAction.CapsLock -> { caseState.lock(); syncCaseState() }
            KeyboardAction.ToggleNumbers -> keyboardState = keyboardState.copy(editing = false, page = if (keyboardState.page == KeyboardPage.Numbers) KeyboardPage.Letters else KeyboardPage.Numbers)
            KeyboardAction.ToggleSymbols -> keyboardState = keyboardState.copy(editing = false, page = if (keyboardState.page == KeyboardPage.Symbols) KeyboardPage.Letters else KeyboardPage.Symbols)
            KeyboardAction.ToggleEditing -> keyboardState = keyboardState.copy(editing = !keyboardState.editing)
            KeyboardAction.ToggleSelection -> toggleSelection()
            KeyboardAction.SelectAll -> selectAll()
            KeyboardAction.Copy -> copySelection(cut = false)
            KeyboardAction.Cut -> copySelection(cut = true)
            KeyboardAction.Paste -> paste()
            is KeyboardAction.MoveCursor -> moveCursor(action.direction)
            KeyboardAction.NextLanguage, KeyboardAction.ToggleCandidates,
            is KeyboardAction.SelectCandidate, is KeyboardAction.LoadMoreCandidates,
            is KeyboardAction.SetChinese -> Unit
            KeyboardAction.PickInputMethod -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    private fun commitText(text: String) {
        val connection = currentInputConnection ?: return
        val inputType = currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL
        val committed = EditorPolicy.normalizeText(text, inputType)
        clearSelectionMode()
        if (inputType == InputType.TYPE_NULL && committed.all { it.code < 128 }) {
            committed.forEach { sendKeyChar(it) }
        } else {
            val end = minOf(selectionStart, selectionEnd).coerceAtLeast(0) + committed.length
            if (expectedEditorSelections.size > 32) expectedEditorSelections.clear()
            expectedEditorSelections.add(end to end)
            connection.commitText(committed, 1)
        }
        caseState.committed(committed)
        updateAutoCaps()
    }

    private fun backspace() {
        val connection = currentInputConnection ?: return
        if (currentInputEditorInfo?.inputType == InputType.TYPE_NULL) {
            key(connection, KeyEvent.KEYCODE_DEL)
            return
        }
        clearSelectionMode()
        if (!EditorOperations.deleteGrapheme(connection)) key(connection, KeyEvent.KEYCODE_DEL)
        updateAutoCaps()
    }

    private fun clearSelectionMode() {
        selectionAnchor = null
        requestedSelection = null
        keyboardState = keyboardState.copy(selecting = false)
    }

    private fun toggleSelection() {
        if (keyboardState.selecting) {
            clearSelectionMode()
        } else {
            selectionAnchor = if (selectionStart >= 0) selectionStart else null
            keyboardState = keyboardState.copy(selecting = true)
        }
    }

    private fun selectAll() {
        val connection = currentInputConnection ?: return
        clearSelectionMode()
        if (!connection.performContextMenuAction(android.R.id.selectAll)) showNotice("此编辑框不支持全选")
    }

    private fun copySelection(cut: Boolean) {
        val connection = currentInputConnection ?: return
        if (EditorPolicy.isPassword(currentInputEditorInfo?.inputType ?: 0)) return
        val text = connection.getSelectedText(0)
        if (text.isNullOrEmpty()) { showNotice("请先选择文字"); return }
        try {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("絮雨选中文字", text))
            if (cut) {
                clearSelectionMode()
                connection.commitText("", 1)
                updateAutoCaps()
            } else showNotice("已复制")
        } catch (_: SecurityException) { showNotice("系统不允许访问剪贴板") }
    }

    private fun paste() {
        val connection = currentInputConnection ?: return
        try {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
            if (text.isNullOrEmpty()) { showNotice("剪贴板中没有可粘贴的文字"); return }
            clearSelectionMode()
            // Clipboard text is committed verbatim; never normalize or save user's pasted text.
            connection.commitText(text, 1)
            caseState.committed(text.toString())
            updateAutoCaps()
        } catch (_: SecurityException) { showNotice("系统不允许访问剪贴板") }
    }

    private fun moveCursor(direction: CursorDirection) {
        val connection = currentInputConnection ?: return
        val selecting = keyboardState.selecting
        val sensitive = EditorPolicy.isPassword(currentInputEditorInfo?.inputType ?: 0)
        // Native up/down preserves the editor's visual wrapped lines. Left/right and line
        // edges use complete grapheme boundaries when the editor exposes a text snapshot.
        val snapshot = if (!sensitive && direction != CursorDirection.Up && direction != CursorDirection.Down)
            EditorOperations.snapshot(connection) else null
        if (snapshot != null) {
            val current = requestedSelection?.let { snapshot.copy(start = it.first, end = it.second) } ?: snapshot
            val destination = EditorOperations.destination(current, direction, selecting)
            val anchor = if (selecting) selectionAnchor ?: current.start else destination
            if (selecting) selectionAnchor = anchor
            if (connection.setSelection(anchor, destination)) {
                requestedSelection = anchor to destination
                selectionStart = anchor
                selectionEnd = destination
                keyboardState = keyboardState.copy(hasSelection = anchor != destination)
                updateAutoCaps()
                return
            }
        }
        val code = when (direction) {
            CursorDirection.Left -> KeyEvent.KEYCODE_DPAD_LEFT
            CursorDirection.Right -> KeyEvent.KEYCODE_DPAD_RIGHT
            CursorDirection.Up -> KeyEvent.KEYCODE_DPAD_UP
            CursorDirection.Down -> KeyEvent.KEYCODE_DPAD_DOWN
            CursorDirection.Home -> KeyEvent.KEYCODE_MOVE_HOME
            CursorDirection.End -> KeyEvent.KEYCODE_MOVE_END
        }
        requestedSelection = null
        key(connection, code, if (selecting) KeyEvent.META_SHIFT_ON else 0)
    }

    private fun showNotice(message: String) {
        toast?.cancel()
        toast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun enter() {
        val connection = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        clearSelectionMode()
        val action = EditorPolicy.enterAction(info.imeOptions, info.actionId, info.actionLabel?.toString())
        if (action.id != null && connection.performEditorAction(action.id)) return
        val multiline = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 && !EditorPolicy.isPassword(info.inputType)
        if (multiline) {
            connection.commitText("\n", 1)
            updateAutoCaps()
        } else key(connection, KeyEvent.KEYCODE_ENTER)
    }

    private fun key(connection: InputConnection, code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, -1, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta, -1, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
    }

    override fun onDestroy() {
        toast?.cancel()
        pauseViewLifecycle()
        rime.close()
        composeView?.disposeComposition()
        composeView = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }
}
