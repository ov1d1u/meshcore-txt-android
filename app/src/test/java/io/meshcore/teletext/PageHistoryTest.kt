package io.meshcore.teletext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageHistoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun restoresCachedPagesInReverseOrderWithTheirSectionPosition() {
        val first = CachedPage(100, temporaryFolder.newFile(), listOf(0L, 32L), 1, 120)
        val second = CachedPage(200, temporaryFolder.newFile(), listOf(0L), 0, 40)
        val history = PageHistory()
        history.record(first, nextPageNumber = 200)
        history.record(second, nextPageNumber = 301)

        assertEquals(second, history.pop())
        assertTrue(second.file.exists())
        assertEquals(first, history.pop())
        assertNull(history.pop())
    }

    @Test
    fun clearingHistoryDeletesPagesStillOwnedByIt() {
        val cached = CachedPage(300, temporaryFolder.newFile(), listOf(0L), 0, 0)
        val history = PageHistory()
        history.record(cached, nextPageNumber = 400)

        history.clear()

        assertFalse(cached.file.exists())
        assertNull(history.previous())
    }

    @Test
    fun refreshingTheSamePageReplacesItsFileWithoutAddingHistory() {
        val oldPage = CachedPage(100, temporaryFolder.newFile(), listOf(0L), 0, 0)
        val history = PageHistory()

        history.record(oldPage, nextPageNumber = 100)

        assertFalse(oldPage.file.exists())
        assertNull(history.previous())
    }
}
