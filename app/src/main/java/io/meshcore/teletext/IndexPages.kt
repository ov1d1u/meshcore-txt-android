package io.meshcore.teletext

data class IndexPage(val number: Int, val title: String) {
    override fun toString(): String = if (title.isEmpty()) number.toString() else "$number $title"
}

object IndexPages {
    private val link = Regex("\\[([^]]+)]\\((?:page:)?([0-9]{3})\\)")

    fun parse(lines: Sequence<String>): List<IndexPage> {
        val pages = linkedMapOf<Int, IndexPage>()
        var codeBlock = false
        for (line in lines) {
            if (line.trimStart().startsWith("```")) {
                codeBlock = !codeBlock
                continue
            }
            if (codeBlock) continue
            for (match in link.findAll(line)) {
                val number = match.groupValues[2].toInt()
                if (number !in 100..999 || number in pages) continue
                val label = match.groupValues[1].trim()
                val title = label.removePrefix(number.toString()).let { remaining ->
                    if (remaining.length == label.length ||
                        (remaining.isNotEmpty() && !remaining.first().isWhitespace())) label
                    else remaining.trim()
                }
                pages[number] = IndexPage(number, title)
            }
        }
        return pages.values.toList()
    }

    fun featured(pages: List<IndexPage>, currentPage: Int?, limit: Int = 5): List<IndexPage> {
        val available = pages.filter { it.number != INDEX_PAGE_NUMBER && it.number != currentPage }
        val mainPages = available.filter { it.number % 100 == 0 }
        val otherPages = available.filterNot { it.number % 100 == 0 }
        val index = if (currentPage == INDEX_PAGE_NUMBER) emptyList()
            else listOf(IndexPage(INDEX_PAGE_NUMBER, "Index"))
        return (index + mainPages + otherPages).take(limit)
    }
}

private const val INDEX_PAGE_NUMBER = 100
