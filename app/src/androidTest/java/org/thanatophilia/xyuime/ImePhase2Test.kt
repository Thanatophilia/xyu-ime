package org.thanatophilia.xyuime

import android.content.Intent
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImePhase2Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = context.packageName
    private fun node(selector: BySelector): UiObject2 = requireNotNull(device.wait(Until.findObject(selector), 8_000)) { "Missing UI control: $selector" }
    private fun key(text: String) { node(By.text(text).pkg(pkg)).click(); device.waitForIdle() }
    private fun description(text: String) { node(By.desc(text).pkg(pkg)).click(); device.waitForIdle() }
    private fun editor() = node(By.clazz("android.widget.EditText").pkg(pkg))
    private fun expectText(text: String) {
        assertTrue("Expected editor content: $text", device.wait(Until.hasObject(By.clazz("android.widget.EditText").pkg(pkg).text(text)), 5_000))
    }
    private fun screenshot(name: String) {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "$name.png")
        if (device.takeScreenshot(file)) {
            device.executeShellCommand("mkdir -p /sdcard/Download/xyu-ime-review")
            device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/xyu-ime-review/$name.png")
        }
    }
    private fun withIme(test: () -> Unit) {
        val id = "$pkg/org.thanatophilia.xyuime.ime.XyuImeService"
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        try {
            device.executeShellCommand("ime enable $id")
            device.executeShellCommand("ime set $id")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            editor().click()
            node(By.text("q").pkg(pkg))
            test()
        } catch (error: Throwable) {
            screenshot("phase2-failure")
            throw error
        } finally {
            if (previous.isNotEmpty() && previous != "null") device.executeShellCommand("ime set $previous")
            device.executeShellCommand("ime disable $id")
        }
    }

    @Test fun editingPanelMovesCursorSelectsCutsAndPastes() = withIme {
        listOf("a", "b", "c").forEach(::key)
        expectText("abc")
        description("编辑工具")
        screenshot("phase2-editing")
        description("光标左移")
        description("编辑工具")
        key("x")
        expectText("abxc")
        description("编辑工具")
        key("全选")
        assertTrue(device.wait(Until.hasObject(By.text("复制").enabled(true)), 3_000))
        key("复制")
        key("剪切")
        expectText("")
        key("粘贴")
        expectText("abxc")
        key("行尾")
        key("选择")
        description("光标左移")
        description("编辑工具")
        key("z")
        expectText("abxz")
        screenshot("phase2-text")
    }

    @Test fun completeEmojiDeleteAndPasswordClipboardProtection() = withIme {
        // The explicit sample fixture contains no personal/user clipboard content.
        device.pressBack()
        key("填入 emoji 样例")
        editor().click()
        node(By.text("q").pkg(pkg))
        val expected = listOf("A👨‍👩‍👧‍👦👍🏽🇨🇳1️⃣é", "A👨‍👩‍👧‍👦👍🏽🇨🇳1️⃣", "A👨‍👩‍👧‍👦👍🏽🇨🇳", "A👨‍👩‍👧‍👦👍🏽", "A👨‍👩‍👧‍👦", "A", "")
        expected.forEach { remaining -> description("删除，长按连续删除"); expectText(remaining) }
        device.pressBack()
        key("密码")
        editor().click()
        node(By.text("q").pkg(pkg))
        key("a")
        description("编辑工具")
        key("全选")
        assertFalse(node(By.text("复制").pkg(pkg)).isEnabled)
        assertFalse(node(By.text("剪切").pkg(pkg)).isEnabled)
        screenshot("phase2-password")
    }
}
