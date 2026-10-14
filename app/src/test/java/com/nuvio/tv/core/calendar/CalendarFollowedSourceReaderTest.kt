package com.nuvio.tv.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarFollowedSourceReaderTest {
    @Test fun watchedEpisodesAreFoundByAnyIdOfTheShow() {
        val index = CalendarWatchedIndex.Builder()
            .add("tmdb:5", 1, 2)
            .addAll(mapOf("tt7" to setOf(3 to 4)))
            .add("tt8", null, 1)
            .build()
        val show = CalendarTitleRecord("series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1", tmdb = 5))
        assertTrue(index.isWatched(show, CalendarEntry(season = 1, episode = 2)))
        assertTrue(!index.isWatched(show, CalendarEntry(season = 1, episode = 3)))
        assertTrue(index.isWatched(CalendarIds(imdb = "tt7"), 3, 4))
        assertTrue(!index.isWatched(CalendarIds(imdb = "tt8"), null, 1))
        assertTrue(!index.isWatched(show, CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, season = 1, episode = 2)))
    }

    @Test fun anAddonReleasedValueKeepsOnlyItsDate() {
        assertEquals("2026-10-10", plainReleaseDate("2026-10-10T00:00:00.000Z"))
        assertEquals("2026-10-10", plainReleaseDate("2026-10-10"))
        assertEquals(null, plainReleaseDate("soon"))
    }

    @Test fun contentTypes() {
        assertEquals(CalendarTitleType.MOVIE, calendarTitleType("movie"))
        assertEquals(CalendarTitleType.SERIES, calendarTitleType("series"))
        assertEquals(CalendarTitleType.SERIES, calendarTitleType("anime"))
        assertEquals(null, calendarTitleType("channel"))
        assertEquals(CalendarIds(tmdb = 12), CalendarIds.fromContentId("tmdb:12"))
        assertEquals(CalendarIds(imdb = "tt0944947"), CalendarIds.fromContentId("tt0944947:1:2"))
        assertTrue(CalendarIds.fromContentId("kitsu:1").isEmpty)
    }
}
