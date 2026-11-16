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
    private fun freshTree() {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        device.waitForIdle()
    }
    private fun node(selector: BySelector): UiObject2 {
        freshTree()
        return requireNotNull(device.wait(Until.findObject(selector), 8_000)) { "Missing UI control: $selector" }
    }
    private fun key(text: String) {
        node(By.res("key:$text").pkg(pkg)).click()
        device.waitForIdle()
    }
    private fun description(text: String) {
        node(By.res(if (text == "编辑工具") "edit_toggle" else "key:$text").pkg(pkg)).click()
        device.waitForIdle()
    }
    private fun editing() {
        freshTree()
        if (device.hasObject(By.res("key:全选").pkg(pkg))) return
        description("编辑工具")
        node(By.res("key:全选").pkg(pkg))
    }
    private fun letters() {
        freshTree()
        if (device.hasObject(By.res("key:q").pkg(pkg))) return
        description("编辑工具")
        node(By.res("key:q").pkg(pkg))
    }
    private fun editor() = node(By.clazz("android.widget.EditText").pkg(pkg))
    private fun openEditor() {
        var field = editor()
        repeat(3) {
            field.click()
            device.waitForIdle()
            val keyboard = device.wait(Until.findObject(By.res("key:q").pkg(pkg)), 5_000)
            if (keyboard != null) return
            field = editor()
        }
        error("Keyboard did not appear after focusing trial editor")
    }
    private fun expectText(text: String) {
        assertTrue("Expected editor content: $text", device.wait(Until.hasObject(By.clazz("android.widget.EditText").pkg(pkg).text(text)), 5_000))
    }
    private fun screenshot(name: String) {
        val xml = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "$name.xml")
        freshTree()
        device.dumpWindowHierarchy(xml)
        device.executeShellCommand("mkdir -p /sdcard/Download/xyu-ime-review")
        device.executeShellCommand("cp ${xml.absolutePath} /sdcard/Download/xyu-ime-review/$name.xml")
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "$name.png")
        if (device.takeScreenshot(file)) {
            device.executeShellCommand("mkdir -p /sdcard/Download/xyu-ime-review")
            device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/xyu-ime-review/$name.png")
        }
    }
    private fun withIme(test: () -> Unit) {
        // Instrumentation runs inside this package; clearing it would terminate the test process.
        val id = "$pkg/org.thanatophilia.xyuime.ime.XyuImeService"
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        try {
            device.executeShellCommand("ime enable $id")
            device.executeShellCommand("ime set $id")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            openEditor()
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
        editing()
        screenshot("phase2-editing")
        description("光标左移")
        letters()
        key("x")
        expectText("abxc")
        editing()
        key("全选")
        assertTrue(device.wait(Until.hasObject(By.res("key:复制").enabled(true)), 3_000))
        key("复制")
        key("全选")
        key("剪切")
        expectText("")
        key("粘贴")
        expectText("abxc")
        description("光标到行尾")
        description("选择文字模式")
        description("光标左移")
        letters()
        key("z")
        expectText("abxz")
        screenshot("phase2-text")
    }

    @Test fun completeEmojiDeleteAndPasswordClipboardProtection() = withIme {
        // The explicit sample fixture contains no personal/user clipboard content.
        val sample = "A👨‍👩‍👧‍👦👍🏽🇨🇳1️⃣éZ"
        // UiAutomator's setText uses the editor accessibility API to seed the synthetic fixture;
        // actual deletions below all go through the visible keyboard and InputConnection.
        editor().text = sample
        openEditor()
        editing()
        description("光标到行尾")
        letters()
        expectText(sample)
        val expected = listOf("A👨‍👩‍👧‍👦👍🏽🇨🇳1️⃣é", "A👨‍👩‍👧‍👦👍🏽🇨🇳1️⃣", "A👨‍👩‍👧‍👦👍🏽🇨🇳", "A👨‍👩‍👧‍👦👍🏽", "A👨‍👩‍👧‍👦", "A", "")
        expected.forEach { remaining -> description("删除，长按连续删除"); expectText(remaining) }
        device.pressBack()
        val mode = device.wait(Until.findObject(By.text("密码").pkg(pkg)), 5_000)
        requireNotNull(mode).click()
        openEditor()
        key("a")
        editing()
        key("全选")
        assertFalse(node(By.res("key:复制").pkg(pkg)).isEnabled)
        assertFalse(node(By.res("key:剪切").pkg(pkg)).isEnabled)
        screenshot("phase2-password")
    }
}
