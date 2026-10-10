package app.onestream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreTest {
    private fun tmp() = File.createTempFile("store", ".json").also { it.delete() }

    @Test
    fun roundTripsAcrossInstances() {
        val f = tmp()
        Store(f).apply {
            addRepo("https://a.example/index.json")
            addRepo("https://a.example/index.json") // duplicate ignored
            toggleBookmark(Bookmark("Sintel", "u1", "Blender"))
            saveProgress(HistoryEntry("Sintel", "u1", "Blender", null, "d1", null, 5000, 60000))
            acceptNotice()
        }
        val d = Store(f).data.value
        assertEquals(listOf("https://a.example/index.json"), d.repos)
        assertEquals(1, d.bookmarks.size)
        assertEquals(5000, d.history.single().positionMs)
        assertTrue(d.noticeAccepted)
    }

    @Test
    fun bookmarkToggleAndHistoryReplace() {
        val s = Store(tmp())
        val b = Bookmark("X", "u", "api")
        s.toggleBookmark(b); assertTrue(s.isBookmarked("u", "api"))
        s.toggleBookmark(b); assertFalse(s.isBookmarked("u", "api"))
        s.saveProgress(HistoryEntry("X", "u", "api", null, "d", null, 1, 10))
        s.saveProgress(HistoryEntry("X", "u", "api", null, "d", null, 9, 10))
        assertEquals(listOf(9L), s.data.value.history.map { it.positionMs })
    }

    @Test
    fun corruptFileFallsBackToEmpty() {
        val f = tmp().also { it.writeText("{not json") }
        assertEquals(StoreData(), Store(f).data.value)
    }
}