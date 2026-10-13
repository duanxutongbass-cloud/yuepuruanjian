package com.ensemble.scorelibrary.pdf

import com.ensemble.scorelibrary.core.model.PedalAction
import com.ensemble.scorelibrary.domain.ResolvedConcert

data class ConcertPosition(val item: Int, val page: Int)

class ConcertCursor(val concert: ResolvedConcert) {
    val pages = concert.items.map { PageSequence(it.segments) }
    init {
        require(concert.items.isNotEmpty())
        require(concert.items.all { it.itemId.isNotEmpty() })
        require(concert.items.map { it.itemId }.distinct().size == concert.items.size)
    }
    fun move(position: ConcertPosition, action: PedalAction): ConcertPosition? {
        require(position.item in pages.indices && position.page in 0 until pages[position.item].count)
        return when (action) {
            PedalAction.NEXT_PAGE -> when {
                position.page + 1 < pages[position.item].count -> position.copy(page = position.page + 1)
                position.item + 1 < pages.size -> ConcertPosition(position.item + 1, 0)
                else -> null
            }
            PedalAction.PREVIOUS_PAGE -> when {
                position.page > 0 -> position.copy(page = position.page - 1)
                position.item > 0 -> ConcertPosition(position.item - 1, pages[position.item - 1].count - 1)
                else -> null
            }
            else -> position
        }
    }
}
