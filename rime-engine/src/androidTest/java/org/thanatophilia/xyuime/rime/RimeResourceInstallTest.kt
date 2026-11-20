package org.thanatophilia.xyuime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated cache directories only; no active engine DB and no personal input are accessed. */
@RunWith(AndroidJUnit4::class)
class RimeResourceInstallTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (File) -> Unit) {
        val root = File(context.cacheDir, "d6-resource-${System.nanoTime()}")
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun sentinel(root: File): File = File(root, "user/sentinel").apply {
        parentFile!!.mkdirs(); writeText("synthetic-preserved-data")
    }
    private fun asset(name: String) = context.assets.open("rime/$name")
    private fun sharedDigests(dir: File): Map<String, String> = dir.listFiles()!!.associate { file ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val read = input.read(bytes)
                if (read < 0) break
                digest.update(bytes, 0, read)
            }
        }
        file.name to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun fails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IOException) { failed = true }
        catch (_: IllegalStateException) { failed = true }
        assertTrue("Injected resource failure must be observable", failed)
    }

    @Test fun coldInstallAndSameVersionReuseDoNotTouchUserFiles() = fixture { root ->
        val user = sentinel(root)
        val shared = BundledRimeData.install(context.assets, root, "d6-first")
        val files = sharedDigests(shared)
        assertEquals(3, files.size)
        // A correct same-version shared directory must not reopen or recopy bundled assets.
        val again = BundledRimeData.install(root, "d6-first") { error("Unexpected asset recopy") }
        assertEquals(shared.canonicalPath, again.canonicalPath)
        assertEquals(files, sharedDigests(again))
        assertEquals("synthetic-preserved-data", user.readText())
        assertFalse(File(root, "shared-d6-first.tmp").exists())
    }

    @Test fun checksumFailureAndRetryKeepOldResourcesAndUserFiles() = fixture { root ->
        val user = sentinel(root)
        val old = BundledRimeData.install(context.assets, root, "d6-old")
        val original = File(old, "default.yaml").readBytes()
        fails {
            BundledRimeData.install(root, "d6-new") { name ->
                if (name == "default.yaml") ByteArrayInputStream("bad bytes".toByteArray()) else asset(name)
            }
        }
        assertFalse(File(root, "shared-d6-new").exists())
        assertFalse(File(root, "shared-d6-new.tmp").exists())
        assertArrayEquals(original, File(old, "default.yaml").readBytes())
        assertEquals("synthetic-preserved-data", user.readText())
        val repaired = BundledRimeData.install(context.assets, root, "d6-new")
        assertArrayEquals(original, File(repaired, "default.yaml").readBytes())
        assertEquals("synthetic-preserved-data", user.readText())
    }

    @Test fun interruptedAssetReadCleansStagingAndAllowsCleanRetry() = fixture { root ->
        val user = sentinel(root)
        fails {
            BundledRimeData.install(root, "d6-interrupted") { name ->
                object : FilterInputStream(asset(name)) {
                    override fun read(): Int = throw IOException("Synthetic interrupted copy")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        throw IOException("Synthetic interrupted copy")
                }
            }
        }
        assertFalse(File(root, "shared-d6-interrupted.tmp").exists())
        assertFalse(File(root, "shared-d6-interrupted").exists())
        assertEquals("synthetic-preserved-data", user.readText())
        val retried = BundledRimeData.install(context.assets, root, "d6-interrupted")
        assertTrue(File(retried, "xyu_pinyin.schema.yaml").isFile)
    }

    @Test fun newResourceVersionNeverModifiesTheOldVersion() = fixture { root ->
        val user = sentinel(root)
        val old = BundledRimeData.install(context.assets, root, "d6-v1")
        File(old, "default.yaml").writeText("synthetic-old-version-fixture")
        val newer = BundledRimeData.install(context.assets, root, "d6-v2")
        assertNotEquals(old.canonicalPath, newer.canonicalPath)
        assertEquals("synthetic-old-version-fixture", File(old, "default.yaml").readText())
        assertNotEquals("synthetic-old-version-fixture", File(newer, "default.yaml").readText())
        assertEquals("synthetic-preserved-data", user.readText())
    }

    @Test fun invalidResourceVersionIsRejectedBeforeAnyFilesystemOrAssetAccess() = fixture { root ->
        assertFalse(root.exists())
        for (version in listOf("", "../outside", "a/b", "a\\b", "a\n", "版本")) {
            fails { BundledRimeData.install(root, version) { error("Must reject before asset access") } }
            assertFalse(root.exists())
        }
    }
}
