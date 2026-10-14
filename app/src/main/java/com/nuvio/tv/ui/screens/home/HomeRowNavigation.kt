package com.nuvio.tv.ui.screens.home

import kotlin.math.abs

/** Preserve row identity when a focused card has transformed visual bounds. */
internal fun adjacentHomeRowIndex(current: Int, count: Int, down: Boolean): Int? {
    if (current !in 0 until count) return null
    return (current + if (down) 1 else -1).takeIf { it in 0 until count }
}

/** A card's place in its row, in the row's item coordinates (the same for every Home row). */
internal data class RowCardSpan(val index: Int, val offset: Int, val size: Int)

/**
 * The card at the same spot on screen as the focused one, so Up and Down keep the column. A row that ends
 * earlier gives its last card; a card cut off at the edge is still taken, and the row brings it in.
 */
internal fun sameColumnCardIndex(centerPx: Int, cards: List<RowCardSpan>): Int? =
    cards.minByOrNull { abs(it.offset + it.size / 2 - centerPx) }?.index

/**
 * The middle of the focused card's column: where the card comes to rest once its row stops sliding, at the
 * width of the cards next to it, so a grown poster keeps the column of the poster it was. [keepInView] rows
 * only scroll to keep the card on screen; the others bring it to the start. [roomAheadPx] and [roomBackPx] are
 * how far the row can still scroll each way.
 */
internal fun focusedColumnCenter(
    index: Int,
    cards: List<RowCardSpan>,
    visibleEndPx: Int,
    keepInView: Boolean,
    roomAheadPx: Int,
    roomBackPx: Int
): Int? {
    val card = cards.firstOrNull { it.index == index } ?: return null
    val wanted = when {
        !keepInView -> card.offset
        card.offset < 0 -> card.offset
        card.offset + card.size > visibleEndPx -> card.offset + card.size - visibleEndPx
        else -> 0
    }
    val rest = card.offset - wanted.coerceIn(-roomBackPx, roomAheadPx)
    val width = cards.filter { it.index != index }.minByOrNull { abs(it.index - index) }
        ?.let { minOf(card.size, it.size) } ?: card.size
    return rest + width / 2
}

/** The card in the spot when a row has not been laid out yet, from its scroll position and the width of a card. */
internal fun estimatedColumnIndex(centerPx: Int, firstIndex: Int, firstOffsetPx: Int, spanPx: Int): Int =
    firstIndex + Math.floorDiv(centerPx + firstOffsetPx, spanPx)

/** The row an Up or Down starts from: the one the last move is still heading to, for a short while, else focus's row. */
internal fun verticalMoveSource(activeRowKey: String?, headingRowKey: String?, headingAgeMs: Long): String? =
    headingRowKey?.takeIf { it != activeRowKey && headingAgeMs < VERTICAL_HEADING_MS } ?: activeRowKey

internal const val VERTICAL_HEADING_MS = 600L

/** The card focus enters a row on: the one the row is pointed at, kept inside a row that has since shrunk. */
internal fun rowEntryCardIndex(pointedAt: Int?, itemCount: Int): Int? =
    if (itemCount <= 0) null else (pointedAt ?: 0).coerceIn(0, itemCount - 1)
