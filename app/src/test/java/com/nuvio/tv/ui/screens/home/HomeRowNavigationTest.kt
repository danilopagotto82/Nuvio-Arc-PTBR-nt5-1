package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

class HomeRowNavigationTest {
    @Test fun verticalNavigationCrossesRowsInsteadOfTransformedNeighbours() {
        assertEquals(1, adjacentHomeRowIndex(0, 12, true))
        assertEquals(0, adjacentHomeRowIndex(1, 12, false))
        assertEquals(9, adjacentHomeRowIndex(8, 12, true))
    }
    @Test fun boundariesAndEmptyOrRemovedRowsDoNotCreateInvalidTargets() {
        assertNull(adjacentHomeRowIndex(0, 12, false))
        assertNull(adjacentHomeRowIndex(11, 12, true))
        assertNull(adjacentHomeRowIndex(-1, 12, true))
        assertNull(adjacentHomeRowIndex(0, 0, true))
        assertNull(adjacentHomeRowIndex(12, 12, false))
    }

    private fun row(width: Int, gap: Int, first: Int, scrolledPx: Int, count: Int) =
        (first until first + count).map { RowCardSpan(it, (it - first) * (width + gap) - scrolledPx, width) }

    @Test fun upAndDownKeepTheColumnAcrossRowsWithTheSameCards() {
        // Poster 6 (index 5) of a row at the start sits over poster 6 of the row below.
        val posters = row(142, 18, 0, 0, 11)
        val center = posters[5].offset + 71
        assertEquals(5, sameColumnCardIndex(center, posters))
        // The row below is scrolled along by three posters: the poster in that spot is its ninth.
        assertEquals(8, sameColumnCardIndex(center, row(142, 18, 3, 0, 11)))
    }

    @Test fun widerCardsPickTheOneUnderTheRingAndShortRowsGiveTheirLastCard() {
        val center = 5 * 160 + 71
        // Continue watching cards are wider: the card whose middle is nearest the ring wins.
        assertEquals(2, sameColumnCardIndex(center, row(300, 18, 0, 0, 6)))
        // A row with three titles gives its last one.
        assertEquals(2, sameColumnCardIndex(center, row(142, 18, 0, 0, 3)))
    }

    @Test fun aCardCutOffAtTheEdgeIsStillTheOneInThatSpot() {
        val cards = listOf(RowCardSpan(9, 1500, 142), RowCardSpan(10, 1660, 142))
        assertEquals(10, sameColumnCardIndex(1720, cards))
        assertNull(sameColumnCardIndex(500, emptyList()))
    }

    @Test fun aGrownPosterKeepsTheColumnOfThePosterItWas() {
        // Poster 4 (index 3) has grown to 379 px; the row below lands on its poster 4, not poster 5.
        val row = row(142, 18, 0, 0, 3) + RowCardSpan(3, 480, 379) + (4..6).map { RowCardSpan(it, 480 + 397 + (it - 4) * 160, 142) }
        val column = focusedColumnCenter(3, row, 1760, keepInView = true, roomAheadPx = 2000, roomBackPx = 0)!!
        assertEquals(551, column)
        assertEquals(3, sameColumnCardIndex(column, row(142, 18, 0, 0, 11)))
    }

    @Test fun aRowStillSlidingGivesTheColumnWhereTheCardWillRest() {
        // Right moved focus to a poster still sliding in at the right edge: it will rest against the edge.
        val sliding = row(142, 18, 0, 90, 12)
        assertEquals(1760 - 71, focusedColumnCenter(11, sliding, 1760, keepInView = true, roomAheadPx = 500, roomBackPx = 90))
        // Rows that bring the focused card to the start: mid-slide it still counts as at the start.
        assertEquals(71, focusedColumnCenter(5, row(142, 18, 3, 0, 9), 1760, keepInView = false, roomAheadPx = 2000, roomBackPx = 480))
        // ...unless the row is at its end and cannot slide that far.
        assertEquals(320 - 100 + 71, focusedColumnCenter(5, row(142, 18, 3, 0, 9), 1760, keepInView = false, roomAheadPx = 100, roomBackPx = 480))
        // A card sitting still mid-screen stays where it is.
        assertEquals(871, focusedColumnCenter(5, row(142, 18, 0, 0, 11), 1760, keepInView = true, roomAheadPx = 2000, roomBackPx = 0))
    }

    @Test fun aRowNotLaidOutYetStillGetsTheCardInTheSpot() {
        // At its start: poster 6 sits under a ring centred on 871 px.
        assertEquals(5, estimatedColumnIndex(871, 0, 0, 160))
        // Scrolled to its fourth poster plus 40 px: the ninth poster is in that spot.
        assertEquals(8, estimatedColumnIndex(871, 3, 40, 160))
        // Partly scrolled past its first card at the start edge.
        assertEquals(0, estimatedColumnIndex(30, 0, 0, 160))
    }

    @Test fun aSecondPressBeforeFocusArrivesCarriesOnFromTheRowItIsHeadingTo() {
        assertEquals("row3", verticalMoveSource("row2", "row3", 120))
        // Focus has arrived: carry on from where it is.
        assertEquals("row3", verticalMoveSource("row3", "row3", 120))
        // A heading that never arrived does not linger.
        assertEquals("row2", verticalMoveSource("row2", "row3", VERTICAL_HEADING_MS))
        assertEquals("row2", verticalMoveSource("row2", null, 0))
    }

    @Test fun focusEntersARowOnTheCardItIsPointedAt() {
        // Coming up remembered poster 4, the move points it at poster 2: that is where focus goes in.
        assertEquals(1, rowEntryCardIndex(1, 8))
        // A row never visited starts at its first card.
        assertEquals(0, rowEntryCardIndex(null, 8))
        // Pointed past the end of a row that has since shrunk: its last card.
        assertEquals(0, rowEntryCardIndex(6, 1))
        assertNull(rowEntryCardIndex(0, 0))
    }
}
