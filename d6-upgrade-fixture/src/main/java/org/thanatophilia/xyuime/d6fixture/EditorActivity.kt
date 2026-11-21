package org.thanatophilia.xyuime.d6fixture

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal external editor. It contains no expected answer and no IME-specific code. */
class EditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(32, 48, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "D6 release upgrade editor fixture"
            contentDescription = "D6 fixture title"
        })
        root.addView(EditText(this).apply {
            id = R.id.editor
            hint = "External editor"
            contentDescription = "D6 external editor"
            inputType = InputType.TYPE_CLASS_TEXT
            minLines = 1
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setOnEditorActionListener { _, _, _ -> true }
        }, LinearLayout.LayoutParams(-1, -2))
        root.isFocusableInTouchMode = true
        root.requestFocus()
        setContentView(root)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }
}
