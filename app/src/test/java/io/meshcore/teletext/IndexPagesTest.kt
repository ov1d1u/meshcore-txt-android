package io.meshcore.teletext

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexPagesTest {
    @Test
    fun parsesIndexLinksInOrderAndKeepsFirstTitle() {
        val markdown = """
            # Index
            - [101 Welcome](page:101)
            - [How it works](102)
            - [101 Duplicate](page:101)
            ```
            [999 Hidden example](page:999)
            ```
            - [200 News](page:200)
        """.trimIndent()

        assertEquals(
            listOf(IndexPage(101, "Welcome"), IndexPage(102, "How it works"), IndexPage(200, "News")),
            IndexPages.parse(markdown.lineSequence())
        )
    }

    @Test
    fun featuresMainPagesThenFillsFromIndexOrder() {
        val pages = listOf(101, 201, 300, 102, 200, 400, 103, 500).map { IndexPage(it, "Page $it") }

        assertEquals(listOf(100, 300, 200, 400, 500),
            IndexPages.featured(pages, currentPage = 102).map(IndexPage::number))
        assertEquals(listOf(100, 300),
            IndexPages.featured(pages, currentPage = 102, limit = 2).map(IndexPage::number))
        assertEquals(listOf(300, 200, 400, 500, 101),
            IndexPages.featured(pages, currentPage = 100).map(IndexPage::number))
        assertEquals(listOf(100, 300, 400, 500, 101),
            IndexPages.featured(pages, currentPage = 200).map(IndexPage::number))
    }
}
