package app.onestream

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalTest {
    @Test
    fun approvalIsPinnedToTheExactFile() {
        val dir = Files.createTempDirectory("appr").toFile()
        val store = Store(java.io.File(dir, "data.json"))
        val jar = java.io.File(dir, "X.jar").apply { writeText("v1") }
        assertFalse(store.isApproved(jar), "never approved")
        store.approvePlugin(jar.name, app.onestream.update.Updater.sha256(jar))
        assertTrue(store.isApproved(jar))
        jar.writeText("v2-tampered")
        assertFalse(store.isApproved(jar), "a changed file is no longer approved")
        assertTrue(Store(java.io.File(dir, "data.json")).data.value.approvedPlugins.containsKey("X.jar"), "approval persists")
    }
}