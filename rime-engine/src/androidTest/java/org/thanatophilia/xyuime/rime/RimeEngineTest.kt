package org.thanatophilia.xyuime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RimeEngineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun request(engine: RimeEngine, session: Int = 1, operation: Int = 0,
                        key: Int = 0, limit: Int = 64): RimeOutput {
        val latch = CountDownLatch(1)
        val result = AtomicReference<RimeOutput>()
        engine.request(session, operation, key, limit) {
            result.set(it)
            latch.countDown()
        }
        assertTrue("Engine request timed out", latch.await(120, TimeUnit.SECONDS))
        return result.get().also { assertNull(it.error) }
    }

    private fun type(engine: RimeEngine, text: String): RimeOutput {
        var output = request(engine, operation = RimeEngine.CLEAR)
        text.forEach { output = request(engine, operation = RimeEngine.PROCESS_KEY, key = it.code) }
        return output
    }

    @Test fun offlineDictionaryCommitDrainAndNewSession() {
        val engine = RimeEngine(context)
        try {
            val composing = type(engine, "nihao")
            assertTrue(composing.hasComposition)
            assertTrue(composing.cursor in 0..composing.preedit.length)
            val index = composing.candidates.indexOf("你好")
            assertTrue("Bundled pinyin must offer 你好", index >= 0)
            val selected = request(engine, operation = RimeEngine.SELECT_CANDIDATE, key = index)
            assertEquals("你好", selected.commitText)
            assertFalse(selected.hasComposition)
            assertEquals("", request(engine).commitText)
            type(engine, "ni")
            val newSession = request(engine, session = 2)
            assertFalse(newSession.hasComposition)
            assertTrue(newSession.candidates.isEmpty())
            assertEquals("", newSession.commitText)
        } finally { engine.close() }
    }

    @Test fun globalCandidatesBeyondFirstPageDoNotMoveSpaceTarget() {
        val engine = RimeEngine(context)
        try {
            type(engine, "shi")
            val short = request(engine, limit = 9)
            val expanded = request(engine, limit = 128)
            assertEquals(short.candidates, expanded.candidates.take(9))
            assertFalse(short.isLastPage)
            assertTrue(expanded.candidates.size > 9)
            assertEquals(short.highlightedIndex, expanded.highlightedIndex)
            assertEquals(short.page, expanded.page)
            val expected = expanded.candidates[expanded.highlightedIndex]
            assertEquals(expected, request(engine, operation = RimeEngine.PROCESS_KEY, key = 32).commitText)
            type(engine, "shi")
            val again = request(engine, limit = 128)
            assertEquals(again.candidates[10], request(engine,
                operation = RimeEngine.SELECT_CANDIDATE, key = 10).commitText)
            assertEquals("", request(engine).commitText)
        } finally { engine.close() }
    }

    @Test fun backspaceClearResetAndMultipleFrontendOwners() {
        val first = RimeEngine(context)
        val second = RimeEngine(context)
        try {
            type(first, "ni")
            assertFalse(request(second).hasComposition)
            val deleted = request(first, operation = RimeEngine.PROCESS_KEY, key = 0xff08)
            assertTrue(deleted.hasComposition)
            assertFalse(request(first, operation = RimeEngine.CLEAR).hasComposition)
            type(first, "hao")
            first.reset()
            assertFalse(request(first).hasComposition)
            first.close()
            assertTrue(type(second, "ni").candidates.isNotEmpty())
        } finally { first.close(); second.close() }
    }

    @Test fun stagingRepairsCorruptSharedFileWithoutTouchingUserData() {
        val root = File(context.cacheDir, "rime-deploy-test-${System.nanoTime()}")
        try {
            val sentinel = File(root, "user/learned.txt")
            sentinel.parentFile!!.mkdirs()
            sentinel.writeText("preserve")
            val shared = BundledRimeData.install(context.assets, root, "test")
            File(shared, "default.yaml").writeText("corrupted")
            val repaired = BundledRimeData.install(context.assets, root, "test")
            assertTrue(File(repaired, "default.yaml").readText().contains("xyu_pinyin"))
            assertEquals("preserve", sentinel.readText())
            assertFalse(File(root, "version").exists())
            assertFalse(File(root, "shared-test.tmp").exists())
        } finally { root.deleteRecursively() }
    }
}
