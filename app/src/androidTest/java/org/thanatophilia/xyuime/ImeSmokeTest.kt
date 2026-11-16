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
        try {
            device.executeShellCommand("ime enable $ime")
            device.executeShellCommand("ime set $ime")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertNotNull(device.wait(Until.findObject(By.pkg(packageName).depth(0)), 15_000))
            val editor = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(packageName)), 10_000)
            assertNotNull("The trial editor must be available", editor)
            requireNotNull(editor).click()
            val q = device.wait(Until.findObject(By.text("q").pkg(packageName)), 10_000)
            assertNotNull("Compose IME window did not appear", q)
            val screenshot = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "keyboard-phase1.png")
            device.takeScreenshot(screenshot)
            requireNotNull(q).click()
            assertTrue("IME key must commit q through InputConnection", device.wait(Until.hasObject(By.clazz("android.widget.EditText").textContains("q")), 5_000))
            device.pressBack()
            requireNotNull(editor).click()
            assertNotNull("Compose keyboard must return after hiding", device.wait(Until.findObject(By.text("q").pkg(packageName)), 5_000))
        } finally {
            if (previous.isNotBlank() && previous != "null") device.executeShellCommand("ime set $previous")
            device.executeShellCommand("ime disable $ime")
        }
    }
}
