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
        return result.get().also {
            assertNull(it.error)
            assertEquals("Every global candidate needs a comment slot, even when empty",
                it.candidates.size, it.candidateComments.size)
        }
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
            assertEquals("ni hao", composing.candidateComments[index])
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
            assertEquals(short.candidateComments, expanded.candidateComments.take(9))
            assertTrue(expanded.candidateComments.all { it.substringBefore(' ') == "shi" })
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

    @Test fun dictionaryReadingsSurviveAbbreviationsPolyphonesAndExpansion() {
        val engine = RimeEngine(context)
        // This oracle is the bundled dictionary, not a pinyin conversion of commit text.
        // Keep every reading of a polyphone instead of overwriting by candidate text.
        val readings = mutableMapOf<String, MutableSet<String>>()
        context.assets.open("rime/pinyin_simp.dict.yaml").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val fields = line.split('\t')
                if (fields.size >= 2) readings.getOrPut(fields[0]) { mutableSetOf() }.add(fields[1])
            }
        }
        fun assertReading(output: RimeOutput, text: String, spelling: String) {
            val index = output.candidates.indexOf(text)
            assertTrue("$text must be offered for $spelling", index >= 0)
            assertEquals(spelling, output.candidateComments[index])
        }
        try {
            assertReading(type(engine, "ni"), "呢", "ni")
            assertReading(type(engine, "ne"), "呢", "ne")
            for (input in listOf("nihao", "ni", "nh", "n")) {
                type(engine, input)
                val short = request(engine, limit = 9)
                // A single initial may rank hundreds of words with one first syllable first.
                val limit = if (input == "n") 4096 else 512
                val expanded = request(engine, limit = limit)
                assertEquals(short.candidates, expanded.candidates.take(short.candidates.size))
                assertEquals(short.candidateComments,
                    expanded.candidateComments.take(short.candidateComments.size))
                assertEquals(short.highlightedIndex, expanded.highlightedIndex)
                assertEquals(short.page, expanded.page)
                val repeated = request(engine, limit = limit)
                assertEquals(expanded.candidates, repeated.candidates)
                assertEquals(expanded.candidateComments, repeated.candidateComments)
                assertTrue(expanded.candidateComments.all { it.isNotBlank() })
                expanded.candidates.zip(expanded.candidateComments).forEach { (text, comment) ->
                    // Learned/sentence candidates need not be entries of the bundled dictionary.
                    readings[text]?.let { valid ->
                        assertTrue("Wrong association: $text / $comment", comment in valid)
                    }
                }
                if (input == "nihao" || input == "nh") assertReading(expanded, "你好", "ni hao")
                if (input == "nh" || input == "n") {
                    val initials = expanded.candidateComments.map { it.substringBefore(' ') }.toSet()
                    assertTrue("$input must expose distinct real first syllables: $initials",
                        initials.size > 1)
                }
                // Choosing from a filtered view still uses the original, zero-based global index.
                if (input == "nh") {
                    val index = expanded.candidates.indexOf("你好")
                    assertEquals("你好", request(engine, operation = RimeEngine.SELECT_CANDIDATE,
                        key = index).commitText)
                    assertTrue(request(engine).candidateComments.isEmpty())
                }
            }
        } finally { engine.close() }
    }

    @Test fun generatedAndLearnedSentenceKeepDictionarySyllables() {
        val engine = RimeEngine(context)
        val input = "nihaonihao"
        val expectedText = "你好你好"
        val expectedReading = "ni hao ni hao"
        try {
            // No bundled four-syllable entry: first retrieval exercises sentence synthesis.
            // After selecting it, the same code can be served from the user dictionary.
            repeat(2) {
                type(engine, input)
                val output = request(engine, limit = 128)
                val index = output.candidates.indexOf(expectedText)
                assertTrue("Generated/learned sentence must be offered", index >= 0)
                assertEquals(expectedReading, output.candidateComments[index])
                assertEquals(expectedText, request(engine, operation = RimeEngine.SELECT_CANDIDATE,
                    key = index).commitText)
                assertTrue(request(engine).candidateComments.isEmpty())
                engine.reset()
            }
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
            val previous = BundledRimeData.install(context.assets, root, "previous")
            File(previous, "xyu_pinyin.schema.yaml").writeText("previous schema")
            val shared = BundledRimeData.install(context.assets, root, "test")
            val schema = File(shared, "xyu_pinyin.schema.yaml").readText()
            assertTrue(schema.contains("spelling_hints: 256"))
            assertTrue(schema.contains("always_show_comments: true"))
            assertEquals("previous schema", File(previous, "xyu_pinyin.schema.yaml").readText())
            File(shared, "default.yaml").writeText("corrupted")
            val repaired = BundledRimeData.install(context.assets, root, "test")
            assertTrue(File(repaired, "default.yaml").readText().contains("xyu_pinyin"))
            assertEquals("preserve", sentinel.readText())
            assertFalse(File(root, "version").exists())
            assertFalse(File(root, "shared-test.tmp").exists())
        } finally { root.deleteRecursively() }
    }
}
