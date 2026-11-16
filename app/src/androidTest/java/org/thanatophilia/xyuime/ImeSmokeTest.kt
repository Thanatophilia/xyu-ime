package org.thanatophilia.xyuime

import android.content.Intent
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real IME window across the app/service boundary, not a UI mock. */
@RunWith(AndroidJUnit4::class)
class ImeSmokeTest {
    @Test fun keyboardAppearsAndCommitsEnglishInRealEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val packageName = context.packageName
        val ime = "$packageName/org.thanatophilia.xyuime.ime.XyuImeService"
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val shots = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "diagnostics")
        shots.mkdirs()
        fun shot(name: String) {
            device.takeScreenshot(File(shots, "$name.png"))
            File(shots, "$name-window.txt").writeText(device.executeShellCommand("dumpsys window"))
            File(shots, "$name-ime.txt").writeText(device.executeShellCommand("dumpsys input_method"))
        }
        try {
            val enableOutput = device.executeShellCommand("ime enable $ime").trim()
            val setOutput = device.executeShellCommand("ime set $ime").trim()
            Thread.sleep(1500)
            File(shots, "ime-command.txt").writeText("enable: $enableOutput\nset: $setOutput\n")
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertNotNull(device.wait(Until.findObject(By.pkg(packageName).depth(0)), 20_000))
            val editor = device.wait(Until.findObject(By.focused(true).pkg(packageName)), 10_000)
                ?: device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(packageName)), 10_000)
            if (editor == null) {
                shot("editor-missing")
                File(shots, "page.xml").writeText(device.executeShellCommand("uiautomator dump /sdcard/window.xml >/dev/null; cat /sdcard/window.xml"))
            }
            assertNotNull("The trial editor must be available", editor)
            requireNotNull(editor).click()
            val q = device.wait(Until.findObject(By.text("q").pkg(packageName)), 15_000)
            if (q == null) shot("keyboard-missing")
            assertNotNull("Compose IME window did not appear", q)
            shot("keyboard-visible")
            requireNotNull(q).click()
            assertTrue(
                "IME key must commit q through InputConnection",
                device.wait(Until.hasObject(By.pkg(packageName).textContains("q")), 5_000),
            )
            device.pressBack()
            requireNotNull(editor).click()
            assertNotNull("Compose keyboard must return after hiding", device.wait(Until.findObject(By.text("q").pkg(packageName)), 5_000))
        } finally {
            if (previous.isNotBlank() && previous != "null") device.executeShellCommand("ime set $previous")
            device.executeShellCommand("ime disable $ime")
        }
    }
}
