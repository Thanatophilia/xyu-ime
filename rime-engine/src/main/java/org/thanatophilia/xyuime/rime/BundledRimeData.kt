package org.thanatophilia.xyuime.rime

import android.content.res.AssetManager
import java.io.File
import java.security.MessageDigest

/** Versioned immutable shared files; user dictionaries are never copied or removed. */
internal object BundledRimeData {
    private val checksums = mapOf(
        "default.yaml" to "aabd181c47275af3114aca55e4fcd94fe60ef4cb3554eee1e160a3ecc13f6166",
        "pinyin_simp.dict.yaml" to "e341598343a0f0f2035bb1aafc34a7f3bb7887deeecb3f60796262aaa2983e6b",
        "xyu_pinyin.schema.yaml" to "6c53486d52c2870291ca90bbd9629218d26be7c08a88d7ba44f52031f178b9f3",
    )

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun valid(directory: File) = checksums.all { (name, hash) ->
        val file = File(directory, name)
        file.isFile && digest(file) == hash
    }

    fun install(assets: AssetManager, root: File, version: String): File {
        check(root.isDirectory || root.mkdirs())
        val destination = File(root, "shared-$version")
        if (valid(destination)) return destination
        val staging = File(root, "shared-$version.tmp")
        check(!staging.exists() || staging.deleteRecursively())
        check(staging.mkdirs())
        try {
            checksums.keys.forEach { name ->
                assets.open("rime/$name").use { input ->
                    File(staging, name).outputStream().use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            check(valid(staging)) { "Bundled dictionary checksum mismatch" }
            check(!destination.exists() || destination.deleteRecursively())
            check(staging.renameTo(destination))
            return destination
        } finally {
            staging.deleteRecursively()
        }
    }
}
