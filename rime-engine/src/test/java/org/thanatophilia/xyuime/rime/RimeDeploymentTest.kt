package org.thanatophilia.xyuime.rime

import java.io.File
import java.nio.file.Files

class RimeDeploymentJUnitTest {
    @org.junit.Test fun deploymentFaultInjectionScenarios() {
        RimeDeploymentTest.main(emptyArray())
    }
}

/** Standalone JVM fault-injection suite, also invoked by the JUnit wrapper above. */
object RimeDeploymentTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("rime-transaction-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun put(root: File, path: String, value: String) {
        File(root, path).apply { parentFile!!.mkdirs(); writeText(value) }
    }
    private fun seed(root: File) {
        put(root, "version", "old")
        put(root, "shared-old/schema.yaml", "old schema")
        put(root, "user/dict/000001.ldb", "learned\u0000bytes")
        put(root, "user/custom.yaml", "user settings")
    }
    private fun old(root: File) {
        check(File(root, "version").readText() == "old")
        check(File(root, "shared-old/schema.yaml").readText() == "old schema")
        check(File(root, "user/dict/000001.ldb").readText() == "learned\u0000bytes")
        check(File(root, "user/custom.yaml").readText() == "user settings")
        check(!File(root, "user/partial").exists())
    }
    private fun fails(block: () -> Unit) {
        var thrown = false
        try { block() } catch (_: IllegalStateException) { thrown = true }
        check(thrown) { "Expected injected failure" }
    }

    @JvmStatic fun main(args: Array<String>) {
        fixture { root ->
            seed(root)
            var open = false
            var calls = 0
            RimeDeployment(root).initialize("new", { File(root, "shared-new").apply { mkdirs() } },
                { shared, _ ->
                    check(!open)
                    if (++calls == 1) {
                        open = true
                        put(root, "user/dict/000001.ldb", "broken")
                        put(root, "user/partial", "partial")
                        put(root, "shared-old/schema.yaml", "broken")
                        error("deploy failure")
                    }
                    old(root)
                    check(shared.name == "shared-old")
                    open = true
                }, { check(open); open = false })
            check(calls == 2)
            old(root)
            check(File(root, "deployment.pending/user/dict/000001.ldb").exists())
        }
        fixture { root ->
            seed(root)
            RimeDeployment(root).begin()
            put(root, "user/dict/000001.ldb", "interrupted deployment")
            put(root, "user/partial", "partial")
            put(root, "version", "new") // crash between version publication and commit
            RimeDeployment(root).recover()
            old(root)
            RimeDeployment(root).recover()
            old(root)
        }
        fixture { root ->
            seed(root)
            RimeDeployment(root).begin()
            put(root, "user/partial", "partial")
            // Interrupt restore after deleting the working user tree and copying part of it.
            var fail = true
            fails {
                RimeDeployment(root) { dir ->
                    if (fail && dir.path.endsWith("user/dict")) {
                        fail = false
                        error("interrupted restore")
                    }
                }.recover()
            }
            check(File(root, "deployment.pending/user/dict/000001.ldb").exists())
            RimeDeployment(root).recover()
            old(root)
        }
        fixture { root ->
            val deployment = RimeDeployment(root)
            fails {
                deployment.initialize("new", { File(root, "shared-new").apply { mkdirs() } },
                    { _, _ -> put(root, "user/partial", "partial"); error("first deploy") }, {})
            }
            check(!File(root, "version").exists())
            check(!File(root, "user").exists())
            deployment.initialize("new", { File(root, "shared-new") },
                { _, _ -> put(root, "user/learned", "new learned") }, {})
            check(File(root, "version").readText() == "new")
            // Successful transaction is collected without rolling back learned data.
            deployment.recover()
            check(File(root, "user/learned").readText() == "new learned")
            check(!File(root, "deployment.pending").exists())
        }
        fixture { root ->
            seed(root)
            fails {
                RimeDeployment(root).initialize("new", { File(root, "shared-new") },
                    { _, _ -> put(root, "user/partial", "open DB"); error("deploy") },
                    { error("shutdown failed") })
            }
            check(File(root, "user/partial").readText() == "open DB")
            check(File(root, "deployment.pending/user/dict/000001.ldb").exists())
            // Simulate a fresh process: OS has now closed database handles.
            RimeDeployment(root).recover()
            old(root)
        }
        fixture { root ->
            seed(root)
            var calls = 0
            fails {
                RimeDeployment(root).initialize("new", { File(root, "shared-new") },
                    { _, _ -> calls++; put(root, "user/partial", "partial"); error("both fail") }, {})
            }
            check(calls == 2)
            old(root)
        }
        fixture { root ->
            seed(root)
            // A crash while preparing a snapshot must not overwrite the still-valid user.
            put(root, "deployment.preparing/user/dict/000001.ldb", "incomplete snapshot")
            RimeDeployment(root).recover()
            old(root)
        }
        fixture { root ->
            seed(root)
            var calls = 0
            val deployment = RimeDeployment(root) { dir ->
                if (dir.name == "deployment.pending" &&
                    File(dir, "state").readText() == "committed") error("commit fsync failed")
            }
            fails {
                deployment.initialize("new", { File(root, "shared-new").apply { mkdirs() } },
                    { _, _ -> calls++; put(root, "user/new", "committed data") }, {})
            }
            check(calls == 1) // no fallback against the newly committed user data
            RimeDeployment(root).recover()
            check(File(root, "version").readText() == "new")
            check(File(root, "user/new").readText() == "committed data")
        }
        fixture { root ->
            seed(root)
            var calls = 0
            fails {
                RimeDeployment(root).initialize("old", { File(root, "shared-old") },
                    { _, _ -> calls++; put(root, "user/partial", "partial"); error("same version") }, {})
            }
            check(calls == 1)
            old(root)
        }
        println("RimeDeploymentTest: 9 fault-injection scenarios passed")
    }
}
