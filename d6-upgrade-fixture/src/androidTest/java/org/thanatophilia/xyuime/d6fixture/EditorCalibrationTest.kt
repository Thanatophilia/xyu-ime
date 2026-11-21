package org.thanatophilia.xyuime.d6fixture

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** G1 mechanism calibration only. Synthetic direct InputConnection calls are NOT release input. */
@RunWith(AndroidJUnit4::class)
class EditorCalibrationTest {
    @Test fun atomicSnapshotDistinguishesCompositionCommitAndInvalidSpans() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("d6Phase") == "calibrate") { "D6_CALIBRATION_EXPLICIT_PHASE_REQUIRED" }
        val run = requireNotNull(args.getString("d6Run"))
        check(run.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        check(Build.VERSION.SDK_INT == 36 && Build.SUPPORTED_ABIS.first() == "x86_64")
        check(UiDevice.getInstance(ins).executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        val context = ins.targetContext
        val output = File(context.filesDir, "d6-fixture-evidence/$run-calibrate.json")
        check(!output.exists()) { "D6_STALE_CALIBRATION_REPORT" }
        val host = ins.startActivitySync(Intent(context, EditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as EditorActivity
        try {
            ins.runOnMainSync {
                host.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                val editor = host.findViewById<EditText>(R.id.editor)
                editor.requestFocus()
                fun snap() = EditorSnapshot(editor.text.toString(),
                    BaseInputConnection.getComposingSpanStart(editor.text),
                    BaseInputConnection.getComposingSpanEnd(editor.text),
                    editor.selectionStart, editor.selectionEnd)
                assertTrue("D6_EMPTY_COMMITTED_CHECK", snap().isCommitted(""))
                val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
                check(connection.setComposingText("calibration", 1))
                assertTrue("D6_COMPOSING_RECOGNIZED", snap().isComposing("", "calibration"))
                assertFalse("D6_COMPOSING_NOT_COMMITTED", snap().isCommitted("calibration"))
                check(connection.commitText("mechanism", 1))
                assertTrue("D6_COMMIT_RECOGNIZED", snap().isCommitted("mechanism"))
                assertFalse("D6_WRONG_TEXT_REJECTED", snap().isCommitted("mechanismmechanism"))
                assertFalse("D6_HALF_SPAN_REJECTED", snap().copy(composingStart = 0).isCommitted("mechanism"))
                assertFalse("D6_INVALID_SPAN_REJECTED", snap().copy(composingStart = 9,
                    composingEnd = 2).isComposing("", "mechanism"))
                connection.closeConnection()
            }
            output.parentFile!!.mkdirs()
            output.writeText(JSONObject().put("result", "PASS").put("run", run)
                .put("phase", "calibrate").put("mechanism_assertions", 7)
                .put("scope", "synthetic editor mechanism only; not release IME input")
                .put("process", "${android.os.Process.myPid()}:${SystemClock.elapsedRealtime()}").toString(2))
        } finally { ins.runOnMainSync { host.finish() } }
    }
}
