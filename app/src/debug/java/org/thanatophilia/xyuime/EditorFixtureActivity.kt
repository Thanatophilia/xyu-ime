package org.thanatophilia.xyuime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/**
 * Native and web editor fixtures for D1 input checks. Test-only: not exported and absent from the launcher.
 */
class EditorFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fixture = intent.getStringExtra(EXTRA_FIXTURE)
        setContentView(
            when (fixture) {
                FIXTURE_WEB, FIXTURE_WEB_EDITABLE -> webLayout(fixture == FIXTURE_WEB_EDITABLE)
                FIXTURE_SWITCH -> switchLayout()
                FIXTURE_PASSWORD, FIXTURE_NUMBER, FIXTURE_EMAIL -> specialLayout(fixture)
                else -> nativeLayout(if (fixture == FIXTURE_MULTILINE) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_CLASS_TEXT)
            }
        )
    }

    private fun specialLayout(fixture: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        val password = fixture == FIXTURE_PASSWORD
        val email = fixture == FIXTURE_EMAIL
        val label = TextView(this@EditorFixtureActivity).apply {
            id = R.id.fixture_action_label
            text = "action:none"
        }
        addView(TextView(this@EditorFixtureActivity).apply {
            text = when {
                password -> "Password field"
                email -> "Email field"
                else -> "Number field"
            }
        })
        addView(EditText(this@EditorFixtureActivity).apply {
            id = when {
                password -> R.id.fixture_password_editor
                email -> R.id.fixture_email_editor
                else -> R.id.fixture_number_editor
            }
            hint = when {
                password -> "Password"
                email -> "Email"
                else -> "Number"
            }
            contentDescription = hint
            inputType = when {
                password -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                email -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                else -> android.text.InputType.TYPE_CLASS_NUMBER
            }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            if (!password && !email) {
                setOnEditorActionListener { _, actionId, _ ->
                    label.text = "action:$actionId"
                    true
                }
            }
        })
        addView(label)
    }

    private fun switchLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        addView(TextView(this@EditorFixtureActivity).apply { text = "Two native editors" })
        addView(EditText(this@EditorFixtureActivity).apply {
            id = R.id.fixture_editor_a
            hint = "Field A"
            contentDescription = "Field A"
        })
        addView(EditText(this@EditorFixtureActivity).apply {
            id = R.id.fixture_editor_b
            hint = "Field B"
            contentDescription = "Field B"
        })
    }

    private fun nativeLayout(inputType: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        addView(TextView(this@EditorFixtureActivity).apply { text = "Native EditText fixture" })
        addView(EditText(this@EditorFixtureActivity).apply {
            id = R.id.fixture_native_editor
            hint = "Native editor"
            this.inputType = inputType
            minLines = if (inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) 4 else 1
            maxLines = if (inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) 8 else 1
        })
    }

    private fun webLayout(contentEditable: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(WebView(this@EditorFixtureActivity).apply {
            id = R.id.fixture_web_view
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
            val editors = if (contentEditable) {
                "<div id='editable' role='textbox' contenteditable='true' aria-label='Web editable' style='width:95%;border:1px solid #888;min-height:120px'></div>"
            } else {
                "<textarea id='area' rows='5' aria-label='Web textarea' style='width:95%'></textarea><input id='input' aria-label='Web input' style='width:95%'>"
            }
            loadDataWithBaseURL(null,
                "<!doctype html><meta name=viewport content='width=device-width'><title>Web fixture</title><body><h3>Web input fixture</h3>$editors</body>",
                "text/html", "UTF-8", null)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    companion object {
        const val EXTRA_FIXTURE = "fixture"
        const val FIXTURE_NATIVE = "native"
        const val FIXTURE_MULTILINE = "multiline"
        const val FIXTURE_WEB = "web"
        const val FIXTURE_WEB_EDITABLE = "web-editable"
        const val FIXTURE_SWITCH = "switch"
        const val FIXTURE_PASSWORD = "password"
        const val FIXTURE_NUMBER = "number"
        const val FIXTURE_EMAIL = "email"
        const val ACTION_FIXTURE = "org.thanatophilia.xyuime.DEBUG_EDITOR_FIXTURE"
        fun intent(context: Context, fixture: String) = Intent(context, EditorFixtureActivity::class.java).putExtra(EXTRA_FIXTURE, fixture)
    }
}
