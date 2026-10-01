package io.meshcore.teletext

import java.io.File

internal data class CachedPage(
    val number: Int,
    val file: File,
    val sectionOffsets: List<Long>,
    val sectionIndex: Int,
    val scrollY: Int,
)

/** Owns cached pages until they are restored or the reading session ends. */
internal class PageHistory {
    private val previousPages = ArrayDeque<CachedPage>()

    fun previous(): CachedPage? = previousPages.lastOrNull()

    fun record(previous: CachedPage, nextPageNumber: Int) {
        if (previous.number == nextPageNumber) previous.file.delete()
        else previousPages.addLast(previous)
    }

    fun pop(): CachedPage? = if (previousPages.isEmpty()) null else previousPages.removeLast()

    fun clear() {
        previousPages.forEach { it.file.delete() }
        previousPages.clear()
    }
}
