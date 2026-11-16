package org.thanatophilia.xyuime

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/** Compose IME semantics can change without invalidating Android 36's cached nodes. */
internal fun UiDevice.freshImeTree() {
    waitForIdle()
    if (Build.VERSION.SDK_INT >= 33) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
    }
}

internal fun UiDevice.waitForFreshObject(selector: BySelector, timeout: Long): UiObject2? {
    val deadline = SystemClock.uptimeMillis() + timeout
    do {
        freshImeTree()
        findObject(selector)?.let { return it }
        SystemClock.sleep(100)
    } while (SystemClock.uptimeMillis() < deadline)
    return null
}

internal fun UiDevice.waitForFreshGone(selector: BySelector, timeout: Long): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeout
    do {
        freshImeTree()
        if (!hasObject(selector)) return true
        SystemClock.sleep(100)
    } while (SystemClock.uptimeMillis() < deadline)
    return false
}
