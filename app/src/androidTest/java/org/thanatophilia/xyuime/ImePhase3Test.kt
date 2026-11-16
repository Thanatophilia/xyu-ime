package org.thanatophilia.xyuime

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImePhase3Test {
    @Test fun rawReturnAndFullCandidateDrawer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val pkg = context.packageName
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        fun control(tag: String) = requireNotNull(device.waitForFreshObject(By.res(tag).pkg(pkg), 15000)) { tag }
        fun tap(tag: String) { control(tag).click(); device.waitForIdle() }
        fun has(tag: String): Boolean {
            device.freshImeTree()
            return device.hasObject(By.res(tag).pkg(pkg))
        }
        fun typePinyin() { for (letter in "nihao") tap("key:$letter") }
        try {
            val id = "$pkg/org.thanatophilia.xyuime.ime.XyuImeService"
            device.executeShellCommand("ime enable $id")
            device.executeShellCommand("ime set $id")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(pkg)), 8000)).click()
            assertNotNull(device.waitForFreshObject(By.text("絮雨 · English").pkg(pkg), 120000))
            tap("key:切换语言，长按切换输入法")
            typePinyin()
            control("candidate:0")
            tap("candidate_toggle")
            control("candidate_drawer")
            assertFalse(has("key:q"))
            assertFalse(has("key:空格"))
            // Reverse the drawer repeatedly; no ordinary keys exist while expanded.
            tap("candidate_toggle")
            tap("candidate_toggle")
            assertFalse(has("key:q"))
            tap("candidate_toggle")
            control("key:q")
            requireNotNull(device.wait(Until.findObject(By.descStartsWith("回车：").pkg(pkg)), 8000)).click()
            device.waitForIdle()
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText").pkg(pkg).text("nihao")), 5000))
            assertFalse(device.hasObject(By.res("candidate_toggle").pkg(pkg)))
            typePinyin()
            tap("candidate:0")
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText").pkg(pkg).text("nihao你好")), 5000))
        } finally {
            if (previous.contains('/')) device.executeShellCommand("ime set $previous")
        }
    }
}
